/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.system.OsConstants.ENOENT;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_SNDTIMEO;

import android.annotation.NonNull;
import android.annotation.TargetApi;
import android.net.Network;
import android.net.NetworkUtils;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.HandlerUtils;
import com.android.net.module.util.SharedLog;
import com.android.net.module.util.SkDestroyListener;
import com.android.net.module.util.netlink.InetDiagMessage;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@TargetApi(Build.VERSION_CODES.S)
public class QuicConnectionCloser {
    private static final String TAG = QuicConnectionCloser.class.getSimpleName();

    private static final int SOCKET_WRITE_TIMEOUT_MS = 100;

    // Map from socket cookie to QUIC connection close information
    private final Map<Long, QuicConnectionCloseInfo>
            mRegisteredQuicConnectionCloseInfos = new ArrayMap<>();

    // The maximum number of QUIC connection close information entries that can be registered
    // concurrently.
    private static final int MAX_REGISTERED_QUIC_CONNECTION_CLOSE_INFO = 1000;

    private final Handler mHandler;

    // Reference to ConnectivityService#mNetworkForNetId, must be synchronized on itself.
    // mHandler in this class is associated with the ConnectivityService handler thread.
    // The only code that can update mNetworkForNetId runs on that thread, so no entries can be
    // updated while the code in this class is running on mHandler thread.
    @GuardedBy("mNetworkForNetId")
    private final SparseArray<NetworkAgentInfo> mNetworkForNetId;

    @NonNull
    private final Dependencies mDeps;

    /**
     * Class to store the necessary information for closing a QUIC connection.
     */
    private static class QuicConnectionCloseInfo {
        public final int uid;
        public final int netId;
        public final long cookie;
        public final InetSocketAddress src;
        public final InetSocketAddress dst;
        public final byte[] payload;

        QuicConnectionCloseInfo(int uid, int netId, long cookie, InetSocketAddress src,
                InetSocketAddress dst, byte[] payload) {
            this.uid = uid;
            this.netId = netId;
            this.cookie = cookie;
            this.src = src;
            this.dst = dst;
            this.payload = payload;
        }

        @Override
        public String toString() {
            return "QuicConnectionCloseInfo{"
                    + "uid: " + uid
                    + ", netId: " + netId
                    + ", cookie: " + cookie
                    + ", src: " + src
                    + ", dst: " + dst
                    + ", payload length: " + payload.length
                    + "}";
        }
    }

    public static class Dependencies {
        /**
         * Send a UDP packet with the specified source and destination address and port over the
         * specified network.
         *
         * @param network The {@link Network} over which the UDP packet will be sent.
         * @param src     The source {@link InetSocketAddress} of the UDP packet to be sent.
         * @param dst     The destination {@link InetSocketAddress} of the UDP packet to be sent.
         * @param payload The UDP payload to be sent.
         */
        public void sendQuicConnectionClosePayload(final Network network,
                final InetSocketAddress src, final InetSocketAddress dst, final byte[] payload)
                throws IOException, ErrnoException {
            final DatagramSocket socket = new DatagramSocket(src);
            network.bindSocket(socket);
            socket.connect(dst);
            Os.setsockoptTimeval(socket.getFileDescriptor$(), SOL_SOCKET, SO_SNDTIMEO,
                    StructTimeval.fromMillis(SOCKET_WRITE_TIMEOUT_MS));
            Os.write(socket.getFileDescriptor$(), payload, 0 /* byteOffset */, payload.length);
        }

        /**
         * Call {@link InetDiagMessage#destroyUdpSocket}
         */
        public void destroyUdpSocket(final InetSocketAddress src, final InetSocketAddress dst,
                final long cookie)
                throws SocketException, InterruptedIOException, ErrnoException {
            InetDiagMessage.destroyUdpSocket(src, dst, cookie);
        }

        /**
         * Call {@link SkDestroyListener#makeSkDestroyListener}
         */
        public SkDestroyListener makeSkDestroyListener(final Consumer<InetDiagMessage> consumer,
                final Handler handler) {
            return SkDestroyListener.makeSkDestroyListener(consumer, false /* monitorTcpSocket */,
                    true /* monitorUdpSocket */, handler, new SharedLog(TAG));
        }

        /**
         * Call {@link NetworkUtils#getSocketCookie}
         */
        public long getSocketCookie(final FileDescriptor fd) throws ErrnoException {
            return NetworkUtils.getSocketCookie(fd);
        }

        /**
         * Call {@link Os#getsockoptInt}
         */
        public int getsockoptInt(final FileDescriptor fd, final int level, final int option)
                throws ErrnoException {
            return Os.getsockoptInt(fd, level, option);
        }

        /**
         * Call {@link Os#getsockname}}
         */
        public InetSocketAddress getsockname(final FileDescriptor fd) throws ErrnoException {
            return (InetSocketAddress) Os.getsockname(fd);
        }

