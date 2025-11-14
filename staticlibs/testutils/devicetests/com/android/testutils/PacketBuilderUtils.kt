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
// Ktlint forces a blank line between declarations with comments which wastes space in enum
// declarations.
@file:Suppress("ktlint:standard:spacing-between-declarations-with-comments")

package com.android.testutils

import android.net.IpPrefix
import android.net.MacAddress
import com.android.internal.util.HexDump
import com.android.net.module.util.IpUtils
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.EnumSet
import kotlin.random.Random

class PacketBuilder(outerPacket: Packet, innerPacket: Packet) {
    private data class PacketHolder(
            /** Points to the last encapsulating packet that includes a pseudo header (IPv4 or IPv6)
             * for the purpose of checksum calculation. May be null if no such packet exists. */
            val pseudoHeaderPacket: PseudoHeaderPacket?,
            val packet: Packet,
    )
    private var lastPseudoHeaderPacket: PseudoHeaderPacket? = null
    /** Collects packets in order, where the outermost packet is at index 0. */
    private val packetCollector = ArrayList<PacketHolder>()

    private fun addPacket(p: Packet) {
        if (p is PseudoHeaderPacket) {
            lastPseudoHeaderPacket = p
        }
        packetCollector.add(PacketHolder(lastPseudoHeaderPacket, p))
    }

    init {
        addPacket(outerPacket)
        addPacket(innerPacket)
    }

    operator fun div(p: Packet): PacketBuilder {
        addPacket(p)
        return this
    }

    fun build(): ByteArray {
        var payload: FinalizedPacket? = null
        for (holder in packetCollector.reversed()) {
            payload = holder.packet.build(payload, holder.pseudoHeaderPacket)
        }

        // payload is guaranteed non-null as PacketBuilder is created with at least two packets.
        return payload!!.bytes
    }

    override fun toString(): String {
        return HexDump.toHexString(build())
    }
}

/** Interface to support PacketBuilder syntax, i.e. {@code val p = ether / ipv6 / icmpv6} */
interface Packet {
    operator fun div(p: Packet): PacketBuilder {
        return PacketBuilder(this, p)
    }
    fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket
}

interface PseudoHeaderPacket : Packet {
    /**
     * Calculates the partial checksum (i.e. pseudo header checksum) to be used as a seed value for
     * the higher layer checksum calculation.
     */
    fun calculatePseudoHeaderCsum(proto: Byte, length: Int): Int
}

/**
 * Represents a packet that has been built. Depending on the type of packet, it includes additional
 * information on top of the ByteArray.
 */
interface FinalizedPacket {
    val bytes: ByteArray
}

data class L2Packet(override val bytes: ByteArray) : FinalizedPacket
// Some L3 packets can be carried by an L3 packet (e.g. 6in4), so consider adding proto to L3Packet.
data class L3Packet(override val bytes: ByteArray, val etherType: Short) : FinalizedPacket
data class L4Packet(override val bytes: ByteArray, val proto: Byte) : FinalizedPacket

private interface Flags {
    val value: Int
}

private fun <E> EnumSet<E>.toByte(): Byte where E : Enum<E>, E : Flags {
    val result = this.fold(0) { result, next -> result or next.value }
    return result.toByte()
}

private inline fun <reified E> enumSetOfFlags(str: String): EnumSet<E>
        where E : Enum<E>, E : Flags {
    val result = EnumSet.noneOf(E::class.java)
    for (char in str) {
        result.add(enumValueOf<E>(char.uppercase()))
    }
    return result
}

private fun calculatePacketCsum(
        packetBuffer: ByteBuffer,
        pseudo: PseudoHeaderPacket,
        proto: Byte,
): Short {
    val csum = pseudo.calculatePseudoHeaderCsum(proto, packetBuffer.limit())
    return IpUtils.checksum(packetBuffer, csum, 0 /*start*/, packetBuffer.limit()).toShort()
}

