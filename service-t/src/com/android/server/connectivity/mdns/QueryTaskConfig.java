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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A configuration for the PeriodicalQueryTask that contains parameters to build a query packet.
 * Call to getConfigForNextRun returns a config that can be used to build the next query task.
 */
public class QueryTaskConfig {

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
    final boolean onlyUseIpv6OnIpv6OnlyNetworks;
    private final int numOfQueriesBeforeBackoff;
    @VisibleForTesting
    final int transactionId;
    @VisibleForTesting
    final boolean expectUnicastResponse;
    private final int queriesPerBurst;
    private final int timeBetweenBurstsInMs;
    private final int burstCounter;
    final long delayUntilNextTaskWithoutBackoffMs;
    private final boolean isFirstBurst;
    private final long queryCount;
    @NonNull
    final SocketKey socketKey;

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

    /**
     * Get new QueryTaskConfig for next run.
     */
    public QueryTaskConfig getConfigForNextRun() {
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

    /**
     * Determine if the query backoff should be used.
     */
    public boolean shouldUseQueryBackoff() {
        // Don't enable backoff mode during the burst or in the first burst
        if (burstCounter != 0 || isFirstBurst) {
            return false;
        }
        return queryCount > numOfQueriesBeforeBackoff;
    }
}
