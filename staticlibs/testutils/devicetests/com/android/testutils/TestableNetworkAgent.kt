/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.testutils

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.FEATURE_QUEUE_NETWORK_AGENT_EVENTS_IN_SYSTEM_SERVER
import android.net.InetAddresses.parseNumericAddress
import android.net.KeepalivePacketData
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkAgent
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.QosFilter
import android.net.Uri
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.OsConstants.EADDRNOTAVAIL
import android.system.OsConstants.ENETUNREACH
import android.system.OsConstants.ENONET
import android.system.OsConstants.IPPROTO_UDP
import android.system.OsConstants.SOCK_DGRAM
import com.android.modules.utils.build.SdkLevel.isAtLeastS
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.CompatUtil.makeTestNetworkSpecifier
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
import java.net.NetworkInterface
import java.net.SocketException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assert.assertArrayEquals

// Any legal score (0~99) for the test network would do, as it is going to be kept up by the
// requests filed by the test and should never match normal internet requests. 70 is the default
// score of Ethernet networks, it's as good a value as any other.
// Note that this can't use NetworkScore.Builder() because this test must be able to
// run on R, which doesn't know this class.
private const val TEST_NETWORK_SCORE = 70

private class Provider(context: Context, looper: Looper) :
            NetworkProvider(context, looper, "NetworkAgentTest NetworkProvider")

private val enabledFeatures = mutableMapOf<Long, Boolean>()