/**
 * Class that facilitates the creation of NA packets.
 *
 * @param target The IPv6 address of the target.
 * @param flags The Neighbor Advertisement flags.
 */
class NaPkt(
    target: Inet6Address,
    flags: EnumSet<NaFlags>,
) : Packet {
    /**
     * Convenience constructor accepting parameters as Strings.
     *
     * @param target Target IPv6 address as a String; e.g. {@code "2001:db8::1"}.
     * @param flags Neighbor Advertisement flags as a String; e.g. {@code "RS"}
     */
    constructor(target: String, flags: String = "RO") :
        this(Inet6Address.getByName(target) as Inet6Address, enumSetOfFlags<NaFlags>(flags))

    enum class NaFlags(override val value: Int) : Flags {
        /** Router flag: indicates that the sender is a router. */
        R(0x80),
        /** Solicited flag: indicates the advertisement was sent in response to a solicitation. */
        S(0x40),
        /** Override flag: indicates the advertisement should override an existing cache entry. */
        O(0x20),
    }

    val outputStream = ByteArrayOutputStream()
    init {
        val naHeader = ByteBuffer.allocate(24)
        naHeader.put(136.toByte()) // Type = 136 (Neighbor Advertisement)
        naHeader.put(0) // Code = 0
        naHeader.putShort(0) // Checksum = 0 (ignored)
        naHeader.put(flags.toByte())
        naHeader.put(0) // reserved
        naHeader.putShort(0) // reserved
        naHeader.put(target.address) // Target address
        naHeader.flip()
        outputStream.write(naHeader.array())
    }

    /** Add Target Link-Layer Address Option */
    fun addTllaOption(lla: MacAddress): NaPkt {
        val llao = ByteBuffer.allocate(8)
        llao.put(2) // Type = 2 (Target Link-layer Address)
        llao.put(1) // Length in units of 8 octets
        llao.put(lla.toByteArray())
        llao.flip()
        outputStream.write(llao.array())
        return this
    }

    /**
     * Add Target Link-Layer Address Option
     *
     * @param lla Link-Layer Address as a String; e.g. {@code "0:0:5e:0:53:0"}
     */
    fun addTllaOption(lla: String): NaPkt {
        return addTllaOption(MacAddress.fromString(lla))
    }

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        require(payload == null)
        require(pseudo != null)

        val packetBytes = outputStream.toByteArray()
        val packetBuffer = ByteBuffer.wrap(packetBytes)
        val proto: Byte = 58
        val csum = calculatePacketCsum(packetBuffer, pseudo, proto)
        packetBuffer.position(2)
        packetBuffer.putShort(csum)
        return L4Packet(proto = proto, bytes = packetBytes)
    }
}

/**
 * Class that facilitates the creation of RA packets.
 *
 * Default argument values reflect the rfc4861 defaults where applicable.
 *
 * @param lft lifetime of the default router in seconds.
 * @param reachableTime The time, in milliseconds, that a node assumes a neighbor is reachable.
 * @param retransTimer The time, in milliseconds, between retransmitted NS messages.
 * @param flags The Router Advertisement flags.
 */
