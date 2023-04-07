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

package com.android.net.module.util.netlink;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.NETLINK_INET_DIAG;

import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_DUMP;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.InetAddresses;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import libcore.util.HexEncoding;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class InetDiagSocketTest {
    // Hexadecimal representation of InetDiagReqV2 request.
    private static final String INET_DIAG_REQ_V2_UDP_INET4_HEX =
            // struct nlmsghdr
            "48000000" +     // length = 72
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0103" +         // flags = NLM_F_REQUEST | NLM_F_DUMP
            "00000000" +     // seqno
            "00000000" +     // pid (0 == kernel)
            // struct inet_diag_req_v2
            "02" +           // family = AF_INET
            "11" +           // protcol = IPPROTO_UDP
            "00" +           // idiag_ext
            "00" +           // pad
            "ffffffff" +     // idiag_states
            // inet_diag_sockid
            "a5de" +         // idiag_sport = 42462
            "b971" +         // idiag_dport = 47473
            "0a006402000000000000000000000000" + // idiag_src = 10.0.100.2
            "08080808000000000000000000000000" + // idiag_dst = 8.8.8.8
            "00000000" +     // idiag_if
            "ffffffffffffffff"; // idiag_cookie = INET_DIAG_NOCOOKIE
    private static final byte[] INET_DIAG_REQ_V2_UDP_INET4_BYTES =
            HexEncoding.decode(INET_DIAG_REQ_V2_UDP_INET4_HEX.toCharArray(), false);

    @Test
    public void testInetDiagReqV2UdpInet4() throws Exception {
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByName("10.0.100.2"),
                42462);
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName("8.8.8.8"),
                47473);
        final byte[] msg = InetDiagMessage.inetDiagReqV2(IPPROTO_UDP, local, remote, AF_INET,
                (short) (NLM_F_REQUEST | NLM_F_DUMP));
        assertArrayEquals(INET_DIAG_REQ_V2_UDP_INET4_BYTES, msg);
    }

    // Hexadecimal representation of InetDiagReqV2 request.
    private static final String INET_DIAG_REQ_V2_TCP_INET6_HEX =
            // struct nlmsghdr
            "48000000" +     // length = 72
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0100" +         // flags = NLM_F_REQUEST
            "00000000" +     // seqno
            "00000000" +     // pid (0 == kernel)
            // struct inet_diag_req_v2
            "0a" +           // family = AF_INET6
            "06" +           // protcol = IPPROTO_TCP
            "00" +           // idiag_ext
            "00" +           // pad
            "ffffffff" +     // idiag_states
                // inet_diag_sockid
                "a5de" +         // idiag_sport = 42462
                "b971" +         // idiag_dport = 47473
                "fe8000000000000086c9b2fffe6aed4b" + // idiag_src = fe80::86c9:b2ff:fe6a:ed4b
                "08080808000000000000000000000000" + // idiag_dst = 8.8.8.8
                "00000000" +     // idiag_if
                "ffffffffffffffff"; // idiag_cookie = INET_DIAG_NOCOOKIE
    private static final byte[] INET_DIAG_REQ_V2_TCP_INET6_BYTES =
            HexEncoding.decode(INET_DIAG_REQ_V2_TCP_INET6_HEX.toCharArray(), false);

    @Test
    public void testInetDiagReqV2TcpInet6() throws Exception {
        InetSocketAddress local = new InetSocketAddress(
                InetAddress.getByName("fe80::86c9:b2ff:fe6a:ed4b"), 42462);
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName("8.8.8.8"),
                47473);
        byte[] msg = InetDiagMessage.inetDiagReqV2(IPPROTO_TCP, local, remote, AF_INET6,
                NLM_F_REQUEST);

        assertArrayEquals(INET_DIAG_REQ_V2_TCP_INET6_BYTES, msg);
    }

    // Hexadecimal representation of InetDiagReqV2 request with extension, INET_DIAG_INFO.
    private static final String INET_DIAG_REQ_V2_TCP_INET_INET_DIAG_HEX =
            // struct nlmsghdr
            "48000000" +     // length = 72
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0100" +         // flags = NLM_F_REQUEST
            "00000000" +     // seqno
            "00000000" +     // pid (0 == kernel)
            // struct inet_diag_req_v2
            "02" +           // family = AF_INET
            "06" +           // protcol = IPPROTO_TCP
            "02" +           // idiag_ext = INET_DIAG_INFO
            "00" +           // pad
            "ffffffff" +   // idiag_states
            // inet_diag_sockid
            "3039" +         // idiag_sport = 12345
            "d431" +         // idiag_dport = 54321
            "01020304000000000000000000000000" + // idiag_src = 1.2.3.4
            "08080404000000000000000000000000" + // idiag_dst = 8.8.4.4
            "00000000" +     // idiag_if
            "ffffffffffffffff"; // idiag_cookie = INET_DIAG_NOCOOKIE

    private static final byte[] INET_DIAG_REQ_V2_TCP_INET_INET_DIAG_BYTES =
            HexEncoding.decode(INET_DIAG_REQ_V2_TCP_INET_INET_DIAG_HEX.toCharArray(), false);
    private static final int TCP_ALL_STATES = 0xffffffff;
    @Test
    public void testInetDiagReqV2TcpInetWithExt() throws Exception {
        InetSocketAddress local = new InetSocketAddress(
                InetAddress.getByName("1.2.3.4"), 12345);
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName("8.8.4.4"),
                54321);
        byte[] msg = InetDiagMessage.inetDiagReqV2(IPPROTO_TCP, local, remote, AF_INET,
                NLM_F_REQUEST, 0 /* pad */, 2 /* idiagExt */, TCP_ALL_STATES);

        assertArrayEquals(INET_DIAG_REQ_V2_TCP_INET_INET_DIAG_BYTES, msg);

        local = new InetSocketAddress(
                InetAddress.getByName("fe80::86c9:b2ff:fe6a:ed4b"), 42462);
        remote = new InetSocketAddress(InetAddress.getByName("8.8.8.8"),
                47473);
        msg = InetDiagMessage.inetDiagReqV2(IPPROTO_TCP, local, remote, AF_INET6,
                NLM_F_REQUEST, 0 /* pad */, 0 /* idiagExt */, TCP_ALL_STATES);

        assertArrayEquals(INET_DIAG_REQ_V2_TCP_INET6_BYTES, msg);
    }

    // Hexadecimal representation of InetDiagReqV2 request with no socket specified.
    private static final String INET_DIAG_REQ_V2_TCP_INET6_NO_ID_SPECIFIED_HEX =
            // struct nlmsghdr
            "48000000" +     // length = 72
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0100" +         // flags = NLM_F_REQUEST
            "00000000" +     // seqno
            "00000000" +     // pid (0 == kernel)
            // struct inet_diag_req_v2
            "0a" +           // family = AF_INET6
            "06" +           // protcol = IPPROTO_TCP
            "00" +           // idiag_ext
            "00" +           // pad
            "ffffffff" +     // idiag_states
            // inet_diag_sockid
            "0000" +         // idiag_sport
            "0000" +         // idiag_dport
            "00000000000000000000000000000000" + // idiag_src
            "00000000000000000000000000000000" + // idiag_dst
            "00000000" +     // idiag_if
            "0000000000000000"; // idiag_cookie

    private static final byte[] INET_DIAG_REQ_V2_TCP_INET6_NO_ID_SPECIFIED_BYTES =
            HexEncoding.decode(INET_DIAG_REQ_V2_TCP_INET6_NO_ID_SPECIFIED_HEX.toCharArray(), false);

    @Test
    public void testInetDiagReqV2TcpInet6NoIdSpecified() throws Exception {
        InetSocketAddress local = new InetSocketAddress(
                InetAddress.getByName("fe80::fe6a:ed4b"), 12345);
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName("8.8.4.4"),
                54321);
        // Verify no socket specified if either local or remote socket address is null.
        byte[] msgExt = InetDiagMessage.inetDiagReqV2(IPPROTO_TCP, null, null, AF_INET6,
                NLM_F_REQUEST, 0 /* pad */, 0 /* idiagExt */, TCP_ALL_STATES);
        byte[] msg;
        try {
            msg = InetDiagMessage.inetDiagReqV2(IPPROTO_TCP, null, remote, AF_INET6,
                    NLM_F_REQUEST);
            fail("Both remote and local should be null, expected UnknownHostException");
        } catch (NullPointerException e) {
        }

        try {
            msg = InetDiagMessage.inetDiagReqV2(IPPROTO_TCP, local, null, AF_INET6,
                    NLM_F_REQUEST, 0 /* pad */, 0 /* idiagExt */, TCP_ALL_STATES);
            fail("Both remote and local should be null, expected UnknownHostException");
        } catch (NullPointerException e) {
        }

        msg = InetDiagMessage.inetDiagReqV2(IPPROTO_TCP, null, null, AF_INET6,
                NLM_F_REQUEST, 0 /* pad */, 0 /* idiagExt */, TCP_ALL_STATES);
        assertArrayEquals(INET_DIAG_REQ_V2_TCP_INET6_NO_ID_SPECIFIED_BYTES, msg);
        assertArrayEquals(INET_DIAG_REQ_V2_TCP_INET6_NO_ID_SPECIFIED_BYTES, msgExt);
    }

    private void assertNlMsgHdr(StructNlMsgHdr hdr, short type, short flags, int seq, int pid) {
        assertNotNull(hdr);
        assertEquals(type, hdr.nlmsg_type);
        assertEquals(flags, hdr.nlmsg_flags);
        assertEquals(seq, hdr.nlmsg_seq);
        assertEquals(pid, hdr.nlmsg_pid);
    }

    private void assertInetDiagSockId(StructInetDiagSockId sockId,
            InetSocketAddress locSocketAddress, InetSocketAddress remSocketAddress,
            int ifIndex, long cookie) {
        assertEquals(locSocketAddress, sockId.locSocketAddress);
        assertEquals(remSocketAddress, sockId.remSocketAddress);
        assertEquals(ifIndex, sockId.ifIndex);
        assertEquals(cookie, sockId.cookie);
    }

    // Hexadecimal representation of InetDiagMessage
    private static final String INET_DIAG_MSG_HEX1 =
            // struct nlmsghdr
            "58000000" +     // length = 88
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0200" +         // flags = NLM_F_MULTI
            "00000000" +     // seqno
            "f5220000" +     // pid
            // struct inet_diag_msg
            "0a" +           // family = AF_INET6
            "01" +           // idiag_state = 1
            "02" +           // idiag_timer = 2
            "ff" +           // idiag_retrans = 255
                // inet_diag_sockid
                "a817" +     // idiag_sport = 43031
                "960f" +     // idiag_dport = 38415
                "20010db8000000000000000000000001" + // idiag_src = 2001:db8::1
                "20010db8000000000000000000000002" + // idiag_dst = 2001:db8::2
                "07000000" + // idiag_if = 7
                "5800000000000000" + // idiag_cookie = 88
            "04000000" +     // idiag_expires = 4
            "05000000" +     // idiag_rqueue = 5
            "06000000" +     // idiag_wqueue = 6
            "a3270000" +     // idiag_uid = 10147
            "a57e19f0";      // idiag_inode = 4028202661

    private void assertInetDiagMsg1(final NetlinkMessage msg) {
        assertNotNull(msg);

        assertTrue(msg instanceof InetDiagMessage);
        final InetDiagMessage inetDiagMsg = (InetDiagMessage) msg;

        assertNlMsgHdr(inetDiagMsg.getHeader(),
                NetlinkConstants.SOCK_DIAG_BY_FAMILY,
                StructNlMsgHdr.NLM_F_MULTI,
                0    /* seq */,
                8949 /* pid */);

        assertEquals(AF_INET6, inetDiagMsg.inetDiagMsg.idiag_family);
        assertEquals(1, inetDiagMsg.inetDiagMsg.idiag_state);
        assertEquals(2, inetDiagMsg.inetDiagMsg.idiag_timer);
        assertEquals(255, inetDiagMsg.inetDiagMsg.idiag_retrans);
        assertInetDiagSockId(inetDiagMsg.inetDiagMsg.id,
                new InetSocketAddress(InetAddresses.parseNumericAddress("2001:db8::1"), 43031),
                new InetSocketAddress(InetAddresses.parseNumericAddress("2001:db8::2"), 38415),
                7  /* ifIndex */,
                88 /* cookie */);
        assertEquals(4, inetDiagMsg.inetDiagMsg.idiag_expires);
        assertEquals(5, inetDiagMsg.inetDiagMsg.idiag_rqueue);
        assertEquals(6, inetDiagMsg.inetDiagMsg.idiag_wqueue);
        assertEquals(10147, inetDiagMsg.inetDiagMsg.idiag_uid);
        assertEquals(4028202661L, inetDiagMsg.inetDiagMsg.idiag_inode);
    }

    // Hexadecimal representation of InetDiagMessage
    private static final String INET_DIAG_MSG_HEX2 =
            // struct nlmsghdr
            "58000000" +     // length = 88
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0200" +         // flags = NLM_F_MULTI
            "00000000" +     // seqno
            "f5220000" +     // pid
            // struct inet_diag_msg
            "0a" +           // family = AF_INET6
            "02" +           // idiag_state = 2
            "10" +           // idiag_timer = 16
            "20" +           // idiag_retrans = 32
                // inet_diag_sockid
                "a845" +     // idiag_sport = 43077
                "01bb" +     // idiag_dport = 443
                "20010db8000000000000000000000003" + // idiag_src = 2001:db8::3
                "20010db8000000000000000000000004" + // idiag_dst = 2001:db8::4
                "08000000" + // idiag_if = 8
                "6300000000000000" + // idiag_cookie = 99
            "30000000" +     // idiag_expires = 48
            "40000000" +     // idiag_rqueue = 64
            "50000000" +     // idiag_wqueue = 80
            "39300000" +     // idiag_uid = 12345
            "851a0000";      // idiag_inode = 6789

    private void assertInetDiagMsg2(final NetlinkMessage msg) {
        assertNotNull(msg);

        assertTrue(msg instanceof InetDiagMessage);
        final InetDiagMessage inetDiagMsg = (InetDiagMessage) msg;

        assertNlMsgHdr(inetDiagMsg.getHeader(),
                NetlinkConstants.SOCK_DIAG_BY_FAMILY,
                StructNlMsgHdr.NLM_F_MULTI,
                0    /* seq */,
                8949 /* pid */);

        assertEquals(AF_INET6, inetDiagMsg.inetDiagMsg.idiag_family);
        assertEquals(2, inetDiagMsg.inetDiagMsg.idiag_state);
        assertEquals(16, inetDiagMsg.inetDiagMsg.idiag_timer);
        assertEquals(32, inetDiagMsg.inetDiagMsg.idiag_retrans);
        assertInetDiagSockId(inetDiagMsg.inetDiagMsg.id,
                new InetSocketAddress(InetAddresses.parseNumericAddress("2001:db8::3"), 43077),
                new InetSocketAddress(InetAddresses.parseNumericAddress("2001:db8::4"), 443),
                8  /* ifIndex */,
                99 /* cookie */);
        assertEquals(48, inetDiagMsg.inetDiagMsg.idiag_expires);
        assertEquals(64, inetDiagMsg.inetDiagMsg.idiag_rqueue);
        assertEquals(80, inetDiagMsg.inetDiagMsg.idiag_wqueue);
        assertEquals(12345, inetDiagMsg.inetDiagMsg.idiag_uid);
        assertEquals(6789, inetDiagMsg.inetDiagMsg.idiag_inode);
    }

    private static final byte[] INET_DIAG_MSG_BYTES =
            HexEncoding.decode(INET_DIAG_MSG_HEX1.toCharArray(), false);

    @Test
    public void testParseInetDiagResponse() throws Exception {
        final ByteBuffer byteBuffer = ByteBuffer.wrap(INET_DIAG_MSG_BYTES);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        assertInetDiagMsg1(NetlinkMessage.parse(byteBuffer, NETLINK_INET_DIAG));
    }


    private static final byte[] INET_DIAG_MSG_BYTES_MULTIPLE =
            HexEncoding.decode((INET_DIAG_MSG_HEX1 + INET_DIAG_MSG_HEX2).toCharArray(), false);

    @Test
    public void testParseInetDiagResponseMultiple() {
        final ByteBuffer byteBuffer = ByteBuffer.wrap(INET_DIAG_MSG_BYTES_MULTIPLE);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        assertInetDiagMsg1(NetlinkMessage.parse(byteBuffer, NETLINK_INET_DIAG));
        assertInetDiagMsg2(NetlinkMessage.parse(byteBuffer, NETLINK_INET_DIAG));
    }
}
