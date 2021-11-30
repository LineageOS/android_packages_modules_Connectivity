/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.Hide;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * A class represents a Fast Pair device that can be discovered by multiple mediums.
 *
 * @hide
 */
public class FastPairDevice extends NearbyDevice implements Parcelable {
    public static final Creator<FastPairDevice> CREATOR = new Creator<FastPairDevice>() {
        @Override
        public FastPairDevice createFromParcel(Parcel in) {
            FastPairDevice.Builder builder = new FastPairDevice.Builder();
            if (in.readInt() == 1) {
                builder.setName(in.readString());
            }
            builder.setMedium(in.readInt());
            builder.setRssi(in.readInt());
            if (in.readInt() == 1) {
                builder.setModelId(in.readString());
            }
            builder.setBluetoothAddress(in.readString());
            if (in.readInt() == 1) {
                int dataLength = in.readInt();
                byte[] data = new byte[dataLength];
                in.readByteArray(data);
                builder.setData(data);
            }
            return builder.build();
        }

        @Override
        public FastPairDevice[] newArray(int size) {
            return new FastPairDevice[size];
        }
    };

    // Some OEM devices devices don't have model Id.
    @Nullable private final String mModelId;

    // Bluetooth hardware address as string. Can be read from BLE ScanResult.
    private final String mBluetoothAddress;

    @Nullable
    private final byte[] mData;

    public FastPairDevice(@Nullable String name,
            @Medium int medium,
            int rssi,
            @Nullable String modelId,
            @NonNull String bluetoothAddress,
            @Nullable byte[] data) {
        super(name, medium, rssi);
        this.mModelId = modelId;
        this.mBluetoothAddress = bluetoothAddress;
        this.mData = data;
    }

    @Nullable
    @Override
    public String getName() {
        return mName;
    }

    @Override
    public int getMedium() {
        return mMedium;
    }

    @Override
    public float getRssi() {
        return mRssi;
    }

    @Nullable
    public String getModelId() {
        return this.mModelId;
    }

    @NonNull
    public String getBluetoothAddress() {
        return mBluetoothAddress;
    }

    // Only visible to system clients.
    @Nullable
    @Hide
    public byte[] getData() {
        return mData;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("FastPairDevice [");
        if (mName != null && !mName.isEmpty()) {
            stringBuilder.append("name=").append(mName).append(", ");
        }
        stringBuilder.append("medium=").append(mediumToString(mMedium));
        stringBuilder.append(" rssi=").append(mRssi);
        stringBuilder.append(" modelId=").append(mModelId);
        stringBuilder.append(" bluetoothAddress=").append(mBluetoothAddress);
        stringBuilder.append("]");
        return stringBuilder.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof FastPairDevice) {
            FastPairDevice otherDevice = (FastPairDevice) other;
            if (!super.equals(other)) {
                return false;
            }
            return Objects.equals(mModelId, otherDevice.mModelId)
                    && Objects.equals(mBluetoothAddress, otherDevice.mBluetoothAddress)
                    && Arrays.equals(mData, otherDevice.mData);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mName, mMedium, mRssi, mModelId, mBluetoothAddress, Arrays.hashCode(mData));
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mName == null ? 0 : 1);
        if (mName != null) {
            dest.writeString(mName);
        }
        dest.writeInt(mMedium);
        dest.writeInt(mRssi);
        dest.writeInt(mModelId == null ? 0 : 1);
        if (mModelId != null) {
            dest.writeString(mModelId);
        }
        dest.writeString(mBluetoothAddress);
        dest.writeInt(mData == null ? 0 : 1);
        if (mData != null) {
            dest.writeInt(mData.length);
            dest.writeByteArray(mData);
        }
    }

    /**
     * A builder class for {@link FastPairDevice}
     *
     * @hide
     */
    public static final class Builder extends NearbyDevice.Builder {

        @Nullable private String mName;
        @Medium private int mMedium;
        private int mRssi;
        @Nullable private String mModelId;
        private String mBluetoothAddress;
        @Nullable private byte[] mData;
        /**
         * Sets the name of the Fast Pair device.
         */
        @NonNull
        public Builder setName(@Nullable String name) {
            mName = name;
            return this;
        }

        /**
         * Sets the medium over which the Fast Pair device is discovered.
         */
        @NonNull
        public Builder setMedium(@Medium int medium) {
            mMedium = medium;
            return this;
        }

        /**
         * Sets the RSSI between the scan device and the discovered Fast Pair device.
         */
        @NonNull
        public Builder setRssi(int rssi) {
            mRssi = rssi;
            return this;
        }

        /**
         * Sets the model Id of this Fast Pair device.
         */
        @NonNull
        public Builder setModelId(String modelId) {
            mModelId = modelId;
            return this;
        }

        /**
         * Sets the hardware address of this BluetoothDevice.
         */
        @NonNull
        public Builder setBluetoothAddress(@NonNull String maskedBluetoothAddress) {
            mBluetoothAddress = maskedBluetoothAddress;
            return this;
        }

        /**
         * Sets the raw data. Only visible to system API.
         * @hide
         */
        @Hide
        @NonNull
        public Builder setData(byte[] data) {
            mData = data;
            return this;
        }

        /**
         * Builds a FastPairDevice and return it.
         */
        @NonNull
        public FastPairDevice build() {
            return new FastPairDevice(mName, mMedium, mRssi, mModelId,
                    mBluetoothAddress, mData);
        }
    }
}
