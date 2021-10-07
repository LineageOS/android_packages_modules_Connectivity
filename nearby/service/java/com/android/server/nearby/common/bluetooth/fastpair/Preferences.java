/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.nearby.common.bluetooth.fastpair;

import static com.android.server.nearby.common.bluetooth.fastpair.BluetoothUuids.get16BitUuid;

import androidx.annotation.Nullable;

import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.FirmwareVersionCharacteristic;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Shorts;

import java.nio.ByteOrder;

/**
 * Preferences that tweak the Fast Pairing process: timeouts, number of retries... All preferences
 * have default values which should be reasonable for all clients.
 */
@AutoValue
public abstract class Preferences {

    /**
     * Timeout for each GATT operation (not for the whole pairing process).
     */
    public abstract int getGattOperationTimeoutSeconds();

    /** Timeout for Gatt connection operation. */
    public abstract int getGattConnectionTimeoutSeconds();

    /** Timeout for Bluetooth toggle. */
    public abstract int getBluetoothToggleTimeoutSeconds();

    /** Sleep time for Bluetooth toggle. */
    public abstract int getBluetoothToggleSleepSeconds();

    /** Timeout for classic discovery. */
    public abstract int getClassicDiscoveryTimeoutSeconds();

    /** Number of discovery attempts allowed. */
    public abstract int getNumDiscoverAttempts();

    /** Sleep time between discovery retry. */
    public abstract int getDiscoveryRetrySleepSeconds();

    /** Whether to ignore error incurred during discovery. */
    public abstract boolean getIgnoreDiscoveryError();

    /** Timeout for Sdp. */
    public abstract int getSdpTimeoutSeconds();

    /** Number of Sdp attempts allowed. */
    public abstract int getNumSdpAttempts();

    /** Number of create bond attempts allowed. */
    public abstract int getNumCreateBondAttempts();

    /** Number of connect attempts allowed. */
    public abstract int getNumConnectAttempts();

    /** Number of write account key attempts allowed. */
    public abstract int getNumWriteAccountKeyAttempts();

    /** Returns whether it is OK toggle bluetooth to retry upon failure.  */
    public abstract boolean getToggleBluetoothOnFailure();

    /** Whether to get Bluetooth state using polling. */
    public abstract boolean getBluetoothStateUsesPolling();

    /** Polling time when retrieving Bluetooth state. */
    public abstract int getBluetoothStatePollingMillis();

    /**
     * The number of times to attempt a generic operation, before giving up.
     */
    public abstract int getNumAttempts();

    /** Returns whether BrEdr handover is enabled. */
    public abstract boolean getEnableBrEdrHandover();

    /** Returns characteristic Id for Br Handover data. */
    public abstract short getBrHandoverDataCharacteristicId();

    /** Returns characteristic Id for Bluethoth Sig data. */
    public abstract short getBluetoothSigDataCharacteristicId();

    /** Returns characteristic Id for Firmware version. */
    public abstract short getFirmwareVersionCharacteristicId();

    /** Returns descripter Id for Br transport block data. */
    public abstract short getBrTransportBlockDataDescriptorId();

    /** Whether to wait for Uuids after bonding. */
    public abstract boolean getWaitForUuidsAfterBonding();

    /** Whether to get received Uuids and bonded events before close. */
    public abstract boolean getReceiveUuidsAndBondedEventBeforeClose();

    /** Timeout for remove bond operation. */
    public abstract int getRemoveBondTimeoutSeconds();

    /** Sleep time for remove bond operation. */
    public abstract int getRemoveBondSleepMillis();

    /**
     * This almost always succeeds (or fails) in 2-10 seconds (Taimen running O -> Nexus 6P sim).
     */
    public abstract int getCreateBondTimeoutSeconds();

    /** Timeout for creating bond with Hid devices. */
    public abstract int getHidCreateBondTimeoutSeconds();

    /** Timeout for get proxy operation. */
    public abstract int getProxyTimeoutSeconds();

    /** Whether to reject phone book access. */
    public abstract boolean getRejectPhonebookAccess();

    /** Whether to reject message access. */
    public abstract boolean getRejectMessageAccess();

    /** Whether to reject sim access. */
    public abstract boolean getRejectSimAccess();

    /** Sleep time for write account key operation. */
    public abstract int getWriteAccountKeySleepMillis();

