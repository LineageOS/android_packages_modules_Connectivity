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

import android.net.ConnectivityManager.BLOCKED_METERED_REASON_DATA_SAVER
import android.net.ConnectivityManager.BLOCKED_METERED_REASON_USER_RESTRICTED
import android.net.ConnectivityManager.BLOCKED_REASON_APP_BACKGROUND
import android.net.ConnectivityManager.BLOCKED_REASON_DOZE
import android.net.ConnectivityManager.BLOCKED_REASON_NETWORK_RESTRICTED
import android.net.ConnectivityManager.BLOCKED_REASON_NONE
import android.net.ConnectivityManager.FIREWALL_CHAIN_BACKGROUND
import android.net.ConnectivityManager.FIREWALL_CHAIN_DOZABLE
import android.net.ConnectivityManager.FIREWALL_CHAIN_METERED_DENY_USER
import android.net.ConnectivityManager.FIREWALL_RULE_ALLOW
import android.net.ConnectivityManager.FIREWALL_RULE_DENY
import android.net.ConnectivitySettingsManager
import android.net.INetd.PERMISSION_NONE
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.connectivity.ConnectivityCompatChanges.NETWORK_BLOCKED_WITHOUT_INTERNET_PERMISSION
import android.os.Build
import android.os.Process
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.BlockedStatusInt
import com.android.testutils.TestableNetworkCallback
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.doReturn

private fun cellNc() = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_CELLULAR)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()
private fun cellRequest() = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_CELLULAR)
        .build()
private fun wifiNc() = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_WIFI)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .addCapability(NET_CAPABILITY_NOT_METERED)
        .build()
