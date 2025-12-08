/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;

import static com.android.net.module.util.FrameworkConnectivityStatsLog.SATISFIED_BY_LOCAL_NETWORK_REQUESTS;
import static com.android.net.module.util.FrameworkConnectivityStatsLog.SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_BACKGROUND_REQUEST;
import static com.android.net.module.util.FrameworkConnectivityStatsLog.SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_LISTEN;
import static com.android.net.module.util.FrameworkConnectivityStatsLog.SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_LISTEN_FOR_BEST;
import static com.android.net.module.util.FrameworkConnectivityStatsLog.SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_REQUEST;
import static com.android.net.module.util.FrameworkConnectivityStatsLog.SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_RESERVATION;
import static com.android.net.module.util.FrameworkConnectivityStatsLog.SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_TRACK_DEFAULT;
import static com.android.net.module.util.FrameworkConnectivityStatsLog.SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_TRACK_SYSTEM_DEFAULT;
import static com.android.net.module.util.FrameworkConnectivityStatsLog.SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_UNSPECIFIED;

import android.annotation.NonNull;
import android.app.StatsManager;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.connectivity.ConnectivityInternalApiUtil;
import android.os.Handler;
import android.util.IndentingPrintWriter;
import android.util.SparseIntArray;
import android.util.StatsEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.FrameworkConnectivityStatsLog;
import com.android.net.module.util.HandlerUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks NetworkRequest metrics related to local network capabilities.
 *
 * This class is thread-safe because all access to its mutable internal state
 * is serialized through a single {@link Handler} thread.
 */
public class SatisfiedByLocalNetworkMetrics implements StatsManager.StatsPullAtomCallback {
    private final Context mContext;
    private final Handler mHandler;
    private final Dependencies mDeps;

    private static int requestTypeToEnum(NetworkRequest.Type reqType) {
        return switch (reqType) {
            case LISTEN -> SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_LISTEN;
            case TRACK_DEFAULT ->
                    SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_TRACK_DEFAULT;
            case REQUEST -> SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_REQUEST;
            case BACKGROUND_REQUEST ->
                    SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_BACKGROUND_REQUEST;
            case TRACK_SYSTEM_DEFAULT ->
                    SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_TRACK_SYSTEM_DEFAULT;
            case LISTEN_FOR_BEST ->
                    SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_LISTEN_FOR_BEST;
            case RESERVATION ->
                    SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_RESERVATION;
            default -> SATISFIED_BY_LOCAL_NETWORK_REQUESTS__TYPE__NETWORK_REQUEST_TYPE_UNSPECIFIED;
        };
    }

    public static class Dependencies {
        @NonNull
        StatsEvent buildStatsEvent(int uid, NetworkRequest.Type requestType, int count) {
            return FrameworkConnectivityStatsLog.buildStatsEvent(
                    SATISFIED_BY_LOCAL_NETWORK_REQUESTS,
                    uid,
                    requestTypeToEnum(requestType),
                    count
            );
        }
    }

