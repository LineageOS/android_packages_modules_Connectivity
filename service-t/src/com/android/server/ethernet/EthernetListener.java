/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.ethernet;

import static android.net.EthernetManager.LISTENER_FLAG_INCLUDE_NCM;

import android.net.IEthernetServiceListener;
import android.net.IpConfiguration;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.ethernet.EthernetTracker.TrackingReason;

import java.util.EnumSet;

/** Wraps IEthernetServiceListener and implements IInterface for use in RemoteCallbackList */
public class EthernetListener implements IInterface {
    private static final String TAG = "EthernetListener";

    private final IEthernetServiceListener mListener;
    private final boolean mHasUseRestrictedNetworksPermission;
    private final int mFlags;

    public EthernetListener(IEthernetServiceListener listener, boolean canUseRestrictedNetworks,
            int flags) {
        mListener = listener;
        mHasUseRestrictedNetworksPermission = canUseRestrictedNetworks;
        mFlags = flags;
    }

    /** Indicates whether the remote uid has USE_RESTRICTED_NETWORKS permission */
    public boolean hasUseRestrictedNetworksPermission() {
        return mHasUseRestrictedNetworksPermission;
    }

    /** Send onEthernetStateChanged callback */
    public void onEthernetStateChanged(int state) {
        try {
            mListener.onEthernetStateChanged(state);
        } catch (RemoteException e) {
            // Most likely because the other end is dead. Do nothing.
        }
    }

    private boolean isIncludingNcmCallback() {
        return (mFlags & LISTENER_FLAG_INCLUDE_NCM) != 0;
    }

    private boolean shouldNotifyInterfaceStateListener(EnumSet<TrackingReason> trackingReason) {
        if (trackingReason.isEmpty()) {
            Log.wtf(TAG, "TrackingReason is empty in onInterfaceStateChanged. This is a bug.");
            return false;
        }

        if (trackingReason.contains(TrackingReason.REGEX)) return true;
        if (trackingReason.contains(TrackingReason.NCM) && isIncludingNcmCallback()) return true;
        return false;
    }

    /** Send onInterfaceStateChanged callback based on tracking reason. */
    public void onInterfaceStateChanged(String iface, EnumSet<TrackingReason> trackingReason,
            int state, int role, IpConfiguration configuration) {
        if (!shouldNotifyInterfaceStateListener(trackingReason)) return;

        try {
            mListener.onInterfaceStateChanged(iface, state, role, configuration);
        } catch (RemoteException e) {
            // Most likely because the other end is dead. Do nothing.
        }
    }

    @Override
    public IBinder asBinder() {
        return mListener.asBinder();
    }
}
