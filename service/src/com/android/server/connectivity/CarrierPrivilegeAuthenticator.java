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

package com.android.server.connectivity;

import static android.net.NetworkCapabilities.NET_CAPABILITY_CBS;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.networkstack.apishim.TelephonyManagerShimImpl;
import com.android.networkstack.apishim.common.TelephonyManagerShim;
import com.android.networkstack.apishim.common.TelephonyManagerShim.CarrierPrivilegesListenerShim;
import com.android.networkstack.apishim.common.UnsupportedApiLevelException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Tracks the uid of the carrier privileged app that provides the carrier config.
 * Authenticates if the caller has same uid as
 * carrier privileged app that provides the carrier config
 * @hide
 */
public class CarrierPrivilegeAuthenticator extends BroadcastReceiver {
    private static final String TAG = CarrierPrivilegeAuthenticator.class.getSimpleName();
    private static final boolean DBG = true;

    // The context is for the current user (system server)
    private final Context mContext;
    private final TelephonyManagerShim mTelephonyManagerShim;
    private final TelephonyManager mTelephonyManager;
    @GuardedBy("mLock")
    private int[] mCarrierServiceUid;
    @GuardedBy("mLock")
    private int mModemCount = 0;
    private final Object mLock = new Object();
    private final HandlerThread mThread;
    private final Handler mHandler;
    @NonNull
    private final List<CarrierPrivilegesListenerShim> mCarrierPrivilegesChangedListeners =
            new ArrayList<>();

    public CarrierPrivilegeAuthenticator(@NonNull final Context c,
            @NonNull final TelephonyManager t,
            @NonNull final TelephonyManagerShimImpl telephonyManagerShim) {
        mContext = c;
        mTelephonyManager = t;
        mTelephonyManagerShim = telephonyManagerShim;
        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new Handler(mThread.getLooper()) {};
        synchronized (mLock) {
            mModemCount = mTelephonyManager.getActiveModemCount();
            registerForCarrierChanges();
            updateCarrierServiceUid();
        }
    }

