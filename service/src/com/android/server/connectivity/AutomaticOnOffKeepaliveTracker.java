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

package com.android.server.connectivity;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_SNDTIMEO;

import static com.android.net.module.util.netlink.NetlinkConstants.NLMSG_DONE;
import static com.android.net.module.util.netlink.NetlinkConstants.SOCKDIAG_MSG_HEADER_SIZE;
import static com.android.net.module.util.netlink.NetlinkConstants.SOCK_DIAG_BY_FAMILY;
import static com.android.net.module.util.netlink.NetlinkUtils.IO_TIMEOUT_MS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.INetd;
import android.net.ISocketKeepaliveCallback;
import android.net.MarkMaskParcel;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.net.module.util.HexDump;
import com.android.net.module.util.SocketUtils;
import com.android.net.module.util.netlink.InetDiagMessage;
import com.android.net.module.util.netlink.NetlinkUtils;
import com.android.net.module.util.netlink.StructNlAttr;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Manages automatic on/off socket keepalive requests.
 *
 * Provides methods to stop and start automatic keepalive requests, and keeps track of keepalives
 * across all networks. For non-automatic on/off keepalive request, this class bypass the requests
 * and send to KeepaliveTrakcer. This class is tightly coupled to ConnectivityService. It is not
 * thread-safe and its handle* methods must be called only from the ConnectivityService handler
 * thread.
 */
public class AutomaticOnOffKeepaliveTracker {
    private static final String TAG = "AutomaticOnOffKeepaliveTracker";
    private static final int[] ADDRESS_FAMILIES = new int[] {AF_INET6, AF_INET};

    @NonNull
    private final Handler mConnectivityServiceHandler;
    @NonNull
    private final KeepaliveTracker mKeepaliveTracker;
    @NonNull
    private final Context mContext;

    /**
     * The {@code inetDiagReqV2} messages for different IP family.
     *
     *   Key: Ip family type.
     * Value: Bytes array represent the {@code inetDiagReqV2}.
     *
     * This should only be accessed in the connectivity service handler thread.
     */
    private final SparseArray<byte[]> mSockDiagMsg = new SparseArray<>();
    private final Dependencies mDependencies;
    private final INetd mNetd;

    public AutomaticOnOffKeepaliveTracker(Context context, Handler handler) {
        this(context, handler, new Dependencies(context));
    }

    @VisibleForTesting
    public AutomaticOnOffKeepaliveTracker(@NonNull Context context, @NonNull Handler handler,
            @NonNull Dependencies dependencies) {
        mContext = Objects.requireNonNull(context);
        mDependencies = dependencies;
        this.mConnectivityServiceHandler = Objects.requireNonNull(handler);
        mNetd = mDependencies.getNetd();
        mKeepaliveTracker = mDependencies.newKeepaliveTracker(
                mContext, mConnectivityServiceHandler);
    }

    /**
     * Handle keepalive events from lower layer.
     */
    public void handleEventSocketKeepalive(@NonNull NetworkAgentInfo nai, int slot, int reason) {
        mKeepaliveTracker.handleEventSocketKeepalive(nai, slot, reason);
    }

    /**
     * Handle stop all keepalives on the specific network.
     */
    public void handleStopAllKeepalives(NetworkAgentInfo nai, int reason) {
        mKeepaliveTracker.handleStopAllKeepalives(nai, reason);
    }

    /**
     *  Handle start keepalives with the message.
     *
     *  The message is expected to be a KeepaliveTracker.KeepaliveInfo.
     */
    public void handleStartKeepalive(Message message) {
        mKeepaliveTracker.handleStartKeepalive(message);
    }

    /**
     * Handle stop keepalives on the specific network with given slot.
     */
    public void handleStopKeepalive(NetworkAgentInfo nai, int slot, int reason) {
        mKeepaliveTracker.handleStopKeepalive(nai, slot, reason);
    }

    /**
     * Called when requesting that keepalives be started on a IPsec NAT-T socket. See
     * {@link android.net.SocketKeepalive}.
     **/
    public void startNattKeepalive(@Nullable NetworkAgentInfo nai,
            @Nullable FileDescriptor fd,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb,
            @NonNull String srcAddrString,
            int srcPort,
            @NonNull String dstAddrString,
            int dstPort) {
        mKeepaliveTracker.startNattKeepalive(nai, fd, intervalSeconds, cb, srcAddrString,
                srcPort, dstAddrString, dstPort);
    }