public open class TestableNetworkAgent(
    context: Context,
    looper: Looper,
    val nc: NetworkCapabilities,
    val lp: LinkProperties,
    conf: NetworkAgentConfig
) : NetworkAgent(context, looper, TestableNetworkAgent::class.java.simpleName /* tag */,
    nc, lp, TEST_NETWORK_SCORE, conf, Provider(context, looper)) {

    override fun isFeatureEnabled(context: Context, feature: Long): Boolean {
        when (val it = enabledFeatures.get(feature)) {
            null -> {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val res = cm.isFeatureEnabled(feature)
                enabledFeatures[feature] = res
                return res
            }
            else -> return it
        }
    }

    companion object {
        fun setFeatureEnabled(flag: Long, enabled: Boolean) = enabledFeatures.set(flag, enabled)

        /**
         * Convenience method to create a [NetworkRequest] matching [TestableNetworkAgent]s from
         * [createOnInterface].
         */
        fun makeNetworkRequestForInterface(ifaceName: String) = NetworkRequest.Builder()
            .removeCapability(NET_CAPABILITY_TRUSTED)
            .addTransportType(TRANSPORT_TEST)
            .setNetworkSpecifier(makeTestNetworkSpecifier(ifaceName))
            .build()

        /**
         * Convenience method to initialize a [TestableNetworkAgent] on a given interface.
         *
         * This waits for link-local addresses to be setup and ensures LinkProperties are updated
         * with the addresses.
         */
        fun createOnInterface(
            context: Context,
            looper: Looper,
            ifaceName: String,
            timeoutMs: Long
        ): TestableNetworkAgent {
            val lp = LinkProperties().apply {
                interfaceName = ifaceName
            }
            val agent = TestableNetworkAgent(
                context,
                looper,
                NetworkCapabilities().apply {
                    removeCapability(NET_CAPABILITY_TRUSTED)
                    addTransportType(TRANSPORT_TEST)
                    setNetworkSpecifier(makeTestNetworkSpecifier(ifaceName))
                },
                lp,
                NetworkAgentConfig.Builder().build()
            )
            val network = agent.register()
            agent.markConnected()
            if (isAtLeastS()) {
                // OnNetworkCreated was added in S
                agent.eventuallyExpect<OnNetworkCreated>()
            }

            // Wait until the link-local address can be used. Address flags are not available
            // without elevated permissions, so check that bindSocket works.
            assertEventuallyTrue("No usable v6 address after $timeoutMs ms", timeoutMs) {
                // To avoid race condition between socket connection succeeding and interface
                // returning a non-empty address list. Verify that interface returns a non-empty
                // list, before trying the socket connection.
                if (NetworkInterface.getByName(ifaceName).interfaceAddresses.isEmpty()) {
                    return@assertEventuallyTrue false
                }

                val sock = Os.socket(OsConstants.AF_INET6, SOCK_DGRAM, IPPROTO_UDP)
                tryTest {
                    network.bindSocket(sock)
                    Os.connect(sock, parseNumericAddress("ff02::fb%$ifaceName"), 12345)
                    true
                }.catch<ErrnoException> {
                    if (it.errno != ENETUNREACH && it.errno != EADDRNOTAVAIL) {
                        throw it
                    }
                    false
                }.catch<SocketException> {
                    // OnNetworkCreated does not exist on R, so a SocketException caused by ENONET
                    // may be seen before the network is created
                    if (isAtLeastS()) throw it
                    val cause = it.cause as? ErrnoException ?: throw it
                    if (cause.errno != ENONET) {
                        throw it
                    }
                    false
                } cleanup {
                    Os.close(sock)
                }
            }

            agent.lp.setLinkAddresses(NetworkInterface.getByName(ifaceName).interfaceAddresses.map {
                LinkAddress(it.address, it.networkPrefixLength.toInt())
            })
            agent.sendLinkProperties(agent.lp)
            return agent
        }
    }

    val DEFAULT_TIMEOUT_MS = 5000L

    val history = ArrayTrackRecord<Event>().newReadHead()

    sealed class Event {
        object OnBandwidthUpdateRequested : Event()
        object OnNetworkUnwanted : Event()
        data class OnAddKeepalivePacketFilter(
            val slot: Int,
            val packet: KeepalivePacketData
        ) : Event()
        data class OnRemoveKeepalivePacketFilter(val slot: Int) : Event()
        data class OnStartSocketKeepalive(
            val slot: Int,
            val interval: Int,
            val packet: KeepalivePacketData
        ) : Event()
        data class OnStopSocketKeepalive(val slot: Int) : Event()
        data class OnSaveAcceptUnvalidated(val accept: Boolean) : Event()
        object OnAutomaticReconnectDisabled : Event()
        data class OnValidationStatus(val status: Int, val uri: Uri?) : Event()
        data class OnSignalStrengthThresholdsUpdated(val thresholds: IntArray) : Event()
        object OnNetworkCreated : Event()
        object OnNetworkDestroyed : Event()
        data class OnDscpPolicyStatusUpdated(val policyId: Int, val status: Int) : Event()
        data class OnRegisterQosCallback(
            val callbackId: Int,
            val filter: QosFilter
        ) : Event()
        data class OnUnregisterQosCallback(val callbackId: Int) : Event()
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

    fun expectSignalStrengths(thresholds: IntArray? = intArrayOf()) {
        expectCallback<OnSignalStrengthThresholdsUpdated>().let {
            assertArrayEquals(thresholds, it.thresholds)
        }
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

    // Expects the initial validation event that always occurs immediately after registering
    // a NetworkAgent whose network does not require validation (which test networks do
    // not, since they lack the INTERNET capability). It always contains the default argument
    // for the URI.
    fun expectValidationBypassedStatus() = expectCallback<OnValidationStatus>().let {
        assertEquals(it.status, VALID_NETWORK)
        // The returned Uri is parsed from the empty string, which means it's an
        // instance of the (private) Uri.StringUri. There are no real good ways
        // to check this, the least bad is to just convert it to a string and
        // make sure it's empty.
        assertEquals("", it.uri.toString())
    }

    inline fun <reified T : Event> expectCallback(): T {
        val foundCallback = history.poll(DEFAULT_TIMEOUT_MS)
        assertTrue(foundCallback is T, "Expected ${T::class} but found $foundCallback")
        return foundCallback
    }

    inline fun <reified T : Event> expectCallback(valid: (T) -> Boolean) {
        val foundCallback = history.poll(DEFAULT_TIMEOUT_MS)
        assertTrue(foundCallback is T, "Expected ${T::class} but found $foundCallback")
        assertTrue(valid(foundCallback), "Unexpected callback : $foundCallback")
    }

    inline fun <reified T : Event> eventuallyExpect() =
            history.poll(DEFAULT_TIMEOUT_MS) { it is T }.also {
                assertNotNull(it, "Callback ${T::class} not received")
    } as T

    fun assertNoCallback() {
        assertTrue(waitForIdle(DEFAULT_TIMEOUT_MS),
                "Handler didn't became idle after ${DEFAULT_TIMEOUT_MS}ms")
        assertNull(history.peek())
    }
}
