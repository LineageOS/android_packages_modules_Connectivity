/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.networkstack.tethering;

import android.annotation.Nullable;
import android.app.usage.NetworkStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothPan;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.connectivity.ConnectivityInternalApiUtil;
import android.net.ip.IpServer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemProperties;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.RoutingCoordinatorManager;
import com.android.net.module.util.RoutingCoordinatorService;
import com.android.net.module.util.SharedLog;
import com.android.networkstack.apishim.BluetoothPanShimImpl;
import com.android.networkstack.apishim.common.BluetoothPanShim;
import com.android.networkstack.tethering.metrics.TetheringMetrics;
import com.android.networkstack.tethering.wear.WearableConnectionManager;

import java.util.ArrayList;


/**
 * Capture tethering dependencies, for injection.
 *
 * @hide
 */
public abstract class TetheringDependencies {
    /**
     * Make the BpfCoordinator to be used by tethering.
     */
    public @NonNull BpfCoordinator makeBpfCoordinator(
            @NonNull BpfCoordinator.Dependencies deps) {
        return new BpfCoordinator(deps);
    }

    /**
     * Make the offload hardware interface to be used by tethering.
     */
    public OffloadHardwareInterface makeOffloadHardwareInterface(Handler h, SharedLog log) {
        return new OffloadHardwareInterface(h, log);
    }

    /**
     * Make the offload controller to be used by tethering.
     */
    @NonNull
    public OffloadController makeOffloadController(@NonNull Handler h,
            @NonNull SharedLog log, @NonNull OffloadController.Dependencies deps) {
        final NetworkStatsManager statsManager =
                (NetworkStatsManager) getContext().getSystemService(Context.NETWORK_STATS_SERVICE);
        return new OffloadController(h, makeOffloadHardwareInterface(h, log),
                getContext().getContentResolver(), statsManager, log, deps);
    }


    /**
     * Make the UpstreamNetworkMonitor to be used by tethering.
     */
    public UpstreamNetworkMonitor makeUpstreamNetworkMonitor(Context ctx, Handler h,
            SharedLog log, UpstreamNetworkMonitor.EventListener listener) {
        return new UpstreamNetworkMonitor(ctx, h, log, listener);
    }

    /**
     * Make the IPv6TetheringCoordinator to be used by tethering.
     */
    public IPv6TetheringCoordinator makeIPv6TetheringCoordinator(
            ArrayList<IpServer> notifyList, SharedLog log) {
        return new IPv6TetheringCoordinator(notifyList, log);
    }

    /**
     * Make dependencies to be used by IpServer.
     */
    public abstract IpServer.Dependencies makeIpServerDependencies();

    /**
     * Make the EntitlementManager to be used by tethering.
     */
    public EntitlementManager makeEntitlementManager(Context ctx, Handler h, SharedLog log,
            Runnable callback) {
        return new EntitlementManager(ctx, h, log, callback);
    }

    /**
     * Generate a new TetheringConfiguration according to input sub Id.
     */
    public TetheringConfiguration generateTetheringConfiguration(Context ctx, SharedLog log,
            int subId) {
        return new TetheringConfiguration(ctx, log, subId);
    }

    /**
     * Get a reference to INetd to be used by tethering.
     */
    public INetd getINetd(Context context, SharedLog log) {
        final INetd netd =
                INetd.Stub.asInterface((IBinder) context.getSystemService(Context.NETD_SERVICE));
        if (netd == null) {
            log.wtf("INetd is null");
        }
        return netd;
    }

    /**
     * Get the routing coordinator.
     */
    public RoutingCoordinatorManager getRoutingCoordinator(Context context, SharedLog log) {
        IBinder binder;
        if (!SdkLevel.isAtLeastS()) {
            final ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            binder = new RoutingCoordinatorService(getINetd(context, log), cm::getAllNetworks,
                    context, false /* bluetoothTetheringUseRandomAddress */);
        } else {
            binder = ConnectivityInternalApiUtil.getRoutingCoordinator(context);
        }
        return new RoutingCoordinatorManager(context, binder);
    }

    /**
     * Make the TetheringNotificationUpdater to be used by tethering.
     */
    public TetheringNotificationUpdater makeNotificationUpdater(@NonNull final Context ctx,
            @NonNull final Looper looper) {
        return new TetheringNotificationUpdater(ctx, looper);
    }

    /**
     * Make tethering thread looper.
     */
    public abstract Looper makeTetheringLooper();

    /**
     *  Get Context of TetheringService.
     */
    public abstract Context getContext();

    /**
     * Get a reference to BluetoothAdapter to be used by tethering.
     */
    @Nullable
    public abstract BluetoothAdapter getBluetoothAdapter();

    /**
     * Get SystemProperties which indicate whether tethering is denied.
     */
    public boolean isTetheringDenied() {
        return TextUtils.equals(SystemProperties.get("ro.tether.denied"), "true");
    }

    /**
     * Make BluetoothPanShim object to enable/disable bluetooth tethering.
     *
     * TODO: use BluetoothPan directly when mainline module is built with API 32.
     */
    public BluetoothPanShim makeBluetoothPanShim(BluetoothPan pan) {
        return BluetoothPanShimImpl.newInstance(pan);
    }

    /**
     * Make the TetheringMetrics to be used by tethering.
     */
    public TetheringMetrics makeTetheringMetrics(Context ctx) {
        return new TetheringMetrics(ctx);
    }

    /**
     * Returns the implementation of WearableConnectionManager.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public WearableConnectionManager makeWearableConnectionManager(Context ctx) {
        return new WearableConnectionManager(ctx);
    }

    /**
     * Wrapper to get the binder calling uid for unit testing.
     */
    public int getBinderCallingUid() {
        return Binder.getCallingUid();
    }

    /**
     * Returns true if the tethering with soft ap config feature is enabled.
     */
    public boolean isTetheringWithSoftApConfigEnabled() {
        return SdkLevel.isAtLeastB();
    }
}
