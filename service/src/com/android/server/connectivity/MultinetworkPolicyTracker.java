/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.content.pm.PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION;
import static android.net.ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI;
import static android.net.ConnectivitySettingsManager.NETWORK_METERED_MULTIPATH_PREFERENCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.net.platform.flags.Flags;
import android.os.Build;
import android.os.Handler;
import android.os.PersistableBundle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.connectivity.resources.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.HandlerUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A class to encapsulate management of the "Smart Networking" capability of
 * avoiding bad Wi-Fi when, for example upstream connectivity is lost or
 * certain critical link failures occur.
 *
 * This enables the device to switch to another form of connectivity, like
 * mobile, if it's available and working.
 *
 * The Runnable |avoidBadWifiCallback|, if given, is posted to the supplied
 * Handler' whenever the computed "avoid bad wifi" value changes.
 *
 * Disabling this reverts the device to a level of networking sophistication
 * circa 2012-13 by disabling disparate code paths each of which contribute to
 * maintaining continuous, working Internet connectivity.
 *
 * @hide
 */
public class MultinetworkPolicyTracker {
    private class CarrierConfigChangeListener
            implements CarrierConfigManager.CarrierConfigChangeListener {
        /**
         * Must be called on the background thread.
         */
        @Override
        public void onCarrierConfigChanged(
                    int slotIndex, int subId, int carrierId, int specificCarrierId) {
            updateAvoidBadWifiFromCarrierConfigBackground(subId);
        }
    }

    /**
     * Indicates that the "Avoid Bad Wi-Fi" setting originates from a resource.
     */
    private static final int FROM_RESOURCE = 0;

    /**
     * Indicates that the "Avoid Bad Wi-Fi" setting originates from carrier configuration.
     */
    private static final int FROM_CARRIER_CONFIG = 1;

    /**
     * Defines the set of possible integer constants for AvoidBadWifiSource.
     * This annotation provides compile-time type safety.
     */
    @IntDef({FROM_RESOURCE, FROM_CARRIER_CONFIG})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AvoidBadWifiSource {}

    private static String TAG = MultinetworkPolicyTracker.class.getSimpleName();

    // See Dependencies#getConfigActivelyPreferBadWifi
    public static final String CONFIG_ACTIVELY_PREFER_BAD_WIFI = "actively_prefer_bad_wifi";

    private final Context mContext;
    private final ConnectivityResources mResources;
    private final Handler mHandler;
    private final Runnable mAvoidBadWifiCallback;
    private final List<Uri> mSettingsUris;
    private final ContentResolver mResolver;
    private final SettingObserver mSettingObserver;
    private final BroadcastReceiver mBroadcastReceiver;
    private final @AvoidBadWifiSource int mAvoidBadWifiSource;
    // This will be null if the FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG is off
    private final @Nullable CarrierConfigManager mCarrierConfigManager;
    private final @Nullable CarrierConfigChangeListener mCarrierConfigChangeListener;

    // This will be accessed by background and handler thread
    @GuardedBy("mAvoidBadWifiCarrierConfigForSubIdMap")
    private final ArrayMap<Integer, Boolean> mAvoidBadWifiCarrierConfigForSubIdMap =
            new ArrayMap<>();
    private volatile boolean mAvoidBadWifi = true;
    private volatile int mMeteredMultipathPreference;
    private int mActiveSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private volatile long mTestAllowBadWifiUntilMs = 0;

    /**
     * Dependencies for testing
     */
    @VisibleForTesting
    public static class Dependencies {
        private boolean isCarrierConfigValid(PersistableBundle config) {
            return config == null || config.isEmpty()
                || !CarrierConfigManager.isConfigForIdentifiedCarrier(config);
        }

        /**
         * @see DeviceConfigUtils#getDeviceConfigPropertyInt
         */
        protected int getConfigActivelyPreferBadWifi() {
            // CONFIG_ACTIVELY_PREFER_BAD_WIFI is not a feature to be rolled out, but an override
            // for tests and an emergency kill switch (which could force the behavior on OR off).
            // As such it uses a -1/null/1 scheme, but features should use
            // DeviceConfigUtils#isFeatureEnabled instead, to make sure rollbacks disable the
            // feature before it's ready on R and before.
            return DeviceConfig.getInt(DeviceConfig.NAMESPACE_CONNECTIVITY,
                    CONFIG_ACTIVELY_PREFER_BAD_WIFI, 0);
        }

