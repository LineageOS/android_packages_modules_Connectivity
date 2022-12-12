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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetd;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.TetheringManager;
import android.net.TetheringManager.TetheringEventCallback;
import android.os.Handler;
import android.os.Looper;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.LinkPropertiesUtils.CompareResult;
import com.android.net.module.util.ip.NetlinkMonitor;
import com.android.net.module.util.netlink.NetlinkConstants;
import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.server.connectivity.mdns.util.MdnsLogger;

import java.io.IOException;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link MdnsSocketProvider} manages the multiple sockets for mDns.
 *
 * <p>This class is not thread safe, it is intended to be used only from the looper thread.
 * However, the constructor is an exception, as it is called on another thread;
 * therefore for thread safety all members of this class MUST either be final or initialized
 * to their default value (0, false or null).
 *
 */
public class MdnsSocketProvider {
    private static final String TAG = MdnsSocketProvider.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;
    private static final MdnsLogger LOGGER = new MdnsLogger(TAG);
    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final Dependencies mDependencies;
    @NonNull private final NetworkCallback mNetworkCallback;
    @NonNull private final TetheringEventCallback mTetheringEventCallback;
    @NonNull private final NetlinkMonitor mNetlinkMonitor;
    private final ArrayMap<Network, SocketInfo> mNetworkSockets = new ArrayMap<>();
    private final ArrayMap<String, SocketInfo> mTetherInterfaceSockets = new ArrayMap<>();
    private final ArrayMap<Network, LinkProperties> mActiveNetworksLinkProperties =
            new ArrayMap<>();
    private final ArrayMap<SocketCallback, Network> mCallbacksToRequestedNetworks =
            new ArrayMap<>();
    private final List<String> mLocalOnlyInterfaces = new ArrayList<>();
    private final List<String> mTetheredInterfaces = new ArrayList<>();
    private boolean mMonitoringSockets = false;

    public MdnsSocketProvider(@NonNull Context context, @NonNull Looper looper) {
        this(context, looper, new Dependencies());
    }

    MdnsSocketProvider(@NonNull Context context, @NonNull Looper looper,
            @NonNull Dependencies deps) {
        mContext = context;
        mHandler = new Handler(looper);
        mDependencies = deps;
        mNetworkCallback = new NetworkCallback() {
            @Override
            public void onLost(Network network) {
                mActiveNetworksLinkProperties.remove(network);
                removeSocket(network, null /* interfaceName */);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                handleLinkPropertiesChanged(network, lp);
            }
        };
        mTetheringEventCallback = new TetheringEventCallback() {
            @Override
            public void onLocalOnlyInterfacesChanged(@NonNull List<String> interfaces) {
                handleTetherInterfacesChanged(mLocalOnlyInterfaces, interfaces);
            }

            @Override
            public void onTetheredInterfacesChanged(@NonNull List<String> interfaces) {
                handleTetherInterfacesChanged(mTetheredInterfaces, interfaces);
            }
        };

        mNetlinkMonitor = new SocketNetlinkMonitor(mHandler);
    }

    /**
     * Dependencies of MdnsSocketProvider, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /*** Get network interface by given interface name */
        public NetworkInterfaceWrapper getNetworkInterfaceByName(String interfaceName)
                throws SocketException {
            final NetworkInterface ni = NetworkInterface.getByName(interfaceName);
            return ni == null ? null : new NetworkInterfaceWrapper(ni);
        }

        /*** Check whether given network interface can support mdns */
        public boolean canScanOnInterface(NetworkInterfaceWrapper networkInterface) {
            return MulticastNetworkInterfaceProvider.canScanOnInterface(networkInterface);
        }

