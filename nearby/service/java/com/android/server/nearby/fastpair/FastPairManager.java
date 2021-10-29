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

package com.android.server.nearby.fastpair;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;

/**
 * FastPairManager is the class initiated in nearby service to handle Fast Pair related
 * work.
 */
public class FastPairManager {
    LocatorContextWrapper mLocatorContextWrapper;
    IntentFilter mIntentFilter;
    Locator mLocator;
    private BroadcastReceiver mScreenBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                Log.d("FastPairService", " screen on");
                // STOPSHIP(b/202335820): Remove this logic in prod.
                Locator.getFromContextWrapper(mLocatorContextWrapper, FastPairCacheManager.class)
                        .printLog();
            } else {
                Log.d("FastPairService", " screen off");
            }
        }
    };


    public FastPairManager(LocatorContextWrapper contextWrapper) {
        mLocatorContextWrapper = contextWrapper;
        mIntentFilter = new IntentFilter();
        mLocator = mLocatorContextWrapper.getLocator();
        mLocator.bind(new FastPairModule());
    }

    /**
     * Function called when nearby service start.
     */
    public void initiate() {
        mIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
        mIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mLocatorContextWrapper.getContext()
                .registerReceiver(mScreenBroadcastReceiver, mIntentFilter);

        Locator.getFromContextWrapper(mLocatorContextWrapper, FastPairCacheManager.class);
    }

    /**
     * Function to free up fast pair resource.
     */
    public void cleanUp() {
        mLocatorContextWrapper.getContext().unregisterReceiver(mScreenBroadcastReceiver);
    }
}
