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

import android.accounts.Account;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.nearby.aidl.FastPairAccountDevicesMetadataRequestParcel;
import android.nearby.aidl.FastPairAccountKeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataRequestParcel;
import android.nearby.aidl.FastPairEligibleAccountParcel;
import android.nearby.aidl.FastPairEligibleAccountsRequestParcel;
import android.nearby.aidl.FastPairManageAccountDeviceRequestParcel;
import android.nearby.aidl.FastPairManageAccountRequestParcel;
import android.nearby.aidl.IFastPairAccountDevicesMetadataCallback;
import android.nearby.aidl.IFastPairAntispoofkeyDeviceMetadataCallback;
import android.nearby.aidl.IFastPairDataProvider;
import android.nearby.aidl.IFastPairEligibleAccountsCallback;
import android.nearby.aidl.IFastPairManageAccountCallback;
import android.nearby.aidl.IFastPairManageAccountDeviceCallback;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;

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
    /**
     * The action the wrapping service should have in its intent filter to implement the
     * {@link android.nearby.FastPairDataProviderBase}.
     */
    public static final String ACTION_FAST_PAIR_DATA_PROVIDER =
            "android.nearby.action.FAST_PAIR_DATA_PROVIDER";

    /**
     * Manage request type to add, or opt-in.
     */
    public static final int MANAGE_REQUEST_ADD = 0;
    /**
     * Manage request type to remove, or opt-out.
     */
    public static final int MANAGE_REQUEST_REMOVE = 1;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            MANAGE_REQUEST_ADD,
            MANAGE_REQUEST_REMOVE})
    @interface ManageRequestType {}


    public static final int ERROR_CODE_BAD_REQUEST = 0;
    public static final int ERROR_CODE_INTERNAL_ERROR = 1;
    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            ERROR_CODE_BAD_REQUEST,
            ERROR_CODE_INTERNAL_ERROR})
    @interface ErrorCode {}

    private final IBinder mBinder;
    private final String mTag;

    /**
     * Constructor of FastPairDataProviderBase.
     *
     * @param tag TAG for on device logging.
     */
    public FastPairDataProviderBase(@NonNull String tag) {
        mBinder = new Service();
        mTag = tag;
    }

    /**
     * Callback to be invoked when an antispoofkeyed device metadata is loaded.
     */
    public interface FastPairAntispoofkeyDeviceMetadataCallback {

        /**
         * Invoked once the meta data is loaded.
         */
        void onFastPairAntispoofkeyDeviceMetadataReceived(
                @NonNull FastPairAntispoofkeyDeviceMetadata metadata);
        /** Invoked in case of error. */
        void onError(@ErrorCode int code, @Nullable String message);
    }

    /**
     * Callback to be invoked when Fast Pair devices of a given account is loaded.
     */
    public interface FastPairAccountDevicesMetadataCallback {

        /**
         * Should be invoked once the metadatas are loaded.
         */
        void onFastPairAccountDevicesMetadataReceived(
                @NonNull Collection<FastPairAccountKeyDeviceMetadata> metadatas);
        /** Invoked in case of error. */
        void onError(@ErrorCode int code, @Nullable String message);
    }

    /** Callback to be invoked when FastPair eligible accounts are loaded. */
    public interface FastPairEligibleAccountsCallback {

        /**
         * Should be invoked once the eligible accounts are loaded.
         */
        void onFastPairEligibleAccountsReceived(
                @NonNull Collection<FastPairEligibleAccount> accounts);
        /** Invoked in case of error. */
        void onError(@ErrorCode int code, @Nullable String message);
    }

    /**
     * Callback to be invoked when a management action is finished.
     */
    public interface FastPairManageActionCallback {

        /**
         * Should be invoked once the manage action is successful.
         */
        void onSuccess();
        /** Invoked in case of error. */
        void onError(@ErrorCode int code, @Nullable String message);
    }

    /**
     * Fulfills the Fast Pair device metadata request by using callback to send back the
     * device meta data of a given modelId.
     */
    public abstract void onLoadFastPairAntispoofkeyDeviceMetadata(
            @NonNull FastPairAntispoofkeyDeviceMetadataRequest request,
            @NonNull FastPairAntispoofkeyDeviceMetadataCallback callback);

    /**
     * Fulfills the account tied Fast Pair devices metadata request by using callback to send back
     * all Fast Pair device's metadata of a given account.
     */
    public abstract void onLoadFastPairAccountDevicesMetadata(
            @NonNull FastPairAccountDevicesMetadataRequest request,
            @NonNull FastPairAccountDevicesMetadataCallback callback);

    /**
     * Fulfills the Fast Pair eligible accounts request by using callback to send back Fast Pair
     * eligible accounts */
    public abstract void onLoadFastPairEligibleAccounts(
            @NonNull FastPairEligibleAccountsRequest request,
            @NonNull FastPairEligibleAccountsCallback callback);

    /**
     * Fulfills the Fast Pair account management request by using callback to send back result.
     */
    public abstract void onManageFastPairAccount(
            @NonNull FastPairManageAccountRequest request,
            @NonNull FastPairManageActionCallback callback);

    /**
     * Fulfills the request to manage device-account mapping by using callback to send back result.
     */
    public abstract void onManageFastPairAccountDevice(
            @NonNull FastPairManageAccountDeviceRequest request,
            @NonNull FastPairManageActionCallback callback);

    /**
     * Returns the IBinder instance that should be returned from the {@link
     * android.app.Service#onBind(Intent)} method of the wrapping service.
     */
    public final @Nullable IBinder getBinder() {
        return mBinder;
    }

    /**
     * Class for reading FastPairAntispoofkeyDeviceMetadataRequest.
     */
    public static class FastPairAntispoofkeyDeviceMetadataRequest {

        private final FastPairAntispoofkeyDeviceMetadataRequestParcel mMetadataRequestParcel;

        private FastPairAntispoofkeyDeviceMetadataRequest(
                final FastPairAntispoofkeyDeviceMetadataRequestParcel metaDataRequestParcel) {
            this.mMetadataRequestParcel = metaDataRequestParcel;
        }

        /** Get modelId, the key for FastPairAntispoofkeyDeviceMetadata. */
        public @NonNull byte[] getModelId() {
            return this.mMetadataRequestParcel.modelId;
        }
    }

    /**
     * Class for reading FastPairAccountDevicesMetadataRequest.
     */
    public static class FastPairAccountDevicesMetadataRequest {

        private final FastPairAccountDevicesMetadataRequestParcel mMetadataRequestParcel;

        private FastPairAccountDevicesMetadataRequest(
                final FastPairAccountDevicesMetadataRequestParcel metaDataRequestParcel) {
            this.mMetadataRequestParcel = metaDataRequestParcel;
        }
        /** Get account. */
        public @NonNull Account getAccount() {
            return this.mMetadataRequestParcel.account;
        }
    }

    /** Class for reading FastPairEligibleAccountsRequest. */
    public static class FastPairEligibleAccountsRequest {
        @SuppressWarnings("UnusedVariable")
        private final FastPairEligibleAccountsRequestParcel mAccountsRequestParcel;

        private FastPairEligibleAccountsRequest(
                final FastPairEligibleAccountsRequestParcel accountsRequestParcel) {
            this.mAccountsRequestParcel = accountsRequestParcel;
        }
    }

    /** Class for reading FastPairManageAccountRequest. */
    public static class FastPairManageAccountRequest {

        private final FastPairManageAccountRequestParcel mAccountRequestParcel;

        private FastPairManageAccountRequest(
                final FastPairManageAccountRequestParcel accountRequestParcel) {
            this.mAccountRequestParcel = accountRequestParcel;
        }

        /** Get request type: MANAGE_REQUEST_ADD, or MANAGE_REQUEST_REMOVE. */
        public @ManageRequestType int getRequestType() {
            return this.mAccountRequestParcel.requestType;
        }
        /** Get account. */
        public @NonNull Account getAccount() {
            return this.mAccountRequestParcel.account;
        }
    }

    /** Class for reading FastPairManageAccountDeviceRequest. */
    public static class FastPairManageAccountDeviceRequest {

        private final FastPairManageAccountDeviceRequestParcel mRequestParcel;

        private FastPairManageAccountDeviceRequest(
                final FastPairManageAccountDeviceRequestParcel requestParcel) {
            this.mRequestParcel = requestParcel;
        }

        /** Get request type: MANAGE_REQUEST_ADD, or MANAGE_REQUEST_REMOVE. */
        public @ManageRequestType int getRequestType() {
            return this.mRequestParcel.requestType;
        }
        /** Get account. */
        public @NonNull Account getAccount() {
            return this.mRequestParcel.account;
        }
        /** Get BleAddress. */
        public @Nullable String getBleAddress() {
            return this.mRequestParcel.bleAddress;
        }
        /** Get account key device metadata. */
        public @NonNull FastPairAccountKeyDeviceMetadata getAccountKeyDeviceMetadata() {
            return new FastPairAccountKeyDeviceMetadata(
                    this.mRequestParcel.accountKeyDeviceMetadata);
        }
    }

    /**
     * Callback class that sends back FastPairAntispoofkeyDeviceMetadata.
     */
    private final class WrapperFastPairAntispoofkeyDeviceMetadataCallback implements
            FastPairAntispoofkeyDeviceMetadataCallback {

        private IFastPairAntispoofkeyDeviceMetadataCallback mCallback;

        private WrapperFastPairAntispoofkeyDeviceMetadataCallback(
                IFastPairAntispoofkeyDeviceMetadataCallback callback) {
            mCallback = callback;
        }

        /**
         * Sends back FastPairAntispoofkeyDeviceMetadata.
         */
        @Override
        public void onFastPairAntispoofkeyDeviceMetadataReceived(
                @NonNull FastPairAntispoofkeyDeviceMetadata metadata) {
            try {
                mCallback.onFastPairAntispoofkeyDeviceMetadataReceived(metadata.mMetadataParcel);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }

        @Override
        public void onError(@ErrorCode int code, @Nullable String message) {
            try {
                mCallback.onError(code, message);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Callback class that sends back collection of FastPairAccountKeyDeviceMetadata.
     */
    private final class WrapperFastPairAccountDevicesMetadataCallback implements
            FastPairAccountDevicesMetadataCallback {

        private IFastPairAccountDevicesMetadataCallback mCallback;

        private WrapperFastPairAccountDevicesMetadataCallback(
                IFastPairAccountDevicesMetadataCallback callback) {
            mCallback = callback;
        }

        /**
         * Sends back collection of FastPairAccountKeyDeviceMetadata.
         */
        @Override
        public void onFastPairAccountDevicesMetadataReceived(
                @NonNull Collection<FastPairAccountKeyDeviceMetadata> metadatas) {
            FastPairAccountKeyDeviceMetadataParcel[] metadataParcels =
                    new FastPairAccountKeyDeviceMetadataParcel[metadatas.size()];
            int i = 0;
            for (FastPairAccountKeyDeviceMetadata metadata : metadatas) {
                metadataParcels[i] = metadata.mMetadataParcel;
                i = i + 1;
            }
            try {
                mCallback.onFastPairAccountDevicesMetadataReceived(metadataParcels);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }

        @Override
        public void onError(@ErrorCode int code, @Nullable String message) {
            try {
                mCallback.onError(code, message);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Callback class that sends back eligible Fast Pair accounts.
     */
    private final class WrapperFastPairEligibleAccountsCallback implements
            FastPairEligibleAccountsCallback {

        private IFastPairEligibleAccountsCallback mCallback;

        private WrapperFastPairEligibleAccountsCallback(
                IFastPairEligibleAccountsCallback callback) {
            mCallback = callback;
        }

        /**
         * Sends back the eligble Fast Pair accounts.
         */
        @Override
        public void onFastPairEligibleAccountsReceived(
                @NonNull Collection<FastPairEligibleAccount> accounts) {
            int i = 0;
            FastPairEligibleAccountParcel[] accountParcels =
                    new FastPairEligibleAccountParcel[accounts.size()];
            for (FastPairEligibleAccount account: accounts) {
                accountParcels[i] = account.mAccountParcel;
                i = i + 1;
            }
            try {
                mCallback.onFastPairEligibleAccountsReceived(accountParcels);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }

        @Override
        public void onError(@ErrorCode int code, @Nullable String message) {
            try {
                mCallback.onError(code, message);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Callback class that sends back Fast Pair account management result.
     */
    private final class WrapperFastPairManageAccountCallback implements
            FastPairManageActionCallback {

        private IFastPairManageAccountCallback mCallback;

        private WrapperFastPairManageAccountCallback(
                IFastPairManageAccountCallback callback) {
            mCallback = callback;
        }

        /**
         * Sends back Fast Pair account opt in result.
         */
        @Override
        public void onSuccess() {
            try {
                mCallback.onSuccess();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }

        @Override
        public void onError(@ErrorCode int code, @Nullable String message) {
            try {
                mCallback.onError(code, message);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Call back class that sends back account-device mapping management result.
     */
    private final class WrapperFastPairManageAccountDeviceCallback implements
            FastPairManageActionCallback {

        private IFastPairManageAccountDeviceCallback mCallback;

        private WrapperFastPairManageAccountDeviceCallback(
                IFastPairManageAccountDeviceCallback callback) {
            mCallback = callback;
        }

        /**
         * Sends back the account-device mapping management result.
         */
        @Override
        public void onSuccess() {
            try {
                mCallback.onSuccess();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }

        @Override
        public void onError(@ErrorCode int code, @Nullable String message) {
            try {
                mCallback.onError(code, message);
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
        public void loadFastPairAntispoofkeyDeviceMetadata(
                @NonNull FastPairAntispoofkeyDeviceMetadataRequestParcel requestParcel,
                IFastPairAntispoofkeyDeviceMetadataCallback callback) {
            onLoadFastPairAntispoofkeyDeviceMetadata(
                    new FastPairAntispoofkeyDeviceMetadataRequest(requestParcel),
                    new WrapperFastPairAntispoofkeyDeviceMetadataCallback(callback));
        }

        @Override
        public void loadFastPairAccountDevicesMetadata(
                @NonNull FastPairAccountDevicesMetadataRequestParcel requestParcel,
                IFastPairAccountDevicesMetadataCallback callback) {
            onLoadFastPairAccountDevicesMetadata(
                    new FastPairAccountDevicesMetadataRequest(requestParcel),
                    new WrapperFastPairAccountDevicesMetadataCallback(callback));
        }

        @Override
        public void loadFastPairEligibleAccounts(
                @NonNull FastPairEligibleAccountsRequestParcel requestParcel,
                IFastPairEligibleAccountsCallback callback) {
            onLoadFastPairEligibleAccounts(new FastPairEligibleAccountsRequest(requestParcel),
                    new WrapperFastPairEligibleAccountsCallback(callback));
        }

        @Override
        public void manageFastPairAccount(
                @NonNull FastPairManageAccountRequestParcel requestParcel,
                IFastPairManageAccountCallback callback) {
            onManageFastPairAccount(new FastPairManageAccountRequest(requestParcel),
                    new WrapperFastPairManageAccountCallback(callback));
        }

        @Override
        public void manageFastPairAccountDevice(
                @NonNull FastPairManageAccountDeviceRequestParcel requestParcel,
                IFastPairManageAccountDeviceCallback callback) {
            onManageFastPairAccountDevice(new FastPairManageAccountDeviceRequest(requestParcel),
                    new WrapperFastPairManageAccountDeviceCallback(callback));
        }
    }
}
