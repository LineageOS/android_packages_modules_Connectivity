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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.accounts.Account;
import android.nearby.FastPairAntispoofkeyDeviceMetadata;
import android.nearby.FastPairDataProviderBase;
import android.nearby.FastPairDeviceMetadata;
import android.nearby.FastPairEligibleAccount;
import android.nearby.aidl.FastPairAccountDevicesMetadataRequestParcel;
import android.nearby.aidl.FastPairAccountKeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataRequestParcel;
import android.nearby.aidl.FastPairDeviceMetadataParcel;
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
    private static final int ELIGIBLE_ACCOUNTS_NUM = 2;
    private static final Account ELIGIBLE_ACCOUNT_1 = new Account("abc@google.com", "type1");
    private static final boolean ELIGIBLE_ACCOUNT_1_OPT_IN = true;
    private static final Account ELIGIBLE_ACCOUNT_2 = new Account("def@gmail.com", "type2");
    private static final boolean ELIGIBLE_ACCOUNT_2_OPT_IN = false;
    private static final ImmutableList<FastPairEligibleAccount> ELIGIBLE_ACCOUNTS =
            ImmutableList.of(
                    genHappyPathFastPairEligibleAccount(ELIGIBLE_ACCOUNT_1,
                            ELIGIBLE_ACCOUNT_1_OPT_IN),
                    genHappyPathFastPairEligibleAccount(ELIGIBLE_ACCOUNT_2,
                            ELIGIBLE_ACCOUNT_2_OPT_IN));
    private static final int ERROR_CODE_BAD_REQUEST =
            FastPairDataProviderBase.ERROR_CODE_BAD_REQUEST;
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
    private static final byte[] REQUEST_MODEL_ID = new byte[] {1, 2, 3};
    private static final byte[] ANTI_SPOOFING_KEY = new byte[] {4, 5, 6};
    private static final FastPairAntispoofkeyDeviceMetadataRequestParcel
            FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA_REQUEST_PARCEL =
            genFastPairAntispoofkeyDeviceMetadataRequestParcel();
    private static final FastPairEligibleAccountsRequestParcel
            FAST_PAIR_ELIGIBLE_ACCOUNTS_REQUEST_PARCEL =
            genFastPairEligibleAccountsRequestParcel();
    private static final FastPairAntispoofkeyDeviceMetadata
            HAPPY_PATH_FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA =
            genHappyPathFastPairAntispoofkeyDeviceMetadata();

    private @Captor ArgumentCaptor<FastPairEligibleAccountParcel[]>
            mFastPairEligibleAccountParcelsArgumentCaptor;

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
                mFastPairAntispoofkeyDeviceMetadataRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest.class);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAntispoofkeyDeviceMetadata(
                mFastPairAntispoofkeyDeviceMetadataRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataCallback.class));
        ensureHappyPathAsExpected(mFastPairAntispoofkeyDeviceMetadataRequestCaptor.getValue());

        // AOSP receives responses and verifies that it is as expected.
        final ArgumentCaptor<FastPairAntispoofkeyDeviceMetadataParcel>
                mFastPairAntispoofkeyDeviceMetadataParcelCaptor =
                ArgumentCaptor.forClass(FastPairAntispoofkeyDeviceMetadataParcel.class);
        verify(mAntispoofkeyDeviceMetadataCallback).onFastPairAntispoofkeyDeviceMetadataReceived(
                mFastPairAntispoofkeyDeviceMetadataParcelCaptor.capture());
        ensureHappyPathAsExpected(mFastPairAntispoofkeyDeviceMetadataParcelCaptor.getValue());
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
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().loadFastPairEligibleAccounts(
                FAST_PAIR_ELIGIBLE_ACCOUNTS_REQUEST_PARCEL,
                mEligibleAccountsCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairEligibleAccountsRequest>
                mFastPairEligibleAccountsRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairEligibleAccountsRequest.class);
        verify(mMockFastPairDataProviderBase).onLoadFastPairEligibleAccounts(
                mFastPairEligibleAccountsRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairEligibleAccountsCallback.class));
        ensureHappyPathAsExpected(mFastPairEligibleAccountsRequestCaptor.getValue());

        // AOSP receives responses and verifies that it is as expected.
        verify(mEligibleAccountsCallback).onFastPairEligibleAccountsReceived(
                mFastPairEligibleAccountParcelsArgumentCaptor.capture());
        ensureHappyPathAsExpected(mFastPairEligibleAccountParcelsArgumentCaptor.getValue());
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
                    HAPPY_PATH_FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA);
        }

        @Override
        public void onLoadFastPairAccountDevicesMetadata(
                @NonNull FastPairAccountDevicesMetadataRequest request,
                @NonNull FastPairAccountDevicesMetadataCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairAccountDevicesMetadata(
                    request, callback);
            callback.onFastPairAccountDevicesMetadataReceived(ImmutableList.of());
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

    /* Generates FastPairEligibleAccountsRequestParcel. */
    private static FastPairEligibleAccountsRequestParcel
            genFastPairEligibleAccountsRequestParcel() {
        FastPairEligibleAccountsRequestParcel requestParcel =
                new FastPairEligibleAccountsRequestParcel();
        // No fields since FastPairEligibleAccountsRequestParcel is just a place holder now.
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

    @SuppressWarnings("UnusedVariable")
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairEligibleAccountsRequest request) {
        // No fields since FastPairEligibleAccountsRequest is just a place holder now.
    }

    /* Verifies Happy Path AntispoofkeyDeviceMetadataParcel. */
    private static void ensureHappyPathAsExpected(
            FastPairAntispoofkeyDeviceMetadataParcel metadataParcel) {
        assertNotNull(metadataParcel);
        assertEquals(ANTI_SPOOFING_KEY, metadataParcel.antiSpoofPublicKey);
        ensureHappyPathAsExpected(metadataParcel.deviceMetadata);
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

    /* Verifies Happy Path EligibleAccounts. */
    private static void ensureHappyPathAsExpected(FastPairEligibleAccountParcel[] accountsParcel) {
        assertEquals(ELIGIBLE_ACCOUNTS_NUM, accountsParcel.length);
        assertEquals(ELIGIBLE_ACCOUNT_1, accountsParcel[0].account);
        assertEquals(ELIGIBLE_ACCOUNT_1_OPT_IN, accountsParcel[0].optIn);

        assertEquals(ELIGIBLE_ACCOUNT_2, accountsParcel[1].account);
        assertEquals(ELIGIBLE_ACCOUNT_2_OPT_IN, accountsParcel[1].optIn);
    }
}
