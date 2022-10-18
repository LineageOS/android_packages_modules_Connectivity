/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.nearby.fastpair.Constant.TAG;

import static com.google.common.primitives.Bytes.concat;

import android.accounts.Account;
import android.annotation.Nullable;
import android.content.Context;
import android.nearby.FastPairDevice;
import android.nearby.NearbyDevice;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.nearby.common.ble.decode.FastPairDecoder;
import com.android.server.nearby.common.ble.util.RangingUtils;
import com.android.server.nearby.common.bloomfilter.BloomFilter;
import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager;
import com.android.server.nearby.provider.FastPairDataProvider;
import com.android.server.nearby.util.DataUtils;
import com.android.server.nearby.util.Hex;

import java.util.List;

import service.proto.Cache;
import service.proto.Data;
import service.proto.Rpcs;

/**
 * Handler that handle fast pair related broadcast.
 */
public class FastPairAdvHandler {
    Context mContext;
    String mBleAddress;
    // Need to be deleted after notification manager in use.
    private boolean mIsFirst = false;
    private FastPairDataProvider mPairDataProvider;
    private static final double NEARBY_DISTANCE_THRESHOLD = 0.6;
    // The byte, 0bLLLLTTTT, for battery length and type.
    // Bit 0 - 3: type, 0b0011 (show UI indication) or 0b0100 (hide UI indication).
    // Bit 4 - 7: length.
    // Reference: https://developers.google.com/nearby/fast-pair/specifications/extensions/batterynotification
    private static final byte SHOW_UI_INDICATION = 0b0011;
    private static final byte HIDE_UI_INDICATION = 0b0100;
    private static final int LENGTH_ADVERTISEMENT_TYPE_BIT = 4;

    /** The types about how the bloomfilter is processed. */
    public enum ProcessBloomFilterType {
        IGNORE, // The bloomfilter is not handled. e.g. distance is too far away.
        CACHE, // The bloomfilter is recognized in the local cache.
        FOOTPRINT, // Need to check the bloomfilter from the footprints.
        ACCOUNT_KEY_HIT // The specified account key was hit the bloom filter.
    }

    /**
     * Constructor function.
     */
    public FastPairAdvHandler(Context context) {
        mContext = context;
    }

    @VisibleForTesting
    FastPairAdvHandler(Context context, FastPairDataProvider dataProvider) {
        mContext = context;
        mPairDataProvider = dataProvider;
    }

    /**
     * Handles all of the scanner result. Fast Pair will handle model id broadcast bloomfilter
     * broadcast and battery level broadcast.
     */
    public void handleBroadcast(NearbyDevice device) {
        FastPairDevice fastPairDevice = (FastPairDevice) device;
        mBleAddress = fastPairDevice.getBluetoothAddress();
        if (mPairDataProvider == null) {
            mPairDataProvider = FastPairDataProvider.getInstance();
        }
        if (mPairDataProvider == null) {
            return;
        }

        if (FastPairDecoder.checkModelId(fastPairDevice.getData())) {
            byte[] model = FastPairDecoder.getModelId(fastPairDevice.getData());
            Log.d(TAG, "On discovery model id " + Hex.bytesToStringLowercase(model));
            // Use api to get anti spoofing key from model id.
            try {
                List<Account> accountList = mPairDataProvider.loadFastPairEligibleAccounts();
                Rpcs.GetObservedDeviceResponse response =
                        mPairDataProvider.loadFastPairAntispoofKeyDeviceMetadata(model);
                if (response == null) {
                    Log.e(TAG, "server does not have model id "
                            + Hex.bytesToStringLowercase(model));
                    return;
                }
                // Check the distance of the device if the distance is larger than the threshold
                // do not show half sheet.
                if (!isNearby(fastPairDevice.getRssi(),
                        response.getDevice().getBleTxPower() == 0 ? fastPairDevice.getTxPower()
                                : response.getDevice().getBleTxPower())) {
                    return;
                }
                Locator.get(mContext, FastPairHalfSheetManager.class).showHalfSheet(
                        DataUtils.toScanFastPairStoreItem(
                                response, mBleAddress, Hex.bytesToStringLowercase(model),
                                accountList.isEmpty() ? null : accountList.get(0).name));
            } catch (IllegalStateException e) {
                Log.e(TAG, "OEM does not construct fast pair data proxy correctly");
            }
        } else {
            // Start to process bloom filter. Yet to finish.
            try {
                List<Account> accountList = mPairDataProvider.loadFastPairEligibleAccounts();
                byte[] bloomFilterByteArray = FastPairDecoder
                        .getBloomFilter(fastPairDevice.getData());
                byte[] bloomFilterSalt = FastPairDecoder
                        .getBloomFilterSalt(fastPairDevice.getData());
                if (bloomFilterByteArray == null || bloomFilterByteArray.length == 0) {
                    return;
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "OEM does not construct fast pair data proxy correctly");
            }
        }
    }

