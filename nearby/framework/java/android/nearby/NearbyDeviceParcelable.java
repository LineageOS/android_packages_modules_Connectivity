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


import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.bluetooth.le.ScanRecord;
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
@SystemApi
public final class NearbyDeviceParcelable implements Parcelable {

    /**
     * Used to read a NearbyDeviceParcelable from a Parcel.
     */
    @NonNull
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
            @Nullable String fastPairModelId, @Nullable String bluetoothAddress,
            @Nullable byte[] data) {
        mName = name;
        mMedium = medium;
        mRssi = rssi;
        mFastPairModelId = fastPairModelId;
        mBluetoothAddress = bluetoothAddress;
        mData = data;
    }

    /**
     * No special parcel contents.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this NearbyDeviceParcelable in to a Parcel.
     *
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     */

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

    /**
     * Gets the name of the NearbyDeviceParcelable. Returns {@code null} If there is no name.
     */
    @Nullable
    public String getName() {
        return mName;
    }

    /**
     * Gets the {@link android.nearby.NearbyDevice.Medium} of the NearbyDeviceParcelable over which
     * it is discovered.
     */
    @NearbyDevice.Medium
    public int getMedium() {
        return mMedium;
    }

    /**
     * Gets the received signal strength in dBm.
     */
    @IntRange(from = -127, to = 126)
    public int getRssi() {
        return mRssi;
    }

    /**
     * Gets the Fast Pair identifier. Returns {@code null} if there is no Model ID or this is not a
     * Fast Pair device.
     */
    @Nullable
    public String getFastPairModelId() {
        return mFastPairModelId;
    }

    /**
     * Gets the Bluetooth device hardware address. Returns {@code null} if the device is not
     * discovered by Bluetooth.
     */
    @Nullable
    public String getBluetoothAddress() {
        return mBluetoothAddress;
    }

    /**
     * Gets the raw data from the scanning. Returns {@code null} if there is no extra data.
     */
    @Nullable
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
         *
         * @param name The local name of the scanned device.
         */
        @NonNull
        public Builder setName(@Nullable String name) {
            mName = name;
            return this;
        }

        /**
         * Sets the medium over which the device is discovered.
         *
         * @param medium The {@link NearbyDevice.Medium} over which the device is discovered.
         */
        @NonNull
        public Builder setMedium(@NearbyDevice.Medium int medium) {
            mMedium = medium;
            return this;
        }

        /**
         * Sets the RSSI between scanned device and the discovered device.
         *
         * @param rssi The received signal strength in dBm.
         */
        @NonNull
        public Builder setRssi(int rssi) {
            mRssi = rssi;
            return this;
        }

        /**
         * Sets the Fast Pair model Id.
         *
         * @param fastPairModelId Fast Pair device identifier.
         */
        @NonNull
        public Builder setFastPairModelId(@Nullable String fastPairModelId) {
            mFastPairModelId = fastPairModelId;
            return this;
        }

        /**
         * Sets the bluetooth address.
         *
         * @param bluetoothAddress The hardware address of the bluetooth device.
         */
        @NonNull
        public Builder setBluetoothAddress(@Nullable String bluetoothAddress) {
            mBluetoothAddress = bluetoothAddress;
            return this;
        }

        /**
         * Sets the scanned raw data.
         *
         * @param data Data the scan.
         * For example, {@link ScanRecord#getServiceData()} if scanned by Bluetooth.
         */
        @NonNull
        public Builder setData(@Nullable byte[] data) {
            mData = data;
            return this;
        }

        /**
         * Builds a ScanResult.
         */
        @NonNull
        public NearbyDeviceParcelable build() {
            return new NearbyDeviceParcelable(mName, mMedium, mRssi, mFastPairModelId,
                    mBluetoothAddress, mData);
        }
    }
}
