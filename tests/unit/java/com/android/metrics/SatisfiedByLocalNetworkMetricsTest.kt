/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.metrics

import android.app.StatsManager
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.NetworkRequest.Type
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.StatsEvent
import com.android.net.module.util.FrameworkConnectivityStatsLog.SATISFIED_BY_LOCAL_NETWORK_REQUESTS
import com.android.server.any
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.postAndWait
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * Unit tests for {@link SatisfiedByLocalNetworkMetrics}.
 */
// Local network is supported until U.
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
class SatisfiedByLocalNetworkMetricsTest {
    companion object {
        private const val TEST_UID_1 = 10001
        private const val TEST_UID_2 = 10002
        private const val TIMEOUT_MS = 2000

        // Simulate caps which cannot be satisfied by non-thread local
        // networks.
        private val INTERNET_CAPS = NetworkCapabilities.Builder()
                .addCapability(NET_CAPABILITY_INTERNET).build()

        // Simulate caps which can potentially be satisfied by non-thread
        // local networks.
        // Note that requests built by NetworkRequest normally have deduced NOT_VCN_MANAGED.
        private val WIFI_CAPS = NetworkCapabilities.Builder()
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .addCapability(NET_CAPABILITY_NOT_METERED)
                .addTransportType(TRANSPORT_WIFI)
                .build()
    }

    // Spy the Dependencies object to inspect content of buildStatsEvent.
    // Because StatsEvent has no getter to inspect the content.
    private val deps = spy(SatisfiedByLocalNetworkMetrics.Dependencies())
    private val mockStatsManager = mock(StatsManager::class.java)
    private val mockContext = mock(Context::class.java).also {
        doReturn(mockStatsManager).`when`(it).getSystemService(StatsManager::class.java)
    }
    private val handlerThread = HandlerThread("SatisfiedByLocalNetworkMetricsTestThread").also {
        it.start()
    }
    private val testHandler = Handler(handlerThread.looper)
    private val metrics = SatisfiedByLocalNetworkMetrics(mockContext, testHandler, deps)

    @After
    fun tearDown() {
        handlerThread.quitSafely()
        handlerThread.join(TIMEOUT_MS.toLong())
    }

    @Test
    fun testLogRequest_singleStats() {
        val testRequest = createTestRequest(Type.REQUEST, WIFI_CAPS)

        metrics.logRequest(testRequest, TEST_UID_1)

        // Now, pull the atom and verify the stats event
        val data = mutableListOf<StatsEvent>()
        val result = testHandler.postAndWait {
            metrics.onPullAtom(SATISFIED_BY_LOCAL_NETWORK_REQUESTS, data)
        }

        assertEquals(StatsManager.PULL_SUCCESS, result)
        assertEquals(1, data.size)
        verifyStatsEventBuilt(deps, TEST_UID_1, Type.REQUEST, 1)
    }

    @Test
    fun testLogRequest_doesNotReportWhenNotSatisfied() {
        val testRequest = createTestRequest(Type.REQUEST, INTERNET_CAPS)

        metrics.logRequest(testRequest, TEST_UID_1)

        // Pull the atom; no events should be generated
        val data = mutableListOf<StatsEvent>()
        val result = testHandler.postAndWait {
            metrics.onPullAtom(SATISFIED_BY_LOCAL_NETWORK_REQUESTS, data)
        }

        assertEquals(StatsManager.PULL_SUCCESS, result) // PULL_SUCCESS even if no data
        assertTrue(data.isEmpty()) // No stats events should be added
    }

    @Test
    fun testLogRequest_sameUidAndRequestType() {
        val testRequest = createTestRequest(Type.REQUEST, WIFI_CAPS)

        metrics.logRequest(testRequest, TEST_UID_1) // Count 1
        metrics.logRequest(testRequest, TEST_UID_1) // Count 2
        metrics.logRequest(testRequest, TEST_UID_1) // Count 3

        val data = mutableListOf<StatsEvent>()
        testHandler.postAndWait {
            metrics.onPullAtom(SATISFIED_BY_LOCAL_NETWORK_REQUESTS, data)
        }

        assertEquals(1, data.size)
        verifyStatsEventBuilt(deps, TEST_UID_1, Type.REQUEST, 3)
    }

