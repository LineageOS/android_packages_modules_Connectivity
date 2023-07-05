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

import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnHandlerThread;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Instance of this class sends and receives mDNS packets of a given service type and invoke
 * registered {@link MdnsServiceBrowserListener} instances.
 */
public class MdnsServiceTypeClient {

    private static final String TAG = MdnsServiceTypeClient.class.getSimpleName();
    private static final int DEFAULT_MTU = 1500;

    private final String serviceType;
    private final String[] serviceTypeLabels;
    private final MdnsSocketClientBase socketClient;
    private final MdnsResponseDecoder responseDecoder;
    private final ScheduledExecutorService executor;
    @NonNull private final SocketKey socketKey;
    @NonNull private final SharedLog sharedLog;
    @NonNull private final Handler handler;
    private final Object lock = new Object();
    private final ArrayMap<MdnsServiceBrowserListener, MdnsSearchOptions> listeners =
            new ArrayMap<>();
    // TODO: change instanceNameToResponse to TreeMap with case insensitive comparator.
    @GuardedBy("lock")
    private final Map<String, MdnsResponse> instanceNameToResponse = new HashMap<>();
    private final boolean removeServiceAfterTtlExpires =
            MdnsConfigs.removeServiceAfterTtlExpires();
    private final MdnsResponseDecoder.Clock clock;

    @Nullable private MdnsSearchOptions searchOptions;

    // The session ID increases when startSendAndReceive() is called where we schedule a
    // QueryTask for
    // new subtypes. It stays the same between packets for same subtypes.
    private long currentSessionId = 0;

    @GuardedBy("lock")
    @Nullable
    private Future<?> nextQueryTaskFuture;

    @GuardedBy("lock")
    @Nullable
    private QueryTask lastScheduledTask;

    @GuardedBy("lock")
    private long lastSentTime;

    /**
     * Constructor of {@link MdnsServiceTypeClient}.
     *
     * @param socketClient Sends and receives mDNS packet.
     * @param executor         A {@link ScheduledExecutorService} used to schedule query tasks.
     */
    public MdnsServiceTypeClient(
            @NonNull String serviceType,
            @NonNull MdnsSocketClientBase socketClient,
            @NonNull ScheduledExecutorService executor,
            @NonNull SocketKey socketKey,
            @NonNull SharedLog sharedLog,
            @NonNull Looper looper) {
        this(serviceType, socketClient, executor, new MdnsResponseDecoder.Clock(), socketKey,
                sharedLog, looper);
    }

    @VisibleForTesting
    public MdnsServiceTypeClient(
            @NonNull String serviceType,
            @NonNull MdnsSocketClientBase socketClient,
            @NonNull ScheduledExecutorService executor,
            @NonNull MdnsResponseDecoder.Clock clock,
            @NonNull SocketKey socketKey,
            @NonNull SharedLog sharedLog,
            @NonNull Looper looper) {
        this.serviceType = serviceType;
        this.socketClient = socketClient;
        this.executor = executor;
        this.serviceTypeLabels = TextUtils.split(serviceType, "\\.");
        this.responseDecoder = new MdnsResponseDecoder(clock, serviceTypeLabels);
        this.clock = clock;
        this.socketKey = socketKey;
        this.sharedLog = sharedLog;
        this.handler = new Handler(looper);
    }

