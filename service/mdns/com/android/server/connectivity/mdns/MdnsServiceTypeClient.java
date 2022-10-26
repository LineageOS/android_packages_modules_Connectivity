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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.connectivity.mdns.util.MdnsLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Instance of this class sends and receives mDNS packets of a given service type and invoke
 * registered {@link MdnsServiceBrowserListener} instances.
 */
// TODO(b/242631897): Resolve nullness suppression.
@SuppressWarnings("nullness")
public class MdnsServiceTypeClient {

    private static final int DEFAULT_MTU = 1500;
    private static final MdnsLogger LOGGER = new MdnsLogger("MdnsServiceTypeClient");

    private final String serviceType;
    private final String[] serviceTypeLabels;
    private final MdnsSocketClient socketClient;
    private final ScheduledExecutorService executor;
    private final Object lock = new Object();
    private final Set<MdnsServiceBrowserListener> listeners = new ArraySet<>();
    private final Map<String, MdnsResponse> instanceNameToResponse = new HashMap<>();
    private final boolean removeServiceAfterTtlExpires =
            MdnsConfigs.removeServiceAfterTtlExpires();
    private final boolean allowSearchOptionsToRemoveExpiredService =
            MdnsConfigs.allowSearchOptionsToRemoveExpiredService();

    @Nullable private MdnsSearchOptions searchOptions;

    // The session ID increases when startSendAndReceive() is called where we schedule a
    // QueryTask for
    // new subtypes. It stays the same between packets for same subtypes.
    private long currentSessionId = 0;

    @GuardedBy("lock")
    private Future<?> requestTaskFuture;

    /**
     * Constructor of {@link MdnsServiceTypeClient}.
     *
     * @param socketClient Sends and receives mDNS packet.
     * @param executor     A {@link ScheduledExecutorService} used to schedule query tasks.
     */
    public MdnsServiceTypeClient(
            @NonNull String serviceType,
            @NonNull MdnsSocketClient socketClient,
            @NonNull ScheduledExecutorService executor) {
        this.serviceType = serviceType;
        this.socketClient = socketClient;
        this.executor = executor;
        serviceTypeLabels = TextUtils.split(serviceType, "\\.");
    }

    private static MdnsServiceInfo buildMdnsServiceInfoFromResponse(
            @NonNull MdnsResponse response, @NonNull String[] serviceTypeLabels) {
        String[] hostName = response.getServiceRecord().getServiceHost();
        int port = response.getServiceRecord().getServicePort();

        String ipv4Address = null;
        String ipv6Address = null;
        if (response.hasInet4AddressRecord()) {
            ipv4Address = response.getInet4AddressRecord().getInet4Address().getHostAddress();
        }
        if (response.hasInet6AddressRecord()) {
            ipv6Address = response.getInet6AddressRecord().getInet6Address().getHostAddress();
        }
        // TODO: Throw an error message if response doesn't have Inet6 or Inet4 address.
        return new MdnsServiceInfo(
                response.getServiceInstanceName(),
                serviceTypeLabels,
                response.getSubtypes(),
                hostName,
                port,
                ipv4Address,
                ipv6Address,
                response.getTextRecord().getStrings(),
                response.getTextRecord().getEntries(),
                response.getInterfaceIndex());
    }

    /**
     * Registers {@code listener} for receiving discovery event of mDNS service instances, and
     * starts
     * (or continue) to send mDNS queries periodically.
     *
     * @param listener      The {@link MdnsServiceBrowserListener} to register.
     * @param searchOptions {@link MdnsSearchOptions} contains the list of subtypes to discover.
     */
    public void startSendAndReceive(
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        synchronized (lock) {
            this.searchOptions = searchOptions;
            if (!listeners.contains(listener)) {
                listeners.add(listener);
                for (MdnsResponse existingResponse : instanceNameToResponse.values()) {
                    if (existingResponse.isComplete()) {
                        listener.onServiceFound(
                                buildMdnsServiceInfoFromResponse(existingResponse,
                                        serviceTypeLabels));
                    }
                }
            }
            // Cancel the next scheduled periodical task.
            if (requestTaskFuture != null) {
                requestTaskFuture.cancel(true);
            }
            // Keep tracking the ScheduledFuture for the task so we can cancel it if caller is not
            // interested anymore.
            requestTaskFuture =
                    executor.submit(
                            new QueryTask(
                                    new QueryTaskConfig(
                                            searchOptions.getSubtypes(),
                                            searchOptions.isPassiveMode(),
                                            ++currentSessionId)));
        }
    }

    /**
     * Unregisters {@code listener} from receiving discovery event of mDNS service instances.
     *
     * @param listener The {@link MdnsServiceBrowserListener} to unregister.
     * @return {@code true} if no listener is registered with this client after unregistering {@code
     * listener}. Otherwise returns {@code false}.
     */
    public boolean stopSendAndReceive(@NonNull MdnsServiceBrowserListener listener) {
        synchronized (lock) {
            listeners.remove(listener);
            if (listeners.isEmpty() && requestTaskFuture != null) {
                requestTaskFuture.cancel(true);
                requestTaskFuture = null;
            }
            return listeners.isEmpty();
        }
    }

