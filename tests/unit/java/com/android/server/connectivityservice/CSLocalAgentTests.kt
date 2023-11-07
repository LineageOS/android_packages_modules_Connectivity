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
import android.net.NetworkCapabilities.NET_CAPABILITY_DUN
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.NetworkScore.KEEP_CONNECTED_FOR_TEST
import android.net.NetworkScore.KEEP_CONNECTED_LOCAL_NETWORK
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
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import kotlin.test.assertFailsWith

private const val TIMEOUT_MS = 200L
private const val MEDIUM_TIMEOUT_MS = 1_000L
private const val LONG_TIMEOUT_MS = 5_000

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

// This allows keeping all the networks connected without having to file individual requests
// for them.
private fun keepScore() = FromS(
        NetworkScore.Builder().setKeepConnectedReason(KEEP_CONNECTED_FOR_TEST).build()
)

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
        val localAgent = Agent(
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
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

    @Test
    fun testUnregisterUpstreamAfterReplacement_SameIfaceName() {
        doTestUnregisterUpstreamAfterReplacement(true)
    }

    @Test
    fun testUnregisterUpstreamAfterReplacement_DifferentIfaceName() {
        doTestUnregisterUpstreamAfterReplacement(false)
    }

    fun doTestUnregisterUpstreamAfterReplacement(sameIfaceName: Boolean) {
        deps.setBuildSdk(VERSION_V)
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)

        // Set up a local agent that should forward its traffic to the best wifi upstream.
        val localAgent = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = LocalNetworkConfig.Builder()
                .setUpstreamSelector(NetworkRequest.Builder()
                        .addTransportType(TRANSPORT_WIFI)
                        .build())
                .build(),
                score = FromS(NetworkScore.Builder()
                        .setKeepConnectedReason(KEEP_CONNECTED_LOCAL_NETWORK)
                        .build())
        )
        localAgent.connect()

        cb.expectAvailableCallbacks(localAgent.network, validated = false)

        val wifiAgent = Agent(lp = lp("wifi0"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()

        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        clearInvocations(netd)
        val inOrder = inOrder(netd)
        wifiAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)
        waitForIdle()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi0")
        inOrder.verify(netd).networkDestroy(wifiAgent.network.netId)

        val wifiIface2 = if (sameIfaceName) "wifi0" else "wifi1"
        val wifiAgent2 = Agent(lp = lp(wifiIface2),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent2.connect()

        cb.expectAvailableCallbacks(wifiAgent2.network, validated = false)
        cb.expect<Lost> { it.network == wifiAgent.network }

        inOrder.verify(netd).ipfwdAddInterfaceForward("local0", wifiIface2)
        if (sameIfaceName) {
            inOrder.verify(netd, never()).ipfwdRemoveInterfaceForward(any(), any())
        }
    }

    @Test
    fun testUnregisterUpstreamAfterReplacement_neverReplaced() {
        deps.setBuildSdk(VERSION_V)
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)

        // Set up a local agent that should forward its traffic to the best wifi upstream.
        val localAgent = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = LocalNetworkConfig.Builder()
                        .setUpstreamSelector(NetworkRequest.Builder()
                                .addTransportType(TRANSPORT_WIFI)
                                .build())
                        .build(),
                score = FromS(NetworkScore.Builder()
                        .setKeepConnectedReason(KEEP_CONNECTED_LOCAL_NETWORK)
                        .build())
        )
        localAgent.connect()

        cb.expectAvailableCallbacks(localAgent.network, validated = false)

        val wifiAgent = Agent(lp = lp("wifi0"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()

        cb.expectAvailableCallbacksUnvalidated(wifiAgent)

        clearInvocations(netd)
        wifiAgent.unregisterAfterReplacement(TIMEOUT_MS.toInt())
        waitForIdle()
        verify(netd).networkDestroy(wifiAgent.network.netId)
        verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi0")

        cb.expect<Lost> { it.network == wifiAgent.network }
    }

    @Test
    fun testUnregisterLocalAgentAfterReplacement() {
        deps.setBuildSdk(VERSION_V)

        val localCb = TestableNetworkCallback()
        cm.requestNetwork(NetworkRequest.Builder().clearCapabilities()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .build(),
                localCb)

        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)

        val localNc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK)
        val lnc = LocalNetworkConfig.Builder()
                .setUpstreamSelector(NetworkRequest.Builder()
                        .addTransportType(TRANSPORT_WIFI)
                        .build())
                .build()
        val localScore = FromS(NetworkScore.Builder().build())

        // Set up a local agent that should forward its traffic to the best wifi upstream.
        val localAgent = Agent(nc = localNc, lp = lp("local0"), lnc = lnc, score = localScore)
        localAgent.connect()

        localCb.expectAvailableCallbacks(localAgent.network, validated = false)
        cb.expectAvailableCallbacks(localAgent.network, validated = false)

        val wifiAgent = Agent(lp = lp("wifi0"), nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()

        cb.expectAvailableCallbacksUnvalidated(wifiAgent)

        verify(netd).ipfwdAddInterfaceForward("local0", "wifi0")

        localAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)

        val localAgent2 = Agent(nc = localNc, lp = lp("local0"), lnc = lnc, score = localScore)
        localAgent2.connect()

        localCb.expectAvailableCallbacks(localAgent2.network, validated = false)
        cb.expectAvailableCallbacks(localAgent2.network, validated = false)
        cb.expect<Lost> { it.network == localAgent.network }
    }

    @Test
    fun testDestroyedNetworkAsSelectedUpstream() {
        deps.setBuildSdk(VERSION_V)
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)

        val wifiAgent = Agent(lp = lp("wifi0"), nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()
        cb.expectAvailableCallbacksUnvalidated(wifiAgent)

        // Set up a local agent that should forward its traffic to the best wifi upstream.
        val localAgent = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = LocalNetworkConfig.Builder()
                        .setUpstreamSelector(NetworkRequest.Builder()
                                .addTransportType(TRANSPORT_WIFI)
                                .build())
                        .build(),
                score = FromS(NetworkScore.Builder()
                        .setKeepConnectedReason(KEEP_CONNECTED_LOCAL_NETWORK)
                        .build())
        )

        // ...but destroy the wifi agent before connecting it
        wifiAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)

        localAgent.connect()
        cb.expectAvailableCallbacks(localAgent.network, validated = false)

        verify(netd).ipfwdAddInterfaceForward("local0", "wifi0")
        verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi0")
    }

    @Test
    fun testForwardingRules() {
        deps.setBuildSdk(VERSION_V)
        // Set up a local agent that should forward its traffic to the best DUN upstream.
        val lnc = LocalNetworkConfig.Builder()
                .setUpstreamSelector(NetworkRequest.Builder()
                        .addCapability(NET_CAPABILITY_DUN)
                        .build())
                .build()
        val localAgent = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = lnc,
                score = FromS(NetworkScore.Builder()
                        .setKeepConnectedReason(KEEP_CONNECTED_LOCAL_NETWORK)
                        .build())
        )
        localAgent.connect()

        val wifiAgent = Agent(score = keepScore(), lp = lp("wifi0"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        val cellAgentDun = Agent(score = keepScore(), lp = lp("cell0"),
                nc = nc(TRANSPORT_CELLULAR, NET_CAPABILITY_INTERNET, NET_CAPABILITY_DUN))
        val wifiAgentDun = Agent(score = keepScore(), lp = lp("wifi1"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET, NET_CAPABILITY_DUN))

        val inOrder = inOrder(netd)
        inOrder.verify(netd, never()).ipfwdAddInterfaceForward(any(), any())

        wifiAgent.connect()
        inOrder.verify(netd, never()).ipfwdAddInterfaceForward(any(), any())

        cellAgentDun.connect()
        inOrder.verify(netd).ipfwdEnableForwarding(any())
        inOrder.verify(netd).ipfwdAddInterfaceForward("local0", "cell0")

        wifiAgentDun.connect()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "cell0")
        inOrder.verify(netd).ipfwdAddInterfaceForward("local0", "wifi1")

        // Make sure sending the same config again doesn't do anything
        repeat(5) {
            localAgent.sendLocalNetworkConfig(lnc)
        }
        inOrder.verifyNoMoreInteractions()

        wifiAgentDun.disconnect()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi1")
        // This can take a little bit of time because it needs to wait for the rematch
        inOrder.verify(netd, timeout(MEDIUM_TIMEOUT_MS)).ipfwdAddInterfaceForward("local0", "cell0")

        cellAgentDun.disconnect()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "cell0")
        inOrder.verify(netd).ipfwdDisableForwarding(any())

        val wifiAgentDun2 = Agent(score = keepScore(), lp = lp("wifi2"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET, NET_CAPABILITY_DUN))
        wifiAgentDun2.connect()
        inOrder.verify(netd).ipfwdEnableForwarding(any())
        inOrder.verify(netd).ipfwdAddInterfaceForward("local0", "wifi2")

        localAgent.disconnect()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi2")
        inOrder.verify(netd).ipfwdDisableForwarding(any())
    }
}
