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
import android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.dhcp6.Dhcp6Packet
import com.android.net.module.util.dhcp6.Dhcp6RebindPacket
import com.android.net.module.util.dhcp6.Dhcp6SolicitPacket
import com.android.testutils.AutoCloseTestInterfaceRule
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.DeviceConfigRule
import com.android.testutils.Dhcp6IaPdOpt
import com.android.testutils.Dhcp6Pkt
import com.android.testutils.EtherPkt
import com.android.testutils.EthernetTestInterface
import com.android.testutils.Ip6Pkt
import com.android.testutils.NdResponder
import com.android.testutils.PacketBuilder
import com.android.testutils.RaPkt
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Available
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.UdpPkt
import com.android.testutils.runAsShell
import com.google.common.truth.Truth.assertThat
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "Dhcp6PdTest"
private const val SHORT_TIMEOUT_MS = 200L
private const val TIMEOUT_MS = 20_000L

private const val DHCP6_PFLAG_CONFIG = "ipclient_dhcpv6_pd_preferred_flag_version"

private val REQUEST: NetworkRequest = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_ETHERNET)
        .addTransportType(TRANSPORT_TEST)
        .removeCapability(NET_CAPABILITY_INTERNET)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .build()
private val ROUTER_MAC = MacAddress.fromString("01:02:03:04:05:06")
private val ROUTER_V6 = InetAddress.getByName("fe80::1") as Inet6Address
private val RA_WITH_PFLAG = RaPkt()
            .addPioOption(prefix = "2001:db8::/64", flags = "LAP")
            .addRdnssOption(dns = "2001:4860:4860::8888,2001:4860:4860::8844")
            .addSllaOption(ROUTER_MAC)

