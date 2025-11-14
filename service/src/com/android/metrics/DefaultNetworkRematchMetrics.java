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

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED;
import static android.net.NetworkCapabilities.TRANSPORT_SATELLITE;
import static android.stats.connectivity.MeteredState.METERED_NO;
import static android.stats.connectivity.MeteredState.METERED_TEMPORARILY_UNMETERED;
import static android.stats.connectivity.MeteredState.METERED_YES;

import static com.android.net.module.util.FrameworkConnectivityStatsLog.DEFAULT_NETWORK_REMATCH;
import static com.android.server.ConnectivityService.PREFERENCE_ORDER_SATELLITE_FALLBACK;
import static com.android.server.ConnectivityStatsLog.DEFAULT_NETWORK_REMATCH__REMATCH_REASON__RMR_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.stats.connectivity.MeteredState;
import android.stats.connectivity.ValidatedState;

import androidx.annotation.VisibleForTesting;

import com.android.server.ConnectivityService;
import com.android.server.ConnectivityStatsLog;
import com.android.server.connectivity.NetworkAgentInfo;

import java.util.Random;
import java.util.Set;

/**
 * Collects and reports metrics regarding default network rematch events.
 *
 * This class does not provide thread safety and should be only accessed on the same thread.
 */
public class DefaultNetworkRematchMetrics {
    private final Dependencies mDeps;
    private final DefaultNetworkRematchInfoList.Builder mBuilder =
            DefaultNetworkRematchInfoList.newBuilder();
    private final long mSessionId = new Random().nextLong();

    public DefaultNetworkRematchMetrics() {
        this(new Dependencies());
    }

    @VisibleForTesting
    DefaultNetworkRematchMetrics(Dependencies deps) {
        mDeps = deps;
    }

    /** Dependency class */
    public static class Dependencies {
        /**
         * Writes a DEFAULT_NETWORK_REMATCH event to ConnectivityStatsLog.
         */
        public void writeStats(long sessionId, int rematchReason,
                @NonNull DefaultNetworkRematchInfoList list) {
            ConnectivityStatsLog.write(DEFAULT_NETWORK_REMATCH, sessionId, rematchReason,
                    list.toByteArray());
        }
    }

    /**
     * Adds a default network reassignment event to the list of events to be logged.
     *
     * @param nri The NetworkRequestInfo for the default network request.
     * @param oldNetwork The previous default network.
     * @param newNetwork The new default network.
     */
    public void addEvent(@NonNull ConnectivityService.NetworkRequestInfo nri,
            @Nullable NetworkAgentInfo oldNetwork,
            @Nullable NetworkAgentInfo newNetwork,
            long satisfiedDurationMs) {
        // TODO: Record event for network other than satellite after figuring out
        //  how to deal with the amount of data single device reports.
        // Only logs for satellite multilayer requests.
        if (nri.getPreferenceOrderForNetd() != PREFERENCE_ORDER_SATELLITE_FALLBACK) {
            return;
        }
        // Only logs when moving away from a satellite network to another network type.
        if (oldNetwork == null || !oldNetwork.getCapsNoCopy().hasTransport(TRANSPORT_SATELLITE)) {
            return;
        }
        mBuilder.addDefaultNetworkRematchInfo(
                getDefaultNetworkRematchInfo(nri, oldNetwork, newNetwork, satisfiedDurationMs));
    }

    /**
     * Writes the collected events to the stats log and clears the event list, with reason unknown.
     */
    public void writeStatsAndClear() {
        writeStatsAndClear(DEFAULT_NETWORK_REMATCH__REMATCH_REASON__RMR_UNKNOWN);
    }

    /**
     * Writes the collected events to the stats log and clears the event list.
     */
    public void writeStatsAndClear(int rematchReason) {
        if (mBuilder.getDefaultNetworkRematchInfoCount() == 0) return; // Skip.
        mDeps.writeStats(mSessionId, rematchReason, mBuilder.build());
        mBuilder.clear();
    }

    @NonNull
    private DefaultNetworkRematchInfo getDefaultNetworkRematchInfo(
            @NonNull ConnectivityService.NetworkRequestInfo nri,
            @Nullable NetworkAgentInfo oldNetwork, @Nullable NetworkAgentInfo newNetwork,
            long satisfiedDurationMs) {
        final DefaultNetworkRematchInfo.Builder infoBuilder =
                DefaultNetworkRematchInfo.newBuilder();
        if (oldNetwork != null) {
            infoBuilder.setOldNetwork(getNetworkDescription(oldNetwork));
            infoBuilder.setTimeDurationOnOldNetworkSec(
                    (int) (satisfiedDurationMs / 1000));
        }
        if (newNetwork != null) {
            infoBuilder.setNewNetwork(getNetworkDescription(newNetwork));
        }
        infoBuilder.setUidRanges(getUidRangesProto(nri.getUids()));
        return infoBuilder.build();
    }

    /**
     * Creates a NetworkDescription proto from a NetworkAgentInfo.
     *
     * @param nai The NetworkAgentInfo to describe.
     * @return A NetworkDescription proto.
     */
    @VisibleForTesting
    public static NetworkDescription getNetworkDescription(@NonNull final NetworkAgentInfo nai) {
        final NetworkCapabilities caps = nai.getCapsNoCopy();
        final NetworkDescription.Builder builder = NetworkDescription.newBuilder();
        builder.setTransportTypes((int) caps.getTransportTypesInternal());
        builder.setMeteredState(getMeteredState(caps));
        builder.setValidatedState(getValidatedState(caps));
        builder.setScorePolicies(nai.getScore().getPoliciesInternal());
        builder.setCapabilities(caps.getCapabilitiesInternal());
        builder.setEnterpriseId(caps.getEnterpriseIdsInternal());
        return builder.build();
    }

    /**
     * Determines the MeteredState from NetworkCapabilities.
     *
     * @param caps The NetworkCapabilities of the network.
     * @return The corresponding MeteredState.
     */
    @VisibleForTesting
    public static MeteredState getMeteredState(@NonNull NetworkCapabilities caps) {
        if (caps.hasCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)) {
            return METERED_TEMPORARILY_UNMETERED;
        }
        if (caps.hasCapability(NET_CAPABILITY_NOT_METERED)) {
            return METERED_NO;
        }
        return METERED_YES;
    }

    /**
     * Determines the ValidatedState from NetworkCapabilities.
     *
     * @param caps The NetworkCapabilities of the network.
     * @return The corresponding ValidatedState.
     */
    @VisibleForTesting
    public static ValidatedState getValidatedState(@NonNull NetworkCapabilities caps) {
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return ValidatedState.VS_PORTAL;
        }
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY)) {
            return ValidatedState.VS_PARTIAL;
        }
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return ValidatedState.VS_VALID;
        }
        return ValidatedState.VS_INVALID;
    }

    /**
     * Converts a set of UidRange objects to the UidRanges proto format.
     *
     * @param rangeSet The set of UidRange objects.
     * @return The UidRanges proto.
     */
    @VisibleForTesting
    public static UidRanges getUidRangesProto(@NonNull Set<android.net.UidRange> rangeSet) {
        final UidRanges.Builder listBuilder = UidRanges.newBuilder();
        for (android.net.UidRange range : rangeSet) {
            final UidRange.Builder rangeBuilder = UidRange.newBuilder();
            rangeBuilder.setBegin(range.start);
            rangeBuilder.setEnd(range.stop);
            listBuilder.addUidRange(rangeBuilder.build());
        }
        return listBuilder.build();
    }
}