    private fun createTestRequest(reqType: Type, nc: NetworkCapabilities) =
            NetworkRequest(nc, 0, 0, reqType)

    @Test
    fun testLogRequest_differentUidsAndRequestTypes() {
        val testRequest1 = createTestRequest(Type.REQUEST, WIFI_CAPS)
        val testRequest2 = createTestRequest(Type.LISTEN, WIFI_CAPS)

        metrics.logRequest(testRequest1, TEST_UID_1) // Type.REQUEST, UID_1, count 1
        metrics.logRequest(testRequest1, TEST_UID_1) // Type.REQUEST, UID_1, count 2
        metrics.logRequest(testRequest2, TEST_UID_1) // Type.LISTEN, UID_1, count 1
        metrics.logRequest(testRequest1, TEST_UID_2) // Type.REQUEST, UID_2, count 1
        metrics.logRequest(testRequest2, TEST_UID_2) // Type.LISTEN, UID_2, count 1
        metrics.logRequest(testRequest2, TEST_UID_2) // Type.LISTEN, UID_2, count 2

        val data = mutableListOf<StatsEvent>()
        testHandler.postAndWait {
            metrics.onPullAtom(SATISFIED_BY_LOCAL_NETWORK_REQUESTS, data)
        }

        assertEquals(4, data.size)
        verifyStatsEventBuilt(deps, TEST_UID_1, Type.REQUEST, 2)
        verifyStatsEventBuilt(deps, TEST_UID_1, Type.LISTEN, 1)
        verifyStatsEventBuilt(deps, TEST_UID_2, Type.REQUEST, 1)
        verifyStatsEventBuilt(deps, TEST_UID_2, Type.LISTEN, 2)
    }

    @Test
    fun testPullAtom_clearsCounters() {
        val testRequest = createTestRequest(Type.REQUEST, WIFI_CAPS)

        // Log one request.
        metrics.logRequest(testRequest, TEST_UID_1)

        // First pull: should contain one event.
        val data1 = mutableListOf<StatsEvent>()
        val result1 = testHandler.postAndWait {
            metrics.onPullAtom(SATISFIED_BY_LOCAL_NETWORK_REQUESTS, data1)
        }

        assertEquals(StatsManager.PULL_SUCCESS, result1)
        assertEquals(1, data1.size)
        verifyStatsEventBuilt(deps, TEST_UID_1, Type.REQUEST, 1)

        // Second pull: should contain no events, as counters were cleared.
        val data2 = mutableListOf<StatsEvent>()
        val result2 = testHandler.postAndWait {
            metrics.onPullAtom(SATISFIED_BY_LOCAL_NETWORK_REQUESTS, data2)
        }

        assertEquals(StatsManager.PULL_SUCCESS, result2)
        assertTrue(data2.isEmpty(), "Counters should be cleared after a pull, but found data.")
    }

    @Test
    fun testWrongAtomTag() {
        val data = mutableListOf<StatsEvent>()
        val result = testHandler.postAndWait {
            metrics.onPullAtom(12345, data) // A different tag
        }

        assertEquals(StatsManager.PULL_SKIP, result)
        assertTrue(data.isEmpty()) // No stats events should be added
        verify(deps, never()).buildStatsEvent(any(), any(), any())
    }

    /**
     * Helper function to verify that `buildStatsEvent` was called on the mocked dependencies
     * with the specified UID, NetworkRequest.Type, and count.
     * This makes it explicit what kind of StatsEvent is expected to be built.
     */
    private fun verifyStatsEventBuilt(
            deps: SatisfiedByLocalNetworkMetrics.Dependencies,
            expectedUid: Int,
            expectedRequestType: Type,
            expectedCount: Int
    ) {
        verify(deps, times(1)).buildStatsEvent(
                eq(expectedUid),
                eq(expectedRequestType),
                eq(expectedCount)
        )
    }
}