    /**
     * Called when requesting that keepalives be started on a IPsec NAT-T socket. See
     * {@link android.net.SocketKeepalive}.
     **/
    public void startNattKeepalive(@Nullable NetworkAgentInfo nai,
            @Nullable FileDescriptor fd,
            int resourceId,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb,
            @NonNull String srcAddrString,
            @NonNull String dstAddrString,
            int dstPort) {
        mKeepaliveTracker.startNattKeepalive(nai, fd, resourceId, intervalSeconds, cb,
                srcAddrString, dstAddrString, dstPort);
    }

    /**
     * Called by ConnectivityService to start TCP keepalive on a file descriptor.
     *
     * In order to offload keepalive for application correctly, sequence number, ack number and
     * other fields are needed to form the keepalive packet. Thus, this function synchronously
     * puts the socket into repair mode to get the necessary information. After the socket has been
     * put into repair mode, the application cannot access the socket until reverted to normal.
     *
     * See {@link android.net.SocketKeepalive}.
     **/
    public void startTcpKeepalive(@Nullable NetworkAgentInfo nai,
            @NonNull FileDescriptor fd,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb) {
        mKeepaliveTracker.startTcpKeepalive(nai, fd, intervalSeconds, cb);
    }

    /**
     * Dump AutomaticOnOffKeepaliveTracker state.
     */
    public void dump(IndentingPrintWriter pw) {
        // TODO:  Dump the necessary information for automatic on/off keepalive.
        mKeepaliveTracker.dump(pw);
    }

    /**
     * Check all keeplaives on the network are still valid.
     */
    public void handleCheckKeepalivesStillValid(NetworkAgentInfo nai) {
        mKeepaliveTracker.handleCheckKeepalivesStillValid(nai);
    }

    @VisibleForTesting
    boolean isAnyTcpSocketConnected(int netId) {
        FileDescriptor fd = null;

        try {
            fd = mDependencies.createConnectedNetlinkSocket();

            // Get network mask
            final MarkMaskParcel parcel = mNetd.getFwmarkForNetwork(netId);
            final int networkMark = (parcel != null) ? parcel.mark : NetlinkUtils.UNKNOWN_MARK;
            final int networkMask = (parcel != null) ? parcel.mask : NetlinkUtils.NULL_MASK;

            // Send request for each IP family
            for (final int family : ADDRESS_FAMILIES) {
                if (isAnyTcpSocketConnectedForFamily(fd, family, networkMark, networkMask)) {
                    return true;
                }
            }
        } catch (ErrnoException | SocketException | InterruptedIOException | RemoteException e) {
            Log.e(TAG, "Fail to get socket info via netlink.", e);
        } finally {
            SocketUtils.closeSocketQuietly(fd);
        }

        return false;
    }

    private boolean isAnyTcpSocketConnectedForFamily(FileDescriptor fd, int family, int networkMark,
            int networkMask) throws ErrnoException, InterruptedIOException {
        ensureRunningOnHandlerThread();
        // Build SocketDiag messages and cache it.
        if (mSockDiagMsg.get(family) == null) {
            mSockDiagMsg.put(family, InetDiagMessage.buildInetDiagReqForAliveTcpSockets(family));
        }
        mDependencies.sendRequest(fd, mSockDiagMsg.get(family));

        // Iteration limitation as a protection to avoid possible infinite loops.
        // DEFAULT_RECV_BUFSIZE could read more than 20 sockets per time. Max iteration
        // should be enough to go through reasonable TCP sockets in the device.
        final int maxIteration = 100;
        int parsingIteration = 0;
        while (parsingIteration < maxIteration) {
            final ByteBuffer bytes = mDependencies.recvSockDiagResponse(fd);

            try {
                while (NetlinkUtils.enoughBytesRemainForValidNlMsg(bytes)) {
                    final int startPos = bytes.position();

                    final int nlmsgLen = bytes.getInt();
                    final int nlmsgType = bytes.getShort();
                    if (isEndOfMessageOrError(nlmsgType)) return false;
                    // TODO: Parse InetDiagMessage to get uid and dst address information to filter
                    //  socket via NetlinkMessage.parse.

                    // Skip the header to move to data part.
                    bytes.position(startPos + SOCKDIAG_MSG_HEADER_SIZE);

                    if (isTargetTcpSocket(bytes, nlmsgLen, networkMark, networkMask)) {
                        return true;
                    }
                }
            } catch (BufferUnderflowException e) {
                // The exception happens in random place in either header position or any data
                // position. Partial bytes from the middle of the byte buffer may not be enough to
                // clarify, so print out the content before the error to possibly prevent printing
                // the whole 8K buffer.
                final int exceptionPos = bytes.position();
                final String hex = HexDump.dumpHexString(bytes.array(), 0, exceptionPos);
                Log.e(TAG, "Unexpected socket info parsing: " + hex, e);
            }

            parsingIteration++;
        }
        return false;
    }

