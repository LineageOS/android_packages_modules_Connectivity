/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.TestableNetworkCallback.Event.Lost
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

private const val LONG_TIMEOUT_MS = 5_000
private const val PREFIX_LENGTH_IPV4 = 32 + 96
private const val PREFIX_LENGTH_IPV6 = 32
private const val WIFI_IFNAME = "wlan0"
private const val WIFI_IFNAME_2 = "wlan1"
private const val WIFI_IFNAME_3 = "wlan2"

private val wifiNc = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_WIFI)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()

private fun lp(iface: String, vararg linkAddresses: LinkAddress) = LinkProperties().apply {
    interfaceName = iface
    for (linkAddress in linkAddresses) {
        addLinkAddress(linkAddress)
    }
}

private fun nr(transport: Int) = NetworkRequest.Builder()
        .clearCapabilities()
        .addTransportType(transport).apply {
            if (transport != TRANSPORT_VPN) {
                addCapability(NET_CAPABILITY_NOT_VPN)
            }
        }.build()

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CSLocalNetworkProtectionTest : CSTest() {
    private val LOCAL_IPV6_IP_ADDRESS_PREFIX = IpPrefix("fe80::1cf1:35ff:fe8c:db87/64")
    private val LOCAL_IPV6_LINK_ADDRESS = LinkAddress(
        LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress(),
        LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()
    )

    private val LOCAL_IPV6_IP_ADDRESS_2_PREFIX =
            IpPrefix("2601:19b:67f:e220:1cf1:35ff:fe8c:db87/64")
    private val LOCAL_IPV6_LINK_ADDRESS_2 = LinkAddress(
            LOCAL_IPV6_IP_ADDRESS_2_PREFIX.getAddress(),
            LOCAL_IPV6_IP_ADDRESS_2_PREFIX.getPrefixLength()
    )

    private val LOCAL_IPV6_IP_ADDRESS_3_PREFIX =
            IpPrefix("fe80::/10")
    private val LOCAL_IPV6_LINK_ADDRESS_3 = LinkAddress(
            LOCAL_IPV6_IP_ADDRESS_3_PREFIX.getAddress(),
            LOCAL_IPV6_IP_ADDRESS_3_PREFIX.getPrefixLength()
    )

    private val LOCAL_IPV4_IP_ADDRESS_PREFIX_1 = IpPrefix("10.0.0.184/24")
    private val LOCAL_IPV4_LINK_ADDRRESS_1 =
        LinkAddress(
            LOCAL_IPV4_IP_ADDRESS_PREFIX_1.getAddress(),
            LOCAL_IPV4_IP_ADDRESS_PREFIX_1.getPrefixLength()
        )

    private val LOCAL_IPV4_IP_ADDRESS_PREFIX_2 = IpPrefix("10.0.255.184/24")
    private val LOCAL_IPV4_LINK_ADDRRESS_2 =
        LinkAddress(
            LOCAL_IPV4_IP_ADDRESS_PREFIX_2.getAddress(),
            LOCAL_IPV4_IP_ADDRESS_PREFIX_2.getPrefixLength()
        )

    @Test
    fun testNetworkWithIPv6LocalAddress_AddressAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        // Connecting to network with IPv6 local address in LinkProperties
        val wifiLp = lp(WIFI_IFNAME, LOCAL_IPV6_LINK_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )
    }

    @Test
    fun testNetworkWithIPv4LocalAddress_AddressAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LOCAL_IPV4_LINK_ADDRRESS_1)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()

        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME),
            eq(InetAddresses.parseNumericAddress("10.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
    }

    @Test
    fun testChangeLinkPropertiesWithDifferentLinkAddresses_AddressReplacedInBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LOCAL_IPV6_LINK_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )

        // Updating Link Property from IPv6 in Link Address to IPv4 in Link Address
        val wifiLp2 = lp(WIFI_IFNAME, LOCAL_IPV4_LINK_ADDRRESS_1)
        wifiAgent.sendLinkProperties(wifiLp2)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME),
            eq(InetAddresses.parseNumericAddress("10.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
        // Verifying IPv6 address should be removed from local_net_access map
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
            eq(0),
            eq(0)
        )
    }

    @Test
    fun testAddingThenRemovingStackedLinkProperties_AddressAddedThenRemovedInBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LOCAL_IPV6_LINK_ADDRESS)
        val wifiLp2 = lp(WIFI_IFNAME_2, LOCAL_IPV4_LINK_ADDRRESS_1)
        // Adding stacked link
        wifiLp.addStackedLink(wifiLp2)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
                eq(WIFI_IFNAME),
                eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
                eq(0),
                eq(0),
                eq(false)
        )

        // Multicast and Broadcast address should always be populated on stacked link
        // in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated as part of stacked link
        // in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4 + 8),
                eq(WIFI_IFNAME_2),
                eq(InetAddresses.parseNumericAddress("10.0.0.0")),
                eq(0),
                eq(0),
                eq(false)
        )
        // As both addresses are in stacked links, so no address should be removed from the map.
        verify(bpfNetMaps, never()).removeLocalNetAccess(any(), any(), any(), any(), any())

        // replacing link properties without stacked links
        val wifiLp_3 = lp(WIFI_IFNAME, LOCAL_IPV6_LINK_ADDRESS)
        wifiAgent.sendLinkProperties(wifiLp_3)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // As both stacked links is removed, 10.0.0.0/8 should be removed from local_net_access map.
        verify(bpfNetMaps).removeLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4 + 8),
                eq(WIFI_IFNAME_2),
                eq(InetAddresses.parseNumericAddress("10.0.0.0")),
                eq(0),
                eq(0)
        )
    }

    @Test
    fun testChangeStackedLinkProperties_AddressReplacedBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LOCAL_IPV6_LINK_ADDRESS)
        val wifiLp2 = lp(WIFI_IFNAME_2, LOCAL_IPV4_LINK_ADDRRESS_1)
        // populating stacked link
        wifiLp.addStackedLink(wifiLp2)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
                eq(WIFI_IFNAME),
                eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
                eq(0),
                eq(0),
                eq(false)
        )

        // Multicast and Broadcast address should always be populated on stacked link
        // in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated as part of stacked link
        // in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4 + 8),
                eq(WIFI_IFNAME_2),
                eq(InetAddresses.parseNumericAddress("10.0.0.0")),
                eq(0),
                eq(0),
                eq(false)
        )
        // As both addresses are in stacked links, so no address should be removed from the map.
        verify(bpfNetMaps, never()).removeLocalNetAccess(any(), any(), any(), any(), any())

        // replacing link properties multiple stacked links
        val wifiLp_3 = lp(WIFI_IFNAME, LOCAL_IPV6_LINK_ADDRESS_2)
        val wifiLp_4 = lp(WIFI_IFNAME_2, LOCAL_IPV4_LINK_ADDRRESS_2)
        val wifiLp_5 = lp(WIFI_IFNAME_3, LOCAL_IPV6_LINK_ADDRESS_3)
        wifiLp_3.addStackedLink(wifiLp_4)
        wifiLp_3.addStackedLink(wifiLp_5)
        wifiAgent.sendLinkProperties(wifiLp_3)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Multicast and Broadcast address should always be populated on stacked link
        // in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_3)
        // Verifying new base IPv6 address should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_2_PREFIX.getPrefixLength()),
                eq(WIFI_IFNAME),
                eq(LOCAL_IPV6_IP_ADDRESS_2_PREFIX.getAddress()),
                eq(0),
                eq(0),
                eq(false)
        )
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated as part of stacked link
        // in local_net_access map
        verify(bpfNetMaps, times(2)).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4 + 8),
                eq(WIFI_IFNAME_2),
                eq(InetAddresses.parseNumericAddress("10.0.0.0")),
                eq(0),
                eq(0),
                eq(false)
        )
        // Verifying newly stacked IPv6 address should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_3_PREFIX.getPrefixLength()),
                eq(WIFI_IFNAME_3),
                eq(LOCAL_IPV6_IP_ADDRESS_3_PREFIX.getAddress()),
                eq(0),
                eq(0),
                eq(false)
        )
        // Verifying old base IPv6 address should be removed from local_net_access map
        verify(bpfNetMaps).removeLocalNetAccess(
                eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
                eq(WIFI_IFNAME),
                eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
                eq(0),
                eq(0)
        )
        // As both stacked links is had same prefix, 10.0.0.0/8 should not be removed from
        // local_net_access map.
        verify(bpfNetMaps, never()).removeLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4 + 8),
                eq(WIFI_IFNAME_2),
                eq(InetAddresses.parseNumericAddress("10.0.0.0")),
                eq(0),
                eq(0)
        )
    }

    @Test
    fun testChangeLinkPropertiesWithLinkAddressesInSameRange_AddressIntactInBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LOCAL_IPV4_LINK_ADDRRESS_1)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME),
            eq(LOCAL_IPV4_IP_ADDRESS_PREFIX_1.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )

        // Updating Link Property from one IPv4 to another IPv4 within same range(10.0.0.0/8)
        val wifiLp2 = lp(WIFI_IFNAME, LOCAL_IPV4_LINK_ADDRRESS_2)
        wifiAgent.sendLinkProperties(wifiLp2)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // As both addresses below to same range, so no address should be removed from the map.
        verify(bpfNetMaps, never()).removeLocalNetAccess(any(), any(), any(), any(), any())
    }

    @Test
    fun testChangeLinkPropertiesWithDifferentInterface_AddressReplacedInBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LOCAL_IPV6_LINK_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )

        // Updating Link Property by changing interface name which has IPv4 instead of IPv6
        val wifiLp2 = lp(WIFI_IFNAME_2, LOCAL_IPV4_LINK_ADDRRESS_1)
        wifiAgent.sendLinkProperties(wifiLp2)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Multicast and Broadcast address should be populated in local_net_access map for
        // new interface
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME_2),
            eq(InetAddresses.parseNumericAddress("10.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
        // Multicast and Broadcast address should be removed in local_net_access map for
        // old interface
        verifyRemovalOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be removed from local_net_access map
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
            eq(0),
            eq(0)
        )
    }

    @Test
    fun testAddingAnotherNetwork_AllAddressesAddedInBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LOCAL_IPV6_LINK_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )

        // Adding another network with LinkProperty having IPv4 in LinkAddress
        val wifiLp2 = lp(WIFI_IFNAME_2, LOCAL_IPV4_LINK_ADDRRESS_1)
        val wifiAgent2 = Agent(nc = wifiNc, lp = wifiLp2)
        wifiAgent2.connect()

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME_2),
            eq(InetAddresses.parseNumericAddress("10.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
        // Verifying nothing should be removed from local_net_access map
        verify(bpfNetMaps, never()).removeLocalNetAccess(any(), any(), any(), any(), any())
    }

    @Test
    fun testDestroyingNetwork_AddressesRemovedFromBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LOCAL_IPV6_LINK_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq( PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )

        // Unregistering the network
        wifiAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)
        cb.expect<Lost>(wifiAgent.network)

        // Multicast and Broadcast address should be removed in local_net_access map for
        // old interface
        verifyRemovalOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be removed from local_net_access map
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress()),
            eq(0),
            eq(0)
        )
    }

    // Verify if multicast and broadcast addresses have been added using addLocalNetAccess
    fun verifyPopulationOfMulticastAndBroadcastAddress(
        interfaceName: String = WIFI_IFNAME
    ) {
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 4),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("224.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + 8),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("ff00::")),
            eq(0),
            eq(0),
            eq(false)
        )
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 32),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("255.255.255.255")),
            eq(0),
            eq(0),
            eq(false)
        )
    }

    // Verify if multicast and broadcast addresses have been removed using removeLocalNetAccess
    fun verifyRemovalOfMulticastAndBroadcastAddress(
        interfaceName: String = WIFI_IFNAME
    ) {
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 4),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("224.0.0.0")),
            eq(0),
            eq(0)
        )
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + 8),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("ff00::")),
            eq(0),
            eq(0)
        )
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 32),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("255.255.255.255")),
            eq(0),
            eq(0)
        )
    }
}
