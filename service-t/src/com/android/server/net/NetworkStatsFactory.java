/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.net;

import static android.net.NetworkStats.INTERFACES_ALL;
import static android.net.NetworkStats.TAG_ALL;
import static android.net.NetworkStats.UID_ALL;
import static android.provider.DeviceConfig.NAMESPACE_TETHERING;

import android.annotation.NonNull;
import android.content.Context;
import android.net.NetworkStats;
import android.net.UnderlyingNetworkInfo;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.server.BpfNetMaps;
import com.android.server.connectivity.InterfaceTracker;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates {@link NetworkStats} instances by parsing various {@code /proc/}
 * files as needed.
 *
 * @hide
 */
public class NetworkStatsFactory {
    static {
        System.loadLibrary("service-connectivity");
    }

    private static final String TAG = "NetworkStatsFactory";

    private final Context mContext;

    private final BpfNetMaps mBpfNetMaps;

    /**
     * Guards persistent data access in this class
     *
     * <p>In order to prevent deadlocks, critical sections protected by this lock SHALL NOT call out
     * to other code that will acquire other locks within the system server. See b/134244752.
     */
    private final Object mPersistentDataLock = new Object();

    /** Set containing info about active VPNs and their underlying networks. */
    private volatile UnderlyingNetworkInfo[] mUnderlyingNetworkInfos = new UnderlyingNetworkInfo[0];

    static final String CONFIG_PER_UID_TAG_THROTTLING = "per_uid_tag_throttling";
    static final String CONFIG_PER_UID_TAG_THROTTLING_THRESHOLD =
            "per_uid_tag_throttling_threshold";
    private static final int DEFAULT_TAGS_PER_UID_THRESHOLD = 1000;
    private static final int DUMP_TAGS_PER_UID_COUNT = 20;
    private final boolean mSupportPerUidTagThrottling;
    private final int mPerUidTagThrottlingThreshold;

    // Map for set of distinct tags per uid. Used for tag count limiting.
    @GuardedBy("mPersistentDataLock")
    private final SparseArray<SparseBooleanArray> mUidTagSets = new SparseArray<>();

    // A persistent snapshot of cumulative stats since device start
    @GuardedBy("mPersistentDataLock")
    private NetworkStats mPersistSnapshot;

    // The persistent snapshot of tun and 464xlat adjusted stats since device start
    @GuardedBy("mPersistentDataLock")
    private NetworkStats mTunAnd464xlatAdjustedStats;