        /**
         * Call {@link Os#getpeername}
         */
        public InetSocketAddress getpeername(final FileDescriptor fd) throws ErrnoException {
            return (InetSocketAddress) Os.getpeername(fd);
        }
    }

    public QuicConnectionCloser(final SparseArray<NetworkAgentInfo> networkForNetId,
            final Handler handler) {
        this(networkForNetId, handler, new Dependencies());
    }

    @VisibleForTesting
    public QuicConnectionCloser(final SparseArray<NetworkAgentInfo> networkForNetId,
            final Handler handler, final Dependencies deps) {
        mNetworkForNetId = networkForNetId;
        mHandler = handler;
        mDeps = deps;

        // handleUdpSocketDestroy must be posted to the thread to avoid racing with
        // handleUnregisterQuicConnectionCloseInfo, even though they both run on the same thread.
        // Specifically, the following can happen:
        // unregisterQuicConnectionClosePayload posts handleUnregisterQuicConnectionCloseInfo to
        // the handler and then closes the fd.
        // The close() will cause the kernel to enqueue a netlink message to the SkDestroyHandler's
        // fd.
        // It's possible that the MessageQueue of the handler sees both the SkDestroyHandler's fd
        // and the MessageQueue fd go active at the same time, and chooses to run the netlink
        // event first.
        // As a result, handleUdpSocketDestroy runs before handleUnregisterQuicConnectionCloseInfo,
        // and the code incorrectly sends a close packet for a socket that the app has already
        // unregistered.
        // Posting handleUdpSocketDestroy to the handler ensures that it always runs after
        // handleUnregisterQuicConnectionCloseInfo. It doesn't matter if the
        // handleUdpSocketDestroy is delayed, because it will only send a packet if
        // mRegisteredQuicConnectionCloseInfos contains the socket cookie, and socket cookies are
        // never reused.
        final SkDestroyListener udpSkDestroyListener = mDeps.makeSkDestroyListener(
                (inetDiagMessage) -> handler.post(() -> handleUdpSocketDestroy(inetDiagMessage)),
                handler);
        handler.post(udpSkDestroyListener::start);
    }