        /**
         @see DeviceConfig#addOnPropertiesChangedListener
         */
        protected void addOnDevicePropertiesChangedListener(@NonNull final Executor executor,
                @NonNull final DeviceConfig.OnPropertiesChangedListener listener) {
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_CONNECTIVITY,
                    executor, listener);
        }

        @VisibleForTesting
        @NonNull
        protected Resources getResourcesForActiveSubId(
                @NonNull final ConnectivityResources resources, final int activeSubId) {
            return SubscriptionManager.getResourcesForSubId(
                    resources.getResourcesContext(), activeSubId);
        }

        @VisibleForTesting
        protected boolean readAvoidBadWifiFromCarrierConfig(
                @NonNull final CarrierConfigManager ccm, final int subId) {
            // Defaults to true to avoid potentially poor Wi-Fi and improve user experience.
            final boolean defaultConfig = true;
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return defaultConfig;
            }

            PersistableBundle config = null;

            try {
                config = ccm.getConfigForSubId(subId,
                        CarrierConfigManager.KEY_AVOID_BAD_WIFI_BOOL
                );
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to get config from carrier config", e);
            }

            if (isCarrierConfigValid(config)) {
                // If the "avoid bad Wi-Fi" setting is not defined in the carrier-specific config,
                // retrieve the default configuration.
                config = CarrierConfigManager.getDefaultConfig();
            }

