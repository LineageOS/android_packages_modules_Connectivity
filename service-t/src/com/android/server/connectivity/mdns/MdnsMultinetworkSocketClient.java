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

import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnHandlerThread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkAddress;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;

import com.android.net.module.util.SharedLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/**
 * The {@link MdnsMultinetworkSocketClient} manages the multinetwork socket for mDns
 *
 *  * <p>This class is not thread safe.
 */
public class MdnsMultinetworkSocketClient implements MdnsSocketClientBase {
    private static final String TAG = MdnsMultinetworkSocketClient.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;

    @NonNull private final Handler mHandler;
    @NonNull private final MdnsSocketProvider mSocketProvider;
    @NonNull private final SharedLog mSharedLog;

    private final ArrayMap<MdnsServiceBrowserListener, InterfaceSocketCallback> mRequestedNetworks =
            new ArrayMap<>();
    private final ArrayMap<MdnsInterfaceSocket, ReadPacketHandler> mSocketPacketHandlers =
            new ArrayMap<>();
    private MdnsSocketClientBase.Callback mCallback = null;
    private int mReceivedPacketNumber = 0;

    public MdnsMultinetworkSocketClient(@NonNull Looper looper,
            @NonNull MdnsSocketProvider provider,
            @NonNull SharedLog sharedLog) {
        mHandler = new Handler(looper);
        mSocketProvider = provider;
        mSharedLog = sharedLog;
    }

    private class InterfaceSocketCallback implements MdnsSocketProvider.SocketCallback {
        @NonNull
        private final SocketCreationCallback mSocketCreationCallback;
        @NonNull
        private final ArrayMap<MdnsInterfaceSocket, SocketKey> mActiveSockets =
                new ArrayMap<>();

        InterfaceSocketCallback(SocketCreationCallback socketCreationCallback) {
            mSocketCreationCallback = socketCreationCallback;
        }

        @Override
        public void onSocketCreated(@NonNull SocketKey socketKey,
                @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {
            // The socket may be already created by other request before, try to get the stored
            // ReadPacketHandler.
            ReadPacketHandler handler = mSocketPacketHandlers.get(socket);
            if (handler == null) {
                // First request to create this socket. Initial a ReadPacketHandler for this socket.
                handler = new ReadPacketHandler(socketKey);
                mSocketPacketHandlers.put(socket, handler);
            }
            socket.addPacketHandler(handler);
            mActiveSockets.put(socket, socketKey);
            mSocketCreationCallback.onSocketCreated(socketKey);
        }

        @Override
        public void onInterfaceDestroyed(@NonNull SocketKey socketKey,
                @NonNull MdnsInterfaceSocket socket) {
            notifySocketDestroyed(socket);
            maybeCleanupPacketHandler(socket);
        }

        private void notifySocketDestroyed(@NonNull MdnsInterfaceSocket socket) {
            final SocketKey socketKey = mActiveSockets.remove(socket);
            if (!isSocketActive(socket)) {
                mSocketCreationCallback.onSocketDestroyed(socketKey);
            }
        }

        void onNetworkUnrequested() {
            for (int i = mActiveSockets.size() - 1; i >= 0; i--) {
                // Iterate from the end so the socket can be removed
                final MdnsInterfaceSocket socket = mActiveSockets.keyAt(i);
                notifySocketDestroyed(socket);
                maybeCleanupPacketHandler(socket);
            }
        }
    }

    private boolean isSocketActive(@NonNull MdnsInterfaceSocket socket) {
        for (int i = 0; i < mRequestedNetworks.size(); i++) {
            final InterfaceSocketCallback isc = mRequestedNetworks.valueAt(i);
            if (isc.mActiveSockets.containsKey(socket)) {
                return true;
            }
        }
        return false;
    }

    private ArrayMap<MdnsInterfaceSocket, SocketKey> getActiveSockets() {
        final ArrayMap<MdnsInterfaceSocket, SocketKey> sockets = new ArrayMap<>();
        for (int i = 0; i < mRequestedNetworks.size(); i++) {
            final InterfaceSocketCallback isc = mRequestedNetworks.valueAt(i);
            sockets.putAll(isc.mActiveSockets);
        }
        return sockets;
    }

    private void maybeCleanupPacketHandler(@NonNull MdnsInterfaceSocket socket) {
        if (isSocketActive(socket)) return;
        mSocketPacketHandlers.remove(socket);
    }

    private class ReadPacketHandler implements MulticastPacketReader.PacketHandler {
        @NonNull private final SocketKey mSocketKey;

        ReadPacketHandler(@NonNull SocketKey socketKey) {
            mSocketKey = socketKey;
        }

        @Override
        public void handlePacket(byte[] recvbuf, int length, InetSocketAddress src) {
            processResponsePacket(recvbuf, length, mSocketKey);
        }
    }

    /*** Set callback for receiving mDns response */
    @Override
    public void setCallback(@Nullable MdnsSocketClientBase.Callback callback) {
        ensureRunningOnHandlerThread(mHandler);
        mCallback = callback;
    }

    /***
     * Notify that the given network is requested for mdns discovery / resolution
     *
     * @param listener the listener for discovery.
     * @param network the target network for discovery. Null means discovery on all possible
     *                interfaces.
     * @param socketCreationCallback the callback to notify socket creation.
     */
    @Override
    public void notifyNetworkRequested(@NonNull MdnsServiceBrowserListener listener,
            @Nullable Network network, @NonNull SocketCreationCallback socketCreationCallback) {
        ensureRunningOnHandlerThread(mHandler);
        InterfaceSocketCallback callback = mRequestedNetworks.get(listener);
        if (callback != null) {
            throw new IllegalArgumentException("Can not register duplicated listener");
        }

        if (DBG) mSharedLog.v("notifyNetworkRequested: network=" + network);
        callback = new InterfaceSocketCallback(socketCreationCallback);
        mRequestedNetworks.put(listener, callback);
        mSocketProvider.requestSocket(network, callback);
    }

