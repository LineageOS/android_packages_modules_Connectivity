/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.app.ActivityManager.UidFrozenStateChangedCallback
import android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_FROZEN
import android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_UNFROZEN
import android.net.ConnectivityManager.BLOCKED_REASON_APP_BACKGROUND
import android.net.ConnectivityManager.BLOCKED_REASON_NONE
import android.net.ConnectivityManager.FIREWALL_CHAIN_BACKGROUND
import android.net.ConnectivityManager.FIREWALL_RULE_ALLOW
import android.net.ConnectivityManager.FIREWALL_RULE_DENY
import android.net.INetd
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.VpnManager
import android.net.VpnTransportInfo
import android.os.Build
import android.util.Range
import com.android.net.module.util.BaseNetdUnsolicitedEventListener
import com.android.net.module.util.netlink.NetlinkConstants
import com.android.net.module.util.netlink.RtNetlinkAddressMessage
import com.android.net.module.util.netlink.StructIfaddrMsg
import com.android.net.module.util.netlink.StructNlMsgHdr
import com.android.server.NetIdManager.MAX_NET_ID
import com.android.server.NetIdManager.MIN_NET_ID
import com.android.server.connectivity.ConnectivityFlags.DELAY_DESTROY_SOCKETS
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkAgent.Event.OnNetworkDestroyed
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.CapabilitiesChanged
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.TestableNetworkCallback.Event.Lost
import java.net.Inet6Address
import java.net.InetAddress
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

private const val TIMESTAMP = 1234L
private const val TEST_UID = 1234
private const val TEST_UID2 = 5678
private val TEST_UID_RANGE1 = Range(10000, 20000)
private val TEST_UID_RANGE2 = Range(21000, 30000)
private val TEST_UID_RANGE3 = Range(31000, 40000)
private const val TEST_CELL_IFACE = "test_rmnet"

private fun cellNc() = makeNc(TRANSPORT_CELLULAR)

private fun makeNc(transportType: Int) = nc(transportType, NET_CAPABILITY_INTERNET)

private fun cellLp() = makeLp(TEST_CELL_IFACE)

private fun vpnNc(uidRanges: Set<Range<Int>>) = NetworkCapabilities.Builder()
    .addTransportType(TRANSPORT_VPN)
    .setTransportInfo(
        VpnTransportInfo(
            VpnManager.TYPE_VPN_PLATFORM,
            null /* sessionId */,
            false /* bypassable */,
            false /* longLivedTcpConnectionsExpensive */
        )
    )
    .removeCapability(NET_CAPABILITY_NOT_VPN)
    .addCapability(NET_CAPABILITY_INTERNET)
    .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
    .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
    .setUids(uidRanges)
    .build()

private fun makeLp(interfaceName: String) = LinkProperties().also{
    it.interfaceName = interfaceName
}

private fun makeRequest(nc: NetworkCapabilities) = NetworkRequest.Builder()
    .clearCapabilities()
    .setCapabilities(nc)
    .build()

