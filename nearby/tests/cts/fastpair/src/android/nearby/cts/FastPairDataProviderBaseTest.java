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

package android.nearby.cts;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.nearby.FastPairAccountKeyDeviceMetadata;
import android.nearby.FastPairAntispoofkeyDeviceMetadata;
import android.nearby.FastPairDataProviderBase;
import android.nearby.FastPairEligibleAccount;
import android.nearby.aidl.FastPairAccountDevicesMetadataRequestParcel;
import android.nearby.aidl.FastPairAccountKeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataParcel;
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

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class FastPairDataProviderBaseTest {

    private static final String TAG = "FastPairDataProviderBaseTest";
    private static final String ERROR_STRING = "Error String";

    private @Mock FastPairDataProviderBase mMockFastPairDataProviderBase;
    private @Mock IFastPairAntispoofkeyDeviceMetadataCallback.Stub
            mAntispoofkeyDeviceMetadataCallback;
    private @Mock IFastPairAccountDevicesMetadataCallback.Stub mAccountDevicesMetadataCallback;
    private @Mock IFastPairEligibleAccountsCallback.Stub mEligibleAccountsCallback;
    private @Mock IFastPairManageAccountCallback.Stub mManageAccountCallback;
    private @Mock IFastPairManageAccountDeviceCallback.Stub mManageAccountDeviceCallback;

    private MyHappyPathProvider mHappyPathFastPairDataProvider;
    private MyErrorPathProvider mErrorPathFastPairDataProvider;

    @Before
    public void setUp() throws Exception {
        initMocks(this);

        mHappyPathFastPairDataProvider =
                new MyHappyPathProvider(TAG, mMockFastPairDataProviderBase);
        mErrorPathFastPairDataProvider =
                new MyErrorPathProvider(TAG, mMockFastPairDataProviderBase);
    }

    @Test
    public void testHappyPathLoadFastPairAntispoofkeyDeviceMetadata() throws Exception {
        mHappyPathFastPairDataProvider.asProvider().loadFastPairAntispoofkeyDeviceMetadata(
                new FastPairAntispoofkeyDeviceMetadataRequestParcel(),
                mAntispoofkeyDeviceMetadataCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAntispoofkeyDeviceMetadata(
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest.class),
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataCallback.class));
        verify(mAntispoofkeyDeviceMetadataCallback).onFastPairAntispoofkeyDeviceMetadataReceived(
                any(FastPairAntispoofkeyDeviceMetadataParcel.class));
    }

    @Test
    public void testHappyPathLoadFastPairAccountDevicesMetadata() throws Exception {
        mHappyPathFastPairDataProvider.asProvider().loadFastPairAccountDevicesMetadata(
                new FastPairAccountDevicesMetadataRequestParcel(),
                mAccountDevicesMetadataCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAccountDevicesMetadata(
                any(FastPairDataProviderBase.FastPairAccountDevicesMetadataRequest.class),
                any(FastPairDataProviderBase.FastPairAccountDevicesMetadataCallback.class));
        verify(mAccountDevicesMetadataCallback).onFastPairAccountDevicesMetadataReceived(
                any(FastPairAccountKeyDeviceMetadataParcel[].class));
    }

    @Test
    public void testHappyPathLoadFastPairEligibleAccounts() throws Exception {
        mHappyPathFastPairDataProvider.asProvider().loadFastPairEligibleAccounts(
                new FastPairEligibleAccountsRequestParcel(),
                mEligibleAccountsCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairEligibleAccounts(
                any(FastPairDataProviderBase.FastPairEligibleAccountsRequest.class),
                any(FastPairDataProviderBase.FastPairEligibleAccountsCallback.class));
        verify(mEligibleAccountsCallback).onFastPairEligibleAccountsReceived(
                any(FastPairEligibleAccountParcel[].class));
    }

    @Test
    public void testHappyPathManageFastPairAccount() throws Exception {
        mHappyPathFastPairDataProvider.asProvider().manageFastPairAccount(
                new FastPairManageAccountRequestParcel(),
                mManageAccountCallback);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccount(
                any(FastPairDataProviderBase.FastPairManageAccountRequest.class),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        verify(mManageAccountCallback).onSuccess();
    }

    @Test
    public void testHappyPathManageFastPairAccountDevice() throws Exception {
        mHappyPathFastPairDataProvider.asProvider().manageFastPairAccountDevice(
                new FastPairManageAccountDeviceRequestParcel(),
                mManageAccountDeviceCallback);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccountDevice(
                any(FastPairDataProviderBase.FastPairManageAccountDeviceRequest.class),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        verify(mManageAccountDeviceCallback).onSuccess();
    }

    @Test
    public void testErrorPathLoadFastPairAntispoofkeyDeviceMetadata() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().loadFastPairAntispoofkeyDeviceMetadata(
                new FastPairAntispoofkeyDeviceMetadataRequestParcel(),
                mAntispoofkeyDeviceMetadataCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAntispoofkeyDeviceMetadata(
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest.class),
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataCallback.class));
        verify(mAntispoofkeyDeviceMetadataCallback).onError(anyInt(), any());
    }

    @Test
    public void testErrorPathLoadFastPairAccountDevicesMetadata() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().loadFastPairAccountDevicesMetadata(
                new FastPairAccountDevicesMetadataRequestParcel(),
                mAccountDevicesMetadataCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAccountDevicesMetadata(
                any(FastPairDataProviderBase.FastPairAccountDevicesMetadataRequest.class),
                any(FastPairDataProviderBase.FastPairAccountDevicesMetadataCallback.class));
        verify(mAccountDevicesMetadataCallback).onError(anyInt(), any());
    }

    @Test
    public void testErrorPathLoadFastPairEligibleAccounts() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().loadFastPairEligibleAccounts(
                new FastPairEligibleAccountsRequestParcel(),
                mEligibleAccountsCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairEligibleAccounts(
                any(FastPairDataProviderBase.FastPairEligibleAccountsRequest.class),
                any(FastPairDataProviderBase.FastPairEligibleAccountsCallback.class));
        verify(mEligibleAccountsCallback).onError(anyInt(), any());
    }

    @Test
    public void testErrorPathManageFastPairAccount() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().manageFastPairAccount(
                new FastPairManageAccountRequestParcel(),
                mManageAccountCallback);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccount(
                any(FastPairDataProviderBase.FastPairManageAccountRequest.class),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        verify(mManageAccountCallback).onError(anyInt(), any());
    }

    @Test
    public void testErrorPathManageFastPairAccountDevice() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().manageFastPairAccountDevice(
                new FastPairManageAccountDeviceRequestParcel(),
                mManageAccountDeviceCallback);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccountDevice(
                any(FastPairDataProviderBase.FastPairManageAccountDeviceRequest.class),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        verify(mManageAccountDeviceCallback).onError(anyInt(), any());
    }

    public static class MyHappyPathProvider extends FastPairDataProviderBase {

        private final FastPairDataProviderBase mMockFastPairDataProviderBase;

        public MyHappyPathProvider(@NonNull String tag, FastPairDataProviderBase mock) {
            super(tag);
            mMockFastPairDataProviderBase = mock;
        }

        public IFastPairDataProvider asProvider() {
            return IFastPairDataProvider.Stub.asInterface(getBinder());
        }

        @Override
        public void onLoadFastPairAntispoofkeyDeviceMetadata(
                @NonNull FastPairAntispoofkeyDeviceMetadataRequest request,
                @NonNull FastPairAntispoofkeyDeviceMetadataCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairAntispoofkeyDeviceMetadata(
                    request, callback);
            callback.onFastPairAntispoofkeyDeviceMetadataReceived(
                    new FastPairAntispoofkeyDeviceMetadata.Builder().build());
        }

        @Override
        public void onLoadFastPairAccountDevicesMetadata(
                @NonNull FastPairAccountDevicesMetadataRequest request,
                @NonNull FastPairAccountDevicesMetadataCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairAccountDevicesMetadata(
                    request, callback);
            callback.onFastPairAccountDevicesMetadataReceived(
                    new ArrayList<FastPairAccountKeyDeviceMetadata>());
        }

        @Override
        public void onLoadFastPairEligibleAccounts(
                @NonNull FastPairEligibleAccountsRequest request,
                @NonNull FastPairEligibleAccountsCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairEligibleAccounts(
                    request, callback);
            callback.onFastPairEligibleAccountsReceived(
                    new ArrayList<FastPairEligibleAccount>());
        }

        @Override
        public void onManageFastPairAccount(
                @NonNull FastPairManageAccountRequest request,
                @NonNull FastPairManageActionCallback callback) {
            mMockFastPairDataProviderBase.onManageFastPairAccount(request, callback);
            callback.onSuccess();
        }

        @Override
        public void onManageFastPairAccountDevice(
                @NonNull FastPairManageAccountDeviceRequest request,
                @NonNull FastPairManageActionCallback callback) {
            mMockFastPairDataProviderBase.onManageFastPairAccountDevice(request, callback);
            callback.onSuccess();
        }
    }

    public static class MyErrorPathProvider extends FastPairDataProviderBase {

        private final FastPairDataProviderBase mMockFastPairDataProviderBase;

        public MyErrorPathProvider(@NonNull String tag, FastPairDataProviderBase mock) {
            super(tag);
            mMockFastPairDataProviderBase = mock;
        }

        public IFastPairDataProvider asProvider() {
            return IFastPairDataProvider.Stub.asInterface(getBinder());
        }

        @Override
        public void onLoadFastPairAntispoofkeyDeviceMetadata(
                @NonNull FastPairAntispoofkeyDeviceMetadataRequest request,
                @NonNull FastPairAntispoofkeyDeviceMetadataCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairAntispoofkeyDeviceMetadata(
                    request, callback);
            callback.onError(ERROR_CODE_BAD_REQUEST, ERROR_STRING);
        }

        @Override
        public void onLoadFastPairAccountDevicesMetadata(
                @NonNull FastPairAccountDevicesMetadataRequest request,
                @NonNull FastPairAccountDevicesMetadataCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairAccountDevicesMetadata(request, callback);
            callback.onError(ERROR_CODE_BAD_REQUEST, ERROR_STRING);
        }

        @Override
        public void onLoadFastPairEligibleAccounts(
                @NonNull FastPairEligibleAccountsRequest request,
                @NonNull FastPairEligibleAccountsCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairEligibleAccounts(request, callback);
            callback.onError(ERROR_CODE_BAD_REQUEST, ERROR_STRING);
        }

        @Override
        public void onManageFastPairAccount(
                @NonNull FastPairManageAccountRequest request,
                @NonNull FastPairManageActionCallback callback) {
            mMockFastPairDataProviderBase.onManageFastPairAccount(request, callback);
            callback.onError(ERROR_CODE_BAD_REQUEST, ERROR_STRING);
        }

        @Override
        public void onManageFastPairAccountDevice(
                @NonNull FastPairManageAccountDeviceRequest request,
                @NonNull FastPairManageActionCallback callback) {
            mMockFastPairDataProviderBase.onManageFastPairAccountDevice(request, callback);
            callback.onError(ERROR_CODE_BAD_REQUEST, ERROR_STRING);
        }
    }
}