    private boolean isEndOfMessageOrError(int nlmsgType) {
        return nlmsgType == NLMSG_DONE || nlmsgType != SOCK_DIAG_BY_FAMILY;
    }

    private boolean isTargetTcpSocket(@NonNull ByteBuffer bytes, int nlmsgLen, int networkMark,
            int networkMask) {
        final int mark = readSocketDataAndReturnMark(bytes, nlmsgLen);
        return (mark & networkMask) == networkMark;
    }

    private int readSocketDataAndReturnMark(@NonNull ByteBuffer bytes, int nlmsgLen) {
        final int nextMsgOffset = bytes.position() + nlmsgLen - SOCKDIAG_MSG_HEADER_SIZE;
        int mark = NetlinkUtils.INIT_MARK_VALUE;
        // Get socket mark
        // TODO: Add a parsing method in NetlinkMessage.parse to support this to skip the remaining
        //  data.
        while (bytes.position() < nextMsgOffset) {
            final StructNlAttr nlattr = StructNlAttr.parse(bytes);
            if (nlattr != null && nlattr.nla_type == NetlinkUtils.INET_DIAG_MARK) {
                mark = nlattr.getValueAsInteger();
            }
        }
        return mark;
    }

    private void ensureRunningOnHandlerThread() {
        if (mConnectivityServiceHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on handler thread: " + Thread.currentThread().getName());
        }
    }

    /**
     * Dependencies class for testing.
     */
    @VisibleForTesting
    public static class Dependencies {
        private final Context mContext;

        public Dependencies(final Context context) {
            mContext = context;
        }

        /**
         * Create a netlink socket connected to the kernel.
         *
         * @return fd the fileDescriptor of the socket.
         */
        public FileDescriptor createConnectedNetlinkSocket()
                throws ErrnoException, SocketException {
            final FileDescriptor fd = NetlinkUtils.createNetLinkInetDiagSocket();
            NetlinkUtils.connectSocketToNetlink(fd);
            Os.setsockoptTimeval(fd, SOL_SOCKET, SO_SNDTIMEO,
                    StructTimeval.fromMillis(IO_TIMEOUT_MS));
            return fd;
        }

        /**
         * Send composed message request to kernel.
         *
         * The given FileDescriptor is expected to be created by
         * {@link #createConnectedNetlinkSocket} or equivalent way.
         *
         * @param fd a netlink socket {@code FileDescriptor} connected to the kernel.
         * @param msg the byte array representing the request message to write to kernel.
         */
        public void sendRequest(@NonNull final FileDescriptor fd,
                @NonNull final byte[] msg)
                throws ErrnoException, InterruptedIOException {
            Os.write(fd, msg, 0 /* byteOffset */, msg.length);
        }

        /**
         * Get an INetd connector.
         */
        public INetd getNetd() {
            return INetd.Stub.asInterface(
                    (IBinder) mContext.getSystemService(Context.NETD_SERVICE));
        }

        /**
         * Receive the response message from kernel via given {@code FileDescriptor}.
         * The usage should follow the {@code #sendRequest} call with the same
         * FileDescriptor.
         *
         * The overall response may be large but the individual messages should not be
         * excessively large(8-16kB) because trying to get the kernel to return
         * everything in one big buffer is inefficient as it forces the kernel to allocate
         * large chunks of linearly physically contiguous memory. The usage should iterate the
         * call of this method until the end of the overall message.
         *
         * The default receiving buffer size should be small enough that it is always
         * processed within the {@link NetlinkUtils#IO_TIMEOUT_MS} timeout.
         */
        public ByteBuffer recvSockDiagResponse(@NonNull final FileDescriptor fd)
                throws ErrnoException, InterruptedIOException {
            return NetlinkUtils.recvMessage(
                    fd, NetlinkUtils.DEFAULT_RECV_BUFSIZE, NetlinkUtils.IO_TIMEOUT_MS);
        }

        /**
         * Construct a new KeepaliveTracker.
         */
        public KeepaliveTracker newKeepaliveTracker(@NonNull Context context,
                @NonNull Handler connectivityserviceHander) {
            return new KeepaliveTracker(mContext, connectivityserviceHander);
        }
    }
}
