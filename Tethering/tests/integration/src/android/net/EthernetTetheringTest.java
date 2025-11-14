/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.LOG_COMPAT_CHANGE;
import static android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG;
import static android.net.InetAddresses.parseNumericAddress;
import static android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_VIRTUAL;
import static android.net.TetheringTester.TestDnsPacket;
import static android.net.TetheringTester.buildIcmpEchoPacketV4;
import static android.net.TetheringTester.buildUdpPacket;
import static android.net.TetheringTester.isExpectedIcmpPacket;
import static android.net.TetheringTester.isExpectedUdpDnsPacket;
import static android.net.connectivity.ConnectivityCompatChanges.ENABLE_MATCH_NON_THREAD_LOCAL_NETWORKS;
import static android.provider.DeviceConfig.NAMESPACE_TETHERING;
import static android.system.OsConstants.ICMP_ECHO;
import static android.system.OsConstants.ICMP_ECHOREPLY;
import static android.system.OsConstants.IPPROTO_UDP;

import static com.android.net.module.util.ConnectivityUtils.isIPv6ULA;
import static com.android.net.module.util.HexDump.dumpHexString;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REQUEST_TYPE;
import static com.android.net.module.util.NetworkStackConstants.IPV4_HEADER_MIN_LEN;
import static com.android.net.module.util.NetworkStackConstants.IPV6_HEADER_LEN;
import static com.android.net.module.util.NetworkStackConstants.UDP_HEADER_LEN;
import static com.android.testutils.DeviceInfoUtils.KVersion;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.compat.CompatChanges;
import android.content.Context;
import android.net.TetheringManager.TetheringRequest;
import android.net.TetheringTester.TetheredDevice;
import android.os.Build;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.VintfRuntimeInfo;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfDump;
import com.android.net.module.util.Ipv6Utils;
import com.android.net.module.util.Struct;
import com.android.net.module.util.bpf.ClatEgress4Key;
import com.android.net.module.util.bpf.ClatEgress4Value;
import com.android.net.module.util.bpf.ClatIngress6Key;
import com.android.net.module.util.bpf.ClatIngress6Value;
import com.android.net.module.util.bpf.Tether4Key;
import com.android.net.module.util.bpf.Tether4Value;
import com.android.net.module.util.bpf.TetherStatsKey;
import com.android.net.module.util.bpf.TetherStatsValue;
import com.android.net.module.util.structs.Ipv4Header;
import com.android.net.module.util.structs.UdpHeader;
import com.android.testutils.AutoReleaseNetworkCallbackRule;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DeviceConfigRule;
import com.android.testutils.DeviceInfoUtils;
import com.android.testutils.DumpTestUtils;
import com.android.testutils.NetworkStackModuleTest;
import com.android.testutils.PollPacketReader;
import com.android.testutils.TestableNetworkCallback;
import com.android.testutils.TestableNetworkCallback.Event;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class EthernetTetheringTest extends EthernetTetheringTestBase {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();
    // For manipulating feature flag before and after testing.
    @Rule
    public final DeviceConfigRule mDeviceConfigRule = new DeviceConfigRule();
    @Rule
    public final AutoReleaseNetworkCallbackRule
            mNetworkCallbackRule = new AutoReleaseNetworkCallbackRule();

    private static final String TAG = EthernetTetheringTest.class.getSimpleName();

    private static final short DNS_PORT = 53;
    private static final short ICMPECHO_ID = 0x0;
    private static final short ICMPECHO_SEQ = 0x0;

    private static final int DUMP_POLLING_MAX_RETRY = 100;
    private static final int DUMP_POLLING_INTERVAL_MS = 50;
    // Kernel treats a confirmed UDP connection which active after two seconds as stream mode.
    // See upstream commit b7b1d02fc43925a4d569ec221715db2dfa1ce4f5.
    private static final int UDP_STREAM_TS_MS = 2000;
    // Give slack time for waiting UDP stream mode because handling conntrack event in user space
    // may not in precise time. Used to reduce the flaky rate.
    private static final int UDP_STREAM_SLACK_MS = 500;
    // Per RX UDP packet size: iphdr (20) + udphdr (8) + payload (2) = 30 bytes.
    private static final int RX_UDP_PACKET_SIZE = 30;
    private static final int RX_UDP_PACKET_COUNT = 456;
    // Per TX UDP packet size: iphdr (20) + udphdr (8) + payload (2) = 30 bytes.
    private static final int TX_UDP_PACKET_SIZE = 30;
    private static final int TX_UDP_PACKET_COUNT = 123;

    private static final String DUMPSYS_CLAT_RAWMAP_EGRESS4_ARG = "clatEgress4RawBpfMap";
    private static final String DUMPSYS_CLAT_RAWMAP_INGRESS6_ARG = "clatIngress6RawBpfMap";
    private static final String DUMPSYS_TETHERING_RAWMAP_ARG = "bpfRawMap";
    private static final String DUMPSYS_RAWMAP_ARG_STATS = "--stats";
    private static final String DUMPSYS_RAWMAP_ARG_UPSTREAM4 = "--upstream4";
    private static final String LINE_DELIMITER = "\\n";

    // TODO: use class DnsPacket to build DNS query and reply message once DnsPacket supports
    // building packet for given arguments.
    private static final ByteBuffer DNS_QUERY = ByteBuffer.wrap(new byte[] {
            // scapy.DNS(
            //   id=0xbeef,
            //   qr=0,
            //   qd=scapy.DNSQR(qname="hello.example.com"))
            //
            /* Header */
            (byte) 0xbe, (byte) 0xef, /* Transaction ID: 0xbeef */
            (byte) 0x01, (byte) 0x00, /* Flags: rd */
            (byte) 0x00, (byte) 0x01, /* Questions: 1 */
            (byte) 0x00, (byte) 0x00, /* Answer RRs: 0 */
            (byte) 0x00, (byte) 0x00, /* Authority RRs: 0 */
            (byte) 0x00, (byte) 0x00, /* Additional RRs: 0 */
            /* Queries */
            (byte) 0x05, (byte) 0x68, (byte) 0x65, (byte) 0x6c,
            (byte) 0x6c, (byte) 0x6f, (byte) 0x07, (byte) 0x65,
            (byte) 0x78, (byte) 0x61, (byte) 0x6d, (byte) 0x70,
            (byte) 0x6c, (byte) 0x65, (byte) 0x03, (byte) 0x63,
            (byte) 0x6f, (byte) 0x6d, (byte) 0x00, /* Name: hello.example.com */
            (byte) 0x00, (byte) 0x01,              /* Type: A */
            (byte) 0x00, (byte) 0x01               /* Class: IN */
    });

    private static final byte[] DNS_REPLY = new byte[] {
            // scapy.DNS(
            //   id=0,
            //   qr=1,
            //   qd=scapy.DNSQR(qname="hello.example.com"),
            //   an=scapy.DNSRR(rrname="hello.example.com", rdata='1.2.3.4'))
            //
            /* Header */
            (byte) 0x00, (byte) 0x00, /* Transaction ID: 0x0, must be updated by dns query id */
            (byte) 0x81, (byte) 0x00, /* Flags: qr rd */
            (byte) 0x00, (byte) 0x01, /* Questions: 1 */
            (byte) 0x00, (byte) 0x01, /* Answer RRs: 1 */
            (byte) 0x00, (byte) 0x00, /* Authority RRs: 0 */
            (byte) 0x00, (byte) 0x00, /* Additional RRs: 0 */
            /* Queries */
            (byte) 0x05, (byte) 0x68, (byte) 0x65, (byte) 0x6c,
            (byte) 0x6c, (byte) 0x6f, (byte) 0x07, (byte) 0x65,
            (byte) 0x78, (byte) 0x61, (byte) 0x6d, (byte) 0x70,
            (byte) 0x6c, (byte) 0x65, (byte) 0x03, (byte) 0x63,
            (byte) 0x6f, (byte) 0x6d, (byte) 0x00,              /* Name: hello.example.com */
            (byte) 0x00, (byte) 0x01,                           /* Type: A */
            (byte) 0x00, (byte) 0x01,                           /* Class: IN */
            /* Answers */
            (byte) 0x05, (byte) 0x68, (byte) 0x65, (byte) 0x6c,
            (byte) 0x6c, (byte) 0x6f, (byte) 0x07, (byte) 0x65,
            (byte) 0x78, (byte) 0x61, (byte) 0x6d, (byte) 0x70,
            (byte) 0x6c, (byte) 0x65, (byte) 0x03, (byte) 0x63,
            (byte) 0x6f, (byte) 0x6d, (byte) 0x00,              /* Name: hello.example.com */
            (byte) 0x00, (byte) 0x01,                           /* Type: A */
            (byte) 0x00, (byte) 0x01,                           /* Class: IN */
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, /* Time to live: 0 */
            (byte) 0x00, (byte) 0x04,                           /* Data length: 4 */
            (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04  /* Address: 1.2.3.4 */
    };

    // Shamelessly copied from TetheringConfiguration.
    private static final String TETHERING_LOCAL_NETWORK_AGENT = "tethering_local_network_agent";
    private static final String WIFIP2PGO_LOCAL_NETWORK_AGENT = "wifip2pgo_local_network_agent";

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        // TODO: See b/318121782#comment4. Register an ethernet InterfaceStateListener, and wait for
        // the callback to report client mode. This happens as soon as both
        // TetheredInterfaceRequester and the tethering code itself have released the interface,
        // i.e. after stopTethering() has completed.
        Thread.sleep(3000);
    }

    @Test
    public void testVirtualEthernetAlreadyExists() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(isInterfaceForTetheringAvailable());

        TestNetworkInterface downstreamIface = null;
        MyTetheringEventCallback tetheringEventCallback = null;
        PollPacketReader downstreamReader = null;

        try {
            downstreamIface = createTestInterface();
            // This must be done now because as soon as setIncludeTestInterfaces(true) is called,
            // the interface will be placed in client mode, which will delete the link-local
            // address. At that point NetworkInterface.getByName() will cease to work on the
            // interface, because starting in R NetworkInterface can no longer see interfaces
            // without IP addresses.
            int mtu = getMTU(downstreamIface);

            Log.d(TAG, "Including test interfaces");
            setIncludeTestInterfaces(true);

            final String iface = mTetheredInterfaceRequester.getInterface();
            assertEquals("TetheredInterfaceCallback for unexpected interface",
                    downstreamIface.getInterfaceName(), iface);

            // Check virtual ethernet.
            FileDescriptor fd = downstreamIface.getFileDescriptor().getFileDescriptor();
            downstreamReader = makePacketReader(fd, mtu);
            tetheringEventCallback = enableEthernetTethering(downstreamIface.getInterfaceName(),
                    null /* any upstream */);
            checkTetheredClientCallbacks(
                    downstreamReader, TETHERING_ETHERNET, tetheringEventCallback);
        } finally {
            maybeStopTapPacketReader(downstreamReader);
            maybeCloseTestInterface(downstreamIface);
            maybeUnregisterTetheringEventCallback(tetheringEventCallback);
        }
    }

    @Test
    public void testVirtualEthernet() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(isInterfaceForTetheringAvailable());

        setIncludeTestInterfaces(true);

        TestNetworkInterface downstreamIface = null;
        MyTetheringEventCallback tetheringEventCallback = null;
        PollPacketReader downstreamReader = null;

        try {
            downstreamIface = createTestInterface();

            final String iface = mTetheredInterfaceRequester.getInterface();
            assertEquals("TetheredInterfaceCallback for unexpected interface",
                    downstreamIface.getInterfaceName(), iface);

            // Check virtual ethernet.
            FileDescriptor fd = downstreamIface.getFileDescriptor().getFileDescriptor();
            downstreamReader = makePacketReader(fd, getMTU(downstreamIface));
            tetheringEventCallback = enableEthernetTethering(downstreamIface.getInterfaceName(),
                    null /* any upstream */);
            checkTetheredClientCallbacks(
                    downstreamReader, TETHERING_ETHERNET, tetheringEventCallback);
        } finally {
            maybeStopTapPacketReader(downstreamReader);
            maybeCloseTestInterface(downstreamIface);
            maybeUnregisterTetheringEventCallback(tetheringEventCallback);
        }
    }

    @Test
    public void testStaticIpv4() throws Exception {
        assumeFalse(isInterfaceForTetheringAvailable());

        setIncludeTestInterfaces(true);

        TestNetworkInterface downstreamIface = null;
        MyTetheringEventCallback tetheringEventCallback = null;
        PollPacketReader downstreamReader = null;

        try {
            downstreamIface = createTestInterface();

            final String iface = mTetheredInterfaceRequester.getInterface();
            assertEquals("TetheredInterfaceCallback for unexpected interface",
                    downstreamIface.getInterfaceName(), iface);

            assertInvalidStaticIpv4Request(iface, null, null);
            assertInvalidStaticIpv4Request(iface, "2001:db8::1/64", "2001:db8:2::/64");
            assertInvalidStaticIpv4Request(iface, "192.0.2.2/28", "2001:db8:2::/28");
            assertInvalidStaticIpv4Request(iface, "2001:db8:2::/28", "192.0.2.2/28");
            assertInvalidStaticIpv4Request(iface, "192.0.2.2/28", null);
            assertInvalidStaticIpv4Request(iface, null, "192.0.2.2/28");
            assertInvalidStaticIpv4Request(iface, "192.0.2.3/27", "192.0.2.2/28");

            final String localAddr = "192.0.2.3/28";
            final String clientAddr = "192.0.2.2/28";
            tetheringEventCallback = enableTethering(iface,
                    requestWithStaticIpv4(localAddr, clientAddr), null /* any upstream */);

            tetheringEventCallback.awaitInterfaceTethered();
            assertInterfaceHasIpAddress(iface, localAddr);

            byte[] client1 = MacAddress.fromString("1:2:3:4:5:6").toByteArray();
            byte[] client2 = MacAddress.fromString("a:b:c:d:e:f").toByteArray();

            FileDescriptor fd = downstreamIface.getFileDescriptor().getFileDescriptor();
            downstreamReader = makePacketReader(fd, getMTU(downstreamIface));
            TetheringTester tester = new TetheringTester(downstreamReader);
            DhcpResults dhcpResults = tester.runDhcp(client1);
            assertEquals(new LinkAddress(clientAddr), dhcpResults.ipAddress);

            try {
                tester.runDhcp(client2);
                fail("Only one client should get an IP address");
            } catch (TimeoutException expected) { }
        } finally {
            maybeStopTapPacketReader(downstreamReader);
            maybeCloseTestInterface(downstreamIface);
            maybeUnregisterTetheringEventCallback(tetheringEventCallback);
        }
    }

    private static void expectLocalOnlyAddresses(String iface) throws Exception {
        final List<InterfaceAddress> interfaceAddresses =
                NetworkInterface.getByName(iface).getInterfaceAddresses();

        boolean foundIpv6Ula = false;
        for (InterfaceAddress ia : interfaceAddresses) {
            final InetAddress addr = ia.getAddress();
            if (isIPv6ULA(addr)) {
                foundIpv6Ula = true;
            }
            final int prefixlen = ia.getNetworkPrefixLength();
            final LinkAddress la = new LinkAddress(addr, prefixlen);
            if (la.isIpv6() && la.isGlobalPreferred()) {
                fail("Found global IPv6 address on local-only interface: " + interfaceAddresses);
            }
        }

        assertTrue("Did not find IPv6 ULA on local-only interface " + iface,
                foundIpv6Ula);
    }

    @Test
    public void testLocalOnlyTethering() throws Exception {
        assumeFalse(isInterfaceForTetheringAvailable());

        setIncludeTestInterfaces(true);

        TestNetworkInterface downstreamIface = null;
        MyTetheringEventCallback tetheringEventCallback = null;
        PollPacketReader downstreamReader = null;

        try {
            downstreamIface = createTestInterface();

            final String iface = mTetheredInterfaceRequester.getInterface();
            assertEquals("TetheredInterfaceCallback for unexpected interface",
                    downstreamIface.getInterfaceName(), iface);

            final TetheringRequest request = new TetheringRequest.Builder(TETHERING_ETHERNET)
                    .setConnectivityScope(CONNECTIVITY_SCOPE_LOCAL).build();
            tetheringEventCallback = enableTethering(iface, request, null /* any upstream */);
            tetheringEventCallback.awaitInterfaceLocalOnly();

            // makePacketReader only works after tethering is started, because until then the
            // interface does not have an IP address, and unprivileged apps cannot see interfaces
            // without IP addresses. This shouldn't be flaky because the TAP interface will buffer
            // all packets even before the reader is started.
            downstreamReader = makePacketReader(downstreamIface);

            waitForRouterAdvertisement(downstreamReader, iface, WAIT_RA_TIMEOUT_MS);
            expectLocalOnlyAddresses(iface);

            // After testing the IPv6 local address, the DHCP server may still be in the process
            // of being created. If the downstream interface is killed by the test while the
            // DHCP server is starting, a DHCP server error may occur. To ensure that the DHCP
            // server has started completely before finishing the test, also test the dhcp server
            // by calling runDhcp.
            final TetheringTester tester = new TetheringTester(downstreamReader);
            tester.runDhcp(MacAddress.fromString("1:2:3:4:5:6").toByteArray());
        } finally {
            maybeStopTapPacketReader(downstreamReader);
            maybeCloseTestInterface(downstreamIface);
            maybeUnregisterTetheringEventCallback(tetheringEventCallback);
        }
    }

    private boolean isAdbOverNetwork() {
        // If adb TCP port opened, this test may running by adb over network.
        return (SystemProperties.getInt("persist.adb.tcp.port", -1) > -1)
                || (SystemProperties.getInt("service.adb.tcp.port", -1) > -1);
    }

    @Test
    public void testPhysicalEthernet() throws Exception {
        assumeTrue(isInterfaceForTetheringAvailable());
        // Do not run this test if adb is over network and ethernet is connected.
        // It is likely the adb run over ethernet, the adb would break when ethernet is switching
        // from client mode to server mode. See b/160389275.
        assumeFalse(isAdbOverNetwork());

        MyTetheringEventCallback tetheringEventCallback = null;
        try {
            // Get an interface to use.
            final String iface = mTetheredInterfaceRequester.getInterface();

            // Enable Ethernet tethering and check that it starts.
            tetheringEventCallback = enableEthernetTethering(iface, null /* any upstream */);
        } finally {
            stopEthernetTethering(tetheringEventCallback);
        }
        // There is nothing more we can do on a physical interface without connecting an actual
        // client, which is not possible in this test.
    }

    private void checkTetheredClientCallbacks(final PollPacketReader packetReader,
            final int tetheringType,
            final MyTetheringEventCallback tetheringEventCallback) throws Exception {
        // Create a fake client.
        byte[] clientMacAddr = new byte[6];
        new Random().nextBytes(clientMacAddr);

        TetheringTester tester = new TetheringTester(packetReader);
        DhcpResults dhcpResults = tester.runDhcp(clientMacAddr);

        final Collection<TetheredClient> clients = tetheringEventCallback.awaitClientConnected();
        assertEquals(1, clients.size());
        final TetheredClient client = clients.iterator().next();

        // Check the MAC address.
        assertEquals(MacAddress.fromBytes(clientMacAddr), client.getMacAddress());
        assertEquals(tetheringType, client.getTetheringType());

        // Check the hostname.
        assertEquals(1, client.getAddresses().size());
        TetheredClient.AddressInfo info = client.getAddresses().get(0);
        assertEquals(TetheringTester.DHCP_HOSTNAME, info.getHostname());

        // Check the address is the one that was handed out in the DHCP ACK.
        assertLinkAddressMatches(dhcpResults.ipAddress, info.getAddress());

        // Check that the lifetime is correct +/- 10s.
        final long now = SystemClock.elapsedRealtime();
        final long actualLeaseDuration = (info.getAddress().getExpirationTime() - now) / 1000;
        final String msg = String.format("IP address should have lifetime of %d, got %d",
                dhcpResults.leaseDuration, actualLeaseDuration);
        assertTrue(msg, Math.abs(dhcpResults.leaseDuration - actualLeaseDuration) < 10);
    }

    public void assertLinkAddressMatches(LinkAddress l1, LinkAddress l2) {
        // Check all fields except the deprecation and expiry times.
        String msg = String.format("LinkAddresses do not match. expected: %s actual: %s", l1, l2);
        assertTrue(msg, l1.isSameAddressAs(l2));
        assertEquals("LinkAddress flags do not match", l1.getFlags(), l2.getFlags());
        assertEquals("LinkAddress scope does not match", l1.getScope(), l2.getScope());
    }

    private TetheringRequest requestWithStaticIpv4(String local, String client) {
        LinkAddress localAddr = local == null ? null : new LinkAddress(local);
        LinkAddress clientAddr = client == null ? null : new LinkAddress(client);
        return new TetheringRequest.Builder(TETHERING_ETHERNET)
                .setStaticIpv4Addresses(localAddr, clientAddr)
                .setShouldShowEntitlementUi(false).build();
    }

    private void assertInvalidStaticIpv4Request(String iface, String local, String client)
            throws Exception {
        try {
            enableTethering(iface, requestWithStaticIpv4(local, client), null /* any upstream */);
            fail("Unexpectedly accepted invalid IPv4 configuration: " + local + ", " + client);
        } catch (IllegalArgumentException | NullPointerException expected) { }
    }

    private void assertInterfaceHasIpAddress(String iface, String expected) throws Exception {
        LinkAddress expectedAddr = new LinkAddress(expected);
        NetworkInterface nif = NetworkInterface.getByName(iface);
        for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
            final LinkAddress addr = new LinkAddress(ia.getAddress(), ia.getNetworkPrefixLength());
            if (expectedAddr.equals(addr)) {
                return;
            }
        }
        fail("Expected " + iface + " to have IP address " + expected + ", found "
                + nif.getInterfaceAddresses());
    }

    @Test
    public void testIcmpv6Echo() throws Exception {
        runPing6Test(initTetheringTester(toList(TEST_IP4_ADDR, TEST_IP6_ADDR),
                toList(TEST_IP4_DNS, TEST_IP6_DNS)));
    }

    private void runPing6Test(TetheringTester tester) throws Exception {
        TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, true /* hasIpv6 */);
        Inet6Address remoteIp6Addr = (Inet6Address) parseNumericAddress("2400:222:222::222");
        ByteBuffer request = Ipv6Utils.buildEchoRequestPacket(tethered.macAddr,
                tethered.routerMacAddr, tethered.ipv6Addr, remoteIp6Addr);
        tester.verifyUpload(request, p -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));

            return isExpectedIcmpPacket(p, false /* hasEth */, false /* isIpv4 */,
                    ICMPV6_ECHO_REQUEST_TYPE);
        });

        ByteBuffer reply = Ipv6Utils.buildEchoReplyPacket(remoteIp6Addr, tethered.ipv6Addr);
        tester.verifyDownload(reply, p -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));

            return isExpectedIcmpPacket(p, true /* hasEth */, false /* isIpv4 */,
                    ICMPV6_ECHO_REPLY_TYPE);
        });
    }

    @Test
    public void testTetherUdpV6() throws Exception {
        final TetheringTester tester = initTetheringTester(toList(TEST_IP6_ADDR),
                toList(TEST_IP6_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, true /* hasIpv6 */);
        sendUploadPacketUdp(tethered.macAddr, tethered.routerMacAddr,
                tethered.ipv6Addr, REMOTE_IP6_ADDR, tester, false /* is4To6 */);
        sendDownloadPacketUdp(REMOTE_IP6_ADDR, tethered.ipv6Addr, tester, false /* is6To4 */);

        // TODO: test BPF offload maps {rule, stats}.
    }


    /**
     * Basic IPv4 UDP tethering test. Verify that UDP tethered packets are transferred no matter
     * using which data path.
     */
    @Test
    public void testTetherUdpV4() throws Exception {
        // Test network topology:
        //
        //         public network (rawip)                 private network
        //                   |                 UE                |
        // +------------+    V    +------------+------------+    V    +------------+
        // |   Sever    +---------+  Upstream  | Downstream +---------+   Client   |
        // +------------+         +------------+------------+         +------------+
        // remote ip              public ip                           private ip
        // 8.8.8.8:443            <Upstream ip>:9876                  <TetheredDevice ip>:9876
        //
        final TetheringTester tester = initTetheringTester(toList(TEST_IP4_ADDR),
                toList(TEST_IP4_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, false /* hasIpv6 */);

        // TODO: remove the connectivity verification for upstream connected notification race.
        // Because async upstream connected notification can't guarantee the tethering routing is
        // ready to use. Need to test tethering connectivity before testing.
        // For short term plan, consider using IPv6 RA to get MAC address because the prefix comes
        // from upstream. That can guarantee that the routing is ready. Long term plan is that
        // refactors upstream connected notification from async to sync.
        probeV4TetheringConnectivity(tester, tethered, false /* is4To6 */);

        final MacAddress srcMac = tethered.macAddr;
        final MacAddress dstMac = tethered.routerMacAddr;
        final InetAddress remoteIp = REMOTE_IP4_ADDR;
        final InetAddress tetheringUpstreamIp = TEST_IP4_ADDR.getAddress();
        final InetAddress clientIp = tethered.ipv4Addr;
        sendUploadPacketUdp(srcMac, dstMac, clientIp, remoteIp, tester, false /* is4To6 */);
        sendDownloadPacketUdp(remoteIp, tetheringUpstreamIp, tester, false /* is6To4 */);
    }

    // Test network topology:
    //
    //            public network (rawip)                 private network
    //                      |         UE (CLAT support)         |
    // +---------------+    V    +------------+------------+    V    +------------+
    // | NAT64 Gateway +---------+  Upstream  | Downstream +---------+   Client   |
    // +---------------+         +------------+------------+         +------------+
    // remote ip                 public ip                           private ip
    // [64:ff9b::808:808]:443    [clat ipv6]:9876                    [TetheredDevice ipv4]:9876
    //
    // Note that CLAT IPv6 address is generated by ClatCoordinator. Get the CLAT IPv6 address by
    // sending out an IPv4 packet and extracting the source address from CLAT translated IPv6
    // packet.
    //
    private void runClatUdpTest() throws Exception {
        // CLAT only starts on IPv6 only network.
        final TetheringTester tester = initTetheringTester(toList(TEST_IP6_ADDR),
                toList(TEST_IP6_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, true /* hasIpv6 */);

        // Get CLAT IPv6 address.
        final Inet6Address clatIp6 = getClatIpv6Address(tester, tethered);

        // Send an IPv4 UDP packet in original direction.
        // IPv4 packet -- CLAT translation --> IPv6 packet
        sendUploadPacketUdp(tethered.macAddr, tethered.routerMacAddr, tethered.ipv4Addr,
                REMOTE_IP4_ADDR, tester, true /* is4To6 */);

        // Send an IPv6 UDP packet in reply direction.
        // IPv6 packet -- CLAT translation --> IPv4 packet
        sendDownloadPacketUdp(REMOTE_NAT64_ADDR, clatIp6, tester, true /* is6To4 */);

        // TODO: test CLAT bpf maps.
    }

    // TODO: support R device. See b/234727688.
    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testTetherClatUdp() throws Exception {
        runClatUdpTest();
    }

    @Test
    public void testIcmpv4Echo() throws Exception {
        final TetheringTester tester = initTetheringTester(toList(TEST_IP4_ADDR),
                toList(TEST_IP4_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, false /* hasIpv6 */);

        // TODO: remove the connectivity verification for upstream connected notification race.
        // See the same reason in testTetherUdp4().
        probeV4TetheringConnectivity(tester, tethered, false /* is4To6 */);

        final ByteBuffer request = buildIcmpEchoPacketV4(tethered.macAddr /* srcMac */,
                tethered.routerMacAddr /* dstMac */, tethered.ipv4Addr /* srcIp */,
                REMOTE_IP4_ADDR /* dstIp */, ICMP_ECHO, ICMPECHO_ID, ICMPECHO_SEQ);
        tester.verifyUpload(request, p -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));

            return isExpectedIcmpPacket(p, false /* hasEth */, true /* isIpv4 */, ICMP_ECHO);
        });

        final ByteBuffer reply = buildIcmpEchoPacketV4(REMOTE_IP4_ADDR /* srcIp*/,
                (Inet4Address) TEST_IP4_ADDR.getAddress() /* dstIp */, ICMP_ECHOREPLY, ICMPECHO_ID,
                ICMPECHO_SEQ);
        tester.verifyDownload(reply, p -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));

            return isExpectedIcmpPacket(p, true /* hasEth */, true /* isIpv4 */, ICMP_ECHOREPLY);
        });
    }

    // TODO: support R device. See b/234727688.
    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testTetherClatIcmp() throws Exception {
        // CLAT only starts on IPv6 only network.
        final TetheringTester tester = initTetheringTester(toList(TEST_IP6_ADDR),
                toList(TEST_IP6_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, true /* hasIpv6 */);

        // Get CLAT IPv6 address.
        final Inet6Address clatIp6 = getClatIpv6Address(tester, tethered);

        // Send an IPv4 ICMP packet in original direction.
        // IPv4 packet -- CLAT translation --> IPv6 packet
        final ByteBuffer request = buildIcmpEchoPacketV4(tethered.macAddr /* srcMac */,
                tethered.routerMacAddr /* dstMac */, tethered.ipv4Addr /* srcIp */,
                (Inet4Address) REMOTE_IP4_ADDR /* dstIp */, ICMP_ECHO, ICMPECHO_ID, ICMPECHO_SEQ);
        tester.verifyUpload(request, p -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));

            return isExpectedIcmpPacket(p, false /* hasEth */, false /* isIpv4 */,
                    ICMPV6_ECHO_REQUEST_TYPE);
        });

        // Send an IPv6 ICMP packet in reply direction.
        // IPv6 packet -- CLAT translation --> IPv4 packet
        final ByteBuffer reply = Ipv6Utils.buildEchoReplyPacket(
                (Inet6Address) REMOTE_NAT64_ADDR /* srcIp */, clatIp6 /* dstIp */);
        tester.verifyDownload(reply, p -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));

            return isExpectedIcmpPacket(p, true /* hasEth */, true /* isIpv4 */, ICMP_ECHOREPLY);
        });
    }

    @NonNull
    private ByteBuffer buildDnsReplyMessageById(short id) {
        byte[] replyMessage = Arrays.copyOf(DNS_REPLY, DNS_REPLY.length);
        // Assign transaction id of reply message pattern with a given DNS transaction id.
        replyMessage[0] = (byte) ((id >> 8) & 0xff);
        replyMessage[1] = (byte) (id & 0xff);
        Log.d(TAG, "Built DNS reply: " + dumpHexString(replyMessage));

        return ByteBuffer.wrap(replyMessage);
    }

    @NonNull
    private void sendDownloadPacketDnsV4(@NonNull final Inet4Address srcIp,
            @NonNull final Inet4Address dstIp, short srcPort, short dstPort, short dnsId,
            @NonNull final TetheringTester tester) throws Exception {
        // DNS response transaction id must be copied from DNS query. Used by the requester
        // to match up replies to outstanding queries. See RFC 1035 section 4.1.1.
        final ByteBuffer dnsReplyMessage = buildDnsReplyMessageById(dnsId);
        final ByteBuffer testPacket = buildUdpPacket((InetAddress) srcIp,
                (InetAddress) dstIp, srcPort, dstPort, dnsReplyMessage);

        tester.verifyDownload(testPacket, p -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));
            return isExpectedUdpDnsPacket(p, true /* hasEther */, true /* isIpv4 */,
                    dnsReplyMessage);
        });
    }

    // Send IPv4 UDP DNS packet and return the forwarded DNS packet on upstream.
    @NonNull
    private byte[] sendUploadPacketDnsV4(@NonNull final MacAddress srcMac,
            @NonNull final MacAddress dstMac, @NonNull final Inet4Address srcIp,
            @NonNull final Inet4Address dstIp, short srcPort, short dstPort,
            @NonNull final TetheringTester tester) throws Exception {
        final ByteBuffer testPacket = buildUdpPacket(srcMac, dstMac, srcIp, dstIp,
                srcPort, dstPort, DNS_QUERY);

        return tester.verifyUpload(testPacket, p -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));
            return isExpectedUdpDnsPacket(p, false /* hasEther */, true /* isIpv4 */,
                    DNS_QUERY);
        });
    }

    @Test
    public void testTetherUdpV4Dns() throws Exception {
        final TetheringTester tester = initTetheringTester(toList(TEST_IP4_ADDR),
                toList(TEST_IP4_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, false /* hasIpv6 */);

        // TODO: remove the connectivity verification for upstream connected notification race.
        // See the same reason in testTetherUdp4().
        probeV4TetheringConnectivity(tester, tethered, false /* is4To6 */);

        // [1] Send DNS query.
        // tethered device --> downstream --> dnsmasq forwarding --> upstream --> DNS server
        //
        // Need to extract DNS transaction id and source port from dnsmasq forwarded DNS query
        // packet. dnsmasq forwarding creats new query which means UDP source port and DNS
        // transaction id are changed from original sent DNS query. See forward_query() in
        // external/dnsmasq/src/forward.c. Note that #TetheringTester.isExpectedUdpDnsPacket
        // guarantees that |forwardedQueryPacket| is a valid DNS packet. So we can parse it as DNS
        // packet.
        final MacAddress srcMac = tethered.macAddr;
        final MacAddress dstMac = tethered.routerMacAddr;
        final Inet4Address clientIp = tethered.ipv4Addr;
        final Inet4Address gatewayIp = tethered.ipv4Gatway;
        final byte[] forwardedQueryPacket = sendUploadPacketDnsV4(srcMac, dstMac, clientIp,
                gatewayIp, LOCAL_PORT, DNS_PORT, tester);
        final ByteBuffer buf = ByteBuffer.wrap(forwardedQueryPacket);
        Struct.parse(Ipv4Header.class, buf);
        final UdpHeader udpHeader = Struct.parse(UdpHeader.class, buf);
        final TestDnsPacket dnsQuery = TestDnsPacket.getTestDnsPacket(buf);
        assertNotNull(dnsQuery);
        Log.d(TAG, "Forwarded UDP source port: " + udpHeader.srcPort + ", DNS query id: "
                + dnsQuery.getHeader().getId());

        // [2] Send DNS reply.
        // DNS server --> upstream --> dnsmasq forwarding --> downstream --> tethered device
        //
        // DNS reply transaction id must be copied from DNS query. Used by the requester to match
        // up replies to outstanding queries. See RFC 1035 section 4.1.1.
        final Inet4Address remoteIp = (Inet4Address) TEST_IP4_DNS;
        final Inet4Address tetheringUpstreamIp = (Inet4Address) TEST_IP4_ADDR.getAddress();
        sendDownloadPacketDnsV4(remoteIp, tetheringUpstreamIp, DNS_PORT,
                (short) udpHeader.srcPort, (short) dnsQuery.getHeader().getId(), tester);
    }

    @Test
    public void testTetherTcpV4() throws Exception {
        final TetheringTester tester = initTetheringTester(toList(TEST_IP4_ADDR),
                toList(TEST_IP4_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, false /* hasIpv6 */);

        // TODO: remove the connectivity verification for upstream connected notification race.
        // See the same reason in testTetherUdp4().
        probeV4TetheringConnectivity(tester, tethered, false /* is4To6 */);

        runTcpTest(tethered.macAddr /* uploadSrcMac */, tethered.routerMacAddr /* uploadDstMac */,
                tethered.ipv4Addr /* uploadSrcIp */, REMOTE_IP4_ADDR /* uploadDstIp */,
                REMOTE_IP4_ADDR /* downloadSrcIp */, TEST_IP4_ADDR.getAddress() /* downloadDstIp */,
                tester, false /* isClat */);
    }

    @Test
    public void testTetherTcpV6() throws Exception {
        final TetheringTester tester = initTetheringTester(toList(TEST_IP6_ADDR),
                toList(TEST_IP6_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, true /* hasIpv6 */);

        runTcpTest(tethered.macAddr /* uploadSrcMac */, tethered.routerMacAddr /* uploadDstMac */,
                tethered.ipv6Addr /* uploadSrcIp */, REMOTE_IP6_ADDR /* uploadDstIp */,
                REMOTE_IP6_ADDR /* downloadSrcIp */, tethered.ipv6Addr /* downloadDstIp */,
                tester, false /* isClat */);
    }

    // TODO: support R device. See b/234727688.
    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testTetherClatTcp() throws Exception {
        // CLAT only starts on IPv6 only network.
        final TetheringTester tester = initTetheringTester(toList(TEST_IP6_ADDR),
                toList(TEST_IP6_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, true /* hasIpv6 */);

        // Get CLAT IPv6 address.
        final Inet6Address clatIp6 = getClatIpv6Address(tester, tethered);

        runTcpTest(tethered.macAddr /* uploadSrcMac */, tethered.routerMacAddr /* uploadDstMac */,
                tethered.ipv4Addr /* uploadSrcIp */, REMOTE_IP4_ADDR /* uploadDstIp */,
                REMOTE_NAT64_ADDR /* downloadSrcIp */, clatIp6 /* downloadDstIp */,
                tester, true /* isClat */);
    }

    private static final byte[] ZeroLengthDhcpPacket = new byte[] {
            // scapy.Ether(
            //   dst="ff:ff:ff:ff:ff:ff")
            // scapy.IP(
            //   dst="255.255.255.255")
            // scapy.UDP(sport=68, dport=67)
            /* Ethernet Header */
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xe0, (byte) 0x4f, (byte) 0x43, (byte) 0xe6, (byte) 0xfb, (byte) 0xd2,
            (byte) 0x08, (byte) 0x00,
            /* Ip header */
            (byte) 0x45, (byte) 0x00, (byte) 0x00, (byte) 0x1c, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x00, (byte) 0x40, (byte) 0x11, (byte) 0xb6, (byte) 0x58,
            (byte) 0x64, (byte) 0x4f, (byte) 0x60, (byte) 0x29, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff,
            /* UDP header */
            (byte) 0x00, (byte) 0x44, (byte) 0x00, (byte) 0x43,
            (byte) 0x00, (byte) 0x08, (byte) 0x3a, (byte) 0xdf
    };

    // This test requires the update in NetworkStackModule(See b/269692093).
    @NetworkStackModuleTest
    @Test
    public void testTetherZeroLengthDhcpPacket() throws Exception {
        final TetheringTester tester = initTetheringTester(toList(TEST_IP4_ADDR),
                toList(TEST_IP4_DNS));
        tester.createTetheredDevice(TEST_MAC, false /* hasIpv6 */);

        // Send a zero-length DHCP packet to upstream DHCP server.
        final ByteBuffer packet = ByteBuffer.wrap(ZeroLengthDhcpPacket);
        tester.sendUploadPacket(packet);

        // Send DHCPDISCOVER packet from another downstream tethered device to verify that
        // upstream DHCP server doesn't close the listening socket and stop reading, then we
        // can still receive the next DHCP packet from server.
        final MacAddress macAddress = MacAddress.fromString("11:22:33:44:55:66");
        assertTrue(tester.testDhcpServerAlive(macAddress));
    }

    private static boolean isUdpOffloadSupportedByKernel(final String kernelVersion) {
        final KVersion current = DeviceInfoUtils.getMajorMinorSubminorVersion(kernelVersion);
        return current.isInRange(new KVersion(4, 14, 222), new KVersion(4, 19, 0))
                || current.isInRange(new KVersion(4, 19, 176), new KVersion(5, 4, 0))
                || current.isAtLeast(new KVersion(5, 4, 98));
    }

    @Test
    public void testIsUdpOffloadSupportedByKernel() throws Exception {
        assertFalse(isUdpOffloadSupportedByKernel("4.14.221"));
        assertTrue(isUdpOffloadSupportedByKernel("4.14.222"));
        assertTrue(isUdpOffloadSupportedByKernel("4.16.0"));
        assertTrue(isUdpOffloadSupportedByKernel("4.18.0"));
        assertFalse(isUdpOffloadSupportedByKernel("4.19.0"));

        assertFalse(isUdpOffloadSupportedByKernel("4.19.175"));
        assertTrue(isUdpOffloadSupportedByKernel("4.19.176"));
        assertTrue(isUdpOffloadSupportedByKernel("5.2.0"));
        assertTrue(isUdpOffloadSupportedByKernel("5.3.0"));
        assertFalse(isUdpOffloadSupportedByKernel("5.4.0"));

        assertFalse(isUdpOffloadSupportedByKernel("5.4.97"));
        assertTrue(isUdpOffloadSupportedByKernel("5.4.98"));
        assertTrue(isUdpOffloadSupportedByKernel("5.10.0"));
    }

    private static void assumeKernelSupportBpfOffloadUdpV4() {
        final String kernelVersion = VintfRuntimeInfo.getKernelVersion();
        assumeTrue("Kernel version " + kernelVersion + " doesn't support IPv4 UDP BPF offload",
                isUdpOffloadSupportedByKernel(kernelVersion));
    }

    @Test
    public void testKernelSupportBpfOffloadUdpV4() throws Exception {
        assumeKernelSupportBpfOffloadUdpV4();
    }

    private boolean isTetherConfigBpfOffloadEnabled() throws Exception {
        final String dumpStr = runAsShell(DUMP, () ->
                DumpTestUtils.dumpService(Context.TETHERING_SERVICE, "--short"));

        // BPF offload tether config can be overridden by "config_tether_enable_bpf_offload" in
        // packages/modules/Connectivity/Tethering/res/values/config.xml. OEM may disable config by
        // RRO to override the enabled default value. Get the tethering config via dumpsys.
        // $ dumpsys tethering
        //   mIsBpfEnabled: true
        boolean enabled = dumpStr.contains("mIsBpfEnabled: true");
        if (!enabled) {
            Log.d(TAG, "BPF offload tether config not enabled: " + dumpStr);
        }
        return enabled;
    }

    @Test
    public void testTetherConfigBpfOffloadEnabled() throws Exception {
        assumeTrue(isTetherConfigBpfOffloadEnabled());
    }

    @NonNull
    private <K extends Struct, V extends Struct> HashMap<K, V> dumpAndParseRawMap(
            Class<K> keyClass, Class<V> valueClass, @NonNull String service, @NonNull String[] args)
            throws Exception {
        final String rawMapStr = runAsShell(DUMP, () ->
                DumpTestUtils.dumpService(service, args));
        final HashMap<K, V> map = new HashMap<>();

        for (final String line : rawMapStr.split(LINE_DELIMITER)) {
            final Pair<K, V> rule =
                    BpfDump.fromBase64EncodedString(keyClass, valueClass, line.trim());
            map.put(rule.first, rule.second);
        }
        return map;
    }

    @Nullable
    private <K extends Struct, V extends Struct> HashMap<K, V> pollRawMapFromDump(
            Class<K> keyClass, Class<V> valueClass, @NonNull String service, @NonNull String[] args)
            throws Exception {
        for (int retryCount = 0; retryCount < DUMP_POLLING_MAX_RETRY; retryCount++) {
            final HashMap<K, V> map = dumpAndParseRawMap(keyClass, valueClass, service, args);
            if (!map.isEmpty()) return map;

            Thread.sleep(DUMP_POLLING_INTERVAL_MS);
        }

        fail("Cannot get rules after " + DUMP_POLLING_MAX_RETRY * DUMP_POLLING_INTERVAL_MS + "ms");
        return null;
    }

    // Test network topology:
    //
    //         public network (rawip)                 private network
    //                   |                 UE                |
    // +------------+    V    +------------+------------+    V    +------------+
    // |   Sever    +---------+  Upstream  | Downstream +---------+   Client   |
    // +------------+         +------------+------------+         +------------+
    // remote ip              public ip                           private ip
    // 8.8.8.8:443            <Upstream ip>:9876                  <TetheredDevice ip>:9876
    //
    private void runUdp4Test() throws Exception {
        final TetheringTester tester = initTetheringTester(toList(TEST_IP4_ADDR),
                toList(TEST_IP4_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, false /* hasIpv6 */);

        // TODO: remove the connectivity verification for upstream connected notification race.
        // Because async upstream connected notification can't guarantee the tethering routing is
        // ready to use. Need to test tethering connectivity before testing.
        // For short term plan, consider using IPv6 RA to get MAC address because the prefix comes
        // from upstream. That can guarantee that the routing is ready. Long term plan is that
        // refactors upstream connected notification from async to sync.
        probeV4TetheringConnectivity(tester, tethered, false /* is4To6 */);

        final MacAddress srcMac = tethered.macAddr;
        final MacAddress dstMac = tethered.routerMacAddr;
        final InetAddress remoteIp = REMOTE_IP4_ADDR;
        final InetAddress tetheringUpstreamIp = TEST_IP4_ADDR.getAddress();
        final InetAddress clientIp = tethered.ipv4Addr;
        sendUploadPacketUdp(srcMac, dstMac, clientIp, remoteIp, tester, false /* is4To6 */);
        sendDownloadPacketUdp(remoteIp, tetheringUpstreamIp, tester, false /* is6To4 */);

        // Send second UDP packet in original direction.
        // The BPF coordinator only offloads the ASSURED conntrack entry. The "request + reply"
        // packets can make status IPS_SEEN_REPLY to be set. Need one more packet to make
        // conntrack status IPS_ASSURED_BIT to be set. Note the third packet needs to delay
        // 2 seconds because kernel monitors a UDP connection which still alive after 2 seconds
        // and apply ASSURED flag.
        // See kernel upstream commit b7b1d02fc43925a4d569ec221715db2dfa1ce4f5 and
        // nf_conntrack_udp_packet in net/netfilter/nf_conntrack_proto_udp.c
        Thread.sleep(UDP_STREAM_TS_MS);
        sendUploadPacketUdp(srcMac, dstMac, clientIp, remoteIp, tester, false /* is4To6 */);

        // Give a slack time for handling conntrack event in user space.
        Thread.sleep(UDP_STREAM_SLACK_MS);

        // [1] Verify IPv4 upstream rule map.
        final String[] upstreamArgs = new String[] {DUMPSYS_TETHERING_RAWMAP_ARG,
                DUMPSYS_RAWMAP_ARG_UPSTREAM4};
        final HashMap<Tether4Key, Tether4Value> upstreamMap = pollRawMapFromDump(
                Tether4Key.class, Tether4Value.class, Context.TETHERING_SERVICE, upstreamArgs);
        assertNotNull(upstreamMap);
        assertEquals(1, upstreamMap.size());

        final Map.Entry<Tether4Key, Tether4Value> rule =
                upstreamMap.entrySet().iterator().next();

        final Tether4Key upstream4Key = rule.getKey();
        assertEquals(IPPROTO_UDP, upstream4Key.l4proto);
        assertTrue(Arrays.equals(tethered.ipv4Addr.getAddress(), upstream4Key.src4));
        assertEquals(LOCAL_PORT, upstream4Key.srcPort);
        assertTrue(Arrays.equals(REMOTE_IP4_ADDR.getAddress(), upstream4Key.dst4));
        assertEquals(REMOTE_PORT, upstream4Key.dstPort);

        final Tether4Value upstream4Value = rule.getValue();
        assertTrue(Arrays.equals(tetheringUpstreamIp.getAddress(),
                InetAddress.getByAddress(upstream4Value.src46).getAddress()));
        assertEquals(LOCAL_PORT, upstream4Value.srcPort);
        assertTrue(Arrays.equals(REMOTE_IP4_ADDR.getAddress(),
                InetAddress.getByAddress(upstream4Value.dst46).getAddress()));
        assertEquals(REMOTE_PORT, upstream4Value.dstPort);

        // [2] Verify stats map.
        // Transmit packets on both direction for verifying stats. Because we only care the
        // packet count in stats test, we just reuse the existing packets to increaes
        // the packet count on both direction.

        // Send packets on original direction.
        for (int i = 0; i < TX_UDP_PACKET_COUNT; i++) {
            sendUploadPacketUdp(srcMac, dstMac, clientIp, remoteIp, tester,
                    false /* is4To6 */);
        }

        // Send packets on reply direction.
        for (int i = 0; i < RX_UDP_PACKET_COUNT; i++) {
            sendDownloadPacketUdp(remoteIp, tetheringUpstreamIp, tester, false /* is6To4 */);
        }

        // Dump stats map to verify.
        final String[] statsArgs = new String[] {DUMPSYS_TETHERING_RAWMAP_ARG,
                DUMPSYS_RAWMAP_ARG_STATS};
        final HashMap<TetherStatsKey, TetherStatsValue> statsMap = pollRawMapFromDump(
                TetherStatsKey.class, TetherStatsValue.class, Context.TETHERING_SERVICE, statsArgs);
        assertNotNull(statsMap);
        assertEquals(1, statsMap.size());

        final Map.Entry<TetherStatsKey, TetherStatsValue> stats =
                statsMap.entrySet().iterator().next();

        // TODO: verify the upstream index in TetherStatsKey.

        final TetherStatsValue statsValue = stats.getValue();
        assertEquals(RX_UDP_PACKET_COUNT, statsValue.rxPackets);
        assertEquals(RX_UDP_PACKET_COUNT * RX_UDP_PACKET_SIZE, statsValue.rxBytes);
        assertEquals(0, statsValue.rxErrors);
        assertEquals(TX_UDP_PACKET_COUNT, statsValue.txPackets);
        assertEquals(TX_UDP_PACKET_COUNT * TX_UDP_PACKET_SIZE, statsValue.txBytes);
        assertEquals(0, statsValue.txErrors);
    }

    // on S/Sv2 without a new enough DnsResolver apex, NetBpfLoad does not
    // get triggered, and thus no mainline programs get loaded.
    private boolean isNetBpfLoadEnabled() {
        if (SdkLevel.isAtLeastT()) return true;
        if (!SdkLevel.isAtLeastS()) return false;

        File f = new File("/apex/com.android.resolv/NetBpfLoad-S.flag");
        return f.isFile();
    }

    /**
     * BPF offload IPv4 UDP tethering test. Verify that UDP tethered packets are offloaded by BPF.
     * Minimum test requirement:
     * 1. S+ device.
     * 2. Tethering config enables tethering BPF offload.
     * 3. Kernel supports IPv4 UDP BPF offload. See #isUdpOffloadSupportedByKernel.
     *
     * TODO: consider enabling the test even tethering config disables BPF offload. See b/238288883
     */
    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testTetherBpfOffloadUdpV4() throws Exception {
        assumeTrue("Tethering config disabled BPF offload", isTetherConfigBpfOffloadEnabled());
        assumeKernelSupportBpfOffloadUdpV4();
        assumeTrue("Mainline NetBpfLoad not available", isNetBpfLoadEnabled());

        runUdp4Test();
    }

    private ClatEgress4Value getClatEgress4Value(int clatIfaceIndex) throws Exception {
        // Command: dumpsys connectivity clatEgress4RawBpfMap
        final String[] args = new String[] {DUMPSYS_CLAT_RAWMAP_EGRESS4_ARG};
        final HashMap<ClatEgress4Key, ClatEgress4Value> egress4Map = pollRawMapFromDump(
                ClatEgress4Key.class, ClatEgress4Value.class, Context.CONNECTIVITY_SERVICE, args);
        assertNotNull(egress4Map);
        for (Map.Entry<ClatEgress4Key, ClatEgress4Value> entry : egress4Map.entrySet()) {
            ClatEgress4Key key = entry.getKey();
            if (key.iif == clatIfaceIndex) {
                return entry.getValue();
            }
        }
        return null;
    }

    private ClatIngress6Value getClatIngress6Value(int ifaceIndex) throws Exception {
        // Command: dumpsys connectivity clatIngress6RawBpfMap
        final String[] args = new String[] {DUMPSYS_CLAT_RAWMAP_INGRESS6_ARG};
        final HashMap<ClatIngress6Key, ClatIngress6Value> ingress6Map = pollRawMapFromDump(
                ClatIngress6Key.class, ClatIngress6Value.class, Context.CONNECTIVITY_SERVICE, args);
        assertNotNull(ingress6Map);
        for (Map.Entry<ClatIngress6Key, ClatIngress6Value> entry : ingress6Map.entrySet()) {
            ClatIngress6Key key = entry.getKey();
            if (key.iif == ifaceIndex) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Test network topology:
     *
     *            public network (rawip)                 private network
     *                      |         UE (CLAT support)         |
     * +---------------+    V    +------------+------------+    V    +------------+
     * | NAT64 Gateway +---------+  Upstream  | Downstream +---------+   Client   |
     * +---------------+         +------------+------------+         +------------+
     * remote ip                 public ip                           private ip
     * [64:ff9b::808:808]:443    [clat ipv6]:9876                    [TetheredDevice ipv4]:9876
     *
     * Note that CLAT IPv6 address is generated by ClatCoordinator. Get the CLAT IPv6 address by
     * sending out an IPv4 packet and extracting the source address from CLAT translated IPv6
     * packet.
     */
    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testTetherClatBpfOffloadUdp() throws Exception {
        assumeKernelSupportBpfOffloadUdpV4();

        // CLAT only starts on IPv6 only network.
        final TetheringTester tester = initTetheringTester(toList(TEST_IP6_ADDR),
                toList(TEST_IP6_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, true /* hasIpv6 */);

        // Get CLAT IPv6 address.
        final Inet6Address clatIp6 = getClatIpv6Address(tester, tethered);

        // Get current values before sending packets.
        final String ifaceName = getUpstreamInterfaceName();
        final int ifaceIndex = getIndexByName(ifaceName);
        final int clatIfaceIndex = getIndexByName("v4-" + ifaceName);
        final ClatEgress4Value oldEgress4 = getClatEgress4Value(clatIfaceIndex);
        final ClatIngress6Value oldIngress6 = getClatIngress6Value(ifaceIndex);
        assertNotNull(oldEgress4);
        assertNotNull(oldIngress6);

        // Send an IPv4 UDP packet in original direction.
        // IPv4 packet -- CLAT translation --> IPv6 packet
        for (int i = 0; i < TX_UDP_PACKET_COUNT; i++) {
            sendUploadPacketUdp(tethered.macAddr, tethered.routerMacAddr, tethered.ipv4Addr,
                    REMOTE_IP4_ADDR, tester, true /* is4To6 */);
        }

        // Send an IPv6 UDP packet in reply direction.
        // IPv6 packet -- CLAT translation --> IPv4 packet
        for (int i = 0; i < RX_UDP_PACKET_COUNT; i++) {
            sendDownloadPacketUdp(REMOTE_NAT64_ADDR, clatIp6, tester, true /* is6To4 */);
        }

        // Send fragmented IPv6 UDP packets in the reply direction.
        // IPv6 frament packet -- CLAT translation --> IPv4 fragment packet
        final int payloadLen = 1500;
        final int l2mtu = 1000;
        final int fragPktCnt = 2; // 1500 bytes of UDP payload were fragmented into two packets.
        final long fragRxBytes = payloadLen + UDP_HEADER_LEN + fragPktCnt * IPV4_HEADER_MIN_LEN;
        final byte[] payload = new byte[payloadLen];
        // Initialize the payload with random bytes.
        Random random = new Random();
        random.nextBytes(payload);
        sendDownloadFragmentedUdpPackets(REMOTE_NAT64_ADDR, clatIp6, tester,
                ByteBuffer.wrap(payload), l2mtu);

        // After sending test packets, get stats again to verify their differences.
        final ClatEgress4Value newEgress4 = getClatEgress4Value(clatIfaceIndex);
        final ClatIngress6Value newIngress6 = getClatIngress6Value(ifaceIndex);
        assertNotNull(newEgress4);
        assertNotNull(newIngress6);

        assertEquals(RX_UDP_PACKET_COUNT + fragPktCnt, newIngress6.packets - oldIngress6.packets);
        assertEquals(RX_UDP_PACKET_COUNT * RX_UDP_PACKET_SIZE + fragRxBytes,
                newIngress6.bytes - oldIngress6.bytes);
        assertEquals(TX_UDP_PACKET_COUNT, newEgress4.packets - oldEgress4.packets);
        // The increase in egress traffic equals the expected size of the translated UDP packets.
        // Calculation:
        // - Original UDP packet was TX_UDP_PACKET_SIZE bytes (IPv4 header + UDP header + payload).
        // - After CLAT translation, each packet is now:
        //     IPv6 header + unchanged UDP header + unchanged payload
        // Therefore, the total size of the translated UDP packet should be:
        //     TX_UDP_PACKET_SIZE + IPV6_HEADER_LEN - IPV4_HEADER_MIN_LEN
        assertEquals(
                TX_UDP_PACKET_COUNT * (TX_UDP_PACKET_SIZE + IPV6_HEADER_LEN - IPV4_HEADER_MIN_LEN),
                newEgress4.bytes - oldEgress4.bytes);
    }

    @Test
    public void testTetheringVirtual() throws Exception {
        assumeFalse(isInterfaceForTetheringAvailable());
        setIncludeTestInterfaces(true);

        TestNetworkInterface downstreamIface = null;
        MyTetheringEventCallback tetheringEventCallback = null;
        PollPacketReader downstreamReader = null;
        try {
            downstreamIface = createTestInterface();
            String iface = downstreamIface.getInterfaceName();
            final TetheringRequest request = new TetheringRequest.Builder(TETHERING_VIRTUAL)
                    .setConnectivityScope(CONNECTIVITY_SCOPE_GLOBAL)
                    .setInterfaceName(iface)
                    .build();
            tetheringEventCallback = enableTethering(iface, request, null /* any upstream */);

            FileDescriptor fd = downstreamIface.getFileDescriptor().getFileDescriptor();
            downstreamReader = makePacketReader(fd, getMTU(downstreamIface));
            checkTetheredClientCallbacks(
                    downstreamReader, TETHERING_VIRTUAL, tetheringEventCallback);
        } finally {
            maybeStopTapPacketReader(downstreamReader);
            maybeCloseTestInterface(downstreamIface);
            maybeUnregisterTetheringEventCallback(tetheringEventCallback);
        }
    }

    private void assumeMatchNonThreadLocalNetworksEnabled() {
        assumeTrue(runAsShell(READ_COMPAT_CHANGE_CONFIG, LOG_COMPAT_CHANGE,
                () -> CompatChanges.isChangeEnabled(ENABLE_MATCH_NON_THREAD_LOCAL_NETWORKS)));
    }

    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    public void testTetheringLocalAgent_networkCallbacks() throws Exception {
        assumeMatchNonThreadLocalNetworksEnabled();
        mDeviceConfigRule.setConfig(NAMESPACE_TETHERING, TETHERING_LOCAL_NETWORK_AGENT, "1");
        doTestLocalAgent_networkCallbacks(CONNECTIVITY_SCOPE_GLOBAL);
    }

    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    public void testWifiP2pGroupOwnerLocalAgent_networkCallbacks() throws Exception {
        assumeMatchNonThreadLocalNetworksEnabled();
        mDeviceConfigRule.setConfig(NAMESPACE_TETHERING, TETHERING_LOCAL_NETWORK_AGENT, "1");
        mDeviceConfigRule.setConfig(NAMESPACE_TETHERING, WIFIP2PGO_LOCAL_NETWORK_AGENT, "1");
        doTestLocalAgent_networkCallbacks(CONNECTIVITY_SCOPE_LOCAL);
    }

    private void doTestLocalAgent_networkCallbacks(int scope) throws Exception {
        assumeFalse(isInterfaceForTetheringAvailable());
        setIncludeTestInterfaces(true);

        TestNetworkInterface downstreamIface = null;
        MyTetheringEventCallback tetheringEventCallback = null;

        final TestableNetworkCallback networkCallback = new TestableNetworkCallback();
        final NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK).build();
        mNetworkCallbackRule.registerNetworkCallback(networkRequest, networkCallback);

        try {
            downstreamIface = createTestInterface();

            final String iface = mTetheredInterfaceRequester.getInterface();
            assertEquals("TetheredInterfaceCallback for unexpected interface",
                    downstreamIface.getInterfaceName(), iface);

            final TetheringRequest request = new TetheringRequest.Builder(TETHERING_ETHERNET)
                    .setConnectivityScope(scope).build();
            tetheringEventCallback = enableTethering(iface, request, null /* any upstream */);
            if (scope == CONNECTIVITY_SCOPE_GLOBAL) {
                tetheringEventCallback.awaitInterfaceTethered();
            } else {
                tetheringEventCallback.awaitInterfaceLocalOnly();
            }

            // Verify NetworkCallback works accordingly.
            final Network network = networkCallback.expect(Event.AVAILABLE).getNetwork();
            final Event.CapabilitiesChanged capEvent =
                    networkCallback.eventuallyExpect(Event.NETWORK_CAPS_UPDATED);
            assertEquals(network, capEvent.getNetwork());
            assertTrue(capEvent.getCaps().hasTransport(TRANSPORT_ETHERNET));
            assertTrue(capEvent.getCaps().hasCapability(NET_CAPABILITY_LOCAL_NETWORK));

            // Verify details in the LinkPropertiesChanged event.
            final Event.LinkPropertiesChanged lpEvent =
                    networkCallback.eventuallyExpect(Event.LINK_PROPERTIES_CHANGED);
            assertEquals(network, lpEvent.getNetwork());
            assertEquals(iface, lpEvent.getLp().getInterfaceName());
            assertTrue(lpEvent.getLp().hasIPv4Address());
            // At least one Ipv4 route for the subnet is needed.
            assertTrue(lpEvent.getLp().getRoutes().size() >= 1);
        } finally {
            stopEthernetTethering(tetheringEventCallback);
            maybeCloseTestInterface(downstreamIface);
        }
    }
}
