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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * Base class for fast pair providers outside the system server.
 *
 * Fast pair providers should be wrapped in a non-exported service which returns the result of
 * {@link #getBinder()} from the service's {@link android.app.Service#onBind(Intent)} method. The
 * service should not be exported so that components other than the system server cannot bind to it.
 * Alternatively, the service may be guarded by a permission that only system server can obtain.
 *
 * <p>Fast Pair providers are identified by their UID / package name.
 *
 * @hide
 */
@SystemApi
public abstract class FastPairDataProviderBase {

    private final IBinder mBinder;
    private final String mTag;

    public FastPairDataProviderBase(@NonNull String tag) {
        mBinder = new Service();
        mTag = tag;
    }

    /**
     * Callback to be invoked when a device metadata is loaded.
     */
    public interface FastPairDeviceMetadataCallback {

        /**
         * Should be invoked once the meta data is loaded.
         */
        void onFastPairDeviceMetadataReceived(@NonNull FastPairDeviceMetadata metadata);
    }

    /**
     * Fullfills the load device metadata request by using callback to send back the serialized
     * device meta data of the given modelId.
     */
    public abstract void onLoadFastPairDeviceMetadata(
            @NonNull FastPairDeviceMetadataRequest request,
            @NonNull FastPairDeviceMetadataCallback callback);

    /**
     * Returns the IBinder instance that should be returned from the {@link
     * android.app.Service#onBind(Intent)} method of the wrapping service.
     */
    public final @Nullable IBinder getBinder() {
        return mBinder;
    }

    /**
     * Class for building FastPairDeviceMetadata.
     */
    public static class FastPairDeviceMetadata {

        private FastPairDeviceMetadataParcel mMetadataParcel;

        private FastPairDeviceMetadata(FastPairDeviceMetadataParcel metadataParcel) {
            this.mMetadataParcel = metadataParcel;
        }

        /**
         * Builder used to create FastPairDeviceMetadata.
         */
        public static final class Builder {

            private final FastPairDeviceMetadataParcel mBuilderParcel;

            /**
             * Default constructor of Builder.
             */
            public Builder() {
                mBuilderParcel = new FastPairDeviceMetadataParcel();
                mBuilderParcel.imageUrl = null;
                mBuilderParcel.intentUri = null;
                mBuilderParcel.antiSpoofPublicKey = null;
                mBuilderParcel.bleTxPower = 0;
                mBuilderParcel.triggerDistance = 0;
                mBuilderParcel.image = null;
                mBuilderParcel.deviceType = 0;  // DEVICE_TYPE_UNSPECIFIED
                mBuilderParcel.trueWirelessImageUrlLeftBud = null;
                mBuilderParcel.trueWirelessImageUrlRightBud = null;
                mBuilderParcel.trueWirelessImageUrlCase = null;
            }

            /**
             * Set ImageUlr.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setImageUrl(@NonNull String imageUrl) {
                mBuilderParcel.imageUrl = imageUrl;
                return this;
            }

            /**
             * Set IntentUri.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setIntentUri(@NonNull String intentUri) {
                mBuilderParcel.intentUri = intentUri;
                return this;
            }

            /**
             * Set AntiSpoof public key.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setAntiSpoofPublicKey(@NonNull byte[] antiSpoofPublicKey) {
                mBuilderParcel.antiSpoofPublicKey = antiSpoofPublicKey;
                return this;
            }

            /**
             * Set ble transmission power.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setBleTxPower(int bleTxPower) {
                mBuilderParcel.bleTxPower = bleTxPower;
                return this;
            }

            /**
             * Set trigger distance.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setTriggerDistance(float triggerDistance) {
                mBuilderParcel.triggerDistance = triggerDistance;
                return this;
            }

            /**
             * Set image.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setImage(@NonNull byte[] image) {
                mBuilderParcel.image = image;
                return this;
            }

            /**
             * Set device type.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setDeviceType(int deviceType) {
                mBuilderParcel.deviceType = deviceType;
                return this;
            }

            /**
             * Set true wireless image url for left bud.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setTrueWirelessImageUriLeftBud(
                    @NonNull byte[] trueWirelessImageUrlLeftBud) {
                mBuilderParcel.trueWirelessImageUrlLeftBud = trueWirelessImageUrlLeftBud;
                return this;
            }

            /**
             * Set true wireless image url for right bud.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setTrueWirelessImageUrlRightBud(
                    @NonNull byte[] trueWirelessImageUrlRightBud) {
                mBuilderParcel.trueWirelessImageUrlRightBud = trueWirelessImageUrlRightBud;
                return this;
            }

            /**
             * Set true wireless image url for right bud.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setTrueWirelessImageUrlCase(@NonNull byte[] trueWirelessImageUrlCase) {
                mBuilderParcel.trueWirelessImageUrlCase = trueWirelessImageUrlCase;
                return this;
            }

            /**
             * Build {@link FastPairDeviceMetadataRequest} with the currently set configuration.
             */
            @NonNull
            public FastPairDeviceMetadata build() {
                return new FastPairDeviceMetadata(mBuilderParcel);
            }
        }
    }

    /**
     * Class for reading FastPairDeviceMetadataRequest.
     */
    public static class FastPairDeviceMetadataRequest {

        private final FastPairDeviceMetadataRequestParcel mMetadataRequestParcel;

        private FastPairDeviceMetadataRequest(
                final FastPairDeviceMetadataRequestParcel metaDataRequestParcel) {
            this.mMetadataRequestParcel = metaDataRequestParcel;
        }

        public @Nullable byte[] getModelId() {
            return this.mMetadataRequestParcel.modelId;
        }
    }

    /**
     * Call back class that sends back data.
     */
    private final class Callback implements FastPairDeviceMetadataCallback {

        private IFastPairDataCallback mCallback;

        private Callback(IFastPairDataCallback callback) {
            mCallback = callback;
        }

        /**
         * Sends back the serialized device meta data.
         */
        @Override
        public void onFastPairDeviceMetadataReceived(@NonNull FastPairDeviceMetadata metadata) {
            try {
                mCallback.onFastPairDeviceMetadataReceived(metadata.mMetadataParcel);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    private final class Service extends IFastPairDataProvider.Stub {

        Service() {
        }

        @Override
        public void loadFastPairDeviceMetadata(
                @NonNull FastPairDeviceMetadataRequestParcel requestParcel,
                IFastPairDataCallback callback) {
            onLoadFastPairDeviceMetadata(new FastPairDeviceMetadataRequest(requestParcel),
                    new Callback(callback));
        }
    }
}
