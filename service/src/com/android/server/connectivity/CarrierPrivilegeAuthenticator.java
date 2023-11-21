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

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static com.android.server.connectivity.ConnectivityFlags.CARRIER_SERVICE_CHANGED_USE_CALLBACK;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.NetworkCapabilities;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.HandlerExecutor;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.networkstack.apishim.TelephonyManagerShimImpl;
import com.android.networkstack.apishim.common.TelephonyManagerShim;
import com.android.networkstack.apishim.common.TelephonyManagerShim.CarrierPrivilegesListenerShim;
import com.android.networkstack.apishim.common.UnsupportedApiLevelException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Tracks the uid of the carrier privileged app that provides the carrier config.
 * Authenticates if the caller has same uid as
 * carrier privileged app that provides the carrier config
 * @hide
 */
public class CarrierPrivilegeAuthenticator {
    private static final String TAG = CarrierPrivilegeAuthenticator.class.getSimpleName();
    private static final boolean DBG = true;

    // The context is for the current user (system server)
    private final Context mContext;
    private final TelephonyManagerShim mTelephonyManagerShim;
    private final TelephonyManager mTelephonyManager;
    @GuardedBy("mLock")
    private final SparseIntArray mCarrierServiceUid = new SparseIntArray(2 /* initialCapacity */);
    @GuardedBy("mLock")
    private int mModemCount = 0;
    private final Object mLock = new Object();
    private final Handler mHandler;
    @NonNull
    private final List<PrivilegeListener> mCarrierPrivilegesChangedListeners = new ArrayList<>();
    private final boolean mUseCallbacksForServiceChanged;

