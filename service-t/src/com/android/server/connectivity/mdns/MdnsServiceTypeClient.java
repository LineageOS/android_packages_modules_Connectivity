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

import static com.android.server.connectivity.mdns.MdnsServiceCache.findMatchedResponse;
import static com.android.server.connectivity.mdns.util.MdnsUtils.Clock;
import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnHandlerThread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;

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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Instance of this class sends and receives mDNS packets of a given service type and invoke
 * registered {@link MdnsServiceBrowserListener} instances.
 */
public class MdnsServiceTypeClient {

    private static final String TAG = MdnsServiceTypeClient.class.getSimpleName();
    private static final int DEFAULT_MTU = 1500;
    @VisibleForTesting
    static final int EVENT_START_QUERYTASK = 1;
    static final int EVENT_QUERY_RESULT = 2;
    static final int INVALID_TRANSACTION_ID = -1;

    private final String serviceType;
    private final String[] serviceTypeLabels;
    private final MdnsSocketClientBase socketClient;
    private final MdnsResponseDecoder responseDecoder;
    private final ScheduledExecutorService executor;
    @NonNull private final SocketKey socketKey;
    @NonNull private final SharedLog sharedLog;
    @NonNull private final Handler handler;
    @NonNull private final Dependencies dependencies;
    /**
     * The service caches for each socket. It should be accessed from looper thread only.
     */
    @NonNull private final MdnsServiceCache serviceCache;
    private final ArrayMap<MdnsServiceBrowserListener, MdnsSearchOptions> listeners =
            new ArrayMap<>();
    private final boolean removeServiceAfterTtlExpires =
            MdnsConfigs.removeServiceAfterTtlExpires();
    private final Clock clock;

    @Nullable private MdnsSearchOptions searchOptions;

    // The session ID increases when startSendAndReceive() is called where we schedule a
    // QueryTask for
    // new subtypes. It stays the same between packets for same subtypes.
    private long currentSessionId = 0;

    @Nullable
    private ScheduledQueryTaskArgs lastScheduledQueryTaskArgs;
    private long lastSentTime;

    private class QueryTaskHandler extends Handler {
        QueryTaskHandler(Looper looper) {
            super(looper);
        }

