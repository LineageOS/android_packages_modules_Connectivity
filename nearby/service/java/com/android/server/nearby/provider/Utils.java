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
import service.proto.Data;
import service.proto.FastPairString.FastPairStrings;
import service.proto.Rpcs;

/**
 * Utility functions to convert between different data classes.
 */
class Utils {

    static List<Data.FastPairDeviceWithAccountKey>
            convertFastPairAccountKeyDevicesMetadataToFastPairDevicesWithAccountKey(
                    @Nullable FastPairAccountKeyDeviceMetadataParcel[] metadataParcels) {
        if (metadataParcels == null) {
            return new ArrayList<Data.FastPairDeviceWithAccountKey>(0);
        }

        List<Data.FastPairDeviceWithAccountKey> fpDeviceList =
                new ArrayList<>(metadataParcels.length);
        for (FastPairAccountKeyDeviceMetadataParcel metadataParcel : metadataParcels) {
            Data.FastPairDeviceWithAccountKey.Builder fpDeviceBuilder =
                    Data.FastPairDeviceWithAccountKey.newBuilder();
            if (metadataParcel.accountKey != null) {
                fpDeviceBuilder.setAccountKey(ByteString.copyFrom(metadataParcel.accountKey));
            }
            if (metadataParcel.sha256AccountKeyPublicAddress != null) {
                fpDeviceBuilder.setSha256AccountKeyPublicAddress(
                        ByteString.copyFrom(metadataParcel.sha256AccountKeyPublicAddress));
            }

            Cache.StoredDiscoveryItem.Builder storedDiscoveryItemBuilder =
                    Cache.StoredDiscoveryItem.newBuilder();

            if (metadataParcel.discoveryItem != null) {
                if (metadataParcel.discoveryItem.actionUrl != null) {
                    storedDiscoveryItemBuilder.setActionUrl(metadataParcel.discoveryItem.actionUrl);
                }
                storedDiscoveryItemBuilder.setActionUrlType(
                        Cache.ResolvedUrlType.forNumber(
                                metadataParcel.discoveryItem.actionUrlType));
                if (metadataParcel.discoveryItem.appName != null) {
                    storedDiscoveryItemBuilder.setAppName(metadataParcel.discoveryItem.appName);
                }
                storedDiscoveryItemBuilder.setAttachmentType(
                        Cache.DiscoveryAttachmentType.forNumber(
                                metadataParcel.discoveryItem.attachmentType));
                if (metadataParcel.discoveryItem.authenticationPublicKeySecp256r1 != null) {
                    storedDiscoveryItemBuilder.setAuthenticationPublicKeySecp256R1(
                            ByteString.copyFrom(
                                    metadataParcel.discoveryItem.authenticationPublicKeySecp256r1));
                }
                if (metadataParcel.discoveryItem.bleRecordBytes != null) {
                    storedDiscoveryItemBuilder.setBleRecordBytes(
                            ByteString.copyFrom(metadataParcel.discoveryItem.bleRecordBytes));
                }
                storedDiscoveryItemBuilder.setDebugCategory(
                        Cache.StoredDiscoveryItem.DebugMessageCategory.forNumber(
                                metadataParcel.discoveryItem.debugCategory));
                if (metadataParcel.discoveryItem.debugMessage != null) {
                    storedDiscoveryItemBuilder.setDebugMessage(
                            metadataParcel.discoveryItem.debugMessage);
                }
                if (metadataParcel.discoveryItem.description != null) {
                    storedDiscoveryItemBuilder.setDescription(
                            metadataParcel.discoveryItem.description);
                }
                if (metadataParcel.discoveryItem.deviceName != null) {
                    storedDiscoveryItemBuilder.setDeviceName(
                            metadataParcel.discoveryItem.deviceName);
                }
                if (metadataParcel.discoveryItem.displayUrl != null) {
                    storedDiscoveryItemBuilder.setDisplayUrl(
                            metadataParcel.discoveryItem.displayUrl);
                }
                if (metadataParcel.discoveryItem.entityId != null) {
                    storedDiscoveryItemBuilder.setEntityId(
                            metadataParcel.discoveryItem.entityId);
                }
                if (metadataParcel.discoveryItem.featureGraphicUrl != null) {
                    storedDiscoveryItemBuilder.setFeatureGraphicUrl(
                            metadataParcel.discoveryItem.featureGraphicUrl);
                }
                storedDiscoveryItemBuilder.setFirstObservationTimestampMillis(
                        metadataParcel.discoveryItem.firstObservationTimestampMillis);
                if (metadataParcel.discoveryItem.groupId != null) {
                    storedDiscoveryItemBuilder.setGroupId(metadataParcel.discoveryItem.groupId);
                }
                if (metadataParcel.discoveryItem.iconFifeUrl != null) {
                    storedDiscoveryItemBuilder.setIconFifeUrl(
                            metadataParcel.discoveryItem.iconFifeUrl);
                }
                if (metadataParcel.discoveryItem.iconPng != null) {
                    storedDiscoveryItemBuilder.setIconPng(
                            ByteString.copyFrom(metadataParcel.discoveryItem.iconPng));
                }
                if (metadataParcel.discoveryItem.id != null) {
                    storedDiscoveryItemBuilder.setId(metadataParcel.discoveryItem.id);
                }
                storedDiscoveryItemBuilder.setLastObservationTimestampMillis(
                        metadataParcel.discoveryItem.lastObservationTimestampMillis);
                storedDiscoveryItemBuilder.setLastUserExperience(
                        Cache.StoredDiscoveryItem.ExperienceType.forNumber(
                                metadataParcel.discoveryItem.lastUserExperience));
                storedDiscoveryItemBuilder.setLostMillis(metadataParcel.discoveryItem.lostMillis);
                if (metadataParcel.discoveryItem.macAddress != null) {
                    storedDiscoveryItemBuilder.setMacAddress(
                            metadataParcel.discoveryItem.macAddress);
                }
                if (metadataParcel.discoveryItem.packageName != null) {
                    storedDiscoveryItemBuilder.setPackageName(
                            metadataParcel.discoveryItem.packageName);
                }
                storedDiscoveryItemBuilder.setPendingAppInstallTimestampMillis(
                        metadataParcel.discoveryItem.pendingAppInstallTimestampMillis);
                storedDiscoveryItemBuilder.setRssi(metadataParcel.discoveryItem.rssi);
                storedDiscoveryItemBuilder.setState(
                        Cache.StoredDiscoveryItem.State.forNumber(
                                metadataParcel.discoveryItem.state));
                if (metadataParcel.discoveryItem.title != null) {
                    storedDiscoveryItemBuilder.setTitle(metadataParcel.discoveryItem.title);
                }
                if (metadataParcel.discoveryItem.triggerId != null) {
                    storedDiscoveryItemBuilder.setTriggerId(metadataParcel.discoveryItem.triggerId);
                }
                storedDiscoveryItemBuilder.setTxPower(metadataParcel.discoveryItem.txPower);
                storedDiscoveryItemBuilder.setType(
                        Cache.NearbyType.forNumber(metadataParcel.discoveryItem.type));
            }
            if (metadataParcel.metadata != null) {
                FastPairStrings.Builder stringsBuilder = FastPairStrings.newBuilder();
                if (metadataParcel.metadata.assistantSetupHalfSheet != null) {
                    stringsBuilder.setAssistantHalfSheetDescription(
                            metadataParcel.metadata.assistantSetupHalfSheet);
                }
                if (metadataParcel.metadata.assistantSetupNotification != null) {
                    stringsBuilder.setAssistantNotificationDescription(
                            metadataParcel.metadata.assistantSetupNotification);
                }
                if (metadataParcel.metadata.confirmPinDescription != null) {
                    stringsBuilder.setConfirmPinDescription(
                            metadataParcel.metadata.confirmPinDescription);
                }
                if (metadataParcel.metadata.confirmPinTitle != null) {
                    stringsBuilder.setConfirmPinTitle(
                            metadataParcel.metadata.confirmPinTitle);
                }
                if (metadataParcel.metadata.connectSuccessCompanionAppInstalled != null) {
                    stringsBuilder.setPairingFinishedCompanionAppInstalled(
                            metadataParcel.metadata.connectSuccessCompanionAppInstalled);
                }
                if (metadataParcel.metadata.connectSuccessCompanionAppNotInstalled != null) {
                    stringsBuilder.setPairingFinishedCompanionAppNotInstalled(
                            metadataParcel.metadata.connectSuccessCompanionAppNotInstalled);
                }
                if (metadataParcel.metadata.failConnectGoToSettingsDescription != null) {
                    stringsBuilder.setPairingFailDescription(
                            metadataParcel.metadata.failConnectGoToSettingsDescription);
                }
                if (metadataParcel.metadata.fastPairTvConnectDeviceNoAccountDescription != null) {
                    stringsBuilder.setFastPairTvConnectDeviceNoAccountDescription(
                            metadataParcel.metadata.fastPairTvConnectDeviceNoAccountDescription);
                }
                if (metadataParcel.metadata.initialNotificationDescription != null) {
                    stringsBuilder.setTapToPairWithAccount(
                            metadataParcel.metadata.initialNotificationDescription);
                }
                if (metadataParcel.metadata.initialNotificationDescriptionNoAccount != null) {
                    stringsBuilder.setTapToPairWithoutAccount(
                            metadataParcel.metadata.initialNotificationDescriptionNoAccount);
                }
                if (metadataParcel.metadata.initialPairingDescription != null) {
                    stringsBuilder.setInitialPairingDescription(
                            metadataParcel.metadata.initialPairingDescription);
                }
                if (metadataParcel.metadata.retroactivePairingDescription != null) {
                    stringsBuilder.setRetroactivePairingDescription(
                            metadataParcel.metadata.retroactivePairingDescription);
                }
                if (metadataParcel.metadata.subsequentPairingDescription != null) {
                    stringsBuilder.setSubsequentPairingDescription(
                            metadataParcel.metadata.subsequentPairingDescription);
                }
                if (metadataParcel.metadata.syncContactsDescription != null) {
                    stringsBuilder.setSyncContactsDescription(
                            metadataParcel.metadata.syncContactsDescription);
                }
                if (metadataParcel.metadata.syncContactsTitle != null) {
                    stringsBuilder.setSyncContactsTitle(
                            metadataParcel.metadata.syncContactsTitle);
                }
                if (metadataParcel.metadata.syncSmsDescription != null) {
                    stringsBuilder.setSyncSmsDescription(
                            metadataParcel.metadata.syncSmsDescription);
                }
                if (metadataParcel.metadata.syncSmsTitle != null) {
                    stringsBuilder.setSyncSmsTitle(metadataParcel.metadata.syncSmsTitle);
                }
                if (metadataParcel.metadata.waitLaunchCompanionAppDescription != null) {
                    stringsBuilder.setWaitAppLaunchDescription(
                            metadataParcel.metadata.waitLaunchCompanionAppDescription);
                }
                storedDiscoveryItemBuilder.setFastPairStrings(stringsBuilder.build());

                Cache.FastPairInformation.Builder fpInformationBuilder =
                        Cache.FastPairInformation.newBuilder();
                Rpcs.TrueWirelessHeadsetImages.Builder imagesBuilder =
                        Rpcs.TrueWirelessHeadsetImages.newBuilder();
                if (metadataParcel.metadata.trueWirelessImageUrlCase != null) {
                    imagesBuilder.setCaseUrl(metadataParcel.metadata.trueWirelessImageUrlCase);
                }
                if (metadataParcel.metadata.trueWirelessImageUrlLeftBud != null) {
                    imagesBuilder.setLeftBudUrl(
                            metadataParcel.metadata.trueWirelessImageUrlLeftBud);
                }
                if (metadataParcel.metadata.trueWirelessImageUrlRightBud != null) {
                    imagesBuilder.setRightBudUrl(
                            metadataParcel.metadata.trueWirelessImageUrlRightBud);
                }
                fpInformationBuilder.setTrueWirelessImages(imagesBuilder.build());
                fpInformationBuilder.setDeviceType(
                        Rpcs.DeviceType.forNumber(metadataParcel.metadata.deviceType));

                storedDiscoveryItemBuilder.setFastPairInformation(fpInformationBuilder.build());
                storedDiscoveryItemBuilder.setTxPower(metadataParcel.metadata.bleTxPower);

                if (metadataParcel.metadata.image != null) {
                    storedDiscoveryItemBuilder.setIconPng(
                            ByteString.copyFrom(metadataParcel.metadata.image));
                }
                if (metadataParcel.metadata.imageUrl != null) {
                    storedDiscoveryItemBuilder.setIconFifeUrl(metadataParcel.metadata.imageUrl);
                }
                if (metadataParcel.metadata.intentUri != null) {
                    storedDiscoveryItemBuilder.setActionUrl(metadataParcel.metadata.intentUri);
                }
            }
            fpDeviceBuilder.setDiscoveryItem(storedDiscoveryItemBuilder.build());
            fpDeviceList.add(fpDeviceBuilder.build());
        }
        return fpDeviceList;
    }

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