    public String[] getServiceTypeLabels() {
        return serviceTypeLabels;
    }

    public synchronized void processResponse(@NonNull MdnsResponse response) {
        if (shouldRemoveServiceAfterTtlExpires()) {
            // Because {@link QueryTask} and {@link processResponse} are running in different
            // threads. We need to synchronize {@link lock} to protect
            // {@link instanceNameToResponse} won’t be modified at the same time.
            synchronized (lock) {
                if (response.isGoodbye()) {
                    onGoodbyeReceived(response.getServiceInstanceName());
                } else {
                    onResponseReceived(response);
                }
            }
        } else {
            if (response.isGoodbye()) {
                onGoodbyeReceived(response.getServiceInstanceName());
            } else {
                onResponseReceived(response);
            }
        }
    }

    public synchronized void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) {
        for (MdnsServiceBrowserListener listener : listeners) {
            listener.onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    private void onResponseReceived(@NonNull MdnsResponse response) {
        MdnsResponse currentResponse;
        currentResponse = instanceNameToResponse.get(response.getServiceInstanceName());

        boolean newServiceFound = false;
        boolean existingServiceChanged = false;
        if (currentResponse == null) {
            newServiceFound = true;
            currentResponse = response;
            instanceNameToResponse.put(response.getServiceInstanceName(), currentResponse);
        } else if (currentResponse.mergeRecordsFrom(response)) {
            existingServiceChanged = true;
        }
        if (!currentResponse.isComplete() || (!newServiceFound && !existingServiceChanged)) {
            return;
        }
        MdnsServiceInfo serviceInfo =
                buildMdnsServiceInfoFromResponse(currentResponse, serviceTypeLabels);

        for (MdnsServiceBrowserListener listener : listeners) {
            if (newServiceFound) {
                listener.onServiceFound(serviceInfo);
            } else {
                listener.onServiceUpdated(serviceInfo);
            }
        }
    }

    private void onGoodbyeReceived(@NonNull String serviceInstanceName) {
        instanceNameToResponse.remove(serviceInstanceName);
        for (MdnsServiceBrowserListener listener : listeners) {
            listener.onServiceRemoved(serviceInstanceName);
        }
    }

    private boolean shouldRemoveServiceAfterTtlExpires() {
        if (removeServiceAfterTtlExpires) {
            return true;
        }
        return allowSearchOptionsToRemoveExpiredService
                && searchOptions != null
                && searchOptions.removeExpiredService();
    }

    @VisibleForTesting
    MdnsPacketWriter createMdnsPacketWriter() {
        return new MdnsPacketWriter(DEFAULT_MTU);
    }

    // A configuration for the PeriodicalQueryTask that contains parameters to build a query packet.
    // Call to getConfigForNextRun returns a config that can be used to build the next query task.
    @VisibleForTesting
    static class QueryTaskConfig {

        private static final int INITIAL_TIME_BETWEEN_BURSTS_MS =
                (int) MdnsConfigs.initialTimeBetweenBurstsMs();
        private static final int TIME_BETWEEN_BURSTS_MS = (int) MdnsConfigs.timeBetweenBurstsMs();
        private static final int QUERIES_PER_BURST = (int) MdnsConfigs.queriesPerBurst();
        private static final int TIME_BETWEEN_QUERIES_IN_BURST_MS =
                (int) MdnsConfigs.timeBetweenQueriesInBurstMs();
        private static final int QUERIES_PER_BURST_PASSIVE_MODE =
                (int) MdnsConfigs.queriesPerBurstPassive();
        private static final int UNSIGNED_SHORT_MAX_VALUE = 65536;
        // The following fields are used by QueryTask so we need to test them.
        @VisibleForTesting
        final List<String> subtypes;
        private final boolean alwaysAskForUnicastResponse =
                MdnsConfigs.alwaysAskForUnicastResponseInEachBurst();
        private final boolean usePassiveMode;
        private final long sessionId;
        @VisibleForTesting
        int transactionId;
        @VisibleForTesting
        boolean expectUnicastResponse;
        private int queriesPerBurst;
        private int timeBetweenBurstsInMs;
        private int burstCounter;
        private int timeToRunNextTaskInMs;
        private boolean isFirstBurst;

        QueryTaskConfig(@NonNull Collection<String> subtypes, boolean usePassiveMode,
                long sessionId) {
            this.usePassiveMode = usePassiveMode;
            this.subtypes = new ArrayList<>(subtypes);
            this.queriesPerBurst = QUERIES_PER_BURST;
            this.burstCounter = 0;
            this.transactionId = 1;
            this.expectUnicastResponse = true;
            this.isFirstBurst = true;
            this.sessionId = sessionId;
            // Config the scan frequency based on the scan mode.
            if (this.usePassiveMode) {
                // In passive scan mode, sends a single burst of QUERIES_PER_BURST queries, and then
                // in each TIME_BETWEEN_BURSTS interval, sends QUERIES_PER_BURST_PASSIVE_MODE
                // queries.
                this.timeBetweenBurstsInMs = TIME_BETWEEN_BURSTS_MS;
            } else {
                // In active scan mode, sends a burst of QUERIES_PER_BURST queries,
                // TIME_BETWEEN_QUERIES_IN_BURST_MS apart, then waits for the scan interval, and
                // then repeats. The scan interval starts as INITIAL_TIME_BETWEEN_BURSTS_MS and
                // doubles until it maxes out at TIME_BETWEEN_BURSTS_MS.
                this.timeBetweenBurstsInMs = INITIAL_TIME_BETWEEN_BURSTS_MS;
            }
        }

        QueryTaskConfig getConfigForNextRun() {
            if (++transactionId > UNSIGNED_SHORT_MAX_VALUE) {
                transactionId = 1;
            }
            // Only the first query expects uni-cast response.
            expectUnicastResponse = false;
            if (++burstCounter == queriesPerBurst) {
                burstCounter = 0;

                if (alwaysAskForUnicastResponse) {
                    expectUnicastResponse = true;
                }
                // In passive scan mode, sends a single burst of QUERIES_PER_BURST queries, and
                // then in each TIME_BETWEEN_BURSTS interval, sends QUERIES_PER_BURST_PASSIVE_MODE
                // queries.
                if (isFirstBurst) {
                    isFirstBurst = false;
                    if (usePassiveMode) {
                        queriesPerBurst = QUERIES_PER_BURST_PASSIVE_MODE;
                    }
                }
                // In active scan mode, sends a burst of QUERIES_PER_BURST queries,
                // TIME_BETWEEN_QUERIES_IN_BURST_MS apart, then waits for the scan interval, and
                // then repeats. The scan interval starts as INITIAL_TIME_BETWEEN_BURSTS_MS and
                // doubles until it maxes out at TIME_BETWEEN_BURSTS_MS.
                timeToRunNextTaskInMs = timeBetweenBurstsInMs;
                if (timeBetweenBurstsInMs < TIME_BETWEEN_BURSTS_MS) {
                    timeBetweenBurstsInMs = Math.min(timeBetweenBurstsInMs * 2,
                            TIME_BETWEEN_BURSTS_MS);
                }
            } else {
                timeToRunNextTaskInMs = TIME_BETWEEN_QUERIES_IN_BURST_MS;
            }
            return this;
        }
    }

    // A FutureTask that enqueues a single query, and schedule a new FutureTask for the next task.
    private class QueryTask implements Runnable {

        private final QueryTaskConfig config;

        QueryTask(@NonNull QueryTaskConfig config) {
            this.config = config;
        }

        @Override
        public void run() {
            Pair<Integer, List<String>> result;
            try {
                result =
                        new EnqueueMdnsQueryCallable(
                                socketClient,
                                createMdnsPacketWriter(),
                                serviceType,
                                config.subtypes,
                                config.expectUnicastResponse,
                                config.transactionId)
                                .call();
            } catch (Exception e) {
                LOGGER.e(String.format("Failed to run EnqueueMdnsQueryCallable for subtype: %s",
                        TextUtils.join(",", config.subtypes)), e);
                result = null;
            }
            synchronized (lock) {
                if (MdnsConfigs.useSessionIdToScheduleMdnsTask()) {
                    // In case that the task is not canceled successfully, use session ID to check
                    // if this task should continue to schedule more.
                    if (config.sessionId != currentSessionId) {
                        return;
                    }
                }

                if (MdnsConfigs.shouldCancelScanTaskWhenFutureIsNull()) {
                    if (requestTaskFuture == null) {
                        // If requestTaskFuture is set to null, the task is cancelled. We can't use
                        // isCancelled() here because this QueryTask is different from the future
                        // that is returned from executor.schedule(). See b/71646910.
                        return;
                    }
                }
                if ((result != null)) {
                    for (MdnsServiceBrowserListener listener : listeners) {
                        listener.onDiscoveryQuerySent(result.second, result.first);
                    }
                }
                if (shouldRemoveServiceAfterTtlExpires()) {
                    Iterator<MdnsResponse> iter = instanceNameToResponse.values().iterator();
                    while (iter.hasNext()) {
                        MdnsResponse existingResponse = iter.next();
                        if (existingResponse.isComplete()
                                && existingResponse
                                .getServiceRecord()
                                .getRemainingTTL(SystemClock.elapsedRealtime())
                                == 0) {
                            iter.remove();
                            for (MdnsServiceBrowserListener listener : listeners) {
                                listener.onServiceRemoved(
                                        existingResponse.getServiceInstanceName());
                            }
                        }
                    }
                }
                QueryTaskConfig config = this.config.getConfigForNextRun();
                requestTaskFuture =
                        executor.schedule(
                                new QueryTask(config), config.timeToRunNextTaskInMs, MILLISECONDS);
            }
        }
    }
}