/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server

import android.net.INetd
import android.net.Network
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.UidRange
import android.os.Build
import com.android.server.ConnectivityService.PREFERENCE_ORDER_SATELLITE_FALLBACK
import com.android.server.ConnectivityStatsLog.DEFAULT_NETWORK_REMATCH__REMATCH_REASON__RMR_NETWORK_DISCONNECTED
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.postAndWait
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.InOrder
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify

private const val DEFAULT_REQUEST_ID = 1
private const val TEST_UID = 1234
private const val TEST_UID2 = 5678

// Timestamp constants. They need to be chosen to prevent a difference between
// any two of them from being equal to a difference between another two
// (or more) of them. This ensures that wrong duration calculations won't pass
// the tests accidentally.
private const val t0 = 0L
private const val t1 = 11L
private const val t2 = 23L
private const val t3 = 38L
private const val t4 = 56L

@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
@RunWith(DevSdkIgnoreRunner::class)
class CSDefaultNetworkRematchMetricsTest : CSTest() {
    companion object {
        private val NO_SERVICE_NETWORK = Network(INetd.UNREACHABLE_NET_ID)
    }

    @Test
    fun testRematchWritesStats() {
        val inOrder = inOrder(defaultNetworkRematchMetrics)

        // 1. Connect a cellular network. It becomes the default.
        deps.elapsedRealtime = t1
        val naiCell = Agent(nc = nc(TRANSPORT_CELLULAR, NET_CAPABILITY_INTERNET))
        naiCell.connect(true)
        expectAddEvent(inOrder, DEFAULT_REQUEST_ID, null, naiCell.network, t1 - t0)
        inOrder.verify(defaultNetworkRematchMetrics, atLeastOnce()).writeStatsAndClear()

        // 2. Connect a WiFi network. It has a higher score and will cause a rematch.
        deps.elapsedRealtime = t2
        val naiWifi = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        naiWifi.connect(true)

        // 3. Verify: The rematch should trigger writing the stats.
        expectAddEvent(inOrder, DEFAULT_REQUEST_ID, naiCell.network, naiWifi.network, t2 - t1)
        inOrder.verify(defaultNetworkRematchMetrics, atLeastOnce()).writeStatsAndClear()
        inOrder.verifyNoMoreInteractions()
    }

    @Test
    fun testDisconnectWritesStats() {
        val inOrder = inOrder(defaultNetworkRematchMetrics)

        // 1. Connect a cellular network. It becomes the default.
        deps.elapsedRealtime = t1
        val naiCell = Agent(nc = nc(TRANSPORT_CELLULAR, NET_CAPABILITY_INTERNET))
        naiCell.connect(true)
        expectAddEvent(inOrder, DEFAULT_REQUEST_ID, null, naiCell.network, t1 - t0)
        inOrder.verify(defaultNetworkRematchMetrics).writeStatsAndClear()
        inOrder.verifyNoMoreInteractions()

        // 2. Disconnect the cellular network.
        deps.elapsedRealtime = t2
        naiCell.disconnect()
        waitForIdle()

        // 3. Verify: Disconnecting the default network should trigger writing the stats.
        expectAddEvent(inOrder, DEFAULT_REQUEST_ID, naiCell.network, null, t2 - t1)
        inOrder.verify(defaultNetworkRematchMetrics).writeStatsAndClear(
                DEFAULT_NETWORK_REMATCH__REMATCH_REASON__RMR_NETWORK_DISCONNECTED
        )
        // Caused by rematch.
        inOrder.verify(defaultNetworkRematchMetrics).writeStatsAndClear()
        inOrder.verifyNoMoreInteractions()
    }

    private fun expectAddEvent(
            inOrder: InOrder,
            requestId: Int,
            oldNetwork: Network?,
            newNetwork: Network?,
            satisfiedDurationMs: Long
    ) {
        inOrder.verify(defaultNetworkRematchMetrics).addEvent(
                argThat { it.mRequests.firstOrNull()?.requestId == requestId },
                argThat { it?.network == oldNetwork },
                argThat { it?.network == newNetwork },
                eq(satisfiedDurationMs)
        )
    }

