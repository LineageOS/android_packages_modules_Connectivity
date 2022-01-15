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

import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nearby.FastPairDevice;
import android.nearby.NearbyDevice;
import android.nearby.NearbyManager;
import android.nearby.ScanCallback;
import android.nearby.ScanRequest;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.server.nearby.common.bluetooth.BluetoothException;
import com.android.server.nearby.common.bluetooth.fastpair.FastPairConnection;
import com.android.server.nearby.common.bluetooth.fastpair.FastPairDualConnection;
import com.android.server.nearby.common.bluetooth.fastpair.PairingException;
import com.android.server.nearby.common.bluetooth.fastpair.Preferences;
import com.android.server.nearby.common.bluetooth.fastpair.ReflectionException;
import com.android.server.nearby.common.bluetooth.fastpair.SimpleBroadcastReceiver;
import com.android.server.nearby.common.eventloop.Annotations;
import com.android.server.nearby.common.eventloop.EventLoop;
import com.android.server.nearby.common.eventloop.NamedRunnable;
import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;
import com.android.server.nearby.fastpair.footprint.FootprintsDeviceManager;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager;
import com.android.server.nearby.fastpair.pairinghandler.PairingProgressHandlerBase;
import com.android.server.nearby.util.FastPairDecoder;
import com.android.server.nearby.util.ForegroundThread;
import com.android.server.nearby.util.Hex;

import com.google.protobuf.ByteString;

import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import service.proto.Cache;
import service.proto.Rpcs;

/**
 * FastPairManager is the class initiated in nearby service to handle Fast Pair related
 * work.
 */

public class FastPairManager {
    private static final String ACTION_PREFIX = UserActionHandler.PREFIX;
    private static final int WAIT_FOR_UNLOCK_MILLIS = 5000;
    /** A notification ID which should be dismissed */
    public static final String EXTRA_NOTIFICATION_ID = ACTION_PREFIX + "EXTRA_NOTIFICATION_ID";
    public static final String ACTION_RESOURCES_APK = "android.nearby.SHOW_HALFSHEET";
    // Temp action deleted when the scanner is ready
    public static final String ACTION_START_PAIRING = "NEARBY_START_PAIRING";
    public static final String EXTRA_MODEL_ID = "MODELID";
    public static final String EXTRA_ADDRESS = "ADDRESS";

    private static Executor sFastPairExecutor;

    final LocatorContextWrapper mLocatorContextWrapper;
    final IntentFilter mIntentFilter;
    final Locator mLocator;
    private final BroadcastReceiver mScreenBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                Log.d("FastPairService", " screen on");
                NearbyManager nearbyManager = (NearbyManager) mLocatorContextWrapper
                        .getApplicationContext().getSystemService(Context.NEARBY_SERVICE);

                Log.d("FastPairService", " the nearby manager is " + nearbyManager);

