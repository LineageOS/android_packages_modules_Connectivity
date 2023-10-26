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

import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.LocalNetworkConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.RouteInfo
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.BlockedStatus
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import com.android.testutils.TestableNetworkCallback
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFailsWith

private fun nc(transport: Int, vararg caps: Int) = NetworkCapabilities.Builder().apply {
    addTransportType(transport)
    caps.forEach {
        addCapability(it)
    }
    // Useful capabilities for everybody
    addCapability(NET_CAPABILITY_NOT_RESTRICTED)
    addCapability(NET_CAPABILITY_NOT_SUSPENDED)
    addCapability(NET_CAPABILITY_NOT_ROAMING)
    addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
}.build()

private fun lp(iface: String) = LinkProperties().apply {
    interfaceName = iface
    addLinkAddress(LinkAddress(LOCAL_IPV4_ADDRESS, 32))
    addRoute(RouteInfo(IpPrefix("0.0.0.0/0"), null, null))
}

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class CSLocalAgentTests : CSTest() {
    @Test
    fun testBadAgents() {
        deps.setBuildSdk(VERSION_V)

        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder()
                    .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                    .build(),
                    lnc = null)
        }
        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder().build(),
                    lnc = LocalNetworkConfig.Builder().build())
        }
    }

    @Test
    fun testStructuralConstraintViolation() {
        deps.setBuildSdk(VERSION_V)

        val cb = TestableNetworkCallback()
        cm.requestNetwork(NetworkRequest.Builder()
                .clearCapabilities()
                .build(),
                cb)
        val agent = Agent(nc = NetworkCapabilities.Builder()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .build(),
                lnc = LocalNetworkConfig.Builder().build())
        agent.connect()
        cb.expect<Available>(agent.network)
        cb.expect<CapabilitiesChanged>(agent.network)
        cb.expect<LinkPropertiesChanged>(agent.network)
        cb.expect<BlockedStatus>(agent.network)
        agent.sendNetworkCapabilities(NetworkCapabilities.Builder().build())
        cb.expect<Lost>(agent.network)

        val agent2 = Agent(nc = NetworkCapabilities.Builder()
                .build(),
                lnc = null)
        agent2.connect()
        cb.expect<Available>(agent2.network)
        cb.expect<CapabilitiesChanged>(agent2.network)
        cb.expect<LinkPropertiesChanged>(agent2.network)
        cb.expect<BlockedStatus>(agent2.network)
        agent2.sendNetworkCapabilities(NetworkCapabilities.Builder()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .build())
        cb.expect<Lost>(agent2.network)
    }

    @Test
    fun testUpdateLocalAgentConfig() {
        deps.setBuildSdk(VERSION_V)

        val cb = TestableNetworkCallback()
        cm.requestNetwork(NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .build(),
                cb)

        // Set up a local agent that should forward its traffic to the best DUN upstream.
        val localAgent = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = LocalNetworkConfig.Builder().build(),
        )
        localAgent.connect()

        cb.expect<Available>(localAgent.network)
        cb.expect<CapabilitiesChanged>(localAgent.network)
        cb.expect<LinkPropertiesChanged>(localAgent.network)
        cb.expect<BlockedStatus>(localAgent.network)

        val newLnc = LocalNetworkConfig.Builder()
                .setUpstreamSelector(NetworkRequest.Builder()
                        .addTransportType(TRANSPORT_WIFI)
                        .build())
                .build()
        localAgent.sendLocalNetworkConfig(newLnc)

        localAgent.disconnect()
    }
}
