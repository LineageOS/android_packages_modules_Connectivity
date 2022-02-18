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

package android.nearby.multidevices.fastpair.seeker

import android.accounts.Account
import android.nearby.*
import android.util.Log
import service.proto.Cache
import service.proto.Rpcs.DeviceType
import java.util.*

class FastPairTestDataProvider : FastPairDataProviderBase(TAG) {
    private val fastPairDeviceMetadata = FastPairDeviceMetadata.Builder()
        .setAssistantSetupHalfSheet(ASSISTANT_SETUP_HALF_SHEET_TEST_CONSTANT)
        .setAssistantSetupNotification(ASSISTANT_SETUP_NOTIFICATION_TEST_CONSTANT)
        .setBleTxPower(BLE_TX_POWER_TEST_CONSTANT)
        .setConfirmPinDescription(CONFIRM_PIN_DESCRIPTION_TEST_CONSTANT)
        .setConfirmPinTitle(CONFIRM_PIN_TITLE_TEST_CONSTANT)
        .setConnectSuccessCompanionAppInstalled(CONNECT_SUCCESS_COMPANION_APP_INSTALLED_TEST_CONSTANT)
        .setConnectSuccessCompanionAppNotInstalled(CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED_TEST_CONSTANT)
        .setDeviceType(DEVICE_TYPE_HEAD_PHONES_TEST_CONSTANT)
        .setDownloadCompanionAppDescription(DOWNLOAD_COMPANION_APP_DESCRIPTION_TEST_CONSTANT)
        .setFailConnectGoToSettingsDescription(FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION_TEST_CONSTANT)
        .setFastPairTvConnectDeviceNoAccountDescription(TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION_TEST_CONSTANT)
        .setInitialNotificationDescription(INITIAL_NOTIFICATION_DESCRIPTION_TEST_CONSTANT)
        .setInitialNotificationDescriptionNoAccount(INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT_TEST_CONSTANT)
        .setInitialPairingDescription(INITIAL_PAIRING_DESCRIPTION_TEST_CONSTANT)
        .setLocale(LOCALE_US_LANGUAGE_TEST_CONSTANT)
        .setImage(IMAGE_BYTE_ARRAY_FAKE_TEST_CONSTANT)
        .setImageUrl(IMAGE_URL_TEST_CONSTANT)
        .setIntentUri(
            generateCompanionAppLaunchIntentUri(
                companionAppPackageName = COMPANION_APP_PACKAGE_TEST_CONSTANT,
                activityName = COMPANION_APP_ACTIVITY_TEST_CONSTANT,
            )
        )
        .setOpenCompanionAppDescription(OPEN_COMPANION_APP_DESCRIPTION_TEST_CONSTANT)
        .setRetroactivePairingDescription(RETRO_ACTIVE_PAIRING_DESCRIPTION_TEST_CONSTANT)
        .setSubsequentPairingDescription(SUBSEQUENT_PAIRING_DESCRIPTION_TEST_CONSTANT)
        .setSyncContactsDescription(SYNC_CONTACT_DESCRIPTION_TEST_CONSTANT)
        .setSyncContactsTitle(SYNC_CONTACTS_TITLE_TEST_CONSTANT)
        .setSyncSmsDescription(SYNC_SMS_DESCRIPTION_TEST_CONSTANT)
        .setSyncSmsTitle(SYNC_SMS_TITLE_TEST_CONSTANT)
        .setTriggerDistance(TRIGGER_DISTANCE_TEST_CONSTANT)
        .setTrueWirelessImageUrlCase(TRUE_WIRELESS_IMAGE_URL_CASE_TEST_CONSTANT)
        .setTrueWirelessImageUrlLeftBud(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD_TEST_CONSTANT)
        .setTrueWirelessImageUrlRightBud(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD_TEST_CONSTANT)
        .setUnableToConnectDescription(UNABLE_TO_CONNECT_DESCRIPTION_TEST_CONSTANT)
        .setUnableToConnectTitle(UNABLE_TO_CONNECT_TITLE_TEST_CONSTANT)
        .setUpdateCompanionAppDescription(UPDATE_COMPANION_APP_DESCRIPTION_TEST_CONSTANT)
        .setWaitLaunchCompanionAppDescription(WAIT_LAUNCH_COMPANION_APP_DESCRIPTION_TEST_CONSTANT)
        .build()