    /** Whether to skip disconneting gatt before writing account key. */
    public abstract boolean getSkipDisconnectingGattBeforeWritingAccountKey();

    /** Whether to get more event log for quality improvement. */
    public abstract boolean getMoreEventLogForQuality();

    /** Whether to retry gatt connection and secrete handshake. */
    public abstract boolean getRetryGattConnectionAndSecretHandshake();

    /** Short Gatt connection timeoout. */
    public abstract long getGattConnectShortTimeoutMs();

    /** Long Gatt connection timeout. */
    public abstract long getGattConnectLongTimeoutMs();

    /** Short Timeout for Gatt connection, including retry. */
    public abstract long getGattConnectShortTimeoutRetryMaxSpentTimeMs();

    /** Timeout for address rotation, including retry. */
    public abstract long getAddressRotateRetryMaxSpentTimeMs();

    /** Returns pairing retry delay time. */
    public abstract long getPairingRetryDelayMs();

    /** Short timeout for secrete handshake. */
    public abstract long getSecretHandshakeShortTimeoutMs();

    /** Long timeout for secret handshake. */
    public abstract long getSecretHandshakeLongTimeoutMs();

    /** Short timeout for secret handshake, including retry. */
    public abstract long getSecretHandshakeShortTimeoutRetryMaxSpentTimeMs();

    /** Long timeout for secret handshake, including retry. */
    public abstract long getSecretHandshakeLongTimeoutRetryMaxSpentTimeMs();

    /** Number of secrete handshake retry allowed. */
    public abstract long getSecretHandshakeRetryAttempts();

    /** Timeout for secrete handshake and gatt connection, including retry. */
    public abstract long getSecretHandshakeRetryGattConnectionMaxSpentTimeMs();

    /** Timeout for signal lost handling, including retry. */
    public abstract long getSignalLostRetryMaxSpentTimeMs();

    /** Returns error for gatt connection and secrete handshake, without retry. */
    public abstract ImmutableSet<Integer> getGattConnectionAndSecretHandshakeNoRetryGattError();

    /** Whether to retry upon secrete handshake timeout. */
    public abstract boolean getRetrySecretHandshakeTimeout();

    /** Wehther to log user manual retry. */
    public abstract boolean getLogUserManualRetry();

    /** Returns number of pairing failure counts. */
    public abstract int getPairFailureCounts();

    /** Returns cached device address. */
    public abstract String getCachedDeviceAddress();

    /** Returns possible cached device address. */
    public abstract String getPossibleCachedDeviceAddress();

    /** Returns count of paired devices from the same model Id. */
    public abstract int getSameModelIdPairedDeviceCount();

    /** Whether the bonded device address is in the Cache . */
    public abstract boolean getIsDeviceFinishCheckAddressFromCache();

    /** Whether to log pairing info when cached model Id is hit. */
    public abstract boolean getLogPairWithCachedModelId();

    /** Whether to directly connnect to a profile of a device, whose model Id is in cache. */
    public abstract boolean getDirectConnectProfileIfModelIdInCache();

    /**
     * Whether to auto-accept
     * {@link android.bluetooth.BluetoothDevice#PAIRING_VARIANT_PASSKEY_CONFIRMATION}.
     * Only the Fast Pair Simulator (which runs on an Android device) sends this. Since real
     * Bluetooth headphones don't have displays, they use secure simple pairing (no pin code
     * confirmation; we get no pairing request broadcast at all). So we may want to turn this off in
     * prod.
     */
    public abstract boolean getAcceptPasskey();

    /** Returns Uuids for supported profiles. */
    @SuppressWarnings("mutable")
    public abstract byte[] getSupportedProfileUuids();

    /**
     * If true, after the Key-based Pairing BLE handshake, we wait for the headphones to send a
     * pairing request to us; if false, we send the request to them.
     */
    public abstract boolean getProviderInitiatesBondingIfSupported();

    /**
     * If true, the first step will be attempting to connect directly to our supported profiles when
     * a device has previously been bonded. This will help with performance on subsequent bondings
     * and help to increase reliability in some cases.
     */
    public abstract boolean getAttemptDirectConnectionWhenPreviouslyBonded();

    /**
     * If true, closed Gatt connections will be reopened when they are needed again. Otherwise, they
     * will remain closed until they are explicitly reopened.
     */
    public abstract boolean getAutomaticallyReconnectGattWhenNeeded();