        Rpcs.Device.Builder deviceBuilder = Rpcs.Device.newBuilder();
        if (metadata.antiSpoofPublicKey != null) {
            deviceBuilder.setAntiSpoofingKeyPair(Rpcs.AntiSpoofingKeyPair.newBuilder()
                    .setPublicKey(ByteString.copyFrom(metadata.antiSpoofPublicKey))
                    .build());
        }
        if (metadata.deviceMetadata != null) {
            Rpcs.TrueWirelessHeadsetImages.Builder imagesBuilder =
                    Rpcs.TrueWirelessHeadsetImages.newBuilder();
            if (metadata.deviceMetadata.trueWirelessImageUrlLeftBud != null) {
                imagesBuilder.setLeftBudUrl(metadata.deviceMetadata.trueWirelessImageUrlLeftBud);
            }
            if (metadata.deviceMetadata.trueWirelessImageUrlRightBud != null) {
                imagesBuilder.setRightBudUrl(metadata.deviceMetadata.trueWirelessImageUrlRightBud);
            }
            if (metadata.deviceMetadata.trueWirelessImageUrlCase != null) {
                imagesBuilder.setCaseUrl(metadata.deviceMetadata.trueWirelessImageUrlCase);
            }
            deviceBuilder.setTrueWirelessImages(imagesBuilder.build());
            if (metadata.deviceMetadata.imageUrl != null) {
                deviceBuilder.setImageUrl(metadata.deviceMetadata.imageUrl);
            }
            if (metadata.deviceMetadata.intentUri != null) {
                deviceBuilder.setIntentUri(metadata.deviceMetadata.intentUri);
            }
            if (metadata.deviceMetadata.name != null) {
                deviceBuilder.setName(metadata.deviceMetadata.name);
            }
            deviceBuilder.setBleTxPower(metadata.deviceMetadata.bleTxPower)
                    .setTriggerDistance(metadata.deviceMetadata.triggerDistance)
                    .setDeviceType(Rpcs.DeviceType.forNumber(metadata.deviceMetadata.deviceType));
        }

        return Rpcs.GetObservedDeviceResponse.newBuilder()
                .setDevice(deviceBuilder.build())
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
