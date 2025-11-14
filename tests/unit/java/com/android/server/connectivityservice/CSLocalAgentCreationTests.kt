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

package com.android.server

import android.content.pm.PackageManager.FEATURE_LEANBACK
import android.net.INetd
import android.net.NativeNetworkConfig
import android.net.NativeNetworkType
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.net.NetworkRequest
import android.net.VpnManager
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Available
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout

private const val TIMEOUT_MS = 2_000L
private const val NO_CALLBACK_TIMEOUT_MS = 200L

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSLocalAgentCreationTests : CSTest() {
    @Parameterized.Parameter(0) lateinit var params: TestParams

    data class TestParams(
            val sdkLevel: Int,
            val isTv: Boolean = false,
            val addLocalNetCapToRequest: Boolean = true
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun arguments() = listOf(
                TestParams(VERSION_V, isTv = false, addLocalNetCapToRequest = true),
                TestParams(VERSION_V, isTv = false, addLocalNetCapToRequest = false),
                TestParams(VERSION_V, isTv = true, addLocalNetCapToRequest = true),
                TestParams(VERSION_V, isTv = true, addLocalNetCapToRequest = false),
                TestParams(VERSION_U, isTv = false, addLocalNetCapToRequest = true),
                TestParams(VERSION_U, isTv = false, addLocalNetCapToRequest = false),
                TestParams(VERSION_U, isTv = true, addLocalNetCapToRequest = true),
                TestParams(VERSION_U, isTv = true, addLocalNetCapToRequest = false),
                TestParams(VERSION_T, isTv = false, addLocalNetCapToRequest = false),
                TestParams(VERSION_T, isTv = true, addLocalNetCapToRequest = false),
        )
    }

    private fun makeNativeNetworkConfigLocal(netId: Int, permission: Int) =
            NativeNetworkConfig(
                    netId,
                    NativeNetworkType.PHYSICAL_LOCAL,
                    permission,
                    false /* secure */,
                    VpnManager.TYPE_VPN_NONE,
                    false /* excludeLocalRoutes */
            )

    @Test
    fun testLocalAgents() {
        val netdInOrder = inOrder(netd)
        deps.setBuildSdk(params.sdkLevel)
        doReturn(params.isTv).`when`(packageManager).hasSystemFeature(FEATURE_LEANBACK)
        val allNetworksCb = TestableNetworkCallback()
        val request = NetworkRequest.Builder()
        if (params.addLocalNetCapToRequest) {
            request.addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }
        cm.registerNetworkCallback(request.build(), allNetworksCb)
        val ncTemplate = NetworkCapabilities.Builder().run {
            addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
            addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }.build()
        val localAgent = if (params.sdkLevel >= VERSION_V ||
                params.sdkLevel == VERSION_U && params.isTv) {
            Agent(nc = ncTemplate, score = keepConnectedScore(), lnc = defaultLnc())
        } else {
            assertFailsWith<IllegalArgumentException> { Agent(nc = ncTemplate, lnc = defaultLnc()) }
            netdInOrder.verify(netd, never()).networkCreate(any())
            return
        }
        localAgent.connect()
        netdInOrder.verify(netd).networkCreate(
                makeNativeNetworkConfigLocal(localAgent.network.netId, INetd.PERMISSION_NONE)
        )
        if (params.addLocalNetCapToRequest) {
            assertEquals(localAgent.network, allNetworksCb.expect<Available>().network)
        } else {
            allNetworksCb.assertNoCallback(NO_CALLBACK_TIMEOUT_MS)
        }
        cm.unregisterNetworkCallback(allNetworksCb)
        localAgent.disconnect()
        netdInOrder.verify(netd, timeout(TIMEOUT_MS)).networkDestroy(localAgent.network.netId)
    }

    @Test
    fun testBadAgents() {
        assertFailsWith<IllegalArgumentException> {
            Agent(
                    nc = NetworkCapabilities.Builder()
                            .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                            .build(),
                    lnc = null
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder().build(), lnc = defaultLnc())
        }
    }
}
