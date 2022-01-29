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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.accounts.Account;
import android.nearby.FastPairAccountKeyDeviceMetadata;
import android.nearby.FastPairAntispoofkeyDeviceMetadata;
import android.nearby.FastPairDataProviderBase;
import android.nearby.FastPairDeviceMetadata;
import android.nearby.FastPairDiscoveryItem;
import android.nearby.FastPairEligibleAccount;
import android.nearby.aidl.FastPairAccountDevicesMetadataRequestParcel;
import android.nearby.aidl.FastPairAccountKeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataRequestParcel;
import android.nearby.aidl.FastPairDeviceMetadataParcel;
import android.nearby.aidl.FastPairDiscoveryItemParcel;
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

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
public class FastPairDataProviderBaseTest {

    private static final String TAG = "FastPairDataProviderBaseTest";

    private static final String ASSISTANT_SETUP_HALFSHEET = "ASSISTANT_SETUP_HALFSHEET";
    private static final String ASSISTANT_SETUP_NOTIFICATION = "ASSISTANT_SETUP_NOTIFICATION";
    private static final int BLE_TX_POWER  = 5;
    private static final String CONFIRM_PIN_DESCRIPTION = "CONFIRM_PIN_DESCRIPTION";
    private static final String CONFIRM_PIN_TITLE = "CONFIRM_PIN_TITLE";
    private static final String CONNECT_SUCCESS_COMPANION_APP_INSTALLED =
            "CONNECT_SUCCESS_COMPANION_APP_INSTALLED";
    private static final String CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED =
            "CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED";
    private static final double DELTA = 0.000001;
    private static final int DEVICE_TYPE = 7;
    private static final String DOWNLOAD_COMPANION_APP_DESCRIPTION =
            "DOWNLOAD_COMPANION_APP_DESCRIPTION";
    private static final Account ELIGIBLE_ACCOUNT_1 = new Account("abc@google.com", "type1");
    private static final boolean ELIGIBLE_ACCOUNT_1_OPT_IN = true;
    private static final Account ELIGIBLE_ACCOUNT_2 = new Account("def@gmail.com", "type2");
    private static final boolean ELIGIBLE_ACCOUNT_2_OPT_IN = false;
    private static final Account MANAGE_ACCOUNT = new Account("ghi@gmail.com", "type3");
    private static final Account ACCOUNTDEVICES_METADATA_ACCOUNT =
            new Account("jk@gmail.com", "type4");

    private static final int ERROR_CODE_BAD_REQUEST =
            FastPairDataProviderBase.ERROR_CODE_BAD_REQUEST;
    private static final int MANAGE_ACCOUNT_REQUEST_TYPE =
            FastPairDataProviderBase.MANAGE_REQUEST_ADD;
    private static final String ERROR_STRING = "ERROR_STRING";
    private static final String FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION =
            "FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION";
    private static final String FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION =
            "FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION";
    private static final byte[] IMAGE = new byte[] {7, 9};
    private static final String IMAGE_URL = "IMAGE_URL";
    private static final String INITIAL_NOTIFICATION_DESCRIPTION =
            "INITIAL_NOTIFICATION_DESCRIPTION";
    private static final String INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT =
            "INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT";
    private static final String INITIAL_PAIRING_DESCRIPTION = "INITIAL_PAIRING_DESCRIPTION";
    private static final String INTENT_URI = "INTENT_URI";
    private static final String LOCALE = "LOCALE";
    private static final String OPEN_COMPANION_APP_DESCRIPTION = "OPEN_COMPANION_APP_DESCRIPTION";
    private static final String RETRO_ACTIVE_PAIRING_DESCRIPTION =
            "RETRO_ACTIVE_PAIRING_DESCRIPTION";
    private static final String SUBSEQUENT_PAIRING_DESCRIPTION = "SUBSEQUENT_PAIRING_DESCRIPTION";
    private static final String SYNC_CONTACT_DESCRPTION = "SYNC_CONTACT_DESCRPTION";
    private static final String SYNC_CONTACTS_TITLE = "SYNC_CONTACTS_TITLE";
    private static final String SYNC_SMS_DESCRIPTION = "SYNC_SMS_DESCRIPTION";
    private static final String SYNC_SMS_TITLE = "SYNC_SMS_TITLE";
    private static final float TRIGGER_DISTANCE = 111;
    private static final String TRUE_WIRELESS_IMAGE_URL_CASE = "TRUE_WIRELESS_IMAGE_URL_CASE";
    private static final String TRUE_WIRELESS_IMAGE_URL_LEFT_BUD =
            "TRUE_WIRELESS_IMAGE_URL_LEFT_BUD";
    private static final String TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD =
            "TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD";
    private static final String UNABLE_TO_CONNECT_DESCRIPTION = "UNABLE_TO_CONNECT_DESCRIPTION";
    private static final String UNABLE_TO_CONNECT_TITLE = "UNABLE_TO_CONNECT_TITLE";
    private static final String UPDATE_COMPANION_APP_DESCRIPTION =
            "UPDATE_COMPANION_APP_DESCRIPTION";
    private static final String WAIT_LAUNCH_COMPANION_APP_DESCRIPTION =
            "WAIT_LAUNCH_COMPANION_APP_DESCRIPTION";
    private static final byte[] ACCOUNT_KEY = new byte[] {3};
    private static final byte[] SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS = new byte[] {2, 8};
    private static final byte[] REQUEST_MODEL_ID = new byte[] {1, 2, 3};
    private static final byte[] ANTI_SPOOFING_KEY = new byte[] {4, 5, 6};
    private static final String ACTION_URL = "ACTION_URL";
    private static final int ACTION_URL_TYPE = 5;
    private static final String APP_NAME = "APP_NAME";
    private static final int ATTACHMENT_TYPE = 8;
    private static final byte[] AUTHENTICATION_PUBLIC_KEY_SEC_P256R1 = new byte[] {5, 7};
    private static final byte[] BLE_RECORD_BYTES = new byte[]{2, 4};
    private static final int DEBUG_CATEGORY = 9;
    private static final String DEBUG_MESSAGE = "DEBUG_MESSAGE";
    private static final String DESCRIPTION = "DESCRIPTION";
    private static final String DEVICE_NAME = "DEVICE_NAME";
    private static final String DISPLAY_URL = "DISPLAY_URL";
    private static final String ENTITY_ID = "ENTITY_ID";
    private static final String FEATURE_GRAPHIC_URL = "FEATURE_GRAPHIC_URL";
    private static final long FIRST_OBSERVATION_TIMESTAMP_MILLIS = 8393L;
    private static final String GROUP_ID = "GROUP_ID";
    private static final String  ICON_FIFE_URL = "ICON_FIFE_URL";
    private static final byte[]  ICON_PNG = new byte[]{2, 5};
    private static final String ID = "ID";
    private static final long LAST_OBSERVATION_TIMESTAMP_MILLIS = 934234L;
    private static final int LAST_USER_EXPERIENCE = 93;
    private static final long LOST_MILLIS = 393284L;
    private static final String MAC_ADDRESS = "MAC_ADDRESS";
    private static final String PACKAGE_NAME = "PACKAGE_NAME";
    private static final long PENDING_APP_INSTALL_TIMESTAMP_MILLIS = 832393L;
    private static final int RSSI = 9;
    private static final int STATE = 63;
    private static final String TITLE = "TITLE";
    private static final String TRIGGER_ID = "TRIGGER_ID";
    private static final int TX_POWER = 62;
    private static final int TYPE = 73;
    private static final String BLE_ADDRESS = "BLE_ADDRESS";

