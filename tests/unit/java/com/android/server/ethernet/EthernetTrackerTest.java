/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.ethernet;

import static android.net.TestNetworkManager.TEST_TAP_PREFIX;

import static com.android.server.ethernet.EthernetTracker.DEFAULT_CAPABILITIES;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.LinkAddress;
import android.net.NetworkCapabilities;
import android.net.StaticIpConfiguration;
import android.os.Build;
import android.os.HandlerThread;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import com.android.server.ethernet.EthernetTracker.EthernetConfigParser;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetAddress;
import java.util.ArrayList;

@SmallTest
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class EthernetTrackerTest {
    private static final String TEST_IFACE = "test123";
    private static final int TIMEOUT_MS = 1_000;
    private static final String THREAD_NAME = "EthernetServiceThread";
    private static final EthernetCallback NULL_CB = new EthernetCallback(null);
    private EthernetTracker tracker;
    private HandlerThread mHandlerThread;
    @Mock private Context mContext;
    @Mock private EthernetNetworkFactory mFactory;
    @Mock private INetd mNetd;
    @Mock private EthernetTracker.Dependencies mDeps;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        initMockResources();
        doReturn(false).when(mFactory).updateInterfaceLinkState(any(), anyBoolean());
        doReturn(new String[0]).when(mNetd).interfaceGetList();
        doReturn(new String[0]).when(mFactory).getInterfacesSorted(anyBoolean());
        mHandlerThread = new HandlerThread(THREAD_NAME);
        mHandlerThread.start();
        tracker = new EthernetTracker(mContext, mHandlerThread.getThreadHandler(), mFactory, mNetd,
                mDeps);
    }

    @After
    public void cleanUp() throws InterruptedException {
        mHandlerThread.quitSafely();
        mHandlerThread.join();
    }

    private void initMockResources() {
        doReturn("").when(mDeps).getInterfaceRegexFromResource(eq(mContext));
        doReturn(new String[0]).when(mDeps).getInterfaceConfigFromResource(eq(mContext));
    }

    private void waitForIdle() {
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);
    }

    @Test
    public void testIpConfigurationParsing() {
        EthernetConfigParser p = new EthernetConfigParser("eth0;*;;", true /*isAtLeastB*/);
        assertThat(p.mIpConfig).isNull();

        p = new EthernetConfigParser("eth0;*;ip=192.0.2.10/24", true /*isAtLeastB*/);
        StaticIpConfiguration s = new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress("192.0.2.10/24"))
                .build();
        assertThat(p.mIpConfig)
                .isEqualTo(new IpConfiguration.Builder().setStaticIpConfiguration(s).build());

        p = new EthernetConfigParser(
                "eth0;*;ip=192.0.2.10/24 dns=4.4.4.4,8.8.8.8   gateway=192.0.2.1  domains=android ",
                true /*isAtLeastB*/);
        ArrayList<InetAddress> dns = new ArrayList<>();
        dns.add(InetAddresses.parseNumericAddress("4.4.4.4"));
        dns.add(InetAddresses.parseNumericAddress("8.8.8.8"));
        s = new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress("192.0.2.10/24"))
                .setDnsServers(dns)
                .setGateway(InetAddresses.parseNumericAddress("192.0.2.1"))
                .setDomains("android")
                .build();
        assertThat(p.mIpConfig)
                .isEqualTo(new IpConfiguration.Builder().setStaticIpConfiguration(s).build());

        // Verify order doesn't matter
        p = new EthernetConfigParser(
                "eth0;; domains=android ip=192.0.2.10/24 gateway=192.0.2.1 dns=4.4.4.4,8.8.8.8   ;",
                false /*isAtLeastB*/);
        assertThat(p.mIpConfig)
                .isEqualTo(new IpConfiguration.Builder().setStaticIpConfiguration(s).build());
    }

    @Test
    public void testIpConfigurationParsing_withInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> { // unknown key
            new EthernetConfigParser("eth0;;ip=192.0.2.1/24 blah=20.20.20.20", true /*isAtLeastB*/);
        });

        assertThrows(IllegalArgumentException.class, () -> { // mask missing
            new EthernetConfigParser("eth0;;ip=192.0.2.1", true /*isAtLeastB*/);
        });

        assertThrows(IllegalArgumentException.class, () -> { // invalid ip address
            new EthernetConfigParser("eth0;;ip=x.y.z", true /*isAtLeastB*/);
        });

        assertThrows(IllegalArgumentException.class, () -> { // invalid dns ip
            new EthernetConfigParser("eth0;;dns=4.4.4.4,1.2.3.A", true /*isAtLeastB*/);
        });

        assertThrows(IllegalArgumentException.class, () -> { // empty key / value
            new EthernetConfigParser("eth0;;=", true /*isAtLeastB*/);
        });

        assertThrows(IllegalArgumentException.class, () -> { // empty value
            new EthernetConfigParser("eth0;;ip=", true /*isAtLeastB*/);
        });

        assertThrows(IllegalArgumentException.class, () -> { // empty gateway
            new EthernetConfigParser("eth0;;ip=192.0.2.1/24 gateway=", true /*isAtLeastB*/);
        });
    }

    @Test
    public void testNetworkCapabilityParsing() {
        final NetworkCapabilities baseNc = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .setLinkUpstreamBandwidthKbps(100 * 1000 /* 100 Mbps */)
                .setLinkDownstreamBandwidthKbps(100 * 1000 /* 100 Mbps */)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                .build();

        // Empty capabilities always default to the baseNc above.
        EthernetConfigParser p = new EthernetConfigParser("eth0;", false /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(baseNc);
        p = new EthernetConfigParser("eth0;", true /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(baseNc);

        // On Android B+, "*" defaults to using DEFAULT_CAPABILITIES.
        p = new EthernetConfigParser("eth0;*;;;;;;", true /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(DEFAULT_CAPABILITIES);

        // But not so before B.
        p = new EthernetConfigParser("eth0;*", false /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(baseNc);

        p = new EthernetConfigParser("eth0;12,13,14,15;", false /*isAtLeastB*/);
        assertThat(p.mCaps.getCapabilities()).asList().containsAtLeast(12, 13, 14, 15);

        p = new EthernetConfigParser("eth0;12,13,500,abc", false /*isAtLeastB*/);
        // 18, 20, 21 are added by EthernetConfigParser.
        assertThat(p.mCaps.getCapabilities()).asList().containsExactly(12, 13, 18, 20, 21);

        p = new EthernetConfigParser("eth0;1,2,3;;0", false /*isAtLeastB*/);
        assertThat(p.mCaps.getCapabilities()).asList().containsAtLeast(1, 2, 3);
        assertThat(p.mCaps.hasSingleTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isTrue();

        // TRANSPORT_VPN (4) is not allowed.
        p = new EthernetConfigParser("eth0;;;4", false /*isAtLeastB*/);
        assertThat(p.mCaps.hasSingleTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).isTrue();
        p = new EthernetConfigParser("eth0;*;;4", true /*isAtLeastB*/);
        assertThat(p.mCaps.hasSingleTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).isTrue();

        // invalid capability and transport type
        p = new EthernetConfigParser("eth0;-1,a,1000,,;;-1", false /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(baseNc);

        p = new EthernetConfigParser("eth0;*;;0", false /*isAtLeastB*/);
        assertThat(p.mCaps.hasSingleTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isTrue();
        p = new EthernetConfigParser("eth0;*;;0", true /*isAtLeastB*/);
        assertThat(p.mCaps.hasSingleTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isTrue();

        NetworkCapabilities nc = new NetworkCapabilities.Builder(DEFAULT_CAPABILITIES)
                .removeTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();
        p = new EthernetConfigParser("eth0;*;;0", true /*isAtLeastB*/);
        assertThat(p.mCaps).isEqualTo(nc);
    }

    @Test
    public void testInterfaceNameParsing() {
        EthernetConfigParser p = new EthernetConfigParser("eth12", false /*isAtLeastB*/);
        assertThat(p.mIface).isEqualTo("eth12");

        p = new EthernetConfigParser("", true /*isAtLeastB*/);
        assertThat(p.mIface).isEqualTo("");

        p = new EthernetConfigParser("eth0;12;", true /*isAtLeastB*/);
        assertThat(p.mIface).isEqualTo("eth0");
    }

    @Test
    public void testCreateEthernetConfigParserThrowsNpeWithNullInput() {
        assertThrows(NullPointerException.class, () -> new EthernetConfigParser(null, false));
    }

    @Test
    public void testUpdateConfiguration() {
        final NetworkCapabilities capabilities = new NetworkCapabilities.Builder().build();
        final LinkAddress linkAddr = new LinkAddress("192.0.2.2/25");
        final StaticIpConfiguration staticIpConfig =
                new StaticIpConfiguration.Builder().setIpAddress(linkAddr).build();
        final IpConfiguration ipConfig =
                new IpConfiguration.Builder().setStaticIpConfiguration(staticIpConfig).build();
        final EthernetCallback listener = new EthernetCallback(null);

        tracker.updateConfiguration(TEST_IFACE, ipConfig, capabilities, listener);
        waitForIdle();

        verify(mFactory).updateInterface(
                eq(TEST_IFACE), eq(ipConfig), eq(capabilities));
    }
}
