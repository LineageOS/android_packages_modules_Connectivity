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
 * limitations under the License
 */

package android.net

import android.Manifest.permission.CHANGE_NETWORK_STATE
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.TestNetworkManager.TestInterfaceRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.platform.test.annotations.AppModeFull
import android.system.Os
import android.system.OsConstants.AF_INET
import android.system.OsConstants.IPPROTO_UDP
import android.system.OsConstants.SOCK_DGRAM
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.ProcfsParsingUtils
import com.android.testutils.AutoCloseTestInterfaceRule
import com.android.testutils.DataPkt
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.EtherPkt
import com.android.testutils.EthernetTestInterface
import com.android.testutils.Ip6Pkt
import com.android.testutils.NdResponder
import com.android.testutils.RaPkt
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.UdpPkt
import com.android.testutils.runAsShell
import com.google.common.truth.Truth.assertThat
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.test.fail
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ClatTest"
private const val SHORT_TIMEOUT_MS = 200L
private const val TIMEOUT_MS = 2000L

private val REQUEST: NetworkRequest = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_ETHERNET)
        .addTransportType(TRANSPORT_TEST)
        .removeCapability(NET_CAPABILITY_INTERNET)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .build()
private val ROUTER_MAC = MacAddress.fromString("01:02:03:04:05:06")
private val ROUTER_V6 = InetAddress.getByName("fe80::0102:03ff:fe04:0506") as Inet6Address

// For ByteArray.toHexString
@kotlin.ExperimentalStdlibApi
@AppModeFull(reason = "Instant apps can't access EthernetManager")
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class ClatTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val handlerThread = HandlerThread("$TAG thread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val registeredCallbacks = ArrayList<TestableNetworkCallback>()

    // Cannot be initialized before setUp as eventuallyExpect<LinkPropertiesChanged>() can fail.
    private lateinit var lp: LinkProperties
    private lateinit var network: Network
    private var socket: FileDescriptor? = null

    @get:Rule
    val testInterfaceRule = AutoCloseTestInterfaceRule(context)

    private val iface: EthernetTestInterface
    init {
        val req = TestInterfaceRequest.Builder().setTap().build()
        val tap = testInterfaceRule.createTestInterface(req)
        iface = EthernetTestInterface(context, handler, tap)
    }

    private val localMac = iface.testIface.macAddress!!

    // Available after provisioning.
    private lateinit var clatV6Addr: Inet6Address

    private val ndResponder = NdResponder(iface.packetReader).apply {
        val ra = RaPkt()
                .addPioOption(prefix = prefix.toString(), flags = "LA")
                .addRdnssOption(dns = "2001:4860:4860::8888,2001:4860:4860::8844")
                .addPref64Option(prefix = "64:ff9b::/96")
        addRouterEntry(ROUTER_MAC, ROUTER_V6, ra)
        start()
    }

    private fun requestNetwork(request: NetworkRequest): TestableNetworkCallback {
        val cb = TestableNetworkCallback()
        runAsShell(CHANGE_NETWORK_STATE) {
            cm.requestNetwork(request, cb, handler)
            registeredCallbacks.add(cb)
        }
        return cb
    }

    @Before
    fun setUp() {
        val cb = requestNetwork(REQUEST)

        // Wait for the clat interface to be created.
        var linkPropertiesChanged: LinkPropertiesChanged
        do {
            linkPropertiesChanged = cb.eventuallyExpect<LinkPropertiesChanged>()
        } while (linkPropertiesChanged.lp.stackedLinks.isEmpty())

        network = linkPropertiesChanged.network
        lp = linkPropertiesChanged.lp

        clatV6Addr = ProcfsParsingUtils.getAnycast6Addresses(iface.name).get(0)
    }

    @After
    fun tearDown() {
        if (socket != null) {
            Os.close(socket)
        }
        for (cb in registeredCallbacks) {
            cm.unregisterNetworkCallback(cb)
        }
        // TODO: AutoCloseTestInterfaceRule should destroy associated EthernetTestInterface.
        iface.destroy()
        handlerThread.quitSafely()
        handlerThread.join()
    }

    fun LinkProperties.getInet4Address(): Inet4Address {
        val las = getAllLinkAddresses()
        for (la in las) {
            if (la.isIpv4()) return la.address as Inet4Address
        }
        // If the v4- interface is present, this cannot happen.
        fail("LinkProperties did not include an IPv4 address")
    }

    fun expectPacket(expectedPacket: ByteArray) {
        val p = iface.packetReader.poll(TIMEOUT_MS) {
            it.contentEquals(expectedPacket)
        }
        assertThat(p).isNotNull()
    }

    /** Assert that no packet from the clat source address was received */
    fun expectNoClatPacket() {
        val p = iface.packetReader.poll(SHORT_TIMEOUT_MS) {
            val src = ByteArray(16)
            val buf = ByteBuffer.wrap(it)
            buf.position(14 + 8)
            buf.get(src)
            val srcAddr = Inet6Address.getByAddress(src)
            clatV6Addr.equals(srcAddr)
        }
        assertThat(p).isNull()
    }

    @Test
    fun testClatEgress() {
        socket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        network.bindSocket(socket)
        Os.connect(socket, InetAddress.getByName("1.2.3.4"), 12345)
        val sockaddr = Os.getsockname(socket) as InetSocketAddress
        val localPort = sockaddr.port
        val buf = ByteBuffer.wrap("test data".toByteArray())
        Os.write(socket, buf)

        val ether = EtherPkt(dst = ROUTER_MAC, src = localMac)
        val ipv6 = Ip6Pkt(src = clatV6Addr.hostAddress!!, dst = "64:ff9b::1.2.3.4", hlim = 64)
        val udp = UdpPkt(sport = localPort, dport = 12345)
        val payload = DataPkt(buf.array())
        val pkt = ether / ipv6 / udp / payload
        expectPacket(pkt.build())
    }

    @Test
    fun testClatIngress() {
        socket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        network.bindSocket(socket)
        Os.bind(socket, lp.getInet4Address(), 0)
        val sockaddr = Os.getsockname(socket) as InetSocketAddress
        val localPort = sockaddr.port

        val ether = EtherPkt(dst = localMac, src = ROUTER_MAC)
        val ipv6 = Ip6Pkt(src = "64:ff9b::1.2.3.4", dst = clatV6Addr.hostAddress!!)
        val udp = UdpPkt(sport = 12345, dport = localPort)
        val data = "more test data"
        val payload = DataPkt(data)
        val pkt = ether / ipv6 / udp / payload
        iface.packetReader.sendResponse(ByteBuffer.wrap(pkt.build()))

        val buf = ByteBuffer.allocate(data.length)
        Os.read(socket, buf)
        assertThat(buf.array()).isEqualTo(data.toByteArray())
    }

    @Test
    fun testEgressDropsMulticast() {
        socket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        network.bindSocket(socket)

        val buf = ByteBuffer.wrap("test".toByteArray())
        Os.sendto(socket, buf, 0 /*flags*/, InetAddress.getByName("224.0.0.251"), 12345 /*port*/)
        Os.sendto(socket, buf, 0 /*flags*/, InetAddress.getByName("234.42.42.42"), 123 /*port*/)
        expectNoClatPacket()
    }
}