    private static MdnsServiceInfo buildMdnsServiceInfoFromResponse(
            @NonNull MdnsResponse response, @NonNull String[] serviceTypeLabels) {
        String[] hostName = null;
        int port = 0;
        if (response.hasServiceRecord()) {
            hostName = response.getServiceRecord().getServiceHost();
            port = response.getServiceRecord().getServicePort();
        }

        final List<String> ipv4Addresses = new ArrayList<>();
        final List<String> ipv6Addresses = new ArrayList<>();
        if (response.hasInet4AddressRecord()) {
            for (MdnsInetAddressRecord inetAddressRecord : response.getInet4AddressRecords()) {
                final Inet4Address inet4Address = inetAddressRecord.getInet4Address();
                ipv4Addresses.add((inet4Address == null) ? null : inet4Address.getHostAddress());
            }
        }
        if (response.hasInet6AddressRecord()) {
            for (MdnsInetAddressRecord inetAddressRecord : response.getInet6AddressRecords()) {
                final Inet6Address inet6Address = inetAddressRecord.getInet6Address();
                ipv6Addresses.add((inet6Address == null) ? null : inet6Address.getHostAddress());
            }
        }
        String serviceInstanceName = response.getServiceInstanceName();
        if (serviceInstanceName == null) {
            throw new IllegalStateException(
                    "mDNS response must have non-null service instance name");
        }
        List<String> textStrings = null;
        List<MdnsServiceInfo.TextEntry> textEntries = null;
        if (response.hasTextRecord()) {
            textStrings = response.getTextRecord().getStrings();
            textEntries = response.getTextRecord().getEntries();
        }
        // TODO: Throw an error message if response doesn't have Inet6 or Inet4 address.
        return new MdnsServiceInfo(
                serviceInstanceName,
                serviceTypeLabels,
                response.getSubtypes(),
                hostName,
                port,
                ipv4Addresses,
                ipv6Addresses,
                textStrings,
                textEntries,
                response.getInterfaceIndex(),
                response.getNetwork());
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
        ensureRunningOnHandlerThread(handler);
        synchronized (lock) {
            this.searchOptions = searchOptions;
            boolean hadReply = false;
            if (listeners.put(listener, searchOptions) == null) {
                for (MdnsResponse existingResponse : instanceNameToResponse.values()) {
                    if (!responseMatchesOptions(existingResponse, searchOptions)) continue;
                    final MdnsServiceInfo info =
                            buildMdnsServiceInfoFromResponse(existingResponse, serviceTypeLabels);
                    listener.onServiceNameDiscovered(info);
                    if (existingResponse.isComplete()) {
                        listener.onServiceFound(info);
                        hadReply = true;
                    }
                }
            }
            // Cancel the next scheduled periodical task.
            if (nextQueryTaskFuture != null) {
                cancelRequestTaskLocked();
            }
            // Keep tracking the ScheduledFuture for the task so we can cancel it if caller is not
            // interested anymore.
            final QueryTaskConfig taskConfig = new QueryTaskConfig(
                    searchOptions.getSubtypes(),
                    searchOptions.isPassiveMode(),
                    searchOptions.onlyUseIpv6OnIpv6OnlyNetworks(),
                    searchOptions.numOfQueriesBeforeBackoff(),
                    socketKey);
            final long now = clock.elapsedRealtime();
            if (lastSentTime == 0) {
                lastSentTime = now;
            }
            if (hadReply) {
                final QueryTaskConfig queryTaskConfig = taskConfig.getConfigForNextRun();
                final long minRemainingTtl = getMinRemainingTtlLocked(now);
                final long timeToRun = now + queryTaskConfig.delayUntilNextTaskWithoutBackoffMs;
                nextQueryTaskFuture = scheduleNextRunLocked(queryTaskConfig,
                        minRemainingTtl, now, timeToRun, currentSessionId);
            } else {
                lastScheduledTask = new QueryTask(taskConfig,
                        now /* timeToRun */,
                        now + getMinRemainingTtlLocked(now)/* minTtlExpirationTimeWhenScheduled */,
                        currentSessionId);
                nextQueryTaskFuture = executor.submit(lastScheduledTask);
            }
        }
    }

    @GuardedBy("lock")
    private void cancelRequestTaskLocked() {
        final boolean canceled = nextQueryTaskFuture.cancel(true);
        sharedLog.log("task canceled:" + canceled + ", current session: " + currentSessionId
                + " task hashcode: " + getHexString(nextQueryTaskFuture));
        ++currentSessionId;
        nextQueryTaskFuture = null;
        lastScheduledTask = null;
    }

    private static String getHexString(Object o) {
        return Integer.toHexString(System.identityHashCode(o));
    }

    private boolean responseMatchesOptions(@NonNull MdnsResponse response,
            @NonNull MdnsSearchOptions options) {
        final boolean matchesInstanceName = options.getResolveInstanceName() == null
                // DNS is case-insensitive, so ignore case in the comparison
                || MdnsUtils.equalsIgnoreDnsCase(options.getResolveInstanceName(),
                response.getServiceInstanceName());

        // If discovery is requiring some subtypes, the response must have one that matches a
        // requested one.
        final List<String> responseSubtypes = response.getSubtypes() == null
                ? Collections.emptyList() : response.getSubtypes();
        final boolean matchesSubtype = options.getSubtypes().size() == 0
                || CollectionUtils.any(options.getSubtypes(), requiredSub ->
                CollectionUtils.any(responseSubtypes, actualSub ->
                        MdnsUtils.equalsIgnoreDnsCase(
                                MdnsConstants.SUBTYPE_PREFIX + requiredSub, actualSub)));

        return matchesInstanceName && matchesSubtype;
    }

