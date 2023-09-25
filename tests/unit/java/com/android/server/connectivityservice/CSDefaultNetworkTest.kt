package com.android.server.connectivityservice

import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkRequest
import android.os.Build
import com.android.server.CSTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import com.android.testutils.TestableNetworkCallback
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNull
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET as NET_CAP_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH as NET_CAP_PRIO_BW

private fun netCaps(transport: Int, vararg cap: Int): NetworkCapabilities =
        NetworkCapabilities.Builder().apply {
            addTransportType(transport)
            // Standard caps that everybody in this test file needs
            addCapability(NET_CAPABILITY_NOT_SUSPENDED)
            addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
            addCapability(NET_CAPABILITY_NOT_RESTRICTED)
            cap.forEach { addCapability(it) }
        }.build()

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSDefaultNetworkTest : CSTest() {
    @Test
    fun testSlicesAreNotDefault() {
        val keepSliceUpRequest = NetworkRequest.Builder().clearCapabilities()
                .addCapability(NET_CAP_PRIO_BW)
                .build()
        val keepSliceUpCb = TestableNetworkCallback()
        cm.requestNetwork(keepSliceUpRequest, keepSliceUpCb)

        val nonSlice = Agent(nc = netCaps(TRANSPORT_CELLULAR, NET_CAP_INTERNET))
        val slice = Agent(nc = netCaps(TRANSPORT_CELLULAR, NET_CAP_INTERNET, NET_CAP_PRIO_BW))

        val allCb = TestableNetworkCallback()
        val bestCb = TestableNetworkCallback()
        val defaultCb = TestableNetworkCallback()
        val allNetRequest = NetworkRequest.Builder().clearCapabilities().build()
        cm.registerNetworkCallback(allNetRequest, allCb)
        cm.registerBestMatchingNetworkCallback(allNetRequest, bestCb, csHandler)
        cm.registerDefaultNetworkCallback(defaultCb)
        nonSlice.connect()

        allCb.expectAvailableCallbacks(nonSlice.network, validated = false)
        keepSliceUpCb.assertNoCallback()
        bestCb.expectAvailableCallbacks(nonSlice.network, validated = false)
        defaultCb.expectAvailableCallbacks(nonSlice.network, validated = false)

        slice.connect()
        allCb.expectAvailableCallbacks(slice.network, validated = false)
        keepSliceUpCb.expectAvailableCallbacks(slice.network, validated = false)
        bestCb.assertNoCallback()
        defaultCb.assertNoCallback()

        nonSlice.disconnect()
        allCb.expect<Lost>(nonSlice.network)
        bestCb.expect<Lost>(nonSlice.network)
        bestCb.expectAvailableCallbacks(slice.network, validated = false)
        defaultCb.expect<Lost>(nonSlice.network)

        allCb.assertNoCallback()
        keepSliceUpCb.assertNoCallback()
        bestCb.assertNoCallback()
        defaultCb.assertNoCallback()

        assertNull(cm.activeNetwork)
    }
}