class RaPkt(
    private val lft: Short,
    private val reachableTime: Int,
    private val retransTimer: Int,
    private val flags: EnumSet<RaFlags>,
) : Packet {
    /**
     * Convenience constructor accepting flags parameter as String
     *
     * @param lft lifetime of the default router in seconds.
     * @param reachableTime The time, in milliseconds, that a node assumes a neighbor is reachable.
     * @param retransTimer The time, in milliseconds, between retransmitted NS messages.
     * @param flags The Router Advertisement flags as a String; e.g. {@code "MO"}. Defaults to "".
     */
    constructor(
        lft: Short = 1800,
        reachableTime: Int = 0,
        retransTimer: Int = 0,
        flags: String = "",
    ) : this(lft, reachableTime, retransTimer, enumSetOfFlags<RaFlags>(flags))

    enum class RaFlags(override val value: Int) : Flags {
        /** Managed address configuration: indicates addresses are available via DHCPv6. */
        M(0x80),
        /** Other configuration: indicates other configuration info is available via DHCPv6. */
        O(0x40),
    }

    val outputStream = ByteArrayOutputStream()
    init {
        val raHeader = ByteBuffer.allocate(16)
        raHeader.put(134.toByte()) // Type = 134 (Router Advertisement)
        raHeader.put(0) // Code = 0
        raHeader.putShort(0) // Checksum = 0 (filled in later)
        raHeader.put(255.toByte()) // Cur Hop Limit
        raHeader.put(flags.toByte())
        raHeader.putShort(lft)
        raHeader.putInt(reachableTime)
        raHeader.putInt(retransTimer)
        raHeader.flip()
        outputStream.write(raHeader.array())
    }

    enum class PioFlags(override val value: Int) : Flags {
        /** On-Link: indicates that this prefix can be used for on-link determination. */
        L(0x80),
        /** Autonomous address-configuration: indicates that this prefix can be used for SLAAC. */
        A(0x40),
        /** Router Address: obsolete. */
        R(0x20),
        /** PD Preferred: indicates that the network prefers the use of DHCPv6-PD over SLAAC. */
        P(0x10),
    }

    /**
     * Add a Prefix Information Option
     *
     * @param prefix The prefix of an IPv6 address.
     * @param valid The time, in seconds, that the prefix is valid for on-link determination.
     * @param preferred The time, in seconds, that SLAAC-generated addresses remain preferred.
     * @param flags The Prefix Information Option flags.
     */
    fun addPioOption(
            prefix: IpPrefix,
            valid: Int,
            preferred: Int,
            flags: EnumSet<PioFlags>,
    ): RaPkt {
        if (!prefix.isIPv6()) throw IllegalArgumentException("Invalid prefix")
        val pio = ByteBuffer.allocate(32)
        pio.put(3) // Type = 3
        pio.put(4) // Length = 4 (*8)
        pio.put(prefix.prefixLength.toByte()) // Prefix Length
        pio.put(flags.toByte()) // Flags
        pio.putInt(valid) // Valid Lifetime
        pio.putInt(preferred) // Preferred Lifetime
        pio.putInt(0) // Reserved2
        pio.put(prefix.rawAddress)
        pio.flip()
        outputStream.write(pio.array())
        return this
    }

    /**
     * Add a Prefix Information Option
     *
     * @param prefix The IPv6 prefix as a String; e.g. {@code "2001:db8::/64"}
     * @param valid The time, in seconds, that the prefix is valid for on-link determination.
     * @param preferred The time, in seconds, that SLAAC-generated addresses remain preferred.
     * @param flags The PIO flags as a String; e.g. {@code "LA"}
     */
    fun addPioOption(
            prefix: String,
            valid: Int = 2592000,
            preferred: Int = 604800,
            flags: String = "L",
    ): RaPkt {
        return addPioOption(IpPrefix(prefix), valid, preferred, enumSetOfFlags<PioFlags>(flags))
    }

    /** Add a Recursive DNS Server Option
     *
     * @param dns The list of DNS servers.
     * @param lft The time, in seconds, over which the DNS servers may be used for resolution.
     */
    fun addRdnssOption(dns: List<Inet6Address>, lft: Int): RaPkt {
        val length = 1 + (dns.size * 2)
        val rdnss = ByteBuffer.allocate(length * 8) // length is in units of 8 octets.
        rdnss.put(25) // Type = 25
        rdnss.put(length.toByte()) // Length = 1 + number of dns servers * 2 in units of 8 octets.
        rdnss.putShort(0) // Reserved
        rdnss.putInt(lft)
        for (dnsServer in dns) {
            rdnss.put(dnsServer.address)
        }
        rdnss.flip()
        outputStream.write(rdnss.array())
        return this
    }

    /** Add a Recursive DNS Server Option
     *
     * @param dns The list of DNS servers as a String; e.g. {@code "2001:db8:1,2001:db8:2"}
     * @param lft The time, in seconds, over which the DNS servers may be used for resolution.
     */
    fun addRdnssOption(dns: String, lft: Int = 1800): RaPkt {
        val dnsServers = dns.split(",").map { InetAddress.getByName(it) as Inet6Address }
        return addRdnssOption(dnsServers, lft)
    }

    /** Add a Route Information Option
     *
     * @param prefix The IPv6 prefix of the route.
     * @param lft The lifetime of the route in seconds.
     */
    fun addRioOption(prefix: IpPrefix, lft: Int): RaPkt {
        val rio = ByteBuffer.allocate(24)
        rio.put(24) // Type = 24
        rio.put(3) // Length = 3 -- TODO: support variable prefix length
        rio.put(prefix.prefixLength.toByte())
        // TODO: support setting pref
        rio.put(0) // res (3 bits) | pref (2 bits) | res (3 bits)
        rio.putInt(lft)
        rio.put(prefix.rawAddress)
        rio.flip()
        outputStream.write(rio.array())
        return this
    }

    /** Add a Route Information Option
     *
     * @param prefix The IPv6 prefix of the route as a String; e.g. {@code "2001:db8::/48"}
     * @param lft The lifetime of the route in seconds.
     */
    fun addRioOption(prefix: String, lft: Int = 1800): RaPkt {
        return addRioOption(IpPrefix(prefix), lft)
    }

    /** Add a PREF64 Option
     *
     * @param prefix The PREF64 prefix.
     * @param lft The lifetime of the PREF64 prefix in seconds.
     */
    fun addPref64Option(prefix: IpPrefix, lft: Int): RaPkt {
        val pref64 = ByteBuffer.allocate(16)
        pref64.put(38) // Type = 38
        pref64.put(2) // Length = 2 (*8)
        val scaledLifetime = lft and 0xfff8
        val plc = when (prefix.prefixLength) {
            96 -> 0
            64 -> 1
            56 -> 2
            48 -> 3
            40 -> 4
            32 -> 5
            else -> throw IllegalArgumentException("Invalid pref64 prefix length")
        }
        pref64.putShort((scaledLifetime or plc).toShort()) // Scaled lft (13 bits) | plc (3 bits)
        pref64.put(prefix.rawAddress, 0 /*offset*/, 12 /*length*/) // highest 96 bits of prefix.
        pref64.flip()
        outputStream.write(pref64.array())
        return this
    }

    /** Add a PREF64 Option
     *
     * @param prefix The PREF64 prefix as a String; e.g. {@code "2001:db8::/64"}
     * @param lft The lifetime of the PREF64 prefix in seconds.
     */
    fun addPref64Option(prefix: String, lft: Int = 1800): RaPkt {
        return addPref64Option(IpPrefix(prefix), lft)
    }

    /** Add Source Link-Layer Address Option */
    fun addSllaOption(lla: MacAddress): RaPkt {
        val llao = ByteBuffer.allocate(8)
        llao.put(1) // Type = 1 (Source Link-layer Address)
        llao.put(1) // Length in units of 8 octets
        llao.put(lla.toByteArray())
        llao.flip()
        outputStream.write(llao.array())
        return this
    }

    /**
     * Add Source Link-Layer Address Option
     *
     * @param lla Link-Layer Address as a String; e.g. {@code "0:0:5e:0:53:0"}
     */
    fun addSllaOption(lla: String): RaPkt {
        return addSllaOption(MacAddress.fromString(lla))
    }

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        require(payload == null)
        require(pseudo != null)

        val packetBytes = outputStream.toByteArray()
        val packetBuffer = ByteBuffer.wrap(packetBytes)
        val proto: Byte = 58
        val csum = calculatePacketCsum(packetBuffer, pseudo, proto)
        packetBuffer.position(2)
        packetBuffer.putShort(csum)
        return L4Packet(proto = proto, bytes = packetBytes)
    }
}