    /**
     * Unregisters {@code listener} from receiving discovery event of mDNS service instances.
     *
     * @param listener The {@link MdnsServiceBrowserListener} to unregister.
     * @return {@code true} if no listener is registered with this client after unregistering {@code
     * listener}. Otherwise returns {@code false}.
     */
    public boolean stopSendAndReceive(@NonNull MdnsServiceBrowserListener listener) {
        ensureRunningOnHandlerThread(handler);
        synchronized (lock) {
            if (listeners.remove(listener) == null) {
                return listeners.isEmpty();
            }
            if (listeners.isEmpty() && nextQueryTaskFuture != null) {
                cancelRequestTaskLocked();
            }
            return listeners.isEmpty();
        }
    }

    /**
     * Process an incoming response packet.
     */
    public synchronized void processResponse(@NonNull MdnsPacket packet,
            @NonNull SocketKey socketKey) {
        ensureRunningOnHandlerThread(handler);
        synchronized (lock) {
            // Augment the list of current known responses, and generated responses for resolve
            // requests if there is no known response
            final List<MdnsResponse> currentList = new ArrayList<>(instanceNameToResponse.values());
            List<MdnsResponse> additionalResponses = makeResponsesForResolve(socketKey);
            for (MdnsResponse additionalResponse : additionalResponses) {
                if (!instanceNameToResponse.containsKey(
                        additionalResponse.getServiceInstanceName())) {
                    currentList.add(additionalResponse);
                }
            }
            final Pair<ArraySet<MdnsResponse>, ArrayList<MdnsResponse>> augmentedResult =
                    responseDecoder.augmentResponses(packet, currentList,
                            socketKey.getInterfaceIndex(), socketKey.getNetwork());

            final ArraySet<MdnsResponse> modifiedResponse = augmentedResult.first;
            final ArrayList<MdnsResponse> allResponses = augmentedResult.second;

            for (MdnsResponse response : allResponses) {
                if (modifiedResponse.contains(response)) {
                    if (response.isGoodbye()) {
                        onGoodbyeReceivedLocked(response.getServiceInstanceName());
                    } else {
                        onResponseModifiedLocked(response);
                    }
                } else if (instanceNameToResponse.containsKey(response.getServiceInstanceName())) {
                    // If the response is not modified and already in the cache. The cache will
                    // need to be updated to refresh the last receipt time.
                    instanceNameToResponse.put(response.getServiceInstanceName(), response);
                }
            }
            if (nextQueryTaskFuture != null && lastScheduledTask != null
                    && lastScheduledTask.config.shouldUseQueryBackoff()) {
                final long now = clock.elapsedRealtime();
                final long minRemainingTtl = getMinRemainingTtlLocked(now);
                final long timeToRun = calculateTimeToRun(lastScheduledTask,
                        lastScheduledTask.config, now,
                        minRemainingTtl, lastSentTime);
                if (timeToRun > lastScheduledTask.timeToRun) {
                    QueryTaskConfig lastTaskConfig = lastScheduledTask.config;
                    cancelRequestTaskLocked();
                    nextQueryTaskFuture = scheduleNextRunLocked(lastTaskConfig, minRemainingTtl,
                            now, timeToRun, currentSessionId);
                }
            }
        }
    }

