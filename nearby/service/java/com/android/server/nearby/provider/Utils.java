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
import android.nearby.aidl.FastPairAccountKeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairDeviceMetadataParcel;
import android.nearby.aidl.FastPairDiscoveryItemParcel;
import android.nearby.aidl.FastPairEligibleAccountParcel;

import com.android.server.nearby.fastpair.footprint.FastPairUploadInfo;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.List;

import service.proto.Cache;
import service.proto.FastPairString.FastPairStrings;
import service.proto.Rpcs;

/**
 * Utility classes to convert between different data classes.
 */
class Utils {

    static List<Account> convertFastPairEligibleAccountsToAccountList(
            @Nullable FastPairEligibleAccountParcel[] accountParcels) {
        if (accountParcels == null) {
            return new ArrayList<Account>(0);
        }
        List<Account> accounts = new ArrayList<Account>(accountParcels.length);
        for (FastPairEligibleAccountParcel parcel : accountParcels) {
            accounts.add(parcel.account);
        }
        return accounts;
    }

    static @Nullable Rpcs.GetObservedDeviceResponse
            convertFastPairAntispoofkeyDeviceMetadataToGetObservedDeviceResponse(
            @Nullable FastPairAntispoofkeyDeviceMetadataParcel metadata) {
        if (metadata == null) {
            return null;
        }
        return Rpcs.GetObservedDeviceResponse.newBuilder()
                .setDevice(Rpcs.Device.newBuilder()
                        .setAntiSpoofingKeyPair(Rpcs.AntiSpoofingKeyPair.newBuilder()
                                .setPublicKey(ByteString.copyFrom(metadata.antiSpoofPublicKey))
                                .build())
                        .setTrueWirelessImages(Rpcs.TrueWirelessHeadsetImages.newBuilder()
                                        .setLeftBudUrl(
                                                metadata.deviceMetadata.trueWirelessImageUrlLeftBud)
                                        .setRightBudUrl(
                                                metadata.deviceMetadata
                                                        .trueWirelessImageUrlRightBud)
                                        .setCaseUrl(
                                                metadata.deviceMetadata
                                                        .trueWirelessImageUrlCase
                                        )
                                        .build())
                        .setImageUrl(metadata.deviceMetadata.imageUrl)
                        .setIntentUri(metadata.deviceMetadata.intentUri)
                        .setBleTxPower(metadata.deviceMetadata.bleTxPower)
                        .setTriggerDistance(metadata.deviceMetadata.triggerDistance)
                        .setDeviceType(
                                Rpcs.DeviceType.forNumber(metadata.deviceMetadata.deviceType))
                        .build())
                .setImage(ByteString.copyFrom(metadata.deviceMetadata.image))
                .setStrings(Rpcs.ObservedDeviceStrings.newBuilder()
                        .setAssistantSetupHalfSheet(metadata.deviceMetadata.assistantSetupHalfSheet)
                        .setAssistantSetupNotification(
                                metadata.deviceMetadata.assistantSetupNotification)
                        .setConfirmPinDescription(metadata.deviceMetadata.confirmPinDescription)
                        .setConfirmPinTitle(metadata.deviceMetadata.confirmPinTitle)
                        .setConnectSuccessCompanionAppInstalled(
                                metadata.deviceMetadata.connectSuccessCompanionAppInstalled)
                        .setConnectSuccessCompanionAppNotInstalled(
                                metadata.deviceMetadata.connectSuccessCompanionAppNotInstalled)
                        .setDownloadCompanionAppDescription(
                                metadata.deviceMetadata.downloadCompanionAppDescription)
                        .setFailConnectGoToSettingsDescription(
                                metadata.deviceMetadata.failConnectGoToSettingsDescription)
                        .setFastPairTvConnectDeviceNoAccountDescription(
                                metadata.deviceMetadata.fastPairTvConnectDeviceNoAccountDescription)
                        .setInitialNotificationDescription(
                                metadata.deviceMetadata.initialNotificationDescription)
                        .setInitialNotificationDescriptionNoAccount(
                                metadata.deviceMetadata.initialNotificationDescriptionNoAccount)
                        .setInitialPairingDescription(
                                metadata.deviceMetadata.initialPairingDescription)
                        .setLocale(metadata.deviceMetadata.locale)
                        .setOpenCompanionAppDescription(
                                metadata.deviceMetadata.openCompanionAppDescription)
                        .setRetroactivePairingDescription(
                                metadata.deviceMetadata.retroactivePairingDescription)
                        .setSubsequentPairingDescription(
                                metadata.deviceMetadata.subsequentPairingDescription)
                        .setSyncContactsDescription(
                                metadata.deviceMetadata.syncContactsDescription)
                        .setSyncContactsTitle(
                                metadata.deviceMetadata.syncContactsTitle)
                        .setSyncSmsDescription(
                                metadata.deviceMetadata.syncSmsDescription)
                        .setSyncSmsTitle(
                                metadata.deviceMetadata.syncSmsTitle)
                        .setUnableToConnectDescription(
                                metadata.deviceMetadata.unableToConnectDescription)
                        .setUnableToConnectTitle(
                                metadata.deviceMetadata.unableToConnectTitle)
                        .setUpdateCompanionAppDescription(
                                metadata.deviceMetadata.updateCompanionAppDescription)
                        .setWaitLaunchCompanionAppDescription(
                                metadata.deviceMetadata.waitLaunchCompanionAppDescription)
                        .build())
                .build();
    }

