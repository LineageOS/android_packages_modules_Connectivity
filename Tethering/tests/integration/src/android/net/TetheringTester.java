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

package android.net;

import static org.junit.Assert.fail;

import android.net.dhcp.DhcpAckPacket;
import android.net.dhcp.DhcpOfferPacket;
import android.net.dhcp.DhcpPacket;
import android.util.ArrayMap;
import android.util.Log;

import com.android.testutils.TapPacketReader;

import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * A class simulate tethered client. When caller create TetheringTester, it would connect to
 * tethering module that do the dhcp and slaac to obtain ipv4 and ipv6 address. Then caller can
 * send/receive packets by this class.
 */
public final class TetheringTester {
    private static final String TAG = TetheringTester.class.getSimpleName();
    private static final int PACKET_READ_TIMEOUT_MS = 100;
    private static final int DHCP_DISCOVER_ATTEMPTS = 10;
    private static final byte[] DHCP_REQUESTED_PARAMS = new byte[] {
            DhcpPacket.DHCP_SUBNET_MASK,
            DhcpPacket.DHCP_ROUTER,
            DhcpPacket.DHCP_DNS_SERVER,
            DhcpPacket.DHCP_LEASE_TIME,
    };

    public static final String DHCP_HOSTNAME = "testhostname";

    private final ArrayMap<MacAddress, TetheredDevice> mTetheredDevices;
    private final TapPacketReader mDownstreamReader;

    public TetheringTester(TapPacketReader downstream) {
        if (downstream == null) fail("Downstream reader could not be NULL");

        mDownstreamReader = downstream;
        mTetheredDevices = new ArrayMap<>();
    }

    public TetheredDevice createTetheredDevice(MacAddress macAddr) throws Exception {
        if (mTetheredDevices.get(macAddr) != null) {
            fail("Tethered device already created");
        }

        TetheredDevice tethered = new TetheredDevice(macAddr);
        mTetheredDevices.put(macAddr, tethered);

        return tethered;
    }

    public class TetheredDevice {
        private final MacAddress mMacAddr;

        public final Inet4Address mIpv4Addr;

        private TetheredDevice(MacAddress mac) throws Exception {
            mMacAddr = mac;

            DhcpResults dhcpResults = runDhcp(mMacAddr.toByteArray());
            mIpv4Addr = (Inet4Address) dhcpResults.ipAddress.getAddress();
        }
    }

    /** Simulate dhcp client to obtain ipv4 address. */
    public DhcpResults runDhcp(byte[] clientMacAddr)
            throws Exception {
        // We have to retransmit DHCP requests because IpServer declares itself to be ready before
        // its DhcpServer is actually started. TODO: fix this race and remove this loop.
        DhcpPacket offerPacket = null;
        for (int i = 0; i < DHCP_DISCOVER_ATTEMPTS; i++) {
            Log.d(TAG, "Sending DHCP discover");
            sendDhcpDiscover(clientMacAddr);
            offerPacket = getNextDhcpPacket();
            if (offerPacket instanceof DhcpOfferPacket) break;
        }
        if (!(offerPacket instanceof DhcpOfferPacket)) {
            throw new TimeoutException("No DHCPOFFER received on interface within timeout");
        }

        sendDhcpRequest(offerPacket, clientMacAddr);
        DhcpPacket ackPacket = getNextDhcpPacket();
        if (!(ackPacket instanceof DhcpAckPacket)) {
            throw new TimeoutException("No DHCPACK received on interface within timeout");
        }

        return ackPacket.toDhcpResults();
    }

    private void sendDhcpDiscover(byte[] macAddress) throws Exception {
        ByteBuffer packet = DhcpPacket.buildDiscoverPacket(DhcpPacket.ENCAP_L2,
                new Random().nextInt() /* transactionId */, (short) 0 /* secs */,
                macAddress,  false /* unicast */, DHCP_REQUESTED_PARAMS,
                false /* rapid commit */,  DHCP_HOSTNAME);
        mDownstreamReader.sendResponse(packet);
    }

    private void sendDhcpRequest(DhcpPacket offerPacket, byte[] macAddress)
            throws Exception {
        DhcpResults results = offerPacket.toDhcpResults();
        Inet4Address clientIp = (Inet4Address) results.ipAddress.getAddress();
        Inet4Address serverIdentifier = results.serverAddress;
        ByteBuffer packet = DhcpPacket.buildRequestPacket(DhcpPacket.ENCAP_L2,
                0 /* transactionId */, (short) 0 /* secs */, DhcpPacket.INADDR_ANY /* clientIp */,
                false /* broadcast */, macAddress, clientIp /* requestedIpAddress */,
                serverIdentifier, DHCP_REQUESTED_PARAMS, DHCP_HOSTNAME);
        mDownstreamReader.sendResponse(packet);
    }

    private DhcpPacket getNextDhcpPacket() throws Exception {
        final byte[] packet = getNextMatchedPacket((p) -> {
            // Test whether this is DHCP packet.
            try {
                DhcpPacket.decodeFullPacket(p, p.length, DhcpPacket.ENCAP_L2);
            } catch (DhcpPacket.ParseException e) {
                // Not a DHCP packet.
                return false;
            }

            return true;
        });

        return packet == null ? null :
                DhcpPacket.decodeFullPacket(packet, packet.length, DhcpPacket.ENCAP_L2);
    }

    public void sendPacket(ByteBuffer packet) throws Exception {
        mDownstreamReader.sendResponse(packet);
    }

    public byte[] getNextMatchedPacket(Predicate<byte[]> filter) {
        return mDownstreamReader.poll(PACKET_READ_TIMEOUT_MS, filter);
    }

    public static class RemoteResponder {
        final TapPacketReader mUpstreamReader;
        public RemoteResponder(TapPacketReader reader) {
            mUpstreamReader = reader;
        }

        public void sendPacket(ByteBuffer packet) throws Exception {
            mUpstreamReader.sendResponse(packet);
        }

        public byte[] getNextMatchedPacket(Predicate<byte[]> filter) throws Exception {
            return mUpstreamReader.poll(PACKET_READ_TIMEOUT_MS, filter);
        }
    }
}
