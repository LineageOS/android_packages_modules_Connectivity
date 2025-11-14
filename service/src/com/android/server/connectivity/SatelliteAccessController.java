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

import static android.os.Process.INVALID_UID;

import static com.android.server.connectivity.ConnectivityFlags.CONSTRAINED_DATA_SATELLITE_OPTIN;

import android.Manifest;
import android.annotation.NonNull;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.HandlerUtils;
import com.android.net.module.util.SharedLog;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import javax.annotation.CheckReturnValue;

/**
 * Tracks the uid of all the default messaging application which are role_sms role and
 * satellite_communication permission complaint and requests ConnectivityService to create multi
 * layer request with satellite internet access support for the default message application.
 *
 * Note that this class is not thread safe and should only be accessed on the handler
 * thread, except {@link #start()}.
 */
public class SatelliteAccessController {
    private static final String TAG = SatelliteAccessController.class.getSimpleName();
    // Shamelessly copied from telephony/satellite/SatelliteManager.java.
    // TODO: Import from SatelliteManager when it is available.
    @VisibleForTesting
    public static final String PROPERTY_SATELLITE_DATA_OPTIMIZED =
            "android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED";
    // This value is taken from android.os.UserHandle#PER_USER_RANGE.
    @VisibleForTesting
    public static final int PER_USER_RANGE = 100000;
    private static final int MAX_LOG_ENTRIES = 50;
    private final Context mContext;
    private final Dependencies mDeps;
    private final DefaultMessageRoleListener mDefaultMessageRoleListener;
    private final BiConsumer<Set<Integer>, Set<Integer>> mCallback;
    private final Handler mConnectivityServiceHandler;
    private final boolean mSupportConstrainedDataSatelliteOptIn;
    private final SharedLog mLog = new SharedLog(MAX_LOG_ENTRIES, TAG);

    // At this sparseArray, Key is userId and values are uids of SMS apps that are allowed
    // to use satellite network as fallback.
    private final SparseArray<Set<Integer>> mSatelliteRoleSmsUids = new SparseArray<>();

    // Set of UIDs that have declared the
    // {@code android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED} meta-data
    // with a value of package name in their manifest file. This variable will only be
    // accessed on the handler thread.
    // See {@link SatelliteManager#PROPERTY_SATELLITE_DATA_OPTIMIZED}.
    private final Set<Integer> mSatelliteDataOptInUids = new ArraySet<>();

    private final ArrayMap<UserHandle, PackageManager> mUserPackageManagers = new ArrayMap<>();

    /**
     *  Monitor {@link android.app.role.OnRoleHoldersChangedListener#onRoleHoldersChanged(String,
     *  UserHandle)},
     *
     */
    private final class DefaultMessageRoleListener
            implements OnRoleHoldersChangedListener {
        @Override
        public void onRoleHoldersChanged(String role, UserHandle userHandle) {
            HandlerUtils.ensureRunningOnHandlerThread(mConnectivityServiceHandler);
            if (RoleManager.ROLE_SMS.equals(role) && updateSatelliteRoleSmsUids(userHandle)) {
                mLog.i("ROLE_SMS Change detected ");
                reportSatelliteNetworkFallbackUids();
            }
        }

        public void register() {
            try {
                mDeps.addOnRoleHoldersChangedListenerAsUser(
                        mConnectivityServiceHandler::post, this, UserHandle.ALL);
            } catch (RuntimeException e) {
                mLog.wtf("Could not register satellite controller listener due to " + e);
            }
        }
    }

    public SatelliteAccessController(@NonNull final Context c,
            BiConsumer<Set<Integer>, Set<Integer>> callback,
            @NonNull final Handler connectivityServiceInternalHandler) {
        this(c, new Dependencies(c), callback, connectivityServiceInternalHandler);
    }

    public static class Dependencies {
        private final RoleManager mRoleManager;

        private Dependencies(Context context) {
            mRoleManager = context.getSystemService(RoleManager.class);
        }

        /** See {@link RoleManager#getRoleHoldersAsUser(String, UserHandle)} */
        public List<String> getRoleHoldersAsUser(String roleName, UserHandle userHandle) {
            return mRoleManager.getRoleHoldersAsUser(roleName, userHandle);
        }

        /** See {@link RoleManager#addOnRoleHoldersChangedListenerAsUser} */
        public void addOnRoleHoldersChangedListenerAsUser(@NonNull Executor executor,
                @NonNull OnRoleHoldersChangedListener listener, UserHandle user) {
            mRoleManager.addOnRoleHoldersChangedListenerAsUser(executor, listener, user);
        }

