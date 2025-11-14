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

package com.android.server.connectivityservice

import android.app.ActivityManager.UidFrozenStateChangedCallback
import android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_FROZEN
import android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_UNFROZEN
import android.net.ConnectivityManager.BLOCKED_METERED_REASON_DATA_SAVER
import android.net.ConnectivityManager.BLOCKED_REASON_NONE
import android.net.ConnectivitySettingsManager
import android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_DNS
import android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_HTTPS
import android.net.INetworkMonitor.NETWORK_VALIDATION_RESULT_VALID
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.LocalNetworkConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_FOREGROUND
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_THREAD
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkPolicyManager.NetworkPolicyCallback
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import com.android.server.CALLING_UID_UNMOCKED
import com.android.server.CSAgentWrapper
import com.android.server.CSTest
import com.android.server.FromS
import com.android.server.HANDLER_TIMEOUT_MS
import com.android.server.connectivity.ConnectivityFlags.QUEUE_CALLBACKS_FOR_FROZEN_APPS
import com.android.server.defaultLp
import com.android.server.defaultNc
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.BlockedStatus
import com.android.testutils.TestableNetworkCallback.Event.CapabilitiesChanged
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.TestableNetworkCallback.Event.LocalInfoChanged
import com.android.testutils.TestableNetworkCallback.Event.Lost
import com.android.testutils.TestableNetworkCallback.Event.Resumed
import com.android.testutils.TestableNetworkCallback.Event.Suspended
import com.android.testutils.visibleOnHandlerThread
import com.android.testutils.waitForIdleSerialExecutor
import java.util.Collections
import kotlin.test.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.verify

private const val TEST_UID = 42

@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
class CSQueuedCallbacksTest(freezingBehavior: FreezingBehavior) : CSTest() {
    companion object {
        enum class FreezingBehavior {
            UID_FROZEN,
            UID_NOT_FROZEN,
            UID_FROZEN_FEATURE_DISABLED
        }

        // Use a parameterized test with / without freezing to make it easy to compare and make sure
        // freezing behavior (which callbacks are sent in which order) stays close to what happens
        // without freezing.
        @JvmStatic
        @Parameterized.Parameters(name = "freezingBehavior={0}")
        fun freezingBehavior() = listOf(
            FreezingBehavior.UID_FROZEN,
            FreezingBehavior.UID_NOT_FROZEN,
            FreezingBehavior.UID_FROZEN_FEATURE_DISABLED
        )

        private val TAG = CSQueuedCallbacksTest::class.simpleName
            ?: fail("Could not get test class name")
    }

    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    private val mockedBlockedReasonsPerUid = Collections.synchronizedMap(mutableMapOf(
        Process.myUid() to BLOCKED_REASON_NONE,
        TEST_UID to BLOCKED_REASON_NONE
    ))

    private val freezeUids = freezingBehavior != FreezingBehavior.UID_NOT_FROZEN
    private val expectAllCallbacks = freezingBehavior == FreezingBehavior.UID_NOT_FROZEN ||
            freezingBehavior == FreezingBehavior.UID_FROZEN_FEATURE_DISABLED
    init {
        setFeatureEnabled(
            QUEUE_CALLBACKS_FOR_FROZEN_APPS,
            freezingBehavior != FreezingBehavior.UID_FROZEN_FEATURE_DISABLED
        )
    }

    @Before
    fun subclassSetUp() {
        // Ensure cellular stays up. CS is recreated for each test so no cleanup is necessary.
//        cm.requestNetwork(
//            NetworkRequest.Builder().addTransportType(TRANSPORT_CELLULAR).build(),
//            TestableNetworkCallback()
//        )
    }

    @Test
    fun testFrozenWhileNetworkConnects_UpdatesAreReceived() {
        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        }
        val agent = Agent(TRANSPORT_CELLULAR).apply { connect() }
        val lpChangeOnConnect = agent.sendLpChange { setLinkAddresses("fe80:db8::123/64") }
        val ncChangeOnConnect = agent.sendNcChange {
            addCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        }