/** Class that facilitates the creation of generic L5 data packets. */
class DataPkt(data: ByteArray) : Packet {
    constructor(data: String) : this(data.toByteArray())

    val outputStream = ByteArrayOutputStream()
    init {
        outputStream.write(data)
    }

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        if (payload != null) {
            outputStream.write(payload.bytes)
        }
        return object : FinalizedPacket { override val bytes = outputStream.toByteArray() }
    }
}

class Dhcp6IaPdOpt(
    private val iaid: Int,
    private val t1: Int = 0,
    private val t2: Int = 0,
) : Packet {

    private val outputStream = ByteArrayOutputStream()
    init {
        val bb = ByteBuffer.allocate(16)
                .putShort(25) // OPTION_IA_PD
                .putShort(0) // Length filled in later
                .putInt(iaid)
                .putInt(t1)
                .putInt(t2)
        bb.flip()
        outputStream.write(bb.array())
    }

    fun addIaPrefixOption(prefix: IpPrefix, preferred: Int, valid: Int): Dhcp6IaPdOpt {
        val bb = ByteBuffer.allocate(29)
                .putShort(26) // OPTION_IAPREFIX
                .putShort(25) // Length without IAprefix-options
                .putInt(preferred)
                .putInt(valid)
                .put(prefix.prefixLength.toByte())
                .put(prefix.rawAddress)
        bb.flip()
        outputStream.write(bb.array())
        return this
    }

    fun addIaPrefixOption(prefix: String, preferred: Int = 900, valid: Int = 1800): Dhcp6IaPdOpt {
        return addIaPrefixOption(IpPrefix(prefix), preferred, valid)
    }

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        // Fix up the length *before* appending payload.
        val bytes = outputStream.toByteArray()
        val bb = ByteBuffer.wrap(bytes)
        bb.position(2)
        // Note that option-len does not include the type (2 bytes) and len (2 bytes) fields
        bb.putShort((bytes.size - 4).toShort())

        val tempStream = ByteArrayOutputStream()
        tempStream.write(bytes)
        if (payload != null) tempStream.write(payload.bytes)

        return object : FinalizedPacket {
            override val bytes = tempStream.toByteArray()
        }
    }
}