    // Battery advertisement format:
    // Byte 0: Battery length and type, Bit 0 - 3: type, Bit 4 - 7: length.
    // Byte 1 - 3: Battery values.
    // Reference:
    // https://developers.google.com/nearby/fast-pair/specifications/extensions/batterynotification
    @VisibleForTesting
    static byte[] generateBatteryData(byte[] data) {
        byte[] batteryLevelNoNotification = FastPairDecoder.getBatteryLevelNoNotification(data);
        boolean suppressBatteryNotification =
                (batteryLevelNoNotification != null && batteryLevelNoNotification.length > 0);
        byte[] batteryValues =
                suppressBatteryNotification
                        ? batteryLevelNoNotification
                        : FastPairDecoder.getBatteryLevel(data);
        if (batteryValues == null || batteryValues.length == 0) {
            return new byte[0];
        }
        return concat(
                new byte[] {
                        (byte)
                                (batteryValues.length << LENGTH_ADVERTISEMENT_TYPE_BIT
                                        | (suppressBatteryNotification
                                        ? HIDE_UI_INDICATION : SHOW_UI_INDICATION))
                },
                batteryValues);
    }

    /**
     * Checks the bloom filter to see if any of the devices are recognized and should have a
     * notification displayed for them. A device is recognized if the account key + salt combination
     * is inside the bloom filter.
     */
    @Nullable
    static Data.FastPairDeviceWithAccountKey findRecognizedDevice(
            List<Data.FastPairDeviceWithAccountKey> devices, BloomFilter bloomFilter, byte[] salt) {
        Log.d(TAG, "saved devices size in the account is " + devices.size());
        for (Data.FastPairDeviceWithAccountKey device : devices) {
            if (device.getAccountKey().toByteArray() == null || salt == null) {
                return null;
            }
            byte[] rotatedKey = concat(device.getAccountKey().toByteArray(), salt);
            StringBuilder sb = new StringBuilder();
            for (byte b : rotatedKey) {
                sb.append(b);
            }
            if (bloomFilter.possiblyContains(rotatedKey)) {
                Log.d(TAG, "match " + sb.toString());
                return device;
            } else {
                Log.d(TAG, "not match " + sb.toString());
            }
        }
        return null;
    }

    @Nullable
    static Cache.StoredFastPairItem findRecognizedDeviceFromCachedItem(
            List<Cache.StoredFastPairItem> devices, BloomFilter bloomFilter, byte[] salt) {
        for (Cache.StoredFastPairItem device : devices) {
            if (device.getAccountKey().toByteArray() == null || salt == null) {
                return null;
            }
            byte[] rotatedKey = concat(device.getAccountKey().toByteArray(), salt);
            if (bloomFilter.possiblyContains(rotatedKey)) {
                return device;
            }
        }
        return null;
    }

    /**
     * Check the device distance for certain rssi value.
     */
    boolean isNearby(int rssi, int txPower) {
        return RangingUtils.distanceFromRssiAndTxPower(rssi, txPower) < NEARBY_DISTANCE_THRESHOLD;
    }
}
