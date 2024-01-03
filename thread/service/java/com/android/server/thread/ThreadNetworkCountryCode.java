/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.thread;

import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.TargetApi;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.thread.IOperationReceiver;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.ActiveCountryCodeChangedCallback;
import android.os.Build;
import android.util.Log;

import com.android.connectivity.resources.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.connectivity.ConnectivityResources;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Provide functions for making changes to Thread Network country code. This Country Code is from
 * location or WiFi configuration. This class sends Country Code to Thread Network native layer.
 *
 * <p>This class is thread-safe.
 */
@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class ThreadNetworkCountryCode {
    private static final String TAG = "ThreadNetworkCountryCode";
    // To be used when there is no country code available.
    @VisibleForTesting public static final String DEFAULT_COUNTRY_CODE = "WW";

    // Wait 1 hour between updates.
    private static final long TIME_BETWEEN_LOCATION_UPDATES_MS = 1000L * 60 * 60 * 1;
    // Minimum distance before an update is triggered, in meters. We don't need this to be too
    // exact because all we care about is what country the user is in.
    private static final float DISTANCE_BETWEEN_LOCALTION_UPDATES_METERS = 5_000.0f;

    /** List of country code sources. */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(
            prefix = "COUNTRY_CODE_SOURCE_",
            value = {
                COUNTRY_CODE_SOURCE_DEFAULT,
                COUNTRY_CODE_SOURCE_LOCATION,
                COUNTRY_CODE_SOURCE_OVERRIDE,
                COUNTRY_CODE_SOURCE_WIFI,
            })
    private @interface CountryCodeSource {}

    private static final String COUNTRY_CODE_SOURCE_DEFAULT = "Default";
    private static final String COUNTRY_CODE_SOURCE_LOCATION = "Location";
    private static final String COUNTRY_CODE_SOURCE_OVERRIDE = "Override";
    private static final String COUNTRY_CODE_SOURCE_WIFI = "Wifi";
    private static final CountryCodeInfo DEFAULT_COUNTRY_CODE_INFO =
            new CountryCodeInfo(DEFAULT_COUNTRY_CODE, COUNTRY_CODE_SOURCE_DEFAULT);

    private final ConnectivityResources mResources;
    private final LocationManager mLocationManager;
    @Nullable private final Geocoder mGeocoder;
    private final ThreadNetworkControllerService mThreadNetworkControllerService;
    private final WifiManager mWifiManager;

    @Nullable private CountryCodeInfo mCurrentCountryCodeInfo;
    @Nullable private CountryCodeInfo mLocationCountryCodeInfo;
    @Nullable private CountryCodeInfo mOverrideCountryCodeInfo;
    @Nullable private CountryCodeInfo mWifiCountryCodeInfo;

    /** Container class to store Thread country code information. */
    private static final class CountryCodeInfo {
        private String mCountryCode;
        @CountryCodeSource private String mSource;
        private final Instant mUpdatedTimestamp;

        public CountryCodeInfo(
                String countryCode, @CountryCodeSource String countryCodeSource, Instant instant) {
            mCountryCode = countryCode;
            mSource = countryCodeSource;
            mUpdatedTimestamp = instant;
        }

        public CountryCodeInfo(String countryCode, @CountryCodeSource String countryCodeSource) {
            this(countryCode, countryCodeSource, Instant.now());
        }

        public String getCountryCode() {
            return mCountryCode;
        }

        public boolean isCountryCodeMatch(CountryCodeInfo countryCodeInfo) {
            if (countryCodeInfo == null) {
                return false;
            }

            return Objects.equals(countryCodeInfo.mCountryCode, mCountryCode);
        }

        @Override
        public String toString() {
            return "CountryCodeInfo{ mCountryCode: "
                    + mCountryCode
                    + ", mSource: "
                    + mSource
                    + ", mUpdatedTimestamp: "
                    + mUpdatedTimestamp
                    + "}";
        }
    }

    private boolean isLocationUseForCountryCodeEnabled() {
        return mResources
                .get()
                .getBoolean(R.bool.config_thread_location_use_for_country_code_enabled);
    }

    public ThreadNetworkCountryCode(
            LocationManager locationManager,
            ThreadNetworkControllerService threadNetworkControllerService,
            @Nullable Geocoder geocoder,
            ConnectivityResources resources,
            WifiManager wifiManager) {
        mLocationManager = locationManager;
        mThreadNetworkControllerService = threadNetworkControllerService;
        mGeocoder = geocoder;
        mResources = resources;
        mWifiManager = wifiManager;
    }

    /** Sets up this country code module to listen to location country code changes. */
    public synchronized void initialize() {
        registerGeocoderCountryCodeCallback();
        registerWifiCountryCodeCallback();
        updateCountryCode(false /* forceUpdate */);
    }

    private synchronized void registerGeocoderCountryCodeCallback() {
        if ((mGeocoder != null) && isLocationUseForCountryCodeEnabled()) {
            mLocationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    TIME_BETWEEN_LOCATION_UPDATES_MS,
                    DISTANCE_BETWEEN_LOCALTION_UPDATES_METERS,
                    location -> setCountryCodeFromGeocodingLocation(location));
        }
    }

    private synchronized void geocodeListener(List<Address> addresses) {
        if (addresses != null && !addresses.isEmpty()) {
            String countryCode = addresses.get(0).getCountryCode();

            if (isValidCountryCode(countryCode)) {
                Log.d(TAG, "Set location country code to: " + countryCode);
                mLocationCountryCodeInfo =
                        new CountryCodeInfo(countryCode, COUNTRY_CODE_SOURCE_LOCATION);
            } else {
                Log.d(TAG, "Received invalid location country code");
                mLocationCountryCodeInfo = null;
            }

            updateCountryCode(false /* forceUpdate */);
        }
    }

    private synchronized void setCountryCodeFromGeocodingLocation(@Nullable Location location) {
        if ((location == null) || (mGeocoder == null)) return;

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            Log.wtf(
                    TAG,
                    "Unexpected call to set country code from the Geocoding location, "
                            + "Thread code never runs under T or lower.");
            return;
        }

        mGeocoder.getFromLocation(
                location.getLatitude(),
                location.getLongitude(),
                1 /* maxResults */,
                this::geocodeListener);
    }

    private synchronized void registerWifiCountryCodeCallback() {
        if (mWifiManager != null) {
            mWifiManager.registerActiveCountryCodeChangedCallback(
                    r -> r.run(), new WifiCountryCodeCallback());
        }
    }

    private class WifiCountryCodeCallback implements ActiveCountryCodeChangedCallback {
        @Override
        public void onActiveCountryCodeChanged(String countryCode) {
            Log.d(TAG, "Wifi country code is changed to " + countryCode);
            synchronized ("ThreadNetworkCountryCode.this") {
                mWifiCountryCodeInfo = new CountryCodeInfo(countryCode, COUNTRY_CODE_SOURCE_WIFI);
                updateCountryCode(false /* forceUpdate */);
            }
        }

        @Override
        public void onCountryCodeInactive() {
            Log.d(TAG, "Wifi country code is inactived");
            synchronized ("ThreadNetworkCountryCode.this") {
                mWifiCountryCodeInfo = null;
                updateCountryCode(false /* forceUpdate */);
            }
        }
    }

    /**
     * Priority order of country code sources (we stop at the first known country code source):
     *
     * <ul>
     *   <li>1. Override country code - Country code forced via shell command (local/automated
     *       testing)
     *   <li>2. Wifi country code - Current country code retrieved via wifi (via 80211.ad).
     *   <li>3. Location Country code - Country code retrieved from LocationManager passive location
     *       provider.
     * </ul>
     *
     * @return the selected country code information.
     */
    private CountryCodeInfo pickCountryCode() {
        if (mOverrideCountryCodeInfo != null) {
            return mOverrideCountryCodeInfo;
        }

        if (mWifiCountryCodeInfo != null) {
            return mWifiCountryCodeInfo;
        }

        if (mLocationCountryCodeInfo != null) {
            return mLocationCountryCodeInfo;
        }

        return DEFAULT_COUNTRY_CODE_INFO;
    }

    private IOperationReceiver newOperationReceiver(CountryCodeInfo countryCodeInfo) {
        return new IOperationReceiver.Stub() {
            @Override
            public void onSuccess() {
                synchronized ("ThreadNetworkCountryCode.this") {
                    mCurrentCountryCodeInfo = countryCodeInfo;
                }
            }

            @Override
            public void onError(int otError, String message) {
                Log.e(
                        TAG,
                        "Error "
                                + otError
                                + ": "
                                + message
                                + ". Failed to set country code "
                                + countryCodeInfo);
            }
        };
    }

    /**
     * Updates country code to the Thread native layer.
     *
     * @param forceUpdate Force update the country code even if it was the same as previously cached
     *     value.
     */
    @VisibleForTesting
    public synchronized void updateCountryCode(boolean forceUpdate) {
        CountryCodeInfo countryCodeInfo = pickCountryCode();

        if (!forceUpdate && countryCodeInfo.isCountryCodeMatch(mCurrentCountryCodeInfo)) {
            Log.i(TAG, "Ignoring already set country code " + countryCodeInfo.getCountryCode());
            return;
        }

        Log.i(TAG, "Set country code: " + countryCodeInfo);
        mThreadNetworkControllerService.setCountryCode(
                countryCodeInfo.getCountryCode().toUpperCase(Locale.ROOT),
                newOperationReceiver(countryCodeInfo));
    }

    /** Returns the current country code or {@code null} if no country code is set. */
    @Nullable
    public synchronized String getCountryCode() {
        return (mCurrentCountryCodeInfo != null) ? mCurrentCountryCodeInfo.getCountryCode() : null;
    }

    /**
     * Returns {@code true} if {@code countryCode} is a valid country code.
     *
     * <p>A country code is valid if it consists of 2 alphabets.
     */
    public static boolean isValidCountryCode(String countryCode) {
        return countryCode != null
                && countryCode.length() == 2
                && countryCode.chars().allMatch(Character::isLetter);
    }

    /**
     * Overrides any existing country code.
     *
     * @param countryCode A 2-Character alphabetical country code (as defined in ISO 3166).
     * @throws IllegalArgumentException if {@code countryCode} is an invalid country code.
     */
    public synchronized void setOverrideCountryCode(String countryCode) {
        if (!isValidCountryCode(countryCode)) {
            throw new IllegalArgumentException("The override country code is invalid");
        }

        mOverrideCountryCodeInfo = new CountryCodeInfo(countryCode, COUNTRY_CODE_SOURCE_OVERRIDE);
        updateCountryCode(true /* forceUpdate */);
    }

    /** Clears the country code previously set through {@link #setOverrideCountryCode} method. */
    public synchronized void clearOverrideCountryCode() {
        mOverrideCountryCodeInfo = null;
        updateCountryCode(true /* forceUpdate */);
    }

    /** Dumps the current state of this ThreadNetworkCountryCode object. */
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("---- Dump of ThreadNetworkCountryCode begin ----");
        pw.println("mOverrideCountryCodeInfo: " + mOverrideCountryCodeInfo);
        pw.println("mWifiCountryCodeInfo: " + mWifiCountryCodeInfo);
        pw.println("mLocationCountryCodeInfo: " + mLocationCountryCodeInfo);
        pw.println("mCurrentCountryCodeInfo: " + mCurrentCountryCodeInfo);
        pw.println("---- Dump of ThreadNetworkCountryCode end ------");
    }
}
