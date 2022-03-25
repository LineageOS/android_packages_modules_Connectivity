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

package android.nearby.fastpair.seeker

import android.nearby.FastPairAccountKeyDeviceMetadata
import android.nearby.FastPairAntispoofKeyDeviceMetadata
import android.nearby.FastPairDeviceMetadata
import android.nearby.FastPairDiscoveryItem
import com.google.common.io.BaseEncoding
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName

/** Manage a cache of Fast Pair test data for testing. */
class FastPairTestDataCache {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val accountKeyDeviceMetadataList = mutableListOf<FastPairAccountKeyDeviceMetadata>()
    private val antispoofKeyDeviceMetadataDataMap =
        mutableMapOf<String, FastPairAntispoofKeyDeviceMetadataData>()

    fun putAccountKeyDeviceMetadata(json: String) {
        accountKeyDeviceMetadataList +=
            gson.fromJson(json, Array<FastPairAccountKeyDeviceMetadataData>::class.java)
                .map { it.toFastPairAccountKeyDeviceMetadata() }
    }

    fun putAccountKeyDeviceMetadata(accountKeyDeviceMetadata: FastPairAccountKeyDeviceMetadata) {
        accountKeyDeviceMetadataList += accountKeyDeviceMetadata
    }

    fun getAccountKeyDeviceMetadataList(): List<FastPairAccountKeyDeviceMetadata> =
        accountKeyDeviceMetadataList.toList()

    fun dumpAccountKeyDeviceMetadataAsJson(metadata: FastPairAccountKeyDeviceMetadata): String =
        gson.toJson(FastPairAccountKeyDeviceMetadataData(metadata))

    fun dumpAccountKeyDeviceMetadataListAsJson(): String =
        gson.toJson(accountKeyDeviceMetadataList.map { FastPairAccountKeyDeviceMetadataData(it) })

    fun putAntispoofKeyDeviceMetadata(modelId: String, json: String) {
        antispoofKeyDeviceMetadataDataMap[modelId] =
            gson.fromJson(json, FastPairAntispoofKeyDeviceMetadataData::class.java)
    }

    fun getAntispoofKeyDeviceMetadata(modelId: String): FastPairAntispoofKeyDeviceMetadata? {
        return antispoofKeyDeviceMetadataDataMap[modelId]?.toFastPairAntispoofKeyDeviceMetadata()
    }

    fun getFastPairDeviceMetadata(modelId: String): FastPairDeviceMetadata? =
        antispoofKeyDeviceMetadataDataMap[modelId]?.deviceMeta?.toFastPairDeviceMetadata()

    fun reset() {
        accountKeyDeviceMetadataList.clear()
        antispoofKeyDeviceMetadataDataMap.clear()
    }

    data class FastPairAccountKeyDeviceMetadataData(
        @SerializedName("account_key") val accountKey: String?,
        @SerializedName("sha256_account_key_public_address") val accountKeyPublicAddress: String?,
        @SerializedName("fast_pair_device_metadata") val deviceMeta: FastPairDeviceMetadataData?,
        @SerializedName("fast_pair_discovery_item") val discoveryItem: FastPairDiscoveryItemData?
    ) {
        constructor(meta: FastPairAccountKeyDeviceMetadata) : this(
            accountKey = meta.deviceAccountKey?.base64Encode(),
            accountKeyPublicAddress = meta.sha256DeviceAccountKeyPublicAddress?.base64Encode(),
            deviceMeta = meta.fastPairDeviceMetadata?.let { FastPairDeviceMetadataData(it) },
            discoveryItem = meta.fastPairDiscoveryItem?.let { FastPairDiscoveryItemData(it) }
        )

        fun toFastPairAccountKeyDeviceMetadata(): FastPairAccountKeyDeviceMetadata {
            return FastPairAccountKeyDeviceMetadata.Builder()
                .setDeviceAccountKey(accountKey?.base64Decode())
                .setSha256DeviceAccountKeyPublicAddress(accountKeyPublicAddress?.base64Decode())
                .setFastPairDeviceMetadata(deviceMeta?.toFastPairDeviceMetadata())
                .setFastPairDiscoveryItem(discoveryItem?.toFastPairDiscoveryItem())
                .build()
        }
    }

