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

import static android.net.NetworkAgent.CMD_START_SOCKET_KEEPALIVE;
import static android.net.SocketKeepalive.ERROR_INVALID_SOCKET;
import static android.net.SocketKeepalive.SUCCESS;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_SNDTIMEO;

import static com.android.net.module.util.netlink.NetlinkConstants.NLMSG_DONE;
import static com.android.net.module.util.netlink.NetlinkConstants.SOCKDIAG_MSG_HEADER_SIZE;
import static com.android.net.module.util.netlink.NetlinkConstants.SOCK_DIAG_BY_FAMILY;
import static com.android.net.module.util.netlink.NetlinkUtils.IO_TIMEOUT_MS;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.INetd;
import android.net.ISocketKeepaliveCallback;
import android.net.MarkMaskParcel;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.SocketKeepalive;
import android.net.SocketKeepalive.InvalidSocketException;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.HexDump;
import com.android.net.module.util.SocketUtils;
import com.android.net.module.util.netlink.InetDiagMessage;
import com.android.net.module.util.netlink.NetlinkUtils;
import com.android.net.module.util.netlink.StructNlAttr;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.SocketException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;

/**
 * Manages automatic on/off socket keepalive requests.
 *
 * Provides methods to stop and start automatic keepalive requests, and keeps track of keepalives
 * across all networks. For non-automatic on/off keepalive request, this class just forwards the
 * requests to KeepaliveTracker. This class is tightly coupled to ConnectivityService. It is not
 * thread-safe and its handle* methods must be called only from the ConnectivityService handler
 * thread.
 */
