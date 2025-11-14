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

import android.net.ConnectivitySettingsManager
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NativeNetworkConfig
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.ResolverParamsParcel
import android.net.RouteInfo
import android.net.VpnManager
import android.net.VpnTransportInfo
import android.net.netd.aidl.NativeUidRangeConfig
import android.os.Build
import android.os.Process
import android.util.Range
import com.android.server.connectivity.ConnectivityFlags.EARLY_LINK_PROPERTIES_UPDATE_FOR_VPN
import com.android.server.connectivity.ConnectivityFlags.QUEUE_NETWORK_AGENT_EVENTS_IN_SYSTEM_SERVER
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.times

private const val DNS_ADDR = "8.8.8.8"
private const val IPV4_ADDR = "192.168.2.1"
private const val ROUTE_PREFIX = "0.0.0.0/0"

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S)
class CSNetworkAgentTest : CSTest() {

    @Test fun testVpnUidAgent() = testUidAgent(
        TRANSPORT_VPN,
        expectAddUidRanges = true
    )
    @ConnectivityModuleTest
    @Test fun testWifiUidAgent() = testUidAgent(TRANSPORT_WIFI, expectAddUidRanges = false)

    fun testUidAgent(transport: Int, expectAddUidRanges: Boolean) {
        val netdInOrder = inOrder(netd)
        val uid = Process.myUid()

        val nc = defaultNc()
            .addTransportType(transport)
            .setUids(setOf(Range(uid, uid)))
        if (TRANSPORT_VPN == transport) {
            nc.removeCapability(NET_CAPABILITY_NOT_VPN)
            nc.setTransportInfo(
                VpnTransportInfo(
                    VpnManager.TYPE_VPN_SERVICE,
                    "MySession12345",
                    true /* bypassable */,
                    false /* longLivedTcpConnectionsExpensive */
                )
            )
        }
        val agent = Agent(nc)
        agent.connect()

        netdInOrder.verify(netd).networkCreate(argThat { it: NativeNetworkConfig ->
            it.netId == agent.network.netId
        })
        if (deps.isAtLeastU()) {
          // The call to setNetworkAllowlist was added in U.
          netdInOrder.verify(netd).setNetworkAllowlist(any())
        }
        if (expectAddUidRanges) {
            netdInOrder.verify(netd).networkAddUidRangesParcel(argThat { it: NativeUidRangeConfig ->
                it.netId == agent.network.netId &&
                        it.uidRanges.size == 1 &&
                        it.uidRanges[0].start == uid &&
                        it.uidRanges[0].stop == uid &&
                        it.subPriority == 0 // VPN priority
            })
        } else {
            netdInOrder.verify(netd, never()).networkAddUidRangesParcel(any())
        }
        // The old method should never be called in any case
        netdInOrder.verify(netd, never()).networkAddUidRanges(anyInt(), any())
    }

    // Test the early link properties update for the VPN network when the flag
    // QUEUE_NETWORK_AGENT_EVENTS_IN_SYSTEM_SERVER is disabled. That flag is enabled by default in
    // the CSTest.
    @ConnectivityModuleTest
    @FeatureFlags(flags = [Flag(QUEUE_NETWORK_AGENT_EVENTS_IN_SYSTEM_SERVER, false)])
    @Test
    fun testEarlyLinkPropertiesUpdateForVPN() =
            testEarlyLinkPropertiesUpdate(TRANSPORT_VPN, expectEarlyLinkPropertiesUpdate = true)

    // Test the early link properties update for the non-VPN network when the flag
    // QUEUE_NETWORK_AGENT_EVENTS_IN_SYSTEM_SERVER is disabled. That flag is enabled by default in
    // the CSTest.
    @ConnectivityModuleTest
    @FeatureFlags(flags = [Flag(QUEUE_NETWORK_AGENT_EVENTS_IN_SYSTEM_SERVER, false)])
    @Test
    fun testEarlyLinkPropertiesUpdateForNonVPN() =
            testEarlyLinkPropertiesUpdate(TRANSPORT_WIFI, expectEarlyLinkPropertiesUpdate = false)

