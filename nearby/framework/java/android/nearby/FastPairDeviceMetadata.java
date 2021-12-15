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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.nearby.aidl.FastPairDeviceMetadataParcel;

/**
 * Class for the properties of a given type of Fast Pair device, including images and text.
 *
 * @hide
 */
@SystemApi
public class FastPairDeviceMetadata {

    FastPairDeviceMetadataParcel mMetadataParcel;

    FastPairDeviceMetadata(
            FastPairDeviceMetadataParcel metadataParcel) {
        this.mMetadataParcel = metadataParcel;
    }

    /**
     * Get ImageUlr.
     */
    @Nullable
    public String getImageUrl() {
        return mMetadataParcel.imageUrl;
    }

    /**
     * Get IntentUri.
     */
    @Nullable
    public String getIntentUri() {
        return mMetadataParcel.intentUri;
    }

    /**
     * Get ble transmission power.
     */
    public int getBleTxPower() {
        return mMetadataParcel.bleTxPower;
    }

    /**
     * Get trigger distance.
     */
    public float getTriggerDistance() {
        return mMetadataParcel.triggerDistance;
    }

    /**
     * Get image.
     */
    @Nullable
    public byte[] getImage() {
        return mMetadataParcel.image;
    }

    /**
     * Get device type.
     */
    public int getDeviceType() {
        return mMetadataParcel.deviceType;
    }

    /**
     * Get true wireless image url for left bud.
     */
    @Nullable
    public String getTrueWirelessImageUrlLeftBud() {
        return mMetadataParcel.trueWirelessImageUrlLeftBud;
    }

    /**
     * Get true wireless image url for right bud.
     */
    @Nullable
    public String getTrueWirelessImageUrlRightBud() {
        return mMetadataParcel.trueWirelessImageUrlRightBud;
    }

    /**
     * Get true wireless image url for case.
     */
    @Nullable
    public String getTrueWirelessImageUrlCase() {
        return mMetadataParcel.trueWirelessImageUrlCase;
    }

    /**
     * Get Locale.
     */
    @Nullable
    public String getLocale() {
        return mMetadataParcel.locale;
    }

    /**
     * Get InitialNotificationDescription.
     */
    @Nullable
    public String getInitialNotificationDescription() {
        return mMetadataParcel.initialNotificationDescription;
    }

    /**
     * Get InitialNotificationDescriptionNoAccount.
     */
    @Nullable
    public String getInitialNotificationDescriptionNoAccount() {
        return mMetadataParcel.initialNotificationDescriptionNoAccount;
    }

    /**
     * Get OpenCompanionAppDescription.
     */
    @Nullable
    public String getOpenCompanionAppDescription() {
        return mMetadataParcel.openCompanionAppDescription;
    }

    /**
     * Get UpdateCompanionAppDescription.
     */
    @Nullable
    public String getUpdateCompanionAppDescription() {
        return mMetadataParcel.updateCompanionAppDescription;
    }

    /**
     * Get DownloadCompanionAppDescription.
     */
    @Nullable
    public String getDownloadCompanionAppDescription() {
        return mMetadataParcel.downloadCompanionAppDescription;
    }

    /**
     * Get UnableToConnectTitle.
     */
    @Nullable
    public String getUnableToConnectTitle() {
        return mMetadataParcel.unableToConnectTitle;
    }

    /**
     * Get UnableToConnectDescription.
     */
    @Nullable
    public String getUnableToConnectDescription() {
        return mMetadataParcel.unableToConnectDescription;
    }

    /**
     * Get InitialPairingDescription.
     */
    @Nullable
    public String getInitialPairingDescription() {
        return mMetadataParcel.initialPairingDescription;
    }

    /**
     * Get ConnectSuccessCompanionAppInstalled.
     */
    @Nullable
    public String getConnectSuccessCompanionAppInstalled() {
        return mMetadataParcel.connectSuccessCompanionAppInstalled;
    }

