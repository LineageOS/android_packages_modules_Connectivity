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

import android.net.INetd
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.RouteInfo
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.net.module.util.NetdUtils
import com.android.server.ConnectivityService
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.net.Inet6Address
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

private const val WIFI_IFNAME = "wlan0"
private const val NON_LOCAL_NET_ID = 102

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CSLocalRouteTest {
    private val LOCAL_IPV6_IP_ADDRESS_PREFIX = IpPrefix("2001:db8:1234:abcd::/64")
    private val LOCAL_IPV4_IP_ADDRESS_PREFIX = IpPrefix("10.0.0.0/24")
    private val IPV4_GATEWAY = InetAddresses.parseNumericAddress("10.0.0.1")
    private val IPV6_GATEWAY = InetAddresses.parseNumericAddress("fe80::1") as Inet6Address
    private val NON_LOCAL_IPV4_IP_ADDRESS_PREFIX = IpPrefix("8.8.0.0/24")

    @Test
    fun testIsLocalRouteForLocalIPv6_TrueForLocalRoute() {
        val route = NetdUtils.toRouteInfoParcel(RouteInfo(
            LOCAL_IPV6_IP_ADDRESS_PREFIX,
            null,
            WIFI_IFNAME
        ))
        Assert.assertTrue(ConnectivityService.isLocalRoute(route, NON_LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteWithNextHopForLocalIPv6_FalseForLocalRoute() {
        val route = NetdUtils.toRouteInfoParcel(RouteInfo(
            LOCAL_IPV6_IP_ADDRESS_PREFIX,
            IPV6_GATEWAY,
            WIFI_IFNAME
        ))
        Assert.assertFalse(ConnectivityService.isLocalRoute(route, NON_LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteWithLocalNetIdForLocalIPv6_FalseForLocalRoute() {
        val route = NetdUtils.toRouteInfoParcel(RouteInfo(
            LOCAL_IPV6_IP_ADDRESS_PREFIX,
            null,
            WIFI_IFNAME
        ))
        Assert.assertFalse(ConnectivityService.isLocalRoute(route, INetd.LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteForLocalIPv4_TrueForLocalRoute() {
        val route = NetdUtils.toRouteInfoParcel(RouteInfo(
            LOCAL_IPV4_IP_ADDRESS_PREFIX,
            null,
            WIFI_IFNAME
        ))
        Assert.assertTrue(ConnectivityService.isLocalRoute(route, NON_LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteForNonLocalIPv4_FalseForLocalRoute() {
        val route = NetdUtils.toRouteInfoParcel(RouteInfo(
            NON_LOCAL_IPV4_IP_ADDRESS_PREFIX,
            null,
            WIFI_IFNAME
        ))
        Assert.assertFalse(ConnectivityService.isLocalRoute(route, NON_LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteWithNextHopForLocalIPv4_FalseForLocalRoute() {
        val route = NetdUtils.toRouteInfoParcel(RouteInfo(
            LOCAL_IPV4_IP_ADDRESS_PREFIX,
            IPV4_GATEWAY,
            WIFI_IFNAME
        ))
        Assert.assertFalse(ConnectivityService.isLocalRoute(route, NON_LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteWithNextHopForNonLocalIPv4_FalseForLocalRoute() {
        val route = NetdUtils.toRouteInfoParcel(RouteInfo(
            NON_LOCAL_IPV4_IP_ADDRESS_PREFIX,
            IPV4_GATEWAY,
            WIFI_IFNAME
        ))
        Assert.assertFalse(ConnectivityService.isLocalRoute(route, NON_LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteWithLocalNetIdForLocalIPv4_FalseForLocalRoute() {
        val route = NetdUtils.toRouteInfoParcel(RouteInfo(
            LOCAL_IPV4_IP_ADDRESS_PREFIX,
            null,
            WIFI_IFNAME
        ))
        Assert.assertFalse(ConnectivityService.isLocalRoute(route, INetd.LOCAL_NET_ID))
    }
}