@AppModeFull(reason = "Instant apps can't access EthernetManager")
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class Dhcp6PdTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val handlerThread = HandlerThread("$TAG thread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val networkCallback = TestableNetworkCallback().also {
        runAsShell(CHANGE_NETWORK_STATE) {
            val request = NetworkRequest.Builder()
                    .addTransportType(TRANSPORT_ETHERNET)
                    .addTransportType(TRANSPORT_TEST)
                    .removeCapability(NET_CAPABILITY_INTERNET)
                    .removeCapability(NET_CAPABILITY_TRUSTED)
                    .build()
            cm.requestNetwork(request, it, handler)
        }
    }

    @get:Rule(order = 1)
    val deviceConfigRule = DeviceConfigRule().apply {
        setConfig(NAMESPACE_CONNECTIVITY, DHCP6_PFLAG_CONFIG, "1")
    }

    @get:Rule(order = 2)
    val testInterfaceRule = AutoCloseTestInterfaceRule(context)

    private val iface: EthernetTestInterface
    init {
        val req = TestInterfaceRequest.Builder().setTap().build()
        val tap = testInterfaceRule.createTestInterface(req)
        iface = EthernetTestInterface(context, handler, tap)
    }
    private val localMac = iface.testIface.macAddress!!
    private val ndResponder = NdResponder(iface.packetReader).apply { start() }

    @After
    fun tearDown() {
        cm.unregisterNetworkCallback(networkCallback)
        // TODO: AutoCloseTestInterfaceRule should destroy associated EthernetTestInterface.
        iface.destroy()
        handlerThread.quitSafely()
        handlerThread.join()
    }

    private fun eventuallyExpectPacket(predicate: (ByteArray) -> Boolean): ByteArray {
        val p = iface.packetReader.poll(TIMEOUT_MS) {
            it != null && predicate(it)
        }
        assertNotNull(p)
        return p
    }

    private fun assertNoPacket(predicate: (ByteArray) -> Boolean) {
        val p = iface.packetReader.poll(SHORT_TIMEOUT_MS) {
            it != null && predicate(it)
        }
        assertNull(p)
    }

    private fun assertNoDhcp6Packet() {
        assertNoPacket(::isDhcp6Packet)
    }

    private fun isDhcp6Packet(p: ByteArray): Boolean {
        val bb = ByteBuffer.wrap(p)
        bb.position(6 + 6)
        val etherType = bb.getShort()
        if ((etherType.toInt() and 0xffff) != 0x86dd) return false

        bb.position(14 + 6)
        val nextHeader = bb.get()
        if (nextHeader.toInt() != 17) return false

        bb.position(14 + 40 + 2)
        val dport = bb.getShort()
        if (dport.toInt() != 547) return false

        return true
    }

    private inline fun <reified T : Dhcp6Packet> expectDhcp6Packet(): Pair<Inet6Address, T> {
        val l2bytes = eventuallyExpectPacket(::isDhcp6Packet)

        // Read src v6 address from byte array.
        val arr = ByteArray(16)
        val bb = ByteBuffer.wrap(l2bytes)
        bb.position(14 + 8)
        bb.get(arr)
        val srcAddr = InetAddress.getByAddress(arr) as Inet6Address

        val dhcp6bytes = l2bytes.drop(14 + 40 + 8).toByteArray()
        val packet = Dhcp6Packet.decode(dhcp6bytes, dhcp6bytes.size)
        assertIs<T>(packet)
        return Pair(srcAddr, packet as T)
    }

    private fun EthernetTestInterface.sendPacket(p: PacketBuilder) {
        packetReader.sendResponse(ByteBuffer.wrap(p.build()))
    }

    @Test
    fun testSolicit_triggeredByPflag() {
        ndResponder.addRouterEntry(ROUTER_MAC, ROUTER_V6, RA_WITH_PFLAG)
        expectDhcp6Packet<Dhcp6SolicitPacket>()
        networkCallback.assertNoCallback()
    }

    @Test
    fun testProvisioning_triggeredByPflag() {
        ndResponder.addRouterEntry(ROUTER_MAC, ROUTER_V6, RA_WITH_PFLAG)
        val (srcAddr, solicit) = expectDhcp6Packet<Dhcp6SolicitPacket>()

        val ether = EtherPkt(src = ROUTER_MAC, dst = localMac)
        val ipv6 = Ip6Pkt(src = ROUTER_V6, dst = srcAddr)
        val udp = UdpPkt(sport = 547, dport = 546)
        val dhcp6 = Dhcp6Pkt(type = "REPLY", transId = solicit.transactionId)
                .addRapidCommitOption()
                .addClientIdentifierOption(solicit.clientDuid)
                .addServerIdentifierOption(byteArrayOf(1, 2, 3, 4, 5, 6))
        val dhcp6_pd = Dhcp6IaPdOpt(iaid = solicit.iaid)
                .addIaPrefixOption(prefix = "2001:db8:1234::/64")
        val pkt = ether / ipv6 / udp / dhcp6 / dhcp6_pd
        iface.sendPacket(pkt)

        networkCallback.expect<Available>()
    }

    @Test
    fun testProvisioning_triggeredByMultiplePrefixesWithPflag() {
        ndResponder.addRouterEntry(ROUTER_MAC, ROUTER_V6, RA_WITH_PFLAG)
        val (srcAddr, solicit) = expectDhcp6Packet<Dhcp6SolicitPacket>()

        run {
            val ether = EtherPkt(src = ROUTER_MAC, dst = localMac)
            val ipv6 = Ip6Pkt(src = ROUTER_V6, dst = srcAddr)
            val udp = UdpPkt(sport = 547, dport = 546)
            val dhcp6 = Dhcp6Pkt(type = "REPLY", transId = solicit.transactionId)
                .addRapidCommitOption()
                .addClientIdentifierOption(solicit.clientDuid)
                .addServerIdentifierOption(byteArrayOf(1, 2, 3, 4, 5, 6))
            val dhcp6_pd = Dhcp6IaPdOpt(iaid = solicit.iaid)
                .addIaPrefixOption(prefix = "2001:db8:1234::/64")
            val pkt = ether / ipv6 / udp / dhcp6 / dhcp6_pd
            iface.sendPacket(pkt)
        }

        networkCallback.expect<Available>()

        run {
            val ether = EtherPkt(src = "f4:34:f0:64:52:fe", dst = "33:33:00:00:00:01")
            val ipv6 = Ip6Pkt(src = "fe80::12", dst = "ff02::1")
            val ra = RaPkt(lft = 360, retransTimer = 360)
                .addPioOption(prefix = "2002:db8:1::/64", flags = "LAP")
            iface.sendPacket(ether / ipv6 / ra)
        }

        expectDhcp6Packet<Dhcp6RebindPacket>()
    }

    @Test
    fun testProvisioning_withTwoPrefixes() {
        val ra = RaPkt()
            .addPioOption(prefix = "2001:db8::/64", flags = "LAP")
            .addPioOption(prefix = "fd00::/48", flags = "LAP")
            .addRdnssOption(dns = "2001:4860:4860::8888,2001:4860:4860::8844")
        ndResponder.addRouterEntry(ROUTER_MAC, ROUTER_V6, ra)
        val (srcAddr, solicit) = expectDhcp6Packet<Dhcp6SolicitPacket>()

        val ether = EtherPkt(src = ROUTER_MAC, dst = localMac)
        val ipv6 = Ip6Pkt(src = ROUTER_V6, dst = srcAddr)
        val udp = UdpPkt(sport = 547, dport = 546)
        val dhcp6 = Dhcp6Pkt(type = "REPLY", transId = solicit.transactionId)
                .addRapidCommitOption()
                .addClientIdentifierOption(solicit.clientDuid)
                .addServerIdentifierOption(byteArrayOf(5, 4, 3, 2, 1))
        val dhcp6_pd = Dhcp6IaPdOpt(iaid = solicit.iaid)
                .addIaPrefixOption(prefix = "fd00::/48")
                .addIaPrefixOption(prefix = "2001:db8:1234::/64")
        val pkt = ether / ipv6 / udp / dhcp6 / dhcp6_pd
        iface.sendPacket(pkt)

        val lp = networkCallback.eventuallyExpect<LinkPropertiesChanged>().lp
        val prefixes = lp.linkAddresses.map { it -> IpPrefix(it.address, it.prefixLength) }
        // TODO: Should the stack also derive an address from fd00::/48?
        assertThat(prefixes).containsExactly(IpPrefix("fe80::/64"), IpPrefix("2001:db8:1234::/64"))
    }

    @Test
    fun testProvisioning_withMultihomingConfiguration() {
        // Configure two routers.
        run {
            val ra = RaPkt()
                .addPioOption(prefix = "2001:db8:1::/64", flags = "LAP")
                .addRdnssOption(dns = "2001:4860:4860::8888,2001:4860:4860::8844")
            ndResponder.addRouterEntry(ROUTER_MAC, ROUTER_V6, ra)
        }
        run {
            val ra = RaPkt()
                .addPioOption(prefix = "2001:db8:2::/64", flags = "LAP")
                .addRdnssOption(dns = "2001:4860:4860::8888,2001:4860:4860::8844")
            val mac = MacAddress.fromString("9:8:7:6:5:4")
            val ip = InetAddress.getByName("fe80::2") as Inet6Address
            ndResponder.addRouterEntry(mac, ip, ra)
        }
        val (srcAddr, solicit) = expectDhcp6Packet<Dhcp6SolicitPacket>()

        val ether = EtherPkt(src = "42:42:42:42:42:42", dst = localMac.toString())
        val ipv6 = Ip6Pkt(src = "fe80::42", dst = srcAddr.hostAddress!!)
        val udp = UdpPkt(sport = 547, dport = 546)
        val dhcp6 = Dhcp6Pkt(type = "REPLY", transId = solicit.transactionId)
                .addRapidCommitOption()
                .addClientIdentifierOption(solicit.clientDuid)
                .addServerIdentifierOption(byteArrayOf(5, 4, 3, 2, 1))
        val dhcp6_pd = Dhcp6IaPdOpt(iaid = solicit.iaid)
                .addIaPrefixOption(prefix = "2001:db8:1:1234::/64")
                .addIaPrefixOption(prefix = "2001:db8:2:1234::/64")
        val pkt = ether / ipv6 / udp / dhcp6 / dhcp6_pd
        iface.sendPacket(pkt)

        val lp = networkCallback.eventuallyExpect<LinkPropertiesChanged>().lp
        val prefixes = lp.linkAddresses.map { it -> IpPrefix(it.address, it.prefixLength) }
        assertThat(prefixes).containsExactly(
                IpPrefix("fe80::/64"),
                IpPrefix("2001:db8:1:1234::/64"),
                IpPrefix("2001:db8:2:1234::/64")
        )
    }

    @Test
    fun testSolicit_notTriggeredByLinkLocalPrefix() {
        // RA includes a SLAAC prefix to ensure the heuristic does not trigger.
        val ra = RaPkt()
            .addPioOption(prefix = "2001:db8:1::/64", flags = "LA")
            .addPioOption(prefix = "fe80:42::/64", flags = "LP")
            .addRdnssOption(dns = "2001:4860::8888")
        ndResponder.addRouterEntry(ROUTER_MAC, ROUTER_V6, ra)
        // Expect network Available to wait for RA arrival
        networkCallback.expect<Available>()
        // Ensure that no Solicit was sent.
        assertNoDhcp6Packet()
    }
}