    static @Nullable FastPairAccountKeyDeviceMetadataParcel
            convertFastPairUploadInfoToFastPairAccountKeyDeviceMetadata(
            FastPairUploadInfo uploadInfo) {
        if (uploadInfo == null) {
            return null;
        }

        FastPairAccountKeyDeviceMetadataParcel accountKeyDeviceMetadataParcel =
                new FastPairAccountKeyDeviceMetadataParcel();
        accountKeyDeviceMetadataParcel.accountKey = uploadInfo.getAccountKey().toByteArray();
        accountKeyDeviceMetadataParcel.sha256AccountKeyPublicAddress =
                uploadInfo.getSha256AccountKeyPublicAddress().toByteArray();
        accountKeyDeviceMetadataParcel.metadata =
                convertStoredDiscoveryItemToFastPairDeviceMetadata(
                        uploadInfo.getStoredDiscoveryItem());
        accountKeyDeviceMetadataParcel.discoveryItem =
                convertStoredDiscoveryItemToFastPairDiscoveryItem(
                        uploadInfo.getStoredDiscoveryItem());

        return accountKeyDeviceMetadataParcel;
    }

    private static @Nullable FastPairDiscoveryItemParcel
            convertStoredDiscoveryItemToFastPairDiscoveryItem(
            @Nullable Cache.StoredDiscoveryItem storedDiscoveryItem) {
        if (storedDiscoveryItem == null) {
            return null;
        }

        FastPairDiscoveryItemParcel discoveryItemParcel = new FastPairDiscoveryItemParcel();
        discoveryItemParcel.actionUrl = storedDiscoveryItem.getActionUrl();
        discoveryItemParcel.actionUrlType = storedDiscoveryItem.getActionUrlType().getNumber();
        discoveryItemParcel.appName = storedDiscoveryItem.getAppName();
        discoveryItemParcel.attachmentType = storedDiscoveryItem.getAttachmentType().getNumber();
        discoveryItemParcel.attachmentType = storedDiscoveryItem.getAttachmentType().getNumber();
        discoveryItemParcel.authenticationPublicKeySecp256r1 =
                storedDiscoveryItem.getAuthenticationPublicKeySecp256R1().toByteArray();
        discoveryItemParcel.bleRecordBytes = storedDiscoveryItem.getBleRecordBytes().toByteArray();
        discoveryItemParcel.debugCategory = storedDiscoveryItem.getDebugCategory().getNumber();
        discoveryItemParcel.debugMessage = storedDiscoveryItem.getDebugMessage();
        discoveryItemParcel.description = storedDiscoveryItem.getDescription();
        discoveryItemParcel.deviceName = storedDiscoveryItem.getDeviceName();
        discoveryItemParcel.displayUrl = storedDiscoveryItem.getDisplayUrl();
        discoveryItemParcel.entityId = storedDiscoveryItem.getEntityId();
        discoveryItemParcel.featureGraphicUrl = storedDiscoveryItem.getFeatureGraphicUrl();
        discoveryItemParcel.firstObservationTimestampMillis =
                storedDiscoveryItem.getFirstObservationTimestampMillis();
        discoveryItemParcel.groupId = storedDiscoveryItem.getGroupId();
        discoveryItemParcel.iconFifeUrl = storedDiscoveryItem.getIconFifeUrl();
        discoveryItemParcel.iconPng = storedDiscoveryItem.getIconPng().toByteArray();
        discoveryItemParcel.id = storedDiscoveryItem.getId();
        discoveryItemParcel.lastObservationTimestampMillis =
                storedDiscoveryItem.getLastObservationTimestampMillis();
        discoveryItemParcel.lastUserExperience =
                storedDiscoveryItem.getLastUserExperience().getNumber();
        discoveryItemParcel.lostMillis = storedDiscoveryItem.getLostMillis();
        discoveryItemParcel.macAddress = storedDiscoveryItem.getMacAddress();
        discoveryItemParcel.packageName = storedDiscoveryItem.getPackageName();
        discoveryItemParcel.pendingAppInstallTimestampMillis =
                storedDiscoveryItem.getPendingAppInstallTimestampMillis();
        discoveryItemParcel.rssi = storedDiscoveryItem.getRssi();
        discoveryItemParcel.state = storedDiscoveryItem.getState().getNumber();
        discoveryItemParcel.title = storedDiscoveryItem.getTitle();
        discoveryItemParcel.triggerId = storedDiscoveryItem.getTriggerId();
        discoveryItemParcel.txPower = storedDiscoveryItem.getTxPower();
        discoveryItemParcel.type = storedDiscoveryItem.getType().getNumber();

        return discoveryItemParcel;
    }

