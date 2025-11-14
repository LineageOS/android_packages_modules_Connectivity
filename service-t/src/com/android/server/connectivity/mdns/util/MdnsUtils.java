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

package com.android.server.connectivity.mdns.util;

import static com.android.net.module.util.DnsUtils.equalsDnsLabelIgnoreDnsCase;
import static com.android.net.module.util.DnsUtils.equalsIgnoreDnsCase;
import static com.android.server.connectivity.mdns.MdnsConstants.FLAG_TRUNCATED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.os.Build;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Pair;

import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.DnsUtils;
import com.android.server.connectivity.mdns.MdnsConstants;
import com.android.server.connectivity.mdns.MdnsInetAddressRecord;
import com.android.server.connectivity.mdns.MdnsPacket;
import com.android.server.connectivity.mdns.MdnsPacketWriter;
import com.android.server.connectivity.mdns.MdnsRecord;
import com.android.server.connectivity.mdns.MdnsResponse;
import com.android.server.connectivity.mdns.MdnsServiceInfo;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mdns utility functions.
 */
public class MdnsUtils {

    private MdnsUtils() { }

    /**
     * Compare labels a equals b or a is suffix of b.
     *
     * @param a the type or subtype.
     * @param b the base type
     */
    public static boolean typeEqualsOrIsSubtype(@NonNull String[] a,
            @NonNull String[] b) {
        return equalsDnsLabelIgnoreDnsCase(a, b)
                || ((b.length == (a.length + 2))
                && equalsIgnoreDnsCase(b[1], MdnsConstants.SUBTYPE_LABEL)
                && MdnsRecord.labelsAreSuffix(a, b));
    }