private fun makeVpnRequest(uidRanges: Set<Range<Int>>) = NetworkRequest.Builder()
    .clearCapabilities()
    .addTransportType(TRANSPORT_VPN)
    .setUids(uidRanges)
    .build()

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSDestroySocketTest : CSTest() {
    private fun getRegisteredNetdUnsolicitedEventListener(): BaseNetdUnsolicitedEventListener {
        val captor = ArgumentCaptor.forClass(BaseNetdUnsolicitedEventListener::class.java)
        verify(netd).registerUnsolicitedEventListener(captor.capture())
        return captor.value
    }

    private fun getUidFrozenStateChangedCallback(): UidFrozenStateChangedCallback {
        val captor = ArgumentCaptor.forClass(UidFrozenStateChangedCallback::class.java)
        verify(activityManager).registerUidFrozenStateChangedCallback(any(), captor.capture())
        return captor.value
    }

    private fun doTestBackgroundRestrictionDestroySockets(
            restrictionWithIdleNetwork: Boolean,
            expectDelay: Boolean
    ) {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()
        val inOrder = inOrder(destroySocketsWrapper)

        val cellAgent = Agent(nc = cellNc(), lp = cellLp())
        cellAgent.connect()
        if (restrictionWithIdleNetwork) {
            // Make cell default network idle
            netdEventListener.onInterfaceClassActivityChanged(
                    false, // isActive
                    cellAgent.network.netId,
                    TIMESTAMP,
                    TEST_UID
            )
        }

        // Set deny rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID,
                FIREWALL_RULE_DENY
        )
        waitForIdle()
        if (expectDelay) {
            inOrder.verify(destroySocketsWrapper, never())
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        } else {
            inOrder.verify(destroySocketsWrapper)
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        }

        netdEventListener.onInterfaceClassActivityChanged(
                true, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )
        waitForIdle()
        if (expectDelay) {
            inOrder.verify(destroySocketsWrapper)
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        } else {
            inOrder.verify(destroySocketsWrapper, never())
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        }

        cellAgent.disconnect()
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, true)])
    fun testBackgroundAppDestroySockets() {
        doTestBackgroundRestrictionDestroySockets(
                restrictionWithIdleNetwork = true,
                expectDelay = true
        )
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, true)])
    fun testBackgroundAppDestroySockets_activeNetwork() {
        doTestBackgroundRestrictionDestroySockets(
                restrictionWithIdleNetwork = false,
                expectDelay = false
        )
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, false)])
    fun testBackgroundAppDestroySockets_featureIsDisabled() {
        doTestBackgroundRestrictionDestroySockets(
                restrictionWithIdleNetwork = true,
                expectDelay = false
        )
    }

    @Test
    fun testReplaceFirewallChain() {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()

        val cellAgent = Agent(nc = cellNc(), lp = cellLp())
        cellAgent.connect()
        // Make cell default network idle
        netdEventListener.onInterfaceClassActivityChanged(
                false, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )

        // Set allow rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID,
                FIREWALL_RULE_ALLOW
        )
        // Set deny rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID2)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID2,
                FIREWALL_RULE_DENY
        )

        // Put only TEST_UID2 on background chain (deny TEST_UID and allow TEST_UID2)
        doReturn(setOf(TEST_UID))
                .`when`(bpfNetMaps).getUidsWithAllowRuleOnAllowListChain(FIREWALL_CHAIN_BACKGROUND)
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID2)
        cm.replaceFirewallChain(FIREWALL_CHAIN_BACKGROUND, intArrayOf(TEST_UID2))
        waitForIdle()
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        verify(quicConnectionCloser, never()).closeQuicConnectionByUids(setOf(TEST_UID))

        netdEventListener.onInterfaceClassActivityChanged(
                true, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )
        waitForIdle()
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        verify(quicConnectionCloser).closeQuicConnectionByUids(setOf(TEST_UID))

        cellAgent.disconnect()
    }

    private fun doTestDestroySockets(
            isFrozen: Boolean,
            denyOnBackgroundChain: Boolean,
            enableBackgroundChain: Boolean,
            expectDestroySockets: Boolean
    ) {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()
        val frozenStateCallback = getUidFrozenStateChangedCallback()

        // Make cell default network idle
        val cellAgent = Agent(nc = cellNc(), lp = cellLp())
        cellAgent.connect()
        netdEventListener.onInterfaceClassActivityChanged(
                false, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )

        // Set deny rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID,
                FIREWALL_RULE_DENY
        )

        // Freeze TEST_UID
        frozenStateCallback.onUidFrozenStateChanged(
                intArrayOf(TEST_UID),
                intArrayOf(UID_FROZEN_STATE_FROZEN)
        )

        if (!isFrozen) {
            // Unfreeze TEST_UID
            frozenStateCallback.onUidFrozenStateChanged(
                    intArrayOf(TEST_UID),
                    intArrayOf(UID_FROZEN_STATE_UNFROZEN)
            )
        }
        if (!enableBackgroundChain) {
            // Disable background chain
            cm.setFirewallChainEnabled(FIREWALL_CHAIN_BACKGROUND, false)
        }
        if (!denyOnBackgroundChain) {
            // Set allow rule on background chain for TEST_UID
            doReturn(BLOCKED_REASON_NONE)
                    .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
            cm.setUidFirewallRule(
                    FIREWALL_CHAIN_BACKGROUND,
                    TEST_UID,
                    FIREWALL_RULE_ALLOW
            )
        }
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))

        // Make cell network active
        netdEventListener.onInterfaceClassActivityChanged(
                true, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )
        waitForIdle()

        if (expectDestroySockets) {
            verify(destroySocketsWrapper).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
            verify(quicConnectionCloser).closeQuicConnectionByUids(setOf(TEST_UID))
        } else {
            verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
            verify(quicConnectionCloser, never()).closeQuicConnectionByUids(setOf(TEST_UID))
        }
    }

    @Test
    fun testDestroySockets_backgroundDeny_frozen() {
        doTestDestroySockets(
                isFrozen = true,
                denyOnBackgroundChain = true,
                enableBackgroundChain = true,
                expectDestroySockets = true
        )
    }

    @Test
    fun testDestroySockets_backgroundDeny_nonFrozen() {
        doTestDestroySockets(
                isFrozen = false,
                denyOnBackgroundChain = true,
                enableBackgroundChain = true,
                expectDestroySockets = true
        )
    }

    @Test
    fun testDestroySockets_backgroundAllow_frozen() {
        doTestDestroySockets(
                isFrozen = true,
                denyOnBackgroundChain = false,
                enableBackgroundChain = true,
                expectDestroySockets = true
        )
    }

    @Test
    fun testDestroySockets_backgroundAllow_nonFrozen() {
        // If the app is neither frozen nor under background restriction, sockets are not
        // destroyed
        doTestDestroySockets(
                isFrozen = false,
                denyOnBackgroundChain = false,
                enableBackgroundChain = true,
                expectDestroySockets = false
        )
    }

    @Test
    fun testDestroySockets_backgroundChainDisabled_nonFrozen() {
        // If the app is neither frozen nor under background restriction, sockets are not
        // destroyed
        doTestDestroySockets(
                isFrozen = false,
                denyOnBackgroundChain = true,
                enableBackgroundChain = false,
                expectDestroySockets = false
        )
    }

    private fun InetAddress.toLinkAddress() =
        LinkAddress(this, if (this is Inet6Address) 64 else 24)

    private fun prepareNetworkAgent(
        interfaceName: String,
        addresses: List<LinkAddress>,
        nc: NetworkCapabilities
    ): Pair<CSAgentWrapper, TestableNetworkCallback> {
        val callback = TestableNetworkCallback()
        cm.registerNetworkCallback(makeRequest(nc), callback)
        val linkProperties = makeLp(interfaceName)
        for (linkAddress in addresses) {
            linkProperties.addLinkAddress(linkAddress)
        }
        val agent = Agent(nc = nc, lp = linkProperties)
        agent.connect()
        return agent to callback
    }

    @Test
    fun testIpToNetworksMap() {
        val addressV6_1 = InetAddress.getByName("2001:DB8:0100::1111")
        val addressV6_2 = InetAddress.getByName("2001:DB8:0200::2222")
        val addressV6_3 = InetAddress.getByName("2001:DB8:0333::3333")
        val addressV6LinkLocal = InetAddress.getByName("FE80::1234")
        val addressV4_1 = InetAddress.getByName("192.0.2.10")
        val addressV4_2 = InetAddress.getByName("192.0.2.11")
        // Creates 3 NetworkAgent with various IP addresses(some are used for only one network
        // agent some are used for multiple network agents.)

        val (cellAgent, cellCallback) = prepareNetworkAgent(
            "rmnet1",
            arrayListOf(addressV6_1.toLinkAddress(), addressV4_1.toLinkAddress()),
            makeNc(transportType = TRANSPORT_CELLULAR)
        )

        val (wlanAgent, wlanCallback) = prepareNetworkAgent(
            "wlan1",
            arrayListOf(addressV6_2.toLinkAddress(), addressV4_1.toLinkAddress()),
            makeNc(transportType = TRANSPORT_WIFI)
        )
        val (ethAgent, ethCallback) = prepareNetworkAgent(
            "eth2",
            arrayListOf(addressV6_2.toLinkAddress(), addressV4_2.toLinkAddress()),
            makeNc(transportType = TRANSPORT_ETHERNET)
        )
        val ipToNetworksMap = assertNotNull(service.mIpToNetworksMap)
        fun InetAddress.getNetworks() = ipToNetworksMap[this]!!.map{ it.network }.toSet()

        // Verify mIpToNetworksMap properly stores network agents based on IP addresses.
        assertEquals(setOf(cellAgent.network), addressV6_1.getNetworks())
        assertEquals(
            setOf(wlanAgent.network, ethAgent.network),
            addressV6_2.getNetworks()
        )
        assertEquals(
            setOf(wlanAgent.network, cellAgent.network),
            addressV4_1.getNetworks()
        )
        assertEquals(setOf(ethAgent.network), addressV4_2.getNetworks())

        // Disconnect network agent#2 & #3.
        wlanAgent.disconnect()
        ethAgent.disconnect()
        // Update only interface name without IP address change for network agent #1.
        val linkProperties = makeLp("ipsec1")
        linkProperties.addLinkAddress(addressV4_1.toLinkAddress())
        linkProperties.addLinkAddress(addressV6_1.toLinkAddress())
        cellAgent.sendLinkProperties(linkProperties)
        cellCallback.eventuallyExpect<LinkPropertiesChanged> {
            it.network == cellAgent.network && it.lp.interfaceName == "ipsec1"
        }

        // Verify mIpToNetworksMap for the LinkPropertiesChanged
        assertFalse(ipToNetworksMap.containsKey(addressV6_2))
        assertFalse(ipToNetworksMap.containsKey(addressV4_2))
        assertEquals(setOf(cellAgent.network), addressV6_1.getNetworks())
        assertEquals(setOf(cellAgent.network), addressV4_1.getNetworks())

        // Change IP addresses for network agent #1.
        val linkProperties2 = makeLp("rmnet1")
        linkProperties2.addLinkAddress(addressV4_2.toLinkAddress())
        linkProperties2.addLinkAddress(addressV6_1.toLinkAddress())
        linkProperties2.addLinkAddress(addressV6_3.toLinkAddress())
        cellAgent.sendLinkProperties(linkProperties2)
        // Add network agent#4
        val (agent4, callback4) = prepareNetworkAgent(
            "bt1",
            arrayListOf(addressV6LinkLocal.toLinkAddress(), addressV4_2.toLinkAddress()),
            makeNc(transportType = TRANSPORT_BLUETOOTH)
        )
        assertEquals(3, ipToNetworksMap.size)
        assertEquals(setOf(cellAgent.network), addressV6_1.getNetworks())
        assertEquals(
            setOf(cellAgent.network, agent4.network),
            addressV4_2.getNetworks()
        )
        assertEquals(setOf(cellAgent.network), addressV6_3.getNetworks())
        assertFalse(ipToNetworksMap.containsKey(addressV4_1))
        // This map doesn't handle IPv6 link local addresses.
        assertFalse(ipToNetworksMap.containsKey(addressV6LinkLocal))

        // Disconnect all remaining network agents.
        cellAgent.disconnect()
        agent4.disconnect()
        agent4.eventuallyExpect<OnNetworkDestroyed>()
        // Verify mIpToNetworksMap is empty.
        assertTrue(ipToNetworksMap.isEmpty())
    }

    @Test
    fun testDestroySocketForRemovedIpAddressFromSingleNetwork() {
        val addressV6_1 = InetAddress.getByName("2001:DB8:0100::1111")
        val addressV6_2 = InetAddress.getByName("2001:DB8:0200::2222")
        // add a test network.
        val (agent1, callback1) = prepareNetworkAgent(
            "wlan2",
            arrayListOf(addressV6_1.toLinkAddress()),
            makeNc(transportType = TRANSPORT_WIFI)
        )

        // IP address changed
        val linkProperties2 = makeLp("wlan2")
        linkProperties2.addLinkAddress(addressV6_2.toLinkAddress())
        agent1.sendLinkProperties(linkProperties2)
        callback1.eventuallyExpect<LinkPropertiesChanged> {
            it.network == agent1.network && !it.lp.addresses.contains(addressV6_1)
        }
        // verify destroy sockets on the removed IP address
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV6_1,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )

        // network disconnect
        agent1.disconnect()
        agent1.eventuallyExpect<OnNetworkDestroyed>()
        // verify destroy sockets on the removed IP address
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV6_2,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByLocalAddress(any(), anyInt())
    }

    @Test
    fun testDestroySocketForRemovedIpAddressFromMultipleNonVpnNetworks() {
        val addressV6_1 = InetAddress.getByName("2001:DB8:0100::1111")
        val addressV6_2 = InetAddress.getByName("2001:DB8:0200::2222")
        val addressV6LinkLocal = InetAddress.getByName("FE80::1234")
        val addressV4_1 = InetAddress.getByName("192.0.2.10")

        // add 4 test networks(non VPN). 3 have a same IP address, and 1 network has different one.
        val (cellAgent, cellCallback) = prepareNetworkAgent(
            "rmnet1",
            arrayListOf(addressV6_1.toLinkAddress(), addressV4_1.toLinkAddress()),
            makeNc(transportType = TRANSPORT_CELLULAR)
        )

        val (wlanAgent, wlanCallback) = prepareNetworkAgent(
            "wlan0",
            arrayListOf(),
            makeNc(transportType = TRANSPORT_WIFI)
        )

        val (testAgent, testCallback) = prepareNetworkAgent(
            "test2",
            arrayListOf(addressV6_2.toLinkAddress()),
            makeNc(transportType = TRANSPORT_TEST)
        )

        val (ethAgent, ethCallback) = prepareNetworkAgent(
            "eth1",
            arrayListOf(addressV6_1.toLinkAddress()),
            makeNc(transportType = TRANSPORT_ETHERNET)
        )

        // Add IP addresses on network#2
        val linkProperties = makeLp("wlan0")
        linkProperties.addLinkAddress(addressV6LinkLocal.toLinkAddress())
        linkProperties.addLinkAddress(addressV6_1.toLinkAddress())
        wlanAgent.sendLinkProperties(linkProperties)

        // Remove IP addresses network#1
        val linkProperties1 = makeLp("rmnet1")
        cellAgent.sendLinkProperties(linkProperties1)
        cellCallback.eventuallyExpect<LinkPropertiesChanged> {
            it.network == cellAgent.network && it.lp.addresses.size == 0
        }

        // verify destroy sockets on the removed IP address and exempt netId lists.
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV6_1,
            setOf(
                Range.create(MIN_NET_ID, wlanAgent.network.netId - 1),
                Range.create(wlanAgent.network.netId + 1, ethAgent.network.netId - 1),
                Range.create(ethAgent.network.netId + 1, MAX_NET_ID)
            ),
            null
        )
        // verify destroy sockets on the removed IP address
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV4_1,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )

        // Remove IP addresses from network#2
        val linkProperties2 = makeLp("wlan3")
        wlanAgent.sendLinkProperties(linkProperties2)
        wlanCallback.eventuallyExpect<LinkPropertiesChanged> {
            it.network == wlanAgent.network && it.lp.interfaceName == "wlan3"
        }
        // verify destroy sockets on the removed IP address and exempt netId lists.
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV6_1,
            setOf(
                Range.create(MIN_NET_ID, ethAgent.network.netId - 1),
                Range.create(ethAgent.network.netId + 1, MAX_NET_ID)
            ),
            null
        )
        // verify not to destroy sockets on the link local address at NetworkAgent update.
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByLocalAddress(
            addressV6LinkLocal,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )

        // disconnect all test networks
        cellAgent.disconnect()
        wlanAgent.disconnect()
        testAgent.disconnect()
        ethAgent.disconnect()
        ethAgent.eventuallyExpect<OnNetworkDestroyed>()

        // verify destroy sockets on the removed IP address
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV6_2,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV6_1,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByLocalAddress(any(), anyInt())
    }

    @Test
    fun testDestroySocketForRemovedIpAddressFromMultipleVpnNetworks() {
        val addressV6_1 = InetAddress.getByName("2001:DB8:0100::1111")
        val addressV6_2 = InetAddress.getByName("2001:DB8:0200::2222")
        val addressV6_3 = InetAddress.getByName("2001:DB8:0333::3333")
        val addressV6LinkLocal = InetAddress.getByName("FE80::1234")
        val addressV4_1 = InetAddress.getByName("192.0.2.10")
        // add 3 VPN test networks and 1 non-VPN network.

        val (vpnAgent1, vpnCallback1) = prepareNetworkAgent(
            "ipsec1",
            arrayListOf(addressV6_1.toLinkAddress(), addressV4_1.toLinkAddress()),
            vpnNc(setOf(TEST_UID_RANGE1))
        )

        val (vpnAgent2, vpnCallback2) = prepareNetworkAgent(
            "ipsec2",
            arrayListOf(addressV6LinkLocal.toLinkAddress(), addressV6_1.toLinkAddress()),
            vpnNc(setOf(TEST_UID_RANGE2))
        )
        val (vpnAgent3, vpnCallback3) = prepareNetworkAgent(
            "ipsec3",
            arrayListOf(addressV6_1.toLinkAddress()),
            vpnNc(setOf(TEST_UID_RANGE3))
        )
        val (testAgent, testCallback) = prepareNetworkAgent(
            "test4",
            arrayListOf(addressV6_2.toLinkAddress(), addressV4_1.toLinkAddress()),
            makeNc(TRANSPORT_TEST)
        )
        // Remove IP addresses network#1
        val linkProperties1 = makeLp("ipsec21")
        vpnAgent1.sendLinkProperties(linkProperties1)
        vpnCallback1.eventuallyExpect<LinkPropertiesChanged> {
            it.network == vpnAgent1.network && it.lp.interfaceName == "ipsec21"
        }
        // verify destroy sockets on the removed IP address(TEST_IPV6_ADDRESS1) and VPN's uid range.
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV6_1,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            vpnAgent1.nc.uids
        )
        // verify destroy sockets on the removed IP address(TEST_IPV4_ADDRESS1). network#1 and
        // network#4 use TEST_IPV4_ADDRESS1, however network#4 is not VPN network, so we destroy
        // sockets only based on the removed IP address.
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV4_1,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )

        // Change IP addresses network#2
        val linkProperties2 = makeLp("ipsec2")
        linkProperties2.addLinkAddress(addressV6_3.toLinkAddress())
        vpnAgent2.sendLinkProperties(linkProperties2)
        vpnCallback2.eventuallyExpect<LinkPropertiesChanged> { it.network == vpnAgent2.network &&
                it.lp.interfaceName == "ipsec2" && it.lp.addresses.contains(addressV6_3)
        }
        // verify destroy sockets on the removed IP address(TEST_IPV6_ADDRESS1) and VPN's uid range.
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV6_1,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            vpnAgent2.nc.uids
        )
        // verify not to destroy sockets on the link local address at NetworkAgent update.
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByLocalAddress(
            addressV6LinkLocal,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )

        // disconnect all test networks
        vpnAgent1.disconnect()
        vpnAgent2.disconnect()
        vpnAgent3.disconnect()
        testAgent.disconnect()
        testAgent.eventuallyExpect<OnNetworkDestroyed>()

        // verify destroy sockets on the removed IP address.
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV6_3,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            addressV6_2,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )
        verify(destroySocketsWrapper, times(2)).destroyLiveTcpSocketsByLocalAddress(
            addressV4_1,
            setOf(Range.create(MIN_NET_ID, MAX_NET_ID)),
            null
        )
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByLocalAddress(any(), anyInt())
    }

    @Test
    fun testDestroySocketWithNetlinkMessageForLinkLocalAddress() {
        val addressV6LinkLocal = InetAddress.getByName("FE80::1234")
        val testInterfaceId = 10
        val nlMsgLinkLocalAddressRemoved = RtNetlinkAddressMessage(
            StructNlMsgHdr(
                30 /* payload length */,
                NetlinkConstants.RTM_DELADDR,
                0 /* flages */,
                0 /* seq */
            ),
            StructIfaddrMsg(
                10 /* family */,
                64 /* prefix length */,
                0 /* flags */,
                0 /* scope */,
                testInterfaceId /* interfaceId */
            ),
            addressV6LinkLocal,
            null,
            0 /* flags */
        )
        csHandler.post{deps.netlinkMessageUpdate?.accept(nlMsgLinkLocalAddressRemoved)}

        // verify destroy a socket on link local address with corresponding interface ID.
        verify(destroySocketsWrapper, timeout(100)).destroyLiveTcpSocketsByLocalAddress(
            addressV6LinkLocal,
            testInterfaceId
        )
    }

    @Test
    fun testDestroySocketWithNetlinkMessageForOemNetwork() {
        val addressV4 = InetAddress.getByName("203.0.113.10")
        val nlMsgAddressRemoved = RtNetlinkAddressMessage(
            StructNlMsgHdr(
                30 /* payload length */,
                NetlinkConstants.RTM_DELADDR,
                0 /* flags */,
                0 /* seq */
            ),
            StructIfaddrMsg(
                10 /* family */,
                64 /* prefix length */,
                0 /* flags */,
                0 /* scope */,
                5 /* interfaceId */
            ),
            addressV4,
            null,
            0 /* flags */
        )
        csHandler.post{deps.netlinkMessageUpdate?.accept(nlMsgAddressRemoved)}

        // verify destroy non platform socket(netId < MIN_NET_ID) with the RTM_DELADDR message
        verify(destroySocketsWrapper, timeout(100)).destroyLiveTcpSocketsByLocalAddress(
            addressV4,
            setOf(Range.create(0, MIN_NET_ID - 1)),
            null /* uidRanges */
        )
    }

    @Test
    fun testDestroySocketsLackingPermissionForPermissionUpdate() {
        val inOrder = inOrder(destroySocketsWrapper)
        val addressV6_1 = InetAddress.getByName("2001:DB8:0100::1111")
        val addressV4_1 = InetAddress.getByName("192.0.2.10")

        doNothing().`when`(netd).networkSetPermissionForNetwork(anyInt(), anyInt())

        val ncRestricted =
            cellNc().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        val ncNotRestricted =
            cellNc().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)

        val (agent, callback) = prepareNetworkAgent(
            "rmnet1",
            arrayListOf(addressV6_1.toLinkAddress(), addressV4_1.toLinkAddress()),
            ncRestricted
        )

        agent.sendNetworkCapabilities(ncNotRestricted)
        callback.eventuallyExpect<CapabilitiesChanged> {
            it.network == agent.network &&
                    it.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        }
        inOrder.verify(destroySocketsWrapper, never())
            .destroyLiveTcpSocketsLackingPermission(anyInt(), anyInt())
        agent.sendNetworkCapabilities(ncRestricted)
        callback.eventuallyExpect<Lost>()
        inOrder.verify(destroySocketsWrapper, times(2))
            .destroyLiveTcpSocketsLackingPermission(
                agent.network.netId,
                INetd.PERMISSION_SYSTEM
            )
    }

    @Test
    fun testDestroySocketsLackingPermissionForDelayedTeardown() {
        val addressV6_1 = InetAddress.getByName("2001:DB8:0100::1111")
        doNothing().`when`(netd).networkSetPermissionForNetwork(anyInt(), anyInt())
        val (agent, callback) = prepareNetworkAgent(
            "rmnet1",
            arrayListOf(addressV6_1.toLinkAddress()),
            makeNc(TRANSPORT_CELLULAR)
        )
        agent.sendTeardownDelayMs(1)
        agent.disconnect()
        agent.eventuallyExpect<OnNetworkDestroyed>()

        verify(destroySocketsWrapper, times(2)).destroyLiveTcpSocketsLackingPermission(
            agent.network.netId,
            INetd.PERMISSION_SYSTEM
        )
    }
}