    private fun expectAddSatelliteEvent(
            inOrder: InOrder,
            expectedUid: Int,
            oldNetwork: Network?,
            newNetwork: Network?,
            expectedSatisfiedDuration: Long
    ) {
        inOrder.verify(defaultNetworkRematchMetrics).addEvent(
                argThat {
                    it.preferenceOrderForNetd == PREFERENCE_ORDER_SATELLITE_FALLBACK &&
                            it.uids.contains(UidRange(expectedUid, expectedUid))
                },
                argThat { it?.network == oldNetwork },
                argThat { it?.network == newNetwork },
                eq(expectedSatisfiedDuration)
        )
    }

    private fun updateSatelliteNetworkFallbackUids(messagingUids: Set<Int>, optinUids: Set<Int>) {
        csHandler.postAndWait {
            deps.satelliteNetworkFallbackUidUpdate!!.accept(messagingUids, optinUids)
        }
    }

    @Test
    fun testReplaceSatelliteRequestWritesStats() {
        val inOrder = inOrder(defaultNetworkRematchMetrics)

        // Trigger CS to add the SATELLITE_FALLBACK multilayer network request.
        // Only an opt-in UID multilayer request is created since there is no
        // role-sms UID.
        // The satellite request is created and immediately satisfied by NO_SERVICE_NETWORK
        // if it rematches when no network is available.
        deps.elapsedRealtime = t1
        updateSatelliteNetworkFallbackUids(setOf(), setOf(TEST_UID))
        // The request created and rematched immediately so it satisfied by null with no time.
        expectAddSatelliteEvent(inOrder, TEST_UID, null, NO_SERVICE_NETWORK, 0)

        // Connect a cellular network. Expect an event indicating the NO_SERVICE_NETWORK
        // was satisfied for the duration t2 - t1.
        deps.elapsedRealtime = t2
        val naiCell = Agent(nc = nc(TRANSPORT_CELLULAR, NET_CAPABILITY_INTERNET))
        naiCell.connect(true)
        expectAddEvent(inOrder, DEFAULT_REQUEST_ID, null, naiCell.network, t2 - t0)
        expectAddSatelliteEvent(inOrder, TEST_UID, NO_SERVICE_NETWORK, naiCell.network, t2 - t1)
        verify(defaultNetworkRematchMetrics, atLeastOnce()).writeStatsAndClear()

        // Make a UID list update to trigger the multilayer requests replacement:
        //  1. Remove opt-in UID request for TEST_UID.
        //  2. Create opt-in UID request for TEST_UID + TEST_UID2.
        deps.elapsedRealtime = t3
        updateSatelliteNetworkFallbackUids(setOf(), setOf(TEST_UID, TEST_UID2))
        // The removed request reports nothing.
        // TODO: Consider reports another event when the request is being removed.
        //  Uncomment expectAddSatelliteEvent(inOrder, TEST_UID, naiCell.network, null, t3 - t2)
        //  Or alternatively copy the satisfiedTime from the removed requests.
        // The just created request satisfied by cell immediately.
        expectAddSatelliteEvent(inOrder, TEST_UID, null, naiCell.network, 0)
        inOrder.verify(defaultNetworkRematchMetrics, atLeastOnce()).writeStatsAndClear()
        inOrder.verifyNoMoreInteractions()

        deps.elapsedRealtime = t4
        naiCell.disconnect()
        // Wait for idle is needed to keep this test stable after disconnection because
        // networkCallback<LOST> fired before addEvent.
        waitForIdle()
        // Verify: Disconnecting the default network should trigger writing the stats.
        // The device reports cell satisfied for t4 - t3, with a transition null -> no service.
        // This is because the original request is removed when updating the uid list and the
        // duration is only calculated for the newly created request at t3.
        expectAddEvent(inOrder, DEFAULT_REQUEST_ID, naiCell.network, null, t4 - t2)
        expectAddSatelliteEvent(inOrder, TEST_UID, naiCell.network, null, t4 - t3)
        inOrder.verify(defaultNetworkRematchMetrics).writeStatsAndClear(
                DEFAULT_NETWORK_REMATCH__REMATCH_REASON__RMR_NETWORK_DISCONNECTED
        )
        // Caused by rematch.
        expectAddSatelliteEvent(inOrder, TEST_UID, null, NO_SERVICE_NETWORK, 0)
        inOrder.verify(defaultNetworkRematchMetrics).writeStatsAndClear()
        inOrder.verifyNoMoreInteractions()
    }
}
