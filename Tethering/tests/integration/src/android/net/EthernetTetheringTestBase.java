/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.net.InetAddresses.parseNumericAddress;

import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.cts.util.CtsNetUtils;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.testutils.TapPacketReader;
import com.android.testutils.TestNetworkTracker;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * TODO: Common variables or methods shared between CtsEthernetTetheringTest and
 * MtsEthernetTetheringTest.
 */
public abstract class EthernetTetheringTestBase {
    private static final String TAG = EthernetTetheringTestBase.class.getSimpleName();

    protected static final int TIMEOUT_MS = 5000;
    // Used to check if any tethering interface is available. Choose 200ms to be request timeout
    // because the average interface requested time on cuttlefish@acloud is around 10ms.
    // See TetheredInterfaceRequester.getInterface, isInterfaceForTetheringAvailable.
    protected static final int AVAILABLE_TETHER_IFACE_REQUEST_TIMEOUT_MS = 200;
    protected static final int TETHER_REACHABILITY_ATTEMPTS = 20;
    protected static final long WAIT_RA_TIMEOUT_MS = 2000;

    // Address and NAT prefix definition.
    protected static final MacAddress TEST_MAC = MacAddress.fromString("1:2:3:4:5:6");
    protected static final LinkAddress TEST_IP4_ADDR = new LinkAddress("10.0.0.1/24");
    protected static final LinkAddress TEST_IP6_ADDR = new LinkAddress("2001:db8:1::101/64");
    protected static final InetAddress TEST_IP4_DNS = parseNumericAddress("8.8.8.8");
    protected static final InetAddress TEST_IP6_DNS = parseNumericAddress("2001:db8:1::888");

    protected static final Inet4Address REMOTE_IP4_ADDR =
            (Inet4Address) parseNumericAddress("8.8.8.8");
    protected static final Inet6Address REMOTE_IP6_ADDR =
            (Inet6Address) parseNumericAddress("2002:db8:1::515:ca");
    protected static final Inet6Address REMOTE_NAT64_ADDR =
            (Inet6Address) parseNumericAddress("64:ff9b::808:808");
    protected static final IpPrefix TEST_NAT64PREFIX = new IpPrefix("64:ff9b::/96");

    // IPv4 header definition.
    protected static final short ID = 27149;
    protected static final short FLAGS_AND_FRAGMENT_OFFSET = (short) 0x4000; // flags=DF, offset=0
    protected static final byte TIME_TO_LIVE = (byte) 0x40;
    protected static final byte TYPE_OF_SERVICE = 0;

    // IPv6 header definition.
    protected static final short HOP_LIMIT = 0x40;
    // version=6, traffic class=0x0, flowlabel=0x0;
    protected static final int VERSION_TRAFFICCLASS_FLOWLABEL = 0x60000000;

    // UDP and TCP header definition.
    // LOCAL_PORT is used by public port and private port. Assume port 9876 has not been used yet
    // before the testing that public port and private port are the same in the testing. Note that
    // NAT port forwarding could be different between private port and public port.
    protected static final short LOCAL_PORT = 9876;
    protected static final short REMOTE_PORT = 433;
    protected static final short WINDOW = (short) 0x2000;
    protected static final short URGENT_POINTER = 0;

    // Payload definition.
    protected static final ByteBuffer EMPTY_PAYLOAD = ByteBuffer.wrap(new byte[0]);
    protected static final ByteBuffer TEST_REACHABILITY_PAYLOAD =
            ByteBuffer.wrap(new byte[] { (byte) 0x55, (byte) 0xaa });
    protected static final ByteBuffer RX_PAYLOAD =
            ByteBuffer.wrap(new byte[] { (byte) 0x12, (byte) 0x34 });
    protected static final ByteBuffer TX_PAYLOAD =
            ByteBuffer.wrap(new byte[] { (byte) 0x56, (byte) 0x78 });

    protected final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    protected final EthernetManager mEm = mContext.getSystemService(EthernetManager.class);
    protected final TetheringManager mTm = mContext.getSystemService(TetheringManager.class);
    protected final PackageManager mPackageManager = mContext.getPackageManager();
    protected final CtsNetUtils mCtsNetUtils = new CtsNetUtils(mContext);
    protected final UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();

    // Late initialization in setUp()
    protected boolean mRunTests;
    protected HandlerThread mHandlerThread;
    protected Handler mHandler;

    // Late initialization in initTetheringTester().
    protected TapPacketReader mUpstreamReader;
    protected TestNetworkTracker mUpstreamTracker;
    protected TestNetworkInterface mDownstreamIface;
    protected TapPacketReader mDownstreamReader;
}