    override fun onLoadFastPairAntispoofkeyDeviceMetadata(
        request: FastPairAntispoofkeyDeviceMetadataRequest,
        callback: FastPairAntispoofkeyDeviceMetadataCallback
    ) {
        val requestedModelId = request.modelId.bytesToStringLowerCase()
        Log.d(TAG, "onLoadFastPairAntispoofkeyDeviceMetadata(modelId: $requestedModelId)")

        val fastPairAntiSpoofKeyDeviceMetadata =
            FastPairAntispoofkeyDeviceMetadata.Builder()
                .setAntiSpoofPublicKey(ANTI_SPOOF_PUBLIC_KEY_BYTE_ARRAY)
                .setFastPairDeviceMetadata(fastPairDeviceMetadata)
                .build()

        callback.onFastPairAntispoofkeyDeviceMetadataReceived(fastPairAntiSpoofKeyDeviceMetadata)
    }

    override fun onLoadFastPairAccountDevicesMetadata(
        request: FastPairAccountDevicesMetadataRequest,
        callback: FastPairAccountDevicesMetadataCallback
    ) {
        val requestedAccount = request.account
        Log.d(TAG, "onLoadFastPairAccountDevicesMetadata(account: $requestedAccount)")
        val discoveryItem = FastPairDiscoveryItem.Builder()
            .setActionUrl(ACTION_URL_TEST_CONSTANT)
            .setActionUrlType(ACTION_URL_TYPE_TEST_CONSTANT)
            .setAppName(APP_NAME_TEST_CONSTANT)
            .setAttachmentType(ATTACHMENT_TYPE_TEST_CONSTANT)
            .setAuthenticationPublicKeySecp256r1(AUTHENTICATION_PUBLIC_KEY_SEC_P256R1_TEST_CONSTANT)
            .setBleRecordBytes(BLE_RECORD_BYTES_TEST_CONSTANT)
            .setDebugCategory(DEBUG_CATEGORY_TEST_CONSTANT)
            .setDebugMessage(DEBUG_MESSAGE_TEST_CONSTANT)
            .setDescription(DESCRIPTION_TEST_CONSTANT)
            .setDeviceName(DEVICE_NAME_TEST_CONSTANT)
            .setDisplayUrl(DISPLAY_URL_TEST_CONSTANT)
            .setEntityId(ENTITY_ID_TEST_CONSTANT)
            .setFeatureGraphicUrl(FEATURE_GRAPHIC_URL_TEST_CONSTANT)
            .setFirstObservationTimestampMillis(FIRST_OBSERVATION_TIMESTAMP_MILLIS_TEST_CONSTANT)
            .setGroupId(GROUP_ID_TEST_CONSTANT)
            .setIconFfeUrl(ICON_FIFE_URL_TEST_CONSTANT)
            .setIconPng(ICON_PNG_TEST_CONSTANT)
            .setId(ID_TEST_CONSTANT)
            .setLastObservationTimestampMillis(LAST_OBSERVATION_TIMESTAMP_MILLIS_TEST_CONSTANT)
            .setLastUserExperience(LAST_USER_EXPERIENCE_TEST_CONSTANT)
            .setLostMillis(LOST_MILLIS_TEST_CONSTANT)
            .setMacAddress(MAC_ADDRESS_TEST_CONSTANT)
            .setPackageName(PACKAGE_NAME_TEST_CONSTANT)
            .setPendingAppInstallTimestampMillis(PENDING_APP_INSTALL_TIMESTAMP_MILLIS_TEST_CONSTANT)
            .setRssi(RSSI_TEST_CONSTANT)
            .setState(STATE_TEST_CONSTANT)
            .setTitle(TITLE_TEST_CONSTANT)
            .setTriggerId(TRIGGER_ID_TEST_CONSTANT)
            .setTxPower(TX_POWER_TEST_CONSTANT)
            .setType(TYPE_TEST_CONSTANT)
            .build()
        val accountDevicesMetadataList = listOf(
            FastPairAccountKeyDeviceMetadata.Builder()
                .setAccountKey(ACCOUNT_KEY_TEST_CONSTANT)
                .setFastPairDeviceMetadata(fastPairDeviceMetadata)
                .setSha256AccountKeyPublicAddress(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS_TEST_CONSTANT)
                .setFastPairDiscoveryItem(discoveryItem)
                .build()
        )

        callback.onFastPairAccountDevicesMetadataReceived(accountDevicesMetadataList)
    }