    /**
     * Get ConnectSuccessCompanionAppNotInstalled.
     */
    @Nullable
    public String getConnectSuccessCompanionAppNotInstalled() {
        return mMetadataParcel.connectSuccessCompanionAppNotInstalled;
    }

    /**
     * Get SubsequentPairingDescription.
     */
    @Nullable
    public String getSubsequentPairingDescription() {
        return mMetadataParcel.subsequentPairingDescription;
    }

    /**
     * Get RetroactivePairingDescription.
     */
    @Nullable
    public String getRetroactivePairingDescription() {
        return mMetadataParcel.retroactivePairingDescription;
    }

    /**
     * Get WaitLaunchCompanionAppDescription.
     */
    @Nullable
    public String getWaitLaunchCompanionAppDescription() {
        return mMetadataParcel.waitLaunchCompanionAppDescription;
    }

    /**
     * Get FailConnectGoToSettingsDescription.
     */
    @Nullable
    public String getFailConnectGoToSettingsDescription() {
        return mMetadataParcel.failConnectGoToSettingsDescription;
    }

    /**
     * Get ConfirmPinTitle.
     */
    @Nullable
    public String getConfirmPinTitle() {
        return mMetadataParcel.confirmPinTitle;
    }

    /**
     * Get ConfirmPinDescription.
     */
    @Nullable
    public String getConfirmPinDescription() {
        return mMetadataParcel.confirmPinDescription;
    }

    /**
     * Get SyncContactsTitle.
     */
    @Nullable
    public String getSyncContactsTitle() {
        return mMetadataParcel.syncContactsTitle;
    }

    /**
     * Get SyncContactsDescription.
     */
    @Nullable
    public String getSyncContactsDescription() {
        return mMetadataParcel.syncContactsDescription;
    }

    /**
     * Get SyncSmsTitle.
     */
    @Nullable
    public String getSyncSmsTitle() {
        return mMetadataParcel.syncSmsTitle;
    }

    /**
     * Get SyncSmsDescription.
     */
    @Nullable
    public String getSyncSmsDescription() {
        return mMetadataParcel.syncSmsDescription;
    }

    /**
     * Get AssistantSetupHalfSheet.
     */
    @Nullable
    public String getAssistantSetupHalfSheet() {
        return mMetadataParcel.assistantSetupHalfSheet;
    }

    /**
     * Get AssistantSetupNotification.
     */
    @Nullable
    public String getAssistantSetupNotification() {
        return mMetadataParcel.assistantSetupNotification;
    }

    /**
     * Get FastPairTvConnectDeviceNoAccountDescription.
     */
    @Nullable
    public String getFastPairTvConnectDeviceNoAccountDescription() {
        return mMetadataParcel.fastPairTvConnectDeviceNoAccountDescription;
    }

    /**
     * Builder used to create FastPairDeviceMetadata.
     */
    public static final class Builder {

        private final FastPairDeviceMetadataParcel mBuilderParcel;

        /**
         * Default constructor of Builder.
         */
        public Builder() {
            mBuilderParcel = new FastPairDeviceMetadataParcel();
            mBuilderParcel.imageUrl = null;
            mBuilderParcel.intentUri = null;
            mBuilderParcel.bleTxPower = 0;
            mBuilderParcel.triggerDistance = 0;
            mBuilderParcel.image = null;
            mBuilderParcel.deviceType = 0;  // DEVICE_TYPE_UNSPECIFIED
            mBuilderParcel.trueWirelessImageUrlLeftBud = null;
            mBuilderParcel.trueWirelessImageUrlRightBud = null;
            mBuilderParcel.trueWirelessImageUrlCase = null;
            mBuilderParcel.locale = null;
            mBuilderParcel.initialNotificationDescription = null;
            mBuilderParcel.initialNotificationDescriptionNoAccount = null;
            mBuilderParcel.openCompanionAppDescription = null;
            mBuilderParcel.updateCompanionAppDescription = null;
            mBuilderParcel.downloadCompanionAppDescription = null;
            mBuilderParcel.unableToConnectTitle = null;
            mBuilderParcel.unableToConnectDescription = null;
            mBuilderParcel.initialPairingDescription = null;
            mBuilderParcel.connectSuccessCompanionAppInstalled = null;
            mBuilderParcel.connectSuccessCompanionAppNotInstalled = null;
            mBuilderParcel.subsequentPairingDescription = null;
            mBuilderParcel.retroactivePairingDescription = null;
            mBuilderParcel.waitLaunchCompanionAppDescription = null;
            mBuilderParcel.failConnectGoToSettingsDescription = null;
            mBuilderParcel.confirmPinTitle = null;
            mBuilderParcel.confirmPinDescription = null;
            mBuilderParcel.syncContactsTitle = null;
            mBuilderParcel.syncContactsDescription = null;
            mBuilderParcel.syncSmsTitle = null;
            mBuilderParcel.syncSmsDescription = null;
            mBuilderParcel.assistantSetupHalfSheet = null;
            mBuilderParcel.assistantSetupNotification = null;
            mBuilderParcel.fastPairTvConnectDeviceNoAccountDescription = null;
        }