/** Class that facilitates the creation of DHCPv6 packets. */
class Dhcp6Pkt(
        private val type: Type,
        private val transId: Int,
) : Packet {
    enum class Type(val value: Byte) {
        SOLICIT(1),
        ADVERTISE(2),
        REQUEST(3),
        CONFIRM(4),
        RENEW(5),
        REBIND(6),
        REPLY(7),
        RELEASE(8),
        DECLINE(9),
        RECONFIGURE(10),
        INFORMATION_REQUEST(11), // INFORMATION-REQUEST
        RELAY_FORW(12),          // RELAY-FORW
        RELAY_REPL(13);          // RELAY-REPL

        companion object {
            fun fromString(str: String) = valueOf(str.replace('-', '_'))
        }
    }

    constructor(type: String, transId: Int) : this(Type.fromString(type), transId)

    private val outputStream = ByteArrayOutputStream()
    init {
        require(transId <= 0xffffff)
        val dhcp6Header = ByteBuffer.allocate(4)
            .putInt((type.value.toInt() shl 24) or transId)
        dhcp6Header.flip()
        outputStream.write(dhcp6Header.array())
    }

    fun addClientIdentifierOption(duid: ByteArray): Dhcp6Pkt {
        require(duid.size <= 0xffff)
        val bb = ByteBuffer.allocate(4 + duid.size)
                .putShort(1) // OPTION_CLIENTID
                .putShort(duid.size.toShort())
                .put(duid)
        bb.flip()
        outputStream.write(bb.array())
        return this
    }

    fun addServerIdentifierOption(duid: ByteArray): Dhcp6Pkt {
        require(duid.size <= 0xffff)
        val bb = ByteBuffer.allocate(4 + duid.size)
                .putShort(2) // OPTION_SERVERID
                .putShort(duid.size.toShort())
                .put(duid)
        bb.flip()
        outputStream.write(bb.array())
        return this
    }

    fun addSolMaxRtOption(solMaxRt: Int): Dhcp6Pkt {
        val bb = ByteBuffer.allocate(8)
                .putShort(82) // OPTION_SOL_MAX_RT
                .putShort(4)
                .putInt(solMaxRt)
        bb.flip()
        outputStream.write(bb.array())
        return this
    }

    fun addRapidCommitOption(): Dhcp6Pkt {
        val bb = ByteBuffer.allocate(4)
                .putShort(14) // OPTION_RAPID_COMMIT
                .putShort(0)
        bb.flip()
        outputStream.write(bb.array())
        return this
    }

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        if (payload != null) outputStream.write(payload.bytes)
        return object : FinalizedPacket {
            override val bytes = outputStream.toByteArray()
        }
    }
}