    public CarrierPrivilegeAuthenticator(@NonNull final Context c,
            @NonNull final Dependencies deps,
            @NonNull final TelephonyManager t,
            @NonNull final TelephonyManagerShim telephonyManagerShim) {
        mContext = c;
        mTelephonyManager = t;
        mTelephonyManagerShim = telephonyManagerShim;
        final HandlerThread thread = deps.makeHandlerThread();
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mUseCallbacksForServiceChanged = deps.isFeatureEnabled(
                c, CARRIER_SERVICE_CHANGED_USE_CALLBACK);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED);
        synchronized (mLock) {
            // Never unregistered because the system server never stops
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    switch (intent.getAction()) {
                        case TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED:
                            simConfigChanged();
                            break;
                        default:
                            Log.d(TAG, "Unknown intent received, action: " + intent.getAction());
                    }
                }
            }, filter, null, mHandler);
            simConfigChanged();
        }
    }

    public CarrierPrivilegeAuthenticator(@NonNull final Context c,
            @NonNull final TelephonyManager t) {
        this(c, new Dependencies(), t, TelephonyManagerShimImpl.newInstance(t));
    }

    public static class Dependencies {
        /**
         * Create a HandlerThread to use in CarrierPrivilegeAuthenticator.
         */
        public HandlerThread makeHandlerThread() {
            return new HandlerThread(TAG);
        }

        /**
         * @see DeviceConfigUtils#isTetheringFeatureEnabled
         */
        public boolean isFeatureEnabled(Context context, String name) {
            return DeviceConfigUtils.isTetheringFeatureEnabled(context, name);
        }
    }

    private void simConfigChanged() {
        synchronized (mLock) {
            unregisterCarrierPrivilegesListeners();
            mModemCount = mTelephonyManager.getActiveModemCount();
            registerCarrierPrivilegesListeners(mModemCount);
            if (!mUseCallbacksForServiceChanged) updateCarrierServiceUid();
        }
    }

    private class PrivilegeListener implements CarrierPrivilegesListenerShim {
        public final int mLogicalSlot;

        PrivilegeListener(final int logicalSlot) {
            mLogicalSlot = logicalSlot;
        }

        @Override
        public void onCarrierPrivilegesChanged(
                @NonNull List<String> privilegedPackageNames,
                @NonNull int[] privilegedUids) {
            if (mUseCallbacksForServiceChanged) return;
            // Re-trigger the synchronous check (which is also very cheap due
            // to caching in CarrierPrivilegesTracker). This allows consistency
            // with the onSubscriptionsChangedListener and broadcasts.
            updateCarrierServiceUid();
        }

        @Override
        public void onCarrierServiceChanged(@Nullable final String carrierServicePackageName,
                final int carrierServiceUid) {
            if (!mUseCallbacksForServiceChanged) {
                // Re-trigger the synchronous check (which is also very cheap due
                // to caching in CarrierPrivilegesTracker). This allows consistency
                // with the onSubscriptionsChangedListener and broadcasts.
                updateCarrierServiceUid();
                return;
            }
            synchronized (mLock) {
                mCarrierServiceUid.put(mLogicalSlot, carrierServiceUid);
            }
        }
    }

    private void registerCarrierPrivilegesListeners(final int modemCount) {
        final HandlerExecutor executor = new HandlerExecutor(mHandler);
        try {
            for (int i = 0; i < modemCount; i++) {
                PrivilegeListener carrierPrivilegesListener = new PrivilegeListener(i);
                addCarrierPrivilegesListener(executor, carrierPrivilegesListener);
                mCarrierPrivilegesChangedListeners.add(carrierPrivilegesListener);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Encountered exception registering carrier privileges listeners", e);
        }
    }

    @GuardedBy("mLock")
    private void unregisterCarrierPrivilegesListeners() {
        for (PrivilegeListener carrierPrivilegesListener : mCarrierPrivilegesChangedListeners) {
            removeCarrierPrivilegesListener(carrierPrivilegesListener);
            mCarrierServiceUid.delete(carrierPrivilegesListener.mLogicalSlot);
        }
        mCarrierPrivilegesChangedListeners.clear();
    }

    private String getCarrierServicePackageNameForLogicalSlot(int logicalSlotIndex) {
        try {
            return mTelephonyManagerShim.getCarrierServicePackageNameForLogicalSlot(
                    logicalSlotIndex);
        } catch (UnsupportedApiLevelException unsupportedApiLevelException) {
            // Should not happen since CarrierPrivilegeAuthenticator is only used on T+
            Log.e(TAG, "getCarrierServicePackageNameForLogicalSlot API is not available");
        }
        return null;
    }

    /**
     * Check if a UID is the carrier service app of the subscription ID in the provided capabilities
     *
     * This returns whether the passed UID is the carrier service package for the subscription ID
     * stored in the telephony network specifier in the passed network capabilities.
     * If the capabilities don't code for a cellular or Wi-Fi network, or if they don't have the
     * subscription ID in their specifier, this returns false.
     *
     * This method can be used to check that a network request that requires the UID to be
     * the carrier service UID is indeed called by such a UID. An example of such a network could
     * be a network with the  {@link android.net.NetworkCapabilities#NET_CAPABILITY_CBS}
     * capability.
     * It can also be used to check that a factory is entitled to grant access to a given network
     * to a given UID on grounds that it is the carrier service package.
     *
     * @param callingUid uid of the app claimed to be the carrier service package.
     * @param networkCapabilities the network capabilities for which carrier privilege is checked.
     * @return true if uid provides the relevant carrier config else false.
     */
    public boolean isCarrierServiceUidForNetworkCapabilities(int callingUid,
            @NonNull NetworkCapabilities networkCapabilities) {
        if (callingUid == Process.INVALID_UID) return false;
        final int subId;
        if (networkCapabilities.hasSingleTransportBesidesTest(TRANSPORT_CELLULAR)) {
            subId = getSubIdFromTelephonySpecifier(networkCapabilities.getNetworkSpecifier());
        } else if (networkCapabilities.hasSingleTransportBesidesTest(TRANSPORT_WIFI)) {
            subId = getSubIdFromWifiTransportInfo(networkCapabilities.getTransportInfo());
        } else {
            subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                && !networkCapabilities.getSubscriptionIds().contains(subId)) {
            // Ideally, the code above should just use networkCapabilities.getSubscriptionIds()
            // for simplicity and future-proofing. However, this is not the historical behavior,
            // and there is no enforcement that they do not differ, so log a terrible failure if
            // they do not match to gain confidence this never happens.
            // TODO : when there is confidence that this never happens, rewrite the code above
            // with NetworkCapabilities#getSubscriptionIds.
            Log.wtf(TAG, "NetworkCapabilities subIds are inconsistent between "
                    + "specifier/transportInfo and mSubIds : " + networkCapabilities);
        }
        if (SubscriptionManager.INVALID_SUBSCRIPTION_ID == subId) return false;
        return callingUid == getCarrierServiceUidForSubId(subId);
    }

    @VisibleForTesting
    void updateCarrierServiceUid() {
        synchronized (mLock) {
            mCarrierServiceUid.clear();
            for (int i = 0; i < mModemCount; i++) {
                mCarrierServiceUid.put(i, getCarrierServicePackageUidForSlot(i));
            }
        }
    }

    @VisibleForTesting
    int getCarrierServiceUidForSubId(int subId) {
        final int slotId = getSlotIndex(subId);
        synchronized (mLock) {
            return mCarrierServiceUid.get(slotId, Process.INVALID_UID);
        }
    }

    @VisibleForTesting
    protected int getSlotIndex(int subId) {
        return SubscriptionManager.getSlotIndex(subId);
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

    @VisibleForTesting
    int getSubIdFromTelephonySpecifier(@Nullable final NetworkSpecifier specifier) {
        if (specifier instanceof TelephonyNetworkSpecifier) {
            return ((TelephonyNetworkSpecifier) specifier).getSubscriptionId();
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    int getSubIdFromWifiTransportInfo(@Nullable final TransportInfo info) {
        if (info instanceof WifiInfo) {
            return ((WifiInfo) info).getSubscriptionId();
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    // Helper methods to avoid having to deal with UnsupportedApiLevelException.
    private void addCarrierPrivilegesListener(@NonNull final Executor executor,
            @NonNull final PrivilegeListener listener) {
        try {
            mTelephonyManagerShim.addCarrierPrivilegesListener(listener.mLogicalSlot, executor,
                    listener);
        } catch (UnsupportedApiLevelException unsupportedApiLevelException) {
            // Should not happen since CarrierPrivilegeAuthenticator is only used on T+
            Log.e(TAG, "addCarrierPrivilegesListener API is not available");
        }
    }

    private void removeCarrierPrivilegesListener(PrivilegeListener listener) {
        try {
            mTelephonyManagerShim.removeCarrierPrivilegesListener(listener);
        } catch (UnsupportedApiLevelException unsupportedApiLevelException) {
            // Should not happen since CarrierPrivilegeAuthenticator is only used on T+
            Log.e(TAG, "removeCarrierPrivilegesListener API is not available");
        }
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("CarrierPrivilegeAuthenticator:");
        synchronized (mLock) {
            final int size = mCarrierServiceUid.size();
            for (int i = 0; i < size; ++i) {
                final int logicalSlot = mCarrierServiceUid.keyAt(i);
                final int serviceUid = mCarrierServiceUid.valueAt(i);
                pw.println("Logical slot = " + logicalSlot + " : uid = " + serviceUid);
            }
        }
    }
}
