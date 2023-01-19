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

package com.android.server.nearby;

import android.provider.DeviceConfig;

/**
 * A utility class for encapsulating Nearby feature flag configurations.
 */
public class NearbyConfiguration {

    /**
     * Flag used to enable presence legacy broadcast.
     */
    public static final String NEARBY_ENABLE_PRESENCE_BROADCAST_LEGACY =
            "nearby_enable_presence_broadcast_legacy";
    /**
     * Flag used to for minimum nano app version to make Nearby CHRE scan work.
     */
    public static final String NEARBY_MAINLINE_NANO_APP_MIN_VERSION =
            "nearby_mainline_nano_app_min_version";

    /**
     * Flag used to allow test mode and customization.
     */
    public static final String NEARBY_SUPPORT_TEST_APP = "nearby_support_test_app";

    private boolean mEnablePresenceBroadcastLegacy;

    private int mNanoAppMinVersion;

    private boolean mSupportTestApp;

    public NearbyConfiguration() {
        mEnablePresenceBroadcastLegacy = getDeviceConfigBoolean(
                NEARBY_ENABLE_PRESENCE_BROADCAST_LEGACY, false /* defaultValue */);
        mNanoAppMinVersion = getDeviceConfigInt(
                NEARBY_MAINLINE_NANO_APP_MIN_VERSION, 0 /* defaultValue */);
        mSupportTestApp = getDeviceConfigBoolean(
                NEARBY_SUPPORT_TEST_APP, false /* defaultValue */);
    }

    /**
     * Returns whether broadcasting legacy presence spec is enabled.
     */
    public boolean isPresenceBroadcastLegacyEnabled() {
        return mEnablePresenceBroadcastLegacy;
    }

    public int getNanoAppMinVersion() {
        return mNanoAppMinVersion;
    }

    /**
     * @return {@code true} when in test mode and allows customization.
     */
    public boolean isTestAppSupported() {
        return mSupportTestApp;
    }

    private boolean getDeviceConfigBoolean(final String name, final boolean defaultValue) {
        final String value = getDeviceConfigProperty(name);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    private int getDeviceConfigInt(final String name, final int defaultValue) {
        final String value = getDeviceConfigProperty(name);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    private String getDeviceConfigProperty(String name) {
        return DeviceConfig.getProperty(DeviceConfig.NAMESPACE_TETHERING, name);
    }
}
