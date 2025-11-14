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

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkRequest
import android.os.Build
import com.android.server.connectivity.NetworkAgentInfo
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.util.Collections
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_UID = 1234

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class CSNetworkRequestInfoTest : CSTest() {
    /**
     * Helper function to create a NetworkRequestInfo for internal system usage.
     * This simplifies NRI creation within the test.
     */
    private fun createNri(request: NetworkRequest): ConnectivityService.NetworkRequestInfo {
        // This uses the internal constructor to create an NRI as the system would.
        return service.NetworkRequestInfo(
                TEST_UID,
                Collections.singletonList(request),
                0 // preferenceOrder
        )
    }

    @Test
    fun testGetSatisfiedTime() {
        // Setup: Create a basic NetworkRequestInfo and mock a network.
        val t0 = 50L
        deps.elapsedRealtime = t0
        val capabilities = NetworkCapabilities.Builder()
                .addCapability(NET_CAPABILITY_INTERNET)
                .build()
        val request = NetworkRequest(
                capabilities,
                ConnectivityManager.TYPE_NONE,
                1 /* requestId */,
                NetworkRequest.Type.REQUEST
        )
        val nri = createNri(request)
        val nai = mock<NetworkAgentInfo>()

        // Initially, satisfiedTime should be the created time, which is the "null" start
        // to satisfying the request.
        assertEquals(t0, nri.satisfiedTime)

        // When a network satisfies the request at t=100, verify the satisfiedTime updates.
        val t1 = 100L
        deps.elapsedRealtime = t1
        nri.setSatisfier(nai, request)
        assertEquals(t1, nri.satisfiedTime)

        // When the request becomes unsatisfied at t=250, verify the satisfiedTime updates.
        val t2 = 250L
        deps.elapsedRealtime = t2
        nri.setSatisfier(null, null)
        assertEquals(t2, nri.satisfiedTime)
    }

    @Test
    fun testCopyConstructorPreservesSatisfiedTime() {
        val capabilities = NetworkCapabilities.Builder()
                .addCapability(NET_CAPABILITY_INTERNET)
                .build()
        val request = NetworkRequest(
                capabilities,
                ConnectivityManager.TYPE_NONE,
                1 /* requestId */,
                NetworkRequest.Type.REQUEST
        )
        val t1 = 100L
        deps.elapsedRealtime = t1
        val originalNri = createNri(request)

        // Change time, and verify it is not used by copy constructor.
        val t2 = 12345L
        deps.elapsedRealtime = t2
        val newNri = service.NetworkRequestInfo(originalNri, Collections.singletonList(request))
        assertEquals(t1, newNri.satisfiedTime)
    }
}