/**
 * Class that facilitates the creation of UDP packets.
 *
 * Use the {@link DataPkt} for carrying a payload.
 */
class UdpPkt(
        private val sport: Short,
        private val dport: Short,
) : Packet {
    constructor(sport: Int = Random.nextInt(1, 65536), dport: Int = Random.nextInt(1, 65536)) :
        this(sport.toShort(), dport.toShort())

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        require(pseudo != null)

        val udpHeader = ByteBuffer.allocate(8)
            .putShort(sport)
            .putShort(dport)
            .putShort(0) // length; to be filled in later.
            .putShort(0) // checksum; to be filled in later.
        udpHeader.flip()
        val outputStream = ByteArrayOutputStream()
        outputStream.write(udpHeader.array())
        if (payload != null) {
            outputStream.write(payload.bytes)
        }
        val packetBytes = outputStream.toByteArray()
        val packetBuffer = ByteBuffer.wrap(packetBytes)

        // Insert length into UDP header
        packetBuffer.position(4)
        packetBuffer.putShort(packetBytes.size.toShort())

        // Insert checksum into UDP header
        val proto: Byte = 17
        val csum = calculatePacketCsum(packetBuffer, pseudo, proto)
        packetBuffer.position(6)
        packetBuffer.putShort(csum)
        return L4Packet(proto = proto, bytes = packetBytes)
    }
}

/** Class that facilitates the creation of IPv6 packets. */
class Ip6Pkt(
        private val src: Inet6Address,
        private val dst: Inet6Address,
        private val hlim: Byte = 255.toByte(),
) : PseudoHeaderPacket {
    constructor(src: String, dst: String, hlim: Int = 255) : this(
            InetAddress.getByName(src) as Inet6Address,
            InetAddress.getByName(dst) as Inet6Address,
            hlim.toByte(),
    )

    private val tc = 0
    private val flowlabel = 0

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        require(payload != null)
        require(payload is L4Packet)

        val ipv6Header = ByteBuffer.allocate(40)
        ipv6Header.putInt((6 shl 28) or (tc shl 20) or flowlabel)
        ipv6Header.putShort(payload.bytes.size.toShort())
        ipv6Header.put(payload.proto)
        ipv6Header.put(hlim)
        ipv6Header.put(src.address)
        ipv6Header.put(dst.address)
        ipv6Header.flip()

        val outputStream = ByteArrayOutputStream()
        outputStream.write(ipv6Header.array())
        outputStream.write(payload.bytes)
        return L3Packet(etherType = 0x86dd.toShort(), bytes = outputStream.toByteArray())
    }

    override fun calculatePseudoHeaderCsum(proto: Byte, length: Int): Int {
        // Calculates the checksum over the src and dst IPv6 addresses.
        // TODO: assemble IPv6 header in constructor and let build() update payload length and
        // proto instead.
        val buffer = ByteBuffer.allocate((2 * 16) + 4 + 2)
        buffer.put(src.address)
        buffer.put(dst.address)
        buffer.putInt(length)
        buffer.putShort(proto.toShort())
        buffer.flip()
        // Note that IpUtils.checksum() flips the result, so it is flipped back here.
        return IpUtils.checksum(buffer, 0 /*seed*/, 0 /*start*/, buffer.limit()) xor 0xffff
    }
}

