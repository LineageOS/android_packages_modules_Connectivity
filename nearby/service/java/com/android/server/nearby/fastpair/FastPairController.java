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

import static com.android.server.nearby.common.bluetooth.fastpair.BroadcastConstants.EXTRA_RETROACTIVE_PAIR;
import static com.android.server.nearby.common.fastpair.service.UserActionHandlerBase.EXTRA_COMPANION_APP;
import static com.android.server.nearby.fastpair.FastPairManager.EXTRA_NOTIFICATION_ID;

import static com.google.common.io.BaseEncoding.base16;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.android.server.nearby.common.eventloop.Annotations;
import com.android.server.nearby.common.eventloop.EventLoop;
import com.android.server.nearby.common.eventloop.NamedRunnable;
import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;
import com.android.server.nearby.fastpair.footprint.FootprintsDeviceManager;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager;
import com.android.server.nearby.fastpair.notification.FastPairNotificationManager;
import com.android.server.nearby.fastpair.pairinghandler.PairingProgressHandlerBase;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import service.proto.Cache;

/**
 * FastPair controller after get the info from intent handler Fast Pair controller is responsible
 * for pairing control.
 */
public class FastPairController {
    private final Context mContext;
    private final EventLoop mEventLoop;
    private final FastPairCacheManager mFastPairCacheManager;
    private final FootprintsDeviceManager mFootprintsDeviceManager;
    private boolean mIsFastPairing = false;
    @Nullable private Callback mCallback;
    public FastPairController(Context context) {
        mContext = context;
        mEventLoop = Locator.get(mContext, EventLoop.class);
        mFastPairCacheManager = Locator.get(mContext, FastPairCacheManager.class);
        mFootprintsDeviceManager = Locator.get(mContext, FootprintsDeviceManager.class);
    }

    /**
     * Should be called on create lifecycle.
     */
    @WorkerThread
    public void onCreate() {
        mEventLoop.postRunnable(new NamedRunnable("FastPairController::InitializeScanner") {
            @Override
            public void run() {
                // init scanner here and start scan.
            }
        });
    }

    /**
     * Should be called on destroy lifecycle.
     */
    @WorkerThread
    public void onDestroy() {
        mEventLoop.postRunnable(new NamedRunnable("FastPairController::DestroyScanner") {
            @Override
            public void run() {
                // Unregister scanner from here
            }
        });
    }

    /**
     * Pairing function.
     */
    @UiThread
    public void pair(Intent intent) {
        String itemId = intent.getStringExtra(UserActionHandler.EXTRA_ITEM_ID);
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        byte[] discoveryItem = intent.getByteArrayExtra(UserActionHandler.EXTRA_DISCOVERY_ITEM);
        String accountKeyString = intent.getStringExtra(UserActionHandler.EXTRA_FAST_PAIR_SECRET);
        String companionApp = trimCompanionApp(intent.getStringExtra(EXTRA_COMPANION_APP));
        byte[] accountKey = accountKeyString != null ? base16().decode(accountKeyString) : null;
        boolean isRetroactivePair = intent.getBooleanExtra(EXTRA_RETROACTIVE_PAIR, false);

        mEventLoop.postRunnable(
                new NamedRunnable("fastPairWith=" + itemId) {
                    @Override
                    public void run() {
                        DiscoveryItem item = null;
                        if (itemId != null) {
                            // api call to get Fast Pair related info
                            item = mFastPairCacheManager.getDiscoveryItem(itemId);
                        } else if (discoveryItem != null) {
                            try {
                                item = new DiscoveryItem(mContext,
                                        Cache.StoredDiscoveryItem.parseFrom(discoveryItem));
                            } catch (InvalidProtocolBufferException e) {
                                Log.w("FastPairController",
                                        "Error parsing serialized discovery item with size "
                                                + discoveryItem.length);
                            }
                        }


                        if (item == null || TextUtils.isEmpty(item.getMacAddress())) {
                            Log.w("FastPairController",
                                    "Invalid DiscoveryItem, ignore pairing");
                            return;
                        }

                        // Check enabled state to prevent multiple pair attempts if we get the
                        // intent more than once (this can happen due to an Android platform
                        // bug - b/31459521).
                        if (item.getState() != Cache.StoredDiscoveryItem.State.STATE_ENABLED
                                && !isRetroactivePair) {
                            Log.d("FastPairController", "Incorrect state, ignore pairing");
                            return;
                        }

                        boolean useLargeNotifications = accountKey != null
                                || item.getAuthenticationPublicKeySecp256R1() != null;
                        FastPairNotificationManager fastPairNotificationManager =
                                notificationId == -1
                                        ? new FastPairNotificationManager(mContext, item,
                                        useLargeNotifications)
                                        : new FastPairNotificationManager(mContext, item,
                                                useLargeNotifications, notificationId);
                        FastPairHalfSheetManager fastPairHalfSheetManager =
                                Locator.get(mContext, FastPairHalfSheetManager.class);

                        mFastPairCacheManager.saveDiscoveryItem(item);

                        PairingProgressHandlerBase pairingProgressHandlerBase =
                                PairingProgressHandlerBase.create(
                                        mContext,
                                        item,
                                        companionApp,
                                        accountKey,
                                        mFootprintsDeviceManager,
                                        fastPairNotificationManager,
                                        fastPairHalfSheetManager,
                                        isRetroactivePair);

                        pair(item, accountKey, companionApp, pairingProgressHandlerBase);
                    }
                });
    }

    /**
     * Pairing function
     */
    @Annotations.EventThread
    public void pair(
            DiscoveryItem item,
            @Nullable byte[] accountKey,
            @Nullable String companionApp,
            PairingProgressHandlerBase pairingProgressHandlerBase) {
        if (mIsFastPairing) {
            Log.d("FastPairController", "FastPair: fastpairing, skip pair request");
            return;
        }
        Log.d("FastPairController", "FastPair: start pair");

        // Hide all "tap to pair" notifications until after the flow completes.
        mEventLoop.removeRunnable(mReEnableAllDeviceItemsRunnable);
        if (mCallback != null) {
            mCallback.fastPairUpdateDeviceItemsEnabled(false);
        }

        Future<Void> task =
                FastPairManager.pair(
                        Executors.newSingleThreadExecutor(),
                        mContext,
                        item,
                        accountKey,
                        companionApp,
                        mFootprintsDeviceManager,
                        pairingProgressHandlerBase);
        mIsFastPairing = true;
    }

    /** Fixes a companion app package name with extra spaces. */
    private static String trimCompanionApp(String companionApp) {
        return companionApp == null ? null : companionApp.trim();
    }

    private final NamedRunnable mReEnableAllDeviceItemsRunnable =
            new NamedRunnable("reEnableAllDeviceItems") {
                @Override
                public void run() {
                    if (mCallback != null) {
                        mCallback.fastPairUpdateDeviceItemsEnabled(true);
                    }
                }
            };

    interface Callback {
        void fastPairUpdateDeviceItemsEnabled(boolean enabled);
    }
}