    /*** Notify that the network is unrequested */
    @Override
    public void notifyNetworkUnrequested(@NonNull MdnsServiceBrowserListener listener) {
        ensureRunningOnHandlerThread(mHandler);
        final InterfaceSocketCallback callback = mRequestedNetworks.get(listener);
        if (callback == null) {
            mSharedLog.e("Can not be unrequested with unknown listener=" + listener);
            return;
        }
        callback.onNetworkUnrequested();
        // onNetworkUnrequested does cleanups based on mRequestedNetworks, only remove afterwards
        mRequestedNetworks.remove(listener);
        mSocketProvider.unrequestSocket(callback);
    }

    @Override
    public Looper getLooper() {
        return mHandler.getLooper();
    }

    @Override
    public boolean supportsRequestingSpecificNetworks() {
        return true;
    }

    private void sendMdnsPacket(@NonNull DatagramPacket packet, @NonNull SocketKey targetSocketKey,
            boolean onlyUseIpv6OnIpv6OnlyNetworks) {
        final boolean isIpv6 = ((InetSocketAddress) packet.getSocketAddress()).getAddress()
                instanceof Inet6Address;
        final boolean isIpv4 = ((InetSocketAddress) packet.getSocketAddress()).getAddress()
                instanceof Inet4Address;
        final ArrayMap<MdnsInterfaceSocket, SocketKey> activeSockets = getActiveSockets();
        boolean shouldQueryIpv6 = !onlyUseIpv6OnIpv6OnlyNetworks || isIpv6OnlySockets(
                activeSockets, targetSocketKey);
        for (int i = 0; i < activeSockets.size(); i++) {
            final MdnsInterfaceSocket socket = activeSockets.keyAt(i);
            final SocketKey socketKey = activeSockets.valueAt(i);
            // Check ip capability and network before sending packet
            if (((isIpv6 && socket.hasJoinedIpv6() && shouldQueryIpv6)
                    || (isIpv4 && socket.hasJoinedIpv4()))
                    && Objects.equals(socketKey, targetSocketKey)) {
                try {
                    socket.send(packet);
                } catch (IOException e) {
                    mSharedLog.e("Failed to send a mDNS packet.", e);
                }
            }
        }
    }

    private boolean isIpv6OnlySockets(
            @NonNull ArrayMap<MdnsInterfaceSocket, SocketKey> activeSockets,
            @NonNull SocketKey targetSocketKey) {
        for (int i = 0; i < activeSockets.size(); i++) {
            final MdnsInterfaceSocket socket = activeSockets.keyAt(i);
            final SocketKey socketKey = activeSockets.valueAt(i);
            if (Objects.equals(socketKey, targetSocketKey) && socket.hasJoinedIpv4()) {
                return false;
            }
        }
        return true;
    }

    private void processResponsePacket(byte[] recvbuf, int length, @NonNull SocketKey socketKey) {
        int packetNumber = ++mReceivedPacketNumber;

        final MdnsPacket response;
        try {
            response = MdnsResponseDecoder.parseResponse(recvbuf, length);
        } catch (MdnsPacket.ParseException e) {
            if (e.code != MdnsResponseErrorCode.ERROR_NOT_RESPONSE_MESSAGE) {
                mSharedLog.e(e.getMessage(), e);
                if (mCallback != null) {
                    mCallback.onFailedToParseMdnsResponse(packetNumber, e.code, socketKey);
                }
            }
            return;
        }

        if (mCallback != null) {
            mCallback.onResponseReceived(response, socketKey);
        }
    }

    /**
     * Send a mDNS request packet via given socket key that asks for multicast response.
     */
    public void sendPacketRequestingMulticastResponse(@NonNull DatagramPacket packet,
            @NonNull SocketKey socketKey, boolean onlyUseIpv6OnIpv6OnlyNetworks) {
        mHandler.post(() -> sendMdnsPacket(packet, socketKey, onlyUseIpv6OnIpv6OnlyNetworks));
    }

    @Override
    public void sendPacketRequestingMulticastResponse(
            @NonNull DatagramPacket packet, boolean onlyUseIpv6OnIpv6OnlyNetworks) {
        throw new UnsupportedOperationException("This socket client need to specify the socket to"
                + "send packet");
    }

    /**
     * Send a mDNS request packet via given socket key that asks for unicast response.
     *
     * <p>The socket client may use a null network to identify some or all interfaces, in which case
     * passing null sends the packet to these.
     */
    public void sendPacketRequestingUnicastResponse(@NonNull DatagramPacket packet,
            @NonNull SocketKey socketKey, boolean onlyUseIpv6OnIpv6OnlyNetworks) {
        mHandler.post(() -> sendMdnsPacket(packet, socketKey, onlyUseIpv6OnIpv6OnlyNetworks));
    }

    @Override
    public void sendPacketRequestingUnicastResponse(
            @NonNull DatagramPacket packet, boolean onlyUseIpv6OnIpv6OnlyNetworks) {
        throw new UnsupportedOperationException("This socket client need to specify the socket to"
                + "send packet");
    }
}