        /*** Create a MdnsInterfaceSocket */
        public MdnsInterfaceSocket createMdnsInterfaceSocket(NetworkInterface networkInterface,
                int port) throws IOException {
            return new MdnsInterfaceSocket(networkInterface, port);
        }
    }

    /*** Data class for storing socket related info  */
    private static class SocketInfo {
        final MdnsInterfaceSocket mSocket;
        final List<LinkAddress> mAddresses = new ArrayList<>();

        SocketInfo(MdnsInterfaceSocket socket, List<LinkAddress> addresses) {
            mSocket = socket;
            mAddresses.addAll(addresses);
        }
    }

    private static class SocketNetlinkMonitor extends NetlinkMonitor {
        SocketNetlinkMonitor(Handler handler) {
            super(handler, LOGGER.mLog, TAG, OsConstants.NETLINK_ROUTE,
                    NetlinkConstants.RTMGRP_IPV4_IFADDR | NetlinkConstants.RTMGRP_IPV6_IFADDR);
        }

        @Override
        public void processNetlinkMessage(NetlinkMessage nlMsg, long whenMs) {
            // TODO: Handle netlink message.
        }
    }

    private void ensureRunningOnHandlerThread() {
        if (mHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on Handler thread: " + Thread.currentThread().getName());
        }
    }

    /*** Start monitoring sockets by listening callbacks for sockets creation or removal */
    public void startMonitoringSockets() {
        ensureRunningOnHandlerThread();
        if (mMonitoringSockets) {
            Log.d(TAG, "Already monitoring sockets.");
            return;
        }
        if (DBG) Log.d(TAG, "Start monitoring sockets.");
        mContext.getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder().clearCapabilities().build(),
                mNetworkCallback, mHandler);

        final TetheringManager tetheringManager = mContext.getSystemService(TetheringManager.class);
        tetheringManager.registerTetheringEventCallback(mHandler::post, mTetheringEventCallback);

        mHandler.post(mNetlinkMonitor::start);
        mMonitoringSockets = true;
    }

    /*** Stop monitoring sockets and unregister callbacks */
    public void stopMonitoringSockets() {
        ensureRunningOnHandlerThread();
        if (!mMonitoringSockets) {
            Log.d(TAG, "Monitoring sockets hasn't been started.");
            return;
        }
        if (DBG) Log.d(TAG, "Stop monitoring sockets.");
        mContext.getSystemService(ConnectivityManager.class)
                .unregisterNetworkCallback(mNetworkCallback);

        final TetheringManager tetheringManager = mContext.getSystemService(TetheringManager.class);
        tetheringManager.unregisterTetheringEventCallback(mTetheringEventCallback);

        mHandler.post(mNetlinkMonitor::stop);
        mMonitoringSockets = false;
    }

    private static boolean isNetworkMatched(@Nullable Network targetNetwork,
            @NonNull Network currentNetwork) {
        return targetNetwork == null || targetNetwork.equals(currentNetwork);
    }

    private boolean matchRequestedNetwork(Network network) {
        for (int i = 0; i < mCallbacksToRequestedNetworks.size(); i++) {
            final Network requestedNetwork =  mCallbacksToRequestedNetworks.valueAt(i);
            if (isNetworkMatched(requestedNetwork, network)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAllNetworksRequest() {
        return mCallbacksToRequestedNetworks.containsValue(null);
    }

    private void handleLinkPropertiesChanged(Network network, LinkProperties lp) {
        mActiveNetworksLinkProperties.put(network, lp);
        if (!matchRequestedNetwork(network)) {
            if (DBG) {
                Log.d(TAG, "Ignore LinkProperties change. There is no request for the"
                        + " Network:" + network);
            }
            return;
        }

        final SocketInfo socketInfo = mNetworkSockets.get(network);
        if (socketInfo == null) {
            createSocket(network, lp);
        } else {
            // Update the addresses of this socket.
            final List<LinkAddress> addresses = lp.getLinkAddresses();
            socketInfo.mAddresses.clear();
            socketInfo.mAddresses.addAll(addresses);
            // Try to join the group again.
            socketInfo.mSocket.joinGroup(addresses);

            notifyAddressesChanged(network, lp);
        }
    }

    private static LinkProperties createLPForTetheredInterface(String interfaceName) {
        final LinkProperties linkProperties = new LinkProperties();
        linkProperties.setInterfaceName(interfaceName);
        // TODO: Use NetlinkMonitor to update addresses for tethering interfaces.
        return linkProperties;
    }

    private void handleTetherInterfacesChanged(List<String> current, List<String> updated) {
        if (!hasAllNetworksRequest()) {
            // Currently, the network for tethering can not be requested, so the sockets for
            // tethering are only created if there is a request for all networks (interfaces).
            // Therefore, this change can skip if there is no such request.
            if (DBG) {
                Log.d(TAG, "Ignore tether interfaces change. There is no request for all"
                        + " networks.");
            }
            return;
        }

        final CompareResult<String> interfaceDiff = new CompareResult<>(
                current, updated);
        for (String name : interfaceDiff.added) {
            createSocket(new Network(INetd.LOCAL_NET_ID), createLPForTetheredInterface(name));
        }
        for (String name : interfaceDiff.removed) {
            removeSocket(new Network(INetd.LOCAL_NET_ID), name);
        }
        current.clear();
        current.addAll(updated);
    }

    private static List<LinkAddress> getLinkAddressFromNetworkInterface(
            NetworkInterfaceWrapper networkInterface) {
        List<LinkAddress> addresses = new ArrayList<>();
        for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
            addresses.add(new LinkAddress(address));
        }
        return addresses;
    }

    private void createSocket(Network network, LinkProperties lp) {
        final String interfaceName = lp.getInterfaceName();
        if (interfaceName == null) {
            Log.e(TAG, "Can not create socket with null interface name.");
            return;
        }

        try {
            final NetworkInterfaceWrapper networkInterface =
                    mDependencies.getNetworkInterfaceByName(interfaceName);
            if (networkInterface == null || !mDependencies.canScanOnInterface(networkInterface)) {
                return;
            }

            if (DBG) {
                Log.d(TAG, "Create a socket on network:" + network
                        + " with interfaceName:" + interfaceName);
            }
            final MdnsInterfaceSocket socket = mDependencies.createMdnsInterfaceSocket(
                    networkInterface.getNetworkInterface(), MdnsConstants.MDNS_PORT);
            final List<LinkAddress> addresses;
            if (network.netId == INetd.LOCAL_NET_ID) {
                addresses = getLinkAddressFromNetworkInterface(networkInterface);
                mTetherInterfaceSockets.put(interfaceName, new SocketInfo(socket, addresses));
            } else {
                addresses = lp.getLinkAddresses();
                mNetworkSockets.put(network, new SocketInfo(socket, addresses));
            }
            // Try to join IPv4/IPv6 group.
            socket.joinGroup(addresses);

            // Notify the listeners which need this socket.
            notifySocketCreated(network, socket, addresses);
        } catch (IOException e) {
            Log.e(TAG, "Create a socket failed with interface=" + interfaceName, e);
        }
    }

    private void removeSocket(Network network, String interfaceName) {
        final SocketInfo socketInfo = network.netId == INetd.LOCAL_NET_ID
                ? mTetherInterfaceSockets.remove(interfaceName)
                : mNetworkSockets.remove(network);
        if (socketInfo == null) return;

        socketInfo.mSocket.destroy();
        notifyInterfaceDestroyed(network, socketInfo.mSocket);
    }

    private void notifySocketCreated(Network network, MdnsInterfaceSocket socket,
            List<LinkAddress> addresses) {
        for (int i = 0; i < mCallbacksToRequestedNetworks.size(); i++) {
            final Network requestedNetwork = mCallbacksToRequestedNetworks.valueAt(i);
            if (isNetworkMatched(requestedNetwork, network)) {
                mCallbacksToRequestedNetworks.keyAt(i).onSocketCreated(network, socket, addresses);
            }
        }
    }

    private void notifyInterfaceDestroyed(Network network, MdnsInterfaceSocket socket) {
        for (int i = 0; i < mCallbacksToRequestedNetworks.size(); i++) {
            final Network requestedNetwork = mCallbacksToRequestedNetworks.valueAt(i);
            if (isNetworkMatched(requestedNetwork, network)) {
                mCallbacksToRequestedNetworks.keyAt(i).onInterfaceDestroyed(network, socket);
            }
        }
    }

    private void notifyAddressesChanged(Network network, LinkProperties lp) {
        for (int i = 0; i < mCallbacksToRequestedNetworks.size(); i++) {
            final Network requestedNetwork = mCallbacksToRequestedNetworks.valueAt(i);
            if (isNetworkMatched(requestedNetwork, network)) {
                mCallbacksToRequestedNetworks.keyAt(i)
                        .onAddressesChanged(network, lp.getLinkAddresses());
            }
        }
    }

    private void retrieveAndNotifySocketFromNetwork(Network network, SocketCallback cb) {
        final SocketInfo socketInfo = mNetworkSockets.get(network);
        if (socketInfo == null) {
            final LinkProperties lp = mActiveNetworksLinkProperties.get(network);
            if (lp == null) {
                // The requested network is not existed. Maybe wait for LinkProperties change later.
                if (DBG) Log.d(TAG, "There is no LinkProperties for this network:" + network);
                return;
            }
            createSocket(network, lp);
        } else {
            // Notify the socket for requested network.
            cb.onSocketCreated(network, socketInfo.mSocket, socketInfo.mAddresses);
        }
    }

    private void retrieveAndNotifySocketFromInterface(String interfaceName, SocketCallback cb) {
        final SocketInfo socketInfo = mTetherInterfaceSockets.get(interfaceName);
        if (socketInfo == null) {
            createSocket(
                    new Network(INetd.LOCAL_NET_ID), createLPForTetheredInterface(interfaceName));
        } else {
            // Notify the socket for requested network.
            cb.onSocketCreated(
                    new Network(INetd.LOCAL_NET_ID), socketInfo.mSocket, socketInfo.mAddresses);
        }
    }

    /**
     * Request a socket for given network.
     *
     * @param network the required network for a socket. Null means create sockets on all possible
     *                networks (interfaces).
     * @param cb the callback to listen the socket creation.
     */
    public void requestSocket(@Nullable Network network, @NonNull SocketCallback cb) {
        ensureRunningOnHandlerThread();
        mCallbacksToRequestedNetworks.put(cb, network);
        if (network == null) {
            // Does not specify a required network, create sockets for all possible
            // networks (interfaces).
            for (int i = 0; i < mActiveNetworksLinkProperties.size(); i++) {
                retrieveAndNotifySocketFromNetwork(mActiveNetworksLinkProperties.keyAt(i), cb);
            }

            for (String localInterface : mLocalOnlyInterfaces) {
                retrieveAndNotifySocketFromInterface(localInterface, cb);
            }

            for (String tetheredInterface : mTetheredInterfaces) {
                retrieveAndNotifySocketFromInterface(tetheredInterface, cb);
            }
        } else {
            retrieveAndNotifySocketFromNetwork(network, cb);
        }
    }

    /*** Unrequest the socket */
    public void unrequestSocket(@NonNull SocketCallback cb) {
        ensureRunningOnHandlerThread();
        mCallbacksToRequestedNetworks.remove(cb);
        if (hasAllNetworksRequest()) {
            // Still has a request for all networks (interfaces).
            return;
        }

        // Check if remaining requests are matched any of sockets.
        for (int i = mNetworkSockets.size() - 1; i >= 0; i--) {
            if (matchRequestedNetwork(mNetworkSockets.keyAt(i))) continue;
            mNetworkSockets.removeAt(i).mSocket.destroy();
        }

        // Remove all sockets for tethering interface because these sockets do not have associated
        // networks, and they should invoke by a request for all networks (interfaces). If there is
        // no such request, the sockets for tethering interface should be removed.
        for (int i = mTetherInterfaceSockets.size() - 1; i >= 0; i--) {
            mTetherInterfaceSockets.removeAt(i).mSocket.destroy();
        }
    }

    /*** Callbacks for listening socket changes */
    public interface SocketCallback {
        /*** Notify the socket is created */
        default void onSocketCreated(@NonNull Network network, @NonNull MdnsInterfaceSocket socket,
                @NonNull List<LinkAddress> addresses) {}
        /*** Notify the interface is destroyed */
        default void onInterfaceDestroyed(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket) {}
        /*** Notify the addresses is changed on the network */
        default void onAddressesChanged(@NonNull Network network,
                @NonNull List<LinkAddress> addresses) {}
    }
}