public class AutomaticOnOffKeepaliveTracker {
    private static final String TAG = "AutomaticOnOffKeepaliveTracker";
    private static final int[] ADDRESS_FAMILIES = new int[] {AF_INET6, AF_INET};
    private static final String ACTION_TCP_POLLING_ALARM =
            "com.android.server.connectivity.KeepaliveTracker.TCP_POLLING_ALARM";
    private static final String EXTRA_NETWORK = "network_id";
    private static final String EXTRA_SLOT = "slot";
    private static final long DEFAULT_TCP_POLLING_INTERVAL_MS = 120_000L;
    private static final String AUTOMATIC_ON_OFF_KEEPALIVE_VERSION =
            "automatic_on_off_keepalive_version";
    /**
     * States for {@code #AutomaticOnOffKeepalive}.
     *
     * A new AutomaticOnOffKeepalive starts with STATE_ENABLED. The system will monitor
     * the TCP sockets on VPN networks running on top of the specified network, and turn off
     * keepalive if there is no TCP socket any of the VPN networks. Conversely, it will turn
     * keepalive back on if any TCP socket is open on any of the VPN networks.
     *
     * When there is no TCP socket on any of the VPN networks, the state becomes STATE_SUSPENDED.
     * The {@link KeepaliveTracker.KeepaliveInfo} object is kept to remember the parameters so it
     * is possible to resume keepalive later with the same parameters.
     *
     * When the system detects some TCP socket is open on one of the VPNs while in STATE_SUSPENDED,
     * this AutomaticOnOffKeepalive goes to STATE_ENABLED again.
     *
     * When finishing keepalive, this object is deleted.
     */
    private static final int STATE_ENABLED = 0;
    private static final int STATE_SUSPENDED = 1;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "STATE_" }, value = {
            STATE_ENABLED,
            STATE_SUSPENDED
    })
    private @interface AutomaticOnOffState {}

    @NonNull
    private final Handler mConnectivityServiceHandler;
    @NonNull
    private final KeepaliveTracker mKeepaliveTracker;
    @NonNull
    private final Context mContext;
    @NonNull
    private final AlarmManager mAlarmManager;

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
    /**
     * Keeps track of automatic on/off keepalive requests.
     * This should be only updated in ConnectivityService handler thread.
     */
    private final ArrayList<AutomaticOnOffKeepalive> mAutomaticOnOffKeepalives = new ArrayList<>();

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_TCP_POLLING_ALARM.equals(intent.getAction())) {
                Log.d(TAG, "Received TCP polling intent");
                final Network network = intent.getParcelableExtra(EXTRA_NETWORK);
                final int slot = intent.getIntExtra(EXTRA_SLOT, -1);
                mConnectivityServiceHandler.obtainMessage(
                        NetworkAgent.CMD_MONITOR_AUTOMATIC_KEEPALIVE,
                        slot, 0 , network).sendToTarget();
            }
        }
    };

    private static class AutomaticOnOffKeepalive {
        @NonNull
        private final KeepaliveTracker.KeepaliveInfo mKi;
        @NonNull
        private final FileDescriptor mFd;
        @NonNull
        private final PendingIntent mTcpPollingAlarm;
        private final int mSlot;
        @AutomaticOnOffState
        private int mAutomaticOnOffState = STATE_ENABLED;

        AutomaticOnOffKeepalive(@NonNull KeepaliveTracker.KeepaliveInfo ki,
                @NonNull Context context) throws InvalidSocketException {
            this.mKi = Objects.requireNonNull(ki);
            // A null fd is acceptable in KeepaliveInfo for backward compatibility of
            // PacketKeepalive API, but it should not happen here because legacy API cannot setup
            // automatic keepalive.
            Objects.requireNonNull(ki.mFd);

            // Get the slot from keepalive because the slot information may be missing when the
            // keepalive is stopped.
            this.mSlot = ki.getSlot();
            try {
                this.mFd = Os.dup(ki.mFd);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot dup fd: ", e);
                throw new InvalidSocketException(ERROR_INVALID_SOCKET, e);
            }
            mTcpPollingAlarm = createTcpPollingAlarmIntent(
                    context, ki.getNai().network(), ki.getSlot());
        }

        public boolean match(Network network, int slot) {
            return this.mKi.getNai().network().equals(network) && this.mSlot == slot;
        }

        private static PendingIntent createTcpPollingAlarmIntent(@NonNull Context context,
                @NonNull Network network, int slot) {
            final Intent intent = new Intent(ACTION_TCP_POLLING_ALARM);
            intent.putExtra(EXTRA_NETWORK, network);
            intent.putExtra(EXTRA_SLOT, slot);
            return PendingIntent.getBroadcast(
                    context, 0 /* requestCode */, intent, PendingIntent.FLAG_IMMUTABLE);
        }
    }

    public AutomaticOnOffKeepaliveTracker(@NonNull Context context, @NonNull Handler handler) {
        this(context, handler, new Dependencies(context));
    }

    @VisibleForTesting
    public AutomaticOnOffKeepaliveTracker(@NonNull Context context, @NonNull Handler handler,
            @NonNull Dependencies dependencies) {
        mContext = Objects.requireNonNull(context);
        mDependencies = Objects.requireNonNull(dependencies);
        mConnectivityServiceHandler = Objects.requireNonNull(handler);
        mNetd = mDependencies.getNetd();
        mKeepaliveTracker = mDependencies.newKeepaliveTracker(
                mContext, mConnectivityServiceHandler);

        if (SdkLevel.isAtLeastU()) {
            mContext.registerReceiver(mReceiver, new IntentFilter(ACTION_TCP_POLLING_ALARM),
                    null, handler);
        }
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
    }

    private void startTcpPollingAlarm(@NonNull PendingIntent alarm) {
        final long triggerAtMillis =
                SystemClock.elapsedRealtime() + DEFAULT_TCP_POLLING_INTERVAL_MS;
        // Setup a non-wake up alarm.
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, triggerAtMillis, alarm);
    }

    /**
     * Determine if any state transition is needed for the specific automatic keepalive.
     */
    public void handleMonitorAutomaticKeepalive(@NonNull Network network, int slot, int vpnNetId) {
        final AutomaticOnOffKeepalive autoKi = findAutomaticOnOffKeepalive(network, slot);
        // This may happen if the keepalive is removed by the app, and the alarm is fired at the
        // same time.
        if (autoKi == null) return;

        handleMonitorTcpConnections(autoKi, vpnNetId);
    }

    /**
     * Determine if disable or re-enable keepalive is needed or not based on TCP sockets status.
     */
    private void handleMonitorTcpConnections(@NonNull AutomaticOnOffKeepalive ki, int vpnNetId) {
        if (!isAnyTcpSocketConnected(vpnNetId)) {
            // No TCP socket exists. Stop keepalive if ENABLED, and remain SUSPENDED if currently
            // SUSPENDED.
            if (ki.mAutomaticOnOffState == STATE_ENABLED) {
                ki.mAutomaticOnOffState = STATE_SUSPENDED;
                handleSuspendKeepalive(ki.mKi.mNai, ki.mSlot, SUCCESS);
            }
        } else {
            handleMaybeResumeKeepalive(ki);
        }
        // TODO: listen to socket status instead of periodically check.
        startTcpPollingAlarm(ki.mTcpPollingAlarm);
    }

    /**
     * Resume keepalive for this slot on this network, if it wasn't already resumed.
     */
    public void handleMaybeResumeKeepalive(@NonNull final Network network, final int slot) {
        final AutomaticOnOffKeepalive autoKi = findAutomaticOnOffKeepalive(network, slot);
        // This may happen if the keepalive is removed by the app, and the alarm is fired at
        // the same time.
        if (autoKi == null) return;
        handleMaybeResumeKeepalive(autoKi);
    }

    private void handleMaybeResumeKeepalive(@NonNull AutomaticOnOffKeepalive autoKi) {
        if (autoKi.mAutomaticOnOffState == STATE_ENABLED) return;
        KeepaliveTracker.KeepaliveInfo newKi;
        try {
            // Get fd from AutomaticOnOffKeepalive since the fd in the original
            // KeepaliveInfo should be closed.
            newKi = autoKi.mKi.withFd(autoKi.mFd);
        } catch (InvalidSocketException | IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Fail to construct keepalive", e);
            mKeepaliveTracker.notifyErrorCallback(autoKi.mKi.mCallback, ERROR_INVALID_SOCKET);
            return;
        }
        autoKi.mAutomaticOnOffState = STATE_ENABLED;
        handleResumeKeepalive(mConnectivityServiceHandler.obtainMessage(
                NetworkAgent.CMD_START_SOCKET_KEEPALIVE,
                autoKi.mAutomaticOnOffState, 0, newKi));
    }

    private int findAutomaticOnOffKeepaliveIndex(@NonNull Network network, int slot) {
        ensureRunningOnHandlerThread();

        int index = 0;
        for (AutomaticOnOffKeepalive ki : mAutomaticOnOffKeepalives) {
            if (ki.match(network, slot)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    @Nullable
    private AutomaticOnOffKeepalive findAutomaticOnOffKeepalive(@NonNull Network network,
            int slot) {
        ensureRunningOnHandlerThread();

        final int index = findAutomaticOnOffKeepaliveIndex(network, slot);
        return (index >= 0) ? mAutomaticOnOffKeepalives.get(index) : null;
    }

    /**
     * Handle keepalive events from lower layer.
     *
     * Forward to KeepaliveTracker.
     */
    public void handleEventSocketKeepalive(@NonNull NetworkAgentInfo nai, int slot, int reason) {
        mKeepaliveTracker.handleEventSocketKeepalive(nai, slot, reason);
    }

    /**
     * Handle stop all keepalives on the specific network.
     */
    public void handleStopAllKeepalives(NetworkAgentInfo nai, int reason) {
        mKeepaliveTracker.handleStopAllKeepalives(nai, reason);
        final Iterator<AutomaticOnOffKeepalive> iterator = mAutomaticOnOffKeepalives.iterator();
        while (iterator.hasNext()) {
            final AutomaticOnOffKeepalive autoKi = iterator.next();
            if (autoKi.mKi.getNai() == nai) {
                cleanupAutoOnOffKeepalive(autoKi);
                iterator.remove();
            }
        }
    }

    /**
     * Handle start keepalive contained within a message.
     *
     * The message is expected to contain a KeepaliveTracker.KeepaliveInfo.
     */
    public void handleStartKeepalive(Message message) {
        mKeepaliveTracker.handleStartKeepalive(message);

        // Add automatic on/off request into list to track its life cycle.
        final boolean automaticOnOff = message.arg1 != 0
                && mDependencies.isFeatureEnabled(AUTOMATIC_ON_OFF_KEEPALIVE_VERSION);
        if (automaticOnOff) {
            final KeepaliveTracker.KeepaliveInfo ki = (KeepaliveTracker.KeepaliveInfo) message.obj;
            AutomaticOnOffKeepalive autoKi;
            try {
                // CAREFUL : mKeepaliveTracker.handleStartKeepalive will assign |ki.mSlot| after
                // pulling |ki| from the message. The constructor below will read this member
                // (through ki.getSlot()) and therefore actively relies on handleStartKeepalive
                // having assigned this member before this is called.
                // TODO : clean this up by assigning the slot at the start of this method instead
                // and ideally removing the mSlot member from KeepaliveInfo.
                autoKi = new AutomaticOnOffKeepalive(ki, mContext);
            } catch (SocketKeepalive.InvalidSocketException | IllegalArgumentException e) {
                Log.e(TAG, "Fail to construct keepalive", e);
                mKeepaliveTracker.notifyErrorCallback(ki.mCallback, ERROR_INVALID_SOCKET);
                return;
            }
            mAutomaticOnOffKeepalives.add(autoKi);
            startTcpPollingAlarm(autoKi.mTcpPollingAlarm);
        }
    }

    private void handleResumeKeepalive(Message message) {
        mKeepaliveTracker.handleStartKeepalive(message);
    }

    private void handleSuspendKeepalive(NetworkAgentInfo nai, int slot, int reason) {
        mKeepaliveTracker.handleStopKeepalive(nai, slot, reason);
    }

    /**
     * Handle stop keepalives on the specific network with given slot.
     */
    public void handleStopKeepalive(NetworkAgentInfo nai, int slot, int reason) {
        final AutomaticOnOffKeepalive autoKi = findAutomaticOnOffKeepalive(nai.network, slot);

        // Let the original keepalive do the stop first, and then clean up the keepalive if it's an
        // automatic keepalive.
        if (autoKi == null || autoKi.mAutomaticOnOffState == STATE_ENABLED) {
            mKeepaliveTracker.handleStopKeepalive(nai, slot, reason);
        }

        // Not an AutomaticOnOffKeepalive.
        if (autoKi == null) return;

        cleanupAutoOnOffKeepalive(autoKi);
        mAutomaticOnOffKeepalives.remove(autoKi);
    }

    private void cleanupAutoOnOffKeepalive(@NonNull final AutomaticOnOffKeepalive autoKi) {
        ensureRunningOnHandlerThread();
        mAlarmManager.cancel(autoKi.mTcpPollingAlarm);
        // Close the duplicated fd that maintains the lifecycle of socket.
        FileUtils.closeQuietly(autoKi.mFd);
    }

    /**
     * Called when requesting that keepalives be started on a IPsec NAT-T socket. See
     * {@link android.net.SocketKeepalive}.
     *
     * Forward to KeepaliveTracker.
     **/
    public void startNattKeepalive(@Nullable NetworkAgentInfo nai,
            @Nullable FileDescriptor fd,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb,
            @NonNull String srcAddrString,
            int srcPort,
            @NonNull String dstAddrString,
            int dstPort, boolean automaticOnOffKeepalives) {
        final KeepaliveTracker.KeepaliveInfo ki = mKeepaliveTracker.makeNattKeepaliveInfo(nai, fd,
                intervalSeconds, cb, srcAddrString, srcPort, dstAddrString, dstPort);
        if (null != ki) {
            mConnectivityServiceHandler.obtainMessage(NetworkAgent.CMD_START_SOCKET_KEEPALIVE,
                    // TODO : move ConnectivityService#encodeBool to a static lib.
                    automaticOnOffKeepalives ? 1 : 0, 0, ki).sendToTarget();
        }
    }

    /**
     * Called when requesting that keepalives be started on a IPsec NAT-T socket. See
     * {@link android.net.SocketKeepalive}.
     *
     * Forward to KeepaliveTracker.
     **/
    public void startNattKeepalive(@Nullable NetworkAgentInfo nai,
            @Nullable FileDescriptor fd,
            int resourceId,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb,
            @NonNull String srcAddrString,
            @NonNull String dstAddrString,
            int dstPort,
            boolean automaticOnOffKeepalives) {
        final KeepaliveTracker.KeepaliveInfo ki = mKeepaliveTracker.makeNattKeepaliveInfo(nai, fd,
                resourceId, intervalSeconds, cb, srcAddrString, dstAddrString, dstPort);
        if (null != ki) {
            mConnectivityServiceHandler.obtainMessage(NetworkAgent.CMD_START_SOCKET_KEEPALIVE,
                    // TODO : move ConnectivityService#encodeBool to a static lib.
                    automaticOnOffKeepalives ? 1 : 0, 0, ki).sendToTarget();
        }
    }

    /**
     * Called by ConnectivityService to start TCP keepalive on a file descriptor.
     *
     * In order to offload keepalive for application correctly, sequence number, ack number and
     * other fields are needed to form the keepalive packet. Thus, this function synchronously
     * puts the socket into repair mode to get the necessary information. After the socket has been
     * put into repair mode, the application cannot access the socket until reverted to normal.
     * See {@link android.net.SocketKeepalive}.
     *
     * Forward to KeepaliveTracker.
     **/
    public void startTcpKeepalive(@Nullable NetworkAgentInfo nai,
            @NonNull FileDescriptor fd,
            int intervalSeconds,
            @NonNull ISocketKeepaliveCallback cb) {
        final KeepaliveTracker.KeepaliveInfo ki = mKeepaliveTracker.makeTcpKeepaliveInfo(nai, fd,
                intervalSeconds, cb);
        if (null != ki) {
            mConnectivityServiceHandler.obtainMessage(CMD_START_SOCKET_KEEPALIVE, ki)
                    .sendToTarget();
        }
    }

    /**
     * Dump AutomaticOnOffKeepaliveTracker state.
     */
    public void dump(IndentingPrintWriter pw) {
        // TODO: Dump the necessary information for automatic on/off keepalive.
        mKeepaliveTracker.dump(pw);
    }

    /**
     * Check all keepalives on the network are still valid.
     *
     * Forward to KeepaliveTracker.
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

        /**
         * Find out if a feature is enabled from DeviceConfig.
         *
         * @param name The name of the property to look up.
         * @return whether the feature is enabled
         */
        public boolean isFeatureEnabled(@NonNull final String name) {
            return DeviceConfigUtils.isFeatureEnabled(mContext, NAMESPACE_CONNECTIVITY, name);
        }
    }
}
