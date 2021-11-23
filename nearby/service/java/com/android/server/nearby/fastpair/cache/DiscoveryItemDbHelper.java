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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Discovery db helper
 */
public class DiscoveryItemDbHelper extends SQLiteOpenHelper {

    public static final int DATABASE_VERSION = 1;
    public static final String DATABASE_NAME = "ScanResult.db";
    private static final String SQL_CREATE_DB =
            "CREATE TABLE " + DiscoveryItemContract.DiscoveryItemEntry.TABLE_NAME
                    + " (" + DiscoveryItemContract.DiscoveryItemEntry._ID
                    + "INTEGER PRIMARY KEY,"
                    + DiscoveryItemContract.DiscoveryItemEntry.COLUMN_MODEL_ID
                    + " TEXT," + DiscoveryItemContract.DiscoveryItemEntry.COLUMN_SCAN_BYTE
                    + " BLOB)";
    private static final String SQL_DELETE_DB =
            "DROP TABLE IF EXISTS " + DiscoveryItemContract.DiscoveryItemEntry.TABLE_NAME;

    public DiscoveryItemDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_DB);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Since the outdated data has no value so just remove the data.
        db.execSQL(SQL_DELETE_DB);
        onCreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        super.onDowngrade(db, oldVersion, newVersion);
    }
}
