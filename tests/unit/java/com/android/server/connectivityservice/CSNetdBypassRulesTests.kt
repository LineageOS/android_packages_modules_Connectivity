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

import android.net.INetworkAgent
import android.net.LinkProperties
import android.net.LocalNetworkConfig
import android.net.Network
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkInfo
import android.net.NetworkProvider
import android.net.NetworkScore
import android.os.Binder
import android.os.Build
import android.os.RemoteException
import android.os.ServiceSpecificException
import android.system.OsConstants
import androidx.test.filters.SmallTest
import com.android.server.ConnectivityService.CaptivePortalImpl
import com.android.server.connectivity.NetworkAgentInfo
import com.android.server.connectivity.QosCallbackTracker
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.postAndWait
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.InOrder
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never

private const val TEST_UID = 101
private const val TEST_TIMEOUT_MS = 500L

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSNetdBypassRulesTests : CSTest() {
    private val TEST_LINGER_DELAY_MS = 400

    @get:Rule val ignoreRule = DevSdkIgnoreRule()

    private val caller = mock(CaptivePortalImpl::class.java)
    private val na = mock(INetworkAgent::class.java)
    private val info = mock(NetworkInfo::class.java)
    private val lp = mock(LinkProperties::class.java)
    private val nc = mock(NetworkCapabilities::class.java)
    private val score = mock(NetworkScore::class.java)
    private val config = mock(NetworkAgentConfig::class.java)
    private val localNetworkConfig = FromS(mock(LocalNetworkConfig::class.java))
    private val qosCallbackTracker = mock(QosCallbackTracker::class.java)

    private fun createNetworkAgentInfo(network: Network): NetworkAgentInfo {
        return NetworkAgentInfo(na, network, info, lp, nc, localNetworkConfig?.value, score,
                context, csHandler, config, service, netd, dnsResolver, NetworkProvider.ID_NONE,
                Binder.getCallingUid(), false /* isAppSpecificNetwork */, TEST_LINGER_DELAY_MS,
                qosCallbackTracker, CSDeps())
    }

    private fun verifySetAllowBypassVpnOnNetwork(
        inOrder: InOrder,
        allow: Boolean,
        netId: Int
    ) {
        inOrder.verify(netd).networkAllowBypassVpnOnNetwork(eq(allow), eq(TEST_UID), eq(netId))
    }

    private fun verifySetAllowBypassPrivateDnsOnNetwork(
        inOrder: InOrder,
        allow: Boolean,
        netId: Int
    ) {
        inOrder.verify(
            dnsResolver
        ).setAllowBypassPrivateDnsOnNetwork(eq(netId), eq(TEST_UID), eq(allow))
    }

    private fun verifyNeverSetAllowBypassPrivateDnsOnNetwork(inOrder: InOrder) {
        inOrder.verify(
            dnsResolver,
            never()
        ).setAllowBypassPrivateDnsOnNetwork(anyInt(), anyInt(), anyBoolean())
    }

    // Always connect a wifi NetworkAgent before running each test case. This guarantees
    // that the handlers (like the DNS resolver or netd) have become idle and completed
    // any pending tasks.
    //
    // Otherwise, a running service might interfere with a mocked dependency. For example,
    // if the mocked netd is still active when a test tries to simulate an API call and throw
    // exception, then it will break the mock.
    private fun createWifiAgentAndConnect() = Agent(
        "wlan0",
        TRANSPORT_WIFI,
        NET_CAPABILITY_INTERNET
    ).also { it.connect() }

    private fun withWifiAgent(what: (CSAgentWrapper) -> Unit) {
        val wifiAgent = createWifiAgentAndConnect()
        what(wifiAgent)
        wifiAgent.disconnect()
    }

    private fun throwExceptionIfAllowingBypassVpn(e: Exception, allow: Boolean, netId: Int) {
        doThrow(
            e
        ).`when`(netd).networkAllowBypassVpnOnNetwork(
            eq(allow),
            eq(TEST_UID),
            eq(netId)
        )
    }

    private fun throwExceptionIfAllowingBypassPrivateDns(e: Exception, allow: Boolean, netId: Int) {
        doThrow(
            e
        ).`when`(dnsResolver).setAllowBypassPrivateDnsOnNetwork(
            eq(netId),
            eq(TEST_UID),
            eq(allow)
        )
    }

    @Test
    fun testNetdBypassRules_allowBypass() {
        withWifiAgent { wifiAgent ->
            val netId = wifiAgent.network.netId
            val inOrder = inOrder(netd, dnsResolver)
            val nai = createNetworkAgentInfo(wifiAgent.network)
            val res = csHandler.postAndWait { nai.setCaptivePortalDelegateUid(caller, TEST_UID) }
            assertEquals(0, res)
            verifySetAllowBypassVpnOnNetwork(inOrder, true /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, true /* allow */, netId)
            inOrder.verifyNoMoreInteractions()
        }
    }

    @Test
    fun testNetdBypassRules_allowBypassVpnFailure_remoteException() {
        withWifiAgent { wifiAgent ->
            val netId = wifiAgent.network.netId
            val inOrder = inOrder(netd, dnsResolver)

            throwExceptionIfAllowingBypassVpn(RemoteException(), true /* allow */, netId)

            val nai = createNetworkAgentInfo(wifiAgent.network)
            val res = csHandler.postAndWait { nai.setCaptivePortalDelegateUid(caller, TEST_UID) }
            assertEquals(OsConstants.EIO, res)
            verifyNeverSetAllowBypassPrivateDnsOnNetwork(inOrder)
        }
    }

    @Test
    fun testNetdBypassRules_allowBypassVpnFailure_delegateUidAlreadyExists() {
        withWifiAgent { wifiAgent ->
            val netId = wifiAgent.network.netId
            val inOrder = inOrder(netd, dnsResolver)

            throwExceptionIfAllowingBypassVpn(
                ServiceSpecificException(OsConstants.EEXIST),
                true /* allow */,
                netId
            )

            val nai = createNetworkAgentInfo(wifiAgent.network)
            // EEXIST indicates that there is already a delegate UID corresponding to the captive
            // portal caller, therefore, this API will return 0 for success.
            val res = csHandler.postAndWait { nai.setCaptivePortalDelegateUid(caller, TEST_UID) }
            assertEquals(0, res)

            verifySetAllowBypassVpnOnNetwork(inOrder, true /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, true /* allow */, netId)
        }
    }

    @Test
    fun testNetdBypassRules_allowBypassPrivateDnsFailure_remoteException() {
        withWifiAgent { wifiAgent ->
            val netId = wifiAgent.network.netId
            val inOrder = inOrder(netd, dnsResolver)

            throwExceptionIfAllowingBypassPrivateDns(RemoteException(), true /* allow */, netId)

            // Verify that VPN bypass rule will be restored if the private DNS bypass fails.
            // While this order is not really contractual, the current implementation will
            // set VPN to true first and call it again with false later if private DNS bypass
            // fails.
            // TODO : don't depend on the order
            val nai = createNetworkAgentInfo(wifiAgent.network)
            val res = csHandler.postAndWait { nai.setCaptivePortalDelegateUid(caller, TEST_UID) }
            assertEquals(OsConstants.EIO, res)
            verifySetAllowBypassVpnOnNetwork(inOrder, false /* allow */, netId)
            inOrder.verifyNoMoreInteractions()
        }
    }

    @Test
    fun testNetdBypassRules_allowBypassPrivateDnsFailure_delegateUidAlreadyExists() {
        withWifiAgent { wifiAgent ->
            val netId = wifiAgent.network.netId
            val inOrder = inOrder(netd, dnsResolver)

            throwExceptionIfAllowingBypassPrivateDns(
                ServiceSpecificException(OsConstants.EEXIST),
                true /* allow */,
                netId
            )

            // EEXIST indicates that there is already a delegate UID corresponding to the captive
            // portal caller, therefore, this API will return 0 for success.
            val nai = createNetworkAgentInfo(wifiAgent.network)
            val res = csHandler.postAndWait { nai.setCaptivePortalDelegateUid(caller, TEST_UID) }
            assertEquals(0, res)

            verifySetAllowBypassVpnOnNetwork(inOrder, true /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, true /* allow */, netId)
        }
    }

    @Test
    fun testNetdBypassRules_disallowBypass() {
        withWifiAgent { wifiAgent ->
            val netId = wifiAgent.network.netId
            val inOrder = inOrder(netd, dnsResolver)

            val nai = createNetworkAgentInfo(wifiAgent.network)
            val res = csHandler.postAndWait { nai.setCaptivePortalDelegateUid(caller, TEST_UID) }
            assertEquals(0, res)

            verifySetAllowBypassVpnOnNetwork(inOrder, true /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, true /* allow */, netId)

            val res2 = csHandler.postAndWait { nai.removeCaptivePortalDelegateUid(caller) }
            assertEquals(0, res)

            verifySetAllowBypassVpnOnNetwork(inOrder, false /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, false /* allow */, netId)
        }
    }

    @Test
    fun testNetdBypassRules_disallowBypassVpnFailure_remoteException() {
        withWifiAgent { wifiAgent ->
            val netId = wifiAgent.network.netId
            val inOrder = inOrder(netd, dnsResolver)

            throwExceptionIfAllowingBypassVpn(RemoteException(), false /* allow */, netId)

            val nai = createNetworkAgentInfo(wifiAgent.network)
            val res = csHandler.postAndWait { nai.setCaptivePortalDelegateUid(caller, TEST_UID) }
            assertEquals(0, res)

            verifySetAllowBypassVpnOnNetwork(inOrder, true /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, true /* allow */, netId)

            // If a remote exception is thrown while disallowing the bypass VPN rule, the function
            // call will return an EIO and log this failure. But the operation will continue to
            // attempt restoring the bypass rule for private DNS. The final return value will then
            // be determined by the outcome of that particular call.
            val res2 = csHandler.postAndWait { nai.removeCaptivePortalDelegateUid(caller) }
            assertEquals(0, res2)
            verifySetAllowBypassVpnOnNetwork(inOrder, false /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, false /* allow */, netId)
        }
    }

    @Test
    fun testNetdBypassRules_disallowBypassVpnFailure_delegateUidNotExist() {
        withWifiAgent { wifiAgent ->
            val netId = wifiAgent.network.netId
            val inOrder = inOrder(netd, dnsResolver)

            throwExceptionIfAllowingBypassVpn(
                ServiceSpecificException(OsConstants.ENOENT),
                false /* allow */,
                netId
            )

            val nai = createNetworkAgentInfo(wifiAgent.network)
            val res = csHandler.postAndWait { nai.setCaptivePortalDelegateUid(caller, TEST_UID) }
            assertEquals(0, res)

            verifySetAllowBypassVpnOnNetwork(inOrder, true /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, true /* allow */, netId)

            // In rare cases, attempts to disallow the VPN bypass rule will throw an ENOENT error, this
            // indicates that there is no delegated UID corresponding to the captive portal caller.
            // Since the delegated UID does not exist, this is not considered an error (return 0), but
            // a terrible failure is logged to see how often this issue happens, see
            // {@link NetworkAgentInfo#setCaptivePortalDelegateUid}. Besides, also restore the private
            // DNS bypass rule to its default state (disallowed) before returning.
            val res2 = csHandler.postAndWait { nai.removeCaptivePortalDelegateUid(caller) }
            assertEquals(0, res2)

            verifySetAllowBypassVpnOnNetwork(inOrder, false /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, false /* allow */, netId)
        }
    }

    @Test
    fun testNetdBypassRules_disallowBypassPrivateDnsFailure_remoteException() {
        withWifiAgent { wifiAgent ->
            val netId = wifiAgent.network.netId
            val inOrder = inOrder(netd, dnsResolver)

            throwExceptionIfAllowingBypassPrivateDns(RemoteException(), false /* allow */, netId)

            val nai = createNetworkAgentInfo(wifiAgent.network)
            val res = csHandler.postAndWait { nai.setCaptivePortalDelegateUid(caller, TEST_UID) }
            assertEquals(0, res)

            verifySetAllowBypassVpnOnNetwork(inOrder, true /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, true /* allow */, netId)

            val res2 = csHandler.postAndWait { nai.removeCaptivePortalDelegateUid(caller) }
            assertEquals(OsConstants.EIO, res2)

            verifySetAllowBypassVpnOnNetwork(inOrder, false /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, false /* allow */, netId)
        }
    }

    @Test
    fun testNetdBypassRules_disallowBypassPrivateDnsFailure_delegateUidNotExist() {
        withWifiAgent { wifiAgent ->
            val netId = wifiAgent.network.netId
            val inOrder = inOrder(netd, dnsResolver)

            throwExceptionIfAllowingBypassPrivateDns(
                ServiceSpecificException(OsConstants.ENOENT),
                false /* allow */,
                netId
            )

            val nai = createNetworkAgentInfo(wifiAgent.network)
            val res = csHandler.postAndWait { nai.setCaptivePortalDelegateUid(caller, TEST_UID) }
            assertEquals(0, res)

            verifySetAllowBypassVpnOnNetwork(inOrder, true /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, true /* allow */, netId)

            // In rare cases, attempts to disallow the VPN bypass rule will throw an ENOENT error, this
            // indicates that there is no delegated UID corresponding to the captive portal caller.
            // Since the delegated UID does not exist, this is not considered an error (return 0), but
            // a terrible failure is logged, see {@link NetworkAgentInfo#removeCaptivePortalDelegateUid}.
            val res2 = csHandler.postAndWait { nai.removeCaptivePortalDelegateUid(caller) }
            assertEquals(0, res2)

            verifySetAllowBypassVpnOnNetwork(inOrder, false /* allow */, netId)
            verifySetAllowBypassPrivateDnsOnNetwork(inOrder, false /* allow */, netId)
        }
    }
}