            return config.getBoolean(CarrierConfigManager.KEY_AVOID_BAD_WIFI_BOOL, defaultConfig);
        }

        protected boolean getAvoidBadWifiFromCarrierConfigFeature() {
            return Flags.avoidBadWifiFromCarrierConfig();
        }

        protected Handler getBackgroundThreadHandler() {
            return BackgroundThread.getHandler();
        }

    }
    private final Dependencies mDeps;

    /**
     * Whether to prefer bad wifi to a network that yields to bad wifis, even if it never validated
     *
     * This setting only makes sense if the system is configured not to avoid bad wifis, i.e.
     * if mAvoidBadWifi is true. If it's not, then no network ever yields to bad wifis
     * ({@see FullScore#POLICY_YIELD_TO_BAD_WIFI}) and this setting has therefore no effect.
     *
     * If this is false, when ranking a bad wifi that never validated against cell data (or any
     * network that yields to bad wifis), the ranker will prefer cell data. It will prefer wifi
     * if wifi loses validation later. This behavior avoids the device losing internet access when
     * walking past a wifi network with no internet access.
     * This is the default behavior up to Android T, but it can be overridden through an overlay
     * to behave like below.
     *
     * If this is true, then in the same scenario, the ranker will prefer cell data until
     * the wifi completes its first validation attempt (or the attempt times out after
     * ConnectivityService#PROMPT_UNVALIDATED_DELAY_MS), then it will prefer the wifi even if it
     * doesn't provide internet access, unless there is a captive portal on that wifi.
     * This is the behavior in U and above.
     */
    private boolean mActivelyPreferBadWifi;

    // Mainline module can't use internal HandlerExecutor, so add an identical executor here.
    private static class HandlerExecutor implements Executor {
        @NonNull
        private final Handler mHandler;

        HandlerExecutor(@NonNull Handler handler) {
            mHandler = handler;
        }
        @Override
        public void execute(Runnable command) {
            if (!mHandler.post(command)) {
                throw new RejectedExecutionException(mHandler + " is shutting down");
            }
        }
    }
    // TODO: Set the mini sdk to 31 and remove @TargetApi annotation when b/205923322 is addressed.
    @VisibleForTesting @TargetApi(Build.VERSION_CODES.S)
    protected class ActiveDataSubscriptionIdListener extends TelephonyCallback
            implements TelephonyCallback.ActiveDataSubscriptionIdListener {
        @Override
        public void onActiveDataSubscriptionIdChanged(final int subId) {
            mActiveSubId = subId;
            reevaluateInternal();
        }
    }

    public MultinetworkPolicyTracker(Context ctx, Handler handler, Runnable avoidBadWifiCallback) {
        this(ctx, handler, avoidBadWifiCallback, new Dependencies());
    }

    public MultinetworkPolicyTracker(Context ctx, Handler handler, Runnable avoidBadWifiCallback,
            Dependencies deps) {
        mContext = ctx;
        mResources = new ConnectivityResources(ctx);
        mHandler = handler;
        mAvoidBadWifiCallback = avoidBadWifiCallback;
        mDeps = deps;
        mSettingsUris = Arrays.asList(
                Settings.Global.getUriFor(NETWORK_AVOID_BAD_WIFI),
                Settings.Global.getUriFor(NETWORK_METERED_MULTIPATH_PREFERENCE));
        mResolver = mContext.getContentResolver();
        mSettingObserver = new SettingObserver();
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reevaluateInternal();
            }
        };

        if (mDeps.getAvoidBadWifiFromCarrierConfigFeature()) {
            mAvoidBadWifiSource = FROM_CARRIER_CONFIG;
            mCarrierConfigManager =
                mContext.getPackageManager().hasSystemFeature(FEATURE_TELEPHONY_SUBSCRIPTION)
                ? mContext.getSystemService(CarrierConfigManager.class)
                : null;
            mCarrierConfigChangeListener = new CarrierConfigChangeListener();
        } else {
            mAvoidBadWifiSource = FROM_RESOURCE;
            mCarrierConfigManager = null;
            mCarrierConfigChangeListener = null;
            updateAvoidBadWifi();
            updateMeteredMultipathPreference();
        }
    }

    // TODO: Set the mini sdk to 31 and remove @TargetApi annotation when b/205923322 is addressed.
    @TargetApi(Build.VERSION_CODES.S)
    public void start() {
        for (Uri uri : mSettingsUris) {
            mResolver.registerContentObserver(uri, false, mSettingObserver);
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        mContext.registerReceiverForAllUsers(mBroadcastReceiver, intentFilter,
                null /* broadcastPermission */, mHandler);

        final Executor handlerExecutor = new HandlerExecutor(mHandler);
        mContext.getSystemService(TelephonyManager.class).registerTelephonyCallback(
                handlerExecutor, new ActiveDataSubscriptionIdListener());

        if (mCarrierConfigManager != null) {
            mCarrierConfigManager.registerCarrierConfigChangeListener(
                    BackgroundThread.getExecutor(), mCarrierConfigChangeListener
            );

            // This ensures the latest carrier configuration is read.
            final int subId = mActiveSubId;
            mDeps.getBackgroundThreadHandler().post(() ->
                    updateAvoidBadWifiFromCarrierConfigBackground(subId));
        } else {
            mDeps.addOnDevicePropertiesChangedListener(handlerExecutor,
                properties -> reevaluateInternal());
        }

        reevaluate();
    }

    public void shutdown() {
        mResolver.unregisterContentObserver(mSettingObserver);

        mContext.unregisterReceiver(mBroadcastReceiver);
        if (mCarrierConfigManager != null) {
            mCarrierConfigManager.unregisterCarrierConfigChangeListener(
                    mCarrierConfigChangeListener
            );
        }
    }

    public boolean getAvoidBadWifi() {
        return mAvoidBadWifi;
    }

    public boolean getActivelyPreferBadWifi() {
        return mActivelyPreferBadWifi;
    }

    // TODO: move this to MultipathPolicyTracker.
    public int getMeteredMultipathPreference() {
        return mMeteredMultipathPreference;
    }

    /**
     * Whether the device or carrier configuration disables avoiding bad wifi by default.
     */
    public boolean configRestrictsAvoidBadWifi() {
        final boolean allowBadWifi = mTestAllowBadWifiUntilMs > 0
                && mTestAllowBadWifiUntilMs > System.currentTimeMillis();
        // If the config returns true, then avoid bad wifi design can be controlled by the
        // NETWORK_AVOID_BAD_WIFI setting.
        if (allowBadWifi) return true;

        return mDeps.getResourcesForActiveSubId(mResources, mActiveSubId)
                .getInteger(R.integer.config_networkAvoidBadWifi) == 0;
    }

    /**
     * Whether the device config prefers bad wifi actively, when it doesn't avoid them
     *
     * This is only relevant when the device is configured not to avoid bad wifis. In this
     * case, "actively" preferring a bad wifi means that the device will switch to a bad
     * wifi it just connected to, as long as it's not a captive portal.
     *
     * On U and above this always returns true. On T and below it reads a configuration option.
     */
    public boolean configActivelyPrefersBadWifi() {
        // See the definition of config_activelyPreferBadWifi for a description of its meaning.
        // On U and above, the config is ignored, and bad wifi is always actively preferred.
        if (SdkLevel.isAtLeastU()) return true;

        // On T and below, 1 means to actively prefer bad wifi, 0 means not to prefer
        // bad wifi (only stay stuck on it if already on there). This implementation treats
        // any non-0 value like 1, on the assumption that anybody setting it non-zero wants
        // the newer behavior.
        return 0 != mDeps.getResourcesForActiveSubId(mResources, mActiveSubId)
                .getInteger(R.integer.config_activelyPreferBadWifi);
    }

    /**
     * Temporarily allow bad wifi to override {@code config_networkAvoidBadWifi} configuration.
     * The value works when the time set is more than {@link System.currentTimeMillis()}.
     */
    public void setTestAllowBadWifiUntil(long timeMs) {
        Log.d(TAG, "setTestAllowBadWifiUntil: " + timeMs);
        mTestAllowBadWifiUntilMs = timeMs;
        reevaluateInternal();
    }

    /**
     * Whether we should display a notification when wifi becomes unvalidated.
     */
    public boolean shouldNotifyWifiUnvalidated() {
        return configRestrictsAvoidBadWifi() && readAvoidBadWifiFromSettings() == null;
    }

    /**
     * Retrieves the "avoid bad Wi-Fi" setting from the global settings.
     */
    public String readAvoidBadWifiFromSettings() {
        return Settings.Global.getString(mResolver, NETWORK_AVOID_BAD_WIFI);
    }

    /**
     * Returns whether device config says the device should actively prefer bad wifi.
     *
     * {@see #configActivelyPrefersBadWifi} for a description of what this does. This device
     * config overrides that config overlay.
     *
     * @return True on Android U and above.
     *         True if device config says to actively prefer bad wifi.
     *         False if device config says not to actively prefer bad wifi.
     *         null if device config doesn't have an opinion (then fall back on the resource).
     */
    public Boolean deviceConfigActivelyPreferBadWifi() {
        if (SdkLevel.isAtLeastU()) return true;
        switch (mDeps.getConfigActivelyPreferBadWifi()) {
            case 1:
                return Boolean.TRUE;
            case -1:
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    @VisibleForTesting
    public void reevaluate() {
        mHandler.post(this::reevaluateInternal);
    }

    /**
     * Reevaluate the settings. Must be called on the handler thread.
     */
    private void reevaluateInternal() {
        if (updateAvoidBadWifi() && mAvoidBadWifiCallback != null) {
            mAvoidBadWifiCallback.run();
        }
        updateMeteredMultipathPreference();
    }

    /**
     * Reads the avoid bad Wi-Fi setting from the cache for a specific subscription ID.
     * If no value is found for the given subId, it defaults to true.
     */
    private boolean readAvoidBadWifiFromCache(int subId) {
        synchronized (mAvoidBadWifiCarrierConfigForSubIdMap) {
            return mAvoidBadWifiCarrierConfigForSubIdMap.getOrDefault(subId, true);
        }
    }

    /**
     * Updates the cached avoid bad Wi-Fi setting for a specific subscription ID.
     */
    private void updateAvoidBadWifiCache(int subId, boolean enable) {
        synchronized (mAvoidBadWifiCarrierConfigForSubIdMap) {
            mAvoidBadWifiCarrierConfigForSubIdMap.put(subId, enable);
        }
    }

    /**
     * Updates the local cache of the "avoid bad Wi-Fi" setting from the carrier config
     * for a specific subscription ID.
     * Must be called on the handler thread.
     */
    private void updateAvoidBadWifiFromCarrierConfig(int subId, boolean enable) {
        updateAvoidBadWifiCache(subId, enable);

        if (subId == mActiveSubId) {
            reevaluateInternal();
        }
    }

    /**
     * Retrieves the "avoid bad Wi-Fi" setting from the carrier configuration for the given subId,
     * and updates the local cache asynchronously on the handler thread.
     * Must be called on the background thread, because it makes an IPC to the phone process.
     * It must not be called on the CS handler thread.
     */
    private void updateAvoidBadWifiFromCarrierConfigBackground(int subId) {
        HandlerUtils.ensureRunningOnHandlerThread(mDeps.getBackgroundThreadHandler());

        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return;
        }

        // CarrierConfigManager#getConfigForSubId() is supported
        // only when system has FEATURE_TELEPHONY_SUBSCRIPTION
        final boolean config =
                mDeps.readAvoidBadWifiFromCarrierConfig(mCarrierConfigManager, subId);
        mHandler.post(() -> updateAvoidBadWifiFromCarrierConfig(subId, config));
    }

    /**
     * Updates the "avoid bad Wi-Fi" setting.
     * Depending on whether the carrier config feature is enabled, this method uses different logic:
     *
     * If carrier config feature is ON (Android U+ and 25Q4+):
     * - Forces "actively prefer bad Wi-Fi" to true.
     * - Checks a system setting first. If it is enabled, we should avoid bad Wi-Fi.
     * - If the system setting isn't available, it uses a cached value from the carrier config
     *   for the current subId.
     *
     * If carrier config feature is OFF:
     * - "Avoid bad Wi-Fi" is true if the system setting is enabled OR
     *    if the carrier doesn't restrict avoiding bad Wi-Fi.
     * - "Actively prefer bad Wi-Fi" is based on a device-specific setting,
     *    falling back to the carrier config if the device setting isn't available.
     *
     * Returns true if either the "avoid bad Wi-Fi" or "actively prefer bad Wi-Fi" setting changed,
     * false otherwise.
     */
    public boolean updateAvoidBadWifi() {
        final boolean prevAvoid = mAvoidBadWifi;
        switch (mAvoidBadWifiSource) {
            case FROM_CARRIER_CONFIG:
                // Force update activelyPreferBadWifi since it will always be true in Android U+,
                // and mAvoidBadWifiFromCarrierConfigFeature is a trunk stable flag
                // that only exists in 25Q4+
                mActivelyPreferBadWifi = true;
                final String settingAvoidBadWifiStr = readAvoidBadWifiFromSettings();
                if (settingAvoidBadWifiStr != null) {
                    mAvoidBadWifi = "1".equals(settingAvoidBadWifiStr);
                } else {
                    // Retrieve the avoid bad Wi-Fi setting from the local cache to avoid potential
                    // issues or blocking from the IPC call getAvoidBadWifiCarrierConfigForSubId().
                    mAvoidBadWifi = readAvoidBadWifiFromCache(mActiveSubId);
                }
                return mAvoidBadWifi != prevAvoid;
            case FROM_RESOURCE:
                final boolean settingAvoidBadWifi = "1".equals(readAvoidBadWifiFromSettings());
                mAvoidBadWifi = settingAvoidBadWifi || !configRestrictsAvoidBadWifi();
                final boolean prevActive = mActivelyPreferBadWifi;
                final Boolean deviceConfigPreferBadWifi = deviceConfigActivelyPreferBadWifi();
                if (null == deviceConfigPreferBadWifi) {
                    mActivelyPreferBadWifi = configActivelyPrefersBadWifi();
                } else {
                    mActivelyPreferBadWifi = deviceConfigPreferBadWifi;
                }
                return mAvoidBadWifi != prevAvoid || mActivelyPreferBadWifi != prevActive;
            default:
                Log.wtf(TAG, "Unexpected avoid bad Wi-Fi source: " + mAvoidBadWifiSource);
                return false;
        }
    }

    /**
     * The default (device and carrier-dependent) value for metered multipath preference.
     */
    public int configMeteredMultipathPreference() {
        return mDeps.getResourcesForActiveSubId(mResources, mActiveSubId)
                .getInteger(R.integer.config_networkMeteredMultipathPreference);
    }

    public void updateMeteredMultipathPreference() {
        String setting = Settings.Global.getString(mResolver, NETWORK_METERED_MULTIPATH_PREFERENCE);
        try {
            mMeteredMultipathPreference = Integer.parseInt(setting);
        } catch (NumberFormatException e) {
            mMeteredMultipathPreference = configMeteredMultipathPreference();
        }
    }

    private class SettingObserver extends ContentObserver {
        public SettingObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            Log.wtf(TAG, "Should never be reached.");
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!mSettingsUris.contains(uri)) {
                Log.wtf(TAG, "Unexpected settings observation: " + uri);
            }
            reevaluate();
        }
    }
}
