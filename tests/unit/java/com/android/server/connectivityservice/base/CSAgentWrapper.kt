/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.content.Context
import android.net.ConnectivityManager
import android.net.INetworkMonitor
import android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_DNS
import android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_HTTP
import android.net.INetworkMonitorCallbacks
import android.net.KeepalivePacketData
import android.net.LinkProperties
import android.net.LocalNetworkConfig
import android.net.Network
import android.net.NetworkAgent
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.NetworkTestResultParcelable
import android.net.QosFilter
import android.net.Uri
import android.net.networkstack.NetworkStackClientBase
import android.os.HandlerThread
import android.os.Looper
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.TestableNetworkAgent.Event
import com.android.testutils.TestableNetworkAgent.Event.OnAddKeepalivePacketFilter
import com.android.testutils.TestableNetworkAgent.Event.OnAutomaticReconnectDisabled
import com.android.testutils.TestableNetworkAgent.Event.OnBandwidthUpdateRequested
import com.android.testutils.TestableNetworkAgent.Event.OnDscpPolicyStatusUpdated
import com.android.testutils.TestableNetworkAgent.Event.OnNetworkCreated
import com.android.testutils.TestableNetworkAgent.Event.OnNetworkDestroyed
import com.android.testutils.TestableNetworkAgent.Event.OnNetworkUnwanted
import com.android.testutils.TestableNetworkAgent.Event.OnRegisterQosCallback
import com.android.testutils.TestableNetworkAgent.Event.OnRemoveKeepalivePacketFilter
import com.android.testutils.TestableNetworkAgent.Event.OnSaveAcceptUnvalidated
import com.android.testutils.TestableNetworkAgent.Event.OnSignalStrengthThresholdsUpdated
import com.android.testutils.TestableNetworkAgent.Event.OnStartSocketKeepalive
import com.android.testutils.TestableNetworkAgent.Event.OnStopSocketKeepalive
import com.android.testutils.TestableNetworkAgent.Event.OnUnregisterQosCallback
import com.android.testutils.TestableNetworkAgent.Event.OnValidationStatus
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Available
import com.android.testutils.TestableNetworkCallback.Event.Lost
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.verify
import org.mockito.stubbing.Answer

const val SHORT_TIMEOUT_MS = 200L

private inline fun <reified T> ArgumentCaptor() = ArgumentCaptor.forClass(T::class.java)

private val agentCounter = AtomicInteger(1)
private fun nextAgentId() = agentCounter.getAndIncrement()

/**
 * A wrapper for network agents, for use with CSTest.
 *
 * This class knows how to interact with CSTest and has helpful methods to make fake agents
 * that can be manipulated directly from a test.
 */