                if (nearbyManager != null) {
                    nearbyManager.startScan(
                            new ScanRequest.Builder()
                                    .setScanType(ScanRequest.SCAN_TYPE_FAST_PAIR).build(),
                            ForegroundThread.getExecutor(),
                            mScanCallback);
                } else {
                    Log.d("FastPairService", " the nearby manager is null");
                }

            } else if (intent.getAction().equals(ACTION_START_PAIRING)) {
                byte[] model = intent.getByteArrayExtra(EXTRA_MODEL_ID);
                String address = intent.getStringExtra(EXTRA_ADDRESS);
                Log.d("FastPairService", "start pair " + address);
                Locator.get(mLocatorContextWrapper, FastPairHalfSheetManager.class).showHalfSheet(
                        Cache.ScanFastPairStoreItem.newBuilder().setAddress(address)
                                .setAntiSpoofingPublicKey(ByteString.EMPTY)
                                .build());
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
        Rpcs.GetObservedDeviceResponse getObservedDeviceResponse =
                Rpcs.GetObservedDeviceResponse.newBuilder().build();
    }

    final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onDiscovered(@NonNull NearbyDevice device) {
            Locator.get(mLocatorContextWrapper, FastPairAdvHandler.class).handleBroadcast(device);
        }

        @Override
        public void onUpdated(@NonNull NearbyDevice device) {
            FastPairDevice fastPairDevice = (FastPairDevice) device;
            byte[] modelArray = FastPairDecoder.getModelId(fastPairDevice.getData());
            Log.d("FastPairService",
                    "update model id" + Hex.bytesToStringLowercase(modelArray));
        }

        @Override
        public void onLost(@NonNull NearbyDevice device) {
            FastPairDevice fastPairDevice = (FastPairDevice) device;
            byte[] modelArray = FastPairDecoder.getModelId(fastPairDevice.getData());
            Log.d("FastPairService",
                    "lost model id" + Hex.bytesToStringLowercase(modelArray));
        }
    };

    /**
     * Function called when nearby service start.
     */
    public void initiate() {
        mIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
        mIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mIntentFilter.addAction(ACTION_START_PAIRING);

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

    /**
     * Starts fast pair process.
     */
    @Annotations.EventThread
    public static Future<Void> pair(
            ExecutorService executor,
            Context context,
            DiscoveryItem item,
            @Nullable byte[] accountKey,
            @Nullable String companionApp,
            FootprintsDeviceManager footprints,
            PairingProgressHandlerBase pairingProgressHandlerBase) {

        return executor.submit(
                () -> pairInternal(context, item, companionApp, accountKey, footprints,
                        pairingProgressHandlerBase), /* result= */ null);
    }

    /**
     * Starts fast pair
     */
    @WorkerThread
    public static void pairInternal(
            Context context,
            DiscoveryItem item,
            @Nullable String companionApp,
            @Nullable byte[] accountKey,
            FootprintsDeviceManager footprints,
            PairingProgressHandlerBase pairingProgressHandlerBase) {

        try {
            pairingProgressHandlerBase.onPairingStarted();
            if (pairingProgressHandlerBase.skipWaitingScreenUnlock()) {
                // Do nothing due to we are not showing the status notification in some pairing
                // types, e.g. the retroactive pairing.
            } else {
                // If the screen is locked when the user taps to pair, the screen will unlock. We
                // must wait for the unlock to complete before showing the status notification, or
                // it won't be heads-up.
                pairingProgressHandlerBase.onWaitForScreenUnlock();
                waitUntilScreenIsUnlocked(context);
                pairingProgressHandlerBase.onScreenUnlocked();
            }
            BluetoothAdapter bluetoothAdapter = getBluetoothAdapter(context);

            boolean isBluetoothEnabled = bluetoothAdapter != null && bluetoothAdapter.isEnabled();
            if (!isBluetoothEnabled) {
                if (bluetoothAdapter == null || !bluetoothAdapter.enable()) {
                    Log.d("FastPairManager", "FastPair: Failed to enable bluetooth");
                    return;
                }
                Log.v("FastPairManager", "FastPair: Enabling bluetooth for fast pair");

                Locator.get(context, EventLoop.class)
                        .postRunnable(
                                new NamedRunnable("enableBluetoothToast") {
                                    @Override
                                    public void run() {
                                        Log.d("FastPairManager",
                                                "Enable bluetooth toast test");
                                    }
                                });
                // Set up call back to call this function again once bluetooth has been
                // enabled; this does not seem to be a problem as the device connects without a
                // problem, but in theory the timeout also includes turning on bluetooth now.
            }

            pairingProgressHandlerBase.onReadyToPair();

            String modelId = item.getTriggerId();
            Preferences.Builder prefsBuilder =
                    Preferences.builderFromGmsLog()
                            .setEnableBrEdrHandover(false)
                            .setIgnoreDiscoveryError(true);
            pairingProgressHandlerBase.onSetupPreferencesBuilder(prefsBuilder);
            if (item.getFastPairInformation() != null) {
                prefsBuilder.setSkipConnectingProfiles(
                        item.getFastPairInformation().getDataOnlyConnection());
            }
            // When add watch and auto device needs to change the config
            prefsBuilder.setRejectMessageAccess(true);
            prefsBuilder.setRejectPhonebookAccess(true);
            prefsBuilder.setHandlePasskeyConfirmationByUi(false);

            FastPairConnection connection = new FastPairDualConnection(
                    context, item.getMacAddress(),
                    prefsBuilder.build(),
                    null);
            pairingProgressHandlerBase.onPairingSetupCompleted();

            FastPairConnection.SharedSecret sharedSecret;
            if ((accountKey != null || item.getAuthenticationPublicKeySecp256R1() != null)) {
                sharedSecret =
                        connection.pair(
                                accountKey != null ? accountKey
                                        : item.getAuthenticationPublicKeySecp256R1());

                byte[] key = pairingProgressHandlerBase.getKeyForLocalCache(accountKey,
                        connection, sharedSecret);

                // We don't cache initial pairing case here but cache it when upload to footprints.
                if (key != null) {
                    // CacheManager to save the content here
                }
            } else {
                connection.pair();
            }
            pairingProgressHandlerBase.onPairingSuccess(connection.getPublicAddress());
        } catch (BluetoothException
                | InterruptedException
                | ReflectionException
                | TimeoutException
                | ExecutionException
                | PairingException
                | GeneralSecurityException e) {
            Log.e("FastPairManager", "FastPair: Error");
            pairingProgressHandlerBase.onPairingFailed(e);
        }
    }

    /** Checks if the pairing is initial pairing with fast pair 2.0 design. */
    public static boolean isThroughFastPair2InitialPairing(
            DiscoveryItem item, @Nullable byte[] accountKey) {
        return accountKey == null && item.getAuthenticationPublicKeySecp256R1() != null;
    }

    private static void waitUntilScreenIsUnlocked(Context context)
            throws InterruptedException, ExecutionException, TimeoutException {
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);

        // KeyguardManager's isScreenLocked() counterintuitively returns false when the lock screen
        // is showing if the user has set "swipe to unlock" (i.e. no required password, PIN, or
        // pattern) So we use this method instead, which returns true when on the lock screen
        // regardless.
        if (keyguardManager.isKeyguardLocked()) {
            Log.v("FastPairManager",
                    "FastPair: Screen is locked, waiting until unlocked "
                            + "to show status notifications.");
            try (SimpleBroadcastReceiver isUnlockedReceiver =
                         SimpleBroadcastReceiver.oneShotReceiver(
                                 context, FlagUtils.getPreferencesBuilder().build(),
                                 Intent.ACTION_USER_PRESENT)) {
                isUnlockedReceiver.await(WAIT_FOR_UNLOCK_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * This function should only be called on main thread since there is no lock
     */
    private static Executor getExecutor() {
        if (sFastPairExecutor != null) {
            return sFastPairExecutor;
        }
        sFastPairExecutor = Executors.newSingleThreadExecutor();
        return sFastPairExecutor;
    }

    /**
     * Helper function to get bluetooth adapter.
     */
    @Nullable
    public static BluetoothAdapter getBluetoothAdapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

}
