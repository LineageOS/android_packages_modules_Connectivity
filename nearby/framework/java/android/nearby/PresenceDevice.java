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

package android.nearby;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a Presence device from nearby scans.
 *
 * @hide
 */
public final class PresenceDevice extends NearbyDevice implements Parcelable {

    /** The type of presence device. */
    /** @hide **/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            DeviceType.UNKNOWN,
            DeviceType.PHONE,
            DeviceType.TABLET,
            DeviceType.DISPLAY,
            DeviceType.LAPTOP,
            DeviceType.TV,
            DeviceType.WATCH,
    })
    public @interface DeviceType {
        /** The type of the device is unknown. */
        int UNKNOWN = 0;
        /** The device is a phone. */
        int PHONE = 1;
        /** The device is a tablet. */
        int TABLET = 2;
        /** The device is a display. */
        int DISPLAY = 3;
        /** The device is a laptop. */
        int LAPTOP = 4;
        /** The device is a TV. */
        int TV = 5;
        /** The device is a watch. */
        int WATCH = 6;
    }

    private final String mDeviceId;
    private final int mDeviceType;
    private final String mDeviceImageUrl;
    private final long mDiscoveryTimestampMillis;
    private final Bundle mExtendedProperties;

    /**
     * Gets the name of the device, or {@code null} if not available.
     *
     * @hide
     */
    @Nullable
    @Override
    public String getName() {
        return mName;
    }

    /**
     * The id of the device.
     *
     * <p>This id is not a hardware id. It may rotate based on the remote device's broadcasts.
     */
    @NonNull
    public String getDeviceId() {
        return mDeviceId;
    }

    /** The type of the device. */
    @DeviceType
    public int getDeviceType() {
        return mDeviceType;
    }

    /** An image URL representing the device. */
    @Nullable
    public String getDeviceImageUrl() {
        return mDeviceImageUrl;
    }

    /** The timestamp (since boot) when the device is discovered. */
    public long getDiscoveryTimestampMillis() {
        return mDiscoveryTimestampMillis;
    }

    /**
     * The extended properties of the device.
     */
    @NonNull
    public Bundle getExtendedProperties() {
        return mExtendedProperties;
    }

    private PresenceDevice(String deviceName, int mMedium, int rssi, String deviceId,
            int deviceType,
            String deviceImageUrl, long discoveryTimestampMillis,
            Bundle extendedProperties) {
        // TODO (b/217462253): change medium to a set in NearbyDevice.
        super(deviceName, mMedium, rssi);
        mDeviceId = deviceId;
        mDeviceType = deviceType;
        mDeviceImageUrl = deviceImageUrl;
        mDiscoveryTimestampMillis = discoveryTimestampMillis;
        mExtendedProperties = extendedProperties;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mName == null ? 0 : 1);
        if (mName != null) {
            dest.writeString(mName);
        }
        dest.writeInt(mMedium);
        dest.writeInt(mRssi);
        dest.writeString(mDeviceId);
        dest.writeInt(mDeviceType);
        dest.writeInt(mDeviceImageUrl == null ? 0 : 1);
        if (mDeviceImageUrl != null) {
            dest.writeString(mDeviceImageUrl);
        }
        dest.writeLong(mDiscoveryTimestampMillis);
        dest.writeBundle(mExtendedProperties);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<PresenceDevice> CREATOR = new Creator<PresenceDevice>() {
        @Override
        public PresenceDevice createFromParcel(Parcel in) {
            Builder builder = new Builder();
            if (in.readInt() == 1) {
                builder.setName(in.readString());
            }
            builder.setMedium(in.readInt());
            builder.setRssi(in.readInt());
            builder.setDeviceId(in.readString());
            builder.setDeviceType(in.readInt());
            if (in.readInt() == 1) {
                builder.setDeviceImageUrl(in.readString());
            }
            builder.setDiscoveryTimestampMillis(in.readLong());
            Bundle bundle = in.readBundle();
            for (String key : bundle.keySet()) {
                builder.addExtendedProperty(key, bundle.getCharSequence(key).toString());
            }
            return builder.build();
        }

        @Override
        public PresenceDevice[] newArray(int size) {
            return new PresenceDevice[size];
        }
    };

    /**
     * Builder class for {@link PresenceDevice}.
     *
     * @hide
     */
    public static final class Builder {

        private final Bundle mExtendedProperties;

        private String mName;
        private int mRssi;
        private int mMedium;
        private String mDeviceId;
        private int mDeviceType;
        private String mDeviceImageUrl;
        private long mDiscoveryTimestampMillis;

        public Builder() {
            mExtendedProperties = new Bundle();
            mRssi = -100;
        }

        /**
         * Sets the name of the Presence device.
         *
         * @param name Name of the Presence. Can be {@code null} if there is no name.
         */
        @NonNull
        public Builder setName(@android.annotation.Nullable String name) {
            mName = name;
            return this;
        }

        /**
         * Sets the medium over which the Presence device is discovered.
         *
         * @param medium The {@link Medium} over which the device is discovered.
         */
        @NonNull
        public Builder setMedium(@Medium int medium) {
            mMedium = medium;
            return this;
        }

        /**
         * Sets the RSSI on the discovered Presence device.
         *
         * @param rssi The received signal strength in dBm.
         */
        @NonNull
        public Builder setRssi(int rssi) {
            mRssi = rssi;
            return this;
        }

        /**
         * Sets the identifier on the discovered Presence device.
         *
         * @param deviceId Identifier of the Presence device.
         */
        @NonNull
        public Builder setDeviceId(@NonNull String deviceId) {
            mDeviceId = deviceId;
            return this;
        }


        /**
         * Sets the type of discovered Presence device.
         *
         * @param deviceType Type of the Presence device.
         */
        @NonNull
        public Builder setDeviceType(int deviceType) {
            mDeviceType = deviceType;
            return this;
        }


        /**
         * Sets the image url of the discovered Presence device.
         *
         * @param deviceImageUrl Url of the image for the Presence device.
         */
        @NonNull
        public Builder setDeviceImageUrl(@NonNull String deviceImageUrl) {
            mDeviceImageUrl = deviceImageUrl;
            return this;
        }


        /**
         * Sets discovery timestamp, the clock is based on elapsed time.
         *
         * @param discoveryTimestampMillis Timestamp when the presence device is discovered.
         */
        @NonNull
        public Builder setDiscoveryTimestampMillis(long discoveryTimestampMillis) {
            mDiscoveryTimestampMillis = discoveryTimestampMillis;
            return this;
        }


        /**
         * Adds an extended property of the discovered presence device.
         *
         * @param key   Key of the extended property.
         * @param value Value of the extended property,
         */
        @NonNull
        public Builder addExtendedProperty(@NonNull String key, @NonNull String value) {
            mExtendedProperties.putCharSequence(key, value);
            return this;
        }

        /**
         * Builds a Presence device.
         */
        @NonNull
        public PresenceDevice build() {
            return new PresenceDevice(mName, mMedium, mRssi, mDeviceId, mDeviceType,
                    mDeviceImageUrl,
                    mDiscoveryTimestampMillis, mExtendedProperties);
        }
    }
}
