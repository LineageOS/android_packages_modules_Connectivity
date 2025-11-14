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

package android.net.cts

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.Manifest.permission.READ_DEVICE_CONFIG
import android.net.DnsResolver
import android.net.InetAddresses.parseNumericAddress
import android.net.IpPrefix
import android.net.MacAddress
import android.net.RouteInfo
import android.os.CancellationSignal
import android.os.HandlerThread
import android.os.SystemClock
import android.platform.test.annotations.AppModeFull
import android.provider.DeviceConfig
import android.provider.DeviceConfig.NAMESPACE_NETD_NATIVE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.NetworkStackConstants.ETHER_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.IPV6_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.UDP_HEADER_LEN
import com.android.testutils.AutoReleaseNetworkCallbackRule
import com.android.testutils.DeviceConfigRule
import com.android.testutils.DnsResolverModuleTest
import com.android.testutils.IPv6UdpFilter
import com.android.testutils.NdResponder
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.TapPacketReaderRule
import com.android.testutils.TestableNetworkAgent
import com.android.testutils.TestDnsPacket
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule
import com.android.testutils.runAsShell
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val TEST_DNSSERVER_MAC = MacAddress.fromString("00:11:22:33:44:55")
private val TAG = DnsResolverTapTest::class.java.simpleName
private const val TEST_TIMEOUT_MS = 10_000L

@AppModeFull(reason = "Test networks cannot be created in instant app mode")
@DnsResolverModuleTest
@RunWith(AndroidJUnit4::class)
class DnsResolverTapTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val handlerThread = HandlerThread(TAG)

    @get:Rule(order = 1)
    val deviceConfigRule = DeviceConfigRule()

    @get:Rule(order = 2)
    val featureFlagsRule = SetFeatureFlagsRule(
        setFlagsMethod = { name, enabled ->
            val value = when (enabled) {
                null -> null
                true -> "1"
                false -> "0"
            }
            deviceConfigRule.setConfig(NAMESPACE_NETD_NATIVE, name, value)
        },
        getFlagsMethod = {
            runAsShell(READ_DEVICE_CONFIG) {
                DeviceConfig.getInt(NAMESPACE_NETD_NATIVE, it, 0) == 1
            }
        }
    )

    @get:Rule(order = 3)
    val packetReaderRule = TapPacketReaderRule()

    @get:Rule(order = 4)
    val cbRule = AutoReleaseNetworkCallbackRule()

    private val ndResponder by lazy { NdResponder(packetReaderRule.reader) }
    private val dnsServerAddr by lazy {
        parseNumericAddress("fe80::124%${packetReaderRule.iface.interfaceName}") as Inet6Address
    }
    private lateinit var agent: TestableNetworkAgent

    @Before
    fun setUp() {
        handlerThread.start()
        val interfaceName = packetReaderRule.iface.interfaceName
        val cb = cbRule.requestNetwork(TestableNetworkAgent.makeNetworkRequestForInterface(
            interfaceName))
        agent = runAsShell(MANAGE_TEST_NETWORKS) {
            TestableNetworkAgent.createOnInterface(context, handlerThread.looper,
                interfaceName, TEST_TIMEOUT_MS)
        }
        ndResponder.addNeighborEntry(TEST_DNSSERVER_MAC, dnsServerAddr)
        ndResponder.start()
        agent.lp.apply {
            addDnsServer(dnsServerAddr)
            // A default route is needed for DnsResolver.java to send queries over IPv6
            // (see usage of DnsUtils.haveIpv6).
            addRoute(RouteInfo(IpPrefix("::/0"), null, null))
        }
        agent.sendLinkProperties(agent.lp)
        cb.eventuallyExpect<LinkPropertiesChanged> { it.lp.dnsServers.isNotEmpty() }
    }

    @After
    fun tearDown() {
        ndResponder.stop()
        if (::agent.isInitialized) {
            agent.unregister()
        }
        handlerThread.quitSafely()
        handlerThread.join()
    }

    private class DnsCallback : DnsResolver.Callback<List<InetAddress>> {
        override fun onAnswer(answer: List<InetAddress>, rcode: Int) = Unit
        override fun onError(error: DnsResolver.DnsException) = Unit
    }

    /**
     * Run a cancellation test.
     *
     * @param domain Domain name to query
     * @param waitTimeForNoRetryAfterCancellationMs If positive, cancel the query and wait for that
     *                                              delay to check no retry is sent.
     * @return The duration it took to receive all expected replies.
     */
    fun doCancellationTest(domain: String, waitTimeForNoRetryAfterCancellationMs: Long): Long {
        val cancellationSignal = CancellationSignal()
        val dnsCb = DnsCallback()
        val queryStart = SystemClock.elapsedRealtime()
        DnsResolver.getInstance().query(
            agent.network, domain, 0 /* flags */,
            Runnable::run /* executor */, cancellationSignal, dnsCb
        )

        if (waitTimeForNoRetryAfterCancellationMs > 0) {
            cancellationSignal.cancel()
        }
        // Filter for queries on UDP port 53 for the specified domain
        val filter = IPv6UdpFilter(dstPort = 53).and {
            TestDnsPacket(
                it.copyOfRange(ETHER_HEADER_LEN + IPV6_HEADER_LEN + UDP_HEADER_LEN, it.size),
                dstAddr = dnsServerAddr
            ).isQueryFor(domain, DnsResolver.TYPE_AAAA)
        }

        val reader = packetReaderRule.reader
        assertNotNull(reader.poll(TEST_TIMEOUT_MS, filter), "Original query not found")
        if (waitTimeForNoRetryAfterCancellationMs > 0) {
            assertNull(reader.poll(waitTimeForNoRetryAfterCancellationMs, filter),
                "Expected no retry query")
        } else {
            assertNotNull(reader.poll(TEST_TIMEOUT_MS, filter), "Retry query not found")
        }
        return SystemClock.elapsedRealtime() - queryStart
    }

    @SetFeatureFlagsRule.FeatureFlag("no_retry_after_cancel", true)
    @Test
    fun testCancellation() {
        val timeWithRetryWhenNotCancelled = doCancellationTest("test1.example.com",
            waitTimeForNoRetryAfterCancellationMs = 0L)
        doCancellationTest("test2.example.com",
            waitTimeForNoRetryAfterCancellationMs = timeWithRetryWhenNotCancelled + 50L)
    }
}
