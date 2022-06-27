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

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a data element in Nearby Presence.
 *
 * @hide
 */
@SystemApi
public final class DataElement implements Parcelable {

    private final int mKey;
    private final byte[] mValue;

    /** @hide */
    @IntDef({
            DataType.BLE_SERVICE_DATA,
            DataType.ACCOUNT_KEY,
            DataType.BLE_ADDRESS,
            DataType.SALT,
            DataType.PRIVATE_IDENTITY,
            DataType.TRUSTED_IDENTITY,
            DataType.PUBLIC_IDENTITY,
            DataType.PROVISIONED_IDENTITY,
            DataType.TX_POWER,
            DataType.INTENT,
            DataType.MODEL_ID,
            DataType.FINDER_EPHEMERAL_IDENTIFIER,
            DataType.CONNECTION_STATUS,
            DataType.BATTERY
    })
    public @interface DataType {
        int BLE_SERVICE_DATA = 0;
        int ACCOUNT_KEY = 1;
        int BLE_ADDRESS = 2;
        int SALT = 3;
        int PRIVATE_IDENTITY = 4;
        int TRUSTED_IDENTITY = 5;
        int PUBLIC_IDENTITY = 6;
        int PROVISIONED_IDENTITY = 7;
        int TX_POWER = 8;
        int INTENT = 9;
        int MODEL_ID = 10;
        int FINDER_EPHEMERAL_IDENTIFIER = 11;
        int CONNECTION_STATUS = 12;
        int BATTERY = 13;
    }

    /**
     * @hide
     */
    public static boolean isValidType(int type) {
        return type == DataType.BLE_SERVICE_DATA
                || type == DataType.ACCOUNT_KEY
                || type == DataType.BLE_ADDRESS
                || type == DataType.SALT
                || type == DataType.PRIVATE_IDENTITY
                || type == DataType.TRUSTED_IDENTITY
                || type == DataType.PUBLIC_IDENTITY
                || type == DataType.PROVISIONED_IDENTITY
                || type == DataType.TX_POWER
                || type == DataType.INTENT
                || type == DataType.MODEL_ID
                || type == DataType.FINDER_EPHEMERAL_IDENTIFIER
                || type == DataType.CONNECTION_STATUS
                || type == DataType.BATTERY;
    }

    /**
     * Constructs a {@link DataElement}.
     */
    public DataElement(int key, @NonNull byte[] value) {
        Preconditions.checkArgument(value != null, "value cannot be null");
        Preconditions.checkArgument(isValidType(key), "key should one of DataElement.DataType");
        mKey = key;
        mValue = value;
    }

    @NonNull
    public static final Creator<DataElement> CREATOR = new Creator<DataElement>() {
        @Override
        public DataElement createFromParcel(Parcel in) {
            int key = in.readInt();
            byte[] value = new byte[in.readInt()];
            in.readByteArray(value);
            return new DataElement(key, value);
        }

        @Override
        public DataElement[] newArray(int size) {
            return new DataElement[size];
        }
    };

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof DataElement) {
            return mKey == ((DataElement) obj).mKey
                    && Arrays.equals(mValue, ((DataElement) obj).mValue);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mKey, Arrays.hashCode(mValue));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mKey);
        dest.writeInt(mValue.length);
        dest.writeByteArray(mValue);
    }

    /**
     * Returns the key of the data element, as defined in the nearby presence specification.
     */
    public int getKey() {
        return mKey;
    }

    /**
     * Returns the value of the data element.
     */
    @NonNull
    public byte[] getValue() {
        return mValue;
    }
}
