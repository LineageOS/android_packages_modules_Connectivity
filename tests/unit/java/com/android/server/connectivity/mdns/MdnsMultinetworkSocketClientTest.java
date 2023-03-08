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

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.MdnsSocketProvider.SocketCallback;
import static com.android.server.connectivity.mdns.MulticastPacketReader.PacketHandler;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.net.InetAddresses;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import com.android.net.module.util.HexDump;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.DatagramPacket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class MdnsMultinetworkSocketClientTest {
    private static final byte[] BUFFER = new byte[10];
    private static final long DEFAULT_TIMEOUT = 2000L;
    @Mock private Network mNetwork;
    @Mock private MdnsSocketProvider mProvider;
    @Mock private MdnsInterfaceSocket mSocket;
    @Mock private MdnsServiceBrowserListener mListener;
    @Mock private MdnsSocketClientBase.Callback mCallback;
    @Mock private MdnsSocketClientBase.SocketCreationCallback mSocketCreationCallback;
    private MdnsMultinetworkSocketClient mSocketClient;
    private Handler mHandler;

    @Before
    public void setUp() throws SocketException {
        MockitoAnnotations.initMocks(this);
        final HandlerThread thread = new HandlerThread("MdnsMultinetworkSocketClientTest");
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mSocketClient = new MdnsMultinetworkSocketClient(thread.getLooper(), mProvider);
        mHandler.post(() -> mSocketClient.setCallback(mCallback));
    }

    private SocketCallback expectSocketCallback() {
        final ArgumentCaptor<SocketCallback> callbackCaptor =
                ArgumentCaptor.forClass(SocketCallback.class);
        mHandler.post(() -> mSocketClient.notifyNetworkRequested(
                mListener, mNetwork, mSocketCreationCallback));
        verify(mProvider, timeout(DEFAULT_TIMEOUT))
                .requestSocket(eq(mNetwork), callbackCaptor.capture());
        return callbackCaptor.getValue();
    }

    private NetworkInterface createEmptyNetworkInterface() {
        try {
            Constructor<NetworkInterface> constructor =
                    NetworkInterface.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testSendPacket() throws IOException {
        final SocketCallback callback = expectSocketCallback();
        final DatagramPacket ipv4Packet = new DatagramPacket(BUFFER, 0 /* offset */, BUFFER.length,
                InetAddresses.parseNumericAddress("192.0.2.1"), 0 /* port */);
        final DatagramPacket ipv6Packet = new DatagramPacket(BUFFER, 0 /* offset */, BUFFER.length,
                InetAddresses.parseNumericAddress("2001:db8::"), 0 /* port */);
        doReturn(true).when(mSocket).hasJoinedIpv4();
        doReturn(true).when(mSocket).hasJoinedIpv6();
        doReturn(createEmptyNetworkInterface()).when(mSocket).getInterface();
        // Notify socket created
        callback.onSocketCreated(mNetwork, mSocket, List.of());
        verify(mSocketCreationCallback).onSocketCreated(mNetwork);

        // Send packet to IPv4 with target network and verify sending has been called.
        mSocketClient.sendMulticastPacket(ipv4Packet, mNetwork);
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mSocket).send(ipv4Packet);

        // Send packet to IPv6 without target network and verify sending has been called.
        mSocketClient.sendMulticastPacket(ipv6Packet);
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mSocket).send(ipv6Packet);
    }

    @Test
    public void testReceivePacket() {
        final SocketCallback callback = expectSocketCallback();
        final byte[] data = HexDump.hexStringToByteArray(
                // scapy.raw(scapy.dns_compress(
                //     scapy.DNS(rd=0, qr=1, aa=1, qd = None,
                //     an =
                //     scapy.DNSRR(type='PTR', rrname='_testtype._tcp.local',
                //         rdata='testservice._testtype._tcp.local', rclass='IN', ttl=4500) /
                //     scapy.DNSRRSRV(rrname='testservice._testtype._tcp.local', rclass=0x8001,
                //         port=31234, target='Android.local', ttl=120))
                // )).hex().upper()
                "000084000000000200000000095F7465737474797065045F746370056C6F63616C00000C0001000011"
                        + "94000E0B7465737473657276696365C00CC02C00218001000000780010000000007A0207"
                        + "416E64726F6964C01B");

        doReturn(createEmptyNetworkInterface()).when(mSocket).getInterface();
        // Notify socket created
        callback.onSocketCreated(mNetwork, mSocket, List.of());
        verify(mSocketCreationCallback).onSocketCreated(mNetwork);

        final ArgumentCaptor<PacketHandler> handlerCaptor =
                ArgumentCaptor.forClass(PacketHandler.class);
        verify(mSocket).addPacketHandler(handlerCaptor.capture());

        // Send the data and verify the received records.
        final PacketHandler handler = handlerCaptor.getValue();
        handler.handlePacket(data, data.length, null /* src */);
        final ArgumentCaptor<MdnsPacket> responseCaptor =
                ArgumentCaptor.forClass(MdnsPacket.class);
        verify(mCallback).onResponseReceived(responseCaptor.capture(), anyInt(), any());
        final MdnsPacket response = responseCaptor.getValue();
        assertEquals(0, response.questions.size());
        assertEquals(0, response.additionalRecords.size());
        assertEquals(0, response.authorityRecords.size());

        final String[] serviceName = "testservice._testtype._tcp.local".split("\\.");
        assertEquals(List.of(
                new MdnsPointerRecord("_testtype._tcp.local".split("\\."),
                        0L /* receiptTimeMillis */, false /* cacheFlush */, 4500000 /* ttlMillis */,
                        serviceName),
                new MdnsServiceRecord(serviceName, 0L /* receiptTimeMillis */,
                        false /* cacheFlush */, 4500000 /* ttlMillis */, 0 /* servicePriority */,
                        0 /* serviceWeight */, 31234 /* servicePort */,
                        new String[] { "Android", "local" } /* serviceHost */)
        ), response.answers);
    }
}