    /**
     * If true, we'll finish the pairing process after we've created a bond instead of after
     * connecting a profile.
     */
    public abstract boolean getSkipConnectingProfiles();

    /**
     * If true, continues the pairing process if we've timed out due to not receiving UUIDs from the
     * headset. We can still attempt to connect to A2DP afterwards. If false, Fast Pair will fail
     * after this step since we're expecting to receive the UUIDs.
     */
    public abstract boolean getIgnoreUuidTimeoutAfterBonded();

    /**
     * If true, a specific transport type will be included in the create bond request, which will be
     * used for dual mode devices. Otherwise, we'll use the platform defined default which is
     * BluetoothDevice.TRANSPORT_AUTO. See {@link #getCreateBondTransportType()}.
     */
    public abstract boolean getSpecifyCreateBondTransportType();

    /**
     * The transport type to use when creating a bond when
     * {@link #getSpecifyCreateBondTransportType()} is true. This should be one of
     * BluetoothDevice.TRANSPORT_AUTO, BluetoothDevice.TRANSPORT_BREDR, or
     * BluetoothDevice.TRANSPORT_LE.
     */
    public abstract int getCreateBondTransportType();

    /** Whether to increase intent filter priority. */
    public abstract boolean getIncreaseIntentFilterPriority();

    /** Whether to evaluate performance. */
    public abstract boolean getEvaluatePerformance();

    /** Returns extra logging information. */
    @Nullable
    public abstract ExtraLoggingInformation getExtraLoggingInformation();

    /** Whether to enable naming characteristic. */
    public abstract boolean getEnableNamingCharacteristic();

    /** Whether to enable firmware version characteristic. */
    public abstract boolean getEnableFirmwareVersionCharacteristic();

    /**
     * If true, even Fast Pair identifies a provider have paired with the account, still writes the
     * identified account key to the provider.
     */
    public abstract boolean getKeepSameAccountKeyWrite();

    /**
     * If true, run retroactive pairing.
     */
    public abstract boolean getIsRetroactivePairing();

    /**
     * If it's larger than 0, {@link android.bluetooth.BluetoothDevice#fetchUuidsWithSdp} would be
     * triggered with number of attempts after device is bonded and no profiles were automatically
     * discovered".
     */
    public abstract int getNumSdpAttemptsAfterBonded();

    /**
     * If true, supports HID device for fastpair.
     */
    public abstract boolean getSupportHidDevice();

    /**
     * If true, we'll enable the pairing behavior to handle the state transition from BOND_BONDED to
     * BOND_BONDING when directly connecting profiles.
     */
    public abstract boolean getEnablePairingWhileDirectlyConnecting();

    /**
     * If true, we will accept the user confirmation when bonding with FastPair 1.0 devices.
     */
    public abstract boolean getAcceptConsentForFastPairOne();

    /**
     * If it's larger than 0, we will retry connecting GATT within the timeout.
     */
    public abstract int getGattConnectRetryTimeoutMillis();

    /**
     * If true, then uses the new custom GATT characteristics {go/fastpair-128bit-gatt}.
     */
    public abstract boolean getEnable128BitCustomGattCharacteristicsId();

    /**
     * If true, then sends the internal pair step or Exception to Validator by Intent.
     */
    public abstract boolean getEnableSendExceptionStepToValidator();

    /**
     * If true, then adds the additional data type in the handshake packet when action over BLE.
     */
    public abstract boolean getEnableAdditionalDataTypeWhenActionOverBle();

    /**
     * If true, then checks the bond state when skips connecting profiles in the pairing shortcut.
     */
    public abstract boolean getCheckBondStateWhenSkipConnectingProfiles();

    /**
     * If true, the passkey confirmation will be handled by the half-sheet UI.
     */
    public abstract boolean getHandlePasskeyConfirmationByUi();

    /**
     * If true, then use pair flow to show ui when pairing is finished without connecting profile.
     */
    public abstract boolean getEnablePairFlowShowUiWithoutProfileConnection();

    /** Converts an instance to a builder. */
    public abstract Builder toBuilder();

