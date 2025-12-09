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

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.DnsUtils;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.MdnsServiceTypeClient.FilterRepliesInfo;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This class keeps tracking the set of registered {@link MdnsServiceBrowserListener} instances, and
 * notify them when a mDNS service instance is found, updated, or removed?
 */
public class MdnsDiscoveryManager implements MdnsSocketClientBase.Callback {
    private static final String TAG = MdnsDiscoveryManager.class.getSimpleName();
    public static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final ExecutorProvider executorProvider;
    private final MdnsSocketClientBase socketClient;
    @NonNull private final SharedLog sharedLog;

    @NonNull private final PerSocketServiceTypeClients perSocketServiceTypeClients;
    @NonNull private final DiscoveryExecutor discoveryExecutor;
    @NonNull private final MdnsFeatureFlags mdnsFeatureFlags;
    @NonNull private final OffloadCallback offloadCallback;

    // Only accessed on the handler thread, initialized before first use
    @Nullable
    private MdnsServiceCache serviceCache;

    private static class PerSocketServiceTypeClients {
        private final ArrayMap<Pair<String, SocketKey>, MdnsServiceTypeClient> clients =
                new ArrayMap<>();

        public void put(@NonNull String serviceType, @NonNull SocketKey socketKey,
                @NonNull MdnsServiceTypeClient client) {
            final String dnsUpperServiceType = DnsUtils.toDnsUpperCase(serviceType);
            final Pair<String, SocketKey> perSocketServiceType = new Pair<>(dnsUpperServiceType,
                    socketKey);
            clients.put(perSocketServiceType, client);
        }

        @Nullable
        public MdnsServiceTypeClient get(
                @NonNull String serviceType, @NonNull SocketKey socketKey) {
            final String dnsUpperServiceType = DnsUtils.toDnsUpperCase(serviceType);
            final Pair<String, SocketKey> perSocketServiceType = new Pair<>(dnsUpperServiceType,
                    socketKey);
            return clients.getOrDefault(perSocketServiceType, null);
        }

        public List<MdnsServiceTypeClient> getByServiceType(@NonNull String serviceType) {
            final String dnsUpperServiceType = DnsUtils.toDnsUpperCase(serviceType);
            final List<MdnsServiceTypeClient> list = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                final Pair<String, SocketKey> perSocketServiceType = clients.keyAt(i);
                if (dnsUpperServiceType.equals(perSocketServiceType.first)) {
                    list.add(clients.valueAt(i));
                }
            }
            return list;
        }