private fun wifiRequest() = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_WIFI)
        .build()

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSBlockedReasonsTest : CSTest() {

    inner class DetailedBlockedStatusCallback : TestableNetworkCallback() {
        override fun onBlockedStatusChanged(network: Network, blockedReasons: Int) {
            history.add(BlockedStatusInt(network, blockedReasons))
        }

        fun expectBlockedStatusChanged(network: Network, blockedReasons: Int) {
            expect<BlockedStatusInt>(network) { it.reason == blockedReasons }
        }
    }

    @Test
    fun testBlockedReasons_onAvailable() {
        doReturn(BLOCKED_REASON_DOZE or BLOCKED_METERED_REASON_DATA_SAVER)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())

        val cellAgent = Agent(nc = cellNc())
        cellAgent.connect()
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()

        val cellCb = DetailedBlockedStatusCallback()
        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(cellRequest(), cellCb)
        cm.requestNetwork(wifiRequest(), wifiCb)

        cellCb.expectAvailableCallbacks(
                cellAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_DOZE or BLOCKED_METERED_REASON_DATA_SAVER
        )
        wifiCb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_DOZE
        )

        cellAgent.disconnect()
        wifiAgent.disconnect()
        cm.unregisterNetworkCallback(cellCb)
        cm.unregisterNetworkCallback(wifiCb)
    }

    @Test
    fun testBlockedReasons_dataSaverChanged() {
        doReturn(BLOCKED_REASON_APP_BACKGROUND or BLOCKED_METERED_REASON_DATA_SAVER)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        doReturn(true).`when`(netd).bandwidthEnableDataSaver(anyBoolean())

        val cellCb = DetailedBlockedStatusCallback()
        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(cellRequest(), cellCb)
        cm.requestNetwork(wifiRequest(), wifiCb)

        val cellAgent = Agent(nc = cellNc())
        cellAgent.connect()
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()
        cellCb.expectAvailableCallbacks(
                cellAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_APP_BACKGROUND or BLOCKED_METERED_REASON_DATA_SAVER
        )
        wifiCb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_APP_BACKGROUND
        )

        // Disable data saver
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setDataSaverEnabled(false)
        cellCb.expectBlockedStatusChanged(cellAgent.network, BLOCKED_REASON_APP_BACKGROUND)

        // waitForIdle since stubbing bpfNetMaps while CS handler thread calls
        // bpfNetMaps.getNetPermForUid throws exception.
        // The expectBlockedStatusChanged just above guarantees that the onBlockedStatusChanged
        // method on this callback was called, but it does not guarantee that ConnectivityService
        // has finished processing all onBlockedStatusChanged callbacks for all requests.
        waitForIdle()
        // Enable data saver
        doReturn(BLOCKED_REASON_APP_BACKGROUND or BLOCKED_METERED_REASON_DATA_SAVER)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setDataSaverEnabled(true)
        cellCb.expectBlockedStatusChanged(
                cellAgent.network,
                BLOCKED_REASON_APP_BACKGROUND or BLOCKED_METERED_REASON_DATA_SAVER
        )
        // BlockedStatus does not change for the non-metered network
        wifiCb.assertNoCallback()

        cellAgent.disconnect()
        wifiAgent.disconnect()
        cm.unregisterNetworkCallback(cellCb)
        cm.unregisterNetworkCallback(wifiCb)
    }

    @Test
    fun testBlockedReasons_setUidFirewallRule() {
        doReturn(BLOCKED_REASON_DOZE or BLOCKED_METERED_REASON_USER_RESTRICTED)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())

        val cellCb = DetailedBlockedStatusCallback()
        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(cellRequest(), cellCb)
        cm.requestNetwork(wifiRequest(), wifiCb)

        val cellAgent = Agent(nc = cellNc())
        cellAgent.connect()
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()
        cellCb.expectAvailableCallbacks(
                cellAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_DOZE or BLOCKED_METERED_REASON_USER_RESTRICTED
        )
        wifiCb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_DOZE
        )

        // waitForIdle since stubbing bpfNetMaps while CS handler thread calls
        // bpfNetMaps.getNetPermForUid throws exception.
        // The expectBlockedStatusChanged just above guarantees that the onBlockedStatusChanged
        // method on this callback was called, but it does not guarantee that ConnectivityService
        // has finished processing all onBlockedStatusChanged callbacks for all requests.
        waitForIdle()
        // Set RULE_ALLOW on metered deny chain
        doReturn(BLOCKED_REASON_DOZE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_METERED_DENY_USER,
                Process.myUid(),
                FIREWALL_RULE_ALLOW
        )
        cellCb.expectBlockedStatusChanged(
                cellAgent.network,
                BLOCKED_REASON_DOZE
        )
        // BlockedStatus does not change for the non-metered network
        wifiCb.assertNoCallback()

        // Set RULE_DENY on metered deny chain
        doReturn(BLOCKED_REASON_DOZE or BLOCKED_METERED_REASON_USER_RESTRICTED)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_METERED_DENY_USER,
                Process.myUid(),
                FIREWALL_RULE_DENY
        )
        cellCb.expectBlockedStatusChanged(
                cellAgent.network,
                BLOCKED_REASON_DOZE or BLOCKED_METERED_REASON_USER_RESTRICTED
        )
        // BlockedStatus does not change for the non-metered network
        wifiCb.assertNoCallback()

        cellAgent.disconnect()
        wifiAgent.disconnect()
        cm.unregisterNetworkCallback(cellCb)
        cm.unregisterNetworkCallback(wifiCb)
    }

    @Test
    fun testBlockedReasons_setFirewallChainEnabled() {
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())

        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(wifiRequest(), wifiCb)
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()
        wifiCb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_NONE
        )

        // Enable dozable firewall chain
        doReturn(BLOCKED_REASON_DOZE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setFirewallChainEnabled(FIREWALL_CHAIN_DOZABLE, true)
        wifiCb.expectBlockedStatusChanged(
                wifiAgent.network,
                BLOCKED_REASON_DOZE
        )

        // Disable dozable firewall chain
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setFirewallChainEnabled(FIREWALL_CHAIN_DOZABLE, false)
        wifiCb.expectBlockedStatusChanged(
                wifiAgent.network,
                BLOCKED_REASON_NONE
        )

        wifiAgent.disconnect()
        cm.unregisterNetworkCallback(wifiCb)
    }

    @Test
    fun testBlockedReasons_replaceFirewallChain() {
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())

        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(wifiRequest(), wifiCb)
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()
        wifiCb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_APP_BACKGROUND
        )

        // Put uid on background firewall chain
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.replaceFirewallChain(FIREWALL_CHAIN_BACKGROUND, intArrayOf(Process.myUid()))
        wifiCb.expectBlockedStatusChanged(
                wifiAgent.network,
                BLOCKED_REASON_NONE
        )

        // Remove uid from background firewall chain
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.replaceFirewallChain(FIREWALL_CHAIN_BACKGROUND, intArrayOf())
        wifiCb.expectBlockedStatusChanged(
                wifiAgent.network,
                BLOCKED_REASON_APP_BACKGROUND
        )

        wifiAgent.disconnect()
        cm.unregisterNetworkCallback(wifiCb)
    }

    @Test
    fun testBlockedReasons_perAppDefaultNetwork() {
        doReturn(BLOCKED_METERED_REASON_USER_RESTRICTED)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())

        val cellCb = DetailedBlockedStatusCallback()
        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(cellRequest(), cellCb)
        cm.requestNetwork(wifiRequest(), wifiCb)

        val cellAgent = Agent(nc = cellNc())
        cellAgent.connect()
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()

        val cb = DetailedBlockedStatusCallback()
        cm.registerDefaultNetworkCallback(cb)
        cb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_NONE
        )

        // CS must send correct blocked reasons after per app default network change
        ConnectivitySettingsManager.setMobileDataPreferredUids(context, setOf(Process.myUid()))
        service.updateMobileDataPreferredUids()
        cb.expectAvailableCallbacks(
                cellAgent.network,
                validated = false,
                blockedReason = BLOCKED_METERED_REASON_USER_RESTRICTED
        )

        // Remove per app default network request
        ConnectivitySettingsManager.setMobileDataPreferredUids(context, setOf())
        service.updateMobileDataPreferredUids()
        cb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_NONE
        )

        cellAgent.disconnect()
        wifiAgent.disconnect()
        cm.unregisterNetworkCallback(cellCb)
        cm.unregisterNetworkCallback(wifiCb)
        cm.unregisterNetworkCallback(cb)
    }

    private fun doTestBlockedReasonsNoInternetPermission(blockedByNoInternetPermission: Boolean) {
        doReturn(PERMISSION_NONE).`when`(bpfNetMaps).getNetPermForUid(Process.myUid())

        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(wifiRequest(), wifiCb)
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()
        val expectedBlockedReason = if (blockedByNoInternetPermission) {
            BLOCKED_REASON_NETWORK_RESTRICTED
        } else {
            BLOCKED_REASON_NONE
        }
        wifiCb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = expectedBlockedReason
        )

        // Enable background firewall chain
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setFirewallChainEnabled(FIREWALL_CHAIN_BACKGROUND, true)
        if (blockedByNoInternetPermission) {
            wifiCb.expectBlockedStatusChanged(
                    wifiAgent.network,
                    BLOCKED_REASON_NETWORK_RESTRICTED or BLOCKED_REASON_APP_BACKGROUND
            )
        }
        // waitForIdle since stubbing bpfNetMaps while CS handler thread calls
        // bpfNetMaps.getNetPermForUid throws exception.
        // ConnectivityService might haven't finished checking blocked status for all requests.
        waitForIdle()

        // Disable background firewall chain
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setFirewallChainEnabled(FIREWALL_CHAIN_BACKGROUND, false)
        if (blockedByNoInternetPermission) {
            wifiCb.expectBlockedStatusChanged(
                    wifiAgent.network,
                    BLOCKED_REASON_NETWORK_RESTRICTED
            )
        } else {
            // No callback is expected since blocked reasons does not change from
            // BLOCKED_REASON_NONE.
            wifiCb.assertNoCallback()
        }
    }

    @Test
    fun testBlockedReasonsNoInternetPermission_changeDisabled() {
        deps.setChangeIdEnabled(false, NETWORK_BLOCKED_WITHOUT_INTERNET_PERMISSION)
        doTestBlockedReasonsNoInternetPermission(blockedByNoInternetPermission = false)
    }

    @Test
    fun testBlockedReasonsNoInternetPermission_changeEnabled() {
        deps.setChangeIdEnabled(true, NETWORK_BLOCKED_WITHOUT_INTERNET_PERMISSION)
        doTestBlockedReasonsNoInternetPermission(blockedByNoInternetPermission = true)
    }
}