    /** Constructs a builder. */
    public static Builder builder() {
        return new AutoValue_Preferences.Builder()
                .setGattOperationTimeoutSeconds(3)
                .setGattConnectionTimeoutSeconds(15)
                .setBluetoothToggleTimeoutSeconds(10)
                .setBluetoothToggleSleepSeconds(2)
                .setClassicDiscoveryTimeoutSeconds(10)
                .setNumDiscoverAttempts(3)
                .setDiscoveryRetrySleepSeconds(1)
                .setIgnoreDiscoveryError(false)
                .setSdpTimeoutSeconds(10)
                .setNumSdpAttempts(3)
                .setNumCreateBondAttempts(3)
                .setNumConnectAttempts(1)
                .setNumWriteAccountKeyAttempts(3)
                .setToggleBluetoothOnFailure(false)
                .setBluetoothStateUsesPolling(true)
                .setBluetoothStatePollingMillis(1000)
                .setNumAttempts(2)
                .setEnableBrEdrHandover(false)
                .setBrHandoverDataCharacteristicId(get16BitUuid(
                        Constants.TransportDiscoveryService.BrHandoverDataCharacteristic.ID))
                .setBluetoothSigDataCharacteristicId(get16BitUuid(
                        Constants.TransportDiscoveryService.BluetoothSigDataCharacteristic.ID))
                .setFirmwareVersionCharacteristicId(get16BitUuid(FirmwareVersionCharacteristic.ID))
                .setBrTransportBlockDataDescriptorId(
                        get16BitUuid(
                                Constants.TransportDiscoveryService.BluetoothSigDataCharacteristic
                                        .BrTransportBlockDataDescriptor.ID))
                .setWaitForUuidsAfterBonding(true)
                .setReceiveUuidsAndBondedEventBeforeClose(true)
                .setRemoveBondTimeoutSeconds(5)
                .setRemoveBondSleepMillis(1000)
                .setCreateBondTimeoutSeconds(15)
                .setHidCreateBondTimeoutSeconds(40)
                .setProxyTimeoutSeconds(2)
                .setRejectPhonebookAccess(false)
                .setRejectMessageAccess(false)
                .setRejectSimAccess(false)
                .setAcceptPasskey(true)
                .setSupportedProfileUuids(Constants.getSupportedProfiles())
                .setWriteAccountKeySleepMillis(2000)
                .setProviderInitiatesBondingIfSupported(false)
                .setAttemptDirectConnectionWhenPreviouslyBonded(false)
                .setAutomaticallyReconnectGattWhenNeeded(false)
                .setSkipDisconnectingGattBeforeWritingAccountKey(false)
                .setSkipConnectingProfiles(false)
                .setIgnoreUuidTimeoutAfterBonded(false)
                .setSpecifyCreateBondTransportType(false)
                .setCreateBondTransportType(0 /*BluetoothDevice.TRANSPORT_AUTO*/)
                .setIncreaseIntentFilterPriority(true)
                .setEvaluatePerformance(false)
                .setKeepSameAccountKeyWrite(true)
                .setEnableNamingCharacteristic(false)
                .setEnableFirmwareVersionCharacteristic(false)
                .setIsRetroactivePairing(false)
                .setNumSdpAttemptsAfterBonded(1)
                .setSupportHidDevice(false)
                .setEnablePairingWhileDirectlyConnecting(true)
                .setAcceptConsentForFastPairOne(true)
                .setGattConnectRetryTimeoutMillis(0)
                .setEnable128BitCustomGattCharacteristicsId(true)
                .setEnableSendExceptionStepToValidator(true)
                .setEnableAdditionalDataTypeWhenActionOverBle(true)
                .setCheckBondStateWhenSkipConnectingProfiles(true)
                .setHandlePasskeyConfirmationByUi(false)
                .setMoreEventLogForQuality(true)
                .setRetryGattConnectionAndSecretHandshake(true)
                .setGattConnectShortTimeoutMs(7000)
                .setGattConnectLongTimeoutMs(15000)
                .setGattConnectShortTimeoutRetryMaxSpentTimeMs(10000)
                .setAddressRotateRetryMaxSpentTimeMs(15000)
                .setPairingRetryDelayMs(100)
                .setSecretHandshakeShortTimeoutMs(3000)
                .setSecretHandshakeLongTimeoutMs(10000)
                .setSecretHandshakeShortTimeoutRetryMaxSpentTimeMs(5000)
                .setSecretHandshakeLongTimeoutRetryMaxSpentTimeMs(7000)
                .setSecretHandshakeRetryAttempts(3)
                .setSecretHandshakeRetryGattConnectionMaxSpentTimeMs(15000)
                .setSignalLostRetryMaxSpentTimeMs(15000)
                .setGattConnectionAndSecretHandshakeNoRetryGattError(ImmutableSet.of())
                .setRetrySecretHandshakeTimeout(false)
                .setLogUserManualRetry(true)
                .setPairFailureCounts(0)
                .setEnablePairFlowShowUiWithoutProfileConnection(true)
                .setPairFailureCounts(0)
                .setLogPairWithCachedModelId(true)
                .setDirectConnectProfileIfModelIdInCache(false)
                .setCachedDeviceAddress("")
                .setPossibleCachedDeviceAddress("")
                .setSameModelIdPairedDeviceCount(0)
                .setIsDeviceFinishCheckAddressFromCache(true);
    }

