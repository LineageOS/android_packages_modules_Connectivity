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

import static com.android.server.ConnectivityStatsLog.NETWORK_REQUEST_STATE_CHANGED;

import android.annotation.NonNull;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ConnectivityStatsLog;

/**
 * A Connectivity Service helper class to push atoms capturing network requests have been received
 * and removed and its metadata.
 *
 * Atom events are logged in the ConnectivityStatsLog. Network request id: network request metadata
 * hashmap is stored to calculate network request duration when it is removed.
 *
 * Note that this class is not thread-safe. The instance of the class needs to be
 * synchronized in the callers when being used in multiple threads.
 */
public class NetworkRequestStateStatsMetrics {

    private static final String TAG = "NetworkRequestStateStatsMetrics";
    private static final int MSG_NETWORK_REQUEST_STATE_CHANGED = 0;

    // 1 second internal is suggested by experiment team
    private static final int ATOM_INTERVAL_MS = 1000;
    private final SparseArray<NetworkRequestStateInfo> mNetworkRequestsActive;

    private final Handler mStatsLoggingHandler;

    private final Dependencies mDependencies;

    private final NetworkRequestStateInfo.Dependencies mNRStateInfoDeps;

    public NetworkRequestStateStatsMetrics() {
        this(new Dependencies(), new NetworkRequestStateInfo.Dependencies());
    }

    @VisibleForTesting
    NetworkRequestStateStatsMetrics(Dependencies deps,
            NetworkRequestStateInfo.Dependencies nrStateInfoDeps) {
        mNetworkRequestsActive = new SparseArray<>();
        mDependencies = deps;
        mNRStateInfoDeps = nrStateInfoDeps;
        HandlerThread handlerThread = mDependencies.makeHandlerThread(TAG);
        handlerThread.start();
        mStatsLoggingHandler = new StatsLoggingHandler(handlerThread.getLooper());
    }

    /**
     * Register network request receive event, push RECEIVE atom
     *
     * @param networkRequest network request received
     */
    public void onNetworkRequestReceived(NetworkRequest networkRequest) {
        if (mNetworkRequestsActive.contains(networkRequest.requestId)) {
            Log.w(TAG, "Received already registered network request, id = "
                    + networkRequest.requestId);
        } else {
            Log.d(TAG, "Registered nr with ID = " + networkRequest.requestId
                    + ", package_uid = " + networkRequest.networkCapabilities.getRequestorUid());
            NetworkRequestStateInfo networkRequestStateInfo = new NetworkRequestStateInfo(
                    networkRequest, mNRStateInfoDeps);
            mNetworkRequestsActive.put(networkRequest.requestId, networkRequestStateInfo);
            mStatsLoggingHandler.sendMessage(
                    Message.obtain(
                            mStatsLoggingHandler,
                            MSG_NETWORK_REQUEST_STATE_CHANGED,
                            networkRequestStateInfo));
        }
    }

    /**
     * Register network request remove event, push REMOVE atom
     *
     * @param networkRequest network request removed
     */
    public void onNetworkRequestRemoved(NetworkRequest networkRequest) {
        NetworkRequestStateInfo networkRequestStateInfo = mNetworkRequestsActive.get(
                networkRequest.requestId);
        if (networkRequestStateInfo == null) {
            Log.w(TAG, "This NR hasn't been registered. NR id = " + networkRequest.requestId);
        } else {
            Log.d(TAG, "Removed nr with ID = " + networkRequest.requestId);

            mNetworkRequestsActive.remove(networkRequest.requestId);
            networkRequestStateInfo.setNetworkRequestRemoved();
            mStatsLoggingHandler.sendMessage(
                    Message.obtain(
                            mStatsLoggingHandler,
                            MSG_NETWORK_REQUEST_STATE_CHANGED,
                            networkRequestStateInfo));

        }
    }

    /** Dependency class */
    public static class Dependencies {
        /**
         * Creates a thread with provided tag.
         *
         * @param tag for the thread.
         */
        public HandlerThread makeHandlerThread(@NonNull final String tag) {
            return new HandlerThread(tag);
        }

        /**
         * Sleeps the thread for provided intervalMs millis.
         *
         * @param intervalMs number of millis for the thread sleep.
         */
        public void threadSleep(int intervalMs) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Log.w(TAG, "Cool down interrupted!", e);
            }
        }

        /**
         * Writes a NETWORK_REQUEST_STATE_CHANGED event to ConnectivityStatsLog.
         *
         * @param networkRequestStateInfo NetworkRequestStateInfo containing network request info.
         */
        public void writeStats(NetworkRequestStateInfo networkRequestStateInfo) {
            ConnectivityStatsLog.write(
                    NETWORK_REQUEST_STATE_CHANGED,
                    networkRequestStateInfo.getPackageUid(),
                    networkRequestStateInfo.getTransportTypes(),
                    networkRequestStateInfo.getNetCapabilityNotMetered(),
                    networkRequestStateInfo.getNetCapabilityInternet(),
                    networkRequestStateInfo.getNetworkRequestStateStatsType(),
                    networkRequestStateInfo.getNetworkRequestDurationMillis());
        }
    }

    private class StatsLoggingHandler extends Handler {
        private static final String TAG = "NetworkRequestsStateStatsLoggingHandler";
        private long mLastLogTime = 0;

        StatsLoggingHandler(Looper looper) {
            super(looper);
        }

        private void checkStatsLoggingTimeout() {
            // Cool down before next execution. Required by atom logging frequency.
            long now = SystemClock.elapsedRealtime();
            if (now - mLastLogTime < ATOM_INTERVAL_MS) {
                mDependencies.threadSleep(ATOM_INTERVAL_MS);
            }
            mLastLogTime = now;
        }

        @Override
        public void handleMessage(Message msg) {
            NetworkRequestStateInfo loggingInfo;
            switch (msg.what) {
                case MSG_NETWORK_REQUEST_STATE_CHANGED:
                    checkStatsLoggingTimeout();
                    loggingInfo = (NetworkRequestStateInfo) msg.obj;
                    mDependencies.writeStats(loggingInfo);
                    break;
                default: // fall out
            }
        }
    }
}
