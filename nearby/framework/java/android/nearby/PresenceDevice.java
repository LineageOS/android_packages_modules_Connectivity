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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a Presence device from nearby scans.
 *
 * @hide
 */
@SystemApi
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
    private final byte[] mSalt;
    private final byte[] mSecretId;
    private final byte[] mEncryptedIdentity;
    private final int mDeviceType;
    private final String mDeviceImageUrl;
    private final long mDiscoveryTimestampMillis;
    private final List<DataElement> mExtendedProperties;

    /**
     * The id of the device.
     *
     * <p>This id is not a hardware id. It may rotate based on the remote device's broadcasts.
     */
    @NonNull
    public String getDeviceId() {
        return mDeviceId;
    }

    /**
     * Returns the salt used when presence device is discovered.
     */
    @NonNull
    public byte[] getSalt() {
        return mSalt;
    }

    /**
     * Returns the secret used when presence device is discovered.
     */
    @NonNull
    public byte[] getSecretId() {
        return mSecretId;
    }

    /**
     * Returns the encrypted identity used when presence device is discovered.
     */
    @NonNull
    public byte[] getEncryptedIdentity() {
        return mEncryptedIdentity;
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
    public List<DataElement> getExtendedProperties() {
        return mExtendedProperties;
    }

    private PresenceDevice(String deviceName, List<Integer> mMediums, int rssi, String deviceId,
            byte[] salt, byte[] secretId, byte[] encryptedIdentity, int deviceType,
            String deviceImageUrl, long discoveryTimestampMillis,
            List<DataElement> extendedProperties) {
        super(deviceName, mMediums, rssi);
        mDeviceId = deviceId;
        mSalt = salt;
        mSecretId = secretId;
        mEncryptedIdentity = encryptedIdentity;
        mDeviceType = deviceType;
        mDeviceImageUrl = deviceImageUrl;
        mDiscoveryTimestampMillis = discoveryTimestampMillis;
        mExtendedProperties = extendedProperties;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        String name = getName();
        dest.writeInt(name == null ? 0 : 1);
        if (name != null) {
            dest.writeString(name);
        }
        List<Integer> mediums = getMediums();
        dest.writeInt(mediums.size());
        for (int medium : mediums) {
            dest.writeInt(medium);
        }
        dest.writeInt(getRssi());
        dest.writeString(mDeviceId);
        dest.writeInt(mDeviceType);
        dest.writeInt(mDeviceImageUrl == null ? 0 : 1);
        if (mDeviceImageUrl != null) {
            dest.writeString(mDeviceImageUrl);
        }
        dest.writeLong(mDiscoveryTimestampMillis);
        dest.writeInt(mExtendedProperties.size());
        for (DataElement dataElement : mExtendedProperties) {
            dest.writeParcelable(dataElement, 0);
        }
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
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                builder.addMedium(in.readInt());
            }
            builder.setRssi(in.readInt());
            builder.setDeviceId(in.readString());
            builder.setDeviceType(in.readInt());
            if (in.readInt() == 1) {
                builder.setDeviceImageUrl(in.readString());
            }
            builder.setDiscoveryTimestampMillis(in.readLong());
            int dataElementSize = in.readInt();
            for (int i = 0; i < dataElementSize; i++) {
                builder.addExtendedProperty(
                        in.readParcelable(DataElement.class.getClassLoader(), DataElement.class));
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
     */
    public static final class Builder {

        private final List<DataElement> mExtendedProperties;
        private final List<Integer> mMediums;

        private String mName;
        private int mRssi;
        private String mDeviceId;
        private byte[] mSalt;
        private byte[] mSecretId;
        private byte[] mEncryptedIdentity;
        private int mDeviceType;
        private String mDeviceImageUrl;
        private long mDiscoveryTimestampMillis;

        public Builder() {
            mMediums = new ArrayList<>();
            mExtendedProperties = new ArrayList<>();
            mRssi = -100;
        }

        /**
         * Sets the name of the Presence device.
         *
         * @param name Name of the Presence. Can be {@code null} if there is no name.
         */
        @NonNull
        public Builder setName(@Nullable String name) {
            mName = name;
            return this;
        }

        /**
         * Adds the medium over which the Presence device is discovered.
         *
         * @param medium The {@link Medium} over which the device is discovered.
         */
        @NonNull
        public Builder addMedium(@Medium int medium) {
            mMediums.add(medium);
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
            Objects.requireNonNull(deviceId);
            mDeviceId = deviceId;
            return this;
        }

        /**
         * Sets the identifier on the discovered Presence device.
         */
        @NonNull
        public Builder setSalt(@NonNull byte[] salt) {
            Objects.requireNonNull(salt);
            mSalt = salt;
            return this;
        }

        /**
         * Sets the secret id of the discovered Presence device.
         */
        @NonNull
        public Builder setSecretId(@NonNull byte[] secretId) {
            Objects.requireNonNull(secretId);
            mSecretId = secretId;
            return this;
        }

        /**
         * Sets the encrypted identity of the discovered Presence device.
         */
        @NonNull
        public Builder setEncryptedIdentity(@NonNull byte[] encryptedIdentity) {
            Objects.requireNonNull(encryptedIdentity);
            mEncryptedIdentity = encryptedIdentity;
            return this;
        }

        /**
         * Sets the type of discovered Presence device.
         *
         * @param deviceType Type of the Presence device.
         */
        @NonNull
        public Builder setDeviceType(@DeviceType int deviceType) {
            mDeviceType = deviceType;
            return this;
        }


        /**
         * Sets the image url of the discovered Presence device.
         *
         * @param deviceImageUrl Url of the image for the Presence device.
         */
        @NonNull
        public Builder setDeviceImageUrl(@Nullable String deviceImageUrl) {
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
         * @param dataElement Data element of the extended property.
         */
        @NonNull
        public Builder addExtendedProperty(@NonNull DataElement dataElement) {
            Objects.requireNonNull(dataElement);
            mExtendedProperties.add(dataElement);
            return this;
        }

        /**
         * Builds a Presence device.
         */
        @NonNull
        public PresenceDevice build() {
            return new PresenceDevice(mName, mMediums, mRssi, mDeviceId,
                    mSalt, mSecretId, mEncryptedIdentity,
                    mDeviceType,
                    mDeviceImageUrl,
                    mDiscoveryTimestampMillis, mExtendedProperties);
        }
    }
}