    override fun onLoadFastPairEligibleAccounts(
        request: FastPairEligibleAccountsRequest,
        callback: FastPairEligibleAccountsCallback
    ) {
        Log.d(TAG, "onLoadFastPairEligibleAccounts()")
        callback.onFastPairEligibleAccountsReceived(ELIGIBLE_ACCOUNTS_TEST_CONSTANT)
    }

    override fun onManageFastPairAccount(
        request: FastPairManageAccountRequest, callback: FastPairManageActionCallback
    ) {
        val requestedAccount = request.account
        val requestType = request.requestType
        Log.d(TAG, "onManageFastPairAccount(account: $requestedAccount, requestType: $requestType)")

        callback.onSuccess()
    }

    override fun onManageFastPairAccountDevice(
        request: FastPairManageAccountDeviceRequest, callback: FastPairManageActionCallback
    ) {
        val requestedAccount = request.account
        val requestType = request.requestType
        val requestedBleAddress = request.bleAddress
        val requestedAccountKeyDeviceMetadata = request.accountKeyDeviceMetadata
        Log.d(TAG, "onManageFastPairAccountDevice(requestedAccount: $requestedAccount, requestType: $requestType,")
        Log.d(TAG, "requestedBleAddress: $requestedBleAddress,")
        Log.d(TAG, "requestedAccountKeyDeviceMetadata: $requestedAccountKeyDeviceMetadata)")

        callback.onSuccess()
    }