        public List<MdnsServiceTypeClient> getBySocketKey(@NonNull SocketKey socketKey) {
            final List<MdnsServiceTypeClient> list = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                final Pair<String, SocketKey> perSocketServiceType = clients.keyAt(i);
                if (socketKey.equals(perSocketServiceType.second)) {
                    list.add(clients.valueAt(i));
                }
            }
            return list;
        }

        public List<MdnsServiceTypeClient> getAllMdnsServiceTypeClient() {
            return new ArrayList<>(clients.values());
        }

        public List<MdnsServiceTypeClient> getByInterfaceName(@NonNull String interfaceName) {
            final List<MdnsServiceTypeClient> list = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                final Pair<String, SocketKey> perSocketServiceType = clients.keyAt(i);
                if (interfaceName.equals(perSocketServiceType.second.getInterfaceName())) {
                    list.add(clients.valueAt(i));
                }
            }
            return list;
        }

        public void remove(@NonNull MdnsServiceTypeClient client) {
            for (int i = 0; i < clients.size(); ++i) {
                if (Objects.equals(client, clients.valueAt(i))) {
                    clients.removeAt(i);
                    break;
                }
            }
        }

        public boolean isEmpty() {
            return clients.isEmpty();
        }
    }

    public MdnsDiscoveryManager(@NonNull ExecutorProvider executorProvider,
            @NonNull MdnsSocketClientBase socketClient, @NonNull SharedLog sharedLog,
            @NonNull MdnsFeatureFlags mdnsFeatureFlags,
            @NonNull OffloadCallback offloadCallback) {
        this.executorProvider = executorProvider;
        this.socketClient = socketClient;
        this.sharedLog = sharedLog;
        this.perSocketServiceTypeClients = new PerSocketServiceTypeClients();
        this.mdnsFeatureFlags = mdnsFeatureFlags;
        this.discoveryExecutor = new DiscoveryExecutor(socketClient.getLooper(), mdnsFeatureFlags);
        this.offloadCallback = offloadCallback;
    }

    /**
     * Do the cleanup of the MdnsDiscoveryManager
     */
    public void shutDown() {
        discoveryExecutor.shutDown();
    }

    /**
     * Starts (or continue) to discovery mDNS services with given {@code serviceType}, and registers
     * {@code listener} for receiving mDNS service discovery responses.
     *
     * @param serviceType   The type of the service to discover.
     * @param listener      The {@link MdnsServiceBrowserListener} listener.
     * @param searchOptions The {@link MdnsSearchOptions} to be used for discovering {@code
     *                      serviceType}.
     */
    @RequiresPermission(permission.CHANGE_WIFI_MULTICAST_STATE)
    public void registerListener(
            @NonNull String serviceType,
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        sharedLog.i("Registering listener for serviceType: " + serviceType);
        discoveryExecutor.checkAndRunOnHandlerThread(() ->
                handleRegisterListener(serviceType, listener, searchOptions));
    }

    private void handleRegisterListener(
            @NonNull String serviceType,
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        if (perSocketServiceTypeClients.isEmpty()) {
            // First listener. Starts the socket client.
            try {
                socketClient.startDiscovery();
            } catch (IOException e) {
                sharedLog.e("Failed to start discover.", e);
                return;
            }
        }
        // Request the network for discovery.
        // This requests sockets on all networks even if the searchOptions have a given interface
        // index (with getNetwork==null, for local interfaces), and only uses matching interfaces
        // in that case. While this is a simple solution to only use matching sockets, a better
        // practice would be to only request the correct socket for discovery.
        // TODO: avoid requesting extra sockets after migrating P2P and tethering networks to local
        // NetworkAgents.
        socketClient.notifyNetworkRequested(listener, searchOptions.getNetwork(),
                new MdnsSocketClientBase.SocketCreationCallback() {
                    @Override
                    public void onSocketCreated(@NonNull SocketKey socketKey) {
                        discoveryExecutor.ensureRunningOnHandlerThread();
                        final int searchInterfaceIndex = searchOptions.getInterfaceIndex();
                        if (searchOptions.getNetwork() == null
                                && searchInterfaceIndex > 0
                                // The interface index in options should only match interfaces that
                                // do not have any Network; a matching Network should be provided
                                // otherwise.
                                && (socketKey.getNetwork() != null
                                    || socketKey.getInterfaceIndex() != searchInterfaceIndex)) {
                            sharedLog.i("Skipping " + socketKey + " as ifIndex "
                                    + searchInterfaceIndex + " was requested.");
                            return;
                        }

                        // All listeners of the same service types shares the same
                        // MdnsServiceTypeClient.
                        MdnsServiceTypeClient serviceTypeClient =
                                perSocketServiceTypeClients.get(serviceType, socketKey);
                        if (serviceTypeClient == null) {
                            serviceTypeClient = createServiceTypeClient(serviceType, socketKey);
                            perSocketServiceTypeClients.put(serviceType, socketKey,
                                    serviceTypeClient);
                        }
                        serviceTypeClient.startSendAndReceive(listener, searchOptions);
                    }

                    @Override
                    public void onSocketDestroyed(@NonNull SocketKey socketKey) {
                        discoveryExecutor.ensureRunningOnHandlerThread();
                        final MdnsServiceTypeClient serviceTypeClient =
                                perSocketServiceTypeClients.get(serviceType, socketKey);
                        if (serviceTypeClient == null) return;
                        // Notify all listeners that all services are removed from this socket.
                        serviceTypeClient.notifySocketDestroyed();
                        executorProvider.shutdownExecutorService(serviceTypeClient.getExecutor());
                        perSocketServiceTypeClients.remove(serviceTypeClient);
                        // The cached services may not be reliable after the socket is disconnected,
                        // the service type client won't receive any updates for them. Therefore,
                        // remove these cached services after exceeding the retention time
                        // (currently 10s) if no service type client requires them.
                        if (mdnsFeatureFlags.isCachedServicesRemovalEnabled()) {
                            final MdnsServiceCache.CacheKey cacheKey =
                                    serviceTypeClient.getCacheKey();
                            discoveryExecutor.executeDelayed(
                                    () -> handleRemoveCachedServices(cacheKey),
                                    mdnsFeatureFlags.getCachedServicesRetentionTime());
                        }
                    }
                });
    }

    /**
     * Unregister {@code listener} for receiving mDNS service discovery responses. IF no listener is
     * registered for the given service type, stops discovery for the service type.
     *
     * @param serviceType The type of the service to discover.
     * @param listener    The {@link MdnsServiceBrowserListener} listener.
     */
    @RequiresPermission(permission.CHANGE_WIFI_MULTICAST_STATE)
    public void unregisterListener(
            @NonNull String serviceType, @NonNull MdnsServiceBrowserListener listener) {
        sharedLog.i("Unregistering listener for serviceType:" + serviceType);
        discoveryExecutor.checkAndRunOnHandlerThread(() ->
                handleUnregisterListener(serviceType, listener));
    }

    private void handleUnregisterListener(
            @NonNull String serviceType, @NonNull MdnsServiceBrowserListener listener) {
        // Unrequested the network.
        socketClient.notifyNetworkUnrequested(listener);

        final List<MdnsServiceTypeClient> serviceTypeClients =
                perSocketServiceTypeClients.getByServiceType(serviceType);
        if (serviceTypeClients.isEmpty()) {
            return;
        }
        for (int i = 0; i < serviceTypeClients.size(); i++) {
            final MdnsServiceTypeClient serviceTypeClient = serviceTypeClients.get(i);
            if (serviceTypeClient.stopSendAndReceive(listener)) {
                // No listener is registered for the service type anymore, remove it from the list
                // of the service type clients.
                executorProvider.shutdownExecutorService(serviceTypeClient.getExecutor());
                perSocketServiceTypeClients.remove(serviceTypeClient);
                // The cached services may not be reliable after the socket is disconnected, the
                // service type client won't receive any updates for them. Therefore, remove these
                // cached services after exceeding the retention time (currently 10s) if no service
                // type client requires them.
                // Note: This removal is only called if the requested socket is still active for
                // other requests. If the requested socket is no longer needed after the listener
                // is unregistered, SocketCreationCallback#onSocketDestroyed callback will remove
                // both the service type client and cached services there.
                //
                // List some multiple listener cases for the cached service removal flow.
                //
                // Case 1 - Same service type, different network requests
                //  - Register Listener A (service type X, requesting all networks: Y and Z)
                //  - Create service type clients X-Y and X-Z
                //  - Register Listener B (service type X, requesting network Y)
                //  - Reuse service type client X-Y
                //  - Unregister Listener A
                //  - Socket destroyed on network Z; remove the X-Z client. Unregister the listener
                //    from the X-Y client and keep it, as it's still being used by Listener B.
                //  - Remove cached services associated with the X-Z client after 10 seconds.
                //
                // Case 2 - Different service types, same network request
                //  - Register Listener A (service type X, requesting network Y)
                //  - Create service type client X-Y
                //  - Register Listener B (service type Z, requesting network Y)
                //  - Create service type client Z-Y
                //  - Unregister Listener A
                //  - No socket is destroyed because network Y is still being used by Listener B.
                //  - Unregister the listener from the X-Y client, then remove it.
                //  - Remove cached services associated with the X-Y client after 10 seconds.
                if (mdnsFeatureFlags.isCachedServicesRemovalEnabled()) {
                    final MdnsServiceCache.CacheKey cacheKey = serviceTypeClient.getCacheKey();
                    discoveryExecutor.executeDelayed(
                            () -> handleRemoveCachedServices(cacheKey),
                            mdnsFeatureFlags.getCachedServicesRetentionTime());
                }
            }
        }
        if (perSocketServiceTypeClients.isEmpty()) {
            // No discovery request. Stops the socket client.
            sharedLog.i("All service type listeners unregistered; stopping discovery");
            socketClient.stopDiscovery();
        }
    }

    @Override
    public void onResponseReceived(@NonNull MdnsPacket packet, @NonNull SocketKey socketKey) {
        discoveryExecutor.checkAndRunOnHandlerThread(() ->
                handleOnResponseReceived(packet, socketKey));
    }

    private void handleOnResponseReceived(@NonNull MdnsPacket packet,
            @NonNull SocketKey socketKey) {
        for (MdnsServiceTypeClient serviceTypeClient : getMdnsServiceTypeClient(socketKey)) {
            serviceTypeClient.processResponse(packet, socketKey);
        }
    }

    private List<MdnsServiceTypeClient> getMdnsServiceTypeClient(@NonNull SocketKey socketKey) {
        if (socketClient.supportsRequestingSpecificNetworks()) {
            return perSocketServiceTypeClients.getBySocketKey(socketKey);
        } else {
            return perSocketServiceTypeClients.getAllMdnsServiceTypeClient();
        }
    }

    @Override
    public void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode,
            @NonNull SocketKey socketKey) {
        discoveryExecutor.checkAndRunOnHandlerThread(() ->
                handleOnFailedToParseMdnsResponse(receivedPacketNumber, errorCode, socketKey));
    }

    private void handleOnFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode,
            @NonNull SocketKey socketKey) {
        for (MdnsServiceTypeClient serviceTypeClient : getMdnsServiceTypeClient(socketKey)) {
            serviceTypeClient.onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    private void handleRemoveCachedServices(@NonNull MdnsServiceCache.CacheKey cacheKey) {
        // Check if there is an active service type client that requires the cached services. If so,
        // do not remove associated services from cache.
        for (MdnsServiceTypeClient client : getMdnsServiceTypeClient(cacheKey.mSocketKey)) {
            if (client.getCacheKey().equals(cacheKey)) {
                // Found a client that has same CacheKey.
                return;
            }
        }
        sharedLog.log("Remove cached services for " + cacheKey);
        // No client has same CacheKey. Remove associated services.
        getServiceCache().removeServices(cacheKey);
    }

    @VisibleForTesting
    @NonNull
    MdnsServiceCache getServiceCache() {
        return serviceCache;
    }

    @VisibleForTesting
    MdnsServiceTypeClient createServiceTypeClient(@NonNull String serviceType,
            @NonNull SocketKey socketKey) {
        discoveryExecutor.ensureRunningOnHandlerThread();
        sharedLog.log("createServiceTypeClient for type:" + serviceType + " " + socketKey);
        final String tag = serviceType + "-" + socketKey.getNetwork()
                + "/" + socketKey.getInterfaceIndex();
        final Looper looper = Looper.myLooper();
        if (serviceCache == null) {
            serviceCache = new MdnsServiceCache(looper, mdnsFeatureFlags);
        }
        return new MdnsServiceTypeClient(
                serviceType, socketClient,
                executorProvider.newServiceTypeClientSchedulerExecutor(), socketKey,
                sharedLog.forSubComponent(tag), looper, serviceCache, mdnsFeatureFlags,
                offloadCallback);
    }

    private List<MdnsServiceTypeClient> getMdnsServiceTypeClientByInterfaceName(
            @NonNull String interfaceName) {
        if (socketClient.supportsRequestingSpecificNetworks()) {
            return perSocketServiceTypeClients.getByInterfaceName(interfaceName);
        } else {
            return perSocketServiceTypeClients.getAllMdnsServiceTypeClient();
        }
    }

    /**
     * Notifies the DiscoveryManager that an offload operation is starting for a specific network
     * interface and offload types.
     *
     * @param interfaceName The name of the network interface for which offloading is starting.
     * @return A list of {@link FilterRepliesInfo} relevant to the specified interface.
     */
    @NonNull
    public List<FilterRepliesInfo> notifyOffloadStart(@NonNull String interfaceName) {
        discoveryExecutor.ensureRunningOnHandlerThread();
        sharedLog.log("notifyOffloadStart for interface:" + interfaceName);

        final List<FilterRepliesInfo> info = new ArrayList<>();
        for (MdnsServiceTypeClient serviceTypeClient :
                getMdnsServiceTypeClientByInterfaceName(interfaceName)) {
            info.addAll(serviceTypeClient.getFilterRepliesInfo());
        }
        return info;
    }

    /**
     * Dump DiscoveryManager state.
     */
    public void dump(PrintWriter pw) {
        discoveryExecutor.runWithScissorsForDumpIfReady(() -> {
            pw.println("Clients:");
            // Dump ServiceTypeClients
            for (MdnsServiceTypeClient serviceTypeClient
                    : perSocketServiceTypeClients.getAllMdnsServiceTypeClient()) {
                serviceTypeClient.dump(pw);
            }
            pw.println();
            // Dump ServiceCache
            pw.println("Cached services:");
            if (serviceCache != null) {
                serviceCache.dump(pw, "  ");
            }
        });
    }
}