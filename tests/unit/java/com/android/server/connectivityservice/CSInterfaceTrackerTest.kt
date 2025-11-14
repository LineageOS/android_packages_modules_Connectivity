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

package com.android.server.connectivityservice

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
import com.android.server.CSTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Lost
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder

private const val WIFI_IFNAME = "wlan0"

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
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CSInterfaceTrackerTest : CSTest() {
    private val LOCAL_IPV6_IP_ADDRESS_PREFIX = IpPrefix("fe80::1cf1:35ff:fe8c:db87/64")
    private val LOCAL_IPV6_LINK_ADDRESS = LinkAddress(
        LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress(),
        LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()
    )

    @Test
    fun testDisconnectingNetwork_InterfaceRemoved() {
        val nr = nr(NetworkCapabilities.TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        val interfaceTrackerInorder = inOrder(interfaceTracker)
        cm.requestNetwork(nr, cb)

        // Connecting to network with IPv6 local address in LinkProperties
        val wifiLp = lp(WIFI_IFNAME, LOCAL_IPV6_LINK_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)
        interfaceTrackerInorder.verify(interfaceTracker).addInterface(WIFI_IFNAME)

        wifiAgent.disconnect()
        cb.expect<Lost>(timeoutMs = 500) { it.network == wifiAgent.network }
        // onLost is fired before the network is destroyed.
        waitForIdle()

        interfaceTrackerInorder.verify(interfaceTracker).removeInterface(WIFI_IFNAME)
    }
}
