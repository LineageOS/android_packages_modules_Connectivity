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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.text.TextUtils;
import android.util.Pair;

import com.android.server.connectivity.mdns.MdnsSocketClientBase.SocketCreationCallback;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Tests for {@link MdnsDiscoveryManager}. */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsDiscoveryManagerTests {

    private static final String SERVICE_TYPE_1 = "_googlecast._tcp.local";
    private static final String SERVICE_TYPE_2 = "_test._tcp.local";
    private static final Pair<String, Network> PER_NETWORK_SERVICE_TYPE_1 =
            Pair.create(SERVICE_TYPE_1, null);
    private static final Pair<String, Network> PER_NETWORK_SERVICE_TYPE_2 =
            Pair.create(SERVICE_TYPE_2, null);

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
                    MdnsServiceTypeClient createServiceTypeClient(@NonNull String serviceType,
                            @Nullable Network network) {
                        final Pair<String, Network> perNetworkServiceType =
                                Pair.create(serviceType, network);
                        if (perNetworkServiceType.equals(PER_NETWORK_SERVICE_TYPE_1)) {
                            return mockServiceTypeClientOne;
                        } else if (perNetworkServiceType.equals(PER_NETWORK_SERVICE_TYPE_2)) {
                            return mockServiceTypeClientTwo;
                        }
                        return null;
                    }
                };
    }

    private void verifyListenerRegistration(String serviceType, MdnsServiceBrowserListener listener,
            MdnsServiceTypeClient client) throws IOException {
        final ArgumentCaptor<SocketCreationCallback> callbackCaptor =
                ArgumentCaptor.forClass(SocketCreationCallback.class);
        discoveryManager.registerListener(serviceType, listener,
                MdnsSearchOptions.getDefaultOptions());
        verify(socketClient).startDiscovery();
        verify(socketClient).notifyNetworkRequested(
                eq(listener), any(), callbackCaptor.capture());
        final SocketCreationCallback callback = callbackCaptor.getValue();
        callback.onSocketCreated(null /* network */);
        verify(client).startSendAndReceive(listener, MdnsSearchOptions.getDefaultOptions());
    }

    @Test
    public void registerListener_unregisterListener() throws IOException {
        verifyListenerRegistration(SERVICE_TYPE_1, mockListenerOne, mockServiceTypeClientOne);

        when(mockServiceTypeClientOne.stopSendAndReceive(mockListenerOne)).thenReturn(true);
        discoveryManager.unregisterListener(SERVICE_TYPE_1, mockListenerOne);
        verify(mockServiceTypeClientOne).stopSendAndReceive(mockListenerOne);
        verify(socketClient).stopDiscovery();
    }

    @Test
    public void registerMultipleListeners() throws IOException {
        verifyListenerRegistration(SERVICE_TYPE_1, mockListenerOne, mockServiceTypeClientOne);
        verifyListenerRegistration(SERVICE_TYPE_2, mockListenerTwo, mockServiceTypeClientTwo);
    }

    @Test
    public void onResponseReceived() throws IOException {
        verifyListenerRegistration(SERVICE_TYPE_1, mockListenerOne, mockServiceTypeClientOne);
        verifyListenerRegistration(SERVICE_TYPE_2, mockListenerTwo, mockServiceTypeClientTwo);

        MdnsPacket responseForServiceTypeOne = createMdnsPacket(SERVICE_TYPE_1);
        final int ifIndex = 1;
        discoveryManager.onResponseReceived(responseForServiceTypeOne, ifIndex, null /* network */);
        verify(mockServiceTypeClientOne).processResponse(responseForServiceTypeOne, ifIndex,
                null /* network */);

        MdnsPacket responseForServiceTypeTwo = createMdnsPacket(SERVICE_TYPE_2);
        discoveryManager.onResponseReceived(responseForServiceTypeTwo, ifIndex, null /* network */);
        verify(mockServiceTypeClientTwo).processResponse(responseForServiceTypeTwo, ifIndex,
                null /* network */);

        MdnsPacket responseForSubtype = createMdnsPacket("subtype._sub._googlecast._tcp.local");
        discoveryManager.onResponseReceived(responseForSubtype, ifIndex, null /* network */);
        verify(mockServiceTypeClientOne).processResponse(responseForSubtype, ifIndex,
                null /* network */);
    }

    private MdnsPacket createMdnsPacket(String serviceType) {
        final String[] type = TextUtils.split(serviceType, "\\.");
        final ArrayList<String> name = new ArrayList<>(type.length + 1);
        name.add("TestName");
        name.addAll(Arrays.asList(type));
        return new MdnsPacket(0 /* flags */,
                Collections.emptyList() /* questions */,
                List.of(new MdnsPointerRecord(
                        type,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        120000 /* ttlMillis */,
                        name.toArray(new String[0])
                        )) /* answers */,
                Collections.emptyList() /* authorityRecords */,
                Collections.emptyList() /* additionalRecords */);
    }
}