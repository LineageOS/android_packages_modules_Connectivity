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

import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataParcel;

import com.google.protobuf.ByteString;

import service.proto.Rpcs;

class Utils {

    static Rpcs.GetObservedDeviceResponse convert(
            FastPairAntispoofkeyDeviceMetadataParcel metadata) {
        return Rpcs.GetObservedDeviceResponse.newBuilder()
          .setDevice(Rpcs.Device.newBuilder()
                  .setAntiSpoofingKeyPair(Rpcs.AntiSpoofingKeyPair.newBuilder()
                          .setPublicKey(ByteString.copyFrom(metadata.antiSpoofPublicKey))
                          .build())
                  .setTrueWirelessImages(
                          Rpcs.TrueWirelessHeadsetImages.newBuilder()
                                  .setLeftBudUrl(
                                          metadata.deviceMetadata.trueWirelessImageUrlLeftBud)
                                  .setRightBudUrl(
                                          metadata.deviceMetadata.trueWirelessImageUrlRightBud)
                                  .setCaseUrl(metadata.deviceMetadata.trueWirelessImageUrlCase)
                                  .build())
                  .setImageUrl(metadata.deviceMetadata.imageUrl)
                  .setIntentUri(metadata.deviceMetadata.intentUri)
                  .setBleTxPower(metadata.deviceMetadata.bleTxPower)
                  .setTriggerDistance(metadata.deviceMetadata.triggerDistance)
                  .setDeviceType(Rpcs.DeviceType.forNumber(metadata.deviceMetadata.deviceType))
                  .build())
          .setImage(ByteString.copyFrom(metadata.deviceMetadata.image))
          .setStrings(Rpcs.ObservedDeviceStrings.newBuilder()
                  .setAssistantSetupHalfSheet(metadata.deviceMetadata.assistantSetupHalfSheet)
                  .setAssistantSetupNotification(metadata.deviceMetadata.assistantSetupNotification)
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
                  .setInitialPairingDescription(metadata.deviceMetadata.initialPairingDescription)
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
}