    /**
     * Represents the capabilities and transports that could be provided by a
     * non-thread local network agent.
     * This is used to test against existing application NetworkRequests.
     *
     * Refer to {@link ConnectivityInternalApiUtil#buildTetheringNetworkAgent} and
     * {@code TetheringUtils#getTransportTypeForTetherableType}.
     */
    private static final NetworkCapabilities NON_THREAD_LOCAL_NETWORK_CAPABILITIES =
            new NetworkCapabilities.Builder()
                    .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                    .addCapability(NET_CAPABILITY_NOT_METERED)
                    .addCapability(NET_CAPABILITY_NOT_ROAMING)
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                    .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_USB)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build();

    // This map stores counts per unique NetworkRequest.Type and then per UID.
    // Key: NetworkRequest.Type (representing the 'request type' as defined by the system)
    // Value: SparseIntArray mapping UID to the count of satisfied requests for that specific type.
    // This should only be accessed from the same thread.
    private final Map<NetworkRequest.Type, SparseIntArray> mSatisfiedRequestsPerTypeAndUid =
            new HashMap<>();

    /**
     * Constructs a new SatisfiedByLocalNetworkMetrics instance.
     *
     * @param context The application context, used to get system services like StatsManager.
     * @param handler The Handler on which the onPullAtom callback will be invoked.
     */
    public SatisfiedByLocalNetworkMetrics(Context context, Handler handler) {
        this(context, handler, new Dependencies());
    }

    @VisibleForTesting
    public SatisfiedByLocalNetworkMetrics(Context context, Handler handler, Dependencies deps) {
        mContext = context;
        mHandler = handler;
        mDeps = deps;
    }

    /**
     * Registers this class as a callback for the relevant pull atom.
     */
    public void start() {
        final StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        statsManager.setPullAtomCallback(
                SATISFIED_BY_LOCAL_NETWORK_REQUESTS,
                null, // Use default pull frequency
                (it) -> mHandler.post(it),
                this // This class implements StatsPullAtomCallback
        );
    }

    /**
     * Checks if a given NetworkRequest would be satisfied by the static local network
     * capabilities and increments counters if it matches.
     *
     * @param request The NetworkRequest from the application's callback.
     * @param uid     The UID of the application that registered the callback.
     */
    public void logRequest(NetworkRequest request, int uid) {
        // Get a cloned caps with altered MatchNonThreadLocalNetworks to evaluate
        // whether the request will be affected by Non-Thread local networks.
        final NetworkCapabilities clonedCaps = new NetworkCapabilities(request.networkCapabilities);
        clonedCaps.setMatchNonThreadLocalNetworks(true);
        mHandler.post(() -> handleLogRequest(request.type, clonedCaps, uid));
    }

    private void handleLogRequest(NetworkRequest.Type reqType, NetworkCapabilities caps, int uid) {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);
        // Check if the request matches the defined local network capabilities
        if (caps.satisfiedByNetworkCapabilities(NON_THREAD_LOCAL_NETWORK_CAPABILITIES)) {
            // Get or create the SparseIntArray for this specific NetworkRequest.Type.
            final SparseIntArray uidCounts = mSatisfiedRequestsPerTypeAndUid.computeIfAbsent(
                    reqType, k -> new SparseIntArray());

            // Increment the count for this UID under this specific request type
            final int currentCount = uidCounts.get(uid, 0);
            final int newCount = currentCount + 1;
            uidCounts.put(uid, newCount);
        }
    }

    /**
     * Callback method invoked by StatsManager when a pull atom is requested.
     */
    @Override
    public int onPullAtom(final int atomTag, final List<StatsEvent> data) {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);
        if (atomTag != SATISFIED_BY_LOCAL_NETWORK_REQUESTS) {
            return StatsManager.PULL_SKIP;
        }

        // Iterate through all stored NetworkRequest.Type (request types)
        for (Map.Entry<NetworkRequest.Type, SparseIntArray> entry :
                mSatisfiedRequestsPerTypeAndUid.entrySet()) {
            final NetworkRequest.Type requestType = entry.getKey();
            // Counts for UIDs that made this specific request type.
            final SparseIntArray uidCounts = entry.getValue();

            for (int i = 0; i < uidCounts.size(); i++) {
                final int uid = uidCounts.keyAt(i);
                final int count = uidCounts.valueAt(i);
                data.add(mDeps.buildStatsEvent(uid, requestType, count));
            }
        }
        // Clear counts whenever pulled, this makes server-side to
        // process data easier.
        mSatisfiedRequestsPerTypeAndUid.clear();
        return StatsManager.PULL_SUCCESS;
    }

    /**
     * Dumps the current metrics for debugging purposes.
     *
     * @param pw The IndentingPrintWriter to write the output to.
     */
    public void dump(IndentingPrintWriter pw) {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);
        pw.println("SatisfiedByLocalNetworkMetrics: " + mSatisfiedRequestsPerTypeAndUid);
    }
}