/**
 * Class that facilitates the creation of IPv4 packets.
 *
 * Options are not currently supported.
 */
class Ip4Pkt(
        private val src: Inet4Address,
        private val dst: Inet4Address,
) : PseudoHeaderPacket {
    constructor(src: String, dst: String) : this(
            InetAddress.getByName(src) as Inet4Address,
            InetAddress.getByName(dst) as Inet4Address,
    )

    private val tos: Byte = 0
    private val identification: Short = 0
    private val flags = 0
    private val fragoff = 0
    private val ttl = 255.toByte()

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        require(payload != null)
        require(payload is L4Packet)

        //  0                   1                   2                   3
        //  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |Version|  IHL  |Type of Service|          Total Length         |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |         Identification        |Flags|      Fragment Offset    |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |  Time to Live |    Protocol   |         Header Checksum       |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |                       Source Address                          |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |                    Destination Address                        |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |                    Options                    |    Padding    |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        val ipv4Header = ByteBuffer.allocate(20)
                .put(((4 /*version*/ shl 4) or 5 /*IHL*/).toByte())
                .put(tos)
                .putShort((20 + payload.bytes.size).toShort())
                .putShort(identification)
                .putShort(((flags shl 13) or fragoff).toShort())
                .put(ttl)
                .put(payload.proto)
                .putShort(0 /*csum; to be filled in later*/)
                .put(src.address)
                .put(dst.address)
        ipv4Header.flip()

        val csum = IpUtils.checksum(ipv4Header, 0 /*seed*/, 0 /*start*/, ipv4Header.limit())
        ipv4Header.position(10)
        ipv4Header.putShort(csum.toShort())

        val outputStream = ByteArrayOutputStream()
        outputStream.write(ipv4Header.array())
        outputStream.write(payload.bytes)
        return L3Packet(etherType = 0x0800.toShort(), bytes = outputStream.toByteArray())
    }

    override fun calculatePseudoHeaderCsum(proto: Byte, length: Int): Int {
        val buffer = ByteBuffer.allocate(12)
                .put(src.address)
                .put(dst.address)
                .put(0)
                .put(proto)
                .putShort(length.toShort())
        buffer.flip()
        // Note that IpUtils.checksum() flips the result, so it is flipped back here.
        return IpUtils.checksum(buffer, 0 /*seed*/, 0 /*start*/, buffer.limit()) xor 0xffff
    }
}

/**
 * Class that facilitates the creation of ethernet packets.
 *
 * Example code:
 *
 * <pre>
 * {@code
 * val ether = EtherPkt(src = "1:2:3:4:5:6", dst = "1:1:1:1:1:1")
 * val ipv6 = Ip6Pkt(src = "fe80::1", dst = "fe80::2")
 * val ra = RaPkt(lft = 50, reachableTime = 100, flags = "O")
 *         .addPioOption(prefix = "2001:db8::1/64", flags = "LA")
 * val p = ether / ipv6 / ra
 * val bytes = p.build()
 * }
 * </pre>
 **/
class EtherPkt(
        private val dst: MacAddress,
        private val src: MacAddress,
    ) : Packet {
    constructor(dst: String, src: String) :
        this(MacAddress.fromString(dst), MacAddress.fromString(src))

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        require(payload != null)
        require(payload is L3Packet)

        val ethernetHeader = ByteBuffer.allocate(14)
        ethernetHeader.put(dst.toByteArray())
        ethernetHeader.put(src.toByteArray())
        ethernetHeader.putShort(payload.etherType)
        ethernetHeader.flip()

        val outputStream = ByteArrayOutputStream()
        outputStream.write(ethernetHeader.array())
        outputStream.write(payload.bytes)
        return L2Packet(bytes = outputStream.toByteArray())
    }
}
