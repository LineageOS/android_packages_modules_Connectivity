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

package com.android.net.module.util.netlink;

import static android.net.util.SocketUtils.makeNetlinkSocketAddress;
import static android.system.OsConstants.AF_NETLINK;
import static android.system.OsConstants.EIO;
import static android.system.OsConstants.EPROTO;
import static android.system.OsConstants.ETIMEDOUT;
import static android.system.OsConstants.NETLINK_INET_DIAG;
import static android.system.OsConstants.NETLINK_ROUTE;
import static android.system.OsConstants.SOCK_CLOEXEC;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_RCVBUF;
import static android.system.OsConstants.SO_RCVTIMEO;
import static android.system.OsConstants.SO_SNDTIMEO;

import android.net.util.SocketUtils;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Utilities for netlink related class that may not be able to fit into a specific class.
 * @hide
 */
public class NetlinkUtils {
    private static final String TAG = "NetlinkUtils";
    /** Corresponds to enum from bionic/libc/include/netinet/tcp.h. */
    private static final int TCP_ESTABLISHED = 1;
    private static final int TCP_SYN_SENT = 2;
    private static final int TCP_SYN_RECV = 3;

    public static final int TCP_ALIVE_STATE_FILTER =
            (1 << TCP_ESTABLISHED) | (1 << TCP_SYN_SENT) | (1 << TCP_SYN_RECV);

    public static final int UNKNOWN_MARK = 0xffffffff;
    public static final int NULL_MASK = 0;

    // Initial mark value corresponds to the initValue in system/netd/include/Fwmark.h.
    public static final int INIT_MARK_VALUE = 0;
    // Corresponds to enum definition in bionic/libc/kernel/uapi/linux/inet_diag.h
    public static final int INET_DIAG_INFO = 2;
    public static final int INET_DIAG_MARK = 15;

    public static final long IO_TIMEOUT_MS = 300L;

    public static final int DEFAULT_RECV_BUFSIZE = 8 * 1024;
    public static final int SOCKET_RECV_BUFSIZE = 64 * 1024;

    /**
     * Return whether the input ByteBuffer contains enough remaining bytes for
     * {@code StructNlMsgHdr}.
     */
    public static boolean enoughBytesRemainForValidNlMsg(@NonNull final ByteBuffer bytes) {
        return bytes.remaining() >= StructNlMsgHdr.STRUCT_SIZE;
    }

    /**
     * Parse netlink error message
     *
     * @param bytes byteBuffer to parse netlink error message
     * @return NetlinkErrorMessage if bytes contains valid NetlinkErrorMessage, else {@code null}
     */
    @Nullable
    private static NetlinkErrorMessage parseNetlinkErrorMessage(ByteBuffer bytes) {
        final StructNlMsgHdr nlmsghdr = StructNlMsgHdr.parse(bytes);
        if (nlmsghdr == null || nlmsghdr.nlmsg_type != NetlinkConstants.NLMSG_ERROR) {
            return null;
        }

        final int messageLength = NetlinkConstants.alignedLengthOf(nlmsghdr.nlmsg_len);
        final int payloadLength = messageLength - StructNlMsgHdr.STRUCT_SIZE;
        if (payloadLength < 0 || payloadLength > bytes.remaining()) {
            // Malformed message or runt buffer.  Pretend the buffer was consumed.
            bytes.position(bytes.limit());
            return null;
        }

        return NetlinkErrorMessage.parse(nlmsghdr, bytes);
    }

