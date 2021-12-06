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

package android.nearby;

import android.annotation.IntDef;
import android.annotation.Nullable;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * A class represents a device that can be discovered by multiple mediums.
 *
 * @hide
 */
public abstract class NearbyDevice {

    @Nullable
    final String mName;

    @Medium
    final int mMedium;

    final int mRssi;

    /**
     * @hide
     */
    public NearbyDevice(@Nullable String name, @Medium int medium, int rssi) {
        Preconditions.checkState(isValidMedium(medium),
                "Not supported medium: " + medium
                        + ", scan medium must be one of NearbyDevice#Medium.");
        mName = name;
        mMedium = medium;
        mRssi = rssi;
    }

    static String mediumToString(@Medium int medium) {
        switch (medium) {
            case Medium.BLE:
                return "BLE";
            case Medium.BLUETOOTH:
                return "Bluetooth Classic";
            default:
                return "Unknown";
        }
    }

    /**
     * True if the medium is defined in {@link Medium}.
     */
    public static boolean isValidMedium(@Medium int medium) {
        return medium == Medium.BLE
                || medium == Medium.BLUETOOTH;
    }

    /**
     * The name of the device, or null if not available.
     *
     * @hide
     */
    @Nullable
    public String getName() {
        return mName;
    }

    /** The medium over which this device was discovered. */
    @Medium
    public int getMedium() {
        return mMedium;
    }

    /**
     * Returns the received signal strength in dBm. The valid range is [-127, 126].
     */
    public int getRssi() {
        return mRssi;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("NearbyDevice [");
        if (mName != null && !mName.isEmpty()) {
            stringBuilder.append("name=").append(mName).append(", ");
        }
        stringBuilder.append("medium=").append(mediumToString(mMedium));
        stringBuilder.append(" rssi=").append(mRssi);
        stringBuilder.append("]");
        return stringBuilder.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof NearbyDevice) {
            NearbyDevice otherDevice = (NearbyDevice) other;
            return Objects.equals(mName, otherDevice.mName)
                    && mMedium == otherDevice.mMedium
                    && mRssi == otherDevice.mRssi;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mMedium, mRssi);
    }

    /**
     * The medium where a NearbyDevice was discovered on.
     *
     * @hide
     */
    @IntDef({Medium.BLE, Medium.BLUETOOTH})
    public @interface Medium {
        int BLE = 1;
        int BLUETOOTH = 2;
    }

    /**
     * Builder for a NearbyDevice.
     */
    public abstract static class Builder {

        /**
         * Sets the name of Nearby Device.
         */
        public abstract Builder setName(String name);

        /**
         * Sets the medium over which the Nearby Device is discovered.
         */
        public abstract Builder setMedium(int medium);

        /**
         * Sets the RSSI between scanned device and the discovered device.
         */
        public abstract Builder setRssi(int rssi);

        /**
         * Builds the Nearby Device.
         */
        public abstract NearbyDevice build();
    }
}

