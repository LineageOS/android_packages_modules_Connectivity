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

import android.Manifest.permission.NETWORK_STACK
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.CaptivePortal
import android.net.ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN
import android.net.ConnectivityManager.EXTRA_CAPTIVE_PORTAL
import android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.NetworkStack
import android.os.Build
import android.os.Bundle
import android.os.ConditionVariable
import android.os.OutcomeReceiver
import android.os.ServiceSpecificException
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import kotlin.test.DefaultAsserter.assertTrue
import kotlin.test.assertEquals
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

private const val APP1_UID = 10001
private const val APP2_UID = 10002
private const val TIMEOUT_MS = 2_000L

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSCaptivePortalAppTest : CSTest() {
    private val WIFI_IFACE = "wifi0"
    private val ETHERNET_IFACE = "eth0"
    private val TEST_REDIRECT_URL = "http://example.com/firstPath"

    @get:Rule val ignoreRule = DevSdkIgnoreRule()

    private class FakeOutcomeReceiver<R, E : Throwable> : OutcomeReceiver<R, E> {
        private val mCv = ConditionVariable()
        private var mError: E? = null

        override fun onResult(result: R) {
            mCv.open()
        }

        override fun onError(error: E) {
            mError = error
            mCv.open()
        }

        fun awaitOutcome() {
            assertTrue(
                "OutcomeReceiver did not receive outcome after $TIMEOUT_MS ms",
                    mCv.block(TIMEOUT_MS)
            )
            if (mError != null) {
                fail("OutcomeReceiver got: " + mError!!.message)
            }
        }
    }

    /**
     * Helper extension function to reduce boilerplate in the test.
     */
    private fun CaptivePortal.setDelegateUidAndAwait(uid: Int) {
        val or = FakeOutcomeReceiver<Void, ServiceSpecificException>()
        this.setDelegateUid(uid, CSTestExecutor, or)
        or.awaitOutcome()
    }

    private fun connectToNetworkWithPortal(
        iface: String,
        transportType: Int
    ): Pair<CSAgentWrapper, CaptivePortal> {
        val captivePortalCallback = TestableNetworkCallback()
        val captivePortalRequest = NetworkRequest.Builder()
            .addTransportType(transportType)
            .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build()
        cm.registerNetworkCallback(captivePortalRequest, captivePortalCallback)
        val agent = Agent(iface, transportType, NET_CAPABILITY_INTERNET)
        agent.connectWithCaptivePortal(TEST_REDIRECT_URL)
        captivePortalCallback.expectAvailableCallbacksUnvalidated(agent)

        val signInIntent = startCaptivePortalApp(agent)
        val captivePortal: CaptivePortal = signInIntent.getParcelableExtra(
            EXTRA_CAPTIVE_PORTAL
        )!!

        cm.unregisterNetworkCallback(captivePortalCallback)

        return Pair(agent, captivePortal)
    }

    private fun connectToWifiWithPortal(iface: String = WIFI_IFACE):
            Pair<CSAgentWrapper, CaptivePortal> {
        return connectToNetworkWithPortal(iface, TRANSPORT_WIFI)
    }

    private fun connectToEthernetWithPortal(iface: String = ETHERNET_IFACE):
            Pair<CSAgentWrapper, CaptivePortal> {
        return connectToNetworkWithPortal(iface, TRANSPORT_ETHERNET)
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testCaptivePortalApp_SetDelegateUidForVAndAbove() {
        val (wifiAgent, captivePortal) = connectToWifiWithPortal()
        val inOrder = inOrder(netd)

        // Add the UID and check that it's added to the bypass list.
        captivePortal.setDelegateUidAndAwait(APP2_UID)
        inOrder.verify(netd).networkAllowBypassVpnOnNetwork(
            true /* allow */,
            APP2_UID,
            wifiAgent.network.netId
        )

        // Remove the UID and check that it's removed from the list.
        captivePortal.setDelegateUidAndAwait(android.os.Process.INVALID_UID)
        inOrder.verify(netd).networkAllowBypassVpnOnNetwork(
            false /* allow */,
            APP2_UID,
            wifiAgent.network.netId
        )

        // Add the UID again.
        captivePortal.setDelegateUidAndAwait(APP2_UID)
        inOrder.verify(netd).networkAllowBypassVpnOnNetwork(
            true /* allow */,
            APP2_UID,
            wifiAgent.network.netId
        )

        // Add the UID again. Nothing should change.
        captivePortal.setDelegateUidAndAwait(APP2_UID)
        inOrder.verify(netd, never()).networkAllowBypassVpnOnNetwork(
            false /* allow */,
            APP2_UID,
            wifiAgent.network.netId
        )
        inOrder.verify(netd, never()).networkAllowBypassVpnOnNetwork(
            true /* allow */,
            APP2_UID,
            wifiAgent.network.netId
        )

        // Add another UID again. The old UID should be removed and the new one added.
        captivePortal.setDelegateUidAndAwait(APP1_UID)
        inOrder.verify(netd).networkAllowBypassVpnOnNetwork(
            false /* allow */,
            APP2_UID,
            wifiAgent.network.netId
        )
        inOrder.verify(netd).networkAllowBypassVpnOnNetwork(
            true /* allow */,
            APP1_UID,
            wifiAgent.network.netId
        )

        wifiAgent.disconnect()
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testCaptivePortalApp_SetDelegateUidForUAndBelow() {
        val (wifiAgent, captivePortal) = connectToWifiWithPortal(WIFI_IFACE)
        val inOrder = inOrder(netd)

        // Add the UID and check that it's added to the bypass list.
        captivePortal.setDelegateUidAndAwait(APP2_UID)
        inOrder.verify(netd).networkSetProtectAllow(APP2_UID)

        // Remove the UID and check that it's removed from the list.
        captivePortal.setDelegateUidAndAwait(android.os.Process.INVALID_UID)
        inOrder.verify(netd).networkSetProtectDeny(APP2_UID)

        // Add the UID again.
        captivePortal.setDelegateUidAndAwait(APP2_UID)
        inOrder.verify(netd).networkSetProtectAllow(APP2_UID)

        // Add another UID again. The old UID should be removed and the new one added.
        captivePortal.setDelegateUidAndAwait(APP1_UID)
        inOrder.verify(netd).networkSetProtectDeny(APP2_UID)
        inOrder.verify(netd).networkSetProtectAllow(APP1_UID)

        // Launch another captive portal app on a different network with the same APP1_UID.
        val (ethernetAgent, captivePortal2) = connectToEthernetWithPortal()

        // Netd API should not be called because APP1_UID is already in the allow list.
        captivePortal2.setDelegateUidAndAwait(APP1_UID)
        inOrder.verify(netd, never()).networkSetProtectAllow(anyInt())

        // Netd API should not be called because APP1_UID is still in the allow list.
        captivePortal.setDelegateUidAndAwait(android.os.Process.INVALID_UID)
        inOrder.verify(netd, never()).networkSetProtectDeny(anyInt())

        wifiAgent.disconnect()
        ethernetAgent.disconnect()
    }

    @Test
    fun testCaptivePortalApp_Reevaluate_Nopermission() {
        val captivePortalCallback = TestableNetworkCallback()
        val captivePortalRequest = NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build()
        cm.registerNetworkCallback(captivePortalRequest, captivePortalCallback)
        val wifiAgent = Agent(WIFI_IFACE, TRANSPORT_WIFI, NET_CAPABILITY_INTERNET)
        wifiAgent.connectWithCaptivePortal(TEST_REDIRECT_URL)
        captivePortalCallback.expectAvailableCallbacksUnvalidated(wifiAgent)
        val signInIntent = startCaptivePortalApp(wifiAgent)
        // Remove the granted permissions
        context.setPermission(
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                PERMISSION_DENIED
        )
        context.setPermission(NETWORK_STACK, PERMISSION_DENIED)
        val captivePortal: CaptivePortal? = signInIntent.getParcelableExtra(EXTRA_CAPTIVE_PORTAL)
        captivePortal?.reevaluateNetwork()
        verify(wifiAgent.networkMonitor, never()).forceReevaluation(anyInt())
    }

    private fun startCaptivePortalApp(networkAgent: CSAgentWrapper): Intent {
        val network = networkAgent.network
        cm.startCaptivePortalApp(network)
        waitForIdle()
        verify(networkAgent.networkMonitor).launchCaptivePortalApp()

        val testBundle = Bundle()
        val testKey = "testkey"
        val testValue = "testvalue"
        testBundle.putString(testKey, testValue)
        context.setPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, PERMISSION_GRANTED)
        cm.startCaptivePortalApp(network, testBundle)
        val signInIntent: Intent = context.expectStartActivityIntent(TIMEOUT_MS)
        assertEquals(ACTION_CAPTIVE_PORTAL_SIGN_IN, signInIntent.getAction())
        assertEquals(testValue, signInIntent.getStringExtra(testKey))
        return signInIntent
    }
}