    /**
     * Receive netlink ack message and check error
     *
     * @param fd fd to read netlink message
     */
    public static void receiveNetlinkAck(final FileDescriptor fd)
            throws InterruptedIOException, ErrnoException {
        final ByteBuffer bytes = recvMessage(fd, DEFAULT_RECV_BUFSIZE, IO_TIMEOUT_MS);
        // recvMessage() guaranteed to not return null if it did not throw.
        final NetlinkErrorMessage response = parseNetlinkErrorMessage(bytes);
        if (response != null && response.getNlMsgError() != null) {
            final int errno = response.getNlMsgError().error;
            if (errno != 0) {
                // TODO: consider ignoring EINVAL (-22), which appears to be
                // normal when probing a neighbor for which the kernel does
                // not already have / no longer has a link layer address.
                Log.e(TAG, "receiveNetlinkAck, errmsg=" + response.toString());
                // Note: convert kernel errnos (negative) into userspace errnos (positive).
                throw new ErrnoException(response.toString(), Math.abs(errno));
            }
        } else {
            final String errmsg;
            if (response == null) {
                bytes.position(0);
                errmsg = "raw bytes: " + NetlinkConstants.hexify(bytes);
            } else {
                errmsg = response.toString();
            }
            Log.e(TAG, "receiveNetlinkAck, errmsg=" + errmsg);
            throw new ErrnoException(errmsg, EPROTO);
        }
    }

    /**
     * Send one netlink message to kernel via netlink socket.
     *
     * @param nlProto netlink protocol type.
     * @param msg the raw bytes of netlink message to be sent.
     */
    public static void sendOneShotKernelMessage(int nlProto, byte[] msg) throws ErrnoException {
        final String errPrefix = "Error in NetlinkSocket.sendOneShotKernelMessage";
        final FileDescriptor fd = netlinkSocketForProto(nlProto);

        try {
            connectToKernel(fd);
            sendMessage(fd, msg, 0, msg.length, IO_TIMEOUT_MS);
            receiveNetlinkAck(fd);
        } catch (InterruptedIOException e) {
            Log.e(TAG, errPrefix, e);
            throw new ErrnoException(errPrefix, ETIMEDOUT, e);
        } catch (SocketException e) {
            Log.e(TAG, errPrefix, e);
            throw new ErrnoException(errPrefix, EIO, e);
        } finally {
            try {
                SocketUtils.closeSocket(fd);
            } catch (IOException e) {
                // Nothing we can do here
            }
        }
    }

    /**
     * Send an RTM_NEWADDR message to kernel to add or update an IPv6 address.
     *
     * @param ifIndex interface index.
     * @param ip IPv6 address to be added.
     * @param prefixlen IPv6 address prefix length.
     * @param flags IPv6 address flags.
     * @param scope IPv6 address scope.
     * @param preferred The preferred lifetime of IPv6 address.
     * @param valid The valid lifetime of IPv6 address.
     */
    public static boolean sendRtmNewAddressRequest(int ifIndex, @NonNull final Inet6Address ip,
            short prefixlen, int flags, byte scope, long preferred, long valid) {
        Objects.requireNonNull(ip, "IPv6 address to be added should not be null.");
        final byte[] msg = RtNetlinkAddressMessage.newRtmNewAddressMessage(1 /* seqNo*/, ip,
                prefixlen, flags, scope, ifIndex, preferred, valid);
        try {
            NetlinkUtils.sendOneShotKernelMessage(NETLINK_ROUTE, msg);
            return true;
        } catch (ErrnoException e) {
            Log.e(TAG, "Fail to send RTM_NEWADDR to add " + ip.getHostAddress(), e);
            return false;
        }
    }

    /**
     * Send an RTM_DELADDR message to kernel to delete an IPv6 address.
     *
     * @param ifIndex interface index.
     * @param ip IPv6 address to be deleted.
     * @param prefixlen IPv6 address prefix length.
     */
    public static boolean sendRtmDelAddressRequest(int ifIndex, final Inet6Address ip,
            short prefixlen) {
        Objects.requireNonNull(ip, "IPv6 address to be deleted should not be null.");
        final byte[] msg = RtNetlinkAddressMessage.newRtmDelAddressMessage(1 /* seqNo*/, ip,
                prefixlen, ifIndex);
        try {
            NetlinkUtils.sendOneShotKernelMessage(NETLINK_ROUTE, msg);
            return true;
        } catch (ErrnoException e) {
            Log.e(TAG, "Fail to send RTM_DELADDR to delete " + ip.getHostAddress(), e);
            return false;
        }
    }