    /**
     * Preferences builder.
     */
    @AutoValue.Builder
    public abstract static class Builder {

        /** Set gatt operation timeout. */
        public abstract Builder setGattOperationTimeoutSeconds(int value);

        /** Set gatt connection timeout. */
        public abstract Builder setGattConnectionTimeoutSeconds(int value);

        /** Set bluetooth toggle timeout. */
        public abstract Builder setBluetoothToggleTimeoutSeconds(int value);

        /** Set bluetooth toggle sleep time. */
        public abstract Builder setBluetoothToggleSleepSeconds(int value);

        /** Set classic discovery timeout. */
        public abstract Builder setClassicDiscoveryTimeoutSeconds(int value);

        /** Set number of discover attempts allowed. */
        public abstract Builder setNumDiscoverAttempts(int value);

        /** Set discovery retry sleep time. */
        public abstract Builder setDiscoveryRetrySleepSeconds(int value);

        /** Set whether to ignore discovery error. */
        public abstract Builder setIgnoreDiscoveryError(boolean value);

        /** Set sdp timeout. */
        public abstract Builder setSdpTimeoutSeconds(int value);

        /** Set number of sdp attempts allowed. */
        public abstract Builder setNumSdpAttempts(int value);

        /** Set number of allowed attempts to create bond. */
        public abstract Builder setNumCreateBondAttempts(int value);

        /** Set number of connect attempts allowed. */
        public abstract Builder setNumConnectAttempts(int value);

        /** Set number of write account key attempts allowed. */
        public abstract Builder setNumWriteAccountKeyAttempts(int value);

        /** Set whether to retry by bluetooth toggle on failure. */
        public abstract Builder setToggleBluetoothOnFailure(boolean value);

        /** Set whether to use polling to set bluetooth status. */
        public abstract Builder setBluetoothStateUsesPolling(boolean value);

        /** Set Bluetooth state polling timeout. */
        public abstract Builder setBluetoothStatePollingMillis(int value);

        /** Set number of attempts. */
        public abstract Builder setNumAttempts(int value);

        /** Set whether to enable BrEdr handover. */
        public abstract Builder setEnableBrEdrHandover(boolean value);

        /** Set Br handover data characteristic Id. */
        public abstract Builder setBrHandoverDataCharacteristicId(short value);

        /** Set Bluetooth Sig data characteristic Id. */
        public abstract Builder setBluetoothSigDataCharacteristicId(short value);

        /** Set Firmware version characteristic id. */
        public abstract Builder setFirmwareVersionCharacteristicId(short value);

        /** Set Br transport block data descriptor id. */
        public abstract Builder setBrTransportBlockDataDescriptorId(short value);

        /** Set whether to wait for Uuids after bonding. */
        public abstract Builder setWaitForUuidsAfterBonding(boolean value);

        /** Set whether to receive Uuids and bonded event before close. */
        public abstract Builder setReceiveUuidsAndBondedEventBeforeClose(boolean value);

        /** Set remove bond timeout. */
        public abstract Builder setRemoveBondTimeoutSeconds(int value);

        /** Set remove bound sleep time. */
        public abstract Builder setRemoveBondSleepMillis(int value);

        /** Set create bond timeout. */
        public abstract Builder setCreateBondTimeoutSeconds(int value);

        /** Set Hid create bond timeout. */
        public abstract Builder setHidCreateBondTimeoutSeconds(int value);

        /** Set proxy timeout. */
        public abstract Builder setProxyTimeoutSeconds(int value);

        /** Set whether to reject phone book access. */
        public abstract Builder setRejectPhonebookAccess(boolean value);

