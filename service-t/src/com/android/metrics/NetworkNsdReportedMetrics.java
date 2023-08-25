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

package com.android.metrics;

import static com.android.metrics.NetworkNsdReported.Builder;

import android.stats.connectivity.MdnsQueryResult;
import android.stats.connectivity.NsdEventType;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ConnectivityStatsLog;

/**
 * Class to record the NetworkNsdReported into statsd. Each client should create this class to
 * report its data.
 */
public class NetworkNsdReportedMetrics {
    // Whether this client is using legacy backend.
    private final boolean mIsLegacy;
    // The client id.
    private final int mClientId;
    private final Dependencies mDependencies;

    public NetworkNsdReportedMetrics(boolean isLegacy, int clientId) {
        this(isLegacy, clientId, new Dependencies());
    }

    @VisibleForTesting
    NetworkNsdReportedMetrics(boolean isLegacy, int clientId, Dependencies dependencies) {
        mIsLegacy = isLegacy;
        mClientId = clientId;
        mDependencies = dependencies;
    }

    /**
     * Dependencies of NetworkNsdReportedMetrics, for injection in tests.
     */
    public static class Dependencies {

        /**
         * @see ConnectivityStatsLog
         */
        public void statsWrite(NetworkNsdReported event) {
            ConnectivityStatsLog.write(ConnectivityStatsLog.NETWORK_NSD_REPORTED,
                    event.getIsLegacy(),
                    event.getClientId(),
                    event.getTransactionId(),
                    event.getIsKnownService(),
                    event.getType().getNumber(),
                    event.getEventDurationMillisec(),
                    event.getQueryResult().getNumber(),
                    event.getFoundServiceCount(),
                    event.getFoundCallbackCount(),
                    event.getLostCallbackCount(),
                    event.getRepliedRequestsCount(),
                    event.getSentQueryCount());
        }
    }

    private Builder makeReportedBuilder() {
        final Builder builder = NetworkNsdReported.newBuilder();
        builder.setIsLegacy(mIsLegacy);
        builder.setClientId(mClientId);
        return builder;
    }

    /**
     * Report service registration succeeded metric data.
     *
     * @param transactionId The transaction id of service registration.
     * @param durationMs The duration of service registration success.
     */
    public void reportServiceRegistrationSucceeded(int transactionId, long durationMs) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_REGISTER);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_REGISTERED);
        builder.setEventDurationMillisec(durationMs);
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service registration failed metric data.
     *
     * @param transactionId The transaction id of service registration.
     * @param durationMs The duration of service registration failed.
     */
    public void reportServiceRegistrationFailed(int transactionId, long durationMs) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_REGISTER);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_REGISTRATION_FAILED);
        builder.setEventDurationMillisec(durationMs);
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service unregistration success metric data.
     *
     * @param transactionId The transaction id of service registration.
     * @param durationMs The duration of service stayed registered.
     */
    public void reportServiceUnregistration(int transactionId, long durationMs) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_REGISTER);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_UNREGISTERED);
        builder.setEventDurationMillisec(durationMs);
        // TODO: Report repliedRequestsCount
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service discovery started metric data.
     *
     * @param transactionId The transaction id of service discovery.
     */
    public void reportServiceDiscoveryStarted(int transactionId) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_DISCOVER);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_DISCOVERY_STARTED);
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service discovery failed metric data.
     *
     * @param transactionId The transaction id of service discovery.
     * @param durationMs The duration of service discovery failed.
     */
    public void reportServiceDiscoveryFailed(int transactionId, long durationMs) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_DISCOVER);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_DISCOVERY_FAILED);
        builder.setEventDurationMillisec(durationMs);
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service discovery stop metric data.
     *
     * @param transactionId The transaction id of service discovery.
     * @param durationMs The duration of discovering services.
     * @param foundCallbackCount The count of found service callbacks before stop discovery.
     * @param lostCallbackCount The count of lost service callbacks before stop discovery.
     * @param servicesCount The count of found services.
     * @param sentQueryCount The count of sent queries before stop discovery.
     */
    public void reportServiceDiscoveryStop(int transactionId, long durationMs,
            int foundCallbackCount, int lostCallbackCount, int servicesCount, int sentQueryCount) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_DISCOVER);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_DISCOVERY_STOP);
        builder.setEventDurationMillisec(durationMs);
        builder.setFoundCallbackCount(foundCallbackCount);
        builder.setLostCallbackCount(lostCallbackCount);
        builder.setFoundServiceCount(servicesCount);
        builder.setSentQueryCount(sentQueryCount);
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service resolution success metric data.
     *
     * @param transactionId The transaction id of service resolution.
     * @param durationMs The duration of resolving services.
     * @param isServiceFromCache Whether the resolved service is from cache.
     * @param sentQueryCount The count of sent queries during resolving.
     */
    public void reportServiceResolved(int transactionId, long durationMs,
            boolean isServiceFromCache, int sentQueryCount) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_RESOLVE);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_RESOLVED);
        builder.setEventDurationMillisec(durationMs);
        builder.setIsKnownService(isServiceFromCache);
        builder.setSentQueryCount(sentQueryCount);
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service resolution failed metric data.
     *
     * @param transactionId The transaction id of service resolution.
     * @param durationMs The duration of service resolution failed.
     */
    public void reportServiceResolutionFailed(int transactionId, long durationMs) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_RESOLVE);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_RESOLUTION_FAILED);
        builder.setEventDurationMillisec(durationMs);
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service resolution stop metric data.
     *
     * @param transactionId The transaction id of service resolution.
     * @param durationMs The duration before stop resolving the service.
     */
    public void reportServiceResolutionStop(int transactionId, long durationMs) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_RESOLVE);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_RESOLUTION_STOP);
        builder.setEventDurationMillisec(durationMs);
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service info callback registered metric data.
     *
     * @param transactionId The transaction id of service info callback registration.
     */
    public void reportServiceInfoCallbackRegistered(int transactionId) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_SERVICE_INFO_CALLBACK);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_INFO_CALLBACK_REGISTERED);
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service info callback registration failed metric data.
     *
     * @param transactionId The transaction id of service callback registration.
     */
    public void reportServiceInfoCallbackRegistrationFailed(int transactionId) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_SERVICE_INFO_CALLBACK);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_INFO_CALLBACK_REGISTRATION_FAILED);
        mDependencies.statsWrite(builder.build());
    }

    /**
     * Report service callback unregistered metric data.
     *
     * @param transactionId The transaction id of service callback registration.
     * @param durationMs The duration of service callback stayed registered.
     * @param updateCallbackCount The count of service update callbacks during this registration.
     * @param lostCallbackCount The count of service lost callbacks during this registration.
     * @param isServiceFromCache Whether the resolved service is from cache.
     * @param sentQueryCount The count of sent queries during this registration.
     */
    public void reportServiceInfoCallbackUnregistered(int transactionId, long durationMs,
            int updateCallbackCount, int lostCallbackCount, boolean isServiceFromCache,
            int sentQueryCount) {
        final Builder builder = makeReportedBuilder();
        builder.setTransactionId(transactionId);
        builder.setType(NsdEventType.NET_SERVICE_INFO_CALLBACK);
        builder.setQueryResult(MdnsQueryResult.MQR_SERVICE_INFO_CALLBACK_UNREGISTERED);
        builder.setEventDurationMillisec(durationMs);
        builder.setFoundCallbackCount(updateCallbackCount);
        builder.setLostCallbackCount(lostCallbackCount);
        builder.setIsKnownService(isServiceFromCache);
        builder.setSentQueryCount(sentQueryCount);
        mDependencies.statsWrite(builder.build());
    }
}
