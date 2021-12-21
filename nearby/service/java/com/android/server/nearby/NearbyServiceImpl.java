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

package com.android.server.nearby;

import static com.android.server.nearby.NearbyService.TAG;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nearby.INearbyManager;
import android.nearby.IScanListener;
import android.nearby.ScanRequest;
import android.util.Log;

import com.android.server.nearby.injector.Injector;
import com.android.server.nearby.provider.DiscoveryProviderManager;

/**
 * Implementation of {@link com.android.server.nearby.NearbyService}.
 */
public class NearbyServiceImpl extends INearbyManager.Stub {

    private final Context mContext;
    private final SystemInjector mSystemInjector;
    private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent
                    .getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                if (mSystemInjector != null) {
                    // Have to do this logic in listener. Even during PHASE_BOOT_COMPLETED
                    // phase, BluetoothAdapter is not null, the BleScanner is null.
                    Log.v(TAG, "Initiating BluetoothAdapter when Bluetooth is turned on.");
                    mSystemInjector.onBluetoothReady();
                }
            }
        }
    };
    private DiscoveryProviderManager mProviderManager;

    public NearbyServiceImpl(Context context) {
        mContext = context;
        mSystemInjector = new SystemInjector(context);
        mProviderManager = new DiscoveryProviderManager(context, mSystemInjector);
    }

    @Override
    public void registerScanListener(ScanRequest scanRequest, IScanListener listener) {
        mProviderManager.registerScanListener(scanRequest, listener);
    }

    @Override
    public void unregisterScanListener(IScanListener listener) {
        mProviderManager.unregisterScanListener(listener);
    }

    void onSystemReady() {
        mContext.registerReceiver(mBluetoothReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    private static final class SystemInjector implements Injector {
        private final Context mContext;
        @Nullable
        private BluetoothAdapter mBluetoothAdapter;

        SystemInjector(Context context) {
            mContext = context;
        }

        @Override
        @Nullable
        public BluetoothAdapter getBluetoothAdapter() {
            return mBluetoothAdapter;
        }

        synchronized void onBluetoothReady() {
            if (mBluetoothAdapter != null) {
                return;
            }
            BluetoothManager manager = mContext.getSystemService(BluetoothManager.class);
            if (manager == null) {
                return;
            }
            mBluetoothAdapter = manager.getAdapter();
        }
    }
}