    data class FastPairAntispoofKeyDeviceMetadataData(
        @SerializedName("anti_spoofing_public_key_str") val antispoofPublicKey: String?,
        @SerializedName("fast_pair_device_metadata") val deviceMeta: FastPairDeviceMetadataData?
    ) {
        fun toFastPairAntispoofKeyDeviceMetadata(): FastPairAntispoofKeyDeviceMetadata {
            return FastPairAntispoofKeyDeviceMetadata.Builder()
                .setAntispoofPublicKey(antispoofPublicKey?.base64Decode())
                .setFastPairDeviceMetadata(deviceMeta?.toFastPairDeviceMetadata())
                .build()
        }
    }

    data class FastPairDeviceMetadataData(
        @SerializedName("assistant_setup_half_sheet") val assistantSetupHalfSheet: String?,
        @SerializedName("assistant_setup_notification") val assistantSetupNotification: String?,
        @SerializedName("ble_tx_power") val bleTxPower: Int,
        @SerializedName("confirm_pin_description") val confirmPinDescription: String?,
        @SerializedName("confirm_pin_title") val confirmPinTitle: String?,
        @SerializedName("connect_success_companion_app_installed") val compAppInstalled: String?,
        @SerializedName("connect_success_companion_app_not_installed") val comAppNotIns: String?,
        @SerializedName("device_type") val deviceType: Int,
        @SerializedName("download_companion_app_description") val downloadComApp: String?,
        @SerializedName("fail_connect_go_to_settings_description") val failConnectDes: String?,
        @SerializedName("fast_pair_tv_connect_device_no_account_description") val accDes: String?,
        @SerializedName("image_url") val imageUrl: String?,
        @SerializedName("initial_notification_description") val initNotification: String?,
        @SerializedName("initial_notification_description_no_account") val initNoAccount: String?,
        @SerializedName("initial_pairing_description") val initialPairingDescription: String?,
        @SerializedName("intent_uri") val intentUri: String?,
        @SerializedName("locale") val locale: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("open_companion_app_description") val openCompanionAppDescription: String?,
        @SerializedName("retroactive_pairing_description") val retroactivePairingDes: String?,
        @SerializedName("subsequent_pairing_description") val subsequentPairingDescription: String?,
        @SerializedName("sync_contacts_description") val syncContactsDescription: String?,
        @SerializedName("sync_contacts_title") val syncContactsTitle: String?,
        @SerializedName("sync_sms_description") val syncSmsDescription: String?,
        @SerializedName("sync_sms_title") val syncSmsTitle: String?,
        @SerializedName("trigger_distance") val triggerDistance: Double,
        @SerializedName("case_url") val trueWirelessImageUrlCase: String?,
        @SerializedName("left_bud_url") val trueWirelessImageUrlLeftBud: String?,
        @SerializedName("right_bud_url") val trueWirelessImageUrlRightBud: String?,
        @SerializedName("unable_to_connect_description") val unableToConnectDescription: String?,
        @SerializedName("unable_to_connect_title") val unableToConnectTitle: String?,
        @SerializedName("update_companion_app_description") val updateCompAppDes: String?,
        @SerializedName("wait_launch_companion_app_description") val waitLaunchCompApp: String?
    ) {
        constructor(meta: FastPairDeviceMetadata) : this(
            assistantSetupHalfSheet = meta.assistantSetupHalfSheet,
            assistantSetupNotification = meta.assistantSetupNotification,
            bleTxPower = meta.bleTxPower,
            confirmPinDescription = meta.confirmPinDescription,
            confirmPinTitle = meta.confirmPinTitle,
            compAppInstalled = meta.connectSuccessCompanionAppInstalled,
            comAppNotIns = meta.connectSuccessCompanionAppNotInstalled,
            deviceType = meta.deviceType,
            downloadComApp = meta.downloadCompanionAppDescription,
            failConnectDes = meta.failConnectGoToSettingsDescription,
            accDes = meta.fastPairTvConnectDeviceNoAccountDescription,
            imageUrl = meta.imageUrl,
            initNotification = meta.initialNotificationDescription,
            initNoAccount = meta.initialNotificationDescriptionNoAccount,
            initialPairingDescription = meta.initialPairingDescription,
            intentUri = meta.intentUri,
            locale = meta.locale,
            name = meta.name,
            openCompanionAppDescription = meta.openCompanionAppDescription,
            retroactivePairingDes = meta.retroactivePairingDescription,
            subsequentPairingDescription = meta.subsequentPairingDescription,
            syncContactsDescription = meta.syncContactsDescription,
            syncContactsTitle = meta.syncContactsTitle,
            syncSmsDescription = meta.syncSmsDescription,
            syncSmsTitle = meta.syncSmsTitle,
            triggerDistance = meta.triggerDistance.toDouble(),
            trueWirelessImageUrlCase = meta.trueWirelessImageUrlCase,
            trueWirelessImageUrlLeftBud = meta.trueWirelessImageUrlLeftBud,
            trueWirelessImageUrlRightBud = meta.trueWirelessImageUrlRightBud,
            unableToConnectDescription = meta.unableToConnectDescription,
            unableToConnectTitle = meta.unableToConnectTitle,
            updateCompAppDes = meta.updateCompanionAppDescription,
            waitLaunchCompApp = meta.waitLaunchCompanionAppDescription
        )

        fun toFastPairDeviceMetadata(): FastPairDeviceMetadata {
            return FastPairDeviceMetadata.Builder()
                .setAssistantSetupHalfSheet(assistantSetupHalfSheet)
                .setAssistantSetupNotification(assistantSetupNotification)
                .setBleTxPower(bleTxPower)
                .setConfirmPinDescription(confirmPinDescription)
                .setConfirmPinTitle(confirmPinTitle)
                .setConnectSuccessCompanionAppInstalled(compAppInstalled)
                .setConnectSuccessCompanionAppNotInstalled(comAppNotIns)
                .setDeviceType(deviceType)
                .setDownloadCompanionAppDescription(downloadComApp)
                .setFailConnectGoToSettingsDescription(failConnectDes)
                .setFastPairTvConnectDeviceNoAccountDescription(accDes)
                .setImageUrl(imageUrl)
                .setInitialNotificationDescription(initNotification)
                .setInitialNotificationDescriptionNoAccount(initNoAccount)
                .setInitialPairingDescription(initialPairingDescription)
                .setIntentUri(intentUri)
                .setLocale(locale)
                .setName(name)
                .setOpenCompanionAppDescription(openCompanionAppDescription)
                .setRetroactivePairingDescription(retroactivePairingDes)
                .setSubsequentPairingDescription(subsequentPairingDescription)
                .setSyncContactsDescription(syncContactsDescription)
                .setSyncContactsTitle(syncContactsTitle)
                .setSyncSmsDescription(syncSmsDescription)
                .setSyncSmsTitle(syncSmsTitle)
                .setTriggerDistance(triggerDistance.toFloat())
                .setTrueWirelessImageUrlCase(trueWirelessImageUrlCase)
                .setTrueWirelessImageUrlLeftBud(trueWirelessImageUrlLeftBud)
                .setTrueWirelessImageUrlRightBud(trueWirelessImageUrlRightBud)
                .setUnableToConnectDescription(unableToConnectDescription)
                .setUnableToConnectTitle(unableToConnectTitle)
                .setUpdateCompanionAppDescription(updateCompAppDes)
                .setWaitLaunchCompanionAppDescription(waitLaunchCompApp)
                .build()
        }
    }