    public CarrierPrivilegeAuthenticator(@NonNull final Context c,
            @NonNull final TelephonyManager t) {
        mContext = c;
        mTelephonyManager = t;
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S) {
            mTelephonyManagerShim = new TelephonyManagerShimImpl(mTelephonyManager);
        } else {
            mTelephonyManagerShim = null;
        }
        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new Handler(mThread.getLooper()) {};
        synchronized (mLock) {
            mModemCount = mTelephonyManager.getActiveModemCount();
            registerForCarrierChanges();
            updateCarrierServiceUid();
        }
    }

    /**
     * An adapter {@link Executor} that posts all executed tasks onto the given
     * {@link Handler}.
     *
     * TODO : migrate to the version in frameworks/libs/net when it's ready
     *
     * @hide
     */
    public class HandlerExecutor implements Executor {
        private final Handler mHandler;
        public HandlerExecutor(@NonNull Handler handler) {
            mHandler = handler;
        }
        @Override
        public void execute(Runnable command) {
            if (!mHandler.post(command)) {
                throw new RejectedExecutionException(mHandler + " is shutting down");
            }
        }
    }

    /**
     * Broadcast receiver for ACTION_MULTI_SIM_CONFIG_CHANGED
     *
     * <p>The broadcast receiver is registered with mHandler
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        switch (intent.getAction()) {
            case TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED:
                handleActionMultiSimConfigChanged(context, intent);
                break;
            default:
                Log.d(TAG, "Unknown intent received with action: " + intent.getAction());
        }
    }

    private void handleActionMultiSimConfigChanged(Context context, Intent intent) {
        unregisterCarrierPrivilegesListeners();
        synchronized (mLock) {
            mModemCount = mTelephonyManager.getActiveModemCount();
        }
        registerCarrierPrivilegesListeners();
        updateCarrierServiceUid();
    }

    private void registerForCarrierChanges() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED);
        mContext.registerReceiver(this, filter, null, mHandler);
        registerCarrierPrivilegesListeners();
    }

    private void registerCarrierPrivilegesListeners() {
        final HandlerExecutor executor = new HandlerExecutor(mHandler);
        int modemCount;
        synchronized (mLock) {
            modemCount = mModemCount;
        }
        try {
            for (int i = 0; i < modemCount; i++) {
                CarrierPrivilegesListenerShim carrierPrivilegesListener =
                        new CarrierPrivilegesListenerShim() {
                            @Override
                            public void onCarrierPrivilegesChanged(
                                    @NonNull List<String> privilegedPackageNames,
                                    @NonNull int[] privilegedUids) {
                                // Re-trigger the synchronous check (which is also very cheap due
                                // to caching in CarrierPrivilegesTracker). This allows consistency
                                // with the onSubscriptionsChangedListener and broadcasts.
                                updateCarrierServiceUid();
                            }
                        };
                addCarrierPrivilegesListener(i, executor, carrierPrivilegesListener);
                mCarrierPrivilegesChangedListeners.add(carrierPrivilegesListener);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Encountered exception registering carrier privileges listeners", e);
        }
    }

    private void addCarrierPrivilegesListener(int logicalSlotIndex, Executor executor,
            CarrierPrivilegesListenerShim listener) {
        if (mTelephonyManagerShim  == null) {
            return;
        }
        try {
            mTelephonyManagerShim.addCarrierPrivilegesListener(
                    logicalSlotIndex, executor, listener);
        } catch (UnsupportedApiLevelException unsupportedApiLevelException) {
            Log.e(TAG, "addCarrierPrivilegesListener API is not available");
        }
    }

    private void removeCarrierPrivilegesListener(CarrierPrivilegesListenerShim listener) {
        if (mTelephonyManagerShim  == null) {
            return;
        }
        try {
            mTelephonyManagerShim.removeCarrierPrivilegesListener(listener);
        } catch (UnsupportedApiLevelException unsupportedApiLevelException) {
            Log.e(TAG, "removeCarrierPrivilegesListener API is not available");
        }
    }

    private String getCarrierServicePackageNameForLogicalSlot(int logicalSlotIndex) {
        if (mTelephonyManagerShim  == null) {
            return null;
        }
        try {
            return mTelephonyManagerShim.getCarrierServicePackageNameForLogicalSlot(
                    logicalSlotIndex);
        } catch (UnsupportedApiLevelException unsupportedApiLevelException) {
            Log.e(TAG, "getCarrierServicePackageNameForLogicalSlot API is not available");
        }
        return null;
    }

    private void unregisterCarrierPrivilegesListeners() {
        for (CarrierPrivilegesListenerShim carrierPrivilegesListener :
                mCarrierPrivilegesChangedListeners) {
            removeCarrierPrivilegesListener(carrierPrivilegesListener);
        }
        mCarrierPrivilegesChangedListeners.clear();
    }

    /**
     * Check if network request is allowed based upon carrrier service package.
     *
     * Network request for {@link NET_CAPABILITY_CBS} is allowed if the caller has
     * carrier privilege and provides the carrier config. This function checks if caller
     * has the same and returns true if it has else false.
     *
     * @param callingUid user identifier that uniquely identifies the caller.
     * @param networkRequest the network request for which the carrier privilege is checked.
     * @return true if caller has carrier privilege and provides the carrier config else false.
     */
    public boolean hasCarrierPrivilegeForNetworkRequest(int callingUid,
            NetworkRequest networkRequest) {
        if (callingUid != Process.INVALID_UID) {
            final int subId = getSubIdFromNetworkSpecifier(
                    networkRequest.getNetworkSpecifier());
            return callingUid == getCarrierServiceUidForSubId(subId);
        }
        return false;
    }

    @VisibleForTesting
    void updateCarrierServiceUid() {
        synchronized (mLock) {
            mCarrierServiceUid = new int[mModemCount];
            for (int i = 0; i < mModemCount; i++) {
                mCarrierServiceUid[i] = getCarrierServicePackageUidForSlot(i);
            }
        }
    }

    @VisibleForTesting
    int getCarrierServiceUidForSubId(int subId) {
        final int slotId = getSlotIndex(subId);
        synchronized (mLock) {
            if (slotId != SubscriptionManager.INVALID_SIM_SLOT_INDEX && slotId < mModemCount) {
                return mCarrierServiceUid[slotId];
            }
        }
        return Process.INVALID_UID;
    }

    @VisibleForTesting
    protected int getSlotIndex(int subId) {
        return SubscriptionManager.getSlotIndex(subId);
    }

    @VisibleForTesting
    int getSubIdFromNetworkSpecifier(NetworkSpecifier specifier) {
        if (specifier instanceof TelephonyNetworkSpecifier) {
            return ((TelephonyNetworkSpecifier) specifier).getSubscriptionId();
        }
        return SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
    }

    @VisibleForTesting
    int getUidForPackage(String pkgName) {
        if (pkgName == null) {
            return Process.INVALID_UID;
        }
        try {
            PackageManager pm = mContext.getPackageManager();
            if (pm != null) {
                ApplicationInfo applicationInfo = pm.getApplicationInfo(pkgName, 0);
                if (applicationInfo != null) {
                    return applicationInfo.uid;
                }
            }
        } catch (PackageManager.NameNotFoundException exception) {
            // Didn't find package. Try other users
            Log.i(TAG, "Unable to find uid for package " + pkgName);
        }
        return Process.INVALID_UID;
    }

    @VisibleForTesting
    int getCarrierServicePackageUidForSlot(int slotId) {
        return getUidForPackage(getCarrierServicePackageNameForLogicalSlot(slotId));
    }
}
