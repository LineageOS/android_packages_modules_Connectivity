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

import android.annotation.SuppressLint
import android.net.INetd
import android.net.NativeNetworkConfig
import android.net.NativeNetworkType
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_SATELLITE
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.UidRangeParcel
import android.net.VpnManager
import android.net.netd.aidl.NativeUidRangeConfig
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.filters.SmallTest
import com.android.server.ConnectivityService.PREFERENCE_ORDER_DEBUG_FALLBACK
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Losing
import com.android.testutils.TestableNetworkCallback.Event.Lost
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSShellCommandsTest : CSTest() {

    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    fun handleShellCommand(args: String) {
        val pfd = ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_WRITE)
        service.handleShellCommand(pfd, pfd, pfd, args.split(" ").toTypedArray())
    }

    fun ncForTransport(transport: Int, otherCaps: IntArray = intArrayOf()): NetworkCapabilities {
        return NetworkCapabilities.Builder().apply {
            addTransportType(transport)
            addCapability(NET_CAPABILITY_INTERNET)
            addCapability(NET_CAPABILITY_NOT_ROAMING)
            addCapability(NET_CAPABILITY_NOT_SUSPENDED)
            addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
            addCapability(NET_CAPABILITY_NOT_VPN)
            removeCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
            for (i in otherCaps) addCapability(i)
        }.build()
    }

    private fun nativeNetworkConfigPhysical(netId: Int) =
        NativeNetworkConfig(
            netId,
            NativeNetworkType.PHYSICAL,
            INetd.PERMISSION_NONE,
            false, // secure
            VpnManager.TYPE_VPN_NONE,
            false // excludeLocalRoutes
        )

    private fun uidRangeConfig(netId: Int, uid: Int) = NativeUidRangeConfig(
        netId,
        arrayOf(UidRangeParcel(uid, uid)),
        PREFERENCE_ORDER_DEBUG_FALLBACK
    )

    private fun createSatelliteNetwork() = Agent(
        lp = defaultLp().apply{ interfaceName = "satellite0" },
        nc = ncForTransport(TRANSPORT_SATELLITE),
        score = defaultScore() // Do not keep connected if there are no requests.
    ).apply { connect() }.network

    @SuppressLint("MissingPermission")
    @Test @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testDebugFallbackNetwork() {
        assumeTrue(Build.isDebuggable())

        val myUid = Process.myUid()
        val inOrder = inOrder(netd)
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)

        val defaultCb = TestableNetworkCallback()
        cm.registerDefaultNetworkCallback(defaultCb)

        // Set myUid to default to satellite.
        handleShellCommand(
            "set-debug-fallback-network-for-uid $myUid $TRANSPORT_SATELLITE"
        )

        // When satellite connects, it becomes the default network for myUid.
        val satelliteNetwork = createSatelliteNetwork()
        val satelliteNetId = satelliteNetwork.netId
        cb.expectAvailableCallbacks(satelliteNetwork, validated = false)
        defaultCb.expectAvailableCallbacks(satelliteNetwork, validated = false)

        inOrder.verify(netd).networkCreate(nativeNetworkConfigPhysical(satelliteNetId))
        inOrder.verify(netd).networkAddUidRangesParcel(uidRangeConfig(satelliteNetId, myUid))

        // When wifi connects, satellite is no longer myUid's default and gets torn down.
        val wifiAgent = Agent(ncForTransport(
            TRANSPORT_WIFI,
            intArrayOf(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
        ))
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)
        defaultCb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        cb.expect<Losing>(satelliteNetwork)
        cb.expect<Lost>(satelliteNetwork)
        inOrder.verify(netd).networkRemoveUidRangesParcel(uidRangeConfig(satelliteNetId, myUid))
        waitForIdle() // Network teardown is not guaranteed to have happened when onLost fires.
        inOrder.verify(netd).networkDestroy(satelliteNetId)

        // When wifi disconnects, satellite becomes the default again.
        wifiAgent.disconnect()
        cb.expect<Lost>(wifiAgent.network)
        defaultCb.expect<Lost>(wifiAgent.network)

        val satelliteNetwork2 = createSatelliteNetwork()
        val satelliteNetId2 = satelliteNetwork2.netId
        cb.expectAvailableCallbacks(satelliteNetwork2, validated = false)
        defaultCb.expectAvailableCallbacks(satelliteNetwork2, validated = false)

        inOrder.verify(netd).networkCreate(nativeNetworkConfigPhysical(satelliteNetId2))
        inOrder.verify(netd).networkAddUidRangesParcel(uidRangeConfig(satelliteNetId2, myUid))

        // Set myUid to no longer default to satellite, and expect satellite to disconnect.
        // It cannot be the system default network because it's bandwidth constrained.
        handleShellCommand("clear-debug-fallback-network-for-uid ${Process.myUid()}")
        cb.expect<Lost>(satelliteNetwork2)
        defaultCb.expect<Lost>(satelliteNetwork2)

        inOrder.verify(netd).networkRemoveUidRangesParcel(uidRangeConfig(satelliteNetId2, myUid))
        waitForIdle() // Network teardown is not guaranteed to have happened when onLost fires.
        inOrder.verify(netd).networkDestroy(satelliteNetId2)
    }
}