        @Override
        @SuppressWarnings("FutureReturnValueIgnored")
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_START_QUERYTASK: {
                    final ScheduledQueryTaskArgs taskArgs = (ScheduledQueryTaskArgs) msg.obj;
                    // QueryTask should be run immediately after being created (not be scheduled in
                    // advance). Because the result of "makeResponsesForResolve" depends on answers
                    // that were received before it is called, so to take into account all answers
                    // before sending the query, it needs to be called just before sending it.
                    final List<MdnsResponse> servicesToResolve = makeResponsesForResolve(socketKey);
                    final QueryTask queryTask = new QueryTask(taskArgs, servicesToResolve,
                            servicesToResolve.size() < listeners.size() /* sendDiscoveryQueries */);
                    executor.submit(queryTask);
                    break;
                }
                case EVENT_QUERY_RESULT: {
                    final QuerySentArguments sentResult = (QuerySentArguments) msg.obj;
                    // If a task is cancelled while the Executor is running it, EVENT_QUERY_RESULT
                    // will still be sent when it ends. So use session ID to check if this task
                    // should continue to schedule more.
                    if (sentResult.taskArgs.sessionId != currentSessionId) {
                        break;
                    }

                    if ((sentResult.transactionId != INVALID_TRANSACTION_ID)) {
                        for (int i = 0; i < listeners.size(); i++) {
                            listeners.keyAt(i).onDiscoveryQuerySent(
                                    sentResult.subTypes, sentResult.transactionId);
                        }
                    }

                    tryRemoveServiceAfterTtlExpires();

                    final QueryTaskConfig nextRunConfig =
                            sentResult.taskArgs.config.getConfigForNextRun();
                    final long now = clock.elapsedRealtime();
                    lastSentTime = now;
                    final long minRemainingTtl = getMinRemainingTtl(now);
                    final long timeToRun = calculateTimeToRun(lastScheduledQueryTaskArgs,
                            nextRunConfig, now, minRemainingTtl, lastSentTime);
                    scheduleNextRun(nextRunConfig, minRemainingTtl, now, timeToRun,
                            lastScheduledQueryTaskArgs.sessionId);
                    break;
                }
                default:
                    sharedLog.e("Unrecognized event " + msg.what);
                    break;
            }
        }
    }

    /**
     * Dependencies of MdnsServiceTypeClient, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * @see Handler#sendMessageDelayed(Message, long)
         */
        public void sendMessageDelayed(@NonNull Handler handler, @NonNull Message message,
                long delayMillis) {
            handler.sendMessageDelayed(message, delayMillis);
        }

        /**
         * @see Handler#removeMessages(int)
         */
        public void removeMessages(@NonNull Handler handler, int what) {
            handler.removeMessages(what);
        }

        /**
         * @see Handler#hasMessages(int)
         */
        public boolean hasMessages(@NonNull Handler handler, int what) {
            return handler.hasMessages(what);
        }

        /**
         * @see Handler#post(Runnable)
         */
        public void sendMessage(@NonNull Handler handler, @NonNull Message message) {
            handler.sendMessage(message);
        }
    }

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
            @NonNull Looper looper,
            @NonNull MdnsServiceCache serviceCache) {
        this(serviceType, socketClient, executor, new Clock(), socketKey, sharedLog, looper,
                new Dependencies(), serviceCache);
    }

    @VisibleForTesting
    public MdnsServiceTypeClient(
            @NonNull String serviceType,
            @NonNull MdnsSocketClientBase socketClient,
            @NonNull ScheduledExecutorService executor,
            @NonNull Clock clock,
            @NonNull SocketKey socketKey,
            @NonNull SharedLog sharedLog,
            @NonNull Looper looper,
            @NonNull Dependencies dependencies,
            @NonNull MdnsServiceCache serviceCache) {
        this.serviceType = serviceType;
        this.socketClient = socketClient;
        this.executor = executor;
        this.serviceTypeLabels = TextUtils.split(serviceType, "\\.");
        this.responseDecoder = new MdnsResponseDecoder(clock, serviceTypeLabels);
        this.clock = clock;
        this.socketKey = socketKey;
        this.sharedLog = sharedLog;
        this.handler = new QueryTaskHandler(looper);
        this.dependencies = dependencies;
        this.serviceCache = serviceCache;
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
    @SuppressWarnings("FutureReturnValueIgnored")
    public void startSendAndReceive(
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        ensureRunningOnHandlerThread(handler);
        this.searchOptions = searchOptions;
        boolean hadReply = false;
        if (listeners.put(listener, searchOptions) == null) {
            for (MdnsResponse existingResponse :
                    serviceCache.getCachedServices(serviceType, socketKey)) {
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
        // Remove the next scheduled periodical task.
        removeScheduledTask();
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
            final long minRemainingTtl = getMinRemainingTtl(now);
            final long timeToRun = now + queryTaskConfig.delayUntilNextTaskWithoutBackoffMs;
            scheduleNextRun(
                    queryTaskConfig, minRemainingTtl, now, timeToRun, currentSessionId);
        } else {
            final List<MdnsResponse> servicesToResolve = makeResponsesForResolve(socketKey);
            lastScheduledQueryTaskArgs = new ScheduledQueryTaskArgs(taskConfig, now /* timeToRun */,
                    now + getMinRemainingTtl(now)/* minTtlExpirationTimeWhenScheduled */,
                    currentSessionId);
            final QueryTask queryTask = new QueryTask(lastScheduledQueryTaskArgs, servicesToResolve,
                    servicesToResolve.size() < listeners.size() /* sendDiscoveryQueries */);
            executor.submit(queryTask);
        }
    }

    private void removeScheduledTask() {
        dependencies.removeMessages(handler, EVENT_START_QUERYTASK);
        sharedLog.log("Remove EVENT_START_QUERYTASK"
                + ", current session: " + currentSessionId);
        ++currentSessionId;
        lastScheduledQueryTaskArgs = null;
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
        if (listeners.remove(listener) == null) {
            return listeners.isEmpty();
        }
        if (listeners.isEmpty()) {
            removeScheduledTask();
        }
        return listeners.isEmpty();
    }

    /**
     * Process an incoming response packet.
     */
    public synchronized void processResponse(@NonNull MdnsPacket packet,
            @NonNull SocketKey socketKey) {
        ensureRunningOnHandlerThread(handler);
        // Augment the list of current known responses, and generated responses for resolve
        // requests if there is no known response
        final List<MdnsResponse> cachedList =
                serviceCache.getCachedServices(serviceType, socketKey);
        final List<MdnsResponse> currentList = new ArrayList<>(cachedList);
        List<MdnsResponse> additionalResponses = makeResponsesForResolve(socketKey);
        for (MdnsResponse additionalResponse : additionalResponses) {
            if (findMatchedResponse(
                    cachedList, additionalResponse.getServiceInstanceName()) == null) {
                currentList.add(additionalResponse);
            }
        }
        final Pair<ArraySet<MdnsResponse>, ArrayList<MdnsResponse>> augmentedResult =
                responseDecoder.augmentResponses(packet, currentList,
                        socketKey.getInterfaceIndex(), socketKey.getNetwork());

        final ArraySet<MdnsResponse> modifiedResponse = augmentedResult.first;
        final ArrayList<MdnsResponse> allResponses = augmentedResult.second;

        for (MdnsResponse response : allResponses) {
            final String serviceInstanceName = response.getServiceInstanceName();
            if (modifiedResponse.contains(response)) {
                if (response.isGoodbye()) {
                    onGoodbyeReceived(serviceInstanceName);
                } else {
                    onResponseModified(response);
                }
            } else if (findMatchedResponse(cachedList, serviceInstanceName) != null) {
                // If the response is not modified and already in the cache. The cache will
                // need to be updated to refresh the last receipt time.
                serviceCache.addOrUpdateService(serviceType, socketKey, response);
            }
        }
        if (dependencies.hasMessages(handler, EVENT_START_QUERYTASK)
                && lastScheduledQueryTaskArgs != null
                && lastScheduledQueryTaskArgs.config.shouldUseQueryBackoff()) {
            final long now = clock.elapsedRealtime();
            final long minRemainingTtl = getMinRemainingTtl(now);
            final long timeToRun = calculateTimeToRun(lastScheduledQueryTaskArgs,
                    lastScheduledQueryTaskArgs.config, now,
                    minRemainingTtl, lastSentTime);
            if (timeToRun > lastScheduledQueryTaskArgs.timeToRun) {
                QueryTaskConfig lastTaskConfig = lastScheduledQueryTaskArgs.config;
                removeScheduledTask();
                scheduleNextRun(lastTaskConfig, minRemainingTtl, now, timeToRun, currentSessionId);
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
        for (MdnsResponse response : serviceCache.getCachedServices(serviceType, socketKey)) {
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
        removeScheduledTask();
    }

    private void onResponseModified(@NonNull MdnsResponse response) {
        final String serviceInstanceName = response.getServiceInstanceName();
        final MdnsResponse currentResponse =
                serviceCache.getCachedService(serviceInstanceName, serviceType, socketKey);

        boolean newServiceFound = false;
        boolean serviceBecomesComplete = false;
        if (currentResponse == null) {
            newServiceFound = true;
            if (serviceInstanceName != null) {
                serviceCache.addOrUpdateService(serviceType, socketKey, response);
            }
        } else {
            boolean before = currentResponse.isComplete();
            serviceCache.addOrUpdateService(serviceType, socketKey, response);
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

    private void onGoodbyeReceived(@Nullable String serviceInstanceName) {
        final MdnsResponse response =
                serviceCache.removeService(serviceInstanceName, serviceType, socketKey);
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
            MdnsResponse knownResponse =
                    serviceCache.getCachedService(resolveName, serviceType, socketKey);
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

    private void tryRemoveServiceAfterTtlExpires() {
        if (!shouldRemoveServiceAfterTtlExpires()) return;

        Iterator<MdnsResponse> iter =
                serviceCache.getCachedServices(serviceType, socketKey).iterator();
        while (iter.hasNext()) {
            MdnsResponse existingResponse = iter.next();
            final String serviceInstanceName = existingResponse.getServiceInstanceName();
            if (existingResponse.hasServiceRecord()
                    && existingResponse.getServiceRecord()
                    .getRemainingTTL(clock.elapsedRealtime()) == 0) {
                serviceCache.removeService(serviceInstanceName, serviceType, socketKey);
                for (int i = 0; i < listeners.size(); i++) {
                    if (!responseMatchesOptions(existingResponse, listeners.valueAt(i))) {
                        continue;
                    }
                    final MdnsServiceBrowserListener listener = listeners.keyAt(i);
                    if (serviceInstanceName != null) {
                        final MdnsServiceInfo serviceInfo = buildMdnsServiceInfoFromResponse(
                                existingResponse, serviceTypeLabels);
                        if (existingResponse.isComplete()) {
                            sharedLog.log("TTL expired. onServiceRemoved: " + serviceInfo);
                            listener.onServiceRemoved(serviceInfo);
                        }
                        sharedLog.log("TTL expired. onServiceNameRemoved: " + serviceInfo);
                        listener.onServiceNameRemoved(serviceInfo);
                    }
                }
            }
        }
    }

    private static class ScheduledQueryTaskArgs {
        private final QueryTaskConfig config;
        private final long timeToRun;
        private final long minTtlExpirationTimeWhenScheduled;
        private final long sessionId;

        ScheduledQueryTaskArgs(@NonNull QueryTaskConfig config, long timeToRun,
                long minTtlExpirationTimeWhenScheduled, long sessionId) {
            this.config = config;
            this.timeToRun = timeToRun;
            this.minTtlExpirationTimeWhenScheduled = minTtlExpirationTimeWhenScheduled;
            this.sessionId = sessionId;
        }
    }

    private static class QuerySentArguments {
        private final int transactionId;
        private final List<String> subTypes = new ArrayList<>();
        private final ScheduledQueryTaskArgs taskArgs;

        QuerySentArguments(int transactionId, @NonNull List<String> subTypes,
                @NonNull ScheduledQueryTaskArgs taskArgs) {
            this.transactionId = transactionId;
            this.subTypes.addAll(subTypes);
            this.taskArgs = taskArgs;
        }
    }

    // A FutureTask that enqueues a single query, and schedule a new FutureTask for the next task.
    private class QueryTask implements Runnable {

        private final ScheduledQueryTaskArgs taskArgs;
        private final List<MdnsResponse> servicesToResolve = new ArrayList<>();
        private final boolean sendDiscoveryQueries;

        QueryTask(@NonNull ScheduledQueryTaskArgs taskArgs,
                @NonNull List<MdnsResponse> servicesToResolve, boolean sendDiscoveryQueries) {
            this.taskArgs = taskArgs;
            this.servicesToResolve.addAll(servicesToResolve);
            this.sendDiscoveryQueries = sendDiscoveryQueries;
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
                                taskArgs.config.subtypes,
                                taskArgs.config.expectUnicastResponse,
                                taskArgs.config.transactionId,
                                taskArgs.config.socketKey,
                                taskArgs.config.onlyUseIpv6OnIpv6OnlyNetworks,
                                sendDiscoveryQueries,
                                servicesToResolve,
                                clock)
                                .call();
            } catch (RuntimeException e) {
                sharedLog.e(String.format("Failed to run EnqueueMdnsQueryCallable for subtype: %s",
                        TextUtils.join(",", taskArgs.config.subtypes)), e);
                result = Pair.create(INVALID_TRANSACTION_ID, new ArrayList<>());
            }
            dependencies.sendMessage(
                    handler, handler.obtainMessage(EVENT_QUERY_RESULT,
                            new QuerySentArguments(result.first, result.second, taskArgs)));
        }
    }

    private static long calculateTimeToRun(@NonNull ScheduledQueryTaskArgs taskArgs,
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
                && taskArgs.minTtlExpirationTimeWhenScheduled == now + minRemainingTtl) {
            // Use the original scheduling time if the TTL has not changed, to avoid continuously
            // rescheduling to 80% of the remaining TTL as time passes
            return taskArgs.timeToRun;
        }
        return Math.max(now + (long) (0.8 * minRemainingTtl), lastSentTime + baseDelayInMs);
    }

    private long getMinRemainingTtl(long now) {
        long minRemainingTtl = Long.MAX_VALUE;
        for (MdnsResponse response : serviceCache.getCachedServices(serviceType, socketKey)) {
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

    @NonNull
    private void scheduleNextRun(@NonNull QueryTaskConfig nextRunConfig,
            long minRemainingTtl,
            long timeWhenScheduled, long timeToRun, long sessionId) {
        lastScheduledQueryTaskArgs = new ScheduledQueryTaskArgs(nextRunConfig, timeToRun,
                minRemainingTtl + timeWhenScheduled, sessionId);
        // The timeWhenScheduled could be greater than the timeToRun if the Runnable is delayed.
        long timeToNextTasksWithBackoffInMs = Math.max(timeToRun - timeWhenScheduled, 0);
        sharedLog.log(String.format("Next run: sessionId: %d, in %d ms",
                lastScheduledQueryTaskArgs.sessionId, timeToNextTasksWithBackoffInMs));
        dependencies.sendMessageDelayed(
                handler,
                handler.obtainMessage(EVENT_START_QUERYTASK, lastScheduledQueryTaskArgs),
                timeToNextTasksWithBackoffInMs);
    }
}