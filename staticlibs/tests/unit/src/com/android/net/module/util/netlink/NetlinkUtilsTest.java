/*
 * Copyright (C) 2015 The Android Open Source Project
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
import static android.system.OsConstants.AF_UNSPEC;
import static android.system.OsConstants.EACCES;
import static android.system.OsConstants.NETLINK_ROUTE;

import static com.android.net.module.util.netlink.NetlinkUtils.DEFAULT_RECV_BUFSIZE;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_DUMP;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.system.ErrnoException;
import android.system.NetlinkSocketAddress;
import android.system.Os;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.Struct;

import libcore.io.IoUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetlinkUtilsTest {
    private static final String TAG = "NetlinkUtilsTest";
    private static final int TEST_SEQNO = 5;
    private static final int TEST_TIMEOUT_MS = 500;

    @Test
    public void testGetNeighborsQuery() throws Exception {
        final FileDescriptor fd = NetlinkUtils.netlinkSocketForProto(NETLINK_ROUTE);
        assertNotNull(fd);

        NetlinkUtils.connectToKernel(fd);

        final NetlinkSocketAddress localAddr = (NetlinkSocketAddress) Os.getsockname(fd);
        assertNotNull(localAddr);
        assertEquals(0, localAddr.getGroupsMask());
        assertTrue(0 != localAddr.getPortId());

        final byte[] req = RtNetlinkNeighborMessage.newGetNeighborsRequest(TEST_SEQNO);
        assertNotNull(req);

        final Context ctx = InstrumentationRegistry.getInstrumentation().getContext();
        final int targetSdk =
                ctx.getPackageManager()
                        .getApplicationInfo(ctx.getPackageName(), 0)
                        .targetSdkVersion;

        // Apps targeting an SDK version > S are not allowed to send RTM_GETNEIGH{TBL} messages
        if (SdkLevel.isAtLeastT() && targetSdk > 31) {
            var ctxt = new String(Files.readAllBytes(Paths.get("/proc/thread-self/attr/current")));
            assumeFalse("must not be platform app", ctxt.startsWith("u:r:platform_app:s0:"));
            // NetworkStackCoverageTests uses the same UID with NetworkStack module, which
            // still has the permission to send RTM_GETNEIGH message (sepolicy just blocks the
            // access from untrusted_apps), also exclude the NetworkStackCoverageTests.
            assumeFalse("network_stack context is expected to have permission to send RTM_GETNEIGH",
                    ctxt.startsWith("u:r:network_stack:s0"));
            try {
                NetlinkUtils.sendMessage(fd, req, 0, req.length, TEST_TIMEOUT_MS);
                fail("RTM_GETNEIGH is not allowed for apps targeting SDK > 31 on T+ platforms,"
                        + " target SDK version: " + targetSdk);
            } catch (ErrnoException e) {
                // Expected
                assertEquals(e.errno, EACCES);
                return;
            }
        }

        // Check that apps targeting lower API levels / running on older platforms succeed
        assertEquals(req.length,
                NetlinkUtils.sendMessage(fd, req, 0, req.length, TEST_TIMEOUT_MS));

        int neighMessageCount = 0;
        int doneMessageCount = 0;

        while (doneMessageCount == 0) {
            ByteBuffer response =
                    NetlinkUtils.recvMessage(fd, DEFAULT_RECV_BUFSIZE, TEST_TIMEOUT_MS);
            assertNotNull(response);
            assertTrue(StructNlMsgHdr.STRUCT_SIZE <= response.limit());
            assertEquals(0, response.position());
            assertEquals(ByteOrder.nativeOrder(), response.order());

            // Verify the messages at least appears minimally reasonable.
            while (response.remaining() > 0) {
                final NetlinkMessage msg = NetlinkMessage.parse(response, NETLINK_ROUTE);
                assertNotNull(msg);
                final StructNlMsgHdr hdr = msg.getHeader();
                assertNotNull(hdr);

                if (hdr.nlmsg_type == NetlinkConstants.NLMSG_DONE) {
                    doneMessageCount++;
                    continue;
                }

                assertEquals(NetlinkConstants.RTM_NEWNEIGH, hdr.nlmsg_type);
                assertTrue(msg instanceof RtNetlinkNeighborMessage);
                assertTrue((hdr.nlmsg_flags & StructNlMsgHdr.NLM_F_MULTI) != 0);
                assertEquals(TEST_SEQNO, hdr.nlmsg_seq);
                assertEquals(localAddr.getPortId(), hdr.nlmsg_pid);

                neighMessageCount++;
            }
        }

        assertEquals(1, doneMessageCount);
        // TODO: make sure this test passes sanely in airplane mode.
        assertTrue(neighMessageCount > 0);

        IoUtils.closeQuietly(fd);
    }

    @Test
    public void testBasicWorkingGetAddrQuery() throws Exception {
        final FileDescriptor fd = NetlinkUtils.netlinkSocketForProto(NETLINK_ROUTE);
        assertNotNull(fd);

        NetlinkUtils.connectToKernel(fd);

        final NetlinkSocketAddress localAddr = (NetlinkSocketAddress) Os.getsockname(fd);
        assertNotNull(localAddr);
        assertEquals(0, localAddr.getGroupsMask());
        assertTrue(0 != localAddr.getPortId());

        final int testSeqno = 8;
        final byte[] req = newGetAddrRequest(testSeqno);
        assertNotNull(req);

        final long timeout = 500;
        assertEquals(req.length, NetlinkUtils.sendMessage(fd, req, 0, req.length, timeout));

        int addrMessageCount = 0;

        while (true) {
            ByteBuffer response = NetlinkUtils.recvMessage(fd, DEFAULT_RECV_BUFSIZE, timeout);
            assertNotNull(response);
            assertTrue(StructNlMsgHdr.STRUCT_SIZE <= response.limit());
            assertEquals(0, response.position());
            assertEquals(ByteOrder.nativeOrder(), response.order());

            final NetlinkMessage msg = NetlinkMessage.parse(response, NETLINK_ROUTE);
            assertNotNull(msg);
            final StructNlMsgHdr nlmsghdr = msg.getHeader();
            assertNotNull(nlmsghdr);

            if (nlmsghdr.nlmsg_type == NetlinkConstants.NLMSG_DONE) {
                break;
            }

            assertEquals(NetlinkConstants.RTM_NEWADDR, nlmsghdr.nlmsg_type);
            assertTrue((nlmsghdr.nlmsg_flags & StructNlMsgHdr.NLM_F_MULTI) != 0);
            assertEquals(testSeqno, nlmsghdr.nlmsg_seq);
            assertEquals(localAddr.getPortId(), nlmsghdr.nlmsg_pid);
            assertTrue(msg instanceof RtNetlinkAddressMessage);
            addrMessageCount++;

            // From the query response we can see the RTM_NEWADDR messages representing for IPv4
            // and IPv6 loopback address: 127.0.0.1 and ::1.
            final StructIfaddrMsg ifaMsg = ((RtNetlinkAddressMessage) msg).getIfaddrHeader();
            final InetAddress ipAddress = ((RtNetlinkAddressMessage) msg).getIpAddress();
            assertTrue(
                    "Non-IP address family: " + ifaMsg.family,
                    ifaMsg.family == AF_INET || ifaMsg.family == AF_INET6);
            assertTrue(ipAddress.isLoopbackAddress());
        }

        assertTrue(addrMessageCount > 0);

        IoUtils.closeQuietly(fd);
    }

    /** A convenience method to create an RTM_GETADDR request message. */
    private static byte[] newGetAddrRequest(int seqNo) {
        final int length = StructNlMsgHdr.STRUCT_SIZE + Struct.getSize(StructIfaddrMsg.class);
        final byte[] bytes = new byte[length];
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());

        final StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
        nlmsghdr.nlmsg_len = length;
        nlmsghdr.nlmsg_type = NetlinkConstants.RTM_GETADDR;
        nlmsghdr.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
        nlmsghdr.nlmsg_seq = seqNo;
        nlmsghdr.pack(byteBuffer);

        final StructIfaddrMsg addrMsg = new StructIfaddrMsg((byte) AF_UNSPEC /* family */,
                (short) 0 /* prefixLen */, (short) 0 /* flags */, (short) 0 /* scope */,
                0 /* index */);
        addrMsg.pack(byteBuffer);

        return bytes;
    }
}
