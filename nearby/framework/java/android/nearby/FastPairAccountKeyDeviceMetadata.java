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
import android.annotation.SystemApi;
import android.nearby.aidl.FastPairAccountKeyDeviceMetadataParcel;

/**
 * Class for metadata of a Fast Pair device associated with an account.
 *
 * @hide
 */
@SystemApi
public class FastPairAccountKeyDeviceMetadata {

    FastPairAccountKeyDeviceMetadataParcel mMetadataParcel;

    FastPairAccountKeyDeviceMetadata(FastPairAccountKeyDeviceMetadataParcel metadataParcel) {
        this.mMetadataParcel = metadataParcel;
    }

    /**
     * Get Device Account Key, which uniquely identifies a Fast Pair device associated with an
     * account.
     */
    @Nullable
    public byte[] getDeviceAccountKey() {
        return mMetadataParcel.deviceAccountKey;
    }

    /**
     * Get a hash value of device's account key and public bluetooth address without revealing the
     * public bluetooth address.
     */
    @Nullable
    public byte[] getSha256DeviceAccountKeyPublicAddress() {
        return mMetadataParcel.sha256DeviceAccountKeyPublicAddress;
    }

    /**
     * Get metadata of a Fast Pair device type.
     */
    @Nullable
    public FastPairDeviceMetadata getFastPairDeviceMetadata() {
        if (mMetadataParcel.metadata == null) {
            return null;
        }
        return new FastPairDeviceMetadata(mMetadataParcel.metadata);
    }

    /**
     * Get Fast Pair discovery item, which is tied to both the device type and the account.
     */
    @Nullable
    public FastPairDiscoveryItem getFastPairDiscoveryItem() {
        if (mMetadataParcel.discoveryItem == null) {
            return null;
        }
        return new FastPairDiscoveryItem(mMetadataParcel.discoveryItem);
    }

    /**
     * Builder used to create FastPairAccountKeyDeviceMetadata.
     */
    public static final class Builder {

        private final FastPairAccountKeyDeviceMetadataParcel mBuilderParcel;

        /**
         * Default constructor of Builder.
         */
        public Builder() {
            mBuilderParcel = new FastPairAccountKeyDeviceMetadataParcel();
            mBuilderParcel.deviceAccountKey = null;
            mBuilderParcel.sha256DeviceAccountKeyPublicAddress = null;
            mBuilderParcel.metadata = null;
            mBuilderParcel.discoveryItem = null;
        }

        /**
         * Set Account Key.
         *
         * @param deviceAccountKey Fast Pair device account key.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setDeviceAccountKey(@Nullable byte[] deviceAccountKey) {
            mBuilderParcel.deviceAccountKey = deviceAccountKey;
            return this;
        }

        /**
         * Set sha256 account key and  public address.
         *
         * @param sha256DeviceAccountKeyPublicAddress Hash value of device's account key and public
         *                                            address.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setSha256DeviceAccountKeyPublicAddress(
                @Nullable byte[] sha256DeviceAccountKeyPublicAddress) {
            mBuilderParcel.sha256DeviceAccountKeyPublicAddress =
                    sha256DeviceAccountKeyPublicAddress;
            return this;
        }


        /**
         * Set Fast Pair metadata.
         *
         * @param metadata Fast Pair metadata.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setFastPairDeviceMetadata(@Nullable FastPairDeviceMetadata metadata) {
            if (metadata == null) {
                mBuilderParcel.metadata = null;
            } else {
                mBuilderParcel.metadata = metadata.mMetadataParcel;
            }
            return this;
        }

        /**
         * Set Fast Pair discovery item.
         *
         * @param discoveryItem Fast Pair discovery item.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setFastPairDiscoveryItem(@Nullable FastPairDiscoveryItem discoveryItem) {
            if (discoveryItem == null) {
                mBuilderParcel.discoveryItem = null;
            } else {
                mBuilderParcel.discoveryItem = discoveryItem.mMetadataParcel;
            }
            return this;
        }

        /**
         * Build {@link FastPairAccountKeyDeviceMetadata} with the currently set configuration.
         */
        @NonNull
        public FastPairAccountKeyDeviceMetadata build() {
            return new FastPairAccountKeyDeviceMetadata(mBuilderParcel);
        }
    }
}