        maybeSetUidsFrozen(true, TEST_UID)

        val lpChange1WhileFrozen = agent.sendLpChange {
            setLinkAddresses("fe80:db8::126/64")
        }
        val ncChange1WhileFrozen = agent.sendNcChange {
            removeCapability(NET_CAPABILITY_NOT_ROAMING)
        }
        val ncChange2WhileFrozen = agent.sendNcChange {
            addCapability(NET_CAPABILITY_NOT_ROAMING)
            addCapability(NET_CAPABILITY_NOT_CONGESTED)
        }
        val lpChange2WhileFrozen = agent.sendLpChange {
            setLinkAddresses("fe80:db8::125/64")
        }
        maybeSetUidsFrozen(false, TEST_UID)

        // Verify callbacks that are sent before freezing
        cb.expectAvailableCallbacks(agent.network, validated = false)
        cb.expectLpWith(agent, lpChangeOnConnect)
        cb.expectNcWith(agent, ncChangeOnConnect)

        // Below callbacks should be skipped if the processes were frozen, since a single callback
        // will be sent with the latest state after unfreezing
        if (expectAllCallbacks) {
            cb.expectLpWith(agent, lpChange1WhileFrozen)
            cb.expectNcWith(agent, ncChange1WhileFrozen)
        }

        cb.expectNcWith(agent, ncChange2WhileFrozen)
        cb.expectLpWith(agent, lpChange2WhileFrozen)

        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testFrozenWhileNetworkConnects_SuspendedUnsuspendedWhileFrozen() {
        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        }

        val agent = Agent(TRANSPORT_CELLULAR).apply { connect() }
        maybeSetUidsFrozen(true, TEST_UID)
        val rmCap = agent.sendNcChange { removeCapability(NET_CAPABILITY_NOT_SUSPENDED) }
        val addCap = agent.sendNcChange { addCapability(NET_CAPABILITY_NOT_SUSPENDED) }

        maybeSetUidsFrozen(false, TEST_UID)