        /**
         * Set ImageUlr.
         *
         * @param imageUrl Image Ulr.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setImageUrl(@Nullable String imageUrl) {
            mBuilderParcel.imageUrl = imageUrl;
            return this;
        }

        /**
         * Set IntentUri.
         *
         * @param intentUri Intent uri.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setIntentUri(@Nullable String intentUri) {
            mBuilderParcel.intentUri = intentUri;
            return this;
        }

        /**
         * Set ble transmission power.
         *
         * @param bleTxPower Ble transimmision power.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.

         */
        @NonNull
        public Builder setBleTxPower(int bleTxPower) {
            mBuilderParcel.bleTxPower = bleTxPower;
            return this;
        }

        /**
         * Set trigger distance.
         *
         * @param triggerDistance Fast Pair trigger distance.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setTriggerDistance(float triggerDistance) {
            mBuilderParcel.triggerDistance = triggerDistance;
            return this;
        }

        /**
         * Set image.
         *
         * @param image Fast Pair device image.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setImage(@Nullable byte[] image) {
            mBuilderParcel.image = image;
            return this;
        }

        /**
         * Set device type.
         *
         * @param deviceType Fast Pair device type.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setDeviceType(int deviceType) {
            mBuilderParcel.deviceType = deviceType;
            return this;
        }

        /**
         * Set true wireless image url for left bud.
         *
         * @param trueWirelessImageUrlLeftBud True wireless image url for left bud.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setTrueWirelessImageUrlLeftBud(
                @Nullable String trueWirelessImageUrlLeftBud) {
            mBuilderParcel.trueWirelessImageUrlLeftBud = trueWirelessImageUrlLeftBud;
            return this;
        }

        /**
         * Set true wireless image url for right bud.
         *
         * @param trueWirelessImageUrlRightBud True wireless image url for right bud.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setTrueWirelessImageUrlRightBud(
                @Nullable String trueWirelessImageUrlRightBud) {
            mBuilderParcel.trueWirelessImageUrlRightBud = trueWirelessImageUrlRightBud;
            return this;
        }

        /**
         * Set true wireless image url for case.
         *
         * @param trueWirelessImageUrlCase True wireless image url for case.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setTrueWirelessImageUrlCase(@Nullable String trueWirelessImageUrlCase) {
            mBuilderParcel.trueWirelessImageUrlCase = trueWirelessImageUrlCase;
            return this;
        }

        /**
         * Set Locale.
         *
         * @param locale Device locale.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setLocale(@Nullable String locale) {
            mBuilderParcel.locale = locale;
            return this;
        }

        /**
         * Set InitialNotificationDescription.
         *
         * @param initialNotificationDescription Initial notification description.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setInitialNotificationDescription(
                @Nullable String initialNotificationDescription) {
            mBuilderParcel.initialNotificationDescription = initialNotificationDescription;
            return this;
        }

        /**
         * Set InitialNotificationDescriptionNoAccount.
         *
         * @param initialNotificationDescriptionNoAccount Initial notification description when
         *                                                account is not present.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setInitialNotificationDescriptionNoAccount(
                @Nullable String initialNotificationDescriptionNoAccount) {
            mBuilderParcel.initialNotificationDescriptionNoAccount =
                    initialNotificationDescriptionNoAccount;
            return this;
        }

        /**
         * Set OpenCompanionAppDescription.
         *
         * @param openCompanionAppDescription Description for opening companion app.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setOpenCompanionAppDescription(
                @Nullable String openCompanionAppDescription) {
            mBuilderParcel.openCompanionAppDescription = openCompanionAppDescription;
            return this;
        }

        /**
         * Set UpdateCompanionAppDescription.
         *
         * @param updateCompanionAppDescription Description for updating companion app.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setUpdateCompanionAppDescription(
                @Nullable String updateCompanionAppDescription) {
            mBuilderParcel.updateCompanionAppDescription = updateCompanionAppDescription;
            return this;
        }

        /**
         * Set DownloadCompanionAppDescription.
         *
         * @param downloadCompanionAppDescription Description for downloading companion app.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setDownloadCompanionAppDescription(
                @Nullable String downloadCompanionAppDescription) {
            mBuilderParcel.downloadCompanionAppDescription = downloadCompanionAppDescription;
            return this;
        }

        /**
         * Set UnableToConnectTitle.
         *
         * @param unableToConnectTitle Title when Fast Pair device is unable to be connected to.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setUnableToConnectTitle(@Nullable String unableToConnectTitle) {
            mBuilderParcel.unableToConnectTitle = unableToConnectTitle;
            return this;
        }

        /**
         * Set UnableToConnectDescription.
         *
         * @param unableToConnectDescription Description when Fast Pair device is uanble to be
         *                                   connected to.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setUnableToConnectDescription(
                @Nullable String unableToConnectDescription) {
            mBuilderParcel.unableToConnectDescription = unableToConnectDescription;
            return this;
        }

        /**
         * Set InitialPairingDescription.
         *
         * @param initialPairingDescription Description for Fast Pair initial pairing.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setInitialPairingDescription(@Nullable String initialPairingDescription) {
            mBuilderParcel.initialPairingDescription = initialPairingDescription;
            return this;
        }

        /**
         * Set ConnectSuccessCompanionAppInstalled.
         *
         * @param connectSuccessCompanionAppInstalled Description that let user open the companion
         *                                            app.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setConnectSuccessCompanionAppInstalled(
                @Nullable String connectSuccessCompanionAppInstalled) {
            mBuilderParcel.connectSuccessCompanionAppInstalled =
                    connectSuccessCompanionAppInstalled;
            return this;
        }

        /**
         * Set ConnectSuccessCompanionAppNotInstalled.
         *
         * @param connectSuccessCompanionAppNotInstalled Description that let user download the
         *                                               companion app.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setConnectSuccessCompanionAppNotInstalled(
                @Nullable String connectSuccessCompanionAppNotInstalled) {
            mBuilderParcel.connectSuccessCompanionAppNotInstalled =
                    connectSuccessCompanionAppNotInstalled;
            return this;
        }

        /**
         * Set SubsequentPairingDescription.
         *
         * @param subsequentPairingDescription Description that reminds user there is a paired
         *                                     device nearby.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setSubsequentPairingDescription(
                @Nullable String subsequentPairingDescription) {
            mBuilderParcel.subsequentPairingDescription = subsequentPairingDescription;
            return this;
        }

        /**
         * Set RetroactivePairingDescription.
         *
         * @param retroactivePairingDescription Description that reminds users opt in their device.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setRetroactivePairingDescription(
                @Nullable String retroactivePairingDescription) {
            mBuilderParcel.retroactivePairingDescription = retroactivePairingDescription;
            return this;
        }

        /**
         * Set WaitLaunchCompanionAppDescription.
         *
         * @param waitLaunchCompanionAppDescription Description that indicates companion app is
         *                                          about to launch.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setWaitLaunchCompanionAppDescription(
                @Nullable String waitLaunchCompanionAppDescription) {
            mBuilderParcel.waitLaunchCompanionAppDescription =
                    waitLaunchCompanionAppDescription;
            return this;
        }

        /**
         * Set FailConnectGoToSettingsDescription.
         *
         * @param failConnectGoToSettingsDescription Description that indicates go to bluetooth
         *                                           settings when connection fail.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setFailConnectGoToSettingsDescription(
                @Nullable String failConnectGoToSettingsDescription) {
            mBuilderParcel.failConnectGoToSettingsDescription =
                    failConnectGoToSettingsDescription;
            return this;
        }

        /**
         * Set ConfirmPinTitle.
         *
         * @param confirmPinTitle Title of the UI to ask the user to confirm the pin code.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setConfirmPinTitle(@Nullable String confirmPinTitle) {
            mBuilderParcel.confirmPinTitle = confirmPinTitle;
            return this;
        }

        /**
         * Set ConfirmPinDescription.
         *
         * @param confirmPinDescription Description of the UI to ask the user to confirm the pin
         *                              code.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setConfirmPinDescription(@Nullable String confirmPinDescription) {
            mBuilderParcel.confirmPinDescription = confirmPinDescription;
            return this;
        }

        /**
         * Set SyncContactsTitle.
         *
         * @param syncContactsTitle Title of the UI to ask the user to confirm to sync contacts.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setSyncContactsTitle(@Nullable String syncContactsTitle) {
            mBuilderParcel.syncContactsTitle = syncContactsTitle;
            return this;
        }

        /**
         * Set SyncContactsDescription.
         *
         * @param syncContactsDescription Description of the UI to ask the user to confirm to sync
         *                                contacts.
         */
        @NonNull
        public Builder setSyncContactsDescription(@Nullable String syncContactsDescription) {
            mBuilderParcel.syncContactsDescription = syncContactsDescription;
            return this;
        }

