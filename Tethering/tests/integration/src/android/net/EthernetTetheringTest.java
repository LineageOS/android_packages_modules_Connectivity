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

import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.content.pm.PackageManager.FEATURE_WIFI;
import static android.net.InetAddresses.parseNumericAddress;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringTester.TestDnsPacket;
import static android.net.TetheringTester.isExpectedIcmpPacket;
import static android.net.TetheringTester.isExpectedTcpPacket;
import static android.net.TetheringTester.isExpectedUdpDnsPacket;
import static android.net.TetheringTester.isExpectedUdpPacket;
import static android.system.OsConstants.ICMP_ECHO;
import static android.system.OsConstants.ICMP_ECHOREPLY;
import static android.system.OsConstants.IPPROTO_ICMP;
import static android.system.OsConstants.IPPROTO_IP;
import static android.system.OsConstants.IPPROTO_IPV6;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

import static com.android.net.module.util.ConnectivityUtils.isIPv6ULA;
import static com.android.net.module.util.HexDump.dumpHexString;
import static com.android.net.module.util.IpUtils.icmpChecksum;
import static com.android.net.module.util.IpUtils.ipChecksum;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV4;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV6;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REQUEST_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;
import static com.android.net.module.util.NetworkStackConstants.ICMP_CHECKSUM_OFFSET;
import static com.android.net.module.util.NetworkStackConstants.IPV4_CHECKSUM_OFFSET;
import static com.android.net.module.util.NetworkStackConstants.IPV4_HEADER_MIN_LEN;
import static com.android.net.module.util.NetworkStackConstants.IPV4_LENGTH_OFFSET;
import static com.android.net.module.util.NetworkStackConstants.TCPHDR_ACK;
import static com.android.net.module.util.NetworkStackConstants.TCPHDR_SYN;
import static com.android.testutils.DeviceInfoUtils.KVersion;
import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.EthernetManager.TetheredInterfaceCallback;
import android.net.EthernetManager.TetheredInterfaceRequest;
import android.net.TetheringManager.StartTetheringCallback;
import android.net.TetheringManager.TetheringEventCallback;
import android.net.TetheringManager.TetheringRequest;
import android.net.TetheringTester.TetheredDevice;
import android.net.cts.util.CtsNetUtils;;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.VintfRuntimeInfo;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfDump;
import com.android.net.module.util.Ipv6Utils;
import com.android.net.module.util.PacketBuilder;
import com.android.net.module.util.Struct;
import com.android.net.module.util.bpf.Tether4Key;
import com.android.net.module.util.bpf.Tether4Value;
import com.android.net.module.util.bpf.TetherStatsKey;
import com.android.net.module.util.bpf.TetherStatsValue;
import com.android.net.module.util.structs.EthernetHeader;
import com.android.net.module.util.structs.Icmpv4Header;
import com.android.net.module.util.structs.Ipv4Header;
import com.android.net.module.util.structs.Ipv6Header;
import com.android.net.module.util.structs.UdpHeader;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DeviceInfoUtils;
import com.android.testutils.DumpTestUtils;
import com.android.testutils.HandlerUtils;
import com.android.testutils.TapPacketReader;
import com.android.testutils.TestNetworkTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class EthernetTetheringTest {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private static final String TAG = EthernetTetheringTest.class.getSimpleName();
    private static final int TIMEOUT_MS = 5000;
    // Used to check if any tethering interface is available. Choose 200ms to be request timeout
    // because the average interface requested time on cuttlefish@acloud is around 10ms.
    // See TetheredInterfaceRequester.getInterface, isInterfaceForTetheringAvailable.
    private static final int AVAILABLE_TETHER_IFACE_REQUEST_TIMEOUT_MS = 200;
    private static final int TETHER_REACHABILITY_ATTEMPTS = 20;
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
    // Per TX UDP packet size: ethhdr (14) + iphdr (20) + udphdr (8) + payload (2) = 44 bytes.
    private static final int TX_UDP_PACKET_SIZE = 44;
    private static final int TX_UDP_PACKET_COUNT = 123;
    private static final long WAIT_RA_TIMEOUT_MS = 2000;

    private static final MacAddress TEST_MAC = MacAddress.fromString("1:2:3:4:5:6");
    private static final LinkAddress TEST_IP4_ADDR = new LinkAddress("10.0.0.1/24");
    private static final LinkAddress TEST_IP6_ADDR = new LinkAddress("2001:db8:1::101/64");
    private static final InetAddress TEST_IP4_DNS = parseNumericAddress("8.8.8.8");
    private static final InetAddress TEST_IP6_DNS = parseNumericAddress("2001:db8:1::888");
    private static final IpPrefix TEST_NAT64PREFIX = new IpPrefix("64:ff9b::/96");
    private static final Inet6Address REMOTE_NAT64_ADDR =
            (Inet6Address) parseNumericAddress("64:ff9b::808:808");
    private static final Inet6Address REMOTE_IP6_ADDR =
            (Inet6Address) parseNumericAddress("2002:db8:1::515:ca");
    private static final ByteBuffer TEST_REACHABILITY_PAYLOAD =
            ByteBuffer.wrap(new byte[] { (byte) 0x55, (byte) 0xaa });
    private static final ByteBuffer EMPTY_PAYLOAD = ByteBuffer.wrap(new byte[0]);

    private static final short DNS_PORT = 53;
    private static final short WINDOW = (short) 0x2000;
    private static final short URGENT_POINTER = 0;

    private static final String DUMPSYS_TETHERING_RAWMAP_ARG = "bpfRawMap";
    private static final String DUMPSYS_RAWMAP_ARG_STATS = "--stats";
    private static final String DUMPSYS_RAWMAP_ARG_UPSTREAM4 = "--upstream4";
    private static final String LINE_DELIMITER = "\\n";

    // version=6, traffic class=0x0, flowlabel=0x0;
    private static final int VERSION_TRAFFICCLASS_FLOWLABEL = 0x60000000;
    private static final short HOP_LIMIT = 0x40;

    private static final short ICMPECHO_CODE = 0x0;
    private static final short ICMPECHO_ID = 0x0;
    private static final short ICMPECHO_SEQ = 0x0;

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

    private final Context mContext = InstrumentationRegistry.getContext();
    private final EthernetManager mEm = mContext.getSystemService(EthernetManager.class);
    private final TetheringManager mTm = mContext.getSystemService(TetheringManager.class);
    private final PackageManager mPackageManager = mContext.getPackageManager();
    private final CtsNetUtils mCtsNetUtils = new CtsNetUtils(mContext);

    private TestNetworkInterface mDownstreamIface;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TapPacketReader mDownstreamReader;
    private TapPacketReader mUpstreamReader;

    private TetheredInterfaceRequester mTetheredInterfaceRequester;
    private MyTetheringEventCallback mTetheringEventCallback;

    private UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
    private boolean mRunTests;

    private TestNetworkTracker mUpstreamTracker;

    @Before
    public void setUp() throws Exception {
        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mRunTests = runAsShell(NETWORK_SETTINGS, TETHER_PRIVILEGED, () ->
                mTm.isTetheringSupported());
        assumeTrue(mRunTests);

        mTetheredInterfaceRequester = new TetheredInterfaceRequester(mHandler, mEm);
    }

    private void cleanUp() throws Exception {
        setPreferTestNetworks(false);

        if (mUpstreamTracker != null) {
            runAsShell(MANAGE_TEST_NETWORKS, () -> {
                mUpstreamTracker.teardown();
                mUpstreamTracker = null;
            });
        }
        if (mUpstreamReader != null) {
            TapPacketReader reader = mUpstreamReader;
            mHandler.post(() -> reader.stop());
            mUpstreamReader = null;
        }

        if (mDownstreamReader != null) {
            TapPacketReader reader = mDownstreamReader;
            mHandler.post(() -> reader.stop());
            mDownstreamReader = null;
        }

        // To avoid flaky which caused by the next test started but the previous interface is not
        // untracked from EthernetTracker yet. Just delete the test interface without explicitly
        // calling TetheringManager#stopTethering could let EthernetTracker untrack the test
        // interface from server mode before tethering stopped. Thus, awaitInterfaceUntethered
        // could not only make sure tethering is stopped but also guarantee the test interface is
        // untracked from EthernetTracker.
        maybeDeleteTestInterface();
        if (mTetheringEventCallback != null) {
            mTetheringEventCallback.awaitInterfaceUntethered();
            mTetheringEventCallback.unregister();
            mTetheringEventCallback = null;
        }
        runAsShell(NETWORK_SETTINGS, () -> mTetheredInterfaceRequester.release());
        setIncludeTestInterfaces(false);
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (mRunTests) cleanUp();
        } finally {
            mHandlerThread.quitSafely();
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    private boolean isInterfaceForTetheringAvailable() throws Exception {
        // Before T, all ethernet interfaces could be used for server mode. Instead of
        // waiting timeout, just checking whether the system currently has any
        // ethernet interface is more reliable.
        if (!SdkLevel.isAtLeastT()) {
            return runAsShell(CONNECTIVITY_USE_RESTRICTED_NETWORKS, () -> mEm.isAvailable());
        }

        // If previous test case doesn't release tethering interface successfully, the other tests
        // after that test may be skipped as unexcepted.
        // TODO: figure out a better way to check default tethering interface existenion.
        final TetheredInterfaceRequester requester = new TetheredInterfaceRequester(mHandler, mEm);
        try {
            // Use short timeout (200ms) for requesting an existing interface, if any, because
            // it should reurn faster than requesting a new tethering interface. Using default
            // timeout (5000ms, TIMEOUT_MS) may make that total testing time is over 1 minute
            // test module timeout on internal testing.
            // TODO: if this becomes flaky, consider using default timeout (5000ms) and moving
            // this check into #setUpOnce.
            return requester.getInterface(AVAILABLE_TETHER_IFACE_REQUEST_TIMEOUT_MS) != null;
        } catch (TimeoutException e) {
            return false;
        } finally {
            runAsShell(NETWORK_SETTINGS, () -> {
                requester.release();
            });
        }
    }

    private void setIncludeTestInterfaces(boolean include) {
        runAsShell(NETWORK_SETTINGS, () -> {
            mEm.setIncludeTestInterfaces(include);
        });
    }

    private void setPreferTestNetworks(boolean prefer) {
        runAsShell(NETWORK_SETTINGS, () -> {
            mTm.setPreferTestNetworks(prefer);
        });
    }

    @Test
    public void testVirtualEthernetAlreadyExists() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(isInterfaceForTetheringAvailable());

        mDownstreamIface = createTestInterface();
        // This must be done now because as soon as setIncludeTestInterfaces(true) is called, the
        // interface will be placed in client mode, which will delete the link-local address.
        // At that point NetworkInterface.getByName() will cease to work on the interface, because
        // starting in R NetworkInterface can no longer see interfaces without IP addresses.
        int mtu = getMTU(mDownstreamIface);

        Log.d(TAG, "Including test interfaces");
        setIncludeTestInterfaces(true);

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        checkVirtualEthernet(mDownstreamIface, mtu);
    }

    @Test
    public void testVirtualEthernet() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(isInterfaceForTetheringAvailable());

        CompletableFuture<String> futureIface = mTetheredInterfaceRequester.requestInterface();

        setIncludeTestInterfaces(true);

        mDownstreamIface = createTestInterface();

        final String iface = futureIface.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        checkVirtualEthernet(mDownstreamIface, getMTU(mDownstreamIface));
    }

    @Test
    public void testStaticIpv4() throws Exception {
        assumeFalse(isInterfaceForTetheringAvailable());

        setIncludeTestInterfaces(true);

        mDownstreamIface = createTestInterface();

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        assertInvalidStaticIpv4Request(iface, null, null);
        assertInvalidStaticIpv4Request(iface, "2001:db8::1/64", "2001:db8:2::/64");
        assertInvalidStaticIpv4Request(iface, "192.0.2.2/28", "2001:db8:2::/28");
        assertInvalidStaticIpv4Request(iface, "2001:db8:2::/28", "192.0.2.2/28");
        assertInvalidStaticIpv4Request(iface, "192.0.2.2/28", null);
        assertInvalidStaticIpv4Request(iface, null, "192.0.2.2/28");
        assertInvalidStaticIpv4Request(iface, "192.0.2.3/27", "192.0.2.2/28");

        final String localAddr = "192.0.2.3/28";
        final String clientAddr = "192.0.2.2/28";
        mTetheringEventCallback = enableEthernetTethering(iface,
                requestWithStaticIpv4(localAddr, clientAddr), null /* any upstream */);

        mTetheringEventCallback.awaitInterfaceTethered();
        assertInterfaceHasIpAddress(iface, localAddr);

        byte[] client1 = MacAddress.fromString("1:2:3:4:5:6").toByteArray();
        byte[] client2 = MacAddress.fromString("a:b:c:d:e:f").toByteArray();

        FileDescriptor fd = mDownstreamIface.getFileDescriptor().getFileDescriptor();
        mDownstreamReader = makePacketReader(fd, getMTU(mDownstreamIface));
        TetheringTester tester = new TetheringTester(mDownstreamReader);
        DhcpResults dhcpResults = tester.runDhcp(client1);
        assertEquals(new LinkAddress(clientAddr), dhcpResults.ipAddress);

        try {
            tester.runDhcp(client2);
            fail("Only one client should get an IP address");
        } catch (TimeoutException expected) { }

    }

    private static void waitForRouterAdvertisement(TapPacketReader reader, String iface,
            long timeoutMs) {
        final long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            byte[] pkt = reader.popPacket(timeoutMs);
            if (isExpectedIcmpPacket(pkt, true /* hasEth */, false /* isIpv4 */,
                    ICMPV6_ROUTER_ADVERTISEMENT)) {
                return;
            }

            timeoutMs = deadline - SystemClock.uptimeMillis();
        } while (timeoutMs > 0);
        fail("Did not receive router advertisement on " + iface + " after "
                +  timeoutMs + "ms idle");
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

        mDownstreamIface = createTestInterface();

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_ETHERNET)
                .setConnectivityScope(CONNECTIVITY_SCOPE_LOCAL).build();
        mTetheringEventCallback = enableEthernetTethering(iface, request,
                null /* any upstream */);
        mTetheringEventCallback.awaitInterfaceLocalOnly();

        // makePacketReader only works after tethering is started, because until then the interface
        // does not have an IP address, and unprivileged apps cannot see interfaces without IP
        // addresses. This shouldn't be flaky because the TAP interface will buffer all packets even
        // before the reader is started.
        mDownstreamReader = makePacketReader(mDownstreamIface);

        waitForRouterAdvertisement(mDownstreamReader, iface, WAIT_RA_TIMEOUT_MS);
        expectLocalOnlyAddresses(iface);
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

        // Get an interface to use.
        final String iface = mTetheredInterfaceRequester.getInterface();

        // Enable Ethernet tethering and check that it starts.
        mTetheringEventCallback = enableEthernetTethering(iface, null /* any upstream */);

        // There is nothing more we can do on a physical interface without connecting an actual
        // client, which is not possible in this test.
    }

    private boolean isEthernetTetheringSupported() throws Exception {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final TetheringEventCallback callback = new TetheringEventCallback() {
            @Override
            public void onSupportedTetheringTypes(Set<Integer> supportedTypes) {
                future.complete(supportedTypes.contains(TETHERING_ETHERNET));
            }
        };

        try {
            mTm.registerTetheringEventCallback(mHandler::post, callback);
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            mTm.unregisterTetheringEventCallback(callback);
        }
    }

    private static final class MyTetheringEventCallback implements TetheringEventCallback {
        private final TetheringManager mTm;
        private final CountDownLatch mTetheringStartedLatch = new CountDownLatch(1);
        private final CountDownLatch mTetheringStoppedLatch = new CountDownLatch(1);
        private final CountDownLatch mLocalOnlyStartedLatch = new CountDownLatch(1);
        private final CountDownLatch mLocalOnlyStoppedLatch = new CountDownLatch(1);
        private final CountDownLatch mClientConnectedLatch = new CountDownLatch(1);
        private final CountDownLatch mUpstreamLatch = new CountDownLatch(1);
        private final CountDownLatch mCallbackRegisteredLatch = new CountDownLatch(1);
        private final TetheringInterface mIface;
        private final Network mExpectedUpstream;

        private boolean mAcceptAnyUpstream = false;

        private volatile boolean mInterfaceWasTethered = false;
        private volatile boolean mInterfaceWasLocalOnly = false;
        private volatile boolean mUnregistered = false;
        private volatile Collection<TetheredClient> mClients = null;
        private volatile Network mUpstream = null;

        MyTetheringEventCallback(TetheringManager tm, String iface) {
            this(tm, iface, null);
            mAcceptAnyUpstream = true;
        }

        MyTetheringEventCallback(TetheringManager tm, String iface, Network expectedUpstream) {
            mTm = tm;
            mIface = new TetheringInterface(TETHERING_ETHERNET, iface);
            mExpectedUpstream = expectedUpstream;
        }

        public void unregister() {
            mTm.unregisterTetheringEventCallback(this);
            mUnregistered = true;
        }
        @Override
        public void onTetheredInterfacesChanged(List<String> interfaces) {
            fail("Should only call callback that takes a Set<TetheringInterface>");
        }

        @Override
        public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            if (!mInterfaceWasTethered && interfaces.contains(mIface)) {
                // This interface is being tethered for the first time.
                Log.d(TAG, "Tethering started: " + interfaces);
                mInterfaceWasTethered = true;
                mTetheringStartedLatch.countDown();
            } else if (mInterfaceWasTethered && !interfaces.contains(mIface)) {
                Log.d(TAG, "Tethering stopped: " + interfaces);
                mTetheringStoppedLatch.countDown();
            }
        }

        @Override
        public void onLocalOnlyInterfacesChanged(List<String> interfaces) {
            fail("Should only call callback that takes a Set<TetheringInterface>");
        }

        @Override
        public void onLocalOnlyInterfacesChanged(Set<TetheringInterface> interfaces) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            if (!mInterfaceWasLocalOnly && interfaces.contains(mIface)) {
                // This interface is being put into local-only mode for the first time.
                Log.d(TAG, "Local-only started: " + interfaces);
                mInterfaceWasLocalOnly = true;
                mLocalOnlyStartedLatch.countDown();
            } else if (mInterfaceWasLocalOnly && !interfaces.contains(mIface)) {
                Log.d(TAG, "Local-only stopped: " + interfaces);
                mLocalOnlyStoppedLatch.countDown();
            }
        }

        public void awaitInterfaceTethered() throws Exception {
            assertTrue("Ethernet not tethered after " + TIMEOUT_MS + "ms",
                    mTetheringStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        public void awaitInterfaceLocalOnly() throws Exception {
            assertTrue("Ethernet not local-only after " + TIMEOUT_MS + "ms",
                    mLocalOnlyStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        // Used to check if the callback has registered. When the callback is registered,
        // onSupportedTetheringTypes is celled in onCallbackStarted(). After
        // onSupportedTetheringTypes called, drop the permission for registering callback.
        // See MyTetheringEventCallback#register, TetheringManager#onCallbackStarted.
        @Override
        public void onSupportedTetheringTypes(Set<Integer> supportedTypes) {
            // Used to check callback registered.
            mCallbackRegisteredLatch.countDown();
        }

        public void awaitCallbackRegistered() throws Exception {
            if (!mCallbackRegisteredLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                fail("Did not receive callback registered signal after " + TIMEOUT_MS + "ms");
            }
        }

        public void awaitInterfaceUntethered() throws Exception {
            // Don't block teardown if the interface was never tethered.
            // This is racy because the interface might become tethered right after this check, but
            // that can only happen in tearDown if startTethering timed out, which likely means
            // the test has already failed.
            if (!mInterfaceWasTethered && !mInterfaceWasLocalOnly) return;

            if (mInterfaceWasTethered) {
                assertTrue(mIface + " not untethered after " + TIMEOUT_MS + "ms",
                        mTetheringStoppedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } else if (mInterfaceWasLocalOnly) {
                assertTrue(mIface + " not untethered after " + TIMEOUT_MS + "ms",
                        mLocalOnlyStoppedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } else {
                fail(mIface + " cannot be both tethered and local-only. Update this test class.");
            }
        }

        @Override
        public void onError(String ifName, int error) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            fail("TetheringEventCallback got error:" + error + " on iface " + ifName);
        }

        @Override
        public void onClientsChanged(Collection<TetheredClient> clients) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            Log.d(TAG, "Got clients changed: " + clients);
            mClients = clients;
            if (clients.size() > 0) {
                mClientConnectedLatch.countDown();
            }
        }

        public Collection<TetheredClient> awaitClientConnected() throws Exception {
            assertTrue("Did not receive client connected callback after " + TIMEOUT_MS + "ms",
                    mClientConnectedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            return mClients;
        }

        @Override
        public void onUpstreamChanged(Network network) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            Log.d(TAG, "Got upstream changed: " + network);
            mUpstream = network;
            if (mAcceptAnyUpstream || Objects.equals(mUpstream, mExpectedUpstream)) {
                mUpstreamLatch.countDown();
            }
        }

        public Network awaitUpstreamChanged(boolean throwTimeoutException) throws Exception {
            if (!mUpstreamLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                final String errorMessage = "Did not receive upstream "
                            + (mAcceptAnyUpstream ? "any" : mExpectedUpstream)
                            + " callback after " + TIMEOUT_MS + "ms";

                if (throwTimeoutException) {
                    throw new TimeoutException(errorMessage);
                } else {
                    fail(errorMessage);
                }
            }
            return mUpstream;
        }
    }

    private MyTetheringEventCallback enableEthernetTethering(String iface,
            TetheringRequest request, Network expectedUpstream) throws Exception {
        // Enable ethernet tethering with null expectedUpstream means the test accept any upstream
        // after etherent tethering started.
        final MyTetheringEventCallback callback;
        if (expectedUpstream != null) {
            callback = new MyTetheringEventCallback(mTm, iface, expectedUpstream);
        } else {
            callback = new MyTetheringEventCallback(mTm, iface);
        }
        runAsShell(NETWORK_SETTINGS, () -> {
            mTm.registerTetheringEventCallback(mHandler::post, callback);
            // Need to hold the shell permission until callback is registered. This helps to avoid
            // the test become flaky.
            callback.awaitCallbackRegistered();
        });
        final CountDownLatch tetheringStartedLatch = new CountDownLatch(1);
        StartTetheringCallback startTetheringCallback = new StartTetheringCallback() {
            @Override
            public void onTetheringStarted() {
                Log.d(TAG, "Ethernet tethering started");
                tetheringStartedLatch.countDown();
            }

            @Override
            public void onTetheringFailed(int resultCode) {
                fail("Unexpectedly got onTetheringFailed");
            }
        };
        Log.d(TAG, "Starting Ethernet tethering");
        runAsShell(TETHER_PRIVILEGED, () -> {
            mTm.startTethering(request, mHandler::post /* executor */, startTetheringCallback);
            // Binder call is an async call. Need to hold the shell permission until tethering
            // started. This helps to avoid the test become flaky.
            if (!tetheringStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                fail("Did not receive tethering started callback after " + TIMEOUT_MS + "ms");
            }
        });

        final int connectivityType = request.getConnectivityScope();
        switch (connectivityType) {
            case CONNECTIVITY_SCOPE_GLOBAL:
                callback.awaitInterfaceTethered();
                break;
            case CONNECTIVITY_SCOPE_LOCAL:
                callback.awaitInterfaceLocalOnly();
                break;
            default:
                fail("Unexpected connectivity type requested: " + connectivityType);
        }

        return callback;
    }

    private MyTetheringEventCallback enableEthernetTethering(String iface, Network expectedUpstream)
            throws Exception {
        return enableEthernetTethering(iface,
                new TetheringRequest.Builder(TETHERING_ETHERNET)
                .setShouldShowEntitlementUi(false).build(), expectedUpstream);
    }

    private int getMTU(TestNetworkInterface iface) throws SocketException {
        NetworkInterface nif = NetworkInterface.getByName(iface.getInterfaceName());
        assertNotNull("Can't get NetworkInterface object for " + iface.getInterfaceName(), nif);
        return nif.getMTU();
    }

    private TapPacketReader makePacketReader(final TestNetworkInterface iface) throws Exception {
        FileDescriptor fd = iface.getFileDescriptor().getFileDescriptor();
        return makePacketReader(fd, getMTU(iface));
    }

    private TapPacketReader makePacketReader(FileDescriptor fd, int mtu) {
        final TapPacketReader reader = new TapPacketReader(mHandler, fd, mtu);
        mHandler.post(() -> reader.start());
        HandlerUtils.waitForIdle(mHandler, TIMEOUT_MS);
        return reader;
    }

    private void checkVirtualEthernet(TestNetworkInterface iface, int mtu) throws Exception {
        FileDescriptor fd = iface.getFileDescriptor().getFileDescriptor();
        mDownstreamReader = makePacketReader(fd, mtu);
        mTetheringEventCallback = enableEthernetTethering(iface.getInterfaceName(),
                null /* any upstream */);
        checkTetheredClientCallbacks(mDownstreamReader);
    }

    private void checkTetheredClientCallbacks(TapPacketReader packetReader) throws Exception {
        // Create a fake client.
        byte[] clientMacAddr = new byte[6];
        new Random().nextBytes(clientMacAddr);

        TetheringTester tester = new TetheringTester(packetReader);
        DhcpResults dhcpResults = tester.runDhcp(clientMacAddr);

        final Collection<TetheredClient> clients = mTetheringEventCallback.awaitClientConnected();
        assertEquals(1, clients.size());
        final TetheredClient client = clients.iterator().next();

        // Check the MAC address.
        assertEquals(MacAddress.fromBytes(clientMacAddr), client.getMacAddress());
        assertEquals(TETHERING_ETHERNET, client.getTetheringType());

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

    private static final class TetheredInterfaceRequester implements TetheredInterfaceCallback {
        private final Handler mHandler;
        private final EthernetManager mEm;

        private TetheredInterfaceRequest mRequest;
        private final CompletableFuture<String> mFuture = new CompletableFuture<>();

        TetheredInterfaceRequester(Handler handler, EthernetManager em) {
            mHandler = handler;
            mEm = em;
        }

        @Override
        public void onAvailable(String iface) {
            Log.d(TAG, "Ethernet interface available: " + iface);
            mFuture.complete(iface);
        }

        @Override
        public void onUnavailable() {
            mFuture.completeExceptionally(new IllegalStateException("onUnavailable received"));
        }

        public CompletableFuture<String> requestInterface() {
            assertNull("BUG: more than one tethered interface request", mRequest);
            Log.d(TAG, "Requesting tethered interface");
            mRequest = runAsShell(NETWORK_SETTINGS, () ->
                    mEm.requestTetheredInterface(mHandler::post, this));
            return mFuture;
        }

        public String getInterface(int timeout) throws Exception {
            return requestInterface().get(timeout, TimeUnit.MILLISECONDS);
        }

        public String getInterface() throws Exception {
            return getInterface(TIMEOUT_MS);
        }

        public void release() {
            if (mRequest != null) {
                mFuture.obtrudeException(new IllegalStateException("Request already released"));
                mRequest.release();
                mRequest = null;
            }
        }
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
            enableEthernetTethering(iface, requestWithStaticIpv4(local, client),
                    null /* any upstream */);
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

    private TestNetworkInterface createTestInterface() throws Exception {
        TestNetworkManager tnm = runAsShell(MANAGE_TEST_NETWORKS, () ->
                mContext.getSystemService(TestNetworkManager.class));
        TestNetworkInterface iface = runAsShell(MANAGE_TEST_NETWORKS, () ->
                tnm.createTapInterface());
        Log.d(TAG, "Created test interface " + iface.getInterfaceName());
        return iface;
    }

    private void maybeDeleteTestInterface() throws Exception {
        if (mDownstreamIface != null) {
            mDownstreamIface.getFileDescriptor().close();
            Log.d(TAG, "Deleted test interface " + mDownstreamIface.getInterfaceName());
            mDownstreamIface = null;
        }
    }

    private TestNetworkTracker createTestUpstream(final List<LinkAddress> addresses,
            final List<InetAddress> dnses) throws Exception {
        setPreferTestNetworks(true);

        final LinkProperties lp = new LinkProperties();
        lp.setLinkAddresses(addresses);
        lp.setDnsServers(dnses);
        lp.setNat64Prefix(TEST_NAT64PREFIX);

        return runAsShell(MANAGE_TEST_NETWORKS, () -> initTestNetwork(mContext, lp, TIMEOUT_MS));
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
    private static final Inet4Address REMOTE_IP4_ADDR =
            (Inet4Address) parseNumericAddress("8.8.8.8");
    // Used by public port and private port. Assume port 9876 has not been used yet before the
    // testing that public port and private port are the same in the testing. Note that NAT port
    // forwarding could be different between private port and public port.
    // TODO: move to the start of test class.
    private static final short LOCAL_PORT = 9876;
    private static final short REMOTE_PORT = 433;
    private static final byte TYPE_OF_SERVICE = 0;
    private static final short ID = 27149;
    private static final short FLAGS_AND_FRAGMENT_OFFSET = (short) 0x4000; // flags=DF, offset=0
    private static final byte TIME_TO_LIVE = (byte) 0x40;
    private static final ByteBuffer RX_PAYLOAD =
            ByteBuffer.wrap(new byte[] { (byte) 0x12, (byte) 0x34 });
    private static final ByteBuffer TX_PAYLOAD =
            ByteBuffer.wrap(new byte[] { (byte) 0x56, (byte) 0x78 });

    private short getEthType(@NonNull final InetAddress srcIp, @NonNull final InetAddress dstIp) {
        return isAddressIpv4(srcIp, dstIp) ? (short) ETHER_TYPE_IPV4 : (short) ETHER_TYPE_IPV6;
    }

    private int getIpProto(@NonNull final InetAddress srcIp, @NonNull final InetAddress dstIp) {
        return isAddressIpv4(srcIp, dstIp) ? IPPROTO_IP : IPPROTO_IPV6;
    }

    @NonNull
    private ByteBuffer buildUdpPacket(
            @Nullable final MacAddress srcMac, @Nullable final MacAddress dstMac,
            @NonNull final InetAddress srcIp, @NonNull final InetAddress dstIp,
            short srcPort, short dstPort, @Nullable final ByteBuffer payload)
            throws Exception {
        final int ipProto = getIpProto(srcIp, dstIp);
        final boolean hasEther = (srcMac != null && dstMac != null);
        final int payloadLen = (payload == null) ? 0 : payload.limit();
        final ByteBuffer buffer = PacketBuilder.allocate(hasEther, ipProto, IPPROTO_UDP,
                payloadLen);
        final PacketBuilder packetBuilder = new PacketBuilder(buffer);

        // [1] Ethernet header
        if (hasEther) {
            packetBuilder.writeL2Header(srcMac, dstMac, getEthType(srcIp, dstIp));
        }

        // [2] IP header
        if (ipProto == IPPROTO_IP) {
            packetBuilder.writeIpv4Header(TYPE_OF_SERVICE, ID, FLAGS_AND_FRAGMENT_OFFSET,
                    TIME_TO_LIVE, (byte) IPPROTO_UDP, (Inet4Address) srcIp, (Inet4Address) dstIp);
        } else {
            packetBuilder.writeIpv6Header(VERSION_TRAFFICCLASS_FLOWLABEL, (byte) IPPROTO_UDP,
                    HOP_LIMIT, (Inet6Address) srcIp, (Inet6Address) dstIp);
        }

        // [3] UDP header
        packetBuilder.writeUdpHeader(srcPort, dstPort);

        // [4] Payload
        if (payload != null) {
            buffer.put(payload);
            // in case data might be reused by caller, restore the position and
            // limit of bytebuffer.
            payload.clear();
        }

        return packetBuilder.finalizePacket();
    }

    @NonNull
    private ByteBuffer buildUdpPacket(@NonNull final InetAddress srcIp,
            @NonNull final InetAddress dstIp, short srcPort, short dstPort,
            @Nullable final ByteBuffer payload) throws Exception {
        return buildUdpPacket(null /* srcMac */, null /* dstMac */, srcIp, dstIp, srcPort,
                dstPort, payload);
    }

    private boolean isAddressIpv4(@NonNull final  InetAddress srcIp,
            @NonNull final InetAddress dstIp) {
        if (srcIp instanceof Inet4Address && dstIp instanceof Inet4Address) return true;
        if (srcIp instanceof Inet6Address && dstIp instanceof Inet6Address) return false;

        fail("Unsupported conditions: srcIp " + srcIp + ", dstIp " + dstIp);
        return false;  // unreachable
    }

    private void sendDownloadPacketUdp(@NonNull final InetAddress srcIp,
            @NonNull final InetAddress dstIp, @NonNull final TetheringTester tester,
            boolean is6To4) throws Exception {
        if (is6To4) {
            assertFalse("CLAT download test must sends IPv6 packet", isAddressIpv4(srcIp, dstIp));
        }

        // Expected received UDP packet IP protocol. While testing CLAT (is6To4 = true), the packet
        // on downstream must be IPv4. Otherwise, the IP protocol of test packet is the same on
        // both downstream and upstream.
        final boolean isIpv4 = is6To4 ? true : isAddressIpv4(srcIp, dstIp);

        final ByteBuffer testPacket = buildUdpPacket(srcIp, dstIp, REMOTE_PORT /* srcPort */,
                LOCAL_PORT /* dstPort */, RX_PAYLOAD);
        tester.verifyDownload(testPacket, p -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));
            return isExpectedUdpPacket(p, true /* hasEther */, isIpv4, RX_PAYLOAD);
        });
    }

    private void sendUploadPacketUdp(@NonNull final MacAddress srcMac,
            @NonNull final MacAddress dstMac, @NonNull final InetAddress srcIp,
            @NonNull final InetAddress dstIp, @NonNull final TetheringTester tester,
            boolean is4To6) throws Exception {
        if (is4To6) {
            assertTrue("CLAT upload test must sends IPv4 packet", isAddressIpv4(srcIp, dstIp));
        }

        // Expected received UDP packet IP protocol. While testing CLAT (is4To6 = true), the packet
        // on upstream must be IPv6. Otherwise, the IP protocol of test packet is the same on
        // both downstream and upstream.
        final boolean isIpv4 = is4To6 ? false : isAddressIpv4(srcIp, dstIp);

        final ByteBuffer testPacket = buildUdpPacket(srcMac, dstMac, srcIp, dstIp,
                LOCAL_PORT /* srcPort */, REMOTE_PORT /* dstPort */, TX_PAYLOAD);
        tester.verifyUpload(testPacket, p -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));
            return isExpectedUdpPacket(p, false /* hasEther */, isIpv4, TX_PAYLOAD);
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

    // TODO: remove ipv4 verification (is4To6 = false) once upstream connected notification race is
    // fixed. See #runUdp4Test.
    //
    // This function sends a probe packet to downstream interface and exam the result from upstream
    // interface to make sure ipv4 tethering is ready. Return the entire packet which received from
    // upstream interface.
    @NonNull
    private byte[] probeV4TetheringConnectivity(TetheringTester tester, TetheredDevice tethered,
            boolean is4To6) throws Exception {
        final ByteBuffer probePacket = buildUdpPacket(tethered.macAddr,
                tethered.routerMacAddr, tethered.ipv4Addr /* srcIp */,
                REMOTE_IP4_ADDR /* dstIp */, LOCAL_PORT /* srcPort */, REMOTE_PORT /* dstPort */,
                TEST_REACHABILITY_PAYLOAD);

        // Send a UDP packet from client and check the packet can be found on upstream interface.
        for (int i = 0; i < TETHER_REACHABILITY_ATTEMPTS; i++) {
            byte[] expectedPacket = tester.testUpload(probePacket, p -> {
                Log.d(TAG, "Packet in upstream: " + dumpHexString(p));
                // If is4To6 is true, the ipv4 probe packet would be translated to ipv6 by Clat and
                // would see this translated ipv6 packet in upstream interface.
                return isExpectedUdpPacket(p, false /* hasEther */, !is4To6 /* isIpv4 */,
                        TEST_REACHABILITY_PAYLOAD);
            });
            if (expectedPacket != null) return expectedPacket;
        }

        fail("Can't verify " + (is4To6 ? "ipv4 to ipv6" : "ipv4") + " tethering connectivity after "
                + TETHER_REACHABILITY_ATTEMPTS + " attempts");
        return null;
    }

    private void runUdp4Test(boolean verifyBpf) throws Exception {
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

        if (verifyBpf) {
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
            final HashMap<Tether4Key, Tether4Value> upstreamMap = pollRawMapFromDump(
                    Tether4Key.class, Tether4Value.class, DUMPSYS_RAWMAP_ARG_UPSTREAM4);
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
            final HashMap<TetherStatsKey, TetherStatsValue> statsMap = pollRawMapFromDump(
                    TetherStatsKey.class, TetherStatsValue.class, DUMPSYS_RAWMAP_ARG_STATS);
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
    }

    // TODO: remove triggering upstream reselection once test network can replace selected upstream
    // network in Tethering module.
    private void maybeRetryTestedUpstreamChanged(final Network expectedUpstream,
            final TimeoutException fallbackException) throws Exception {
        // Fall back original exception because no way to reselect if there is no WIFI feature.
        assertTrue(fallbackException.toString(), mPackageManager.hasSystemFeature(FEATURE_WIFI));

        // Try to toggle wifi network, if any, to reselect upstream network via default network
        // switching. Because test network has higher priority than internet network, this can
        // help selecting test network to be upstream network for testing. This tries to avoid
        // the flaky upstream selection under multinetwork environment. Internet and test network
        // upstream changed event order is not guaranteed. Once tethering selects non-test
        // upstream {wifi, ..}, test network won't be selected anymore. If too many test cases
        // trigger the reselection, the total test time may over test suite 1 minmute timeout.
        // Probably need to disable/restore all internet networks in a common place of test
        // process. Currently, EthernetTetheringTest is part of CTS test which needs wifi network
        // connection if device has wifi feature. CtsNetUtils#toggleWifi() checks wifi connection
        // during the toggling process.
        // See Tethering#chooseUpstreamType, CtsNetUtils#toggleWifi.
        // TODO: toggle cellular network if the device has no WIFI feature.
        Log.d(TAG, "Toggle WIFI to retry upstream selection");
        mCtsNetUtils.toggleWifi();

        // Wait for expected upstream.
        final CompletableFuture<Network> future = new CompletableFuture<>();
        final TetheringEventCallback callback = new TetheringEventCallback() {
            @Override
            public void onUpstreamChanged(Network network) {
                Log.d(TAG, "Got upstream changed: " + network);
                if (Objects.equals(expectedUpstream, network)) {
                    future.complete(network);
                }
            }
        };
        try {
            mTm.registerTetheringEventCallback(mHandler::post, callback);
            assertEquals("onUpstreamChanged for unexpected network", expectedUpstream,
                    future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            throw new AssertionError("Did not receive upstream " + expectedUpstream
                    + " callback after " + TIMEOUT_MS + "ms");
        } finally {
            mTm.unregisterTetheringEventCallback(callback);
        }
    }

    private TetheringTester initTetheringTester(List<LinkAddress> upstreamAddresses,
            List<InetAddress> upstreamDnses) throws Exception {
        assumeFalse(isInterfaceForTetheringAvailable());

        // MyTetheringEventCallback currently only support await first available upstream. Tethering
        // may select internet network as upstream if test network is not available and not be
        // preferred yet. Create test upstream network before enable tethering.
        mUpstreamTracker = createTestUpstream(upstreamAddresses, upstreamDnses);

        mDownstreamIface = createTestInterface();
        setIncludeTestInterfaces(true);

        // Make sure EtherentTracker use "mDownstreamIface" as server mode interface.
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), mTetheredInterfaceRequester.getInterface());

        mTetheringEventCallback = enableEthernetTethering(mDownstreamIface.getInterfaceName(),
                mUpstreamTracker.getNetwork());

        try {
            assertEquals("onUpstreamChanged for test network", mUpstreamTracker.getNetwork(),
                    mTetheringEventCallback.awaitUpstreamChanged(
                            true /* throwTimeoutException */));
        } catch (TimeoutException e) {
            // Due to race condition inside tethering module, test network may not be selected as
            // tethering upstream. Force tethering retry upstream if possible. If it is not
            // possible to retry, fail the test with the original timeout exception.
            maybeRetryTestedUpstreamChanged(mUpstreamTracker.getNetwork(), e);
        }

        mDownstreamReader = makePacketReader(mDownstreamIface);
        mUpstreamReader = makePacketReader(mUpstreamTracker.getTestIface());

        final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        // Currently tethering don't have API to tell when ipv6 tethering is available. Thus, make
        // sure tethering already have ipv6 connectivity before testing.
        if (cm.getLinkProperties(mUpstreamTracker.getNetwork()).hasGlobalIpv6Address()) {
            waitForRouterAdvertisement(mDownstreamReader, mDownstreamIface.getInterfaceName(),
                    WAIT_RA_TIMEOUT_MS);
        }

        return new TetheringTester(mDownstreamReader, mUpstreamReader);
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

    @Test
    public void testTetherConfigBpfOffloadEnabled() throws Exception {
        assumeTrue(isTetherConfigBpfOffloadEnabled());
    }

    /**
     * Basic IPv4 UDP tethering test. Verify that UDP tethered packets are transferred no matter
     * using which data path.
     */
    @Test
    public void testTetherUdpV4() throws Exception {
        runUdp4Test(false /* verifyBpf */);
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
    public void testTetherUdpV4_VerifyBpf() throws Exception {
        assumeTrue("Tethering config disabled BPF offload", isTetherConfigBpfOffloadEnabled());
        assumeKernelSupportBpfOffloadUdpV4();

        runUdp4Test(true /* verifyBpf */);
    }

    @NonNull
    private <K extends Struct, V extends Struct> HashMap<K, V> dumpAndParseRawMap(
            Class<K> keyClass, Class<V> valueClass, @NonNull String mapArg)
            throws Exception {
        final String[] args = new String[] {DUMPSYS_TETHERING_RAWMAP_ARG, mapArg};
        final String rawMapStr = runAsShell(DUMP, () ->
                DumpTestUtils.dumpService(Context.TETHERING_SERVICE, args));
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
            Class<K> keyClass, Class<V> valueClass, @NonNull String mapArg)
            throws Exception {
        for (int retryCount = 0; retryCount < DUMP_POLLING_MAX_RETRY; retryCount++) {
            final HashMap<K, V> map = dumpAndParseRawMap(keyClass, valueClass, mapArg);
            if (!map.isEmpty()) return map;

            Thread.sleep(DUMP_POLLING_INTERVAL_MS);
        }

        fail("Cannot get rules after " + DUMP_POLLING_MAX_RETRY * DUMP_POLLING_INTERVAL_MS + "ms");
        return null;
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

    @NonNull
    private Inet6Address getClatIpv6Address(TetheringTester tester, TetheredDevice tethered)
            throws Exception {
        // Send an IPv4 UDP packet from client and check that a CLAT translated IPv6 UDP packet can
        // be found on upstream interface. Get CLAT IPv6 address from the CLAT translated IPv6 UDP
        // packet.
        byte[] expectedPacket = probeV4TetheringConnectivity(tester, tethered, true /* is4To6 */);

        // Above has guaranteed that the found packet is an IPv6 packet without ether header.
        return Struct.parse(Ipv6Header.class, ByteBuffer.wrap(expectedPacket)).srcIp;
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

    // PacketBuilder doesn't support IPv4 ICMP packet. It may need to refactor PacketBuilder first
    // because ICMP is a specific layer 3 protocol for PacketBuilder which expects packets always
    // have layer 3 (IP) and layer 4 (TCP, UDP) for now. Since we don't use IPv4 ICMP packet too
    // much in this test, we just write a ICMP packet builder here.
    // TODO: move ICMPv4 packet build function to common utilis.
    @NonNull
    private ByteBuffer buildIcmpEchoPacketV4(
            @Nullable final MacAddress srcMac, @Nullable final MacAddress dstMac,
            @NonNull final Inet4Address srcIp, @NonNull final Inet4Address dstIp,
            int type, short id, short seq) throws Exception {
        if (type != ICMP_ECHO && type != ICMP_ECHOREPLY) {
            fail("Unsupported ICMP type: " + type);
        }

        // Build ICMP echo id and seq fields as payload. Ignore the data field.
        final ByteBuffer payload = ByteBuffer.allocate(4);
        payload.putShort(id);
        payload.putShort(seq);
        payload.rewind();

        final boolean hasEther = (srcMac != null && dstMac != null);
        final int etherHeaderLen = hasEther ? Struct.getSize(EthernetHeader.class) : 0;
        final int ipv4HeaderLen = Struct.getSize(Ipv4Header.class);
        final int Icmpv4HeaderLen = Struct.getSize(Icmpv4Header.class);
        final int payloadLen = payload.limit();
        final ByteBuffer packet = ByteBuffer.allocate(etherHeaderLen + ipv4HeaderLen
                + Icmpv4HeaderLen + payloadLen);

        // [1] Ethernet header
        if (hasEther) {
            final EthernetHeader ethHeader = new EthernetHeader(dstMac, srcMac, ETHER_TYPE_IPV4);
            ethHeader.writeToByteBuffer(packet);
        }

        // [2] IP header
        final Ipv4Header ipv4Header = new Ipv4Header(TYPE_OF_SERVICE,
                (short) 0 /* totalLength, calculate later */, ID,
                FLAGS_AND_FRAGMENT_OFFSET, TIME_TO_LIVE, (byte) IPPROTO_ICMP,
                (short) 0 /* checksum, calculate later */, srcIp, dstIp);
        ipv4Header.writeToByteBuffer(packet);

        // [3] ICMP header
        final Icmpv4Header icmpv4Header = new Icmpv4Header((byte) type, ICMPECHO_CODE,
                (short) 0 /* checksum, calculate later */);
        icmpv4Header.writeToByteBuffer(packet);

        // [4] Payload
        packet.put(payload);
        packet.flip();

        // [5] Finalize packet
        // Used for updating IP header fields. If there is Ehternet header, IPv4 header offset
        // in buffer equals ethernet header length because IPv4 header is located next to ethernet
        // header. Otherwise, IPv4 header offset is 0.
        final int ipv4HeaderOffset = hasEther ? etherHeaderLen : 0;

        // Populate the IPv4 totalLength field.
        packet.putShort(ipv4HeaderOffset + IPV4_LENGTH_OFFSET,
                (short) (ipv4HeaderLen + Icmpv4HeaderLen + payloadLen));

        // Populate the IPv4 header checksum field.
        packet.putShort(ipv4HeaderOffset + IPV4_CHECKSUM_OFFSET,
                ipChecksum(packet, ipv4HeaderOffset /* headerOffset */));

        // Populate the ICMP checksum field.
        packet.putShort(ipv4HeaderOffset + IPV4_HEADER_MIN_LEN + ICMP_CHECKSUM_OFFSET,
                icmpChecksum(packet, ipv4HeaderOffset + IPV4_HEADER_MIN_LEN,
                        Icmpv4HeaderLen + payloadLen));
        return packet;
    }

    @NonNull
    private ByteBuffer buildIcmpEchoPacketV4(@NonNull final Inet4Address srcIp,
            @NonNull final Inet4Address dstIp, int type, short id, short seq)
            throws Exception {
        return buildIcmpEchoPacketV4(null /* srcMac */, null /* dstMac */, srcIp, dstIp,
                type, id, seq);
    }

    @Test
    public void testIcmpv4Echo() throws Exception {
        final TetheringTester tester = initTetheringTester(toList(TEST_IP4_ADDR),
                toList(TEST_IP4_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, false /* hasIpv6 */);

        // TODO: remove the connectivity verification for upstream connected notification race.
        // See the same reason in runUdp4Test().
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
        // See the same reason in runUdp4Test().
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
                + dnsQuery.getHeader().id);

        // [2] Send DNS reply.
        // DNS server --> upstream --> dnsmasq forwarding --> downstream --> tethered device
        //
        // DNS reply transaction id must be copied from DNS query. Used by the requester to match
        // up replies to outstanding queries. See RFC 1035 section 4.1.1.
        final Inet4Address remoteIp = (Inet4Address) TEST_IP4_DNS;
        final Inet4Address tetheringUpstreamIp = (Inet4Address) TEST_IP4_ADDR.getAddress();
        sendDownloadPacketDnsV4(remoteIp, tetheringUpstreamIp, DNS_PORT,
                (short) udpHeader.srcPort, (short) dnsQuery.getHeader().id, tester);
    }

    @NonNull
    private ByteBuffer buildTcpPacket(
            @Nullable final MacAddress srcMac, @Nullable final MacAddress dstMac,
            @NonNull final InetAddress srcIp, @NonNull final InetAddress dstIp,
            short srcPort, short dstPort, final short seq, final short ack,
            final byte tcpFlags, @NonNull final ByteBuffer payload) throws Exception {
        final int ipProto = getIpProto(srcIp, dstIp);
        final boolean hasEther = (srcMac != null && dstMac != null);
        final ByteBuffer buffer = PacketBuilder.allocate(hasEther, ipProto, IPPROTO_TCP,
                payload.limit());
        final PacketBuilder packetBuilder = new PacketBuilder(buffer);

        // [1] Ethernet header
        if (hasEther) {
            packetBuilder.writeL2Header(srcMac, dstMac, getEthType(srcIp, dstIp));
        }

        // [2] IP header
        if (ipProto == IPPROTO_IP) {
            packetBuilder.writeIpv4Header(TYPE_OF_SERVICE, ID, FLAGS_AND_FRAGMENT_OFFSET,
                    TIME_TO_LIVE, (byte) IPPROTO_TCP, (Inet4Address) srcIp, (Inet4Address) dstIp);
        } else {
            packetBuilder.writeIpv6Header(VERSION_TRAFFICCLASS_FLOWLABEL, (byte) IPPROTO_TCP,
                    HOP_LIMIT, (Inet6Address) srcIp, (Inet6Address) dstIp);
        }

        // [3] TCP header
        packetBuilder.writeTcpHeader(srcPort, dstPort, seq, ack, tcpFlags, WINDOW, URGENT_POINTER);

        // [4] Payload
        buffer.put(payload);
        // in case data might be reused by caller, restore the position and
        // limit of bytebuffer.
        payload.clear();

        return packetBuilder.finalizePacket();
    }

    private void sendDownloadPacketTcp(@NonNull final InetAddress srcIp,
            @NonNull final InetAddress dstIp, short seq, short ack, byte tcpFlags,
            @NonNull final ByteBuffer payload, @NonNull final TetheringTester tester,
            boolean is6To4) throws Exception {
        if (is6To4) {
            assertFalse("CLAT download test must sends IPv6 packet", isAddressIpv4(srcIp, dstIp));
        }

        // Expected received TCP packet IP protocol. While testing CLAT (is6To4 = true), the packet
        // on downstream must be IPv4. Otherwise, the IP protocol of test packet is the same on
        // both downstream and upstream.
        final boolean isIpv4 = is6To4 ? true : isAddressIpv4(srcIp, dstIp);

        final ByteBuffer testPacket = buildTcpPacket(null /* srcMac */, null /* dstMac */,
                srcIp, dstIp, REMOTE_PORT /* srcPort */, LOCAL_PORT /* dstPort */, seq, ack,
                tcpFlags, payload);
        tester.verifyDownload(testPacket, p -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));

            return isExpectedTcpPacket(p, true /* hasEther */, isIpv4, seq, payload);
        });
    }

    private void sendUploadPacketTcp(@NonNull final MacAddress srcMac,
            @NonNull final MacAddress dstMac, @NonNull final InetAddress srcIp,
            @NonNull final InetAddress dstIp, short seq, short ack, byte tcpFlags,
            @NonNull final ByteBuffer payload, @NonNull final TetheringTester tester,
            boolean is4To6) throws Exception {
        if (is4To6) {
            assertTrue("CLAT upload test must sends IPv4 packet", isAddressIpv4(srcIp, dstIp));
        }

        // Expected received TCP packet IP protocol. While testing CLAT (is4To6 = true), the packet
        // on upstream must be IPv6. Otherwise, the IP protocol of test packet is the same on
        // both downstream and upstream.
        final boolean isIpv4 = is4To6 ? false : isAddressIpv4(srcIp, dstIp);

        final ByteBuffer testPacket = buildTcpPacket(srcMac, dstMac, srcIp, dstIp,
                LOCAL_PORT /* srcPort */, REMOTE_PORT /* dstPort */, seq, ack, tcpFlags,
                payload);
        tester.verifyUpload(testPacket, p -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));

            return isExpectedTcpPacket(p, false /* hasEther */, isIpv4, seq, payload);
        });
    }

    void runTcpTest(
            @NonNull final MacAddress uploadSrcMac, @NonNull final MacAddress uploadDstMac,
            @NonNull final InetAddress uploadSrcIp, @NonNull final InetAddress uploadDstIp,
            @NonNull final InetAddress downloadSrcIp, @NonNull final InetAddress downloadDstIp,
            @NonNull final TetheringTester tester, boolean isClat) throws Exception {
        // Three way handshake and data transfer.
        //
        // Server (base seq = 2000)                                  Client (base seq = 1000)
        //   |                                                          |
        //   |    [1] [SYN] SEQ = 1000                                  |
        //   |<---------------------------------------------------------|  -
        //   |                                                          |  ^
        //   |    [2] [SYN + ACK] SEQ = 2000, ACK = 1000+1              |  |
        //   |--------------------------------------------------------->|  three way handshake
        //   |                                                          |  |
        //   |    [3] [ACK] SEQ = 1001, ACK = 2000+1                    |  v
        //   |<---------------------------------------------------------|  -
        //   |                                                          |  ^
        //   |    [4] [ACK] SEQ = 1001, ACK = 2001, 2 byte payload      |  |
        //   |<---------------------------------------------------------|  data transfer
        //   |                                                          |  |
        //   |    [5] [ACK] SEQ = 2001, ACK = 1001+2, 2 byte payload    |  v
        //   |--------------------------------------------------------->|  -
        //   |                                                          |
        //

        // This test can only verify the packets are transferred end to end but TCP state.
        // TODO: verify TCP state change via /proc/net/nf_conntrack or netlink conntrack event.
        // [1] [UPLOAD] [SYN]: SEQ = 1000
        sendUploadPacketTcp(uploadSrcMac, uploadDstMac, uploadSrcIp, uploadDstIp,
                (short) 1000 /* seq */, (short) 0 /* ack */, TCPHDR_SYN, EMPTY_PAYLOAD,
                tester, isClat /* is4To6 */);

        // [2] [DONWLOAD] [SYN + ACK]: SEQ = 2000, ACK = 1001
        sendDownloadPacketTcp(downloadSrcIp, downloadDstIp, (short) 2000 /* seq */,
                (short) 1001 /* ack */, (byte) ((TCPHDR_SYN | TCPHDR_ACK) & 0xff), EMPTY_PAYLOAD,
                tester, isClat /* is6To4 */);

        // [3] [UPLOAD] [ACK]: SEQ = 1001, ACK = 2001
        sendUploadPacketTcp(uploadSrcMac, uploadDstMac, uploadSrcIp, uploadDstIp,
                (short) 1001 /* seq */, (short) 2001 /* ack */, TCPHDR_ACK, EMPTY_PAYLOAD, tester,
                isClat /* is4To6 */);

        // [4] [UPLOAD] [ACK]: SEQ = 1001, ACK = 2001, 2 byte payload
        sendUploadPacketTcp(uploadSrcMac, uploadDstMac, uploadSrcIp, uploadDstIp,
                (short) 1001 /* seq */, (short) 2001 /* ack */, TCPHDR_ACK, TX_PAYLOAD,
                tester, isClat /* is4To6 */);

        // [5] [DONWLOAD] [ACK]: SEQ = 2001, ACK = 1003, 2 byte payload
        sendDownloadPacketTcp(downloadSrcIp, downloadDstIp, (short) 2001 /* seq */,
                (short) 1003 /* ack */, TCPHDR_ACK, RX_PAYLOAD, tester, isClat /* is6To4 */);

        // TODO: test BPF offload maps.
    }

    @Test
    public void testTetherTcpV4() throws Exception {
        final TetheringTester tester = initTetheringTester(toList(TEST_IP4_ADDR),
                toList(TEST_IP4_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, false /* hasIpv6 */);

        // TODO: remove the connectivity verification for upstream connected notification race.
        // See the same reason in runUdp4Test().
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

    @Test
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

    private <T> List<T> toList(T... array) {
        return Arrays.asList(array);
    }
}