    private static final int ELIGIBLE_ACCOUNTS_NUM = 2;
    private static final ImmutableList<FastPairEligibleAccount> ELIGIBLE_ACCOUNTS =
            ImmutableList.of(
                    genHappyPathFastPairEligibleAccount(ELIGIBLE_ACCOUNT_1,
                            ELIGIBLE_ACCOUNT_1_OPT_IN),
                    genHappyPathFastPairEligibleAccount(ELIGIBLE_ACCOUNT_2,
                            ELIGIBLE_ACCOUNT_2_OPT_IN));
    private static final int ACCOUNTKEY_DEVICE_NUM = 2;
    private static final ImmutableList<FastPairAccountKeyDeviceMetadata>
            FAST_PAIR_ACCOUNT_DEVICES_METADATA =
            ImmutableList.of(
                    genHappyPathFastPairAccountkeyDeviceMetadata(),
                    genHappyPathFastPairAccountkeyDeviceMetadata());

    private static final FastPairAntispoofkeyDeviceMetadataRequestParcel
            FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA_REQUEST_PARCEL =
            genFastPairAntispoofkeyDeviceMetadataRequestParcel();
    private static final FastPairAccountDevicesMetadataRequestParcel
            FAST_PAIR_ACCOUNT_DEVICES_METADATA_REQUEST_PARCEL =
            genFastPairAccountDevicesMetadataRequestParcel();
    private static final FastPairEligibleAccountsRequestParcel
            FAST_PAIR_ELIGIBLE_ACCOUNTS_REQUEST_PARCEL =
            genFastPairEligibleAccountsRequestParcel();
    private static final FastPairManageAccountRequestParcel
            FAST_PAIR_MANAGE_ACCOUNT_REQUEST_PARCEL =
            genFastPairManageAccountRequestParcel();
    private static final FastPairManageAccountDeviceRequestParcel
            FAST_PAIR_MANAGE_ACCOUNT_DEVICE_REQUEST_PARCEL =
            genFastPairManageAccountDeviceRequestParcel();
    private static final FastPairAntispoofkeyDeviceMetadata
            HAPPY_PATH_FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA =
            genHappyPathFastPairAntispoofkeyDeviceMetadata();

