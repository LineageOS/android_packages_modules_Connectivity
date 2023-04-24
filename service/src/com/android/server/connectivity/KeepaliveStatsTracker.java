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

package com.android.server.connectivity;

import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.metrics.DailykeepaliveInfoReported;
import com.android.metrics.DurationForNumOfKeepalive;
import com.android.metrics.DurationPerNumOfKeepalive;

import java.util.ArrayList;
import java.util.List;

// TODO(b/273451360): Also track KeepaliveLifetimeForCarrier and DailykeepaliveInfoReported
/**
 * Tracks carrier and duration metrics of automatic on/off keepalives.
 *
 * <p>This class follows AutomaticOnOffKeepaliveTracker closely and its on*Keepalive methods needs
 * to be called in a timely manner to keep the metrics accurate. It is also not thread-safe and all
 * public methods must be called by the same thread, namely the ConnectivityService handler thread.
 */
public class KeepaliveStatsTracker {
    private static final String TAG = KeepaliveStatsTracker.class.getSimpleName();

    private final Dependencies mDependencies;
    // List of duration stats metric where the index is the number of concurrent keepalives.
    // Each DurationForNumOfKeepalive message stores a registered duration and an active duration.
    // Registered duration is the total time spent with mNumRegisteredKeepalive == index.
    // Active duration is the total time spent with mNumActiveKeepalive == index.
    private final List<DurationForNumOfKeepalive.Builder> mDurationPerNumOfKeepalive =
            new ArrayList<>();

    private int mNumRegisteredKeepalive = 0;
    private int mNumActiveKeepalive = 0;

    // A timestamp of the most recent time the duration metrics was updated.
    private long mTimestampSinceLastUpdateDurations;

    /** Dependency class */
    @VisibleForTesting
    public static class Dependencies {
        // Returns a timestamp with the time base of SystemClock.uptimeMillis to keep durations
        // relative to start time and avoid timezone change.
        public long getUptimeMillis() {
            return SystemClock.uptimeMillis();
        }
    }

    public KeepaliveStatsTracker() {
        this(new Dependencies());
    }

    @VisibleForTesting
    public KeepaliveStatsTracker(Dependencies dependencies) {
        mDependencies = dependencies;
        mTimestampSinceLastUpdateDurations = mDependencies.getUptimeMillis();
    }

    /** Ensures the list of duration metrics is large enough for number of registered keepalives. */
    private void ensureDurationPerNumOfKeepaliveSize() {
        if (mNumActiveKeepalive < 0 || mNumRegisteredKeepalive < 0) {
            throw new IllegalStateException(
                    "Number of active or registered keepalives is negative");
        }
        if (mNumActiveKeepalive > mNumRegisteredKeepalive) {
            throw new IllegalStateException(
                    "Number of active keepalives greater than registered keepalives");
        }

        while (mDurationPerNumOfKeepalive.size() <= mNumRegisteredKeepalive) {
            final DurationForNumOfKeepalive.Builder durationForNumOfKeepalive =
                    DurationForNumOfKeepalive.newBuilder();
            durationForNumOfKeepalive.setNumOfKeepalive(mDurationPerNumOfKeepalive.size());
            durationForNumOfKeepalive.setKeepaliveRegisteredDurationsMsec(0);
            durationForNumOfKeepalive.setKeepaliveActiveDurationsMsec(0);

            mDurationPerNumOfKeepalive.add(durationForNumOfKeepalive);
        }
    }

    /**
     * Updates the durations metrics to the given time. This should always be called before making a
     * change to mNumRegisteredKeepalive or mNumActiveKeepalive to keep the duration metrics
     * correct.
     *
     * @param timeNow a timestamp obtained using Dependencies.getUptimeMillis
     */
    private void updateDurationsPerNumOfKeepalive(long timeNow) {
        if (mDurationPerNumOfKeepalive.size() < mNumRegisteredKeepalive) {
            Log.e(TAG, "Unexpected jump in number of registered keepalive");
        }
        ensureDurationPerNumOfKeepaliveSize();

        final int durationIncrease = (int) (timeNow - mTimestampSinceLastUpdateDurations);
        final DurationForNumOfKeepalive.Builder durationForNumOfRegisteredKeepalive =
                mDurationPerNumOfKeepalive.get(mNumRegisteredKeepalive);

        durationForNumOfRegisteredKeepalive.setKeepaliveRegisteredDurationsMsec(
                durationForNumOfRegisteredKeepalive.getKeepaliveRegisteredDurationsMsec()
                        + durationIncrease);

        final DurationForNumOfKeepalive.Builder durationForNumOfActiveKeepalive =
                mDurationPerNumOfKeepalive.get(mNumActiveKeepalive);

        durationForNumOfActiveKeepalive.setKeepaliveActiveDurationsMsec(
                durationForNumOfActiveKeepalive.getKeepaliveActiveDurationsMsec()
                        + durationIncrease);

        mTimestampSinceLastUpdateDurations = timeNow;
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just started and is active. */
    public void onStartKeepalive() {
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumRegisteredKeepalive++;
        mNumActiveKeepalive++;
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been paused. */
    public void onPauseKeepalive() {
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumActiveKeepalive--;
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been resumed. */
    public void onResumeKeepalive() {
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumActiveKeepalive++;
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been stopped. */
    public void onStopKeepalive(boolean wasActive) {
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumRegisteredKeepalive--;
        if (wasActive) mNumActiveKeepalive--;
    }

    /**
     * Builds and returns DailykeepaliveInfoReported proto.
     */
    public DailykeepaliveInfoReported buildKeepaliveMetrics() {
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        final DurationPerNumOfKeepalive.Builder durationPerNumOfKeepalive =
                DurationPerNumOfKeepalive.newBuilder();

        mDurationPerNumOfKeepalive.forEach(
                durationForNumOfKeepalive ->
                        durationPerNumOfKeepalive.addDurationForNumOfKeepalive(
                                durationForNumOfKeepalive));

        final DailykeepaliveInfoReported.Builder dailyKeepaliveInfoReported =
                DailykeepaliveInfoReported.newBuilder();

        // TODO(b/273451360): fill all the other values and write to ConnectivityStatsLog.
        dailyKeepaliveInfoReported.setDurationPerNumOfKeepalive(durationPerNumOfKeepalive);

        return dailyKeepaliveInfoReported.build();
    }

    /** Resets the stored metrics but maintains the state of keepalives */
    public void resetMetrics() {
        mDurationPerNumOfKeepalive.clear();
        ensureDurationPerNumOfKeepaliveSize();
    }
}