    // Test the early link properties update for the VPN network when both flags
    // QUEUE_NETWORK_AGENT_EVENTS_IN_SYSTEM_SERVER and EARLY_LINK_PROPERTIES_UPDATE_FOR_VPN are
    // disabled. These flags are enabled by default in the CSTest.
    @ConnectivityModuleTest
    @FeatureFlags(
        flags = [Flag(QUEUE_NETWORK_AGENT_EVENTS_IN_SYSTEM_SERVER, false),
            Flag(EARLY_LINK_PROPERTIES_UPDATE_FOR_VPN, false)]
    )
    @Test
    fun testEarlyLinkPropertiesUpdateDisabledForVPN() =
            testEarlyLinkPropertiesUpdate(TRANSPORT_VPN, expectEarlyLinkPropertiesUpdate = false)

    private fun testEarlyLinkPropertiesUpdate(
            transport: Int,
            expectEarlyLinkPropertiesUpdate: Boolean
    ) {
        val netdInOrder = inOrder(netd)
        val resolverInOrder = inOrder(dnsResolver)

        val nc = defaultNc()
                .addTransportType(transport)
                .addCapability(NET_CAPABILITY_TRUSTED)
                .addCapability(NET_CAPABILITY_INTERNET)
        if (TRANSPORT_VPN == transport) {
            nc.removeCapability(NET_CAPABILITY_NOT_VPN)
            nc.setTransportInfo(
                    VpnTransportInfo(
                            VpnManager.TYPE_VPN_SERVICE,
                            "MySession12345",
                            true /* bypassable */,
                            false /* longLivedTcpConnectionsExpensive */
                    )
            )
        }
        val linkAddress = LinkAddress(InetAddresses.parseNumericAddress(IPV4_ADDR), 32)
        val lp = LinkProperties().apply {
            addLinkAddress(linkAddress)
            addRoute(RouteInfo(IpPrefix(ROUTE_PREFIX), null, null))
            addDnsServer(InetAddresses.parseNumericAddress(DNS_ADDR))
        }
        ConnectivitySettingsManager.setPrivateDnsMode(
                context,
                ConnectivitySettingsManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
        )
        val agent = Agent(nc = nc, lp = lp)
        agent.connect()

        netdInOrder.verify(netd).networkCreate(argThat { it.netId == agent.network.netId })
        netdInOrder.verify(netd).networkAddRouteParcel(
                eq(agent.network.netId),
                argThat { it.destination == ROUTE_PREFIX }
        )
        netdInOrder.verify(netd).networkAddRouteParcel(
                eq(agent.network.netId),
                argThat { it.destination == linkAddress.toString() }
        )

        if (expectEarlyLinkPropertiesUpdate) {
            // Update the DNS without the TLS servers because the private DNS hasn't been set.
            resolverInOrder.verify(dnsResolver).setResolverConfiguration(
                    argThat { it: ResolverParamsParcel ->
                        it.netId == agent.network.netId &&
                        it.servers.any { server -> server == DNS_ADDR } &&
                        it.tlsServers.isEmpty() }
            )
            // The private DNS has been set. Update the DNS again with the TLS servers.
            resolverInOrder.verify(dnsResolver).setResolverConfiguration(
                    argThat {
                        it.netId == agent.network.netId &&
                        it.servers.any { server -> server == DNS_ADDR } &&
                        it.tlsServers.any { server -> server == DNS_ADDR }}
            )
        } else {
            // Update the DNS with the TLS servers because the private DNS has been set.
            resolverInOrder.verify(dnsResolver, times(2)).setResolverConfiguration(
                    argThat {
                        it.netId == agent.network.netId &&
                        it.servers.any { server -> server == DNS_ADDR } &&
                        it.tlsServers.any { server -> server == DNS_ADDR }}
            )
        }
    }
}
