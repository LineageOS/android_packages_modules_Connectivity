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

/**
 * FastPairManager is the class initiated in nearby service to handle Fast Pair related
 * work.
 */
public class FastPairManager {
    Context mContext;
    IntentFilter mIntentFilter;
    private BroadcastReceiver mScreenBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                Log.d("FastPairService", " screen on");
            } else {
                Log.d("FastPairService", " screen off");
            }
        }
    };

    public FastPairManager(Context context) {
        mContext = context;
        mIntentFilter = new IntentFilter();
    }

    /**
     * Function called when nearby service start.
     */
    public void initiate() {
        mIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
        mIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenBroadcastReceiver, mIntentFilter);
    }

    /**
     * Function to free up fast pair resource.
     */
    public void cleanUp() {
        mContext.unregisterReceiver(mScreenBroadcastReceiver);
    }
}
