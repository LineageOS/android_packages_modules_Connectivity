/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.ArrayMap;
import android.util.Pair;

import androidx.annotation.GuardedBy;

import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.SharedLog;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Class for monitoring connectivity changes using {@link ConnectivityManager}. */
public class ConnectivityMonitorWithConnectivityManager implements ConnectivityMonitor {
    private static final String TAG = "ConnMntrWConnMgr";
    private final SharedLog sharedLog;
    private final Listener listener;
    private final ConnectivityManager.NetworkCallback networkCallback;
    private final ConnectivityManager connectivityManager;
    private final Context context;
    private final boolean supportsIncludeOtherUidNetworks =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    private final BroadcastReceiver networkChangedReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                notifyConnectivityChange();
            }
        };
    @GuardedBy("knownNetworks")
    private final ArrayMap<Network, LinkProperties> knownNetworks = new ArrayMap<>();
    // TODO(b/71901993): Ideally we shouldn't need this flag. However we still don't have clues why
    // the receiver is unregistered twice yet.
    private boolean isCallbackRegistered = false;
    private Network lastAvailableNetwork = null;

    @SuppressWarnings({"nullness:assignment", "nullness:method.invocation"})
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public ConnectivityMonitorWithConnectivityManager(Context context, Listener listener,
            SharedLog sharedLog) {
        this.listener = listener;
        this.sharedLog = sharedLog;
        this.context = context;

        connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onLinkPropertiesChanged(
                            @NonNull Network network,
                            @NonNull LinkProperties linkProperties) {
                        final boolean newNetwork;
                        synchronized (knownNetworks) {
                            newNetwork = knownNetworks.put(network, linkProperties) == null;
                        }
                        if (newNetwork) {
                            sharedLog.log("network available: " + network);
                            lastAvailableNetwork = network;
                            notifyConnectivityChange();
                        }
                    }

                    @Override
                    public void onLost(Network network) {
                        sharedLog.log("network lost: " + network);
                        synchronized (knownNetworks) {
                            knownNetworks.remove(network);
                        }
                        notifyConnectivityChange();
                    }

                    @Override
                    public void onUnavailable() {
                        sharedLog.log("network unavailable.");
                        notifyConnectivityChange();
                    }
                };
    }

    @Override
    public void notifyConnectivityChange() {
        listener.onConnectivityChanged();
    }

    /**
     * Starts monitoring changes of connectivity of this device, which may indicate that the list of
     * network interfaces available for multi-cast messaging has changed.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void startWatchingConnectivityChanges() {
        sharedLog.log("Start watching connectivity changes");
        if (isCallbackRegistered) {
            return;
        }

        final NetworkRequest.Builder builder =
            new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        if (supportsIncludeOtherUidNetworks) {
            builder.setIncludeOtherUidNetworks(true);
        }
        connectivityManager.registerNetworkCallback(builder.build(), networkCallback);

        if (!supportsIncludeOtherUidNetworks) {
            // Since networks created via ConnectivityManager#requestNetwork with a
            // WifiNetworkSpecifier cannot be obtained via callbacks before S due to the lack of
            // setIncludeOtherUidNetworks, register a Wi-Fi BroadcastReceiver to ensure network
            // state is updated.
            context.registerReceiver(
                networkChangedReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));
        }
        isCallbackRegistered = true;
    }

    /** Stops monitoring changes of connectivity. */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void stopWatchingConnectivityChanges() {
        sharedLog.log("Stop watching connectivity changes");
        if (!isCallbackRegistered) {
            return;
        }

        connectivityManager.unregisterNetworkCallback(networkCallback);
        if (!supportsIncludeOtherUidNetworks) {
            context.unregisterReceiver(networkChangedReceiver);
        }
        synchronized (knownNetworks) {
            knownNetworks.clear();
        }
        isCallbackRegistered = false;
    }

    @Override
    @Nullable
    public Network getAvailableNetwork() {
        return lastAvailableNetwork;
    }

    /**
     * Try to guess which network a remote host may be in.
     *
     * <p>This is not a perfect guess as it depends on the timing of LinkProperties updates and
     * interfaces going up/down. Also, it is possible that two interfaces have overlapping prefixes,
     * in which case it is not possible to know which one is correct from just an address. This is
     * intended as an incremental improvement for legacy code that cannot use MdnsSocketProvider,
     * which uses per-Network sockets to avoid this problem.
     *
     * @param address The address of a remote host.
     * @return The SocketKey with Network and interface if it could be determined, or null.
     */
    @Nullable
    public SocketKey guessNetworkOfRemoteHost(@NonNull List<NetworkInterfaceWrapper> knownIfaces,
            @NonNull InetAddress address) {
        if (address instanceof Inet6Address && address.isLinkLocalAddress()) {
            final int scopeId = ((Inet6Address) address).getScopeId();
            for (NetworkInterfaceWrapper iface : knownIfaces) {
                if (iface.getIndex() == scopeId) {
                    return getSocketKeyForInterface(iface.getNetworkInterface());
                }
            }
            return null;
        }
        // Find a matching network from the address.
        final Pair<Network, LinkProperties> match = findMatchingNetwork(lp ->
                // Do not consider stacked links (lp.getAllRoutes), as they are generally not
                // compatible with MDNS, and this is consistent with MdnsSocketProvider.
                // Note LinkProperties does not always contain routes for local subnets before P
                // (change ID I35b614eebccfd22c4a5270f40256f9be1e25abfb), but on M+ Wi-Fi does add
                // them from netlink/DHCP.
                // Look for the address in directly-connected routes (no gateway).
                // Note RouteInfo#hasGateway would be equivalent and simpler but is API 29+
                CollectionUtils.any(lp.getRoutes(), r ->
                      (r.getGateway() == null || r.getGateway().isAnyLocalAddress())
                      && r.matches(address)));
        if (match == null) {
            return null;
        }
        // Find a matching interface index for the network.
        final NetworkInterfaceWrapper netIf = CollectionUtils.findFirst(knownIfaces, iface ->
                iface.getName().equals(match.second.getInterfaceName()));
        if (netIf == null) {
            return null;
        }
        return new SocketKey(match.first, netIf.getIndex());
    }

    @Nullable
    private SocketKey getSocketKeyForInterface(@Nullable NetworkInterface iface) {
        if (iface == null) {
            return null;
        }
        final Pair<Network, LinkProperties> match = findMatchingNetwork(
                lp -> iface.getName().equals(lp.getInterfaceName()));
        return match == null ? null : new SocketKey(match.first, iface.getIndex());
    }

    private Pair<Network, LinkProperties> findMatchingNetwork(
            @NonNull Predicate<LinkProperties> predicate) {
        final Set<Network> testedNetworks;
        synchronized (knownNetworks) {
            for (int i = 0; i < knownNetworks.size(); i++) {
                final LinkProperties lp = knownNetworks.valueAt(i);
                // Do not consider stacked links (lp.getAllInterfaceNames), as they are generally
                // not compatible with MDNS, and this is consistent with MdnsSocketProvider.
                if (predicate.test(lp)) {
                    return new Pair<>(knownNetworks.keyAt(i), knownNetworks.valueAt(i));
                }
            }
            testedNetworks = new HashSet<>(knownNetworks.keySet());
        }
        if (!supportsIncludeOtherUidNetworks) {
            // Before S setIncludeOtherUidNetworks cannot be set on the NetworkRequest, so it
            // will not match networks brought up by apps via networkRequest with a
            // WifiNetworkSpecifier (which have uids set in their NetworkCapabilities on S+, or
            // requestor UID set on R-).
            // Fall back to synchronous methods if no matching network could be found.
            for (Network network : connectivityManager.getAllNetworks()) {
                if (testedNetworks.contains(network)) {
                    continue;
                }
                final LinkProperties lp = connectivityManager.getLinkProperties(network);
                if (lp != null && predicate.test(lp)) {
                    return new Pair<>(network, lp);
                }
            }
        }
        return null;
    }
}
