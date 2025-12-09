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

package android.net.thread.borderrouter

import android.content.Context
import android.net.DnsResolver.CLASS_IN
import android.net.DnsResolver.TYPE_A
import android.net.DnsResolver.TYPE_AAAA
import android.net.InetAddresses.parseNumericAddress
import android.net.thread.utils.FullThreadDevice
import android.net.thread.utils.InfraNetworkDevice
import android.net.thread.utils.IntegrationTestUtils.DEFAULT_DATASET
import android.net.thread.utils.IntegrationTestUtils.enableBorderRouterAndJoinNetwork
import android.net.thread.utils.IntegrationTestUtils.joinNetworkAndWaitForOmr
import android.net.thread.utils.IntegrationTestUtils.leaveNetworkAndDisableThread
import android.net.thread.utils.IntegrationTestUtils.newPacketReader
import android.net.thread.utils.IntegrationTestUtils.waitFor
import android.net.thread.utils.OtDaemonController
import android.net.thread.utils.TestDnsServer
import android.net.thread.utils.TestTunNetworkUtils
import android.net.thread.utils.TestUdpEchoServer
import android.net.thread.utils.ThreadFeatureCheckerRule
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresSimulationThreadDevice
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature
import android.net.thread.utils.ThreadNetworkControllerWrapper
import android.os.Handler
import android.os.HandlerThread
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.android.net.module.util.DnsPacket
import com.android.net.module.util.DnsPacket.ANSECTION
import com.android.testutils.PollPacketReader
import com.android.testutils.TestNetworkTracker
import com.google.common.truth.Truth.assertThat
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Integration test cases for Thread Internet Access features. */
@LargeTest
@RunWith(AndroidJUnit4::class)
@RequiresThreadFeature
@RequiresSimulationThreadDevice
class InternetAccessTest {
    companion object {
        private val TAG = BorderRoutingTest::class.java.simpleName
        private val NUM_FTD = 1
        private val DNS_SERVER_ADDR = parseNumericAddress("8.8.8.8") as Inet4Address
        private val UDP_ECHO_SERVER_ADDRESS =
            InetSocketAddress(parseNumericAddress("1.2.3.4"), 12345)
        private val ANSWER_RECORDS =
            listOf(
                DnsPacket.DnsRecord.makeAOrAAAARecord(
                    ANSECTION,
                    "google.com",
                    CLASS_IN,
                    30 /* ttl */,
                    parseNumericAddress("1.2.3.4"),
                ),
                DnsPacket.DnsRecord.makeAOrAAAARecord(
                    ANSECTION,
                    "google.com",
                    CLASS_IN,
                    30 /* ttl */,
                    parseNumericAddress("2001::234"),
                ),
            )

        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            enableBorderRouterAndJoinNetwork(DEFAULT_DATASET)
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            leaveNetworkAndDisableThread()
        }
    }

    @get:Rule val threadRule = ThreadFeatureCheckerRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controller = requireNotNull(ThreadNetworkControllerWrapper.newInstance(context))
    private lateinit var otCtl: OtDaemonController
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var infraNetworkTracker: TestNetworkTracker
    private lateinit var ftds: ArrayList<FullThreadDevice>
    private lateinit var infraNetworkReader: PollPacketReader
    private lateinit var infraDevice: InfraNetworkDevice
    private lateinit var dnsServer: TestDnsServer
    private lateinit var udpEchoServer: TestUdpEchoServer

    @Before
    @Throws(Exception::class)
    fun setUp() {
        otCtl = OtDaemonController()

        handlerThread = HandlerThread(javaClass.simpleName)
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        ftds = ArrayList()

        infraNetworkTracker = TestTunNetworkUtils.setUpInfraNetwork(context, controller)

        // Create an infra network device.
        infraNetworkReader = newPacketReader(infraNetworkTracker.testIface, handler)
        infraDevice = TestTunNetworkUtils.startInfraDeviceAndWaitForOnLinkAddr(infraNetworkReader)

        // Create a DNS server
        dnsServer = TestDnsServer(infraNetworkReader, DNS_SERVER_ADDR, ANSWER_RECORDS)

        // Create a UDP echo server
        udpEchoServer = TestUdpEchoServer(infraNetworkReader, UDP_ECHO_SERVER_ADDRESS)

        // Create Ftds
        for (i in 0 until NUM_FTD) {
            ftds.add(FullThreadDevice(15 + i /* node ID */))
        }
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        controller.setTestNetworkAsUpstreamAndWait(null)
        TestTunNetworkUtils.tearDownAllInfraNetworks()

        dnsServer.stop()
        udpEchoServer.stop()

        handlerThread.quitSafely()
        handlerThread.join()

        ftds.forEach { it.destroy() }
        ftds.clear()
    }

    @Test
    fun nat64Enabled_threadDeviceResolvesHost_hostIsResolved() {
        controller.setNat64EnabledAndWait(true)
        waitFor({ otCtl.hasNat64PrefixInNetdata() }, Duration.ofSeconds(10))
        val ftd = ftds[0]
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET)
        dnsServer.start()
        ftd.autoStartSrpClient()
        ftd.waitForSrpServer()

        val ipv4Addresses =
            ftd.resolveHost("google.com", TYPE_A).map { extractIpv4AddressFromMappedAddress(it) }
        assertThat(ipv4Addresses).isEqualTo(listOf(parseNumericAddress("1.2.3.4")))
        val ipv6Addresses = ftd.resolveHost("google.com", TYPE_AAAA)
        assertThat(ipv6Addresses).isEqualTo(listOf(parseNumericAddress("2001::234")))
    }

    @Test
    fun nat64Disabled_threadDeviceResolvesHost_hostIsNotResolved() {
        controller.setNat64EnabledAndWait(false)
        val ftd = ftds[0]
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET)
        dnsServer.start()
        ftd.autoStartSrpClient()
        ftd.waitForSrpServer()

        assertThat(ftd.resolveHost("google.com", TYPE_A)).isEmpty()
        assertThat(ftd.resolveHost("google.com", TYPE_AAAA)).isEmpty()
    }

    @Test
    fun nat64Enabled_threadDeviceSendsUdpToEchoServer_replyIsReceived() {
        controller.setNat64EnabledAndWait(true)
        waitFor({ otCtl.hasNat64PrefixInNetdata() }, Duration.ofSeconds(10))
        val ftd = ftds[0]
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET)
        udpEchoServer.start()

        ftd.udpOpen()
        ftd.udpSend("Hello,Thread", UDP_ECHO_SERVER_ADDRESS.address, UDP_ECHO_SERVER_ADDRESS.port)
        val reply = ftd.udpReceive()
        assertThat(reply).isEqualTo("Hello,Thread")
    }

    @Test
    fun nat64Enabled_afterInfraNetworkSwitch_threadDeviceSendsUdpToEchoServer_replyIsReceived() {
        controller.setNat64EnabledAndWait(true)
        waitFor({ otCtl.hasNat64PrefixInNetdata() }, Duration.ofSeconds(10))
        infraNetworkTracker = TestTunNetworkUtils.setUpInfraNetwork(context, controller)
        infraNetworkReader = newPacketReader(infraNetworkTracker.testIface, handler)
        udpEchoServer = TestUdpEchoServer(infraNetworkReader, UDP_ECHO_SERVER_ADDRESS)
        val ftd = ftds[0]
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET)
        waitFor({ otCtl.hasNat64PrefixInNetdata() }, Duration.ofSeconds(10))
        udpEchoServer.start()

        ftd.udpOpen()
        ftd.udpSend("Hello,Thread", UDP_ECHO_SERVER_ADDRESS.address, UDP_ECHO_SERVER_ADDRESS.port)
        val reply = ftd.udpReceive()
        assertThat(reply).isEqualTo("Hello,Thread")
    }

    private fun extractIpv4AddressFromMappedAddress(address: InetAddress): Inet4Address {
        return InetAddress.getByAddress(address.address.slice(12 until 16).toByteArray())
            as Inet4Address
    }
}