    data class FastPairDiscoveryItemData(
        @SerializedName("action_url") val actionUrl: String?,
        @SerializedName("action_url_type") val actionUrlType: Int,
        @SerializedName("app_name") val appName: String?,
        @SerializedName("attachment_type") val attachmentType: Int,
        @SerializedName("authentication_public_key_secp256r1") val authenticationPublicKey: String?,
        @SerializedName("ble_record_bytes") val bleRecordBytes: String?,
        @SerializedName("debug_category") val debugCategory: Int,
        @SerializedName("debug_message") val debugMessage: String?,
        @SerializedName("description") val description: String?,
        @SerializedName("device_name") val deviceName: String?,
        @SerializedName("display_url") val displayUrl: String?,
        @SerializedName("entity_id") val entityId: String?,
        @SerializedName("feature_graphic_url") val featureGraphicUrl: String?,
        @SerializedName("first_observation_timestamp_millis") val firstObservationMs: Long,
        @SerializedName("group_id") val groupId: String?,
        @SerializedName("icon_fife_url") val iconFfeUrl: String?,
        @SerializedName("icon_png") val iconPng: String?,
        @SerializedName("id") val id: String?,
        @SerializedName("last_observation_timestamp_millis") val lastObservationMs: Long,
        @SerializedName("last_user_experience") val lastUserExperience: Int,
        @SerializedName("lost_millis") val lostMillis: Long,
        @SerializedName("mac_address") val macAddress: String?,
        @SerializedName("package_name") val packageName: String?,
        @SerializedName("pending_app_install_timestamp_millis") val pendingAppInstallMs: Long,
        @SerializedName("rssi") val rssi: Int,
        @SerializedName("state") val state: Int,
        @SerializedName("title") val title: String?,
        @SerializedName("trigger_id") val triggerId: String?,
        @SerializedName("tx_power") val txPower: Int,
        @SerializedName("type") val type: Int
    ) {
        constructor(item: FastPairDiscoveryItem) : this(
            actionUrl = item.actionUrl,
            actionUrlType = item.actionUrlType,
            appName = item.appName,
            attachmentType = item.attachmentType,
            authenticationPublicKey = item.authenticationPublicKeySecp256r1?.base64Encode(),
            bleRecordBytes = item.bleRecordBytes?.base64Encode(),
            debugCategory = item.debugCategory,
            debugMessage = item.debugMessage,
            description = item.description,
            deviceName = item.deviceName,
            displayUrl = item.displayUrl,
            entityId = item.entityId,
            featureGraphicUrl = item.featureGraphicUrl,
            firstObservationMs = item.firstObservationTimestampMillis,
            groupId = item.groupId,
            iconFfeUrl = item.iconFfeUrl,
            iconPng = item.iconPng?.base64Encode(),
            id = item.id,
            lastObservationMs = item.lastObservationTimestampMillis,
            lastUserExperience = item.lastUserExperience,
            lostMillis = item.lostMillis,
            macAddress = item.macAddress,
            packageName = item.packageName,
            pendingAppInstallMs = item.pendingAppInstallTimestampMillis,
            rssi = item.rssi,
            state = item.state,
            title = item.title,
            triggerId = item.triggerId,
            txPower = item.txPower,
            type = item.type
        )

        fun toFastPairDiscoveryItem(): FastPairDiscoveryItem {
            return FastPairDiscoveryItem.Builder()
                .setActionUrl(actionUrl)
                .setActionUrlType(actionUrlType)
                .setAppName(appName)
                .setAttachmentType(attachmentType)
                .setAuthenticationPublicKeySecp256r1(authenticationPublicKey?.base64Decode())
                .setBleRecordBytes(bleRecordBytes?.base64Decode())
                .setDebugCategory(debugCategory)
                .setDebugMessage(debugMessage)
                .setDescription(description)
                .setDeviceName(deviceName)
                .setDisplayUrl(displayUrl)
                .setEntityId(entityId)
                .setFeatureGraphicUrl(featureGraphicUrl)
                .setFirstObservationTimestampMillis(firstObservationMs)
                .setGroupId(groupId)
                .setIconFfeUrl(iconFfeUrl)
                .setIconPng(iconPng?.base64Decode())
                .setId(id)
                .setLastObservationTimestampMillis(lastObservationMs)
                .setLastUserExperience(lastUserExperience)
                .setLostMillis(lostMillis)
                .setMacAddress(macAddress)
                .setPackageName(packageName)
                .setPendingAppInstallTimestampMillis(pendingAppInstallMs)
                .setRssi(rssi)
                .setState(state)
                .setTitle(title)
                .setTriggerId(triggerId)
                .setTxPower(txPower)
                .setType(type)
                .build()
        }
    }
}

private fun String.base64Decode(): ByteArray = BaseEncoding.base64().decode(this)

private fun ByteArray.base64Encode(): String = BaseEncoding.base64().encode(this)
