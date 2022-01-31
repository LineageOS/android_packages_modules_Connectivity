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

package com.android.server.nearby.provider;

import android.accounts.Account;
import android.annotation.Nullable;
import android.content.Context;
import android.nearby.FastPairDataProviderBase;
import android.nearby.FastPairDevice;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataRequestParcel;
import android.nearby.aidl.FastPairEligibleAccountsRequestParcel;
import android.nearby.aidl.FastPairManageAccountDeviceRequestParcel;
import android.nearby.aidl.FastPairManageAccountRequestParcel;
import android.util.Log;

import androidx.annotation.WorkerThread;

import com.android.server.nearby.common.bloomfilter.BloomFilter;
import com.android.server.nearby.fastpair.footprint.FastPairUploadInfo;

import java.util.List;

import service.proto.Rpcs;

/**
 * FastPairDataProvider is a singleton that implements APIs to get FastPair data.
 */
public class FastPairDataProvider {

    private static final String TAG = "FastPairDataProvider";

    private static FastPairDataProvider sInstance;

    private ProxyFastPairDataProvider mProxyFastPairDataProvider;

    /**
     * Initializes FastPairDataProvider singleton.
     */
    public static synchronized FastPairDataProvider init(Context context) {

        if (sInstance == null) {
            sInstance = new FastPairDataProvider(context);
        }
        if (sInstance.mProxyFastPairDataProvider == null) {
            Log.wtf(TAG, "no proxy fast pair data provider found");
        } else {
            sInstance.mProxyFastPairDataProvider.register();
        }
        return sInstance;
    }

    @Nullable
    public static synchronized FastPairDataProvider getInstance() {
        return sInstance;
    }

    private FastPairDataProvider(Context context) {
        mProxyFastPairDataProvider = ProxyFastPairDataProvider.create(
                context, FastPairDataProviderBase.ACTION_FAST_PAIR_DATA_PROVIDER);
        if (mProxyFastPairDataProvider == null) {
            Log.d("FastPairService", "fail to initiate the fast pair proxy provider");
        } else {
            Log.d("FastPairService", "the fast pair proxy provider initiated");
        }
    }

    /**
     * Loads FastPairAntispoofkeyDeviceMetadata.
     *
     * @throws IllegalStateException If ProxyFastPairDataProvider is not available.
     */
    @WorkerThread
    @Nullable
    public Rpcs.GetObservedDeviceResponse loadFastPairAntispoofkeyDeviceMetadata(byte[] modelId) {
        if (mProxyFastPairDataProvider != null) {
            FastPairAntispoofkeyDeviceMetadataRequestParcel requestParcel =
                    new FastPairAntispoofkeyDeviceMetadataRequestParcel();
            requestParcel.modelId = modelId;
            return Utils.convertFastPairAntispoofkeyDeviceMetadataToGetObservedDeviceResponse(
                    mProxyFastPairDataProvider
                            .loadFastPairAntispoofkeyDeviceMetadata(requestParcel));
        }
        throw new IllegalStateException("No ProxyFastPairDataProvider yet constructed");
    }

    /**
     * Enrolls an account to Fast Pair.
     *
     * @throws IllegalStateException If ProxyFastPairDataProvider is not available.
     */
    public void optIn(Account account) {
        if (mProxyFastPairDataProvider != null) {
            FastPairManageAccountRequestParcel requestParcel =
                    new FastPairManageAccountRequestParcel();
            requestParcel.account = account;
            requestParcel.requestType = FastPairDataProviderBase.MANAGE_REQUEST_ADD;
            mProxyFastPairDataProvider.manageFastPairAccount(requestParcel);
        }
        throw new IllegalStateException("No ProxyFastPairDataProvider yet constructed");
    }

    /**
     * Uploads the device info to Fast Pair account.
     *
     * @throws IllegalStateException If ProxyFastPairDataProvider is not available.
     */
    public void upload(Account account, FastPairUploadInfo uploadInfo) {
        if (mProxyFastPairDataProvider != null) {
            FastPairManageAccountDeviceRequestParcel requestParcel =
                    new FastPairManageAccountDeviceRequestParcel();
            requestParcel.account = account;
            requestParcel.requestType = FastPairDataProviderBase.MANAGE_REQUEST_ADD;
            requestParcel.accountKeyDeviceMetadata =
                    Utils.convertFastPairUploadInfoToFastPairAccountKeyDeviceMetadata(
                            uploadInfo);
            mProxyFastPairDataProvider.manageFastPairAccountDevice(requestParcel);
        }
        throw new IllegalStateException("No ProxyFastPairDataProvider yet constructed");
    }

    /**
     * Get recognized device from bloom filter.
     */
    public FastPairDevice getRecognizedDevice(BloomFilter bloomFilter, byte[] salt) {
        return new FastPairDevice.Builder().build();
    }

    /**
     * Get FastPair Eligible Accounts.
     *
     * @throws IllegalStateException If ProxyFastPairDataProvider is not available.
     */
    public List<Account> loadFastPairEligibleAccounts() {
        if (mProxyFastPairDataProvider != null) {
            FastPairEligibleAccountsRequestParcel requestParcel =
                    new FastPairEligibleAccountsRequestParcel();
            return Utils.convertFastPairEligibleAccountsToAccountList(
                    mProxyFastPairDataProvider.loadFastPairEligibleAccounts(requestParcel));
        }
        throw new IllegalStateException("No ProxyFastPairDataProvider yet constructed");
    }
}
