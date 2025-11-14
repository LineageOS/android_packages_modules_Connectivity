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

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.TRANSPORT_SATELLITE;

import static com.android.net.module.util.FrameworkConnectivityStatsLog.CORE_NETWORKING_CRITICAL_BYTES_EVENT_OCCURRED;
import static com.android.net.module.util.FrameworkConnectivityStatsLog.CORE_NETWORKING_CRITICAL_BYTES_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_BYTES_EVENT_TYPE_SATELLITE_COARSE_RX_USAGE;
import static com.android.net.module.util.FrameworkConnectivityStatsLog.CORE_NETWORKING_CRITICAL_BYTES_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_BYTES_EVENT_TYPE_SATELLITE_COARSE_TX_USAGE;

import static java.util.concurrent.TimeUnit.HOURS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkTemplate;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.FrameworkConnectivityStatsLog;

import javax.annotation.CheckReturnValue;

/**
 * Monitors satellite network connections to collect overall usage during active
 * satellite sessions. It uses NetworkStatsManager to query snapshots upon
 * connection and disconnection, then logs the difference.
 *
 * This class does not provide thread safety and should be only accessed on the same thread.
 */
public class SatelliteCoarseUsageMetricsCollector {
    private static final String TAG = SatelliteCoarseUsageMetricsCollector.class.getSimpleName();
    private static final NetworkTemplate SATELLITE_TEMPLATE;

    static {
        // To prevent NetworkTemplate#Builder not found error when
        // initializing CSTest on S-based image.
        if (SdkLevel.isAtLeastU()) {
            SATELLITE_TEMPLATE = new NetworkTemplate.Builder()
                    .setTransportType(TRANSPORT_SATELLITE).build();
        } else {
            SATELLITE_TEMPLATE = null;
        }
    }
    /**
     * The max bucket duration used when storing usage in NetworkStatsService.
     * See NetworkStatsService#DefaultNetworkStatsSettings#getUidConfig.
     */
    private static final long MAX_NETSTATS_BUCKET_DURATION_MS = HOURS.toMillis(2);

    private ConnectivityManager mCm;
    private NetworkStatsManager mNsm;
    private final Context mContext;
    private final Handler mHandler;
    private final SatelliteNetworkCallback mNetworkCallback = new SatelliteNetworkCallback();

    // Null when no satellite network is connected.
    @Nullable
    private MyStatsEntry mSatelliteBaseline = null;

    private final MyStatsEntry mReportedUsage = new MyStatsEntry();
    private final Dependencies mDeps;

    // The start timestamp used to query the snapshots of data usage.
    // This must only be accessed on the handler thread.
    long mStartTime;

    /**
     * Constructs a helper to monitor satellite network usage.
     *
     * @param context The context.
     */
    public SatelliteCoarseUsageMetricsCollector(@NonNull Context context) {
        this(context, new Dependencies());
    }

    /**
     * Constructs a helper to monitor satellite network usage.
     *
     * @param context The context.
     */
    public SatelliteCoarseUsageMetricsCollector(@NonNull Context context,
            @NonNull Dependencies deps) {
        mContext = context;
        mDeps = deps;
        mHandler = mDeps.getBackgroundThreadHandler();
    }

    /**
     * Helper class for test injection.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Reports the given network usage entry to system metrics.
         */
        public void reportUsage(MyStatsEntry entry) {
            FrameworkConnectivityStatsLog.write_non_chained(CORE_NETWORKING_CRITICAL_BYTES_EVENT_OCCURRED,
                    Process.SYSTEM_UID,
                    TAG,
                    CORE_NETWORKING_CRITICAL_BYTES_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_BYTES_EVENT_TYPE_SATELLITE_COARSE_RX_USAGE,
                    entry.rxBytes);
            FrameworkConnectivityStatsLog.write_non_chained(CORE_NETWORKING_CRITICAL_BYTES_EVENT_OCCURRED,
                    Process.SYSTEM_UID,
                    TAG,
                    CORE_NETWORKING_CRITICAL_BYTES_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_BYTES_EVENT_TYPE_SATELLITE_COARSE_TX_USAGE,
                    entry.txBytes);
        }