    companion object {
        private const val TAG = "FastPairTestDataProvider"

        private const val BLE_TX_POWER_TEST_CONSTANT = 5
        private const val TRIGGER_DISTANCE_TEST_CONSTANT = 10f
        private const val ACTION_URL_TEST_CONSTANT = "ACTION_URL_TEST_CONSTANT"
        private const val ACTION_URL_TYPE_TEST_CONSTANT = Cache.ResolvedUrlType.APP_VALUE
        private const val APP_NAME_TEST_CONSTANT = "Nearby Mainline Mobly Test Snippet"
        private const val ATTACHMENT_TYPE_TEST_CONSTANT =
            Cache.DiscoveryAttachmentType.DISCOVERY_ATTACHMENT_TYPE_NORMAL_VALUE
        private const val DEBUG_CATEGORY_TEST_CONSTANT =
            Cache.StoredDiscoveryItem.DebugMessageCategory.STATUS_VALID_NOTIFICATION_VALUE
        private const val DEBUG_MESSAGE_TEST_CONSTANT = "DEBUG_MESSAGE_TEST_CONSTANT"
        private const val DESCRIPTION_TEST_CONSTANT = "DESCRIPTION_TEST_CONSTANT"
        private const val DEVICE_NAME_TEST_CONSTANT = "Fast Pair Headphone Simulator"
        private const val DISPLAY_URL_TEST_CONSTANT = "DISPLAY_URL_TEST_CONSTANT"
        private const val ENTITY_ID_TEST_CONSTANT = "ENTITY_ID_TEST_CONSTANT"
        private const val FEATURE_GRAPHIC_URL_TEST_CONSTANT = "FEATURE_GRAPHIC_URL_TEST_CONSTANT"
        private const val FIRST_OBSERVATION_TIMESTAMP_MILLIS_TEST_CONSTANT = 8_393L
        private const val GROUP_ID_TEST_CONSTANT = "GROUP_ID_TEST_CONSTANT"
        private const val ICON_FIFE_URL_TEST_CONSTANT = "ICON_FIFE_URL_TEST_CONSTANT"
        private const val ID_TEST_CONSTANT = "ID_TEST_CONSTANT"
        private const val LAST_OBSERVATION_TIMESTAMP_MILLIS_TEST_CONSTANT = 934_234L
        private const val LAST_USER_EXPERIENCE_TEST_CONSTANT =
            Cache.StoredDiscoveryItem.ExperienceType.EXPERIENCE_GOOD_VALUE
        private const val LOST_MILLIS_TEST_CONSTANT = 393_284L
        private const val MAC_ADDRESS_TEST_CONSTANT = "11:aa:22:bb:34:cd"
        private const val PACKAGE_NAME_TEST_CONSTANT = "android.nearby.package.name.test.constant"
        private const val PENDING_APP_INSTALL_TIMESTAMP_MILLIS_TEST_CONSTANT = 832_393L
        private const val RSSI_TEST_CONSTANT = 9
        private const val STATE_TEST_CONSTANT = Cache.StoredDiscoveryItem.State.STATE_ENABLED_VALUE
        private const val TITLE_TEST_CONSTANT = "TITLE_TEST_CONSTANT"
        private const val TRIGGER_ID_TEST_CONSTANT = "TRIGGER_ID_TEST_CONSTANT"
        private const val TX_POWER_TEST_CONSTANT = 62
        private const val TYPE_TEST_CONSTANT = Cache.NearbyType.NEARBY_DEVICE_VALUE

        private val ANTI_SPOOF_PUBLIC_KEY_BYTE_ARRAY =
            "Cbj9eCJrTdDgSYxLkqtfADQi86vIaMvxJsQ298sZYWE=".toByteArray()
        private val LOCALE_US_LANGUAGE_TEST_CONSTANT = Locale.US.language
        private val IMAGE_BYTE_ARRAY_FAKE_TEST_CONSTANT = byteArrayOf(7, 9)
        private val IMAGE_URL_TEST_CONSTANT =
            "2l9cq8LFjK4D7EvPiFAq08DMpUA1b2SoPv9FPw3q6iiwjDvh-hLfKsPCFy0j36rfjDNjSULvRgOodRDxfRHHxA".toFifeUrlString()
        private val TRUE_WIRELESS_IMAGE_URL_CASE_TEST_CONSTANT =
            "oNv4-sFfa0tM1uA7vZ8r7UJPBV8OreiKOFl-_KlFwrqnDD7MoOV4uX8NwGUdYb1dcMm7cfwjZ04628WTeS40".toFifeUrlString()
        private val TRUE_WIRELESS_IMAGE_URL_LEFT_BUD_TEST_CONSTANT =
            "RcGxVZRObx9Avn9AHwSMM4WvDbVNyYlqigW7PlDHL4RLU8W9lcENDMyaTWM9O7JIu1ewSX-FIe_GkQfDlItQkg".toFifeUrlString()
        private val TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD_TEST_CONSTANT =
            "S7AuFqmr_hEqEFo_qfjxAiPz9moae0dkXUSUJV4gVFcysYpn4C95P77egPnuu35C3Eh_UY6_yNpQkmmUqn4N".toFifeUrlString()
        private val ELIGIBLE_ACCOUNTS_TEST_CONSTANT = listOf(
            FastPairEligibleAccount.Builder()
                .setAccount(Account("nearby-mainline-fpseeker@google.com", "TestAccount"))
                .setOptIn(true)
                .build(),
        )
        private val ACCOUNT_KEY_TEST_CONSTANT = byteArrayOf(3)
        private val SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS_TEST_CONSTANT = byteArrayOf(2, 8)
        private val AUTHENTICATION_PUBLIC_KEY_SEC_P256R1_TEST_CONSTANT = byteArrayOf(5, 7)
        private val BLE_RECORD_BYTES_TEST_CONSTANT = byteArrayOf(2, 4)
        private val ICON_PNG_TEST_CONSTANT = byteArrayOf(2, 5)

        private const val DEVICE_TYPE_HEAD_PHONES_TEST_CONSTANT = DeviceType.HEADPHONES_VALUE
        private const val ASSISTANT_SETUP_HALF_SHEET_TEST_CONSTANT =
            "This is a test description in half sheet to ask user setup google assistant."
        private const val ASSISTANT_SETUP_NOTIFICATION_TEST_CONSTANT =
            "This is a test description in notification to ask user setup google assistant."
        private const val CONFIRM_PIN_DESCRIPTION_TEST_CONSTANT =
            "Please confirm the pin code to fast pair your device."
        private const val CONFIRM_PIN_TITLE_TEST_CONSTANT = "PIN code confirmation"
        private const val CONNECT_SUCCESS_COMPANION_APP_INSTALLED_TEST_CONSTANT =
            "This is a test description that let user open the companion app."
        private const val CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED_TEST_CONSTANT =
            "This is a test description that let user download the companion app."
        private const val DOWNLOAD_COMPANION_APP_DESCRIPTION_TEST_CONSTANT =
            "This is a test description for downloading companion app."
        private const val FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION_TEST_CONSTANT = "This is a " +
                "test description that indicates go to bluetooth settings when connection fail."
        private const val TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION_TEST_CONSTANT =
            "This is a test description of the connect device action on TV, " +
                    "when user is not logged in."
        private const val INITIAL_NOTIFICATION_DESCRIPTION_TEST_CONSTANT =
            "This is a test description for initial notification."
        private const val INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT_TEST_CONSTANT = "This is a " +
                "test description of initial notification description when account is not present."
        private const val INITIAL_PAIRING_DESCRIPTION_TEST_CONSTANT =
            "This is a test description for Fast Pair initial pairing."
        private const val COMPANION_APP_PACKAGE_TEST_CONSTANT = "android.nearby.companion"
        private const val COMPANION_APP_ACTIVITY_TEST_CONSTANT =
            "android.nearby.companion.MainActivity"
        private const val OPEN_COMPANION_APP_DESCRIPTION_TEST_CONSTANT =
            "This is a test description for opening companion app."
        private const val RETRO_ACTIVE_PAIRING_DESCRIPTION_TEST_CONSTANT =
            "This is a test description that reminds users opt in their device."
        private const val SUBSEQUENT_PAIRING_DESCRIPTION_TEST_CONSTANT =
            "This is a test description that reminds user there is a paired device nearby."
        private const val SYNC_CONTACT_DESCRIPTION_TEST_CONSTANT =
            "This is a test description of the UI to ask the user to confirm to sync contacts."
        private const val SYNC_CONTACTS_TITLE_TEST_CONSTANT = "Sync contacts to your device"
        private const val SYNC_SMS_DESCRIPTION_TEST_CONSTANT =
            "This is a test description of the UI to ask the user to confirm to sync SMS."
        private const val SYNC_SMS_TITLE_TEST_CONSTANT = "Sync SMS to your device"
        private const val UNABLE_TO_CONNECT_DESCRIPTION_TEST_CONSTANT =
            "This is a test description when Fast Pair device is unable to be connected to."
        private const val UNABLE_TO_CONNECT_TITLE_TEST_CONSTANT =
            "Unable to connect your Fast Pair device"
        private const val UPDATE_COMPANION_APP_DESCRIPTION_TEST_CONSTANT =
            "This is a test description for updating companion app."
        private const val WAIT_LAUNCH_COMPANION_APP_DESCRIPTION_TEST_CONSTANT =
            "This is a test description that indicates companion app is about to launch."

        private fun ByteArray.bytesToStringLowerCase(): String =
            joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

        // Primary image serving domain for Google Photos and most other clients of FIFE.
        private fun String.toFifeUrlString() = "https://lh3.googleusercontent.com/$this"
    }
}