    public synchronized void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) {
        ensureRunningOnHandlerThread(handler);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.keyAt(i).onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    /** Notify all services are removed because the socket is destroyed. */
    public void notifySocketDestroyed() {
        ensureRunningOnHandlerThread(handler);
        synchronized (lock) {
            for (MdnsResponse response : instanceNameToResponse.values()) {
                final String name = response.getServiceInstanceName();
                if (name == null) continue;
                for (int i = 0; i < listeners.size(); i++) {
                    if (!responseMatchesOptions(response, listeners.valueAt(i))) continue;
                    final MdnsServiceBrowserListener listener = listeners.keyAt(i);
                    final MdnsServiceInfo serviceInfo =
                            buildMdnsServiceInfoFromResponse(response, serviceTypeLabels);
                    if (response.isComplete()) {
                        sharedLog.log("Socket destroyed. onServiceRemoved: " + name);
                        listener.onServiceRemoved(serviceInfo);
                    }
                    sharedLog.log("Socket destroyed. onServiceNameRemoved: " + name);
                    listener.onServiceNameRemoved(serviceInfo);
                }
            }

            if (nextQueryTaskFuture != null) {
                cancelRequestTaskLocked();
            }
        }
    }

    @GuardedBy("lock")
    private void onResponseModifiedLocked(@NonNull MdnsResponse response) {
        final String serviceInstanceName = response.getServiceInstanceName();
        final MdnsResponse currentResponse =
                instanceNameToResponse.get(serviceInstanceName);

        boolean newServiceFound = false;
        boolean serviceBecomesComplete = false;
        if (currentResponse == null) {
            newServiceFound = true;
            if (serviceInstanceName != null) {
                instanceNameToResponse.put(serviceInstanceName, response);
            }
        } else {
            boolean before = currentResponse.isComplete();
            instanceNameToResponse.put(serviceInstanceName, response);
            boolean after = response.isComplete();
            serviceBecomesComplete = !before && after;
        }
        sharedLog.i(String.format(
                "Handling response from service: %s, newServiceFound: %b, serviceBecomesComplete:"
                        + " %b, responseIsComplete: %b",
                serviceInstanceName, newServiceFound, serviceBecomesComplete,
                response.isComplete()));
        MdnsServiceInfo serviceInfo =
                buildMdnsServiceInfoFromResponse(response, serviceTypeLabels);

        for (int i = 0; i < listeners.size(); i++) {
            if (!responseMatchesOptions(response, listeners.valueAt(i))) continue;
            final MdnsServiceBrowserListener listener = listeners.keyAt(i);
            if (newServiceFound) {
                sharedLog.log("onServiceNameDiscovered: " + serviceInfo);
                listener.onServiceNameDiscovered(serviceInfo);
            }

            if (response.isComplete()) {
                if (newServiceFound || serviceBecomesComplete) {
                    sharedLog.log("onServiceFound: " + serviceInfo);
                    listener.onServiceFound(serviceInfo);
                } else {
                    sharedLog.log("onServiceUpdated: " + serviceInfo);
                    listener.onServiceUpdated(serviceInfo);
                }
            }
        }
    }

    @GuardedBy("lock")
    private void onGoodbyeReceivedLocked(@Nullable String serviceInstanceName) {
        final MdnsResponse response = instanceNameToResponse.remove(serviceInstanceName);
        if (response == null) {
            return;
        }
        for (int i = 0; i < listeners.size(); i++) {
            if (!responseMatchesOptions(response, listeners.valueAt(i))) continue;
            final MdnsServiceBrowserListener listener = listeners.keyAt(i);
            final MdnsServiceInfo serviceInfo =
                    buildMdnsServiceInfoFromResponse(response, serviceTypeLabels);
            if (response.isComplete()) {
                sharedLog.log("onServiceRemoved: " + serviceInfo);
                listener.onServiceRemoved(serviceInfo);
            }
            sharedLog.log("onServiceNameRemoved: " + serviceInfo);
            listener.onServiceNameRemoved(serviceInfo);
        }
    }

    private boolean shouldRemoveServiceAfterTtlExpires() {
        if (removeServiceAfterTtlExpires) {
            return true;
        }
        return searchOptions != null && searchOptions.removeExpiredService();
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
        private final boolean onlyUseIpv6OnIpv6OnlyNetworks;
        private final int numOfQueriesBeforeBackoff;
        @VisibleForTesting
        final int transactionId;
        @VisibleForTesting
        final boolean expectUnicastResponse;
        private final int queriesPerBurst;
        private final int timeBetweenBurstsInMs;
        private final int burstCounter;
        private final long delayUntilNextTaskWithoutBackoffMs;
        private final boolean isFirstBurst;
        private final long queryCount;
        @NonNull private final SocketKey socketKey;


        QueryTaskConfig(@NonNull QueryTaskConfig other, long queryCount, int transactionId,
                boolean expectUnicastResponse, boolean isFirstBurst, int burstCounter,
                int queriesPerBurst, int timeBetweenBurstsInMs,
                long delayUntilNextTaskWithoutBackoffMs) {
            this.subtypes = new ArrayList<>(other.subtypes);
            this.usePassiveMode = other.usePassiveMode;
            this.onlyUseIpv6OnIpv6OnlyNetworks = other.onlyUseIpv6OnIpv6OnlyNetworks;
            this.numOfQueriesBeforeBackoff = other.numOfQueriesBeforeBackoff;
            this.transactionId = transactionId;
            this.expectUnicastResponse = expectUnicastResponse;
            this.queriesPerBurst = queriesPerBurst;
            this.timeBetweenBurstsInMs = timeBetweenBurstsInMs;
            this.burstCounter = burstCounter;
            this.delayUntilNextTaskWithoutBackoffMs = delayUntilNextTaskWithoutBackoffMs;
            this.isFirstBurst = isFirstBurst;
            this.queryCount = queryCount;
            this.socketKey = other.socketKey;
        }
        QueryTaskConfig(@NonNull Collection<String> subtypes,
                boolean usePassiveMode,
                boolean onlyUseIpv6OnIpv6OnlyNetworks,
                int numOfQueriesBeforeBackoff,
                @Nullable SocketKey socketKey) {
            this.usePassiveMode = usePassiveMode;
            this.onlyUseIpv6OnIpv6OnlyNetworks = onlyUseIpv6OnIpv6OnlyNetworks;
            this.numOfQueriesBeforeBackoff = numOfQueriesBeforeBackoff;
            this.subtypes = new ArrayList<>(subtypes);
            this.queriesPerBurst = QUERIES_PER_BURST;
            this.burstCounter = 0;
            this.transactionId = 1;
            this.expectUnicastResponse = true;
            this.isFirstBurst = true;
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
            this.socketKey = socketKey;
            this.queryCount = 0;
            this.delayUntilNextTaskWithoutBackoffMs = TIME_BETWEEN_QUERIES_IN_BURST_MS;
        }

        QueryTaskConfig getConfigForNextRun() {
            long newQueryCount = queryCount + 1;
            int newTransactionId = transactionId + 1;
            if (newTransactionId > UNSIGNED_SHORT_MAX_VALUE) {
                newTransactionId = 1;
            }
            boolean newExpectUnicastResponse = false;
            boolean newIsFirstBurst = isFirstBurst;
            int newQueriesPerBurst = queriesPerBurst;
            int newBurstCounter = burstCounter + 1;
            long newDelayUntilNextTaskWithoutBackoffMs = delayUntilNextTaskWithoutBackoffMs;
            int newTimeBetweenBurstsInMs = timeBetweenBurstsInMs;
            // Only the first query expects uni-cast response.
            if (newBurstCounter == queriesPerBurst) {
                newBurstCounter = 0;

                if (alwaysAskForUnicastResponse) {
                    newExpectUnicastResponse = true;
                }
                // In passive scan mode, sends a single burst of QUERIES_PER_BURST queries, and
                // then in each TIME_BETWEEN_BURSTS interval, sends QUERIES_PER_BURST_PASSIVE_MODE
                // queries.
                if (isFirstBurst) {
                    newIsFirstBurst = false;
                    if (usePassiveMode) {
                        newQueriesPerBurst = QUERIES_PER_BURST_PASSIVE_MODE;
                    }
                }
                // In active scan mode, sends a burst of QUERIES_PER_BURST queries,
                // TIME_BETWEEN_QUERIES_IN_BURST_MS apart, then waits for the scan interval, and
                // then repeats. The scan interval starts as INITIAL_TIME_BETWEEN_BURSTS_MS and
                // doubles until it maxes out at TIME_BETWEEN_BURSTS_MS.
                newDelayUntilNextTaskWithoutBackoffMs = timeBetweenBurstsInMs;
                if (timeBetweenBurstsInMs < TIME_BETWEEN_BURSTS_MS) {
                    newTimeBetweenBurstsInMs = Math.min(timeBetweenBurstsInMs * 2,
                            TIME_BETWEEN_BURSTS_MS);
                }
            } else {
                newDelayUntilNextTaskWithoutBackoffMs = TIME_BETWEEN_QUERIES_IN_BURST_MS;
            }
            return new QueryTaskConfig(this, newQueryCount, newTransactionId,
                    newExpectUnicastResponse, newIsFirstBurst, newBurstCounter, newQueriesPerBurst,
                    newTimeBetweenBurstsInMs, newDelayUntilNextTaskWithoutBackoffMs);
        }

        private boolean shouldUseQueryBackoff() {
            // Don't enable backoff mode during the burst or in the first burst
            if (burstCounter != 0 || isFirstBurst) {
                return false;
            }
            return queryCount > numOfQueriesBeforeBackoff;
        }
    }

    private List<MdnsResponse> makeResponsesForResolve(@NonNull SocketKey socketKey) {
        final List<MdnsResponse> resolveResponses = new ArrayList<>();
        for (int i = 0; i < listeners.size(); i++) {
            final String resolveName = listeners.valueAt(i).getResolveInstanceName();
            if (resolveName == null) {
                continue;
            }
            MdnsResponse knownResponse = instanceNameToResponse.get(resolveName);
            if (knownResponse == null) {
                final ArrayList<String> instanceFullName = new ArrayList<>(
                        serviceTypeLabels.length + 1);
                instanceFullName.add(resolveName);
                instanceFullName.addAll(Arrays.asList(serviceTypeLabels));
                knownResponse = new MdnsResponse(
                        0L /* lastUpdateTime */, instanceFullName.toArray(new String[0]),
                        socketKey.getInterfaceIndex(), socketKey.getNetwork());
            }
            resolveResponses.add(knownResponse);
        }
        return resolveResponses;
    }

    // A FutureTask that enqueues a single query, and schedule a new FutureTask for the next task.
    private class QueryTask implements Runnable {

        private final QueryTaskConfig config;
        private final long timeToRun;
        private final long minTtlExpirationTimeWhenScheduled;
        private final long sessionId;

        QueryTask(@NonNull QueryTaskConfig config, long timeToRun,
                long minTtlExpirationTimeWhenScheduled,
                long sessionId) {
            this.config = config;
            this.timeToRun = timeToRun;
            this.minTtlExpirationTimeWhenScheduled = minTtlExpirationTimeWhenScheduled;
            this.sessionId = sessionId;
        }

        @Override
        public void run() {
            final List<MdnsResponse> servicesToResolve;
            final boolean sendDiscoveryQueries;
            synchronized (lock) {
                // The listener is requesting to resolve a service that has no info in
                // cache. Use the provided name to generate a minimal response, so other records are
                // queried to complete it.
                servicesToResolve = makeResponsesForResolve(config.socketKey);
                sendDiscoveryQueries = servicesToResolve.size() < listeners.size();
            }
            Pair<Integer, List<String>> result;
            try {
                result =
                        new EnqueueMdnsQueryCallable(
                                socketClient,
                                createMdnsPacketWriter(),
                                serviceType,
                                config.subtypes,
                                config.expectUnicastResponse,
                                config.transactionId,
                                config.socketKey,
                                config.onlyUseIpv6OnIpv6OnlyNetworks,
                                sendDiscoveryQueries,
                                servicesToResolve,
                                clock)
                                .call();
            } catch (RuntimeException e) {
                sharedLog.e(String.format("Failed to run EnqueueMdnsQueryCallable for subtype: %s",
                        TextUtils.join(",", config.subtypes)), e);
                result = null;
            }
            synchronized (lock) {
                if (MdnsConfigs.useSessionIdToScheduleMdnsTask()) {
                    // In case that the task is not canceled successfully, use session ID to check
                    // if this task should continue to schedule more.
                    if (sessionId != currentSessionId) {
                        return;
                    }
                }

                if (MdnsConfigs.shouldCancelScanTaskWhenFutureIsNull()) {
                    if (nextQueryTaskFuture == null) {
                        // If requestTaskFuture is set to null, the task is cancelled. We can't use
                        // isCancelled() here because this QueryTask is different from the future
                        // that is returned from executor.schedule(). See b/71646910.
                        return;
                    }
                }
                if ((result != null)) {
                    for (int i = 0; i < listeners.size(); i++) {
                        listeners.keyAt(i).onDiscoveryQuerySent(result.second, result.first);
                    }
                }
                if (shouldRemoveServiceAfterTtlExpires()) {
                    Iterator<MdnsResponse> iter = instanceNameToResponse.values().iterator();
                    while (iter.hasNext()) {
                        MdnsResponse existingResponse = iter.next();
                        if (existingResponse.hasServiceRecord()
                                && existingResponse
                                .getServiceRecord()
                                .getRemainingTTL(clock.elapsedRealtime())
                                == 0) {
                            iter.remove();
                            for (int i = 0; i < listeners.size(); i++) {
                                if (!responseMatchesOptions(existingResponse,
                                        listeners.valueAt(i)))  {
                                    continue;
                                }
                                final MdnsServiceBrowserListener listener = listeners.keyAt(i);
                                if (existingResponse.getServiceInstanceName() != null) {
                                    final MdnsServiceInfo serviceInfo =
                                            buildMdnsServiceInfoFromResponse(
                                                    existingResponse, serviceTypeLabels);
                                    if (existingResponse.isComplete()) {
                                        sharedLog.log("TTL expired. onServiceRemoved: "
                                                + serviceInfo);
                                        listener.onServiceRemoved(serviceInfo);
                                    }
                                    sharedLog.log("TTL expired. onServiceNameRemoved: "
                                            + serviceInfo);
                                    listener.onServiceNameRemoved(serviceInfo);
                                }
                            }
                        }
                    }
                }
                QueryTaskConfig nextRunConfig = this.config.getConfigForNextRun();
                final long now = clock.elapsedRealtime();
                lastSentTime = now;
                final long minRemainingTtl = getMinRemainingTtlLocked(now);
                final long timeToRun = calculateTimeToRun(this, nextRunConfig, now,
                        minRemainingTtl, lastSentTime);
                nextQueryTaskFuture = scheduleNextRunLocked(nextRunConfig,
                        minRemainingTtl, now, timeToRun, lastScheduledTask.sessionId);
            }
        }
    }

    private static long calculateTimeToRun(@NonNull QueryTask lastScheduledTask,
            QueryTaskConfig queryTaskConfig, long now, long minRemainingTtl, long lastSentTime) {
        final long baseDelayInMs = queryTaskConfig.delayUntilNextTaskWithoutBackoffMs;
        if (!queryTaskConfig.shouldUseQueryBackoff()) {
            return lastSentTime + baseDelayInMs;
        }
        if (minRemainingTtl <= 0) {
            // There's no service, or there is an expired service. In any case, schedule for the
            // minimum time, which is the base delay.
            return lastSentTime + baseDelayInMs;
        }
        // If the next TTL expiration time hasn't changed, then use previous calculated timeToRun.
        if (lastSentTime < now
                && lastScheduledTask.minTtlExpirationTimeWhenScheduled == now + minRemainingTtl) {
            // Use the original scheduling time if the TTL has not changed, to avoid continuously
            // rescheduling to 80% of the remaining TTL as time passes
            return lastScheduledTask.timeToRun;
        }
        return Math.max(now + (long) (0.8 * minRemainingTtl), lastSentTime + baseDelayInMs);
    }

    @GuardedBy("lock")
    private long getMinRemainingTtlLocked(long now) {
        long minRemainingTtl = Long.MAX_VALUE;
        for (MdnsResponse response : instanceNameToResponse.values()) {
            if (!response.isComplete()) {
                continue;
            }
            long remainingTtl =
                    response.getServiceRecord().getRemainingTTL(now);
            // remainingTtl is <= 0 means the service expired.
            if (remainingTtl <= 0) {
                return 0;
            }
            if (remainingTtl < minRemainingTtl) {
                minRemainingTtl = remainingTtl;
            }
        }
        return minRemainingTtl == Long.MAX_VALUE ? 0 : minRemainingTtl;
    }

    @GuardedBy("lock")
    @NonNull
    private Future<?> scheduleNextRunLocked(@NonNull QueryTaskConfig nextRunConfig,
            long minRemainingTtl,
            long timeWhenScheduled, long timeToRun, long sessionId) {
        lastScheduledTask = new QueryTask(nextRunConfig, timeToRun,
                minRemainingTtl + timeWhenScheduled, sessionId);
        // The timeWhenScheduled could be greater than the timeToRun if the Runnable is delayed.
        long timeToNextTasksWithBackoffInMs = Math.max(timeToRun - timeWhenScheduled, 0);
        sharedLog.log(
                String.format("Next run: sessionId: %d, in %d ms", lastScheduledTask.sessionId,
                        timeToNextTasksWithBackoffInMs));
        return executor.schedule(lastScheduledTask, timeToNextTasksWithBackoffInMs,
                MILLISECONDS);
    }
}