    private @Captor ArgumentCaptor<FastPairEligibleAccountParcel[]>
            mFastPairEligibleAccountParcelsArgumentCaptor;
    private @Captor ArgumentCaptor<FastPairAccountKeyDeviceMetadataParcel[]>
            mFastPairAccountKeyDeviceMetadataParcelsArgumentCaptor;

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
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().loadFastPairAntispoofkeyDeviceMetadata(
                FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA_REQUEST_PARCEL,
                mAntispoofkeyDeviceMetadataCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest>
                fastPairAntispoofkeyDeviceMetadataRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest.class);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAntispoofkeyDeviceMetadata(
                fastPairAntispoofkeyDeviceMetadataRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataCallback.class));
        ensureHappyPathAsExpected(fastPairAntispoofkeyDeviceMetadataRequestCaptor.getValue());

        // AOSP receives responses and verifies that it is as expected.
        final ArgumentCaptor<FastPairAntispoofkeyDeviceMetadataParcel>
                fastPairAntispoofkeyDeviceMetadataParcelCaptor =
                ArgumentCaptor.forClass(FastPairAntispoofkeyDeviceMetadataParcel.class);
        verify(mAntispoofkeyDeviceMetadataCallback).onFastPairAntispoofkeyDeviceMetadataReceived(
                fastPairAntispoofkeyDeviceMetadataParcelCaptor.capture());
        ensureHappyPathAsExpected(fastPairAntispoofkeyDeviceMetadataParcelCaptor.getValue());
    }

    @Test
    public void testHappyPathLoadFastPairAccountDevicesMetadata() throws Exception {
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().loadFastPairAccountDevicesMetadata(
                FAST_PAIR_ACCOUNT_DEVICES_METADATA_REQUEST_PARCEL,
                mAccountDevicesMetadataCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairAccountDevicesMetadataRequest>
                fastPairAccountDevicesMetadataRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairAccountDevicesMetadataRequest.class);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAccountDevicesMetadata(
                fastPairAccountDevicesMetadataRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairAccountDevicesMetadataCallback.class));
        ensureHappyPathAsExpected(fastPairAccountDevicesMetadataRequestCaptor.getValue());

        // AOSP receives responses and verifies that it is as expected.
        verify(mAccountDevicesMetadataCallback).onFastPairAccountDevicesMetadataReceived(
                mFastPairAccountKeyDeviceMetadataParcelsArgumentCaptor.capture());
        ensureHappyPathAsExpected(
                mFastPairAccountKeyDeviceMetadataParcelsArgumentCaptor.getValue());
    }

    @Test
    public void testHappyPathLoadFastPairEligibleAccounts() throws Exception {
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().loadFastPairEligibleAccounts(
                FAST_PAIR_ELIGIBLE_ACCOUNTS_REQUEST_PARCEL,
                mEligibleAccountsCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairEligibleAccountsRequest>
                fastPairEligibleAccountsRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairEligibleAccountsRequest.class);
        verify(mMockFastPairDataProviderBase).onLoadFastPairEligibleAccounts(
                fastPairEligibleAccountsRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairEligibleAccountsCallback.class));
        ensureHappyPathAsExpected(fastPairEligibleAccountsRequestCaptor.getValue());

        // AOSP receives responses and verifies that it is as expected.
        verify(mEligibleAccountsCallback).onFastPairEligibleAccountsReceived(
                mFastPairEligibleAccountParcelsArgumentCaptor.capture());
        ensureHappyPathAsExpected(mFastPairEligibleAccountParcelsArgumentCaptor.getValue());
    }

    @Test
    public void testHappyPathManageFastPairAccount() throws Exception {
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().manageFastPairAccount(
                FAST_PAIR_MANAGE_ACCOUNT_REQUEST_PARCEL,
                mManageAccountCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairManageAccountRequest>
                fastPairManageAccountRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairManageAccountRequest.class);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccount(
                fastPairManageAccountRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        ensureHappyPathAsExpected(fastPairManageAccountRequestCaptor.getValue());

        // AOSP receives SUCCESS response.
        verify(mManageAccountCallback).onSuccess();
    }

    @Test
    public void testHappyPathManageFastPairAccountDevice() throws Exception {
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().manageFastPairAccountDevice(
                FAST_PAIR_MANAGE_ACCOUNT_DEVICE_REQUEST_PARCEL,
                mManageAccountDeviceCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairManageAccountDeviceRequest>
                fastPairManageAccountDeviceRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairManageAccountDeviceRequest.class);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccountDevice(
                fastPairManageAccountDeviceRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        ensureHappyPathAsExpected(fastPairManageAccountDeviceRequestCaptor.getValue());

        // AOSP receives SUCCESS response.
        verify(mManageAccountDeviceCallback).onSuccess();
    }

    @Test
    public void testErrorPathLoadFastPairAntispoofkeyDeviceMetadata() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().loadFastPairAntispoofkeyDeviceMetadata(
                FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA_REQUEST_PARCEL,
                mAntispoofkeyDeviceMetadataCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAntispoofkeyDeviceMetadata(
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest.class),
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataCallback.class));
        verify(mAntispoofkeyDeviceMetadataCallback).onError(
                eq(ERROR_CODE_BAD_REQUEST), eq(ERROR_STRING));
    }

    @Test
    public void testErrorPathLoadFastPairAccountDevicesMetadata() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().loadFastPairAccountDevicesMetadata(
                FAST_PAIR_ACCOUNT_DEVICES_METADATA_REQUEST_PARCEL,
                mAccountDevicesMetadataCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAccountDevicesMetadata(
                any(FastPairDataProviderBase.FastPairAccountDevicesMetadataRequest.class),
                any(FastPairDataProviderBase.FastPairAccountDevicesMetadataCallback.class));
        verify(mAccountDevicesMetadataCallback).onError(
                eq(ERROR_CODE_BAD_REQUEST), eq(ERROR_STRING));
    }

    @Test
    public void testErrorPathLoadFastPairEligibleAccounts() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().loadFastPairEligibleAccounts(
                FAST_PAIR_ELIGIBLE_ACCOUNTS_REQUEST_PARCEL,
                mEligibleAccountsCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairEligibleAccounts(
                any(FastPairDataProviderBase.FastPairEligibleAccountsRequest.class),
                any(FastPairDataProviderBase.FastPairEligibleAccountsCallback.class));
        verify(mEligibleAccountsCallback).onError(
                eq(ERROR_CODE_BAD_REQUEST), eq(ERROR_STRING));
    }

    @Test
    public void testErrorPathManageFastPairAccount() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().manageFastPairAccount(
                FAST_PAIR_MANAGE_ACCOUNT_REQUEST_PARCEL,
                mManageAccountCallback);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccount(
                any(FastPairDataProviderBase.FastPairManageAccountRequest.class),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        verify(mManageAccountCallback).onError(eq(ERROR_CODE_BAD_REQUEST), eq(ERROR_STRING));
    }

    @Test
    public void testErrorPathManageFastPairAccountDevice() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().manageFastPairAccountDevice(
                FAST_PAIR_MANAGE_ACCOUNT_DEVICE_REQUEST_PARCEL,
                mManageAccountDeviceCallback);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccountDevice(
                any(FastPairDataProviderBase.FastPairManageAccountDeviceRequest.class),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        verify(mManageAccountDeviceCallback).onError(eq(ERROR_CODE_BAD_REQUEST), eq(ERROR_STRING));
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
                    HAPPY_PATH_FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA);
        }

        @Override
        public void onLoadFastPairAccountDevicesMetadata(
                @NonNull FastPairAccountDevicesMetadataRequest request,
                @NonNull FastPairAccountDevicesMetadataCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairAccountDevicesMetadata(
                    request, callback);
            callback.onFastPairAccountDevicesMetadataReceived(FAST_PAIR_ACCOUNT_DEVICES_METADATA);
        }

        @Override
        public void onLoadFastPairEligibleAccounts(
                @NonNull FastPairEligibleAccountsRequest request,
                @NonNull FastPairEligibleAccountsCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairEligibleAccounts(
                    request, callback);
            callback.onFastPairEligibleAccountsReceived(ELIGIBLE_ACCOUNTS);
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

    /* Generates AntispoofkeyDeviceMetadataRequestParcel. */
    private static FastPairAntispoofkeyDeviceMetadataRequestParcel
            genFastPairAntispoofkeyDeviceMetadataRequestParcel() {
        FastPairAntispoofkeyDeviceMetadataRequestParcel requestParcel =
                new FastPairAntispoofkeyDeviceMetadataRequestParcel();
        requestParcel.modelId = REQUEST_MODEL_ID;

        return requestParcel;
    }

    /* Generates AccountDevicesMetadataRequestParcel. */
    private static FastPairAccountDevicesMetadataRequestParcel
            genFastPairAccountDevicesMetadataRequestParcel() {
        FastPairAccountDevicesMetadataRequestParcel requestParcel =
                new FastPairAccountDevicesMetadataRequestParcel();

        requestParcel.account = ACCOUNTDEVICES_METADATA_ACCOUNT;

        return requestParcel;
    }

    /* Generates FastPairEligibleAccountsRequestParcel. */
    private static FastPairEligibleAccountsRequestParcel
            genFastPairEligibleAccountsRequestParcel() {
        FastPairEligibleAccountsRequestParcel requestParcel =
                new FastPairEligibleAccountsRequestParcel();
        // No fields since FastPairEligibleAccountsRequestParcel is just a place holder now.
        return requestParcel;
    }

    /* Generates FastPairManageAccountRequestParcel. */
    private static FastPairManageAccountRequestParcel
            genFastPairManageAccountRequestParcel() {
        FastPairManageAccountRequestParcel requestParcel =
                new FastPairManageAccountRequestParcel();
        requestParcel.account = MANAGE_ACCOUNT;
        requestParcel.requestType = MANAGE_ACCOUNT_REQUEST_TYPE;

        return requestParcel;
    }

    /* Generates FastPairManageAccountDeviceRequestParcel. */
    private static FastPairManageAccountDeviceRequestParcel
            genFastPairManageAccountDeviceRequestParcel() {
        FastPairManageAccountDeviceRequestParcel requestParcel =
                new FastPairManageAccountDeviceRequestParcel();
        requestParcel.account = MANAGE_ACCOUNT;
        requestParcel.requestType = MANAGE_ACCOUNT_REQUEST_TYPE;
        requestParcel.bleAddress = BLE_ADDRESS;
        requestParcel.accountKeyDeviceMetadata =
                genHappyPathFastPairAccountkeyDeviceMetadataParcel();

        return requestParcel;
    }

    /* Generates Happy Path AntispoofkeyDeviceMetadata. */
    private static FastPairAntispoofkeyDeviceMetadata
            genHappyPathFastPairAntispoofkeyDeviceMetadata() {
        FastPairAntispoofkeyDeviceMetadata.Builder builder =
                new FastPairAntispoofkeyDeviceMetadata.Builder();
        builder.setAntiSpoofPublicKey(ANTI_SPOOFING_KEY);
        builder.setFastPairDeviceMetadata(genHappyPathFastPairDeviceMetadata());

        return builder.build();
    }

    /* Generates Happy Path FastPairAccountKeyDeviceMetadata. */
    private static FastPairAccountKeyDeviceMetadata
            genHappyPathFastPairAccountkeyDeviceMetadata() {
        FastPairAccountKeyDeviceMetadata.Builder builder =
                new FastPairAccountKeyDeviceMetadata.Builder();
        builder.setAccountKey(ACCOUNT_KEY);
        builder.setFastPairDeviceMetadata(genHappyPathFastPairDeviceMetadata());
        builder.setSha256AccountKeyPublicAddress(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS);
        builder.setFastPairDiscoveryItem(genHappyPathFastPairDiscoveryItem());

        return builder.build();
    }

    /* Generates Happy Path FastPairAccountKeyDeviceMetadataParcel. */
    private static FastPairAccountKeyDeviceMetadataParcel
            genHappyPathFastPairAccountkeyDeviceMetadataParcel() {
        FastPairAccountKeyDeviceMetadataParcel parcel =
                new FastPairAccountKeyDeviceMetadataParcel();
        parcel.accountKey = ACCOUNT_KEY;
        parcel.metadata = genHappyPathFastPairDeviceMetadataParcel();
        parcel.sha256AccountKeyPublicAddress = SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS;
        parcel.discoveryItem = genHappyPathFastPairDiscoveryItemParcel();

        return parcel;
    }

    /* Generates Happy Path DiscoveryItem. */
    private static FastPairDiscoveryItem genHappyPathFastPairDiscoveryItem() {
        FastPairDiscoveryItem.Builder builder = new FastPairDiscoveryItem.Builder();

        builder.setActionUrl(ACTION_URL);
        builder.setActionUrlType(ACTION_URL_TYPE);
        builder.setAppName(APP_NAME);
        builder.setAttachmentType(ATTACHMENT_TYPE);
        builder.setAuthenticationPublicKeySecp256r1(AUTHENTICATION_PUBLIC_KEY_SEC_P256R1);
        builder.setBleRecordBytes(BLE_RECORD_BYTES);
        builder.setDebugCategory(DEBUG_CATEGORY);
        builder.setDebugMessage(DEBUG_MESSAGE);
        builder.setDescription(DESCRIPTION);
        builder.setDeviceName(DEVICE_NAME);
        builder.setDisplayUrl(DISPLAY_URL);
        builder.setEntityId(ENTITY_ID);
        builder.setFeatureGraphicUrl(FEATURE_GRAPHIC_URL);
        builder.setFirstObservationTimestampMillis(FIRST_OBSERVATION_TIMESTAMP_MILLIS);
        builder.setGroupId(GROUP_ID);
        builder.setIconFfeUrl(ICON_FIFE_URL);
        builder.setIconPng(ICON_PNG);
        builder.setId(ID);
        builder.setLastObservationTimestampMillis(LAST_OBSERVATION_TIMESTAMP_MILLIS);
        builder.setLastUserExperience(LAST_USER_EXPERIENCE);
        builder.setLostMillis(LOST_MILLIS);
        builder.setMacAddress(MAC_ADDRESS);
        builder.setPackageName(PACKAGE_NAME);
        builder.setPendingAppInstallTimestampMillis(PENDING_APP_INSTALL_TIMESTAMP_MILLIS);
        builder.setRssi(RSSI);
        builder.setState(STATE);
        builder.setTitle(TITLE);
        builder.setTriggerId(TRIGGER_ID);
        builder.setTxPower(TX_POWER);
        builder.setType(TYPE);

        return builder.build();
    }

    /* Generates Happy Path DiscoveryItemParcel. */
    private static FastPairDiscoveryItemParcel genHappyPathFastPairDiscoveryItemParcel() {
        FastPairDiscoveryItemParcel parcel = new FastPairDiscoveryItemParcel();

        parcel.actionUrl = ACTION_URL;
        parcel.actionUrlType = ACTION_URL_TYPE;
        parcel.appName = APP_NAME;
        parcel.attachmentType = ATTACHMENT_TYPE;
        parcel.authenticationPublicKeySecp256r1 = AUTHENTICATION_PUBLIC_KEY_SEC_P256R1;
        parcel.bleRecordBytes = BLE_RECORD_BYTES;
        parcel.debugCategory = DEBUG_CATEGORY;
        parcel.debugMessage = DEBUG_MESSAGE;
        parcel.description = DESCRIPTION;
        parcel.deviceName = DEVICE_NAME;
        parcel.displayUrl = DISPLAY_URL;
        parcel.entityId = ENTITY_ID;
        parcel.featureGraphicUrl = FEATURE_GRAPHIC_URL;
        parcel.firstObservationTimestampMillis = FIRST_OBSERVATION_TIMESTAMP_MILLIS;
        parcel.groupId = GROUP_ID;
        parcel.iconFifeUrl = ICON_FIFE_URL;
        parcel.iconPng = ICON_PNG;
        parcel.id = ID;
        parcel.lastObservationTimestampMillis = LAST_OBSERVATION_TIMESTAMP_MILLIS;
        parcel.lastUserExperience = LAST_USER_EXPERIENCE;
        parcel.lostMillis = LOST_MILLIS;
        parcel.macAddress = MAC_ADDRESS;
        parcel.packageName = PACKAGE_NAME;
        parcel.pendingAppInstallTimestampMillis = PENDING_APP_INSTALL_TIMESTAMP_MILLIS;
        parcel.rssi = RSSI;
        parcel.state = STATE;
        parcel.title = TITLE;
        parcel.triggerId = TRIGGER_ID;
        parcel.txPower = TX_POWER;
        parcel.type = TYPE;

        return parcel;
    }

    /* Generates Happy Path DeviceMetadata. */
    private static FastPairDeviceMetadata genHappyPathFastPairDeviceMetadata() {
        FastPairDeviceMetadata.Builder builder = new FastPairDeviceMetadata.Builder();
        builder.setAssistantSetupHalfSheet(ASSISTANT_SETUP_HALFSHEET);
        builder.setAssistantSetupNotification(ASSISTANT_SETUP_NOTIFICATION);
        builder.setBleTxPower(BLE_TX_POWER);
        builder.setConfirmPinDescription(CONFIRM_PIN_DESCRIPTION);
        builder.setConfirmPinTitle(CONFIRM_PIN_TITLE);
        builder.setConnectSuccessCompanionAppInstalled(CONNECT_SUCCESS_COMPANION_APP_INSTALLED);
        builder.setConnectSuccessCompanionAppNotInstalled(
                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED);
        builder.setDeviceType(DEVICE_TYPE);
        builder.setDownloadCompanionAppDescription(DOWNLOAD_COMPANION_APP_DESCRIPTION);
        builder.setFailConnectGoToSettingsDescription(FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION);
        builder.setFastPairTvConnectDeviceNoAccountDescription(
                FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION);
        builder.setImage(IMAGE);
        builder.setImageUrl(IMAGE_URL);
        builder.setInitialNotificationDescription(INITIAL_NOTIFICATION_DESCRIPTION);
        builder.setInitialNotificationDescriptionNoAccount(
                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT);
        builder.setInitialPairingDescription(INITIAL_PAIRING_DESCRIPTION);
        builder.setIntentUri(INTENT_URI);
        builder.setLocale(LOCALE);
        builder.setOpenCompanionAppDescription(OPEN_COMPANION_APP_DESCRIPTION);
        builder.setRetroactivePairingDescription(RETRO_ACTIVE_PAIRING_DESCRIPTION);
        builder.setSubsequentPairingDescription(SUBSEQUENT_PAIRING_DESCRIPTION);
        builder.setSyncContactsDescription(SYNC_CONTACT_DESCRPTION);
        builder.setSyncContactsTitle(SYNC_CONTACTS_TITLE);
        builder.setSyncSmsDescription(SYNC_SMS_DESCRIPTION);
        builder.setSyncSmsTitle(SYNC_SMS_TITLE);
        builder.setTriggerDistance(TRIGGER_DISTANCE);
        builder.setTrueWirelessImageUrlCase(TRUE_WIRELESS_IMAGE_URL_CASE);
        builder.setTrueWirelessImageUrlLeftBud(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD);
        builder.setTrueWirelessImageUrlRightBud(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD);
        builder.setUnableToConnectDescription(UNABLE_TO_CONNECT_DESCRIPTION);
        builder.setUnableToConnectTitle(UNABLE_TO_CONNECT_TITLE);
        builder.setUpdateCompanionAppDescription(UPDATE_COMPANION_APP_DESCRIPTION);
        builder.setWaitLaunchCompanionAppDescription(WAIT_LAUNCH_COMPANION_APP_DESCRIPTION);

        return builder.build();
    }

    /* Generates Happy Path DeviceMetadataParcel. */
    private static FastPairDeviceMetadataParcel genHappyPathFastPairDeviceMetadataParcel() {
        FastPairDeviceMetadataParcel parcel = new FastPairDeviceMetadataParcel();

        parcel.assistantSetupHalfSheet = ASSISTANT_SETUP_HALFSHEET;
        parcel.assistantSetupNotification = ASSISTANT_SETUP_NOTIFICATION;
        parcel.bleTxPower = BLE_TX_POWER;
        parcel.confirmPinDescription = CONFIRM_PIN_DESCRIPTION;
        parcel.confirmPinTitle = CONFIRM_PIN_TITLE;
        parcel.connectSuccessCompanionAppInstalled = CONNECT_SUCCESS_COMPANION_APP_INSTALLED;
        parcel.connectSuccessCompanionAppNotInstalled =
                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED;
        parcel.deviceType = DEVICE_TYPE;
        parcel.downloadCompanionAppDescription = DOWNLOAD_COMPANION_APP_DESCRIPTION;
        parcel.failConnectGoToSettingsDescription = FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION;
        parcel.fastPairTvConnectDeviceNoAccountDescription =
                FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION;
        parcel.image = IMAGE;
        parcel.imageUrl = IMAGE_URL;
        parcel.initialNotificationDescription = INITIAL_NOTIFICATION_DESCRIPTION;
        parcel.initialNotificationDescriptionNoAccount =
                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT;
        parcel.initialPairingDescription = INITIAL_PAIRING_DESCRIPTION;
        parcel.intentUri = INTENT_URI;
        parcel.locale = LOCALE;
        parcel.openCompanionAppDescription = OPEN_COMPANION_APP_DESCRIPTION;
        parcel.retroactivePairingDescription = RETRO_ACTIVE_PAIRING_DESCRIPTION;
        parcel.subsequentPairingDescription = SUBSEQUENT_PAIRING_DESCRIPTION;
        parcel.syncContactsDescription = SYNC_CONTACT_DESCRPTION;
        parcel.syncContactsTitle = SYNC_CONTACTS_TITLE;
        parcel.syncSmsDescription = SYNC_SMS_DESCRIPTION;
        parcel.syncSmsTitle = SYNC_SMS_TITLE;
        parcel.triggerDistance = TRIGGER_DISTANCE;
        parcel.trueWirelessImageUrlCase = TRUE_WIRELESS_IMAGE_URL_CASE;
        parcel.trueWirelessImageUrlLeftBud = TRUE_WIRELESS_IMAGE_URL_LEFT_BUD;
        parcel.trueWirelessImageUrlRightBud = TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD;
        parcel.unableToConnectDescription = UNABLE_TO_CONNECT_DESCRIPTION;
        parcel.unableToConnectTitle = UNABLE_TO_CONNECT_TITLE;
        parcel.updateCompanionAppDescription = UPDATE_COMPANION_APP_DESCRIPTION;
        parcel.waitLaunchCompanionAppDescription = WAIT_LAUNCH_COMPANION_APP_DESCRIPTION;

        return parcel;
    }

    /* Generates Happy Path FastPairEligibleAccount. */
    private static FastPairEligibleAccount genHappyPathFastPairEligibleAccount(
            Account account, boolean optIn) {
        FastPairEligibleAccount.Builder builder = new FastPairEligibleAccount.Builder();
        builder.setAccount(account);
        builder.setOptIn(optIn);

        return builder.build();
    }

    /* Verifies Happy Path AntispoofkeyDeviceMetadataRequest. */
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest request) {
        assertEquals(REQUEST_MODEL_ID, request.getModelId());
    }

    /* Verifies Happy Path AccountDevicesMetadataRequest. */
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairAccountDevicesMetadataRequest request) {
        assertEquals(ACCOUNTDEVICES_METADATA_ACCOUNT, request.getAccount());
    }

    /* Verifies Happy Path FastPairEligibleAccountsRequest. */
    @SuppressWarnings("UnusedVariable")
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairEligibleAccountsRequest request) {
        // No fields since FastPairEligibleAccountsRequest is just a place holder now.
    }

    /* Verifies Happy Path FastPairManageAccountRequest. */
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairManageAccountRequest request) {
        assertEquals(MANAGE_ACCOUNT, request.getAccount());
        assertEquals(MANAGE_ACCOUNT_REQUEST_TYPE, request.getRequestType());
    }

    /* Verifies Happy Path FastPairManageAccountDeviceRequest. */
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairManageAccountDeviceRequest request) {
        assertEquals(MANAGE_ACCOUNT, request.getAccount());
        assertEquals(MANAGE_ACCOUNT_REQUEST_TYPE, request.getRequestType());
        assertEquals(BLE_ADDRESS, request.getBleAddress());
        ensureHappyPathAsExpected(request.getAccountKeyDeviceMetadata());
    }

    /* Verifies Happy Path AntispoofkeyDeviceMetadataParcel. */
    private static void ensureHappyPathAsExpected(
            FastPairAntispoofkeyDeviceMetadataParcel metadataParcel) {
        assertNotNull(metadataParcel);
        assertEquals(ANTI_SPOOFING_KEY, metadataParcel.antiSpoofPublicKey);
        ensureHappyPathAsExpected(metadataParcel.deviceMetadata);
    }

    /* Verifies Happy Path FastPairAccountKeyDeviceMetadataParcel[]. */
    private static void ensureHappyPathAsExpected(
            FastPairAccountKeyDeviceMetadataParcel[] metadataParcels) {
        assertNotNull(metadataParcels);
        assertEquals(ACCOUNTKEY_DEVICE_NUM, metadataParcels.length);
        for (FastPairAccountKeyDeviceMetadataParcel parcel: metadataParcels) {
            ensureHappyPathAsExpected(parcel);
        }
    }

    /* Verifies Happy Path FastPairAccountKeyDeviceMetadataParcel. */
    private static void ensureHappyPathAsExpected(
            FastPairAccountKeyDeviceMetadataParcel metadataParcel) {
        assertNotNull(metadataParcel);
        assertEquals(ACCOUNT_KEY, metadataParcel.accountKey);
        assertEquals(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS,
                metadataParcel.sha256AccountKeyPublicAddress);
        ensureHappyPathAsExpected(metadataParcel.metadata);
        ensureHappyPathAsExpected(metadataParcel.discoveryItem);
    }

    /* Verifies Happy Path FastPairAccountKeyDeviceMetadata. */
    private static void ensureHappyPathAsExpected(
            FastPairAccountKeyDeviceMetadata metadata) {
        assertEquals(ACCOUNT_KEY, metadata.getAccountKey());
        assertEquals(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS,
                metadata.getSha256AccountKeyPublicAddress());
        ensureHappyPathAsExpected(metadata.getFastPairDeviceMetadata());
        ensureHappyPathAsExpected(metadata.getFastPairDiscoveryItem());
    }

    /* Verifies Happy Path DeviceMetadataParcel. */
    private static void ensureHappyPathAsExpected(FastPairDeviceMetadataParcel metadataParcel) {
        assertNotNull(metadataParcel);
        assertEquals(ASSISTANT_SETUP_HALFSHEET, metadataParcel.assistantSetupHalfSheet);
        assertEquals(ASSISTANT_SETUP_NOTIFICATION, metadataParcel.assistantSetupNotification);
        assertEquals(BLE_TX_POWER, metadataParcel.bleTxPower);
        assertEquals(CONFIRM_PIN_DESCRIPTION, metadataParcel.confirmPinDescription);
        assertEquals(CONFIRM_PIN_TITLE, metadataParcel.confirmPinTitle);
        assertEquals(CONNECT_SUCCESS_COMPANION_APP_INSTALLED,
                metadataParcel.connectSuccessCompanionAppInstalled);
        assertEquals(CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED,
                metadataParcel.connectSuccessCompanionAppNotInstalled);
        assertEquals(DEVICE_TYPE, metadataParcel.deviceType);
        assertEquals(DOWNLOAD_COMPANION_APP_DESCRIPTION,
                metadataParcel.downloadCompanionAppDescription);
        assertEquals(FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION,
                metadataParcel.failConnectGoToSettingsDescription);
        assertEquals(FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION,
                metadataParcel.fastPairTvConnectDeviceNoAccountDescription);
        assertArrayEquals(IMAGE, metadataParcel.image);
        assertEquals(IMAGE_URL, metadataParcel.imageUrl);
        assertEquals(INITIAL_NOTIFICATION_DESCRIPTION,
                metadataParcel.initialNotificationDescription);
        assertEquals(INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT,
                metadataParcel.initialNotificationDescriptionNoAccount);
        assertEquals(INITIAL_PAIRING_DESCRIPTION, metadataParcel.initialPairingDescription);
        assertEquals(INTENT_URI, metadataParcel.intentUri);
        assertEquals(LOCALE, metadataParcel.locale);
        assertEquals(OPEN_COMPANION_APP_DESCRIPTION, metadataParcel.openCompanionAppDescription);
        assertEquals(RETRO_ACTIVE_PAIRING_DESCRIPTION,
                metadataParcel.retroactivePairingDescription);
        assertEquals(SUBSEQUENT_PAIRING_DESCRIPTION, metadataParcel.subsequentPairingDescription);
        assertEquals(SYNC_CONTACT_DESCRPTION, metadataParcel.syncContactsDescription);
        assertEquals(SYNC_CONTACTS_TITLE, metadataParcel.syncContactsTitle);
        assertEquals(SYNC_SMS_DESCRIPTION, metadataParcel.syncSmsDescription);
        assertEquals(SYNC_SMS_TITLE, metadataParcel.syncSmsTitle);
        assertEquals(TRIGGER_DISTANCE, metadataParcel.triggerDistance, DELTA);
        assertEquals(TRUE_WIRELESS_IMAGE_URL_CASE, metadataParcel.trueWirelessImageUrlCase);
        assertEquals(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD, metadataParcel.trueWirelessImageUrlLeftBud);
        assertEquals(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD,
                metadataParcel.trueWirelessImageUrlRightBud);
        assertEquals(UNABLE_TO_CONNECT_DESCRIPTION, metadataParcel.unableToConnectDescription);
        assertEquals(UNABLE_TO_CONNECT_TITLE, metadataParcel.unableToConnectTitle);
        assertEquals(UPDATE_COMPANION_APP_DESCRIPTION,
                metadataParcel.updateCompanionAppDescription);
        assertEquals(WAIT_LAUNCH_COMPANION_APP_DESCRIPTION,
                metadataParcel.waitLaunchCompanionAppDescription);
    }

    /* Verifies Happy Path DeviceMetadata. */
    private static void ensureHappyPathAsExpected(FastPairDeviceMetadata metadata) {
        assertEquals(ASSISTANT_SETUP_HALFSHEET, metadata.getAssistantSetupHalfSheet());
        assertEquals(ASSISTANT_SETUP_NOTIFICATION, metadata.getAssistantSetupNotification());
        assertEquals(BLE_TX_POWER, metadata.getBleTxPower());
        assertEquals(CONFIRM_PIN_DESCRIPTION, metadata.getConfirmPinDescription());
        assertEquals(CONFIRM_PIN_TITLE, metadata.getConfirmPinTitle());
        assertEquals(CONNECT_SUCCESS_COMPANION_APP_INSTALLED,
                metadata.getConnectSuccessCompanionAppInstalled());
        assertEquals(CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED,
                metadata.getConnectSuccessCompanionAppNotInstalled());
        assertEquals(DEVICE_TYPE, metadata.getDeviceType());
        assertEquals(DOWNLOAD_COMPANION_APP_DESCRIPTION,
                metadata.getDownloadCompanionAppDescription());
        assertEquals(FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION,
                metadata.getFailConnectGoToSettingsDescription());
        assertEquals(FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION,
                metadata.getFastPairTvConnectDeviceNoAccountDescription());
        assertArrayEquals(IMAGE, metadata.getImage());
        assertEquals(IMAGE_URL, metadata.getImageUrl());
        assertEquals(INITIAL_NOTIFICATION_DESCRIPTION,
                metadata.getInitialNotificationDescription());
        assertEquals(INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT,
                metadata.getInitialNotificationDescriptionNoAccount());
        assertEquals(INITIAL_PAIRING_DESCRIPTION, metadata.getInitialPairingDescription());
        assertEquals(INTENT_URI, metadata.getIntentUri());
        assertEquals(LOCALE, metadata.getLocale());
        assertEquals(OPEN_COMPANION_APP_DESCRIPTION, metadata.getOpenCompanionAppDescription());
        assertEquals(RETRO_ACTIVE_PAIRING_DESCRIPTION,
                metadata.getRetroactivePairingDescription());
        assertEquals(SUBSEQUENT_PAIRING_DESCRIPTION, metadata.getSubsequentPairingDescription());
        assertEquals(SYNC_CONTACT_DESCRPTION, metadata.getSyncContactsDescription());
        assertEquals(SYNC_CONTACTS_TITLE, metadata.getSyncContactsTitle());
        assertEquals(SYNC_SMS_DESCRIPTION, metadata.getSyncSmsDescription());
        assertEquals(SYNC_SMS_TITLE, metadata.getSyncSmsTitle());
        assertEquals(TRIGGER_DISTANCE, metadata.getTriggerDistance(), DELTA);
        assertEquals(TRUE_WIRELESS_IMAGE_URL_CASE, metadata.getTrueWirelessImageUrlCase());
        assertEquals(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD, metadata.getTrueWirelessImageUrlLeftBud());
        assertEquals(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD,
                metadata.getTrueWirelessImageUrlRightBud());
        assertEquals(UNABLE_TO_CONNECT_DESCRIPTION, metadata.getUnableToConnectDescription());
        assertEquals(UNABLE_TO_CONNECT_TITLE, metadata.getUnableToConnectTitle());
        assertEquals(UPDATE_COMPANION_APP_DESCRIPTION,
                metadata.getUpdateCompanionAppDescription());
        assertEquals(WAIT_LAUNCH_COMPANION_APP_DESCRIPTION,
                metadata.getWaitLaunchCompanionAppDescription());
    }

    /* Verifies Happy Path FastPairDiscoveryItemParcel. */
    private static void ensureHappyPathAsExpected(FastPairDiscoveryItemParcel itemParcel) {
        assertEquals(ACTION_URL, itemParcel.actionUrl);
        assertEquals(ACTION_URL_TYPE, itemParcel.actionUrlType);
        assertEquals(APP_NAME, itemParcel.appName);
        assertEquals(ATTACHMENT_TYPE, itemParcel.attachmentType);
        assertArrayEquals(AUTHENTICATION_PUBLIC_KEY_SEC_P256R1,
                itemParcel.authenticationPublicKeySecp256r1);
        assertArrayEquals(BLE_RECORD_BYTES, itemParcel.bleRecordBytes);
        assertEquals(DEBUG_CATEGORY, itemParcel.debugCategory);
        assertEquals(DEBUG_MESSAGE, itemParcel.debugMessage);
        assertEquals(DESCRIPTION, itemParcel.description);
        assertEquals(DEVICE_NAME, itemParcel.deviceName);
        assertEquals(DISPLAY_URL, itemParcel.displayUrl);
        assertEquals(ENTITY_ID, itemParcel.entityId);
        assertEquals(FEATURE_GRAPHIC_URL, itemParcel.featureGraphicUrl);
        assertEquals(FIRST_OBSERVATION_TIMESTAMP_MILLIS,
                itemParcel.firstObservationTimestampMillis);
        assertEquals(GROUP_ID, itemParcel.groupId);
        assertEquals(ICON_FIFE_URL, itemParcel.iconFifeUrl);
        assertEquals(ICON_PNG, itemParcel.iconPng);
        assertEquals(ID, itemParcel.id);
        assertEquals(LAST_OBSERVATION_TIMESTAMP_MILLIS, itemParcel.lastObservationTimestampMillis);
        assertEquals(LAST_USER_EXPERIENCE, itemParcel.lastUserExperience);
        assertEquals(LOST_MILLIS, itemParcel.lostMillis);
        assertEquals(MAC_ADDRESS, itemParcel.macAddress);
        assertEquals(PACKAGE_NAME, itemParcel.packageName);
        assertEquals(PENDING_APP_INSTALL_TIMESTAMP_MILLIS,
                itemParcel.pendingAppInstallTimestampMillis);
        assertEquals(RSSI, itemParcel.rssi);
        assertEquals(STATE, itemParcel.state);
        assertEquals(TITLE, itemParcel.title);
        assertEquals(TRIGGER_ID, itemParcel.triggerId);
        assertEquals(TX_POWER, itemParcel.txPower);
        assertEquals(TYPE, itemParcel.type);
    }

    /* Verifies Happy Path FastPairDiscoveryItem. */
    private static void ensureHappyPathAsExpected(FastPairDiscoveryItem item) {
        assertEquals(ACTION_URL, item.getActionUrl());
        assertEquals(ACTION_URL_TYPE, item.getActionUrlType());
        assertEquals(APP_NAME, item.getAppName());
        assertEquals(ATTACHMENT_TYPE, item.getAttachmentType());
        assertArrayEquals(AUTHENTICATION_PUBLIC_KEY_SEC_P256R1,
                item.getAuthenticationPublicKeySecp256r1());
        assertArrayEquals(BLE_RECORD_BYTES, item.getBleRecordBytes());
        assertEquals(DEBUG_CATEGORY, item.getDebugCategory());
        assertEquals(DEBUG_MESSAGE, item.getDebugMessage());
        assertEquals(DESCRIPTION, item.getDescription());
        assertEquals(DEVICE_NAME, item.getDeviceName());
        assertEquals(DISPLAY_URL, item.getDisplayUrl());
        assertEquals(ENTITY_ID, item.getEntityId());
        assertEquals(FEATURE_GRAPHIC_URL, item.getFeatureGraphicUrl());
        assertEquals(FIRST_OBSERVATION_TIMESTAMP_MILLIS,
                item.getFirstObservationTimestampMillis());
        assertEquals(GROUP_ID, item.getGroupId());
        assertEquals(ICON_FIFE_URL, item.getIconFfeUrl());
        assertEquals(ICON_PNG, item.getIconPng());
        assertEquals(ID, item.getId());
        assertEquals(LAST_OBSERVATION_TIMESTAMP_MILLIS, item.getLastObservationTimestampMillis());
        assertEquals(LAST_USER_EXPERIENCE, item.getLastUserExperience());
        assertEquals(LOST_MILLIS, item.getLostMillis());
        assertEquals(MAC_ADDRESS, item.getMacAddress());
        assertEquals(PACKAGE_NAME, item.getPackageName());
        assertEquals(PENDING_APP_INSTALL_TIMESTAMP_MILLIS,
                item.getPendingAppInstallTimestampMillis());
        assertEquals(RSSI, item.getRssi());
        assertEquals(STATE, item.getState());
        assertEquals(TITLE, item.getTitle());
        assertEquals(TRIGGER_ID, item.getTriggerId());
        assertEquals(TX_POWER, item.getTxPower());
        assertEquals(TYPE, item.getType());
    }

    /* Verifies Happy Path EligibleAccountParcel[]. */
    private static void ensureHappyPathAsExpected(FastPairEligibleAccountParcel[] accountsParcel) {
        assertEquals(ELIGIBLE_ACCOUNTS_NUM, accountsParcel.length);
        assertEquals(ELIGIBLE_ACCOUNT_1, accountsParcel[0].account);
        assertEquals(ELIGIBLE_ACCOUNT_1_OPT_IN, accountsParcel[0].optIn);

        assertEquals(ELIGIBLE_ACCOUNT_2, accountsParcel[1].account);
        assertEquals(ELIGIBLE_ACCOUNT_2_OPT_IN, accountsParcel[1].optIn);
    }
}