class CSAgentWrapper(
        val context: Context,
        val deps: ConnectivityService.Dependencies,
        csHandlerThread: HandlerThread,
        networkStack: NetworkStackClientBase,
        nac: NetworkAgentConfig,
        val nc: NetworkCapabilities,
        val lp: LinkProperties,
        val lnc: FromS<LocalNetworkConfig>?,
        val score: FromS<NetworkScore>,
        val provider: NetworkProvider?
) : TestableNetworkCallback.HasNetwork {
    private val TAG = "CSAgent${nextAgentId()}"
    private val VALIDATION_RESULT_INVALID = 0
    private val NO_PROBE_RESULT = 0
    private val VALIDATION_TIMESTAMP = 1234L
    private val agent: NetworkAgent
    private val nmCallbacks: INetworkMonitorCallbacks
    val networkMonitor = mock<INetworkMonitor>()
    private var nmValidationRedirectUrl: String? = null
    private var nmValidationResult = NO_PROBE_RESULT
    private var nmProbesCompleted = NO_PROBE_RESULT
    private var nmProbesSucceeded = NO_PROBE_RESULT
    val history = ArrayTrackRecord<Event>().newReadHead()
    val DEFAULT_TIMEOUT_MS = 5000L

    override val network: Network get() = agent.network!!

    inner class TestAgent : NetworkAgent {
        constructor(
            context: Context,
            looper: Looper,
            tag: String,
            nc: NetworkCapabilities,
            lp: LinkProperties,
            lnc: LocalNetworkConfig?,
            score: NetworkScore,
            nac: NetworkAgentConfig,
            provider: NetworkProvider?
        ) :
                super(context, looper, tag, nc, lp, lnc, score, nac, provider) {
        }

        constructor(
            context: Context,
            looper: Looper,
            tag: String,
            nc: NetworkCapabilities,
            lp: LinkProperties,
            score: Int,
            nac: NetworkAgentConfig,
            provider: NetworkProvider?
        ) :
                super(context, looper, tag, nc, lp, score, nac, provider) {
        }

        override fun onBandwidthUpdateRequested() {
            history.add(OnBandwidthUpdateRequested)
        }

        override fun onNetworkUnwanted() {
            history.add(OnNetworkUnwanted)
        }

        override fun onAddKeepalivePacketFilter(slot: Int, packet: KeepalivePacketData) {
            history.add(OnAddKeepalivePacketFilter(slot, packet))
        }

        override fun onRemoveKeepalivePacketFilter(slot: Int) {
            history.add(OnRemoveKeepalivePacketFilter(slot))
        }

        override fun onStartSocketKeepalive(
            slot: Int,
            interval: Duration,
            packet: KeepalivePacketData
        ) {
            history.add(OnStartSocketKeepalive(slot, interval.seconds.toInt(), packet))
        }

        override fun onStopSocketKeepalive(slot: Int) {
            history.add(OnStopSocketKeepalive(slot))
        }

        override fun onSaveAcceptUnvalidated(accept: Boolean) {
            history.add(OnSaveAcceptUnvalidated(accept))
        }

        override fun onAutomaticReconnectDisabled() {
            history.add(OnAutomaticReconnectDisabled)
        }

        override fun onSignalStrengthThresholdsUpdated(thresholds: IntArray) {
            history.add(OnSignalStrengthThresholdsUpdated(thresholds))
        }

        override fun onQosCallbackRegistered(qosCallbackId: Int, filter: QosFilter) {
            history.add(OnRegisterQosCallback(qosCallbackId, filter))
        }

        override fun onQosCallbackUnregistered(qosCallbackId: Int) {
            history.add(OnUnregisterQosCallback(qosCallbackId))
        }

        override fun onValidationStatus(status: Int, uri: Uri?) {
            history.add(OnValidationStatus(status, uri))
        }

        override fun onNetworkCreated() {
            history.add(OnNetworkCreated)
        }

        override fun onNetworkDestroyed() {
            history.add(OnNetworkDestroyed)
        }

        override fun onDscpPolicyStatusUpdated(policyId: Int, status: Int) {
            history.add(OnDscpPolicyStatusUpdated(policyId, status))
        }
    }

    init {
        // Capture network monitor callbacks and simulate network monitor
        val validateAnswer = Answer {
            CSTest.CSTestExecutor.execute { onValidationRequested() }
            null
        }
        doAnswer(validateAnswer).`when`(networkMonitor).notifyNetworkConnected(any(), any())
        doAnswer(validateAnswer).`when`(networkMonitor).notifyNetworkConnectedParcel(any())
        doAnswer(validateAnswer).`when`(networkMonitor).forceReevaluation(anyInt())
        val nmNetworkCaptor = ArgumentCaptor<Network>()
        val nmCbCaptor = ArgumentCaptor<INetworkMonitorCallbacks>()
        doNothing().`when`(networkStack).makeNetworkMonitor(
                nmNetworkCaptor.capture(),
                any() /* name */,
                nmCbCaptor.capture()
        )

        // Create the actual agent. NetworkAgent is abstract, so make an anonymous subclass.
        agent = if (deps.isAtLeastS()) {
            TestAgent(
                context, csHandlerThread.looper, TAG, nc, lp, lnc?.value,
                score.value, nac, provider)
        } else {
            TestAgent(
                context, csHandlerThread.looper, TAG, nc, lp, 50 /* score */, nac, provider)
        }
        agent.register()
        assertEquals(agent.network!!.netId, nmNetworkCaptor.value.netId)
        nmCallbacks = nmCbCaptor.value
        nmCallbacks.onNetworkMonitorCreated(networkMonitor)
    }

    private fun onValidationRequested() {
        if (deps.isAtLeastT()) {
            verify(networkMonitor).notifyNetworkConnectedParcel(any())
        } else {
            verify(networkMonitor).notifyNetworkConnected(any(), any())
        }
        nmCallbacks.notifyProbeStatusChanged(0 /* completed */, 0 /* succeeded */)
        val p = NetworkTestResultParcelable()
        p.result = nmValidationResult
        p.probesAttempted = nmProbesCompleted
        p.probesSucceeded = nmProbesSucceeded
        p.redirectUrl = nmValidationRedirectUrl
        p.timestampMillis = VALIDATION_TIMESTAMP
        nmCallbacks.notifyNetworkTestedWithExtras(p)
    }

    fun connect(expectAvailable: Boolean = true) {
        val mgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().apply {
            clearCapabilities()
            if (nc.transportTypes.isNotEmpty()) addTransportType(nc.transportTypes[0])
            if (nc.hasCapability(NET_CAPABILITY_LOCAL_NETWORK)) {
                addCapability(NET_CAPABILITY_LOCAL_NETWORK)
            }
        }.build()
        val cb = TestableNetworkCallback()
        mgr.registerNetworkCallback(request, cb)
        agent.markConnected()
        if (expectAvailable) {
            if (null == cb.poll { it is Available && agent.network == it.network }) {
                if (!nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED) &&
                        nc.hasTransport(TRANSPORT_CELLULAR)) {
                    // ConnectivityService adds NOT_SUSPENDED by default to all non-cell agents. An
                    // agent without NOT_SUSPENDED will not connect, instead going into the
                    // SUSPENDED state, so this call will not terminate.
                    // Instead of forcefully adding NOT_SUSPENDED to all agents like older tools did,
                    // it's better to let the developer manage it as they see fit but help them
                    // debug if they forget.
                    fail(
                        "Could not connect the agent. Did you forget to add " +
                            "NET_CAPABILITY_NOT_SUSPENDED ?"
                    )
                }
                fail("Could not connect the agent. Instrumentation failure ?")
            }
        } else {
            cb.assertNoCallback()
        }
        mgr.unregisterNetworkCallback(cb)
    }

    fun disconnect(expectAvailable: Boolean = true) {
        val mgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().apply {
            clearCapabilities()
            if (nc.transportTypes.isNotEmpty()) addTransportType(nc.transportTypes[0])
            if (nc.hasCapability(NET_CAPABILITY_LOCAL_NETWORK)) {
                addCapability(NET_CAPABILITY_LOCAL_NETWORK)
            }
        }.build()
        val cb = TestableNetworkCallback(timeoutMs = SHORT_TIMEOUT_MS)
        mgr.registerNetworkCallback(request, cb)
        if (expectAvailable) {
            cb.eventuallyExpect<Available> { it.network == agent.network }
            agent.unregister()
            cb.eventuallyExpect<Lost> { it.network == agent.network }
        } else {
            agent.unregister()
            cb.assertNoCallback()
        }
        mgr.unregisterNetworkCallback(cb)
    }

    fun setTeardownDelayMillis(delayMillis: Int) = agent.setTeardownDelayMillis(delayMillis)
    fun unregisterAfterReplacement(timeoutMs: Int) = agent.unregisterAfterReplacement(timeoutMs)

    fun sendLocalNetworkConfig(lnc: LocalNetworkConfig) = agent.sendLocalNetworkConfig(lnc)
    fun sendNetworkCapabilities(nc: NetworkCapabilities) = agent.sendNetworkCapabilities(nc)
    fun sendLinkProperties(lp: LinkProperties) = agent.sendLinkProperties(lp)
    fun sendTeardownDelayMs(delayMs: Int) = agent.setTeardownDelayMillis(delayMs)

    fun connectWithCaptivePortal(redirectUrl: String) {
        setCaptivePortal(redirectUrl)
        connect()
    }

    fun setValidationResult(result: Int, probesCompleted: Int, probesSucceeded: Int) {
        nmValidationResult = result
        nmProbesCompleted = probesCompleted
        nmProbesSucceeded = probesSucceeded
    }

    fun setCaptivePortal(redirectUrl: String) {
        nmValidationResult = VALIDATION_RESULT_INVALID
        nmValidationRedirectUrl = redirectUrl
        // Suppose the portal is found when NetworkMonitor probes NETWORK_VALIDATION_PROBE_HTTP
        // in the beginning. Because NETWORK_VALIDATION_PROBE_HTTP is the decisive probe for captive
        // portal, considering the NETWORK_VALIDATION_PROBE_HTTPS hasn't probed yet and set only
        // DNS and HTTP probes completed.
        setValidationResult(
            VALIDATION_RESULT_INVALID,
            probesCompleted = NETWORK_VALIDATION_PROBE_DNS or NETWORK_VALIDATION_PROBE_HTTP,
            probesSucceeded = NO_PROBE_RESULT
        )
    }

    inline fun <reified T : Event> expect(test: (T) -> Boolean = { true }): T {
        val foundCallback = history.poll(DEFAULT_TIMEOUT_MS)
        assertTrue(foundCallback is T, "Expected ${T::class} but found $foundCallback")
        assertTrue(test(foundCallback), "Unexpected callback : $foundCallback")
        return foundCallback
    }

    inline fun <reified T : Event> eventuallyExpect() =
        history.poll(DEFAULT_TIMEOUT_MS) { it is T }.also {
            assertNotNull(it, "Callback ${T::class} not received")
        } as T
}