        cb.expectAvailableCallbacks(agent.network, validated = false)
        if (expectAllCallbacks) {
            cb.expectNcWith(agent, rmCap)
            cb.expect<Suspended>(agent)
            cb.expectNcWith(agent, addCap)
            cb.expect<Resumed>(agent)
        } else {
            // When frozen, a single NetworkCapabilitiesChange will be sent at unfreezing time,
            // with nc actually identical to the original ones. This is because NetworkCapabilities
            // callbacks were sent, but CS does not keep initial NetworkCapabilities in memory, so
            // it cannot detect A->B->A.
            cb.expect<CapabilitiesChanged>(agent) {
                it.caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED)
            }
        }
        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testFrozenWhileNetworkConnects_UnsuspendedWhileFrozen_GetResumedCallbackWhenUnfrozen() {
        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        }

        val agent = Agent(TRANSPORT_CELLULAR).apply { connect() }
        val rmCap = agent.sendNcChange { removeCapability(NET_CAPABILITY_NOT_SUSPENDED) }
        maybeSetUidsFrozen(true, TEST_UID)
        val addCap = agent.sendNcChange { addCapability(NET_CAPABILITY_NOT_SUSPENDED) }
        maybeSetUidsFrozen(false, TEST_UID)

        cb.expectAvailableCallbacks(agent.network, validated = false)
        cb.expectNcWith(agent, rmCap)
        cb.expect<Suspended>(agent)
        cb.expectNcWith(agent, addCap)
        cb.expect<Resumed>(agent)
        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testFrozenWhileNetworkConnects_BlockedUnblockedWhileFrozen_SingleCallbackIfFrozen() {
        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        }
        val agent = Agent(TRANSPORT_CELLULAR).apply { connect() }

        maybeSetUidsFrozen(true, TEST_UID)
        setUidsBlockedForDataSaver(true, TEST_UID)
        setUidsBlockedForDataSaver(false, TEST_UID)
        maybeSetUidsFrozen(false, TEST_UID)

        cb.expectAvailableCallbacks(agent.network, validated = false)
        if (expectAllCallbacks) {
            cb.expect<BlockedStatus>(agent) { it.blocked }
        }
        // The unblocked callback is sent in any case (with the latest blocked reason), as the
        // blocked reason may have changed, and ConnectivityService cannot know that it is the same
        // as the original reason as it does not keep pre-freeze blocked reasons in memory.
        cb.expect<BlockedStatus>(agent) { !it.blocked }
        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testFrozenWhileNetworkConnects_BlockedWhileFrozen_GetLastBlockedCallbackOnlyIfFrozen() {
        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        }
        val agent = Agent(TRANSPORT_CELLULAR).apply { connect() }

        maybeSetUidsFrozen(true, TEST_UID)
        setUidsBlockedForDataSaver(true, TEST_UID)
        setUidsBlockedForDataSaver(false, TEST_UID)
        setUidsBlockedForDataSaver(true, TEST_UID)
        maybeSetUidsFrozen(false, TEST_UID)

        cb.expectAvailableCallbacks(agent.network, validated = false)
        if (expectAllCallbacks) {
            cb.expect<BlockedStatus>(agent) { it.blocked }
            cb.expect<BlockedStatus>(agent) { !it.blocked }
        }
        cb.expect<BlockedStatus>(agent) { it.blocked }
        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testNetworkCallback_NetworkToggledWhileFrozen_NotSeen() {
        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        }
        val cellAgent = Agent(TRANSPORT_CELLULAR).apply { connect() }
        maybeSetUidsFrozen(true, TEST_UID)
        val wifiAgent = Agent(TRANSPORT_WIFI).apply { connect() }
        wifiAgent.disconnect()
        maybeSetUidsFrozen(false, TEST_UID)

        cb.expectAvailableCallbacks(cellAgent.network, validated = false)
        if (expectAllCallbacks) {
            cb.expectAvailableCallbacks(wifiAgent.network, validated = false)
            cb.expect<Lost>(wifiAgent)
        }
        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testNetworkCallback_NetworkAppearedWhileFrozen_ReceiveLatestInfoInOnAvailable() {
        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        }
        maybeSetUidsFrozen(true, TEST_UID)
        val agent = Agent(TRANSPORT_CELLULAR).apply { connect() }
        waitForIdle()
        agent.makeValidationSuccess()
        val lpChange = agent.sendLpChange {
            setLinkAddresses("fe80:db8::123/64")
        }
        val suspendedChange = agent.sendNcChange {
            removeCapability(NET_CAPABILITY_NOT_SUSPENDED)
        }
        setUidsBlockedForDataSaver(true, TEST_UID)

        maybeSetUidsFrozen(false, TEST_UID)

        val expectLatestStatusInOnAvailable = !expectAllCallbacks
        cb.expectAvailableCallbacks(
            agent.network,
            suspended = expectLatestStatusInOnAvailable,
            validated = expectLatestStatusInOnAvailable,
            blocked = expectLatestStatusInOnAvailable
        )
        if (expectAllCallbacks) {
            cb.expectNcWith(agent) { addCapability(NET_CAPABILITY_VALIDATED) }
            cb.expectLpWith(agent, lpChange)
            cb.expectNcWith(agent, suspendedChange)
            cb.expect<Suspended>(agent)
            cb.expect<BlockedStatus>(agent) { it.blocked }
        }
        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testNetworkCallback_LocalNetworkAppearedWhileFrozen_ReceiveLatestInfoInOnAvailable() {
        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NET_CAPABILITY_LOCAL_NETWORK).build(),
                cb
            )
        }
        val upstreamAgent = Agent(
            nc = defaultNc()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_INTERNET),
            lp = defaultLp().apply { interfaceName = "wlan0" }
        ).apply { connect() }
        maybeSetUidsFrozen(true, TEST_UID)

        val lnc = LocalNetworkConfig.Builder().build()
        val localAgent = Agent(
            nc = defaultNc()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .addTransportType(TRANSPORT_THREAD)
                .removeCapability(NET_CAPABILITY_INTERNET),
            lp = defaultLp().apply { interfaceName = "local42" },
            lnc = FromS(lnc)
        ).apply { connect() }
        localAgent.sendLocalNetworkConfig(
            LocalNetworkConfig.Builder()
                .setUpstreamSelector(
                    NetworkRequest.Builder()
                        .addCapability(NET_CAPABILITY_INTERNET)
                        .build()
                )
                .build()
        )

        maybeSetUidsFrozen(false, TEST_UID)

        cb.expectAvailableCallbacks(
            localAgent.network,
            validated = false,
            upstream = if (expectAllCallbacks) null else upstreamAgent.network
        )
        if (expectAllCallbacks) {
            cb.expect<LocalInfoChanged>(localAgent) {
                it.info.upstreamNetwork == upstreamAgent.network
            }
        }
        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testNetworkRequest_NetworkSwitchesWhileFrozen_ReceiveLastNetworkUpdatesOnly() {
        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.requestNetwork(NetworkRequest.Builder().build(), cb)
        }
        val cellAgent = Agent(TRANSPORT_CELLULAR).apply { connect() }
        maybeSetUidsFrozen(true, TEST_UID)
        val wifiAgent = Agent(TRANSPORT_WIFI).apply { connect() }
        val ethAgent = Agent(TRANSPORT_ETHERNET).apply { connect() }
        waitForIdle()
        ethAgent.makeValidationSuccess()
        maybeSetUidsFrozen(false, TEST_UID)

        cb.expectAvailableCallbacks(cellAgent.network, validated = false)
        if (expectAllCallbacks) {
            cb.expectAvailableCallbacks(wifiAgent.network, validated = false)
            cb.expectAvailableCallbacks(ethAgent.network, validated = false)
            cb.expectNcWith(ethAgent) { addCapability(NET_CAPABILITY_VALIDATED) }
        } else {
            cb.expectAvailableCallbacks(ethAgent.network, validated = true)
        }
        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testNetworkRequest_NetworkSwitchesBackWhileFrozen_ReceiveNoAvailableCallback() {
        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.requestNetwork(NetworkRequest.Builder().build(), cb)
        }
        val cellAgent = Agent(TRANSPORT_CELLULAR).apply { connect() }
        maybeSetUidsFrozen(true, TEST_UID)
        val wifiAgent = Agent(TRANSPORT_WIFI).apply { connect() }
        waitForIdle()

        // CS switches back to validated cell over non-validated Wi-Fi
        cellAgent.makeValidationSuccess()
        val cellLpChange = cellAgent.sendLpChange {
            setLinkAddresses("fe80:db8::123/64")
        }
        setUidsBlockedForDataSaver(true, TEST_UID)
        maybeSetUidsFrozen(false, TEST_UID)

        cb.expectAvailableCallbacks(cellAgent.network, validated = false)
        if (expectAllCallbacks) {
            cb.expectAvailableCallbacks(wifiAgent.network, validated = false)
            // There is an extra "double validated" CapabilitiesChange callback (b/245893397), so
            // callbacks are (AVAIL, NC, LP), extra NC, then further updates (LP and BLK here).
            cb.expectAvailableDoubleValidatedCallbacks(cellAgent.network)
            cb.expectLpWith(cellAgent, cellLpChange)
            cb.expect<BlockedStatus>(cellAgent) { it.blocked }
        } else {
            cb.expectNcWith(cellAgent) {
                addCapability(NET_CAPABILITY_VALIDATED)
            }
            cb.expectLpWith(cellAgent, cellLpChange)
            cb.expect<BlockedStatus>(cellAgent) { it.blocked }
        }
        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testTrackDefaultRequest_AppFrozenWhilePerAppDefaultRequestFiled_ReceiveChangeCallbacks() {
        val cellAgent = Agent(TRANSPORT_CELLULAR, baseNc = makeInternetNc()).apply { connect() }
        waitForIdle()

        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.registerDefaultNetworkCallback(cb)
        }
        maybeSetUidsFrozen(true, TEST_UID)

        // Change LinkProperties twice before the per-app network request is applied
        val lpChange1 = cellAgent.sendLpChange {
            setLinkAddresses("fe80:db8::123/64")
        }
        val lpChange2 = cellAgent.sendLpChange {
            setLinkAddresses("fe80:db8::124/64")
        }
        setMobileDataPreferredUids(setOf(TEST_UID))

        // Change NetworkCapabilities after the per-app network request is applied
        val ncChange = cellAgent.sendNcChange {
            addCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        }
        maybeSetUidsFrozen(false, TEST_UID)

        // Even if a per-app network request was filed to replace the default network request for
        // the app, all network change callbacks are received
        cb.expectAvailableCallbacks(cellAgent.network, validated = false)
        if (expectAllCallbacks) {
            cb.expectLpWith(cellAgent, lpChange1)
        }
        cb.expectLpWith(cellAgent, lpChange2)
        cb.expectNcWith(cellAgent, ncChange)
        cb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testTrackDefaultRequest_AppFrozenWhilePerAppDefaultToggled_GetStatusUpdateCallbacksOnly() {
        // Add validated Wi-Fi and non-validated cell, expect Wi-Fi is preferred by default
        val wifiAgent = Agent(TRANSPORT_WIFI, baseNc = makeInternetNc()).apply { connect() }
        wifiAgent.makeValidationSuccess()
        val cellAgent = Agent(TRANSPORT_CELLULAR, baseNc = makeInternetNc()).apply { connect() }
        waitForIdle()

        val cb = TestableNetworkCallback(logTag = TAG)
        withCallingUid(TEST_UID) {
            cm.registerDefaultNetworkCallback(cb)
        }
        maybeSetUidsFrozen(true, TEST_UID)

        // LP change on the original Wi-Fi network
        val lpChange = wifiAgent.sendLpChange {
            setLinkAddresses("fe80:db8::123/64")
        }
        // Set per-app default to cell, then unset it
        setMobileDataPreferredUids(setOf(TEST_UID))
        setMobileDataPreferredUids(emptySet())

        maybeSetUidsFrozen(false, TEST_UID)

        cb.expectAvailableCallbacks(wifiAgent.network)
        if (expectAllCallbacks) {
            cb.expectLpWith(wifiAgent, lpChange)
            cb.expectAvailableCallbacks(cellAgent.network, validated = false)
            // Cellular stops being foreground since it is now matched for this app
            cb.expect<CapabilitiesChanged> { it.caps.hasCapability(NET_CAPABILITY_FOREGROUND) }
            cb.expectAvailableCallbacks(wifiAgent.network)
        } else {
            // After switching to cell and back while frozen, only network attribute update
            // callbacks (and not AVAILABLE) for the original Wi-Fi network should be sent
            cb.expect<CapabilitiesChanged>(wifiAgent)
            cb.expectLpWith(wifiAgent, lpChange)
            cb.expect<BlockedStatus> { !it.blocked }
        }
        cb.assertNoCallback(timeoutMs = 0L)
    }

    private fun setUidsBlockedForDataSaver(blocked: Boolean, vararg uid: Int) {
        val reason = if (blocked) {
            BLOCKED_METERED_REASON_DATA_SAVER
        } else {
            BLOCKED_REASON_NONE
        }
        if (deps.isAtLeastV) {
            visibleOnHandlerThread(csHandler) {
                service.handleBlockedReasonsChanged(uid.map { android.util.Pair(it, reason) })
            }
        } else {
            notifyLegacyBlockedReasonChanged(reason, uid)
            waitForIdle()
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyLegacyBlockedReasonChanged(reason: Int, uids: IntArray) {
        val cbCaptor = ArgumentCaptor.forClass(NetworkPolicyCallback::class.java)
        verify(context.networkPolicyManager).registerNetworkPolicyCallback(
            any(),
            cbCaptor.capture()
        )
        uids.forEach {
            cbCaptor.value.onUidBlockedReasonChanged(it, reason)
        }
    }

    private fun withCallingUid(uid: Int, action: () -> Unit) {
        deps.callingUid = uid
        action()
        deps.callingUid = CALLING_UID_UNMOCKED
    }

    private fun getUidFrozenStateChangedCallback(): UidFrozenStateChangedCallback {
        val captor = ArgumentCaptor.forClass(UidFrozenStateChangedCallback::class.java)
        verify(activityManager).registerUidFrozenStateChangedCallback(any(), captor.capture())
        return captor.value
    }

    private fun maybeSetUidsFrozen(frozen: Boolean, vararg uids: Int) {
        if (!freezeUids) return
        val state = if (frozen) UID_FROZEN_STATE_FROZEN else UID_FROZEN_STATE_UNFROZEN
        getUidFrozenStateChangedCallback()
            .onUidFrozenStateChanged(uids, IntArray(uids.size) { state })
        waitForIdle()
    }

    private fun CSAgentWrapper.makeValidationSuccess() {
        setValidationResult(
            NETWORK_VALIDATION_RESULT_VALID,
            probesCompleted = NETWORK_VALIDATION_PROBE_DNS or NETWORK_VALIDATION_PROBE_HTTPS,
            probesSucceeded = NETWORK_VALIDATION_PROBE_DNS or NETWORK_VALIDATION_PROBE_HTTPS
        )
        cm.reportNetworkConnectivity(network, true)
        // Ensure validation is scheduled
        waitForIdle()
        // Ensure validation completes on mock executor
        waitForIdleSerialExecutor(CSTestExecutor, HANDLER_TIMEOUT_MS)
        // Ensure validation results are processed
        waitForIdle()
    }

    private fun setMobileDataPreferredUids(uids: Set<Int>) {
        ConnectivitySettingsManager.setMobileDataPreferredUids(context, uids)
        service.updateMobileDataPreferredUids()
        waitForIdle()
    }
}

private fun makeInternetNc() = NetworkCapabilities.Builder(defaultNc())
    .addCapability(NET_CAPABILITY_INTERNET)
    .build()

private fun CSAgentWrapper.sendLpChange(
    mutator: LinkProperties.() -> Unit
): LinkProperties.() -> Unit {
    lp.mutator()
    sendLinkProperties(lp)
    return mutator
}

private fun CSAgentWrapper.sendNcChange(
    mutator: NetworkCapabilities.() -> Unit
): NetworkCapabilities.() -> Unit {
    nc.mutator()
    sendNetworkCapabilities(nc)
    return mutator
}

private fun TestableNetworkCallback.expectLpWith(
    agent: CSAgentWrapper,
    change: LinkProperties.() -> Unit
) = expect<LinkPropertiesChanged>(agent) {
    // This test uses changes that are no-op when already applied (idempotent): verify that the
    // change is already applied.
    it.lp == LinkProperties(it.lp).apply(change)
}

private fun TestableNetworkCallback.expectNcWith(
    agent: CSAgentWrapper,
    change: NetworkCapabilities.() -> Unit
) = expect<CapabilitiesChanged>(agent) {
    it.caps == NetworkCapabilities(it.caps).apply(change)
}

private fun LinkProperties.setLinkAddresses(vararg addrs: String) {
    setLinkAddresses(addrs.map { LinkAddress(it) })
}