    /**
     * Create netlink socket with the given netlink protocol type.
     *
     * @return fd the fileDescriptor of the socket.
     * @throws ErrnoException if the FileDescriptor not connect to be created successfully
     */
    public static FileDescriptor netlinkSocketForProto(int nlProto) throws ErrnoException {
        final FileDescriptor fd = Os.socket(AF_NETLINK, SOCK_DGRAM, nlProto);
        Os.setsockoptInt(fd, SOL_SOCKET, SO_RCVBUF, SOCKET_RECV_BUFSIZE);
        return fd;
    }

    /**
     * Construct a netlink inet_diag socket.
     */
    public static FileDescriptor createNetLinkInetDiagSocket() throws ErrnoException {
        return Os.socket(AF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC, NETLINK_INET_DIAG);
    }

    /**
     * Connect the given file descriptor to the Netlink interface to the kernel.
     *
     * The fd must be a SOCK_DGRAM socket : create it with {@link #netlinkSocketForProto}
     *
     * @throws ErrnoException if the {@code fd} could not connect to kernel successfully
     * @throws SocketException if there is an error accessing a socket.
     */
    public static void connectToKernel(@NonNull FileDescriptor fd)
            throws ErrnoException, SocketException {
        Os.connect(fd, makeNetlinkSocketAddress(0, 0));
    }

    private static void checkTimeout(long timeoutMs) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Negative timeouts not permitted");
        }
    }

    /**
     * Wait up to |timeoutMs| (or until underlying socket error) for a
     * netlink message of at most |bufsize| size.
     *
     * Multi-threaded calls with different timeouts will cause unexpected results.
     */
    public static ByteBuffer recvMessage(FileDescriptor fd, int bufsize, long timeoutMs)
            throws ErrnoException, IllegalArgumentException, InterruptedIOException {
        checkTimeout(timeoutMs);

        Os.setsockoptTimeval(fd, SOL_SOCKET, SO_RCVTIMEO, StructTimeval.fromMillis(timeoutMs));

        final ByteBuffer byteBuffer = ByteBuffer.allocate(bufsize);
        final int length = Os.read(fd, byteBuffer);
        if (length == bufsize) {
            Log.w(TAG, "maximum read");
        }
        byteBuffer.position(0);
        byteBuffer.limit(length);
        byteBuffer.order(ByteOrder.nativeOrder());
        return byteBuffer;
    }

    /**
     * Send a message to a peer to which this socket has previously connected.
     *
     * This waits at most |timeoutMs| milliseconds for the send to complete, will get the exception
     * if it times out.
     */
    public static int sendMessage(
            FileDescriptor fd, byte[] bytes, int offset, int count, long timeoutMs)
            throws ErrnoException, IllegalArgumentException, InterruptedIOException {
        checkTimeout(timeoutMs);
        Os.setsockoptTimeval(fd, SOL_SOCKET, SO_SNDTIMEO, StructTimeval.fromMillis(timeoutMs));
        return Os.write(fd, bytes, offset, count);
    }

    private static final long CLOCK_TICKS_PER_SECOND = Os.sysconf(OsConstants._SC_CLK_TCK);

    /**
     * Convert the system time in clock ticks(clock_t type in times(), not in clock()) to
     * milliseconds. times() clock_t ticks at the kernel's USER_HZ (100) while clock() clock_t
     * ticks at CLOCKS_PER_SEC (1000000).
     *
     * See the NOTES on https://man7.org/linux/man-pages/man2/times.2.html for the difference
     * of clock_t used in clock() and times().
     */
    public static long ticksToMilliSeconds(int intClockTicks) {
        final long longClockTicks = intClockTicks & 0xffffffffL;
        return (longClockTicks * 1000) / CLOCK_TICKS_PER_SECOND;
    }

    private NetlinkUtils() {}
}
