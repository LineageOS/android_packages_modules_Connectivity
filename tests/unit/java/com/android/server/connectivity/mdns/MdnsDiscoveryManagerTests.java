/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.connectivity.mdns;

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.text.TextUtils;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.Collections;

/** Tests for {@link MdnsDiscoveryManager}. */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsDiscoveryManagerTests {

    private static final String SERVICE_TYPE_1 = "_googlecast._tcp.local";
    private static final String SERVICE_TYPE_2 = "_test._tcp.local";

    @Mock private ExecutorProvider executorProvider;
    @Mock private MdnsSocketClientBase socketClient;
    @Mock private MdnsServiceTypeClient mockServiceTypeClientOne;
    @Mock private MdnsServiceTypeClient mockServiceTypeClientTwo;

    @Mock MdnsServiceBrowserListener mockListenerOne;
    @Mock MdnsServiceBrowserListener mockListenerTwo;
    private MdnsDiscoveryManager discoveryManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mockServiceTypeClientOne.getServiceTypeLabels())
                .thenReturn(TextUtils.split(SERVICE_TYPE_1, "\\."));
        when(mockServiceTypeClientTwo.getServiceTypeLabels())
                .thenReturn(TextUtils.split(SERVICE_TYPE_2, "\\."));

        discoveryManager = new MdnsDiscoveryManager(executorProvider, socketClient) {
                    @Override
                    MdnsServiceTypeClient createServiceTypeClient(@NonNull String serviceType) {
                        if (serviceType.equals(SERVICE_TYPE_1)) {
                            return mockServiceTypeClientOne;
                        } else if (serviceType.equals(SERVICE_TYPE_2)) {
                            return mockServiceTypeClientTwo;
                        }
                        return null;
                    }
                };
    }

    @Test
    public void registerListener_unregisterListener() throws IOException {
        discoveryManager.registerListener(
                SERVICE_TYPE_1, mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        verify(socketClient).startDiscovery();
        verify(mockServiceTypeClientOne)
                .startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());

        when(mockServiceTypeClientOne.stopSendAndReceive(mockListenerOne)).thenReturn(true);
        discoveryManager.unregisterListener(SERVICE_TYPE_1, mockListenerOne);
        verify(mockServiceTypeClientOne).stopSendAndReceive(mockListenerOne);
        verify(socketClient).stopDiscovery();
    }

    @Test
    public void registerMultipleListeners() throws IOException {
        discoveryManager.registerListener(
                SERVICE_TYPE_1, mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        verify(socketClient).startDiscovery();
        verify(mockServiceTypeClientOne)
                .startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());

        discoveryManager.registerListener(
                SERVICE_TYPE_2, mockListenerTwo, MdnsSearchOptions.getDefaultOptions());
        verify(mockServiceTypeClientTwo)
                .startSendAndReceive(mockListenerTwo, MdnsSearchOptions.getDefaultOptions());
    }

    @Test
    public void onResponseReceived() {
        discoveryManager.registerListener(
                SERVICE_TYPE_1, mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        discoveryManager.registerListener(
                SERVICE_TYPE_2, mockListenerTwo, MdnsSearchOptions.getDefaultOptions());

        MdnsResponse responseForServiceTypeOne = createMockResponse(SERVICE_TYPE_1);
        discoveryManager.onResponseReceived(responseForServiceTypeOne);
        verify(mockServiceTypeClientOne).processResponse(responseForServiceTypeOne);

        MdnsResponse responseForServiceTypeTwo = createMockResponse(SERVICE_TYPE_2);
        discoveryManager.onResponseReceived(responseForServiceTypeTwo);
        verify(mockServiceTypeClientTwo).processResponse(responseForServiceTypeTwo);

        MdnsResponse responseForSubtype = createMockResponse("subtype._sub._googlecast._tcp.local");
        discoveryManager.onResponseReceived(responseForSubtype);
        verify(mockServiceTypeClientOne).processResponse(responseForSubtype);
    }

    private MdnsResponse createMockResponse(String serviceType) {
        MdnsPointerRecord mockPointerRecord = mock(MdnsPointerRecord.class);
        MdnsResponse mockResponse = mock(MdnsResponse.class);
        when(mockResponse.getPointerRecords())
                .thenReturn(Collections.singletonList(mockPointerRecord));
        when(mockPointerRecord.getName()).thenReturn(TextUtils.split(serviceType, "\\."));
        return mockResponse;
    }
}