    private void ensureRunningOnHandlerThread() {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);
    }

    /**
     * Close registered QUIC connection by uids
     *
     * @param uids target uids to close QUIC connections
     */
    public void closeQuicConnectionByUids(final Set<Integer> uids) {
        ensureRunningOnHandlerThread();

        for (Iterator<Map.Entry<Long, QuicConnectionCloseInfo>> it =
                mRegisteredQuicConnectionCloseInfos.entrySet().iterator(); it.hasNext();) {
            final QuicConnectionCloseInfo info = it.next().getValue();
            if (uids.contains(info.uid)) {
                closeQuicConnection(info, true /* destroySocket */);
                it.remove();
            }
        }
    }

    private NetworkAgentInfo getNetworkAgentInfoForNetId(int netId) {
        synchronized (mNetworkForNetId) {
            return mNetworkForNetId.get(netId);
        }
    }

    private void closeQuicConnection(final QuicConnectionCloseInfo info,
            final boolean destroySocket) {
        ensureRunningOnHandlerThread();

        Log.d(TAG, "Close QUIC socket for " + info + ", destroySocket=" + destroySocket);
        final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(info.netId);
        if (nai == null) return; // The network has gone away already

        final boolean isLoopbackConnection = info.dst.getAddress().isLoopbackAddress();
        final boolean lpContainsSourceAddress =
                nai.linkProperties.getAddresses().contains(info.src.getAddress());
        if (!isLoopbackConnection && !lpContainsSourceAddress) {
            // The device should not send a packet with an unused source address.
            // Loopback connections are closed exceptionally to simplify device local testing.
            return;
        }

        if (destroySocket) {
            try {
                mDeps.destroyUdpSocket(info.src, info.dst, info.cookie);
            } catch (ErrnoException | SocketException | InterruptedIOException e) {
                if (e instanceof ErrnoException && ((ErrnoException) e).errno == ENOENT) {
                    // This can happen if the socket is already closed, but unregister message is
                    // not processed yet.
                    return;
                }
                Log.e(TAG, "Failed to destroy QUIC socket for " + info + ": " + e);
                return;
            }
        }

        try {
            mDeps.sendQuicConnectionClosePayload(nai.network(), info.src, info.dst, info.payload);
        } catch (ErrnoException | IOException e) {
            Log.e(TAG, "Failed to send registered QUIC connection close payload for "
                    + info + ": " + e);
        }
    }

    /**
     * Close QUIC connection if the registered socket was destroyed
     *
     * @param inetDiagMessage {@link InetDiagMessage} received from kernel
     */
    private void handleUdpSocketDestroy(final InetDiagMessage inetDiagMessage) {
        ensureRunningOnHandlerThread();

        final long cookie = inetDiagMessage.inetDiagMsg.id.cookie;
        final QuicConnectionCloseInfo info = mRegisteredQuicConnectionCloseInfos.remove(cookie);
        if (info == null) {
            // Destroyed socket is not registered or already unregistered.
            return;
        }

        // App registered a QUIC connection close payload and this socket, but the socket was
        // closed before the socket was unregistered.
        // This can happen if the app crashes or is killed.
        closeQuicConnection(info, false /* destroySocket */);
    }

    /**
     * Register the QUIC connection close information
     *
     * @param info {@link QuicConnectionCloseInfo} to register
     */
    private void handleRegisterQuicConnectionCloseInfo(final QuicConnectionCloseInfo info) {
        ensureRunningOnHandlerThread();

        if (mRegisteredQuicConnectionCloseInfos.size()
                >= MAX_REGISTERED_QUIC_CONNECTION_CLOSE_INFO) {
            Log.e(TAG, "Failed to register QUIC connection close information."
                    + " number of registered information exceeded "
                    + MAX_REGISTERED_QUIC_CONNECTION_CLOSE_INFO);
            return;
        }
        mRegisteredQuicConnectionCloseInfos.put(info.cookie, info);
    }

    // TODO: Use OsConstants.SO_MARK once this API is available
    private static final int SO_MARK = 36;

    /**
     * Register QUIC socket and connection close payload
     *
     * @param uid The uid of the socket owner
     * @param pfd The {@link ParcelFileDescriptor} for the connected UDP socket.
     * @param payload The UDP payload that can close QUIC connection.
     */
    public void registerQuicConnectionClosePayload(final int uid, final ParcelFileDescriptor pfd,
            final byte[] payload) {
        try {
            final FileDescriptor fd = pfd.getFileDescriptor();
            // See FWMARK_NET_ID_MASK in Fwmark.h
            final int netId = mDeps.getsockoptInt(fd, SOL_SOCKET, SO_MARK) & 0xffff;
            // The peer name could change if the socket is reconnected to a different server.
            // But, it is the caller's responsibility to avoid this, as explicitly stated in the
            // Javadoc of ConnectivityManager#registerQuicConnectionClosePayload.
            final QuicConnectionCloseInfo info = new QuicConnectionCloseInfo(
                    uid,
                    netId,
                    mDeps.getSocketCookie(fd),
                    mDeps.getsockname(fd),
                    mDeps.getpeername(fd),
                    payload);
            mHandler.post(() -> handleRegisterQuicConnectionCloseInfo(info));
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to register QUIC connection close information", e);
        } finally {
            // |pfd| must remain open until the message is posted to the handler thread, because
            // doing so ensures that the socket is not closed even if the app calls close().
            // Keeping it open avoids the following threading issue with SkDestroyListener :
            // If the PFD was closed before posting the message, then SkDestroyListener might
            // get a "socket destroyed" message from Netlink immediately and post to the handler
            // thread before this method posts its own message, resulting in a leak of the
            // mRegisteredQuicConnectionCloseInfo entry.
            // While the PFD is held, Netlink will not send the socket destroy message to
            // SkDestroyListener, and thus the message posted by SkDestroyListener as
            // a reaction to it will be processed after the message posted by this method.
            IoUtils.closeQuietly(pfd);
        }
    }

    /**
     * Unregister the QUIC connection close information
     *
     * @param cookie cookie of the socket whose connection close information should be unregistered
     */
    private void handleUnregisterQuicConnectionCloseInfo(final long cookie) {
        ensureRunningOnHandlerThread();

        mRegisteredQuicConnectionCloseInfos.remove(cookie);
    }

    /**
     * Unregister the QUIC socket
     *
     * @param pfd The {@link ParcelFileDescriptor} for the UDP socket.
     */
    public void unregisterQuicConnectionClosePayload(final ParcelFileDescriptor pfd) {
        try {
            final long cookie = mDeps.getSocketCookie(pfd.getFileDescriptor());
            mHandler.post(() -> handleUnregisterQuicConnectionCloseInfo(cookie));
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to unregister QUIC connection close information: " + e);
        } finally {
            // |pfd| must be closed after posting the message to the handler thread to avoid the
            // threading issue with SkDestroyListener.
            // See the comment in registerQuicConnectionClosePayload.
            IoUtils.closeQuietly(pfd);
        }
    }

    /**
     * Dump QUIC connection closer information
     */
    public void dump(final IndentingPrintWriter pw) {
        pw.println("Registered QUIC connection close information: "
                + mRegisteredQuicConnectionCloseInfos.size());
        pw.increaseIndent();
        for (QuicConnectionCloseInfo info: mRegisteredQuicConnectionCloseInfos.values()) {
            pw.println(info);
        }
        pw.decreaseIndent();
    }
}
