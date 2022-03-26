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

import static com.android.server.SystemService.PHASE_BOOT_COMPLETED;
import static com.android.server.SystemService.PHASE_THIRD_PARTY_APPS_CAN_START;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.location.ContextHubManager;
import android.nearby.BroadcastRequestParcelable;
import android.nearby.IBroadcastListener;
import android.nearby.INearbyManager;
import android.nearby.IScanListener;
import android.nearby.NearbyManager;
import android.nearby.ScanRequest;
import android.util.Log;

import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.FastPairManager;
import com.android.server.nearby.injector.ContextHubManagerAdapter;
import com.android.server.nearby.injector.Injector;
import com.android.server.nearby.presence.PresenceManager;
import com.android.server.nearby.provider.BroadcastProviderManager;
import com.android.server.nearby.provider.DiscoveryProviderManager;
import com.android.server.nearby.provider.FastPairDataProvider;

/** Service implementing nearby functionality. */
public class NearbyService extends INearbyManager.Stub {
    public static final String TAG = "NearbyService";

    private final Context mContext;
    private final SystemInjector mSystemInjector;
    private final FastPairManager mFastPairManager;
    private final PresenceManager mPresenceManager;
    private final BroadcastReceiver mBluetoothReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int state =
                            intent.getIntExtra(
                                    BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_ON) {
                        if (mSystemInjector != null) {
                            // Have to do this logic in listener. Even during PHASE_BOOT_COMPLETED
                            // phase, BluetoothAdapter is not null, the BleScanner is null.
                            Log.v(TAG, "Initiating BluetoothAdapter when Bluetooth is turned on.");
                            mSystemInjector.initializeBluetoothAdapter();
                        }
                    }
                }
            };
    private DiscoveryProviderManager mProviderManager;
    private BroadcastProviderManager mBroadcastProviderManager;

    public NearbyService(Context context) {
        mContext = context;
        mSystemInjector = new SystemInjector(context);
        mProviderManager = new DiscoveryProviderManager(context, mSystemInjector);
        mBroadcastProviderManager = new BroadcastProviderManager(context, mSystemInjector);
        final LocatorContextWrapper lcw = new LocatorContextWrapper(context, null);
        mFastPairManager = new FastPairManager(lcw);
        mPresenceManager = new PresenceManager(lcw);
    }

    @Override
    @NearbyManager.ScanStatus
    public int registerScanListener(ScanRequest scanRequest, IScanListener listener) {
        if (mProviderManager.registerScanListener(scanRequest, listener)) {
            return NearbyManager.ScanStatus.SUCCESS;
        }
        return NearbyManager.ScanStatus.ERROR;
    }

    @Override
    public void unregisterScanListener(IScanListener listener) {
        mProviderManager.unregisterScanListener(listener);
    }

    @Override
    public void startBroadcast(BroadcastRequestParcelable broadcastRequestParcelable,
            IBroadcastListener listener) {
        mBroadcastProviderManager.startBroadcast(
                broadcastRequestParcelable.getBroadcastRequest(),
                listener);
    }

    @Override
    public void stopBroadcast(IBroadcastListener listener) {
        mBroadcastProviderManager.stopBroadcast(listener);
    }

    /**
     * Called by the service initializer.
     *
     * <p>{@see com.android.server.SystemService#onBootPhase}.
     */
    public void onBootPhase(int phase) {
        switch (phase) {
            case PHASE_THIRD_PARTY_APPS_CAN_START:
                // Ensures that a fast pair data provider exists which will work in direct boot.
                FastPairDataProvider.init(mContext);
                break;
            case PHASE_BOOT_COMPLETED:
                // The nearby service must be functioning after this boot phase.
                mSystemInjector.initializeBluetoothAdapter();
                mContext.registerReceiver(
                        mBluetoothReceiver,
                        new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
                mFastPairManager.initiate();
                // Initialize ContextManager for CHRE scan.
                mSystemInjector.initializeContextHubManagerAdapter();
                mPresenceManager.initiate();
                break;
        }
    }

    private static final class SystemInjector implements Injector {
        private final Context mContext;
        @Nullable private BluetoothAdapter mBluetoothAdapter;
        @Nullable private ContextHubManagerAdapter mContextHubManagerAdapter;

        SystemInjector(Context context) {
            mContext = context;
        }

        @Override
        @Nullable
        public BluetoothAdapter getBluetoothAdapter() {
            return mBluetoothAdapter;
        }

        @Override
        @Nullable
        public ContextHubManagerAdapter getContextHubManagerAdapter() {
            return mContextHubManagerAdapter;
        }

        synchronized void initializeBluetoothAdapter() {
            if (mBluetoothAdapter != null) {
                return;
            }
            BluetoothManager manager = mContext.getSystemService(BluetoothManager.class);
            if (manager == null) {
                return;
            }
            mBluetoothAdapter = manager.getAdapter();
        }

        synchronized void initializeContextHubManagerAdapter() {
            if (mContextHubManagerAdapter != null) {
                return;
            }
            ContextHubManager manager = mContext.getSystemService(ContextHubManager.class);
            if (manager == null) {
                return;
            }
            mContextHubManagerAdapter = new ContextHubManagerAdapter(manager);
        }
    }
}
