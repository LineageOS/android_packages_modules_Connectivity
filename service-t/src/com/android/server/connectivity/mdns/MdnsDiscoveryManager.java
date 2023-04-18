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

import static com.android.server.connectivity.mdns.MdnsSocketProvider.isNetworkMatched;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.net.Network;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.SharedLog;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * This class keeps tracking the set of registered {@link MdnsServiceBrowserListener} instances, and
 * notify them when a mDNS service instance is found, updated, or removed?
 */
public class MdnsDiscoveryManager implements MdnsSocketClientBase.Callback {
    private static final String TAG = MdnsDiscoveryManager.class.getSimpleName();
    public static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final SharedLog LOGGER = new SharedLog(TAG);

    private final ExecutorProvider executorProvider;
    private final MdnsSocketClientBase socketClient;

    @GuardedBy("this")
    @NonNull private final PerNetworkServiceTypeClients perNetworkServiceTypeClients;

    private static class PerNetworkServiceTypeClients {
        private final ArrayMap<Pair<String, Network>, MdnsServiceTypeClient> clients =
                new ArrayMap<>();

        public void put(@NonNull String serviceType, @Nullable Network network,
                @NonNull MdnsServiceTypeClient client) {
            final Pair<String, Network> perNetworkServiceType = new Pair<>(serviceType, network);
            clients.put(perNetworkServiceType, client);
        }

        @Nullable
        public MdnsServiceTypeClient get(@NonNull String serviceType, @Nullable Network network) {
            final Pair<String, Network> perNetworkServiceType = new Pair<>(serviceType, network);
            return clients.getOrDefault(perNetworkServiceType, null);
        }

        public List<MdnsServiceTypeClient> getByServiceType(@NonNull String serviceType) {
            final List<MdnsServiceTypeClient> list = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                final Pair<String, Network> perNetworkServiceType = clients.keyAt(i);
                if (serviceType.equals(perNetworkServiceType.first)) {
                    list.add(clients.valueAt(i));
                }
            }
            return list;
        }

        public List<MdnsServiceTypeClient> getByMatchingNetwork(@Nullable Network network) {
            final List<MdnsServiceTypeClient> list = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                final Pair<String, Network> perNetworkServiceType = clients.keyAt(i);
                if (isNetworkMatched(network, perNetworkServiceType.second)) {
                    list.add(clients.valueAt(i));
                }
            }
            return list;
        }

        public void remove(@NonNull MdnsServiceTypeClient client) {
            final int index = clients.indexOfValue(client);
            clients.removeAt(index);
        }

        public boolean isEmpty() {
            return clients.isEmpty();
        }
    }

    public MdnsDiscoveryManager(@NonNull ExecutorProvider executorProvider,
            @NonNull MdnsSocketClientBase socketClient) {
        this.executorProvider = executorProvider;
        this.socketClient = socketClient;
        perNetworkServiceTypeClients = new PerNetworkServiceTypeClients();
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
    public synchronized void registerListener(
            @NonNull String serviceType,
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        LOGGER.i("Registering listener for serviceType: " + serviceType);
        if (perNetworkServiceTypeClients.isEmpty()) {
            // First listener. Starts the socket client.
            try {
                socketClient.startDiscovery();
            } catch (IOException e) {
                LOGGER.e("Failed to start discover.", e);
                return;
            }
        }
        // Request the network for discovery.
        socketClient.notifyNetworkRequested(listener, searchOptions.getNetwork(), network -> {
            synchronized (this) {
                // All listeners of the same service types shares the same MdnsServiceTypeClient.
                MdnsServiceTypeClient serviceTypeClient =
                        perNetworkServiceTypeClients.get(serviceType, network);
                if (serviceTypeClient == null) {
                    serviceTypeClient = createServiceTypeClient(serviceType, network);
                    perNetworkServiceTypeClients.put(serviceType, network, serviceTypeClient);
                }
                serviceTypeClient.startSendAndReceive(listener, searchOptions);
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
    public synchronized void unregisterListener(
            @NonNull String serviceType, @NonNull MdnsServiceBrowserListener listener) {
        LOGGER.i("Unregistering listener for serviceType:" + serviceType);
        final List<MdnsServiceTypeClient> serviceTypeClients =
                perNetworkServiceTypeClients.getByServiceType(serviceType);
        if (serviceTypeClients.isEmpty()) {
            return;
        }
        for (int i = 0; i < serviceTypeClients.size(); i++) {
            final MdnsServiceTypeClient serviceTypeClient = serviceTypeClients.get(i);
            if (serviceTypeClient.stopSendAndReceive(listener)) {
                // No listener is registered for the service type anymore, remove it from the list
                // of the service type clients.
                perNetworkServiceTypeClients.remove(serviceTypeClient);
                if (perNetworkServiceTypeClients.isEmpty()) {
                    // No discovery request. Stops the socket client.
                    socketClient.stopDiscovery();
                }
            }
        }
        // Unrequested the network.
        socketClient.notifyNetworkUnrequested(listener);
    }

    @Override
    public synchronized void onResponseReceived(@NonNull MdnsPacket packet,
            int interfaceIndex, Network network) {
        for (MdnsServiceTypeClient serviceTypeClient
                : perNetworkServiceTypeClients.getByMatchingNetwork(network)) {
            serviceTypeClient.processResponse(packet, interfaceIndex, network);
        }
    }

    @Override
    public synchronized void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode,
            Network network) {
        for (MdnsServiceTypeClient serviceTypeClient
                : perNetworkServiceTypeClients.getByMatchingNetwork(network)) {
            serviceTypeClient.onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    /** Dump info to dumpsys */
    public void dump(PrintWriter pw) {
        LOGGER.reverseDump(pw);
    }

    @VisibleForTesting
    MdnsServiceTypeClient createServiceTypeClient(@NonNull String serviceType,
            @Nullable Network network) {
        LOGGER.log("createServiceTypeClient for serviceType:" + serviceType
                + " network:" + network);
        return new MdnsServiceTypeClient(
                serviceType, socketClient,
                executorProvider.newServiceTypeClientSchedulerExecutor(), network,
                LOGGER.forSubComponent(serviceType + "-" + network));
    }
}