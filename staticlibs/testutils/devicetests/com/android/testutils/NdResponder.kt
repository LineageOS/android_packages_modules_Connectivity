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

package com.android.testutils

import android.net.IpPrefix
import android.net.MacAddress
import android.system.OsConstants.IPPROTO_ICMPV6
import android.util.Log
import com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV6
import com.android.net.module.util.NetworkStackConstants.ICMPV6_NEIGHBOR_SOLICITATION
import com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_SOLICITATION
import com.android.net.module.util.Struct
import com.android.net.module.util.structs.EthernetHeader
import com.android.net.module.util.structs.Icmpv6Header
import com.android.net.module.util.structs.Ipv6Header
import com.android.net.module.util.structs.NsHeader
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val TAG = "NdResponder"

class NdResponder(
    val packetReader: PollPacketReader,
    val prefix: IpPrefix,
) : PacketResponder(packetReader, ::isRsOrNs, TAG) {
    companion object {
        private fun isRsOrNs(packet: ByteArray): Boolean {
            val buffer = ByteBuffer.wrap(packet)
            val ethHeader = Struct.parse(EthernetHeader::class.java, buffer)
            if (ethHeader.etherType.toInt() != ETHER_TYPE_IPV6) return false

            val ipv6Header = Struct.parse(Ipv6Header::class.java, buffer)
            if (ipv6Header.nextHeader.toInt() != IPPROTO_ICMPV6) return false

            val icmpv6Header = Struct.parse(Icmpv6Header::class.java, buffer)
            return icmpv6Header.type.toInt() == ICMPV6_ROUTER_SOLICITATION ||
                    icmpv6Header.type.toInt() == ICMPV6_NEIGHBOR_SOLICITATION
        }

        private fun makeRandomPrefix(): IpPrefix {
            val prefixBytes = IpPrefix("2001:db8::/64").address.address
            Random.Default.nextBytes(prefixBytes, fromIndex = 4, toIndex = 16)
            return IpPrefix(prefixBytes, 64)
        }
    }

    data class NeighborEntry(
        val macAddr: MacAddress,
        val ipAddr: Inet6Address,
        val ra: RaPkt?, // null if neighbor is not a router.
    )
    private val neighborMap: ConcurrentHashMap<Inet6Address, NeighborEntry> = ConcurrentHashMap()

    constructor(packetReader: PollPacketReader) : this(packetReader, makeRandomPrefix())

    /**
     * Adds a new router to be advertised.
     * @param mac the mac address of the router.
     * @param ip the link-local address of the router.
     */
    fun addRouterEntry(mac: MacAddress, ip: Inet6Address) {
        val ra = RaPkt()
                .addPioOption(prefix = prefix.toString(), flags = "LA")
                .addRdnssOption(dns = "2001:4860:4860::8888,2001:4860:4860::8844")
        addRouterEntry(mac, ip, ra)
    }

    fun addRouterEntry(mac: MacAddress, ip: Inet6Address, ra: RaPkt) {
        neighborMap.put(ip, NeighborEntry(mac, ip, ra))
    }

    /**
     * Adds a new neighbor to be advertised.
     * @param mac the mac address of the neighbor.
     * @param ip the link-local address of the neighbor.
     */
    fun addNeighborEntry(mac: MacAddress, ip: Inet6Address) {
        neighborMap.put(ip, NeighborEntry(mac, ip, null))
    }

    private fun sendPacket(reader: PollPacketReader, packet: ByteArray) {
        try {
            // Note: PollPacketReader#createFd() is just a getter for the underlying fd.
            FileOutputStream(reader.createFd()).use { it.write(packet) }
        } catch (e: IOException) {
            // Throwing an exception here will crash the test process.
            Log.e(TAG, "Failed to send packet", e)
        }
    }

    private fun replyToRouterSolicitation(reader: PollPacketReader, dstMac: MacAddress) {
        neighborMap.forEach { (ip, neigh) ->
            val ra = neigh.ra
            if (ra == null) return@forEach // continue
            // TODO: add prefix to neighborMap
            val ether = EtherPkt(src = neigh.macAddr, dst = dstMac)
            val ip6 = Ip6Pkt(src = neigh.ipAddr.hostAddress, dst = "ff02::1")
            val p = ether / ip6 / ra
            sendPacket(reader, p.build())
        }
    }

    private fun replyToNeighborSolicitation(
        reader: PollPacketReader,
        dstMac: MacAddress,
        dstIp: Inet6Address,
        targetIp: Inet6Address
    ) {
        val neighbor = neighborMap.get(targetIp)
        if (neighbor == null) return

        val ether = EtherPkt(dst = dstMac, src = neighbor.macAddr)
        val ipv6 = Ip6Pkt(src = neighbor.ipAddr, dst = dstIp)
        val flags = if (neighbor.ra == null) "SO" else "RSO"
        val na = NaPkt(targetIp.hostAddress, flags)
                .addTllaOption(neighbor.macAddr)
        val p = ether / ipv6 / na
        sendPacket(reader, p.build())
    }

    protected override fun replyToPacket(packet: ByteArray, reader: PollPacketReader) {
        val buf = ByteBuffer.wrap(packet)
        // Messages are filtered by parent class, so it is safe to assume that packet is either an
        // RS or NS.
        val ethHdr = Struct.parse(EthernetHeader::class.java, buf)
        val ipv6Hdr = Struct.parse(Ipv6Header::class.java, buf)
        val icmpv6Header = Struct.parse(Icmpv6Header::class.java, buf)

        when (icmpv6Header.type.toInt()) {
            ICMPV6_ROUTER_SOLICITATION -> replyToRouterSolicitation(reader, ethHdr.srcMac)
            ICMPV6_NEIGHBOR_SOLICITATION -> {
                val nsHeader = Struct.parse(NsHeader::class.java, buf)
                replyToNeighborSolicitation(reader, ethHdr.srcMac, ipv6Hdr.srcIp, nsHeader.target)
            }
        }
    }
}