    /*  Do we upload these?
        String downloadCompanionAppDescription =
             bundle.getString("downloadCompanionAppDescription");
        String locale = bundle.getString("locale");
        String openCompanionAppDescription = bundle.getString("openCompanionAppDescription");
        float triggerDistance = bundle.getFloat("triggerDistance");
        String unableToConnectDescription = bundle.getString("unableToConnectDescription");
        String unableToConnectTitle = bundle.getString("unableToConnectTitle");
        String updateCompanionAppDescription = bundle.getString("updateCompanionAppDescription");
    */
    private static @Nullable FastPairDeviceMetadataParcel
            convertStoredDiscoveryItemToFastPairDeviceMetadata(
            @Nullable Cache.StoredDiscoveryItem storedDiscoveryItem) {
        if (storedDiscoveryItem == null) {
            return null;
        }

        FastPairStrings fpStrings = storedDiscoveryItem.getFastPairStrings();

        FastPairDeviceMetadataParcel metadataParcel = new FastPairDeviceMetadataParcel();
        metadataParcel.assistantSetupHalfSheet = fpStrings.getAssistantHalfSheetDescription();
        metadataParcel.assistantSetupNotification = fpStrings.getAssistantNotificationDescription();
        metadataParcel.confirmPinDescription = fpStrings.getConfirmPinDescription();
        metadataParcel.confirmPinTitle = fpStrings.getConfirmPinTitle();
        metadataParcel.connectSuccessCompanionAppInstalled =
                fpStrings.getPairingFinishedCompanionAppInstalled();
        metadataParcel.connectSuccessCompanionAppNotInstalled =
                fpStrings.getPairingFinishedCompanionAppNotInstalled();
        metadataParcel.failConnectGoToSettingsDescription = fpStrings.getPairingFailDescription();
        metadataParcel.fastPairTvConnectDeviceNoAccountDescription =
                fpStrings.getFastPairTvConnectDeviceNoAccountDescription();
        metadataParcel.initialNotificationDescription = fpStrings.getTapToPairWithAccount();
        metadataParcel.initialNotificationDescriptionNoAccount =
                fpStrings.getTapToPairWithoutAccount();
        metadataParcel.initialPairingDescription = fpStrings.getInitialPairingDescription();
        metadataParcel.retroactivePairingDescription = fpStrings.getRetroactivePairingDescription();
        metadataParcel.subsequentPairingDescription = fpStrings.getSubsequentPairingDescription();
        metadataParcel.syncContactsDescription = fpStrings.getSyncContactsDescription();
        metadataParcel.syncContactsTitle = fpStrings.getSyncContactsTitle();
        metadataParcel.syncSmsDescription = fpStrings.getSyncSmsDescription();
        metadataParcel.syncSmsTitle = fpStrings.getSyncSmsTitle();
        metadataParcel.waitLaunchCompanionAppDescription = fpStrings.getWaitAppLaunchDescription();

        Cache.FastPairInformation fpInformation = storedDiscoveryItem.getFastPairInformation();
        metadataParcel.trueWirelessImageUrlCase =
                fpInformation.getTrueWirelessImages().getCaseUrl();
        metadataParcel.trueWirelessImageUrlLeftBud =
                fpInformation.getTrueWirelessImages().getLeftBudUrl();
        metadataParcel.trueWirelessImageUrlRightBud =
                fpInformation.getTrueWirelessImages().getRightBudUrl();
        metadataParcel.deviceType = fpInformation.getDeviceType().getNumber();

        metadataParcel.bleTxPower = storedDiscoveryItem.getTxPower();
        metadataParcel.image = storedDiscoveryItem.getIconPng().toByteArray();
        metadataParcel.imageUrl = storedDiscoveryItem.getIconFifeUrl();
        metadataParcel.intentUri = storedDiscoveryItem.getActionUrl();

        return metadataParcel;
    }
}
