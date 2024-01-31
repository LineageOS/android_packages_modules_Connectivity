/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.connectivity;

import android.Manifest;
import android.annotation.NonNull;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Tracks the uid of all the default messaging application which are role_sms role and
 * satellite_communication permission complaint and requests ConnectivityService to create multi
 * layer request with satellite internet access support for the default message application.
 * @hide
 */
public class SatelliteAccessController {
    private static final String TAG = SatelliteAccessController.class.getSimpleName();
    private final PackageManager mPackageManager;
    private final Dependencies mDeps;
    private final DefaultMessageRoleListener mDefaultMessageRoleListener;
    private final Consumer<Set<Integer>> mCallback;
    private final Set<Integer> mSatelliteNetworkPreferredUidCache = new ArraySet<>();
    private final Handler mConnectivityServiceHandler;

    /**
     *  Monitor {@link android.app.role.OnRoleHoldersChangedListener#onRoleHoldersChanged(String,
     *  UserHandle)},
     *
     */
    private final class DefaultMessageRoleListener
            implements OnRoleHoldersChangedListener {
        @Override
        public void onRoleHoldersChanged(String role, UserHandle user) {
            if (RoleManager.ROLE_SMS.equals(role)) {
                Log.i(TAG, "ROLE_SMS Change detected ");
                onRoleSmsChanged();
            }
        }

        public void register() {
            try {
                mDeps.addOnRoleHoldersChangedListenerAsUser(
                        mConnectivityServiceHandler::post, this, UserHandle.ALL);
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not register satellite controller listener due to " + e);
            }
        }
    }

    public SatelliteAccessController(@NonNull final Context c,
            Consumer<Set<Integer>> callback,
            @NonNull final Handler connectivityServiceInternalHandler) {
        this(c, new Dependencies(c), callback, connectivityServiceInternalHandler);
    }

    public static class Dependencies {
        private final RoleManager mRoleManager;

        private Dependencies(Context context) {
            mRoleManager = context.getSystemService(RoleManager.class);
        }

        /** See {@link RoleManager#getRoleHolders(String)} */
        public List<String> getRoleHolders(String roleName) {
            return mRoleManager.getRoleHolders(roleName);
        }

        /** See {@link RoleManager#addOnRoleHoldersChangedListenerAsUser} */
        public void addOnRoleHoldersChangedListenerAsUser(@NonNull Executor executor,
                @NonNull OnRoleHoldersChangedListener listener, UserHandle user) {
            mRoleManager.addOnRoleHoldersChangedListenerAsUser(executor, listener, user);
        }
    }

    @VisibleForTesting
    SatelliteAccessController(@NonNull final Context c, @NonNull final Dependencies deps,
            Consumer<Set<Integer>> callback,
            @NonNull final Handler connectivityServiceInternalHandler) {
        mDeps = deps;
        mPackageManager = c.getPackageManager();
        mDefaultMessageRoleListener = new DefaultMessageRoleListener();
        mCallback = callback;
        mConnectivityServiceHandler = connectivityServiceInternalHandler;
    }

    private void updateSatelliteNetworkPreferredUidListCache(List<String> packageNames) {
        for (String packageName : packageNames) {
            // Check if SATELLITE_COMMUNICATION permission is enabled for default sms application
            // package before adding it part of satellite network preferred uid cache list.
            if (isSatellitePermissionEnabled(packageName)) {
                mSatelliteNetworkPreferredUidCache.add(getUidForPackage(packageName));
            }
        }
    }

    //Check if satellite communication is enabled for the package
    private boolean isSatellitePermissionEnabled(String packageName) {
        if (mPackageManager != null) {
            return mPackageManager.checkPermission(
                    Manifest.permission.SATELLITE_COMMUNICATION, packageName)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    private int getUidForPackage(String pkgName) {
        if (pkgName == null) {
            return Process.INVALID_UID;
        }
        try {
            if (mPackageManager != null) {
                ApplicationInfo applicationInfo = mPackageManager.getApplicationInfo(pkgName, 0);
                if (applicationInfo != null) {
                    return applicationInfo.uid;
                }
            }
        } catch (PackageManager.NameNotFoundException exception) {
            Log.e(TAG, "Unable to find uid for package: " + pkgName);
        }
        return Process.INVALID_UID;
    }

    //on Role sms change triggered by OnRoleHoldersChangedListener()
    private void onRoleSmsChanged() {
        final List<String> packageNames = getRoleSmsChangedPackageName();

        // Create a new Set
        Set<Integer> previousSatellitePreferredUid = new ArraySet<>(
                mSatelliteNetworkPreferredUidCache);

        mSatelliteNetworkPreferredUidCache.clear();

        if (packageNames != null) {
            Log.i(TAG, "role_sms_packages: " + packageNames);
            // On Role change listener, update the satellite network preferred uid cache list
            updateSatelliteNetworkPreferredUidListCache(packageNames);
            Log.i(TAG, "satellite_preferred_uid: " + mSatelliteNetworkPreferredUidCache);
        } else {
            Log.wtf(TAG, "package name was found null");
        }

        // on Role change, update the multilayer request at ConnectivityService with updated
        // satellite network preferred uid cache list if changed or to revoke for previous default
        // sms app
        if (!mSatelliteNetworkPreferredUidCache.equals(previousSatellitePreferredUid)) {
            Log.i(TAG, "update multi layer request");
            mCallback.accept(mSatelliteNetworkPreferredUidCache);
        }
    }

    private List<String> getRoleSmsChangedPackageName() {
        try {
            return mDeps.getRoleHolders(RoleManager.ROLE_SMS);
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Could not get package name at role sms change update due to: " + e);
            return null;
        }
    }

    /** Register OnRoleHoldersChangedListener */
    public void start() {
        mConnectivityServiceHandler.post(this::onRoleSmsChanged);
        mDefaultMessageRoleListener.register();
    }
}
