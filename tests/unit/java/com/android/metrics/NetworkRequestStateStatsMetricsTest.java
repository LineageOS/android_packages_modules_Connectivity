/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.metrics;


import static com.android.server.ConnectivityStatsLog.NETWORK_REQUEST_STATE_CHANGED__STATE__NETWORK_REQUEST_STATE_RECEIVED;
import static com.android.server.ConnectivityStatsLog.NETWORK_REQUEST_STATE_CHANGED__STATE__NETWORK_REQUEST_STATE_REMOVED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.HandlerThread;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.testutils.HandlerUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class NetworkRequestStateStatsMetricsTest {
    @Mock
    private NetworkRequestStateStatsMetrics.Dependencies mNRStateStatsDeps;
    @Mock
    private NetworkRequestStateInfo.Dependencies mNRStateInfoDeps;
    @Captor
    private ArgumentCaptor<NetworkRequestStateInfo> mNetworkRequestStateInfoCaptor;
    private NetworkRequestStateStatsMetrics mNetworkRequestStateStatsMetrics;
    private HandlerThread mHandlerThread;
    private static final int TEST_REQUEST_ID = 10;
    private static final int TEST_PACKAGE_UID = 20;
    private static final int TIMEOUT_MS = 30_000;
    private static final NetworkRequest NOT_METERED_WIFI_NETWORK_REQUEST = new NetworkRequest(
            new NetworkCapabilities()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, true)
                    .setCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET, false)
                    .setRequestorUid(TEST_PACKAGE_UID),
            0, TEST_REQUEST_ID, NetworkRequest.Type.REQUEST
    );

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mHandlerThread = new HandlerThread("NetworkRequestStateStatsMetrics");
        Mockito.when(mNRStateStatsDeps.makeHandlerThread("NetworkRequestStateStatsMetrics"))
                .thenReturn(mHandlerThread);
        mNetworkRequestStateStatsMetrics = new NetworkRequestStateStatsMetrics(
                mNRStateStatsDeps, mNRStateInfoDeps);
    }

    @Test
    public void testNetworkRequestReceivedRemoved() {
        final long nrStartTime = 1L;
        final long nrEndTime = 101L;
        // This call will be used to calculate NR received time
        Mockito.when(mNRStateInfoDeps.getElapsedRealtime()).thenReturn(nrStartTime);
        mNetworkRequestStateStatsMetrics.onNetworkRequestReceived(NOT_METERED_WIFI_NETWORK_REQUEST);
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);

        verify(mNRStateStatsDeps, times(1))
                .writeStats(mNetworkRequestStateInfoCaptor.capture());

        NetworkRequestStateInfo nrStateInfoSent = mNetworkRequestStateInfoCaptor.getValue();
        assertEquals(NETWORK_REQUEST_STATE_CHANGED__STATE__NETWORK_REQUEST_STATE_RECEIVED,
                nrStateInfoSent.getNetworkRequestStateStatsType());
        assertEquals(NOT_METERED_WIFI_NETWORK_REQUEST.requestId, nrStateInfoSent.getRequestId());
        assertEquals(TEST_PACKAGE_UID, nrStateInfoSent.getPackageUid());
        assertEquals(1 << NetworkCapabilities.TRANSPORT_WIFI, nrStateInfoSent.getTransportTypes());
        assertTrue(nrStateInfoSent.getNetCapabilityNotMetered());
        assertFalse(nrStateInfoSent.getNetCapabilityInternet());
        assertEquals(0, nrStateInfoSent.getNetworkRequestDurationMillis());

        clearInvocations(mNRStateStatsDeps);
        // This call will be used to calculate NR removed time
        Mockito.when(mNRStateInfoDeps.getElapsedRealtime()).thenReturn(nrEndTime);
        mNetworkRequestStateStatsMetrics.onNetworkRequestRemoved(NOT_METERED_WIFI_NETWORK_REQUEST);
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);

        verify(mNRStateStatsDeps, times(1))
                .writeStats(mNetworkRequestStateInfoCaptor.capture());

        nrStateInfoSent = mNetworkRequestStateInfoCaptor.getValue();
        assertEquals(NETWORK_REQUEST_STATE_CHANGED__STATE__NETWORK_REQUEST_STATE_REMOVED,
                nrStateInfoSent.getNetworkRequestStateStatsType());
        assertEquals(NOT_METERED_WIFI_NETWORK_REQUEST.requestId, nrStateInfoSent.getRequestId());
        assertEquals(TEST_PACKAGE_UID, nrStateInfoSent.getPackageUid());
        assertEquals(1 << NetworkCapabilities.TRANSPORT_WIFI, nrStateInfoSent.getTransportTypes());
        assertTrue(nrStateInfoSent.getNetCapabilityNotMetered());
        assertFalse(nrStateInfoSent.getNetCapabilityInternet());
        assertEquals(nrEndTime - nrStartTime, nrStateInfoSent.getNetworkRequestDurationMillis());
    }

    @Test
    public void testUnreceivedNetworkRequestRemoved() {
        mNetworkRequestStateStatsMetrics.onNetworkRequestRemoved(NOT_METERED_WIFI_NETWORK_REQUEST);
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);
        verify(mNRStateStatsDeps, never())
                .writeStats(any(NetworkRequestStateInfo.class));
    }

    @Test
    public void testExistingNetworkRequestReceived() {
        mNetworkRequestStateStatsMetrics.onNetworkRequestReceived(NOT_METERED_WIFI_NETWORK_REQUEST);
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);
        verify(mNRStateStatsDeps, times(1))
                .writeStats(any(NetworkRequestStateInfo.class));

        clearInvocations(mNRStateStatsDeps);
        mNetworkRequestStateStatsMetrics.onNetworkRequestReceived(NOT_METERED_WIFI_NETWORK_REQUEST);
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);
        verify(mNRStateStatsDeps, never())
                .writeStats(any(NetworkRequestStateInfo.class));

    }
}