        /** Set whether to reject message access. */
        public abstract Builder setRejectMessageAccess(boolean value);

        /** Set whether to reject slim access. */
        public abstract Builder setRejectSimAccess(boolean value);

        /** Set whether to accept passkey. */
        public abstract Builder setAcceptPasskey(boolean value);

        /** Set supported profile Uuids. */
        public abstract Builder setSupportedProfileUuids(byte[] value);

        /** Set whether to collect more event log for quality. */
        public abstract Builder setMoreEventLogForQuality(boolean value);

        /** Set supported profile Uuids. */
        public Builder setSupportedProfileUuids(short... uuids) {
            return setSupportedProfileUuids(Bytes.toBytes(ByteOrder.BIG_ENDIAN, uuids));
        }

        /** Set write account key sleep time. */
        public abstract Builder setWriteAccountKeySleepMillis(int value);

        /** Set whether to do provider initialized bonding if supported. */
        public abstract Builder setProviderInitiatesBondingIfSupported(boolean value);

        /** Set whether to try direct connection when the device is previously bonded. */
        public abstract Builder setAttemptDirectConnectionWhenPreviouslyBonded(boolean value);

        /** Set whether to automatically reconnect gatt when needed. */
        public abstract Builder setAutomaticallyReconnectGattWhenNeeded(boolean value);

        /** Set whether to skip disconnecting gatt before writing account key. */
        public abstract Builder setSkipDisconnectingGattBeforeWritingAccountKey(boolean value);

        /** Set whether to skip connecting profiles. */
        public abstract Builder setSkipConnectingProfiles(boolean value);

        /** Set whether to ignore Uuid timeout after bonded. */
        public abstract Builder setIgnoreUuidTimeoutAfterBonded(boolean value);

        /** Set whether to include transport type in create bound request. */
        public abstract Builder setSpecifyCreateBondTransportType(boolean value);

        /** Set transport type used in create bond request. */
        public abstract Builder setCreateBondTransportType(int value);

        /** Set whether to increase intent filter priority. */
        public abstract Builder setIncreaseIntentFilterPriority(boolean value);

        /** Set whether to evaluate performance. */
        public abstract Builder setEvaluatePerformance(boolean value);

        /** Set extra logging info. */
        public abstract Builder setExtraLoggingInformation(ExtraLoggingInformation value);

        /** Set whether to enable naming characteristic. */
        public abstract Builder setEnableNamingCharacteristic(boolean value);

        /**
         * Set whether to keep writing the account key to the provider,
         * that has already paired with the account.
         */
        public abstract Builder setKeepSameAccountKeyWrite(boolean value);

        /** Set whether to enable firmware version characteristic. */
        public abstract Builder setEnableFirmwareVersionCharacteristic(boolean value);

        /** Set whether it is retroactive pairing. */
        public abstract Builder setIsRetroactivePairing(boolean value);

        /** Set number of allowed sdp attempts after bonded. */
        public abstract Builder setNumSdpAttemptsAfterBonded(int value);

        /** Set whether to support Hid device. */
        public abstract Builder setSupportHidDevice(boolean value);

        /**
          *  Set wehther to enable the pairing behavior to handle the state transition from
          *  BOND_BONDED to BOND_BONDING when directly connecting profiles.
          */
        public abstract Builder setEnablePairingWhileDirectlyConnecting(boolean value);

        /** Set whether to accept consent for fast pair one. */
        public abstract Builder setAcceptConsentForFastPairOne(boolean value);

        /** Set Gatt connect retry timeout. */
        public abstract Builder setGattConnectRetryTimeoutMillis(int value);

        /** Set whether to enable 128 bit custom gatt characteristic Id. */
        public abstract Builder setEnable128BitCustomGattCharacteristicsId(boolean value);

        /** Set whether to send exception step to validator. */
        public abstract Builder setEnableSendExceptionStepToValidator(boolean value);

        /** Set wehther to add the additional data type in the handshake when action over BLE. */
        public abstract Builder setEnableAdditionalDataTypeWhenActionOverBle(boolean value);

        /** Set whether to check bond state when skip connecting profiles. */
        public abstract Builder setCheckBondStateWhenSkipConnectingProfiles(boolean value);

        /** Set whether to handle passkey confirmation by UI. */
        public abstract Builder setHandlePasskeyConfirmationByUi(boolean value);