        /** Return whether constrained data satellite opt-in is supported. */
        public boolean supportConstrainedDataSatelliteOptIn(Context context) {
            return DeviceConfigUtils.isTetheringFeatureNotChickenedOut(context,
                    CONSTRAINED_DATA_SATELLITE_OPTIN);
        }
    }

    @VisibleForTesting
    SatelliteAccessController(@NonNull final Context c, @NonNull final Dependencies deps,
            BiConsumer<Set<Integer>, Set<Integer>> callback,
            @NonNull final Handler connectivityServiceInternalHandler) {
        mContext = c;
        mDeps = deps;
        mDefaultMessageRoleListener = new DefaultMessageRoleListener();
        mCallback = callback;
        mConnectivityServiceHandler = connectivityServiceInternalHandler;
        mSupportConstrainedDataSatelliteOptIn = mDeps.supportConstrainedDataSatelliteOptIn(c);
    }

    @NonNull
    private Set<Integer> getRoleSmsUidsWithSatellitePermission(List<String> packageNames,
            @NonNull UserHandle userHandle) {
        final Set<Integer> roleSmsUids = new ArraySet<>();
        final PackageManager pm = getPackageManagerForUser(userHandle);
        if (pm != null) {
            for (String packageName : packageNames) {
                // Check if SATELLITE_COMMUNICATION permission is enabled for default sms
                // application package before adding it part of satellite network role-sms uid
                // cache list.
                if (isSatellitePermissionEnabled(pm, packageName)) {
                    int uid = getUidForPackage(pm, packageName);
                    if (uid != INVALID_UID) {
                        roleSmsUids.add(uid);
                    }
                }
            }
        } else {
            mLog.wtf("package manager found null for user " + userHandle);
        }
        return roleSmsUids;
    }