    private final Dependencies mDeps;
    /**
     * Dependencies of NetworkStatsFactory, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Parse detailed statistics from bpf into given {@link NetworkStats} object. Values
         * are expected to monotonically increase since device boot.
         */
        @NonNull
        public NetworkStats getNetworkStatsDetail() throws IOException {
            final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 0);
            final int ret = nativeReadNetworkStatsDetail(stats);
            if (ret != 0) {
                throw new IOException("Failed to parse network stats");
            }
            return stats;
        }
        /**
         * Parse device summary statistics from bpf into given {@link NetworkStats} object. Values
         * are expected to monotonically increase since device boot.
         */
        @NonNull
        public NetworkStats getNetworkStatsDev() throws IOException {
            final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 6);
            final int ret = nativeReadNetworkStatsDev(stats);
            if (ret != 0) {
                throw new IOException("Failed to parse bpf iface stats");
            }
            return stats;
        }

        /** Create a new {@link BpfNetMaps}. */
        public BpfNetMaps createBpfNetMaps(@NonNull Context ctx) {
            return new BpfNetMaps(ctx, new InterfaceTracker(ctx));
        }

        /**
         * Check whether one specific feature is not disabled.
         * @param name Flag name of the experiment in the tethering namespace.
         * @see DeviceConfigUtils#isTetheringFeatureNotChickenedOut(Context, String)
         */
        public boolean isFeatureNotChickenedOut(@NonNull Context context, @NonNull String name) {
            return DeviceConfigUtils.isTetheringFeatureNotChickenedOut(context, name);
        }

        /**
         * Wrapper method for DeviceConfigUtils#getDeviceConfigPropertyInt for test injections.
         *
         * See {@link DeviceConfigUtils#getDeviceConfigPropertyInt(String, String, int)}
         * for more detailed information.
         */
        public int getDeviceConfigPropertyInt(@NonNull String name, int defaultValue) {
            return DeviceConfigUtils.getDeviceConfigPropertyInt(
                    NAMESPACE_TETHERING, name, defaultValue);
        }
    }

    /**
     * (Stacked interface) -> (base interface) association for all connected ifaces since boot.
     *
     * Because counters must never roll backwards, once a given interface is stacked on top of an
     * underlying interface, the stacked interface can never be stacked on top of
     * another interface. */
    private final ConcurrentHashMap<String, String> mStackedIfaces
            = new ConcurrentHashMap<>();

    /** Informs the factory of a new stacked interface. */
    public void noteStackedIface(String stackedIface, String baseIface) {
        if (stackedIface != null && baseIface != null) {
            mStackedIfaces.put(stackedIface, baseIface);
        }
    }

    /**
     * Set active VPN information for data usage migration purposes
     *
     * <p>Traffic on TUN-based VPNs inherently all appear to be originated from the VPN providing
     * app's UID. This method is used to support migration of VPN data usage, ensuring data is
     * accurately billed to the real owner of the traffic.
     *
     * @param vpnArray The snapshot of the currently-running VPNs.
     */
    public void updateUnderlyingNetworkInfos(UnderlyingNetworkInfo[] vpnArray) {
        mUnderlyingNetworkInfos = vpnArray.clone();
    }

    /**
     * Applies 464xlat adjustments with ifaces noted with {@link #noteStackedIface(String, String)}.
     * @see NetworkStats#apply464xlatAdjustments(NetworkStats, NetworkStats, Map)
     */
    public void apply464xlatAdjustments(NetworkStats baseTraffic, NetworkStats stackedTraffic) {
        NetworkStats.apply464xlatAdjustments(baseTraffic, stackedTraffic, mStackedIfaces);
    }

    public NetworkStatsFactory(@NonNull Context ctx) {
        this(ctx, new Dependencies());
    }

    @VisibleForTesting
    public NetworkStatsFactory(@NonNull Context ctx, Dependencies deps) {
        mBpfNetMaps = deps.createBpfNetMaps(ctx);
        synchronized (mPersistentDataLock) {
            mPersistSnapshot = new NetworkStats(SystemClock.elapsedRealtime(), -1);
            mTunAnd464xlatAdjustedStats = new NetworkStats(SystemClock.elapsedRealtime(), -1);
        }
        mContext = ctx;
        mDeps = deps;
        mSupportPerUidTagThrottling = mDeps.isFeatureNotChickenedOut(
            ctx, CONFIG_PER_UID_TAG_THROTTLING);
        mPerUidTagThrottlingThreshold = mDeps.getDeviceConfigPropertyInt(
                CONFIG_PER_UID_TAG_THROTTLING_THRESHOLD, DEFAULT_TAGS_PER_UID_THRESHOLD);
    }

    /**
     * Parse and return interface-level summary {@link NetworkStats}. Designed
     * to return only IP layer traffic. Values monotonically increase since
     * device boot, and may include details about inactive interfaces.
     */
    public NetworkStats readNetworkStatsSummaryXt() throws IOException {
        return mDeps.getNetworkStatsDev();
    }

    public NetworkStats readNetworkStatsDetail() throws IOException {
        return readNetworkStatsDetail(UID_ALL, INTERFACES_ALL, TAG_ALL);
    }

    @GuardedBy("mPersistentDataLock")
    private void requestSwapActiveStatsMapLocked() throws IOException {
        try {
            // Do a active map stats swap. Once the swap completes, this code
            // can read and clean the inactive map without races.
            mBpfNetMaps.swapActiveStatsMap();
        } catch (ServiceSpecificException e) {
            throw new IOException(e);
        }
    }

    /**
     * Reads the detailed UID stats based on the provided parameters
     *
     * @param limitUid the UID to limit this query to
     * @param limitIfaces the interfaces to limit this query to. Use {@link
     *     NetworkStats.INTERFACES_ALL} to select all interfaces
     * @param limitTag the tags to limit this query to
     * @return the NetworkStats instance containing network statistics at the present time.
     */
    public NetworkStats readNetworkStatsDetail(
            int limitUid, String[] limitIfaces, int limitTag) throws IOException {
        // In order to prevent deadlocks, anything protected by this lock MUST NOT call out to other
        // code that will acquire other locks within the system server. See b/134244752.
        synchronized (mPersistentDataLock) {
            // Take a reference. If this gets swapped out, we still have the old reference.
            final UnderlyingNetworkInfo[] vpnArray = mUnderlyingNetworkInfos;

            requestSwapActiveStatsMapLocked();
            // Stats are always read from the inactive map, so they must be read after the
            // swap
            final NetworkStats diff = mDeps.getNetworkStatsDetail();
            // Filter based on UID tag set before merging.
            final NetworkStats filteredDiff = mSupportPerUidTagThrottling
                    ? filterStatsByUidTagSets(diff) : diff;
            // BPF stats are incremental; fold into mPersistSnapshot.
            mPersistSnapshot.setElapsedRealtime(diff.getElapsedRealtime());
            mPersistSnapshot.combineAllValues(filteredDiff);

            // Update the stats adjusted for TunAnd464Xlat
            // Apply 464xlat adjustments before VPN adjustments. If VPNs are using v4 on a v6 only
            // network, the overhead is their fault.
            // No locking here: apply464xlatAdjustments behaves fine with an add-only
            // ConcurrentHashMap.
            filteredDiff.apply464xlatAdjustments(mStackedIfaces);

            // Migrate data usage over a VPN to the TUN network.
            for (UnderlyingNetworkInfo info : vpnArray) {
                filteredDiff.migrateTun(info.getOwnerUid(), info.getInterface(),
                        info.getUnderlyingInterfaces());
                // Filter out debug entries as that may lead to over counting.
                filteredDiff.filterDebugEntries();
            }

            // Update mTunAnd464xlatAdjustedStats with migrated stats.
            mTunAnd464xlatAdjustedStats.combineAllValues(filteredDiff);
            mTunAnd464xlatAdjustedStats.setElapsedRealtime(filteredDiff.getElapsedRealtime());

            // Filter return values
            return mTunAnd464xlatAdjustedStats.filteredClone(limitUid, limitIfaces, limitTag);
        }
    }

    @GuardedBy("mPersistentDataLock")
    private NetworkStats filterStatsByUidTagSets(NetworkStats stats) {
        final NetworkStats filteredStats =
                new NetworkStats(stats.getElapsedRealtime(), stats.size());

        final NetworkStats.Entry entry = new NetworkStats.Entry();
        final Set<Integer> tooManyTagsUidSet = new ArraySet<>();
        for (int i = 0; i < stats.size(); i++) {
            stats.getValues(i, entry);
            final int uid = entry.uid;
            final int tag = entry.tag;

            if (tag == NetworkStats.TAG_NONE) {
                filteredStats.combineValues(entry);
                continue;
            }

            SparseBooleanArray tagSet = mUidTagSets.get(uid);
            if (tagSet == null) {
                tagSet = new SparseBooleanArray();
            }
            if (tagSet.size() < mPerUidTagThrottlingThreshold || tagSet.get(tag)) {
                filteredStats.combineValues(entry);
                tagSet.put(tag, true);
                mUidTagSets.put(uid, tagSet);
            } else {
                tooManyTagsUidSet.add(uid);
            }
        }
        if (tooManyTagsUidSet.size() > 0) {
            Log.wtf(TAG, "Too many tags detected for uids: " + tooManyTagsUidSet);
        }
        return filteredStats;
    }

    /**
     * Remove stats from {@code mPersistSnapshot} and {@code mTunAnd464xlatAdjustedStats} for the
     * given uids.
     */
    public void removeUidsLocked(int[] uids) {
        synchronized (mPersistentDataLock) {
            mPersistSnapshot.removeUids(uids);
            mTunAnd464xlatAdjustedStats.removeUids(uids);
        }
    }

    public void assertEquals(NetworkStats expected, NetworkStats actual) {
        if (expected.size() != actual.size()) {
            throw new AssertionError(
                    "Expected size " + expected.size() + ", actual size " + actual.size());
        }

        NetworkStats.Entry expectedRow = null;
        NetworkStats.Entry actualRow = null;
        for (int i = 0; i < expected.size(); i++) {
            expectedRow = expected.getValues(i, expectedRow);
            actualRow = actual.getValues(i, actualRow);
            if (!expectedRow.equals(actualRow)) {
                throw new AssertionError(
                        "Expected row " + i + ": " + expectedRow + ", actual row " + actualRow);
            }
        }
    }

    /**
     * Convert {@code /proc/} tag format to {@link Integer}. Assumes incoming
     * format like {@code 0x7fffffff00000000}.
     */
    public static int kernelToTag(String string) {
        int length = string.length();
        if (length > 10) {
            return Long.decode(string.substring(0, length - 8)).intValue();
        } else {
            return 0;
        }
    }

    /**
     * Parse statistics from file into given {@link NetworkStats} object. Values
     * are expected to monotonically increase since device boot.
     */
    @VisibleForTesting
    public static native int nativeReadNetworkStatsDetail(NetworkStats stats);

    @VisibleForTesting
    public static native int nativeReadNetworkStatsDev(NetworkStats stats);

    private static ProtocolException protocolExceptionWithCause(String message, Throwable cause) {
        ProtocolException pe = new ProtocolException(message);
        pe.initCause(cause);
        return pe;
    }

    /**
     * Dump the contents of NetworkStatsFactory.
     */
    public void dump(IndentingPrintWriter pw) {
        dumpUidTagSets(pw);
    }

    private void dumpUidTagSets(IndentingPrintWriter pw) {
        pw.println("Top distinct tag counts in UidTagSets:");
        pw.increaseIndent();
        final List<Pair<Integer, Integer>> countForUidList = new ArrayList<>();
        synchronized (mPersistentDataLock) {
            for (int i = 0; i < mUidTagSets.size(); i++) {
                final Pair<Integer, Integer> countForUid =
                        new Pair<>(mUidTagSets.keyAt(i), mUidTagSets.valueAt(i).size());
                countForUidList.add(countForUid);
            }
        }
        Collections.sort(countForUidList,
                (entry1, entry2) -> Integer.compare(entry2.second, entry1.second));
        final int dumpSize = Math.min(countForUidList.size(), DUMP_TAGS_PER_UID_COUNT);
        for (int j = 0; j < dumpSize; j++) {
            final Pair<Integer, Integer> entry = countForUidList.get(j);
            pw.print(entry.first);
            pw.print("=");
            pw.println(entry.second);
        }
        pw.decreaseIndent();
    }
}
