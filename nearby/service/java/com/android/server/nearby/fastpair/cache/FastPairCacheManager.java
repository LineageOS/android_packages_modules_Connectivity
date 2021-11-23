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
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.android.server.nearby.common.eventloop.Annotations;

import com.google.protobuf.InvalidProtocolBufferException;

import service.proto.Cache;
import service.proto.Rpcs;


/**
 * Save FastPair device info to database to avoid multiple requesting.
 */
public class FastPairCacheManager {
    private final Context mContext;
    private final DiscoveryItemDbHelper mDiscoveryItemDbHelper;

    public FastPairCacheManager(Context context) {
        mContext = context;
        mDiscoveryItemDbHelper = new DiscoveryItemDbHelper(context);
    }

    /**
     * Clean up function to release db
     */
    public void cleanUp() {
        mDiscoveryItemDbHelper.close();
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

    /**
     * Save discovery item into database.
     */
    public boolean saveDiscoveryItem(DiscoveryItem item) {
        SQLiteDatabase db = mDiscoveryItemDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DiscoveryItemContract.DiscoveryItemEntry.COLUMN_MODEL_ID, item.getTriggerId());
        values.put(DiscoveryItemContract.DiscoveryItemEntry.COLUMN_SCAN_BYTE,
                item.getCopyOfStoredItem().toByteArray());
        db.insert(DiscoveryItemContract.DiscoveryItemEntry.TABLE_NAME, null, values);
        return true;
    }


    @Annotations.EventThread
    private Rpcs.GetObservedDeviceResponse getObservedDeviceInfo(ScanResult scanResult) {
        return Rpcs.GetObservedDeviceResponse.getDefaultInstance();
    }

    /**
     * Get discovery item from item id.
     */
    public DiscoveryItem getDiscoveryItem(String itemId) {
        return new DiscoveryItem(mContext, getStoredDiscoveryItem(itemId));
    }

    /**
     * Get discovery item from item id.
     */
    public Cache.StoredDiscoveryItem getStoredDiscoveryItem(String itemId) {

        SQLiteDatabase db = mDiscoveryItemDbHelper.getReadableDatabase();
        String[] projection = {
                DiscoveryItemContract.DiscoveryItemEntry.COLUMN_MODEL_ID,
                DiscoveryItemContract.DiscoveryItemEntry.COLUMN_SCAN_BYTE
        };
        String selection = DiscoveryItemContract.DiscoveryItemEntry.COLUMN_MODEL_ID + " =? ";
        String[] selectionArgs = {itemId};
        Cursor cursor = db.query(
                DiscoveryItemContract.DiscoveryItemEntry.TABLE_NAME,
                projection,
                selection,
                selectionArgs,
                null,
                null,
                null
        );

        if (cursor.moveToNext()) {
            byte[] res = cursor.getBlob(cursor.getColumnIndexOrThrow(
                    DiscoveryItemContract.DiscoveryItemEntry.COLUMN_SCAN_BYTE));
            try {
                Cache.StoredDiscoveryItem item = Cache.StoredDiscoveryItem.parseFrom(res);
                return item;
            } catch (InvalidProtocolBufferException e) {
                Log.e("FastPairCacheManager", "storediscovery has error");
            }
        }
        cursor.close();
        return Cache.StoredDiscoveryItem.getDefaultInstance();
    }

    /**
     * Get scan result from local database use model id
     */
    public Cache.StoredScanResult getStoredScanResult(String modelId) {
        return Cache.StoredScanResult.getDefaultInstance();
    }

}