        /**
         * Set SyncSmsTitle.
         *
         * @param syncSmsTitle Title of the UI to ask the user to confirm to sync SMS.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setSyncSmsTitle(@Nullable String syncSmsTitle) {
            mBuilderParcel.syncSmsTitle = syncSmsTitle;
            return this;
        }

        /**
         * Set SyncSmsDescription.
         *
         * @param syncSmsDescription Description of the UI to ask the user to confirm to sync SMS.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setSyncSmsDescription(@Nullable String syncSmsDescription) {
            mBuilderParcel.syncSmsDescription = syncSmsDescription;
            return this;
        }

        /**
         * Set AssistantSetupHalfSheet.
         *
         * @param assistantSetupHalfSheet Description in half sheet to ask user setup google
         * assistant.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setAssistantSetupHalfSheet(@Nullable String assistantSetupHalfSheet) {
            mBuilderParcel.assistantSetupHalfSheet = assistantSetupHalfSheet;
            return this;
        }

        /**
         * Set AssistantSetupNotification.
         *
         * @param assistantSetupNotification Description in notification to ask user setup google
         *                                   assistant.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setAssistantSetupNotification(
                @Nullable String assistantSetupNotification) {
            mBuilderParcel.assistantSetupNotification = assistantSetupNotification;
            return this;
        }

        /**
         * Set FastPairTvConnectDeviceNoAccountDescription.
         *
         * @param fastPairTvConnectDeviceNoAccountDescription Description of the connect device
         *                                                    action on TV, when user is not logged
         *                                                    in.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setFastPairTvConnectDeviceNoAccountDescription(
                @Nullable String fastPairTvConnectDeviceNoAccountDescription) {
            mBuilderParcel.fastPairTvConnectDeviceNoAccountDescription =
                    fastPairTvConnectDeviceNoAccountDescription;
            return this;
        }

        /**
         * Build {@link FastPairDeviceMetadata} with the currently set configuration.
         */
        @NonNull
        public FastPairDeviceMetadata build() {
            return new FastPairDeviceMetadata(mBuilderParcel);
        }
    }
}
