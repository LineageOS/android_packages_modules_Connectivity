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

package com.android.server.nearby.fastpair.halfsheet;

import static com.android.server.nearby.fastpair.Constant.DEVICE_PAIRING_FRAGMENT_TYPE;
import static com.android.server.nearby.fastpair.Constant.EXTRA_BINDER;
import static com.android.server.nearby.fastpair.Constant.EXTRA_BUNDLE;
import static com.android.server.nearby.fastpair.Constant.EXTRA_HALF_SHEET_INFO;
import static com.android.server.nearby.fastpair.Constant.EXTRA_HALF_SHEET_TYPE;
import static com.android.server.nearby.fastpair.FastPairManager.ACTION_RESOURCES_APK;

import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.fastpair.FastPairController;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;
import com.android.server.nearby.util.Environment;

import java.util.List;
import java.util.stream.Collectors;

import service.proto.Cache;

/**
 * Fast Pair ux manager for half sheet.
 */
public class FastPairHalfSheetManager {
    private static final String ACTIVITY_INTENT_ACTION = "android.nearby.SHOW_HALFSHEET";

    private String mHalfSheetApkPkgName;
    private Context mContext;

    /**
     * Construct function
     */
    public FastPairHalfSheetManager(Context context) {
        mContext = context;
    }


    /**
     * Invokes half sheet in the other apk. This function can only be called in Nearby because other
     * app can't get the correct component name.
     */
    public void showHalfSheet(Cache.ScanFastPairStoreItem scanFastPairStoreItem) {
        if (mContext != null) {
            String packageName = getHalfSheetApkPkgName();
            HalfSheetCallback callback = new HalfSheetCallback();
            callback.setmFastPairController(Locator.get(mContext, FastPairController.class));
            Bundle bundle = new Bundle();
            bundle.putBinder(EXTRA_BINDER, callback);
            mContext
                    .startActivityAsUser(new Intent(ACTIVITY_INTENT_ACTION)
                                    .putExtra(EXTRA_HALF_SHEET_INFO,
                                            scanFastPairStoreItem.toByteArray())
                                    .putExtra(EXTRA_HALF_SHEET_TYPE, DEVICE_PAIRING_FRAGMENT_TYPE)
                                    .putExtra(EXTRA_BUNDLE, bundle)
                                    .setComponent(new ComponentName(packageName,
                                            packageName + ".HalfSheetActivity")),
                            UserHandle.CURRENT);

        }
    }

    /**
     * Shows pairing fail half sheet.
     */
    public void showPairingFailed() {
        Log.d("FastPairHalfSheetManager", "show fail half sheet");
    }

    /**
     * Get the half sheet status whether it is foreground or dismissed
     */
    public boolean getHalfSheetForegroundState() {
        return true;
    }

    /**
     * Show passkey confirmation info on half sheet
     */
    public void showPasskeyConfirmation(BluetoothDevice device, int passkey) {
    }

    /**
     * This function will handle pairing steps for half sheet.
     */
    public void showPairingHalfSheet(DiscoveryItem item) {
        Log.d("FastPairHalfSheetManager", "show pairing half sheet");
    }

    /**
     * Shows pairing success info.
     */
    public void showPairingSuccessHalfSheet(String address) {
        Log.d("FastPairHalfSheetManager", "show success half sheet");
    }

    /**
     * Removes dismiss runnable.
     */
    public void disableDismissRunnable() {
    }

    /**
     * Destroys the bluetooth pairing controller.
     */
    public void destroyBluetoothPairController() {
    }

    /**
     * Notify manager the pairing has finished.
     */
    public void notifyPairingProcessDone(boolean success, String address, DiscoveryItem item) {
    }

    /**
     * Gets the package name of HalfSheet.apk
     * getHalfSheetApkPkgName may invoke PackageManager multiple times and it does not have
     * race condition check. Since there is no lock for mHalfSheetApkPkgName.
     */
    String getHalfSheetApkPkgName() {
        if (mHalfSheetApkPkgName != null) {
            return mHalfSheetApkPkgName;
        }
        List<ResolveInfo> resolveInfos = mContext
                .getPackageManager().queryIntentActivities(
                        new Intent(ACTION_RESOURCES_APK),
                        PackageManager.MATCH_SYSTEM_ONLY);

        // remove apps that don't live in the nearby apex
        resolveInfos.removeIf(info ->
                !Environment.isAppInNearbyApex(info.activityInfo.applicationInfo));

        if (resolveInfos.isEmpty()) {
            // Resource APK not loaded yet, print a stack trace to see where this is called from
            Log.e("FastPairManager", "Attempted to fetch resources before halfsheet "
                            + " APK is installed or package manager can't resolve correctly!",
                    new IllegalStateException());
            return null;
        }

        if (resolveInfos.size() > 1) {
            // multiple apps found, log a warning, but continue
            Log.w("FastPairManager", "Found > 1 APK that can resolve halfsheet APK intent: "
                    + resolveInfos.stream()
                    .map(info -> info.activityInfo.applicationInfo.packageName)
                    .collect(Collectors.joining(", ")));
        }

        // Assume the first ResolveInfo is the one we're looking for
        ResolveInfo info = resolveInfos.get(0);
        mHalfSheetApkPkgName = info.activityInfo.applicationInfo.packageName;
        Log.i("FastPairManager", "Found halfsheet APK at: " + mHalfSheetApkPkgName);
        return mHalfSheetApkPkgName;
    }
}