    /**
     * Create a ArraySet or HashSet based on the sdk version.
     */
    public static <Type> Set<Type> newSet() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new ArraySet<>();
        } else {
            return new HashSet<>();
        }
    }


    /*** Check whether the target network matches the current network */
    public static boolean isNetworkMatched(@Nullable Network targetNetwork,
            @Nullable Network currentNetwork) {
        return targetNetwork == null || targetNetwork.equals(currentNetwork);
    }

    /*** Check whether the target network matches any of the current networks */
    public static boolean isAnyNetworkMatched(@Nullable Network targetNetwork,
            Set<Network> currentNetworks) {
        if (targetNetwork == null) {
            return !currentNetworks.isEmpty();
        }
        return currentNetworks.contains(targetNetwork);
    }

    /**
     * Truncate a service name to up to maxLength UTF-8 bytes.
     */
    public static String truncateServiceName(@NonNull String originalName, int maxLength) {
        // UTF-8 is at most 4 bytes per character; return early in the common case where
        // the name can't possibly be over the limit given its string length.
        if (originalName.length() <= maxLength / 4) return originalName;

        final Charset utf8 = StandardCharsets.UTF_8;
        final CharsetEncoder encoder = utf8.newEncoder();
        final ByteBuffer out = ByteBuffer.allocate(maxLength);
        // encode will write as many characters as possible to the out buffer, and just
        // return an overflow code if there were too many characters (no need to check the
        // return code here, this method truncates the name on purpose).
        encoder.encode(CharBuffer.wrap(originalName), out, true /* endOfInput */);
        return new String(out.array(), 0, out.position(), utf8);
    }

    /**
     * Write the mdns packet from given MdnsPacket.
     */
    public static void writeMdnsPacket(@NonNull MdnsPacketWriter writer, @NonNull MdnsPacket packet)
            throws IOException {
        writer.writeUInt16(packet.transactionId); // Transaction ID (advertisement: 0)
        writer.writeUInt16(packet.flags); // Response, authoritative (rfc6762 18.4)
        writer.writeUInt16(packet.questions.size()); // questions count
        writer.writeUInt16(packet.answers.size()); // answers count
        writer.writeUInt16(packet.authorityRecords.size()); // authority entries count
        writer.writeUInt16(packet.additionalRecords.size()); // additional records count

        for (MdnsRecord record : packet.questions) {
            // Questions do not have TTL or data
            record.writeHeaderFields(writer);
        }
        for (MdnsRecord record : packet.answers) {
            record.write(writer, 0L);
        }
        for (MdnsRecord record : packet.authorityRecords) {
            record.write(writer, 0L);
        }
        for (MdnsRecord record : packet.additionalRecords) {
            record.write(writer, 0L);
        }
    }

    /**
     * Create a raw DNS packet.
     */
    public static byte[] createRawDnsPacket(@NonNull byte[] packetCreationBuffer,
            @NonNull MdnsPacket packet) throws IOException {
        // TODO: support packets over size (send in multiple packets with TC bit set)
        final MdnsPacketWriter writer = new MdnsPacketWriter(packetCreationBuffer);
        writeMdnsPacket(writer, packet);

        final int len = writer.getWritePosition();
        return Arrays.copyOfRange(packetCreationBuffer, 0, len);
    }

    /**
     * Writes the possible query content of an MdnsPacket into the data buffer.
     *
     * <p>This method is specifically for query packets. It writes the question and answer sections
     *    into the data buffer only.
     *
     * @param packetCreationBuffer The data buffer for the query content.
     * @param packet The MdnsPacket to be written into the data buffer.
     * @return A Pair containing:
     *         1. The remaining MdnsPacket data that could not fit in the buffer.
     *         2. The length of the data written to the buffer.
     */
    @Nullable
    private static Pair<MdnsPacket, Integer> writePossibleMdnsPacket(
            @NonNull byte[] packetCreationBuffer, @NonNull MdnsPacket packet) throws IOException {
        MdnsPacket remainingPacket;
        final MdnsPacketWriter writer = new MdnsPacketWriter(packetCreationBuffer);
        writer.writeUInt16(packet.transactionId); // Transaction ID

        final int flagsPos = writer.getWritePosition();
        writer.writeUInt16(0); // Flags, written later
        writer.writeUInt16(0); // questions count, written later
        writer.writeUInt16(0); // answers count, written later
        writer.writeUInt16(0); // authority entries count, empty session for query
        writer.writeUInt16(0); // additional records count, empty session for query

        int writtenQuestions = 0;
        int writtenAnswers = 0;
        int lastValidPos = writer.getWritePosition();
        try {
            for (MdnsRecord record : packet.questions) {
                // Questions do not have TTL or data
                record.writeHeaderFields(writer);
                writtenQuestions++;
                lastValidPos = writer.getWritePosition();
            }
            for (MdnsRecord record : packet.answers) {
                record.write(writer, 0L);
                writtenAnswers++;
                lastValidPos = writer.getWritePosition();
            }
            remainingPacket = null;
        } catch (IOException e) {
            // Went over the packet limit; truncate
            if (writtenQuestions == 0 && writtenAnswers == 0) {
                // No space to write even one record: just throw (as subclass of IOException)
                throw e;
            }

            // Set the last valid position as the final position (not as a rewind)
            writer.rewind(lastValidPos);
            writer.clearRewind();

            remainingPacket = new MdnsPacket(packet.flags,
                    packet.questions.subList(
                            writtenQuestions, packet.questions.size()),
                    packet.answers.subList(
                            writtenAnswers, packet.answers.size()),
                    Collections.emptyList(), /* authorityRecords */
                    Collections.emptyList() /* additionalRecords */);
        }

        final int len = writer.getWritePosition();
        writer.rewind(flagsPos);
        writer.writeUInt16(packet.flags | (remainingPacket == null ? 0 : FLAG_TRUNCATED));
        writer.writeUInt16(writtenQuestions);
        writer.writeUInt16(writtenAnswers);
        writer.unrewind();

        return Pair.create(remainingPacket, len);
    }

    /**
     * Create Datagram packets from given MdnsPacket and InetSocketAddress.
     *
     * <p> If the MdnsPacket is too large for a single DatagramPacket, it will be split into
     *     multiple DatagramPackets.
     */
    public static List<DatagramPacket> createQueryDatagramPackets(
            @NonNull byte[] packetCreationBuffer, @NonNull MdnsPacket packet,
            @NonNull InetSocketAddress destination) throws IOException {
        final List<DatagramPacket> datagramPackets = new ArrayList<>();
        MdnsPacket remainingPacket = packet;
        while (remainingPacket != null) {
            final Pair<MdnsPacket, Integer> result =
                    writePossibleMdnsPacket(packetCreationBuffer, remainingPacket);
            remainingPacket = result.first;
            final int len = result.second;
            final byte[] outBuffer = Arrays.copyOfRange(packetCreationBuffer, 0, len);
            datagramPackets.add(new DatagramPacket(outBuffer, 0, outBuffer.length, destination));
        }
        return datagramPackets;
    }

    /**
     * Checks if the MdnsRecord needs to be renewed or not.
     *
     * <p>As per RFC6762 7.1 no need to query if remaining TTL is more than half the original one,
     * so send the queries if half the TTL has passed.
     */
    public static boolean isRecordRenewalNeeded(@NonNull MdnsRecord mdnsRecord, final long now) {
        return mdnsRecord.getTtl() > 0
                && mdnsRecord.getRemainingTTL(now) <= mdnsRecord.getTtl() / 2;
    }

    /**
     * Creates a new full subtype name with given service type and subtype labels.
     *
     * For example, given ["_http", "_tcp"] and "_printer", this method returns a new String array
     * of ["_printer", "_sub", "_http", "_tcp"].
     */
    public static String[] constructFullSubtype(String[] serviceType, String subtype) {
        return CollectionUtils.prependArray(String.class, serviceType, subtype,
                MdnsConstants.SUBTYPE_LABEL);
    }

    /** A wrapper class of {@link SystemClock} to be mocked in unit tests. */
    public static class Clock {
        /**
         * @see SystemClock#elapsedRealtime
         */
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }
    }

    /**
     * Check all DatagramPackets with the same destination address.
     */
    public static boolean checkAllPacketsWithSameAddress(List<DatagramPacket> packets) {
        // No packet for address check
        if (packets.isEmpty()) {
            return true;
        }

        final InetAddress address =
                ((InetSocketAddress) packets.get(0).getSocketAddress()).getAddress();
        for (DatagramPacket packet : packets) {
            if (!address.equals(((InetSocketAddress) packet.getSocketAddress()).getAddress())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Build MdnsServiceInfo object from given MdnsResponse, service type labels and current time.
     *
     * @param response target service response
     * @param serviceTypeLabels service type labels
     * @param elapsedRealtimeMillis current time.
     */
    public static MdnsServiceInfo buildMdnsServiceInfoFromResponse(@NonNull MdnsResponse response,
            @NonNull String[] serviceTypeLabels, long elapsedRealtimeMillis) {
        String[] hostName = null;
        int port = 0;
        if (response.hasServiceRecord()) {
            hostName = response.getServiceRecord().getServiceHost();
            port = response.getServiceRecord().getServicePort();
        }

        final List<String> ipv4Addresses = new ArrayList<>();
        final List<String> ipv6Addresses = new ArrayList<>();
        if (response.hasInet4AddressRecord()) {
            for (MdnsInetAddressRecord inetAddressRecord : response.getInet4AddressRecords()) {
                final Inet4Address inet4Address = inetAddressRecord.getInet4Address();
                ipv4Addresses.add((inet4Address == null) ? null : inet4Address.getHostAddress());
            }
        }
        if (response.hasInet6AddressRecord()) {
            for (MdnsInetAddressRecord inetAddressRecord : response.getInet6AddressRecords()) {
                final Inet6Address inet6Address = inetAddressRecord.getInet6Address();
                ipv6Addresses.add((inet6Address == null) ? null : inet6Address.getHostAddress());
            }
        }
        String serviceInstanceName = response.getServiceInstanceName();
        if (serviceInstanceName == null) {
            throw new IllegalStateException(
                    "mDNS response must have non-null service instance name");
        }
        List<MdnsServiceInfo.TextEntry> textEntries = null;
        if (response.hasTextRecord()) {
            textEntries = response.getTextRecord().getEntries();
        }
        Instant now = Instant.now();
        // TODO: Throw an error message if response doesn't have Inet6 or Inet4 address.
        return new MdnsServiceInfo(
                serviceInstanceName,
                serviceTypeLabels,
                response.getSubtypes(),
                hostName,
                port,
                ipv4Addresses,
                ipv6Addresses,
                textEntries,
                response.getInterfaceIndex(),
                response.getNetwork(),
                now.plusMillis(response.getMinRemainingTtl(elapsedRealtimeMillis)));
    }

    /**
     * Checks if an MDNS response matches a given instance name and a collection of subtypes.
     *
     * <p>This method performs a case-insensitive comparison of the instance name and subtypes,
     * respecting DNS conventions.
     *
     * @param response     The MDNS response to check.
     * @param instanceName The instance name to match, or null to ignore instance name matching.
     * @param subtypes     The collection of subtypes to match.
     * @return {@code true} if the response matches the instance name and at least one of the
     * specified subtypes (if any), {@code false} otherwise.
     */
    public static boolean responseMatchesInstanceNameAndSubtypes(@NonNull MdnsResponse response,
            @Nullable String instanceName, @NonNull Collection<String> subtypes) {
        final boolean matchesInstanceName = instanceName == null
                // DNS is case-insensitive, so ignore case in the comparison
                || DnsUtils.equalsIgnoreDnsCase(instanceName, response.getServiceInstanceName());

        // If discovery is requiring some subtypes, the response must have one that matches a
        // requested one.
        final List<String> responseSubtypes = response.getSubtypes() == null
                ? Collections.emptyList() : response.getSubtypes();
        final boolean matchesSubtype = subtypes.size() == 0
                || CollectionUtils.any(subtypes, requiredSub ->
                CollectionUtils.any(responseSubtypes, actualSub ->
                        DnsUtils.equalsIgnoreDnsCase(
                                MdnsConstants.SUBTYPE_PREFIX + requiredSub, actualSub)));
        return matchesInstanceName && matchesSubtype;
    }
}