        /**
         * Queries the network stats summary for a given network template.
         */
        @NonNull
        public MyStatsEntry getSummary(@NonNull NetworkStatsManager nsm, long startTime) {
            final MyStatsEntry ret = new MyStatsEntry();
            final NetworkStats stats = nsm.querySummary(SATELLITE_TEMPLATE,
                    startTime, Long.MAX_VALUE);
            // This null check simplifies testing by avoiding the need to mock
            // the complex NetworkStats object. In production, this object
            // is not expected to be null. See ConnectivityServiceIntegrationTest
            // for context.
            if (stats == null) {
                return ret;
            }
            final NetworkStats.Bucket recycle = new NetworkStats.Bucket();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(recycle);
                ret.add(new MyStatsEntry(recycle));
            }
            return ret;
        }

        /** Get the background thread handler */
        @NonNull
        public Handler getBackgroundThreadHandler() {
            return BackgroundThread.getHandler();
        }

        /** Get current time */
        public long getCurrentTimeMillis() {
            return System.currentTimeMillis();
        }

        /** Get max netstats bucket duration */
        public long getMaxBucketDuration() {
            return MAX_NETSTATS_BUCKET_DURATION_MS;
        }
    }

    @VisibleForTesting
    public static class MyStatsEntry {
        public long rxBytes;
        public long txBytes;

        public MyStatsEntry() {
            this.rxBytes = 0;
            this.txBytes = 0;
        }

        public MyStatsEntry(@NonNull NetworkStats.Bucket bucket) {
            this.rxBytes = bucket.getRxBytes();
            this.txBytes = bucket.getTxBytes();
        }

        public MyStatsEntry(long rxBytes, long txBytes) {
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }

        /**
         * Adds the stats from another {@link MyStatsEntry} to this entry.
         *
         * This method modifies the current object.
         */
        public void add(@NonNull MyStatsEntry other) {
            this.rxBytes += other.rxBytes;
            this.txBytes += other.txBytes;
        }

        /**
         * Subtracts the stats from another {@link MyStatsEntry} to this entry.
         *
         * This method modifies the current object.
         */
        public void subtract(@NonNull MyStatsEntry other) {
            this.rxBytes -= other.rxBytes;
            this.txBytes -= other.txBytes;
        }

        /**
         * Adds the stats from another {@link MyStatsEntry} from this entry.
         *
         * This method does not modify the current object but returns a new one.
         */
        @CheckReturnValue
        @NonNull
        public MyStatsEntry plus(@NonNull MyStatsEntry other) {
            return new MyStatsEntry(rxBytes + other.rxBytes, txBytes + other.txBytes);
        }

        /**
         * Subtracts the stats from another {@link MyStatsEntry} from this entry.
         *
         * This method does not modify the current object but returns a new one.
         */
        @CheckReturnValue
        @NonNull
        public MyStatsEntry minus(@NonNull MyStatsEntry other) {
            return new MyStatsEntry(rxBytes - other.rxBytes, txBytes - other.txBytes);
        }

        @Override
        public String toString() {
            return "{rxBytes=" + rxBytes + ", txBytes=" + txBytes + "}";
        }
    }

    private class SatelliteNetworkCallback extends ConnectivityManager.NetworkCallback {
        /**
         * Called when a satellite network is lost. Collects and reports the satellite coarse usage
         * difference.
         *
         * Note that multiple satellite networks could occur, for example, when
         * transitioning from one subscription to another. The amount of usage might
         * be slightly inaccurate in such cases.
         *
         * @param network The Network object that was just lost.
         */
        @Override
        public void onLost(Network network) {
            // The start time must be matched with the baseline, in order to calculate
            // the difference.
            final MyStatsEntry snapshot = mDeps.getSummary(mNsm, mStartTime);
            // Report diff with metrics.
            final MyStatsEntry diff = snapshot.minus(mSatelliteBaseline);
            mDeps.reportUsage(diff);
            mReportedUsage.add(diff);

            // Fetch another snapshot as a baseline, with a more recent start timestamp.
            // This is to prevent from fetching too much data.
            updateStartTimestamp();
            mSatelliteBaseline = mDeps.getSummary(mNsm, mStartTime);
            Log.d(TAG, "onLost: Last satellite network " + network + ", Reported usage: " + diff);
        }
    }

    // Calculate the start timestamp used for querying data usage snapshots.
    // This timestamp needs to be early enough to ensure the query window
    // starts at or before the beginning of the usage bucket in which the
    // first event occurred. This is achieved by setting the start time
    // to one bucket duration prior to the calculated bootTimeMillis.
    //
    // It is acceptable if this timestamp is not perfectly aligned with exact
    // bucket boundaries or results in covering some redundant data from before
    // the event. Any such duplicated or irrelevant data will be effectively
    // subtracted out when calculating usage from the differences of two snapshots,
    // provided both snapshots are queried using this same consistent start timestamp.
    // This must only be called on the handler thread.
    private void updateStartTimestamp() {
        mStartTime = mDeps.getCurrentTimeMillis() - mDeps.getMaxBucketDuration();
    }

    /**
     * Starts monitoring for satellite network connections.
     */
    public void startMonitoring() {
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException(TAG + " is not supported below U");
        }
        mHandler.post(() -> handleStartMonitoring());
    }

    private void handleStartMonitoring() {
        // Note that in the constructor it is too early to get the managers.
        mCm = mContext.getSystemService(ConnectivityManager.class);
        mNsm = mContext.getSystemService(NetworkStatsManager.class);
        // Note: This might create heavy workload on NetworkStatsService if queried with high
        // frequency, as the calling UID is system. Each query made with this manager
        // updates usage stored in files before returning the newest result.
        // This behavior is not rate-limited because the calling uid is the system UID.
        mNsm.setPollOnOpen(true);
        // Get the first baseline.
        updateStartTimestamp();
        mSatelliteBaseline = mDeps.getSummary(mNsm, mStartTime);

        final NetworkRequest satelliteRequest = new NetworkRequest.Builder()
                .clearCapabilities()
                .addCapability(NET_CAPABILITY_INTERNET)
                .addTransportType(TRANSPORT_SATELLITE)
                .build();
        // The callback uses the shared background thread. Potential delays of
        // dozens of seconds are acceptable because this class measures coarse
        // usage after a connection is lost, so the metric's accuracy is not
        // that sensitive to the timing.
        // A dedicated thread would be needed if it turns out inaccurate.
        mCm.registerNetworkCallback(satelliteRequest, mNetworkCallback, mHandler);
    }

    /** Dump info to dumpsys */
    public void dump(@NonNull IndentingPrintWriter pw) {
        pw.println(TAG + ":");
        pw.increaseIndent();
        pw.println("Reported usage " + mReportedUsage);
        pw.decreaseIndent();
        pw.println();
    }
}