        /** Set wehther to retry gatt connection and secret handshake. */
        public abstract Builder setRetryGattConnectionAndSecretHandshake(boolean value);

        /** Set gatt connect short timeout. */
        public abstract Builder setGattConnectShortTimeoutMs(long value);

        /** Set gatt connect long timeout. */
        public abstract Builder setGattConnectLongTimeoutMs(long value);

        /** Set gatt connection short timoutout, including retry. */
        public abstract Builder setGattConnectShortTimeoutRetryMaxSpentTimeMs(long value);

        /** Set address rotate timeout, including retry. */
        public abstract Builder setAddressRotateRetryMaxSpentTimeMs(long value);

        /** Set pairing retry delay time. */
        public abstract Builder setPairingRetryDelayMs(long value);

        /** Set secret handshake short timeout. */
        public abstract Builder setSecretHandshakeShortTimeoutMs(long value);

        /** Set secret handshake long timeout. */
        public abstract Builder setSecretHandshakeLongTimeoutMs(long value);

        /** Set secret handshake short timeout retry max spent time. */
        public abstract Builder setSecretHandshakeShortTimeoutRetryMaxSpentTimeMs(long value);

        /** Set secret handshake long timeout retry max spent time. */
        public abstract Builder setSecretHandshakeLongTimeoutRetryMaxSpentTimeMs(long value);

        /** Set secret handshake retry attempts allowed. */
        public abstract Builder setSecretHandshakeRetryAttempts(long value);

        /** Set secret handshake retry gatt connection max spent time. */
        public abstract Builder setSecretHandshakeRetryGattConnectionMaxSpentTimeMs(long value);

        /** Set signal loss retry max spent time. */
        public abstract Builder setSignalLostRetryMaxSpentTimeMs(long value);

        /** Set gatt connection and secret handshake no retry gatt error. */
        public abstract Builder setGattConnectionAndSecretHandshakeNoRetryGattError(
                ImmutableSet<Integer> value);

        /** Set retry secret handshake timeout. */
        public abstract Builder setRetrySecretHandshakeTimeout(boolean value);

        /** Set whether to log user manual retry. */
        public abstract Builder setLogUserManualRetry(boolean value);

        /** Set pair falure counts. */
        public abstract Builder setPairFailureCounts(int counts);

        /**
         * Set whether to use pair flow to show ui when pairing is finished without connecting
         * profile..
         */
        public abstract Builder setEnablePairFlowShowUiWithoutProfileConnection(boolean value);

        /** Set whether to log pairing with cached module Id. */
        public abstract Builder setLogPairWithCachedModelId(boolean value);

        /** Set possible cached device address. */
        public abstract Builder setPossibleCachedDeviceAddress(String value);

        /** Set paired device count from the same module Id. */
        public abstract Builder setSameModelIdPairedDeviceCount(int value);

        /** Set whether the bonded device address is from cache. */
        public abstract Builder setIsDeviceFinishCheckAddressFromCache(boolean value);

        /** Set whether to directly connect profile if modelId is in cache. */
        public abstract Builder setDirectConnectProfileIfModelIdInCache(boolean value);

        /** Set cached device address. */
        public abstract Builder setCachedDeviceAddress(String value);

        /** Builds a Preferences instance. */
        public abstract Preferences build();
    }

    /** Whether a given Uuid is supported. */
    public boolean isSupportedProfile(short profileUuid) {
        return Constants.PROFILES.containsKey(profileUuid)
                && Shorts.contains(
                Bytes.toShorts(ByteOrder.BIG_ENDIAN, getSupportedProfileUuids()), profileUuid);
    }

    /**
     * Information that will be used for logging.
     */
    @AutoValue
    public abstract static class ExtraLoggingInformation {

        /** Returns model Id. */
        public abstract String getModelId();

        /** Converts an instance to a builder. */
        public abstract Builder toBuilder();

        /** Creates a builder for ExtraLoggingInformation. */
        public static Builder builder() {
            return new AutoValue_Preferences_ExtraLoggingInformation.Builder();
        }

        /**
         * Extra logging information builder.
         */
        @AutoValue.Builder
        public abstract static class Builder {

            /** Set model ID. */
            public abstract Builder setModelId(String modelId);

            /** Builds extra logging information. */
            public abstract ExtraLoggingInformation build();
        }
    }
}
