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

package com.android.server.nearby.fastpair.cache;

import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.util.Log;


import com.android.server.nearby.common.eventloop.Annotations;

import service.proto.Cache;
import service.proto.Rpcs;


/**
 * Save FastPair device info to database to avoid multiple requesting.
 */
public class FastPairCacheManager {

    private static final String FAST_PAIR_SERVER_CACHE = "FAST_PAIR_SERVER_CACHE";

    public FastPairCacheManager(Context context) {

    }

    /**
     * Saves the response to the db
     */
    private void saveDevice() {

    }

    Cache.ServerResponseDbItem getDeviceFromScanResult(ScanResult scanResult) {
        return Cache.ServerResponseDbItem.newBuilder().build();
    }

    /**
     * Checks if the entry can be auto deleted from the cache
     */
    public boolean isDeletable(Cache.ServerResponseDbItem entry) {
        if (!entry.getExpirable()) {
            return false;
        }
        return true;
    }

    @Annotations.EventThread
    private Rpcs.GetObservedDeviceResponse getObservedDeviceInfo(ScanResult scanResult) {
        return Rpcs.GetObservedDeviceResponse.getDefaultInstance();
    }

    /**
     * Test function to verify FastPairCacheManager setup.
     */
    public void printLog() {
        Log.d("FastPairCacheManager", "print log");
    }

}
