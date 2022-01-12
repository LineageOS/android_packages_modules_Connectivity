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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataParcel;

/**
 * Class for a type of registered Fast Pair device keyed by modelID, or antiSpoofKey.
 * @hide
 */
@SystemApi
public class FastPairAntispoofkeyDeviceMetadata {

    FastPairAntispoofkeyDeviceMetadataParcel mMetadataParcel;
    FastPairAntispoofkeyDeviceMetadata(
            FastPairAntispoofkeyDeviceMetadataParcel metadataParcel) {
        this.mMetadataParcel = metadataParcel;
    }

    /**
     * Builder used to create FastPairAntisppofkeyDeviceMetadata.
     */
    public static final class Builder {

        private final FastPairAntispoofkeyDeviceMetadataParcel mBuilderParcel;

        /**
         * Default constructor of Builder.
         */
        public Builder() {
            mBuilderParcel = new FastPairAntispoofkeyDeviceMetadataParcel();
            mBuilderParcel.antiSpoofPublicKey = null;
            mBuilderParcel.deviceMetadata = null;
        }

        /**
         * Set AntiSpoof public key, which uniquely identify a Fast Pair device type.
         *
         * @param antiSpoofPublicKey AntiSpoof public key.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setAntiSpoofPublicKey(@Nullable byte[] antiSpoofPublicKey) {
            mBuilderParcel.antiSpoofPublicKey = antiSpoofPublicKey;
            return this;
        }

        /**
         * Set Fast Pair metadata, which is the property of a Fast Pair device type, including
         * device images and strings.
         *
         * @param metadata Fast Pair device meta data.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setFastPairDeviceMetadata(@Nullable FastPairDeviceMetadata metadata) {
            mBuilderParcel.deviceMetadata = metadata.mMetadataParcel;
            return this;
        }

        /**
         * Build {@link FastPairAntispoofkeyDeviceMetadata} with the currently set configuration.
         */
        @NonNull
        public FastPairAntispoofkeyDeviceMetadata build() {
            return new FastPairAntispoofkeyDeviceMetadata(mBuilderParcel);
        }
    }
}