    // Check if satellite communication is enabled for the package
    private boolean isSatellitePermissionEnabled(PackageManager packageManager,
            String packageName) {
        return packageManager.checkPermission(
                Manifest.permission.SATELLITE_COMMUNICATION, packageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    private int getUidForPackage(PackageManager packageManager, String pkgName) {
        if (pkgName == null) {
            return INVALID_UID;
        }
        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(pkgName, 0);
            return applicationInfo.uid;
        } catch (PackageManager.NameNotFoundException exception) {
            mLog.e("Unable to find uid for package: " + pkgName);
        }
        return INVALID_UID;
    }

    @CheckReturnValue
    private boolean updateSatelliteRoleSmsUids(@NonNull UserHandle userHandle) {
        int userId = userHandle.getIdentifier();
        if (userId == INVALID_UID) {
            mLog.wtf("Invalid User Id for userHandle:" + userHandle);
            return false;
        }

        //Returns empty list if no package exists
        final List<String> packageNames =
                mDeps.getRoleHoldersAsUser(RoleManager.ROLE_SMS, userHandle);

        // Store previous satellite role sms uid available
        final Set<Integer> prevUidsForUser = mSatelliteRoleSmsUids.get(userId, new ArraySet<>());
        final Set<Integer> newUidsForUser =
                getRoleSmsUidsWithSatellitePermission(packageNames, userHandle);

        // on Role change, update the multilayer request at ConnectivityService with updated
        // satellite network role-sms uid cache list of multiple users as applicable
        if (newUidsForUser.equals(prevUidsForUser)) {
            return false;
        }

        mSatelliteRoleSmsUids.put(userId, newUidsForUser);
        return true;
    }

    private void reportSatelliteNetworkFallbackUids() {
        // Merge all uids of multiple users available
        final Set<Integer> mergedSatelliteRoleSmsUids = new ArraySet<>();
        for (int i = 0; i < mSatelliteRoleSmsUids.size(); i++) {
            mergedSatelliteRoleSmsUids.addAll(mSatelliteRoleSmsUids.valueAt(i));
        }
        mLog.i("SmsRoleUids:" + mergedSatelliteRoleSmsUids
                + " Opt-InUids:" + mSatelliteDataOptInUids);

        // trigger multiple layer request for satellite network fallback of multi user uids
        final ArraySet<Integer> optInUids = new ArraySet(mSatelliteDataOptInUids);
        // If the same UID is in both sets, keep it only in the first one, which grant
        // stronger privilege to access the satellite network.
        optInUids.removeAll(mergedSatelliteRoleSmsUids);
        mCallback.accept(mergedSatelliteRoleSmsUids, optInUids);
    }

    public void start() {
        // register sms OnRoleHoldersChangedListener
        mDefaultMessageRoleListener.register();
    }

    @CheckReturnValue
    private boolean updateSatelliteRoleSmsUidListOnUserRemoval(int userIdRemoved) {
        mLog.i("user id removed:" + userIdRemoved);
        if (mSatelliteRoleSmsUids.contains(userIdRemoved)) {
            mSatelliteRoleSmsUids.remove(userIdRemoved);
            return true; // Changed.
        }
        return false; // Unchanged.
    }

    /**
     * Called when a user is added. See {link #ACTION_USER_ADDED}.
     *
     * Note that this method will also be called at start up once on the handler thread
     * to iterate through all existing users.
     *
     * @param userHandle The userHandle of the added user. See {@link #EXTRA_USER_HANDLE}.
     * @param apps The list of packages which is installed on the user.
     */
    public void onUserAddedWithInstalledPackageList(@NonNull UserHandle userHandle,
            @NonNull List<PackageInfo> apps) {
        HandlerUtils.ensureRunningOnHandlerThread(mConnectivityServiceHandler);
        // Store PackageManager for user for later use.
        final PackageManager pmForUser = getPackageManagerForUser(userHandle);

        // Obtain uids with role sms and satellite communication permission for the added user.
        final boolean roleSmsUidsChanged = updateSatelliteRoleSmsUids(userHandle);

        boolean optInUidsChanged = false;
        if (mSupportConstrainedDataSatelliteOptIn) {
            final Set<Integer> satelliteDataOptInUidsForUser =
                    getSatelliteDataOptInUidsForUser(pmForUser, apps);
            if (satelliteDataOptInUidsForUser.size() > 0) {
                mLog.i("Add SatelliteDataOptInUids for user " + userHandle + ": "
                        + satelliteDataOptInUidsForUser);
                mSatelliteDataOptInUids.addAll(satelliteDataOptInUidsForUser);
                optInUidsChanged = true;
            }
        }
        if (roleSmsUidsChanged || optInUidsChanged) {
            reportSatelliteNetworkFallbackUids();
        }
    }

    /**
     * Called when a user is removed. See {link #ACTION_USER_REMOVED}.
     *
     * @param userHandle The integer userHandle of the removed user. See {@link #EXTRA_USER_HANDLE}.
     */
    public void onUserRemoved(@NonNull UserHandle userHandle) {
        HandlerUtils.ensureRunningOnHandlerThread(mConnectivityServiceHandler);
        final boolean smsRoleUidsChanged =
                updateSatelliteRoleSmsUidListOnUserRemoval(userHandle.getIdentifier());
        final boolean satelliteOptInUidsChanged;
        mUserPackageManagers.remove(userHandle);
        if (mSupportConstrainedDataSatelliteOptIn) {
            satelliteOptInUidsChanged =
                    removeSatelliteDataOptInUidsForUser(userHandle.getIdentifier());
        } else {
            satelliteOptInUidsChanged = false;
        }
        if (smsRoleUidsChanged || satelliteOptInUidsChanged) {
            reportSatelliteNetworkFallbackUids();
        }
    }

    /**
     * Called when a package is added.
     *
     * @param packageName The name of the new package.
     * @param uid The uid of the new package.
     */
    public void onPackageAdded(@NonNull final String packageName, final int uid) {
        HandlerUtils.ensureRunningOnHandlerThread(mConnectivityServiceHandler);
        if (!mSupportConstrainedDataSatelliteOptIn) return;
        if (addSatelliteDataOptInUid(packageName, uid)) {
            reportSatelliteNetworkFallbackUids();
        }
    }

    @CheckReturnValue
    private boolean addSatelliteDataOptInUid(@NonNull final String packageName, final int uid) {
        if (mSupportConstrainedDataSatelliteOptIn
                && isSatelliteDataOptimizedApp(getPackageManagerForUid(uid), packageName)) {
            mSatelliteDataOptInUids.add(uid);
            return true;
        }
        return false;
    }

    /**
     * Called when the availability of external applications changes.
     *
     * @param pkgList An array of package names that have become available.
     */
    public void onExternalApplicationsAvailable(String[] pkgList) {
        HandlerUtils.ensureRunningOnHandlerThread(mConnectivityServiceHandler);
        if (!mSupportConstrainedDataSatelliteOptIn) return;
        if (CollectionUtils.isEmpty(pkgList)) {
            mLog.e("No available external application.");
            return;
        }

        boolean added = false;
        for (String app : pkgList) {
            for (final PackageManager pm : mUserPackageManagers.values()) {
                final int uid = getUidForPackage(pm, app);
                if (uid != INVALID_UID && addSatelliteDataOptInUid(app, uid)) {
                    added = true;
                }
            }
        }
        if (added) {
            reportSatelliteNetworkFallbackUids();
        }
    }

    @NonNull
    private PackageManager getPackageManagerForUid(int uid) {
        return getPackageManagerForUser(UserHandle.getUserHandleForUid(uid));
    }

    /**
     * Called when a package is removed.
     *
     * @param packageName The name of the removed package or null.
     * @param uid containing the integer uid previously assigned to the package.
     */
    public void onPackageRemoved(@NonNull final String packageName, final int uid) {
        HandlerUtils.ensureRunningOnHandlerThread(mConnectivityServiceHandler);
        if (!mSupportConstrainedDataSatelliteOptIn) return;

        // Scan for all apps sharing the same uid.
        final PackageManager pmForUser = getPackageManagerForUid(uid);
        final String [] pkgs = pmForUser.getPackagesForUid(uid);
        if (pkgs != null) {
            for (String pkg : pkgs) {
                if (!pkg.equals(packageName) && isSatelliteDataOptimizedApp(pmForUser, pkg)) {
                    return; // Early return if another satellite-optimized app shares the UID
                }
            }
        }
        // If the loop completes without returning, it means no other
        // satellite-optimized app shares the UID.
        final boolean removed = mSatelliteDataOptInUids.remove(uid);
        if (removed) {
            reportSatelliteNetworkFallbackUids();
        }
    }

    @NonNull
    private Set<Integer> getSatelliteDataOptInUidsForUser(@NonNull PackageManager pmForUser,
            @NonNull List<PackageInfo> apps) {
        final ArraySet<Integer> uids = new ArraySet<>();
        for (PackageInfo app : apps) {
            if (null == app.applicationInfo || app.applicationInfo.uid < 0) continue;
            if (isSatelliteDataOptimizedApp(pmForUser, app.packageName)) {
                uids.add(app.applicationInfo.uid);
            }
        }
        return uids;
    }

    private boolean isSatelliteDataOptimizedApp(@NonNull PackageManager pmForUser,
            @NonNull String packageName) {
        try {
            final ApplicationInfo applicationInfo = pmForUser.getApplicationInfo(
                    packageName, PackageManager.GET_META_DATA);
            final Bundle metaData = applicationInfo.metaData;
            if (metaData == null) return false;
            // Retrieve the value as a generic Object to avoid Bundle warning log
            // flooding when the format is mismatched.
            final Object rawValue = metaData.get(PROPERTY_SATELLITE_DATA_OPTIMIZED);
            if (rawValue == null) return false; // No expected meta-data.

            // Check if the retrieved object is a matched String.
            if (rawValue instanceof String
                    && TextUtils.equals((String) rawValue, packageName)) {
                return true;
            }
            // Logging if the value is not expected (e.g., Boolean, wrong package name).
            mLog.i("Wrong meta-data format: " + packageName);
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // Return true if changed.
    @CheckReturnValue
    private boolean removeSatelliteDataOptInUidsForUser(int userIdToRemove) {
        return mSatelliteDataOptInUids.removeIf(uid -> uid / PER_USER_RANGE == userIdToRemove);
    }

    @NonNull
    private PackageManager getPackageManagerForUser(UserHandle user) {
        PackageManager pm = mUserPackageManagers.get(user);
        if (pm == null) {
            pm = mContext.createContextAsUser(user, 0 /* flag */).getPackageManager();
            mUserPackageManagers.put(user, pm);
        }
        return pm;
    }

    // Return cached opt-in uid list for metrics sampling.
    // Should only be called on handler thread.
    public int getCachedOptInUidsCount() {
        return mSatelliteDataOptInUids.size();
    }

    /** Dump info to dumpsys */
    public void dump(@NonNull IndentingPrintWriter pw) {
        HandlerUtils.ensureRunningOnHandlerThread(mConnectivityServiceHandler);
        pw.println("SatelliteAccessController:");
        pw.increaseIndent();
        pw.println("SupportConstrainedDataSatelliteOptIn: "
                + mSupportConstrainedDataSatelliteOptIn);
        pw.print("Role-Sms Uids: ");
        pw.print(mSatelliteRoleSmsUids);
        pw.println();
        pw.print("Opt-In Uids: ");
        pw.print(mSatelliteDataOptInUids);
        pw.println();
        pw.println("Log:");
        mLog.reverseDump(pw);
        pw.decreaseIndent();
        pw.println();
    }
}
