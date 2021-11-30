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


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A data class representing scan result from Nearby Service. Scan result can come from multiple
 * mediums like BLE, Wi-Fi Aware, and etc.
 * A scan result  consists of
 * An encapsulation of various parameters for requesting nearby scans.
 *
 * <p>All scan results generated through {@link NearbyManager} are guaranteed to have a valid
 * medium, identifier, timestamp (both UTC time and elapsed real-time since boot), and accuracy.
 * All other parameters are optional.
 *
 * @hide
 */
public final class NearbyDeviceParcelable implements Parcelable {
    public static final Creator<NearbyDeviceParcelable> CREATOR =
            new Creator<NearbyDeviceParcelable>() {
                @Override
                public NearbyDeviceParcelable createFromParcel(Parcel in) {
                    Builder builder = new Builder();
                    if (in.readInt() == 1) {
                        builder.setName(in.readString());
                    }
                    builder.setMedium(in.readInt());
                    builder.setRssi(in.readInt());
                    if (in.readInt() == 1) {
                        builder.setFastPairModelId(in.readString());
                    }
                    if (in.readInt() == 1) {
                        int dataLength = in.readInt();
                        byte[] data = new byte[dataLength];
                        in.readByteArray(data);
                        builder.setData(data);
                    }
                    return builder.build();
                }

                @Override
                public NearbyDeviceParcelable[] newArray(int size) {
                    return new NearbyDeviceParcelable[size];
                }
            };
    @Nullable
    private final String mName;
    @NearbyDevice.Medium
    private final int mMedium;
    private final int mRssi;

    @Nullable
    private final String mBluetoothAddress;
    @Nullable
    private final String mFastPairModelId;
    @Nullable
    private final byte[] mData;

    private NearbyDeviceParcelable(@Nullable String name, int medium, int rssi,
            @Nullable String fastPairModelId, @Nullable String bluetoothAddress, byte[] data) {
        mName = name;
        mMedium = medium;
        mRssi = rssi;
        mFastPairModelId = fastPairModelId;
        mBluetoothAddress = bluetoothAddress;
        mData = data;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mName == null ? 0 : 1);
        if (mName != null) {
            dest.writeString(mName);
        }
        dest.writeInt(mMedium);
        dest.writeInt(mRssi);
        dest.writeInt(mFastPairModelId == null ? 0 : 1);
        if (mFastPairModelId != null) {
            dest.writeString(mFastPairModelId);
        }
        dest.writeInt(mBluetoothAddress == null ? 0 : 1);
        if (mFastPairModelId != null) {
            dest.writeString(mBluetoothAddress);
        }
        dest.writeInt(mData == null ? 0 : 1);
        if (mData != null) {
            dest.writeInt(mData.length);
            dest.writeByteArray(mData);
        }
    }

    public String getName() {
        return mName;
    }

    public int getMedium() {
        return mMedium;
    }

    public int getRssi() {
        return mRssi;
    }

    @Nullable
    public String getFastPairModelId() {
        return mFastPairModelId;
    }

    @Nullable
    public String getBluetoothAddress() {
        return mBluetoothAddress;
    }

    public byte[] getData() {
        return mData;
    }

    /**
     * Builder class for {@link NearbyDeviceParcelable}.
     */
    public static final class Builder {
        @Nullable
        private String mName;
        @NearbyDevice.Medium
        private int mMedium;
        private int mRssi;
        @Nullable
        private String mFastPairModelId;
        @Nullable
        private String mBluetoothAddress;
        @Nullable
        private byte[] mData;

        /**
         * Sets the name of the scanned device.
         */
        public Builder setName(String name) {
            mName = name;
            return this;
        }

        /**
         * Sets the medium over which the device is discovered.
         */
        public Builder setMedium(int medium) {
            mMedium = medium;
            return this;
        }

        /**
         * Sets the RSSI between scanned device and the discovered device.
         */
        public Builder setRssi(int rssi) {
            mRssi = rssi;
            return this;
        }

        /**
         * Sets the identifier of the device.
         */
        public Builder setFastPairModelId(String fastPairModelId) {
            mFastPairModelId = fastPairModelId;
            return this;
        }

        /**
         * Sets the scanned Fast Pair data.
         */
        public Builder setBluetoothAddress(String bluetoothAddress) {
            mBluetoothAddress = bluetoothAddress;
            return this;
        }

        /**
         * Sets the raw data.
         */
        public Builder setData(byte[] data) {
            mData = data;
            return this;
        }

        /**
         * Builds a ScanResult.
         */
        public NearbyDeviceParcelable build() {
            return new NearbyDeviceParcelable(mName, mMedium, mRssi, mFastPairModelId,
                    mBluetoothAddress, mData);
        }
    }
}
