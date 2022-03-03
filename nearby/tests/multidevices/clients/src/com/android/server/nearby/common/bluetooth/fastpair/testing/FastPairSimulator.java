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

package com.android.server.nearby.common.bluetooth.fastpair.testing;

import static android.bluetooth.BluetoothAdapter.EXTRA_STATE;
import static android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE;
import static android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
import static android.bluetooth.BluetoothAdapter.SCAN_MODE_NONE;
import static android.bluetooth.BluetoothAdapter.STATE_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_ON;
import static android.bluetooth.BluetoothDevice.ERROR;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE;

import static com.android.server.nearby.common.bluetooth.fastpair.AesEcbSingleBlockEncryption.AES_BLOCK_LENGTH;
import static com.android.server.nearby.common.bluetooth.fastpair.AesEcbSingleBlockEncryption.encrypt;
import static com.android.server.nearby.common.bluetooth.fastpair.Bytes.toBytes;
import static com.android.server.nearby.common.bluetooth.fastpair.Constants.A2DP_SINK_SERVICE_UUID;
import static com.android.server.nearby.common.bluetooth.fastpair.Constants.TransportDiscoveryService.BLUETOOTH_SIG_ORGANIZATION_ID;
import static com.android.server.nearby.common.bluetooth.fastpair.EllipticCurveDiffieHellmanExchange.PUBLIC_KEY_LENGTH;
import static com.android.server.nearby.common.bluetooth.fastpair.MessageStreamHmacEncoder.SECTION_NONCE_LENGTH;
import static com.android.server.nearby.common.bluetooth.fastpair.testing.RfcommServer.State.CONNECTED;
import static com.android.server.nearby.common.bluetooth.testability.android.bluetooth.BluetoothManager.wrap;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.primitives.Bytes.concat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass.Device.Major;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nearby.multidevices.fastpair.EventStreamProtocol.AcknowledgementEventCode;
import android.nearby.multidevices.fastpair.EventStreamProtocol.DeviceActionEventCode;
import android.nearby.multidevices.fastpair.EventStreamProtocol.DeviceCapabilitySyncEventCode;
import android.nearby.multidevices.fastpair.EventStreamProtocol.DeviceConfigurationEventCode;
import android.nearby.multidevices.fastpair.EventStreamProtocol.DeviceEventCode;
import android.nearby.multidevices.fastpair.EventStreamProtocol.EventGroup;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Consumer;

import com.android.server.nearby.common.bloomfilter.BloomFilter;
import com.android.server.nearby.common.bloomfilter.FastPairBloomFilterHasher;
import com.android.server.nearby.common.bluetooth.BluetoothException;
import com.android.server.nearby.common.bluetooth.BluetoothGattException;
import com.android.server.nearby.common.bluetooth.fastpair.AesEcbSingleBlockEncryption;
import com.android.server.nearby.common.bluetooth.fastpair.BluetoothAddress;
import com.android.server.nearby.common.bluetooth.fastpair.Bytes.Value;
import com.android.server.nearby.common.bluetooth.fastpair.Constants;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.AccountKeyCharacteristic;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.BeaconActionsCharacteristic;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.BeaconActionsCharacteristic.BeaconActionType;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.FirmwareVersionCharacteristic;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.KeyBasedPairingCharacteristic;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.NameCharacteristic;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.PasskeyCharacteristic;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.TransportDiscoveryService;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.TransportDiscoveryService.BrHandoverDataCharacteristic;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.TransportDiscoveryService.ControlPointCharacteristic;
import com.android.server.nearby.common.bluetooth.fastpair.EllipticCurveDiffieHellmanExchange;
import com.android.server.nearby.common.bluetooth.fastpair.Ltv;
import com.android.server.nearby.common.bluetooth.fastpair.MessageStreamHmacEncoder;
import com.android.server.nearby.common.bluetooth.fastpair.NamingEncoder;
import com.android.server.nearby.common.bluetooth.fastpair.Reflect;
import com.android.server.nearby.common.bluetooth.fastpair.ReflectionException;
import com.android.server.nearby.common.bluetooth.gatt.server.BluetoothGattServerConfig;
import com.android.server.nearby.common.bluetooth.gatt.server.BluetoothGattServerConfig.ServiceConfig;
import com.android.server.nearby.common.bluetooth.gatt.server.BluetoothGattServerConnection;
import com.android.server.nearby.common.bluetooth.gatt.server.BluetoothGattServerConnection.Notifier;
import com.android.server.nearby.common.bluetooth.gatt.server.BluetoothGattServerHelper;
import com.android.server.nearby.common.bluetooth.gatt.server.BluetoothGattServlet;

import com.google.common.base.Ascii;
import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;

import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Simulates a Fast Pair device (e.g. a headset).
 *
 * <p>Note: There are two deviations from the spec:
 *
 * <ul>
 *   <li>Instead of using the public address when in pairing mode (discoverable), it always uses the
 *       random private address (RPA), because that's how stock Android works. To work around this,
 *       it implements the BR/EDR Handover profile (which is no longer part of the Fast Pair spec)
 *       when simulating a keyless device (i.e. Fast Pair 1.0), which allows the phone to ask for
 *       the public address. When there is an anti-spoofing key, i.e. Fast Pair 2.0, the public
 *       address is delivered via the Key-based Pairing handshake. b/79374759 tracks fixing this.
 *   <li>The simulator always identifies its device capabilities as Keyboard/Display, even when
 *       simulating a keyless (Fast Pair 1.0) device that should identify as NoInput/NoOutput.
 *       b/79377125 tracks fixing this.
 * </ul>
 *
 * @see {http://go/fast-pair-2-spec}
 */
public class FastPairSimulator {
    private static final String TAG = "FastPairSimulator";
    private final Logger logger = new Logger(TAG);

    /**
     * Headphones. Generated by
     * http://bluetooth-pentest.narod.ru/software/bluetooth_class_of_device-service_generator.html
     */
    private static final Value CLASS_OF_DEVICE =
            new Value(base16().decode("200418"), ByteOrder.BIG_ENDIAN);

    private static final byte[] SUPPORTED_SERVICES_LTV =
            new Ltv(
                    TransportDiscoveryService.SERVICE_UUIDS_16_BIT_LIST_TYPE,
                    toBytes(ByteOrder.LITTLE_ENDIAN, A2DP_SINK_SERVICE_UUID))
                    .getBytes();
    private static final byte[] TDS_CONTROL_POINT_RESPONSE_PARAMETER =
            Bytes.concat(new byte[]{BLUETOOTH_SIG_ORGANIZATION_ID}, SUPPORTED_SERVICES_LTV);

    private static final String SIMULATOR_FAKE_BLE_ADDRESS = "11:22:33:44:55:66";

    private static final long ADVERTISING_REFRESH_DELAY_1_MIN = TimeUnit.MINUTES.toMillis(1);
    private static final long ADVERTISING_REFRESH_DELAY_5_MINS = TimeUnit.MINUTES.toMillis(5);

    /** The user will be prompted to accept or deny the incoming pairing request */
    public static final int PAIRING_VARIANT_CONSENT = 3;

    /**
     * The user will be prompted to enter the passkey displayed on remote device. This is used for
     * Bluetooth 2.1 pairing.
     */
    public static final int PAIRING_VARIANT_DISPLAY_PASSKEY = 4;

    /**
     * The size of account key filter in bytes is (1.2*n + 3), n represents the size of account key,
     * see https://developers.google.com/nearby/fast-pair/spec#advertising_when_not_discoverable.
     * However we'd like to advertise something else, so we could only afford 8 account keys.
     *
     * <ul>
     *   <li>BLE flags: 3 bytes
     *   <li>TxPower: 3 bytes
     *   <li>FastPair: max 25 bytes
     *       <ul>
     *         <li>FastPair service data: 4 bytes
     *         <li>Flags: 1 byte
     *         <li>Account key filter: max 14 bytes (1 byte: length + type, 13 bytes: max 8 account
     *             keys)
     *         <li>Salt: 2 bytes
     *         <li>Battery: 4 bytes
     *       </ul>
     * </ul>
     */
    private String deviceFirmwareVersion = "1.1.0";

    private byte[] sessionNonce;

    private boolean useLogFullEvent = true;

    private enum ResultCode {
        SUCCESS((byte) 0x00),
        OP_CODE_NOT_SUPPORTED((byte) 0x01),
        INVALID_PARAMETER((byte) 0x02),
        UNSUPPORTED_ORGANIZATION_ID((byte) 0x03),
        OPERATION_FAILED((byte) 0x04);

        private final byte byteValue;

        ResultCode(byte byteValue) {
            this.byteValue = byteValue;
        }
    }

    private enum TransportState {
        OFF((byte) 0x00),
        ON((byte) 0x01),
        TEMPORARILY_UNAVAILABLE((byte) 0x10);

        private final byte byteValue;

        TransportState(byte byteValue) {
            this.byteValue = byteValue;
        }
    }

    private final Context context;
    private final Options options;
    private final Handler uiThreadHandler = new Handler(Looper.getMainLooper());
    // No thread pool: Only used in test app (outside gmscore) and in javatests/.../gmscore/.
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(); // exempt
    private final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    private final BroadcastReceiver broadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (shouldFailPairing) {
                        logger.log("Pairing disabled by test app switch");
                        return;
                    }
                    if (isDestroyed) {
                        // Sometimes this receiver does not successfully unregister in destroy()
                        // which causes events to occur after the simulator is stopped, so ignore
                        // those events.
                        logger.log("Intent received after simulator destroyed, ignoring");
                        return;
                    }
                    BluetoothDevice device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE);
                    switch (intent.getAction()) {
                        case BluetoothAdapter.ACTION_SCAN_MODE_CHANGED:
                            if (isDiscoverable()) {
                                isDiscoverableLatch.countDown();
                            }
                            break;
                        case BluetoothDevice.ACTION_PAIRING_REQUEST:
                            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT,
                                    ERROR);
                            int key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, ERROR);
                            logger.log(
                                    "Pairing request, variant=%d, key=%s", variant,
                                    key == ERROR ? "(none)" : key);

                            // Prevent Bluetooth Settings from getting the pairing request.
                            abortBroadcast();

                            pairingDevice = device;
                            if (secret == null) {
                                // We haven't done the handshake over GATT to agree on the shared
                                // secret. For now, just accept anyway (so we can still simulate
                                // old 1.0 model IDs).
                                logger.log("No handshake, auto-accepting anyway.");
                                setPasskeyConfirmation(true);
                            } else if (variant
                                    == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION) {
                                // Store the passkey. And check it, since there's a race (see
                                // method for why). Usually this check is a no-op and we'll get
                                // the passkey later over GATT.
                                localPasskey = key;
                                checkPasskey();
                            } else if (variant == PAIRING_VARIANT_DISPLAY_PASSKEY) {
                                if (passkeyEventCallback != null) {
                                    passkeyEventCallback.onPasskeyRequested(
                                            FastPairSimulator.this::enterPassKey);
                                } else {
                                    logger.log("passkeyEventCallback is not set!");
                                    enterPassKey(key);
                                }
                            } else if (variant == PAIRING_VARIANT_CONSENT) {
                                setPasskeyConfirmation(true);

                            } else if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                                if (passkeyEventCallback != null) {
                                    passkeyEventCallback.onPasskeyRequested(
                                            (int pin) -> {
                                                byte[] newPin = convertPinToBytes(
                                                        String.format(Locale.ENGLISH, "%d", pin));
                                                pairingDevice.setPin(newPin);
                                            });
                                }
                            } else {
                                // Reject the pairing request if it's not using the Numeric
                                // Comparison (aka Passkey Confirmation) method.
                                setPasskeyConfirmation(false);
                            }
                            break;
                        case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                            int bondState =
                                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                                            BluetoothDevice.BOND_NONE);
                            logger.log("Bond state to %s changed to %d", device, bondState);
                            switch (bondState) {
                                case BluetoothDevice.BOND_BONDING:
                                    // If we've started bonding, we shouldn't be advertising.
                                    advertiser.stopAdvertising();
                                    // Not discoverable anymore, but still connectable.
                                    setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE);
                                    break;
                                case BluetoothDevice.BOND_BONDED:
                                    // Once bonded, advertise the account keys.
                                    advertiser.startAdvertising(accountKeysServiceData());
                                    setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE);

                                    // If it is subsequent pair, we need to add paired device here.
                                    if (isSubsequentPair
                                            && secret != null
                                            && secret.length == AES_BLOCK_LENGTH) {
                                        addAccountKey(secret, pairingDevice);
                                    }
                                    break;
                                case BluetoothDevice.BOND_NONE:
                                    // If the bonding process fails, we should be advertising again.
                                    advertiser.startAdvertising(getServiceData());
                                    break;
                                default:
                                    break;
                            }
                            break;
                        case BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED:
                            logger.log(
                                    "Connection state to %s changed to %d",
                                    device,
                                    intent.getIntExtra(
                                            BluetoothAdapter.EXTRA_CONNECTION_STATE,
                                            BluetoothAdapter.STATE_DISCONNECTED));
                            break;
                        case BluetoothAdapter.ACTION_STATE_CHANGED:
                            int state = intent.getIntExtra(EXTRA_STATE, -1);
                            logger.log("Bluetooth adapter state=%s", state);
                            switch (state) {
                                case STATE_ON:
                                    startRfcommServer();
                                    break;
                                case STATE_OFF:
                                    stopRfcommServer();
                                    break;
                                default: // fall out
                            }
                            break;
                        default:
                            logger.log(new IllegalArgumentException(intent.toString()),
                                    "Received unexpected intent");
                            break;
                    }
                }
            };

    @Nullable
    private byte[] convertPinToBytes(@Nullable String pin) {
        if (TextUtils.isEmpty(pin)) {
            return null;
        }
        byte[] pinBytes;
        pinBytes = pin.getBytes(StandardCharsets.UTF_8);
        if (pinBytes.length <= 0 || pinBytes.length > 16) {
            return null;
        }
        return pinBytes;
    }

    private final NotifiableGattServlet passkeyServlet =
            new NotifiableGattServlet() {
                @Override
                // Simulating deprecated API {@code PasskeyCharacteristic.ID} for testing.
                @SuppressWarnings("deprecation")
                public BluetoothGattCharacteristic getBaseCharacteristic() {
                    return new BluetoothGattCharacteristic(
                            PasskeyCharacteristic.CUSTOM_128_BIT_UUID,
                            PROPERTY_WRITE | PROPERTY_INDICATE,
                            PERMISSION_WRITE);
                }

                @Override
                public void write(
                        BluetoothGattServerConnection connection, int offset, byte[] value) {
                    logger.log("Got value from passkey servlet: %s", base16().encode(value));
                    if (secret == null) {
                        logger.log("Ignoring write to passkey characteristic, no pairing secret.");
                        return;
                    }

                    try {
                        remotePasskey = PasskeyCharacteristic.decrypt(
                                PasskeyCharacteristic.Type.SEEKER, secret, value);
                        if (passkeyEventCallback != null) {
                            passkeyEventCallback.onRemotePasskeyReceived(remotePasskey);
                        }
                        checkPasskey();
                    } catch (GeneralSecurityException e) {
                        logger.log(
                                "Decrypting passkey value %s failed using key %s",
                                base16().encode(value), base16().encode(secret));
                    }
                }
            };

    private final NotifiableGattServlet deviceNameServlet =
            new NotifiableGattServlet() {
                @Override
                // Simulating deprecated API {@code NameCharacteristic.ID} for testing.
                @SuppressWarnings("deprecation")
                BluetoothGattCharacteristic getBaseCharacteristic() {
                    return new BluetoothGattCharacteristic(
                            NameCharacteristic.CUSTOM_128_BIT_UUID,
                            PROPERTY_WRITE | PROPERTY_INDICATE,
                            PERMISSION_WRITE);
                }

                @Override
                public void write(
                        BluetoothGattServerConnection connection, int offset, byte[] value) {
                    logger.log("Got value from device naming servlet: %s", base16().encode(value));
                    if (secret == null) {
                        logger.log("Ignoring write to name characteristic, no pairing secret.");
                        return;
                    }
                    // Parse the device name from seeker to write name into provider.
                    logger.log("Got name byte array size = %d", value.length);
                    try {
                        String decryptedDeviceName =
                                NamingEncoder.decodeNamingPacket(secret, value);
                        if (decryptedDeviceName != null) {
                            setDeviceName(decryptedDeviceName.getBytes(StandardCharsets.UTF_8));
                            logger.log("write device name = %s", decryptedDeviceName);
                        }
                    } catch (GeneralSecurityException e) {
                        logger.log(e, "Failed to decrypt device name.");
                    }
                    // For testing to make sure we get the new provider name from simulator.
                    if (writeNameCountDown != null) {
                        logger.log("finish count down latch to write device name.");
                        writeNameCountDown.countDown();
                    }
                }
            };

    private Value bluetoothAddress;
    private final FastPairAdvertiser advertiser;
    private final Map<String, BluetoothGattServerHelper> mBluetoothGattServerHelpers =
            new HashMap<>();
    private CountDownLatch isDiscoverableLatch = new CountDownLatch(1);
    private ScheduledFuture<?> revertDiscoverableFuture;
    private boolean shouldFailPairing = false;
    private boolean isDestroyed = false;
    private boolean isAdvertising;
    @Nullable
    private String bleAddress;
    private BluetoothDevice pairingDevice;
    private int localPasskey;
    private int remotePasskey;
    @Nullable
    private byte[] secret;
    @Nullable
    private byte[] accountKey; // The latest account key added.
    // The first account key added. Eddystone treats that account as the owner of the device.
    @Nullable
    private byte[] ownerAccountKey;
    @Nullable
    private PasskeyConfirmationCallback passkeyConfirmationCallback;
    @Nullable
    private DeviceNameCallback deviceNameCallback;
    @Nullable
    private PasskeyEventCallback passkeyEventCallback;
    private final List<BatteryValue> batteryValues;
    private boolean suppressBatteryNotification = false;
    private boolean suppressSubsequentPairingNotification = false;
    HandshakeRequest handshakeRequest;
    @Nullable
    private CountDownLatch writeNameCountDown;
    private final RfcommServer rfcommServer = new RfcommServer();
    private final boolean dataOnlyConnection;
    private boolean supportDynamicBufferSize = false;
    private NotifiableGattServlet beaconActionsServlet;
    private final FastPairSimulatorDatabase fastPairSimulatorDatabase;
    private boolean isSubsequentPair = false;

    /** Sets the flag for failing paring for debug purpose. */
    public void setShouldFailPairing(boolean shouldFailPairing) {
        this.shouldFailPairing = shouldFailPairing;
    }

    /** Gets the flag for failing paring for debug purpose. */
    public boolean getShouldFailPairing() {
        return shouldFailPairing;
    }

    /** Clear the battery values, then no battery information is packed when advertising. */
    public void clearBatteryValues() {
        batteryValues.clear();
    }

    /** Sets the battery items which will be included in the advertisement packet. */
    public void setBatteryValues(BatteryValue... batteryValues) {
        this.batteryValues.clear();
        Collections.addAll(this.batteryValues, batteryValues);
    }

    /** Sets whether the battery advertisement packet is within suppress type or not. */
    public void setSuppressBatteryNotification(boolean suppressBatteryNotification) {
        this.suppressBatteryNotification = suppressBatteryNotification;
    }

    /** Sets whether the account key data is within suppress type or not. */
    public void setSuppressSubsequentPairingNotification(boolean isSuppress) {
        suppressSubsequentPairingNotification = isSuppress;
    }

    /** Calls this to start advertising after some values are changed. */
    public void startAdvertising() {
        advertiser.startAdvertising(getServiceData());
    }

    /** Send Event Message on to rfcomm connected devices. */
    public void sendEventStreamMessageToRfcommDevices(EventGroup eventGroup) {
        // Send fake log when event code is logging and type is not using Log_Full event.
        if (eventGroup == EventGroup.LOGGING && !useLogFullEvent) {
            rfcommServer.sendFakeEventStreamLoggingMessage(
                    getDeviceName()
                            + " "
                            + getBleAddress()
                            + " send log at "
                            + new SimpleDateFormat("HH:mm:ss:SSS", Locale.US)
                            .format(Calendar.getInstance().getTime()));
        } else {
            rfcommServer.sendFakeEventStreamMessage(eventGroup);
        }
    }

    public void setUseLogFullEvent(boolean useLogFullEvent) {
        this.useLogFullEvent = useLogFullEvent;
    }

    /** An optional way to get status updates. */
    public interface Callback {
        /** Called when we change our BLE advertisement. */
        void onAdvertisingChanged();
    }

    /** A way for tests to get callbacks when passkey confirmation is invoked. */
    public interface PasskeyConfirmationCallback {
        void onPasskeyConfirmation(boolean confirm);
    }

    /** A way for simulator UI update to get callback when device name is changed. */
    public interface DeviceNameCallback {
        void onNameChanged(String deviceName);
    }

    /**
     * Callback when there comes a passkey input request from BT service, or receiving remote
     * device's passkey.
     */
    public interface PasskeyEventCallback {
        void onPasskeyRequested(KeyInputCallback keyInputCallback);

        void onRemotePasskeyReceived(int passkey);

        default void onPasskeyConfirmation(int passkey, Consumer<Boolean> isConfirmed) {
        }
    }

    /** Options for the simulator. */
    public static class Options {
        private final String mModelId;

        // TODO(b/143117318):Remove this when app-launch type has its own anti-spoofing key.
        private final String mAdvertisingModelId;

        @Nullable
        private final String mBluetoothAddress;

        @Nullable
        private final String mBleAddress;

        private final boolean mDataOnlyConnection;

        private final int mTxPowerLevel;

        private final boolean mEnableNameCharacteristic;

        private final Callback mCallback;

        private final boolean mIncludeTransportDataDescriptor;

        @Nullable
        private final byte[] mAntiSpoofingPrivateKey;

        private final boolean mUseRandomSaltForAccountKeyRotation;

        private final boolean mIsMemoryTest;

        private final boolean mBecomeDiscoverable;

        private final boolean mShowsPasskeyConfirmation;

        private final boolean mEnableBeaconActionsCharacteristic;

        private final boolean mRemoveAllDevicesDuringPairing;

        @Nullable
        private final ByteString mEddystoneIdentityKey;

        private Options(
                String modelId,
                String advertisingModelId,
                @Nullable String bluetoothAddress,
                @Nullable String bleAddress,
                boolean dataOnlyConnection,
                int txPowerLevel,
                boolean enableNameCharacteristic,
                Callback callback,
                boolean includeTransportDataDescriptor,
                @Nullable byte[] antiSpoofingPrivateKey,
                boolean useRandomSaltForAccountKeyRotation,
                boolean isMemoryTest,
                boolean becomeDiscoverable,
                boolean showsPasskeyConfirmation,
                boolean enableBeaconActionsCharacteristic,
                boolean removeAllDevicesDuringPairing,
                @Nullable ByteString eddystoneIdentityKey) {
            this.mModelId = modelId;
            this.mAdvertisingModelId = advertisingModelId;
            this.mBluetoothAddress = bluetoothAddress;
            this.mBleAddress = bleAddress;
            this.mDataOnlyConnection = dataOnlyConnection;
            this.mTxPowerLevel = txPowerLevel;
            this.mEnableNameCharacteristic = enableNameCharacteristic;
            this.mCallback = callback;
            this.mIncludeTransportDataDescriptor = includeTransportDataDescriptor;
            this.mAntiSpoofingPrivateKey = antiSpoofingPrivateKey;
            this.mUseRandomSaltForAccountKeyRotation = useRandomSaltForAccountKeyRotation;
            this.mIsMemoryTest = isMemoryTest;
            this.mBecomeDiscoverable = becomeDiscoverable;
            this.mShowsPasskeyConfirmation = showsPasskeyConfirmation;
            this.mEnableBeaconActionsCharacteristic = enableBeaconActionsCharacteristic;
            this.mRemoveAllDevicesDuringPairing = removeAllDevicesDuringPairing;
            this.mEddystoneIdentityKey = eddystoneIdentityKey;
        }

        public String getModelId() {
            return mModelId;
        }

        // TODO(b/143117318):Remove this when app-launch type has its own anti-spoofing key.
        public String getAdvertisingModelId() {
            return mAdvertisingModelId;
        }

        @Nullable
        public String getBluetoothAddress() {
            return mBluetoothAddress;
        }

        @Nullable
        public String getBleAddress() {
            return mBleAddress;
        }

        public boolean getDataOnlyConnection() {
            return mDataOnlyConnection;
        }

        public int getTxPowerLevel() {
            return mTxPowerLevel;
        }

        public boolean getEnableNameCharacteristic() {
            return mEnableNameCharacteristic;
        }

        public Callback getCallback() {
            return mCallback;
        }

        public boolean getIncludeTransportDataDescriptor() {
            return mIncludeTransportDataDescriptor;
        }

        @Nullable
        public byte[] getAntiSpoofingPrivateKey() {
            return mAntiSpoofingPrivateKey;
        }

        public boolean getUseRandomSaltForAccountKeyRotation() {
            return mUseRandomSaltForAccountKeyRotation;
        }

        public boolean getIsMemoryTest() {
            return mIsMemoryTest;
        }

        public boolean getBecomeDiscoverable() {
            return mBecomeDiscoverable;
        }

        public boolean getShowsPasskeyConfirmation() {
            return mShowsPasskeyConfirmation;
        }

        public boolean getEnableBeaconActionsCharacteristic() {
            return mEnableBeaconActionsCharacteristic;
        }

        public boolean getRemoveAllDevicesDuringPairing() {
            return mRemoveAllDevicesDuringPairing;
        }

        @Nullable
        public ByteString getEddystoneIdentityKey() {
            return mEddystoneIdentityKey;
        }

        /** Converts an instance to a builder. */
        public Builder toBuilder() {
            return new Options.Builder(this);
        }

        /** Constructs a builder. */
        public static Builder builder() {
            return new Options.Builder();
        }

        /** @param modelId Must be a 3-byte hex string. */
        public static Builder builder(String modelId) {
            return new Options.Builder()
                    .setModelId(Ascii.toUpperCase(modelId))
                    .setAdvertisingModelId(Ascii.toUpperCase(modelId))
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setCallback(() -> {
                    })
                    .setIncludeTransportDataDescriptor(true)
                    .setUseRandomSaltForAccountKeyRotation(false)
                    .setEnableNameCharacteristic(true)
                    .setDataOnlyConnection(false)
                    .setIsMemoryTest(false)
                    .setBecomeDiscoverable(true)
                    .setShowsPasskeyConfirmation(false)
                    .setEnableBeaconActionsCharacteristic(true)
                    .setRemoveAllDevicesDuringPairing(true);
        }

        /** A builder for {@link Options}. */
        public static class Builder {

            private String mModelId;

            // TODO(b/143117318):Remove this when app-launch type has its own anti-spoofing key.
            private String mAdvertisingModelId;

            @Nullable
            private String mBluetoothAddress;

            @Nullable
            private String mbleAddress;

            private boolean mDataOnlyConnection;

            private int mTxPowerLevel;

            private boolean mEnableNameCharacteristic;

            private Callback mCallback;

            private boolean mIncludeTransportDataDescriptor;

            @Nullable
            private byte[] mAntiSpoofingPrivateKey;

            private boolean mUseRandomSaltForAccountKeyRotation;

            private boolean mIsMemoryTest;

            private boolean mBecomeDiscoverable;

            private boolean mShowsPasskeyConfirmation;

            private boolean mEnableBeaconActionsCharacteristic;

            private boolean mRemoveAllDevicesDuringPairing;

            @Nullable
            private ByteString mEddystoneIdentityKey;

            private Builder() {
            }

            private Builder(Options option) {
                this.mModelId = option.mModelId;
                this.mAdvertisingModelId = option.mAdvertisingModelId;
                this.mBluetoothAddress = option.mBluetoothAddress;
                this.mbleAddress = option.mBleAddress;
                this.mDataOnlyConnection = option.mDataOnlyConnection;
                this.mTxPowerLevel = option.mTxPowerLevel;
                this.mEnableNameCharacteristic = option.mEnableNameCharacteristic;
                this.mCallback = option.mCallback;
                this.mIncludeTransportDataDescriptor = option.mIncludeTransportDataDescriptor;
                this.mAntiSpoofingPrivateKey = option.mAntiSpoofingPrivateKey;
                this.mUseRandomSaltForAccountKeyRotation =
                        option.mUseRandomSaltForAccountKeyRotation;
                this.mIsMemoryTest = option.mIsMemoryTest;
                this.mBecomeDiscoverable = option.mBecomeDiscoverable;
                this.mShowsPasskeyConfirmation = option.mShowsPasskeyConfirmation;
                this.mEnableBeaconActionsCharacteristic = option.mEnableBeaconActionsCharacteristic;
                this.mRemoveAllDevicesDuringPairing = option.mRemoveAllDevicesDuringPairing;
                this.mEddystoneIdentityKey = option.mEddystoneIdentityKey;
            }

            /**
             * Must be one of the {@code ADVERTISE_TX_POWER_*} levels in {@link AdvertiseSettings}.
             * Default is HIGH.
             */
            public Builder setTxPowerLevel(int txPowerLevel) {
                this.mTxPowerLevel = txPowerLevel;
                return this;
            }

            /**
             * Must be a 6-byte hex string (optionally with colons).
             * Default is this device's BT MAC.
             */
            public Builder setBluetoothAddress(@Nullable String bluetoothAddress) {
                this.mBluetoothAddress = bluetoothAddress;
                return this;
            }

            public Builder setBleAddress(@Nullable String bleAddress) {
                this.mbleAddress = bleAddress;
                return this;
            }

            /** A boolean to decide if enable name characteristic as simulator characteristic. */
            public Builder setEnableNameCharacteristic(boolean enable) {
                this.mEnableNameCharacteristic = enable;
                return this;
            }

            /** @see Callback */
            public Builder setCallback(Callback callback) {
                this.mCallback = callback;
                return this;
            }

            public Builder setDataOnlyConnection(boolean dataOnlyConnection) {
                this.mDataOnlyConnection = dataOnlyConnection;
                return this;
            }

            /**
             * Set whether to include the Transport Data descriptor, which has the list of supported
             * profiles. This is required by the spec, but if we can't get it, we recover gracefully
             * by assuming support for one of {A2DP, Headset}. Default is true.
             */
            public Builder setIncludeTransportDataDescriptor(
                    boolean includeTransportDataDescriptor) {
                this.mIncludeTransportDataDescriptor = includeTransportDataDescriptor;
                return this;
            }

            public Builder setAntiSpoofingPrivateKey(@Nullable byte[] antiSpoofingPrivateKey) {
                this.mAntiSpoofingPrivateKey = antiSpoofingPrivateKey;
                return this;
            }

            public Builder setUseRandomSaltForAccountKeyRotation(
                    boolean useRandomSaltForAccountKeyRotation) {
                this.mUseRandomSaltForAccountKeyRotation = useRandomSaltForAccountKeyRotation;
                return this;
            }

            // TODO(b/143117318):Remove this when app-launch type has its own anti-spoofing key.
            public Builder setAdvertisingModelId(String modelId) {
                this.mAdvertisingModelId = modelId;
                return this;
            }

            public Builder setIsMemoryTest(boolean isMemoryTest) {
                this.mIsMemoryTest = isMemoryTest;
                return this;
            }

            public Builder setBecomeDiscoverable(boolean becomeDiscoverable) {
                this.mBecomeDiscoverable = becomeDiscoverable;
                return this;
            }

            public Builder setShowsPasskeyConfirmation(boolean showsPasskeyConfirmation) {
                this.mShowsPasskeyConfirmation = showsPasskeyConfirmation;
                return this;
            }

            public Builder setEnableBeaconActionsCharacteristic(
                    boolean enableBeaconActionsCharacteristic) {
                this.mEnableBeaconActionsCharacteristic = enableBeaconActionsCharacteristic;
                return this;
            }

            public Builder setRemoveAllDevicesDuringPairing(boolean removeAllDevicesDuringPairing) {
                this.mRemoveAllDevicesDuringPairing = removeAllDevicesDuringPairing;
                return this;
            }

            /**
             * Non-public because this is required to create a builder. See
             * {@link Options#builder}.
             */
            public Builder setModelId(String modelId) {
                this.mModelId = modelId;
                return this;
            }

            public Builder setEddystoneIdentityKey(@Nullable ByteString eddystoneIdentityKey) {
                this.mEddystoneIdentityKey = eddystoneIdentityKey;
                return this;
            }

            // Custom builder in order to normalize properties. go/autovalue/builders-howto
            public Options build() {
                return new Options(
                        Ascii.toUpperCase(mModelId),
                        Ascii.toUpperCase(mAdvertisingModelId),
                        mBluetoothAddress,
                        mbleAddress,
                        mDataOnlyConnection,
                        mTxPowerLevel,
                        mEnableNameCharacteristic,
                        mCallback,
                        mIncludeTransportDataDescriptor,
                        mAntiSpoofingPrivateKey,
                        mUseRandomSaltForAccountKeyRotation,
                        mIsMemoryTest,
                        mBecomeDiscoverable,
                        mShowsPasskeyConfirmation,
                        mEnableBeaconActionsCharacteristic,
                        mRemoveAllDevicesDuringPairing,
                        mEddystoneIdentityKey);
            }
        }
    }

    public FastPairSimulator(Context context, Options options) {
        this.context = context;
        this.options = options;

        this.batteryValues = new ArrayList<>();

        String bluetoothAddress =
                !TextUtils.isEmpty(options.getBluetoothAddress())
                        ? options.getBluetoothAddress()
                        : Settings.Secure.getString(context.getContentResolver(),
                                "bluetooth_address");
        if (bluetoothAddress == null && VERSION.SDK_INT >= VERSION_CODES.O) {
            // Requires a modified Android O build for access to bluetoothAdapter.getAddress().
            // See http://google3/java/com/google/location/nearby/apps/fastpair/simulator/README.md.
            bluetoothAddress = bluetoothAdapter.getAddress();
        }
        this.bluetoothAddress =
                new Value(BluetoothAddress.decode(bluetoothAddress), ByteOrder.BIG_ENDIAN);
        this.bleAddress = options.getBleAddress();
        this.dataOnlyConnection = options.getDataOnlyConnection();
        this.advertiser = new OreoFastPairAdvertiser(this);

        fastPairSimulatorDatabase = new FastPairSimulatorDatabase(context);

        byte[] deviceName = getDeviceNameInBytes();
        logger.log(
                "Provider default device name is %s",
                deviceName != null ? new String(deviceName, StandardCharsets.UTF_8) : null);

        if (dataOnlyConnection) {
            // To get BLE address, we need to start advertising first, and then
            // {@code#setBleAddress} will be called with BLE address.
            advertiser.startAdvertising(modelIdServiceData(/* forAdvertising= */ true));
        } else {
            // Make this so that the simulator doesn't start automatically.
            // This is tricky since the simulator is used in our integ tests as well.
            start(bleAddress != null ? bleAddress : bluetoothAddress);
        }
    }

    public void start(String address) {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        context.registerReceiver(broadcastReceiver, filter);

        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothGattServerHelper bluetoothGattServerHelper =
                new BluetoothGattServerHelper(context, wrap(bluetoothManager));
        mBluetoothGattServerHelpers.put(address, bluetoothGattServerHelper);

        if (options.getBecomeDiscoverable()) {
            try {
                becomeDiscoverable();
            } catch (InterruptedException | TimeoutException e) {
                logger.log(e, "Error becoming discoverable");
            }
        }

        advertiser.startAdvertising(modelIdServiceData(/* forAdvertising= */ true));
        startGattServer(bluetoothGattServerHelper);
        startRfcommServer();
        scheduleAdvertisingRefresh();
    }

    /**
     * Regenerate service data on a fixed interval.
     * This causes the bloom filter to be refreshed and a different salt to be used for rotation.
     */
    @SuppressWarnings("FutureReturnValueIgnored")
    private void scheduleAdvertisingRefresh() {
        executor.scheduleAtFixedRate(
                () -> {
                    if (isAdvertising) {
                        advertiser.startAdvertising(getServiceData());
                    }
                },
                options.getIsMemoryTest()
                        ? ADVERTISING_REFRESH_DELAY_5_MINS
                        : ADVERTISING_REFRESH_DELAY_1_MIN,
                options.getIsMemoryTest()
                        ? ADVERTISING_REFRESH_DELAY_5_MINS
                        : ADVERTISING_REFRESH_DELAY_1_MIN,
                TimeUnit.MILLISECONDS);
    }

    public void destroy() {
        try {
            logger.log("Destroying simulator");
            isDestroyed = true;
            context.unregisterReceiver(broadcastReceiver);
            advertiser.stopAdvertising();
            for (BluetoothGattServerHelper helper : mBluetoothGattServerHelpers.values()) {
                helper.close();
            }
            stopRfcommServer();
            deviceNameCallback = null;
            executor.shutdownNow();
        } catch (IllegalArgumentException ignored) {
            // Happens if you haven't given us permissions yet, so we didn't register the receiver.
        }
    }

    public boolean isDestroyed() {
        return isDestroyed;
    }

    @Nullable
    public String getBluetoothAddress() {
        return BluetoothAddress.encode(bluetoothAddress.getBytes(ByteOrder.BIG_ENDIAN));
    }

    public boolean isAdvertising() {
        return isAdvertising;
    }

    public void setIsAdvertising(boolean isAdvertising) {
        if (this.isAdvertising != isAdvertising) {
            this.isAdvertising = isAdvertising;
            options.getCallback().onAdvertisingChanged();
        }
    }

    public void stopAdvertising() {
        advertiser.stopAdvertising();
    }

    public void setBleAddress(String bleAddress) {
        this.bleAddress = bleAddress;
        if (dataOnlyConnection) {
            bluetoothAddress = new Value(BluetoothAddress.decode(bleAddress), ByteOrder.BIG_ENDIAN);
            start(bleAddress);
        }
        // When BLE address changes, needs to send BLE address to the client again.
        sendDeviceBleAddress(bleAddress);

        // If we are advertising something other than the model id (e.g. the bloom filter), restart
        // the advertisement so that it is updated with the new address.
        if (isAdvertising() && !isDiscoverable()) {
            advertiser.startAdvertising(getServiceData());
        }
    }

    @Nullable
    public String getBleAddress() {
        return bleAddress;
    }

    // This method is only for testing to make test block until write name success or time out.
    @VisibleForTesting
    public void setCountDownLatchToWriteName(CountDownLatch countDownLatch) {
        logger.log("Set up count down latch to write device name.");
        writeNameCountDown = countDownLatch;
    }

    public boolean areBeaconActionsNotificationsEnabled() {
        return beaconActionsServlet.areNotificationsEnabled();
    }

    private abstract class NotifiableGattServlet extends BluetoothGattServlet {
        private final Map<BluetoothGattServerConnection, Notifier> connections = new HashMap<>();

        abstract BluetoothGattCharacteristic getBaseCharacteristic();

        @Override
        public BluetoothGattCharacteristic getCharacteristic() {
            // Enabling indication requires the Client Characteristic Configuration descriptor.
            BluetoothGattCharacteristic characteristic = getBaseCharacteristic();
            characteristic.addDescriptor(
                    new BluetoothGattDescriptor(
                            Constants.CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR_UUID,
                            BluetoothGattDescriptor.PERMISSION_READ
                                    | BluetoothGattDescriptor.PERMISSION_WRITE));
            return characteristic;
        }

        @Override
        public void enableNotification(BluetoothGattServerConnection connection, Notifier notifier)
                throws BluetoothGattException {
            logger.log("Registering notifier for %s", getCharacteristic());
            connections.put(connection, notifier);
        }

        @Override
        public void disableNotification(BluetoothGattServerConnection connection, Notifier notifier)
                throws BluetoothGattException {
            logger.log("Removing notifier for %s", getCharacteristic());
            connections.remove(connection);
        }

        boolean areNotificationsEnabled() {
            return !connections.isEmpty();
        }

        void sendNotification(byte[] data) {
            if (connections.isEmpty()) {
                logger.log("Not sending notify as no notifier registered");
                return;
            }
            // Needs to be on a separate thread to avoid deadlocking and timing out (waits for a
            // callback from OS, which happens on the main thread).
            executor.execute(
                    () -> {
                        for (Map.Entry<BluetoothGattServerConnection, Notifier> entry :
                                connections.entrySet()) {
                            try {
                                logger.log("Sending notify %s to %s",
                                        getCharacteristic(),
                                        entry.getKey().getDevice().getAddress());
                                entry.getValue().notify(data);
                            } catch (BluetoothException e) {
                                logger.log(
                                        e,
                                        "Failed to notify (indicate) result of %s to %s",
                                        getCharacteristic(),
                                        entry.getKey().getDevice().getAddress());
                            }
                        }
                    });
        }
    }

    private void startRfcommServer() {
        rfcommServer.setRequestHandler(this::handleRfcommServerRequest);
        rfcommServer.setStateMonitor(state -> {
            logger.log("RfcommServer is in %s state", state);
            if (CONNECTED.equals(state)) {
                sendModelId();
                sendDeviceBleAddress(bleAddress);
                sendFirmwareVersion();
                sendSessionNonce();
            }
        });
        rfcommServer.start();
    }

    private void handleRfcommServerRequest(int eventGroup, int eventCode, byte[] data) {
        switch (eventGroup) {
            case EventGroup.DEVICE_VALUE:
                if (data == null) {
                    break;
                }

                String deviceValue = base16().encode(data);
                if (eventCode == DeviceEventCode.DEVICE_CAPABILITY_VALUE) {
                    logger.log("Received phone capability: %s", deviceValue);
                } else if (eventCode == DeviceEventCode.PLATFORM_TYPE_VALUE) {
                    logger.log("Received platform type: %s", deviceValue);
                }
                break;
            case EventGroup.DEVICE_ACTION_VALUE:
                if (eventCode == DeviceActionEventCode.DEVICE_ACTION_RING_VALUE) {
                    logger.log("receive device action with ring value, data = %d",
                            data[0]);
                    sendDeviceRingActionResponse();
                    // Simulate notifying the seeker that the ringing has stopped due
                    // to user interaction (such as tapping the bud).
                    uiThreadHandler.postDelayed(this::sendDeviceRingStoppedAction,
                            5000);
                }
                break;
            case EventGroup.DEVICE_CONFIGURATION_VALUE:
                if (eventCode == DeviceConfigurationEventCode.CONFIGURATION_BUFFER_SIZE_VALUE) {
                    logger.log(
                            "receive device action with buffer size value, data = %s",
                            base16().encode(data));
                    sendSetBufferActionResponse(data);
                }
                break;
            case EventGroup.DEVICE_CAPABILITY_SYNC_VALUE:
                if (eventCode == DeviceCapabilitySyncEventCode.REQUEST_CAPABILITY_UPDATE_VALUE) {
                    logger.log("receive device capability update request.");
                    sendCapabilitySync();
                }
                break;
            default: // fall out
                break;
        }
    }

    private void stopRfcommServer() {
        rfcommServer.stop();
        rfcommServer.setRequestHandler(null);
        rfcommServer.setStateMonitor(null);
    }

    private void sendModelId() {
        logger.log("Send model ID to the client");
        rfcommServer.send(
                EventGroup.DEVICE_VALUE,
                DeviceEventCode.DEVICE_MODEL_ID_VALUE,
                modelIdServiceData(/* forAdvertising= */ false));
    }

    private void sendDeviceBleAddress(String bleAddress) {
        logger.log("Send BLE address (%s) to the client", bleAddress);
        if (bleAddress != null) {
            rfcommServer.send(
                    EventGroup.DEVICE_VALUE,
                    DeviceEventCode.DEVICE_BLE_ADDRESS_VALUE,
                    BluetoothAddress.decode(bleAddress));
        }
    }

    private void sendFirmwareVersion() {
        logger.log("Send Firmware Version (%s) to the client", deviceFirmwareVersion);
        rfcommServer.send(
                EventGroup.DEVICE_VALUE,
                DeviceEventCode.FIRMWARE_VERSION_VALUE,
                deviceFirmwareVersion.getBytes());
    }

    private void sendSessionNonce() {
        logger.log("Send SessionNonce (%s) to the client", deviceFirmwareVersion);
        SecureRandom secureRandom = new SecureRandom();
        sessionNonce = new byte[SECTION_NONCE_LENGTH];
        secureRandom.nextBytes(sessionNonce);
        rfcommServer.send(
                EventGroup.DEVICE_VALUE, DeviceEventCode.SECTION_NONCE_VALUE, sessionNonce);
    }

    private void sendDeviceRingActionResponse() {
        logger.log("Send device ring action response to the client");
        rfcommServer.send(
                EventGroup.ACKNOWLEDGEMENT_VALUE,
                AcknowledgementEventCode.ACKNOWLEDGEMENT_ACK_VALUE,
                new byte[]{
                        EventGroup.DEVICE_ACTION_VALUE,
                        DeviceActionEventCode.DEVICE_ACTION_RING_VALUE
                });
    }

    private void sendSetBufferActionResponse(byte[] data) {
        boolean hmacPassed = false;
        for (ByteString accountKey : getAccountKeys()) {
            try {
                if (MessageStreamHmacEncoder.verifyHmac(
                        accountKey.toByteArray(), sessionNonce, data)) {
                    hmacPassed = true;
                    logger.log("Buffer size data matches account key %s",
                            base16().encode(accountKey.toByteArray()));
                    break;
                }
            } catch (GeneralSecurityException e) {
                // Ignore.
            }
        }
        if (hmacPassed) {
            logger.log("Send buffer size action response %s to the client", base16().encode(data));
            rfcommServer.send(
                    EventGroup.ACKNOWLEDGEMENT_VALUE,
                    AcknowledgementEventCode.ACKNOWLEDGEMENT_ACK_VALUE,
                    new byte[]{
                            EventGroup.DEVICE_CONFIGURATION_VALUE,
                            DeviceConfigurationEventCode.CONFIGURATION_BUFFER_SIZE_VALUE,
                            data[0],
                            data[1],
                            data[2]
                    });
        } else {
            logger.log("No matched account key for sendSetBufferActionResponse");
        }
    }

    private void sendCapabilitySync() {
        logger.log("Send capability sync to the client");
        if (supportDynamicBufferSize) {
            logger.log("Send dynamic buffer size range to the client");
            rfcommServer.send(
                    EventGroup.DEVICE_CAPABILITY_SYNC_VALUE,
                    DeviceCapabilitySyncEventCode.CONFIGURABLE_BUFFER_SIZE_RANGE_VALUE,
                    new byte[]{
                            0x00, 0x01, (byte) 0xf4, 0x00, 0x64, 0x00, (byte) 0xc8,
                            0x01, 0x00, (byte) 0xff, 0x00, 0x01, 0x00, (byte) 0x88,
                            0x02, 0x01, (byte) 0xff, 0x01, 0x01, 0x01, (byte) 0x88,
                            0x03, 0x02, (byte) 0xff, 0x02, 0x01, 0x02, (byte) 0x88,
                            0x04, 0x03, (byte) 0xff, 0x03, 0x01, 0x03, (byte) 0x88
                    });
        }
    }

    private void sendDeviceRingStoppedAction() {
        logger.log("Sending device ring stopped action to the client");
        rfcommServer.send(
                EventGroup.DEVICE_ACTION_VALUE,
                DeviceActionEventCode.DEVICE_ACTION_RING_VALUE,
                // Additional data for stopping ringing on all components.
                new byte[]{0x00});
    }

    private void startGattServer(BluetoothGattServerHelper helper) {
        BluetoothGattServlet tdsControlPointServlet =
                new NotifiableGattServlet() {
                    @Override
                    public BluetoothGattCharacteristic getBaseCharacteristic() {
                        return new BluetoothGattCharacteristic(ControlPointCharacteristic.ID,
                                PROPERTY_WRITE | PROPERTY_INDICATE, PERMISSION_WRITE);
                    }

                    @Override
                    public void write(
                            BluetoothGattServerConnection connection, int offset, byte[] value)
                            throws BluetoothGattException {
                        logger.log("Requested TDS Control Point write, value=%s",
                                base16().encode(value));

                        ResultCode resultCode = checkTdsControlPointRequest(value);
                        if (resultCode == ResultCode.SUCCESS) {
                            try {
                                becomeDiscoverable();
                            } catch (TimeoutException | InterruptedException e) {
                                logger.log(e, "Failed to become discoverable");
                                resultCode = ResultCode.OPERATION_FAILED;
                            }
                        }

                        logger.log("Request complete, resultCode=%s", resultCode);

                        logger.log("Sending TDS Control Point response indication");
                        sendNotification(
                                Bytes.concat(
                                        new byte[]{
                                                getTdsControlPointOpCode(value),
                                                resultCode.byteValue,
                                        },
                                        resultCode == ResultCode.SUCCESS
                                                ? TDS_CONTROL_POINT_RESPONSE_PARAMETER
                                                : new byte[0]));
                    }
                };

        BluetoothGattServlet brHandoverDataServlet =
                new BluetoothGattServlet() {

                    @Override
                    public BluetoothGattCharacteristic getCharacteristic() {
                        return new BluetoothGattCharacteristic(BrHandoverDataCharacteristic.ID,
                                PROPERTY_READ, PERMISSION_READ);
                    }

                    @Override
                    public byte[] read(BluetoothGattServerConnection connection, int offset) {
                        return Bytes.concat(
                                new byte[]{BrHandoverDataCharacteristic.BR_EDR_FEATURES},
                                bluetoothAddress.getBytes(ByteOrder.LITTLE_ENDIAN),
                                CLASS_OF_DEVICE.getBytes(ByteOrder.LITTLE_ENDIAN));
                    }
                };

        BluetoothGattServlet bluetoothSigServlet =
                new BluetoothGattServlet() {

                    @Override
                    public BluetoothGattCharacteristic getCharacteristic() {
                        BluetoothGattCharacteristic characteristic =
                                new BluetoothGattCharacteristic(
                                        TransportDiscoveryService.BluetoothSigDataCharacteristic.ID,
                                        0 /* no properties */,
                                        0 /* no permissions */);

                        if (options.getIncludeTransportDataDescriptor()) {
                            characteristic.addDescriptor(
                                    new BluetoothGattDescriptor(
                                            TransportDiscoveryService.BluetoothSigDataCharacteristic
                                                    .BrTransportBlockDataDescriptor.ID,
                                            BluetoothGattDescriptor.PERMISSION_READ));
                        }
                        return characteristic;
                    }

                    @Override
                    public byte[] readDescriptor(
                            BluetoothGattServerConnection connection,
                            BluetoothGattDescriptor descriptor,
                            int offset)
                            throws BluetoothGattException {
                        return transportDiscoveryData();
                    }
                };

        BluetoothGattServlet accountKeyServlet =
                new BluetoothGattServlet() {
                    @Override
                    // Simulating deprecated API {@code AccountKeyCharacteristic.ID} for testing.
                    @SuppressWarnings("deprecation")
                    public BluetoothGattCharacteristic getCharacteristic() {
                        return new BluetoothGattCharacteristic(
                                AccountKeyCharacteristic.CUSTOM_128_BIT_UUID,
                                PROPERTY_WRITE,
                                PERMISSION_WRITE);
                    }

                    @Override
                    public void write(
                            BluetoothGattServerConnection connection, int offset, byte[] value) {
                        logger.log("Got value from account key servlet: %s",
                                base16().encode(value));
                        try {
                            addAccountKey(AesEcbSingleBlockEncryption.decrypt(secret, value),
                                    pairingDevice);
                        } catch (GeneralSecurityException e) {
                            logger.log(e, "Failed to decrypt account key.");
                        }
                        uiThreadHandler.post(
                                () -> advertiser.startAdvertising(accountKeysServiceData()));
                    }
                };

        BluetoothGattServlet firmwareVersionServlet =
                new BluetoothGattServlet() {
                    @Override
                    public BluetoothGattCharacteristic getCharacteristic() {
                        return new BluetoothGattCharacteristic(
                                FirmwareVersionCharacteristic.ID, PROPERTY_READ, PERMISSION_READ);
                    }

                    @Override
                    public byte[] read(BluetoothGattServerConnection connection, int offset) {
                        return deviceFirmwareVersion.getBytes();
                    }
                };

        BluetoothGattServlet keyBasedPairingServlet =
                new NotifiableGattServlet() {
                    @Override
                    // Simulating deprecated API {@code KeyBasedPairingCharacteristic.ID} for
                    // testing.
                    @SuppressWarnings("deprecation")
                    public BluetoothGattCharacteristic getBaseCharacteristic() {
                        return new BluetoothGattCharacteristic(
                                KeyBasedPairingCharacteristic.CUSTOM_128_BIT_UUID,
                                PROPERTY_WRITE | PROPERTY_INDICATE,
                                PERMISSION_WRITE);
                    }

                    @Override
                    public void write(
                            BluetoothGattServerConnection connection, int offset, byte[] value) {
                        logger.log("Requesting key based pairing handshake, value=%s",
                                base16().encode(value));

                        secret = null;
                        byte[] seekerPublicAddress = null;
                        if (value.length == AES_BLOCK_LENGTH) {

                            for (ByteString key : getAccountKeys()) {
                                byte[] candidateSecret = key.toByteArray();
                                try {
                                    seekerPublicAddress = handshake(candidateSecret, value);
                                    secret = candidateSecret;
                                    isSubsequentPair = true;
                                    break;
                                } catch (GeneralSecurityException e) {
                                    logger.log(e, "Failed to decrypt with %s",
                                            base16().encode(candidateSecret));
                                }
                            }
                        } else if (value.length == AES_BLOCK_LENGTH + PUBLIC_KEY_LENGTH
                                && options.getAntiSpoofingPrivateKey() != null) {
                            try {
                                byte[] encryptedRequest = Arrays.copyOf(value, AES_BLOCK_LENGTH);
                                byte[] receivedPublicKey =
                                        Arrays.copyOfRange(value, AES_BLOCK_LENGTH, value.length);
                                byte[] candidateSecret =
                                        EllipticCurveDiffieHellmanExchange.create(
                                                        options.getAntiSpoofingPrivateKey())
                                                .generateSecret(receivedPublicKey);
                                seekerPublicAddress = handshake(candidateSecret, encryptedRequest);
                                secret = candidateSecret;
                            } catch (Exception e) {
                                logger.log(
                                        e,
                                        "Failed to decrypt with anti-spoofing private key %s",
                                        base16().encode(options.getAntiSpoofingPrivateKey()));
                            }
                        } else {
                            logger.log("Packet length invalid, %d", value.length);
                            return;
                        }

                        if (secret == null) {
                            logger.log("Couldn't find a usable key to decrypt with.");
                            return;
                        }

                        logger.log("Found valid decryption key, %s", base16().encode(secret));
                        byte[] salt = new byte[9];
                        new Random().nextBytes(salt);
                        try {
                            byte[] data = concat(
                                    new byte[]{KeyBasedPairingCharacteristic.Response.TYPE},
                                    bluetoothAddress.getBytes(ByteOrder.BIG_ENDIAN), salt);
                            byte[] encryptedAddress = encrypt(secret, data);
                            logger.log(
                                    "Sending handshake response %s with size %d",
                                    base16().encode(encryptedAddress), encryptedAddress.length);
                            sendNotification(encryptedAddress);

                            // Notify seeker for NameCharacteristic to get provider device name
                            // when seeker request device name flag is true.
                            if (options.getEnableNameCharacteristic()
                                    && handshakeRequest.requestDeviceName()) {
                                byte[] encryptedResponse =
                                        getDeviceNameInBytes() != null ? createEncryptedDeviceName()
                                                : new byte[0];
                                logger.log(
                                        "Sending device name response %s with size %d",
                                        base16().encode(encryptedResponse),
                                        encryptedResponse.length);
                                deviceNameServlet.sendNotification(encryptedResponse);
                            }

                            // Disconnects the current connection to allow the following pairing
                            // request. Needs to be on a separate thread to avoid deadlocking and
                            // timing out (waits for a callback from OS, which happens on this
                            // thread).
                            //
                            // Note: The spec does not require you to disconnect from other
                            // devices at this point.
                            // If headphones support multiple simultaneous connections, they
                            // should stay connected. But Android fails to pair with the new
                            // device if we don't first disconnect from any other device.
                            logger.log("Skip remove bond, value=%s",
                                    options.getRemoveAllDevicesDuringPairing());
                            if (options.getRemoveAllDevicesDuringPairing()
                                    && handshakeRequest.getType()
                                    == HandshakeRequest.Type.KEY_BASED_PAIRING_REQUEST
                                    && !handshakeRequest.requestRetroactivePair()) {
                                executor.execute(() -> disconnect());
                            }

                            if (handshakeRequest.getType()
                                    == HandshakeRequest.Type.KEY_BASED_PAIRING_REQUEST
                                    && handshakeRequest.requestProviderInitialBonding()) {
                                // Run on executor to ensure it doesn't happen until after the
                                // notify (which tells the remote device what address to expect).
                                String seekerPublicAddressString =
                                        BluetoothAddress.encode(seekerPublicAddress);
                                executor.execute(() -> {
                                    logger.log("Sending pairing request to %s",
                                            seekerPublicAddressString);
                                    bluetoothAdapter.getRemoteDevice(
                                            seekerPublicAddressString).createBond();
                                });
                            }
                        } catch (GeneralSecurityException e) {
                            logger.log(e, "Failed to notify of static mac address");
                        }
                    }

                    @Nullable
                    private byte[] handshake(byte[] key, byte[] encryptedPairingRequest)
                            throws GeneralSecurityException {
                        handshakeRequest = new HandshakeRequest(key, encryptedPairingRequest);

                        byte[] decryptedAddress = handshakeRequest.getVerificationData();
                        if (bleAddress != null
                                && Arrays.equals(decryptedAddress,
                                BluetoothAddress.decode(bleAddress))
                                || Arrays.equals(decryptedAddress,
                                bluetoothAddress.getBytes(ByteOrder.BIG_ENDIAN))) {
                            logger.log("Address matches: %s", base16().encode(decryptedAddress));
                        } else {
                            throw new GeneralSecurityException(
                                    "Address (BLE or BR/EDR) is not correct: "
                                            + base16().encode(decryptedAddress)
                                            + ", "
                                            + bleAddress
                                            + ", "
                                            + getBluetoothAddress());
                        }

                        switch (handshakeRequest.getType()) {
                            case KEY_BASED_PAIRING_REQUEST:
                                return handleKeyBasedPairingRequest(handshakeRequest);
                            case ACTION_OVER_BLE:
                                return handleActionOverBleRequest(handshakeRequest);
                            case UNKNOWN:
                                // continue to throw the exception;
                        }
                        throw new GeneralSecurityException(
                                "Type is not correct: " + handshakeRequest.getType());
                    }

                    @Nullable
                    private byte[] handleKeyBasedPairingRequest(HandshakeRequest handshakeRequest)
                            throws GeneralSecurityException {
                        if (handshakeRequest.requestDiscoverable()) {
                            logger.log("Requested discoverability");
                            setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                        }

                        logger.log(
                                "KeyBasedPairing: initialBonding=%s, requestDeviceName=%s, "
                                        + "retroactivePair=%s",
                                handshakeRequest.requestProviderInitialBonding(),
                                handshakeRequest.requestDeviceName(),
                                handshakeRequest.requestRetroactivePair());

                        byte[] seekerPublicAddress = null;
                        if (handshakeRequest.requestProviderInitialBonding()
                                || handshakeRequest.requestRetroactivePair()) {
                            seekerPublicAddress = handshakeRequest.getSeekerPublicAddress();
                            logger.log(
                                    "Seeker sends BR/EDR address %s to provider",
                                    BluetoothAddress.encode(seekerPublicAddress));
                        }

                        if (handshakeRequest.requestRetroactivePair()) {
                            if (bluetoothAdapter.getRemoteDevice(seekerPublicAddress).getBondState()
                                    != BluetoothDevice.BOND_BONDED) {
                                throw new GeneralSecurityException(
                                        "Address (BR/EDR) is not bonded: "
                                                + BluetoothAddress.encode(seekerPublicAddress));
                            }
                        }

                        return seekerPublicAddress;
                    }

                    @Nullable
                    private byte[] handleActionOverBleRequest(HandshakeRequest handshakeRequest) {
                        // TODO(wollohchou): implement action over ble request.
                        if (handshakeRequest.requestDeviceAction()) {
                            logger.log("Requesting action over BLE, device action");
                        } else if (handshakeRequest.requestFollowedByAdditionalData()) {
                            logger.log(
                                    "Requesting action over BLE, followed by additional data, "
                                            + "type:%s",
                                    handshakeRequest.getAdditionalDataType());
                        } else {
                            logger.log("Requesting action over BLE");
                        }
                        return null;
                    }

                    /**
                     * @return The encrypted device name from provider for seeker to use.
                     */
                    private byte[] createEncryptedDeviceName() throws GeneralSecurityException {
                        byte[] deviceName = getDeviceNameInBytes();
                        String providerName = new String(deviceName, StandardCharsets.UTF_8);
                        logger.log(
                                "Sending handshake response for device name %s with size %d",
                                providerName, deviceName.length);
                        return NamingEncoder.encodeNamingPacket(secret, providerName);
                    }
                };

        beaconActionsServlet =
                new NotifiableGattServlet() {
                    private static final int GATT_ERROR_UNAUTHENTICATED = 0x80;
                    private static final int GATT_ERROR_INVALID_VALUE = 0x81;
                    private static final int NONCE_LENGTH = 8;
                    private static final int ONE_TIME_AUTH_KEY_OFFSET = 2;
                    private static final int ONE_TIME_AUTH_KEY_LENGTH = 8;
                    private static final int IDENTITY_KEY_LENGTH = 32;
                    private static final byte TRANSMISSION_POWER = 0;

                    private final SecureRandom random = new SecureRandom();
                    private final MessageDigest sha256;
                    @Nullable
                    private byte[] lastNonce;
                    @Nullable
                    private ByteString identityKey = options.getEddystoneIdentityKey();

                    {
                        try {
                            sha256 = MessageDigest.getInstance("SHA-256");
                            sha256.reset();
                        } catch (NoSuchAlgorithmException e) {
                            throw new IllegalStateException(
                                    "System missing SHA-256 implementation.", e);
                        }
                    }

                    @Override
                    // Simulating deprecated API {@code BeaconActionsCharacteristic.ID} for testing.
                    @SuppressWarnings("deprecation")
                    public BluetoothGattCharacteristic getBaseCharacteristic() {
                        return new BluetoothGattCharacteristic(
                                BeaconActionsCharacteristic.CUSTOM_128_BIT_UUID,
                                PROPERTY_READ | PROPERTY_WRITE | PROPERTY_NOTIFY,
                                PERMISSION_READ | PERMISSION_WRITE);
                    }

                    @Override
                    public byte[] read(BluetoothGattServerConnection connection, int offset) {
                        lastNonce = new byte[NONCE_LENGTH];
                        random.nextBytes(lastNonce);
                        return lastNonce;
                    }

                    @Override
                    public void write(
                            BluetoothGattServerConnection connection, int offset, byte[] value)
                            throws BluetoothGattException {
                        logger.log("Got value from beacon actions servlet: %s",
                                base16().encode(value));
                        if (value.length == 0) {
                            logger.log("Packet length invalid, %d", value.length);
                            throw new BluetoothGattException("Packet length invalid",
                                    GATT_ERROR_INVALID_VALUE);
                        }
                        switch (value[0]) {
                            case BeaconActionType.READ_BEACON_PARAMETERS:
                                handleReadBeaconParameters(value);
                                break;
                            case BeaconActionType.READ_PROVISIONING_STATE:
                                handleReadProvisioningState(value);
                                break;
                            case BeaconActionType.SET_EPHEMERAL_IDENTITY_KEY:
                                handleSetEphemeralIdentityKey(value);
                                break;
                            case BeaconActionType.CLEAR_EPHEMERAL_IDENTITY_KEY:
                            case BeaconActionType.READ_EPHEMERAL_IDENTITY_KEY:
                            case BeaconActionType.RING:
                            case BeaconActionType.READ_RINGING_STATE:
                                throw new BluetoothGattException(
                                        "Unimplemented beacon action",
                                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED);
                            default:
                                throw new BluetoothGattException(
                                        "Unknown beacon action",
                                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED);
                        }
                    }

                    private boolean verifyAccountKeyToken(byte[] value, boolean ownerOnly)
                            throws BluetoothGattException {
                        if (value.length < ONE_TIME_AUTH_KEY_LENGTH + ONE_TIME_AUTH_KEY_OFFSET) {
                            logger.log("Packet length invalid, %d", value.length);
                            throw new BluetoothGattException(
                                    "Packet length invalid", GATT_ERROR_INVALID_VALUE);
                        }
                        byte[] hashedAccountKey =
                                Arrays.copyOfRange(
                                        value,
                                        ONE_TIME_AUTH_KEY_OFFSET,
                                        ONE_TIME_AUTH_KEY_LENGTH + ONE_TIME_AUTH_KEY_OFFSET);
                        if (lastNonce == null) {
                            throw new BluetoothGattException(
                                    "Nonce wasn't set", GATT_ERROR_UNAUTHENTICATED);
                        }
                        if (ownerOnly) {
                            ByteString accountKey = getOwnerAccountKey();
                            if (accountKey != null) {
                                sha256.update(accountKey.toByteArray());
                                sha256.update(lastNonce);
                                return Arrays.equals(
                                        hashedAccountKey,
                                        Arrays.copyOf(sha256.digest(), ONE_TIME_AUTH_KEY_LENGTH));
                            }
                        } else {
                            Set<ByteString> accountKeys = getAccountKeys();
                            for (ByteString accountKey : accountKeys) {
                                sha256.update(accountKey.toByteArray());
                                sha256.update(lastNonce);
                                if (Arrays.equals(
                                        hashedAccountKey,
                                        Arrays.copyOf(sha256.digest(), ONE_TIME_AUTH_KEY_LENGTH))) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    }

                    private int getBeaconClock() {
                        return (int) TimeUnit.MILLISECONDS.toSeconds(SystemClock.elapsedRealtime());
                    }

                    private ByteString fromBytes(byte... bytes) {
                        return ByteString.copyFrom(bytes);
                    }

                    private byte[] intToByteArray(int value) {
                        byte[] data = new byte[4];
                        data[3] = (byte) value;
                        data[2] = (byte) (value >>> 8);
                        data[1] = (byte) (value >>> 16);
                        data[0] = (byte) (value >>> 24);
                        return data;
                    }

                    private void handleReadBeaconParameters(byte[] value)
                            throws BluetoothGattException {
                        if (!verifyAccountKeyToken(value, /* ownerOnly= */ false)) {
                            throw new BluetoothGattException(
                                    "failed to authenticate account key",
                                    GATT_ERROR_UNAUTHENTICATED);
                        }
                        sendNotification(
                                fromBytes(
                                        (byte) BeaconActionType.READ_BEACON_PARAMETERS,
                                        (byte) 5 /* data length */,
                                        TRANSMISSION_POWER)
                                        .concat(ByteString.copyFrom(
                                                intToByteArray(getBeaconClock())))
                                        .toByteArray());
                    }

                    private void handleReadProvisioningState(byte[] value)
                            throws BluetoothGattException {
                        if (!verifyAccountKeyToken(value, /* ownerOnly= */ false)) {
                            throw new BluetoothGattException(
                                    "failed to authenticate account key",
                                    GATT_ERROR_UNAUTHENTICATED);
                        }
                        byte flags = 0;
                        if (verifyAccountKeyToken(value, /* ownerOnly= */ true)) {
                            flags |= (byte) (1 << 1);
                        }
                        if (identityKey == null) {
                            sendNotification(
                                    fromBytes(
                                            (byte) BeaconActionType.READ_PROVISIONING_STATE,
                                            (byte) 1 /* data length */,
                                            flags)
                                            .toByteArray());
                        } else {
                            flags |= (byte) 1;
                            sendNotification(
                                    fromBytes(
                                            (byte) BeaconActionType.READ_PROVISIONING_STATE,
                                            (byte) 21 /* data length */,
                                            flags)
                                            .concat(
                                                    E2eeCalculator.computeE2eeEid(
                                                            identityKey, /* exponent= */ 10,
                                                            getBeaconClock()))
                                            .toByteArray());
                        }
                    }

                    private void handleSetEphemeralIdentityKey(byte[] value)
                            throws BluetoothGattException {
                        if (!verifyAccountKeyToken(value, /* ownerOnly= */ true)) {
                            throw new BluetoothGattException(
                                    "failed to authenticate owner account key",
                                    GATT_ERROR_UNAUTHENTICATED);
                        }
                        if (value.length
                                != ONE_TIME_AUTH_KEY_LENGTH + ONE_TIME_AUTH_KEY_OFFSET
                                + IDENTITY_KEY_LENGTH) {
                            logger.log("Packet length invalid, %d", value.length);
                            throw new BluetoothGattException("Packet length invalid",
                                    GATT_ERROR_INVALID_VALUE);
                        }
                        if (identityKey != null) {
                            throw new BluetoothGattException(
                                    "Device is already provisioned as Eddystone",
                                    GATT_ERROR_UNAUTHENTICATED);
                        }
                        identityKey = Crypto.aesEcbNoPaddingDecrypt(
                                ByteString.copyFrom(ownerAccountKey),
                                ByteString.copyFrom(value)
                                        .substring(ONE_TIME_AUTH_KEY_LENGTH
                                                + ONE_TIME_AUTH_KEY_OFFSET));
                    }
                };

        ServiceConfig fastPairServiceConfig =
                new ServiceConfig()
                        .addCharacteristic(accountKeyServlet)
                        .addCharacteristic(keyBasedPairingServlet)
                        .addCharacteristic(passkeyServlet)
                        .addCharacteristic(firmwareVersionServlet);
        if (options.getEnableBeaconActionsCharacteristic()) {
            fastPairServiceConfig.addCharacteristic(beaconActionsServlet);
        }

        BluetoothGattServerConfig config =
                new BluetoothGattServerConfig()
                        .addService(
                                TransportDiscoveryService.ID,
                                new ServiceConfig()
                                        .addCharacteristic(tdsControlPointServlet)
                                        .addCharacteristic(brHandoverDataServlet)
                                        .addCharacteristic(bluetoothSigServlet))
                        .addService(
                                FastPairService.ID,
                                options.getEnableNameCharacteristic()
                                        ? fastPairServiceConfig.addCharacteristic(deviceNameServlet)
                                        : fastPairServiceConfig);

        logger.log(
                "Starting GATT server, support name characteristic %b",
                options.getEnableNameCharacteristic());
        try {
            helper.open(config);
        } catch (BluetoothException e) {
            logger.log(e, "Error starting GATT server");
        }
    }

    /** Callback for passkey/pin input. */
    public interface KeyInputCallback {
        void onKeyInput(int key);
    }

    public void enterPassKey(int passkey) {
        logger.log("enterPassKey called with passkey %d.", passkey);
        try {
            boolean result =
                    (Boolean) Reflect.on(pairingDevice).withMethod("setPasskey", int.class).get(
                            passkey);
            logger.log("enterPassKey called with result %b", result);
        } catch (ReflectionException e) {
            logger.log("enterPassKey meet Exception %s.", e.getMessage());
        }
    }

    private void checkPasskey() {
        // There's a race between the PAIRING_REQUEST broadcast from the OS giving us the local
        // passkey, and the remote passkey received over GATT. Skip the check until we have both.
        if (localPasskey == 0 || remotePasskey == 0) {
            logger.log(
                    "Skipping passkey check, missing local (%s) or remote (%s).",
                    localPasskey, remotePasskey);
            return;
        }

        // Regardless of whether it matches, send our (encrypted) passkey to the seeker.
        sendPasskeyToRemoteDevice(localPasskey);

        logger.log("Checking localPasskey %s == remotePasskey %s", localPasskey, remotePasskey);
        boolean passkeysMatched = localPasskey == remotePasskey;
        if (options.getShowsPasskeyConfirmation() && passkeysMatched
                && passkeyEventCallback != null) {
            logger.log("callbacks the UI for passkey confirmation.");
            passkeyEventCallback.onPasskeyConfirmation(localPasskey, this::setPasskeyConfirmation);
        } else {
            setPasskeyConfirmation(passkeysMatched);
        }
    }

    private void sendPasskeyToRemoteDevice(int passkey) {
        try {
            passkeyServlet.sendNotification(
                    PasskeyCharacteristic.encrypt(
                            PasskeyCharacteristic.Type.PROVIDER, secret, passkey));
        } catch (GeneralSecurityException e) {
            logger.log(e, "Failed to encrypt passkey response.");
        }
    }

    public void setFirmwareVersion(String versionNumber) {
        deviceFirmwareVersion = versionNumber;
    }

    public void setDynamicBufferSize(boolean support) {
        if (supportDynamicBufferSize != support) {
            supportDynamicBufferSize = support;
            sendCapabilitySync();
        }
    }

    @VisibleForTesting
    void setPasskeyConfirmationCallback(PasskeyConfirmationCallback callback) {
        this.passkeyConfirmationCallback = callback;
    }

    public void setDeviceNameCallback(DeviceNameCallback callback) {
        this.deviceNameCallback = callback;
    }

    public void setPasskeyEventCallback(PasskeyEventCallback passkeyEventCallback) {
        this.passkeyEventCallback = passkeyEventCallback;
    }

    private void setPasskeyConfirmation(boolean confirm) {
        pairingDevice.setPairingConfirmation(confirm);
        if (passkeyConfirmationCallback != null) {
            passkeyConfirmationCallback.onPasskeyConfirmation(confirm);
        }
        localPasskey = 0;
        remotePasskey = 0;
    }

    private void becomeDiscoverable() throws InterruptedException, TimeoutException {
        setDiscoverable(true);
    }

    public void cancelDiscovery() throws InterruptedException, TimeoutException {
        setDiscoverable(false);
    }

    private void setDiscoverable(boolean discoverable)
            throws InterruptedException, TimeoutException {
        isDiscoverableLatch = new CountDownLatch(1);
        setScanMode(discoverable ? SCAN_MODE_CONNECTABLE_DISCOVERABLE : SCAN_MODE_CONNECTABLE);
        // If we're already discoverable, count down the latch right away. Otherwise,
        // we'll get a broadcast when we successfully become discoverable.
        if (isDiscoverable()) {
            isDiscoverableLatch.countDown();
        }
        if (isDiscoverableLatch.await(3, TimeUnit.SECONDS)) {
            logger.log("Successfully became switched discoverable mode %s", discoverable);
        } else {
            throw new TimeoutException();
        }
    }

    private void setScanMode(int scanMode) {
        if (revertDiscoverableFuture != null) {
            revertDiscoverableFuture.cancel(false /* may interrupt if running */);
        }

        logger.log("Setting scan mode to %s", scanModeToString(scanMode));
        try {
            Method method = bluetoothAdapter.getClass().getMethod("setScanMode", Integer.TYPE);
            method.invoke(bluetoothAdapter, scanMode);

            if (scanMode == SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                revertDiscoverableFuture =
                        executor.schedule(
                                () -> setScanMode(SCAN_MODE_CONNECTABLE),
                                options.getIsMemoryTest() ? 300 : 30,
                                TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logger.log(e, "Error setting scan mode to %d", scanMode);
        }
    }

    public static String scanModeToString(int scanMode) {
        switch (scanMode) {
            case SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return "DISCOVERABLE";
            case SCAN_MODE_CONNECTABLE:
                return "CONNECTABLE";
            case SCAN_MODE_NONE:
                return "NOT CONNECTABLE";
            default:
                return "UNKNOWN(" + scanMode + ")";
        }
    }

    private ResultCode checkTdsControlPointRequest(byte[] request) {
        if (request.length < 2) {
            logger.log(
                    new IllegalArgumentException(), "Expected length >= 2 for %s",
                    base16().encode(request));
            return ResultCode.INVALID_PARAMETER;
        }
        byte opCode = getTdsControlPointOpCode(request);
        if (opCode != ControlPointCharacteristic.ACTIVATE_TRANSPORT_OP_CODE) {
            logger.log(
                    new IllegalArgumentException(),
                    "Expected Activate Transport op code (0x01), got %d",
                    opCode);
            return ResultCode.OP_CODE_NOT_SUPPORTED;
        }
        if (request[1] != BLUETOOTH_SIG_ORGANIZATION_ID) {
            logger.log(
                    new IllegalArgumentException(),
                    "Expected Bluetooth SIG organization ID (0x01), got %d",
                    request[1]);
            return ResultCode.UNSUPPORTED_ORGANIZATION_ID;
        }
        return ResultCode.SUCCESS;
    }

    private static byte getTdsControlPointOpCode(byte[] request) {
        return request.length < 1 ? 0x00 : request[0];
    }

    private boolean isDiscoverable() {
        return bluetoothAdapter.getScanMode() == SCAN_MODE_CONNECTABLE_DISCOVERABLE;
    }

    private byte[] modelIdServiceData(boolean forAdvertising) {
        // Note: This used to be little-endian but is now big-endian. See b/78229467 for details.
        byte[] modelIdPacket =
                base16().decode(
                        forAdvertising ? options.getAdvertisingModelId() : options.getModelId());
        if (!batteryValues.isEmpty()) {
            // If we are going to advertise battery values with the packet, then switch to the
            // non-3-byte model ID format.
            modelIdPacket = concat(new byte[]{0b00000110}, modelIdPacket);
        }
        return modelIdPacket;
    }

    private byte[] accountKeysServiceData() {
        try {
            return concat(new byte[]{0x00}, generateBloomFilterFields());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to build bloom filter.", e);
        }
    }

    private byte[] transportDiscoveryData() {
        byte[] transportData = SUPPORTED_SERVICES_LTV;
        return Bytes.concat(
                new byte[]{BLUETOOTH_SIG_ORGANIZATION_ID},
                new byte[]{tdsFlags(isDiscoverable() ? TransportState.ON : TransportState.OFF)},
                new byte[]{(byte) transportData.length},
                transportData);
    }

    private byte[] generateBloomFilterFields() throws NoSuchAlgorithmException {
        Set<ByteString> accountKeys = getAccountKeys();
        if (accountKeys.isEmpty()) {
            return new byte[0];
        }
        BloomFilter bloomFilter =
                new BloomFilter(
                        new byte[(int) (1.2 * accountKeys.size()) + 3],
                        new FastPairBloomFilterHasher());
        String address = bleAddress == null ? SIMULATOR_FAKE_BLE_ADDRESS : bleAddress;

        // Simulator supports Central Address Resolution characteristic, so when paired, the BLE
        // address in Seeker will be resolved to BR/EDR address. This caused Seeker fails on
        // checking the bloom filter due to different address is used for salting. In order to
        // let battery values notification be shown on paired device, we use random salt to
        // workaround it.
        boolean advertisingBatteryValues = !batteryValues.isEmpty();
        byte[] salt;
        if (options.getUseRandomSaltForAccountKeyRotation() || advertisingBatteryValues) {
            salt = new byte[1];
            new SecureRandom().nextBytes(salt);
            logger.log("Using random salt %s for bloom filter", base16().encode(salt));
        } else {
            salt = BluetoothAddress.decode(address);
            logger.log("Using address %s for bloom filter", address);
        }

        // To prevent tampering, account filter shall be slightly modified to include battery data
        // when the battery values are included in the advertisement. Normally, when building the
        // account filter, a value V is produce by combining the account key with a salt. Instead,
        // when battery values are also being advertised, it be constructed as follows:
        // - the first 16 bytes are account key.
        // - the next bytes are the salt.
        // - the remaining bytes are the battery data.
        byte[] saltAndBatteryData =
                advertisingBatteryValues ? concat(salt, generateBatteryData()) : salt;

        for (ByteString accountKey : accountKeys) {
            bloomFilter.add(concat(accountKey.toByteArray(), saltAndBatteryData));
        }
        byte[] packet = generateAccountKeyData(bloomFilter);
        return options.getUseRandomSaltForAccountKeyRotation() || advertisingBatteryValues
                // Create a header with length 1 and type 1 for a random salt.
                ? concat(packet, createField((byte) 0x11, salt))
                // Exclude the salt from the packet, BLE address will be assumed by the client.
                : packet;
    }

    /**
     * Creates a new field for the packet.
     *
     * The header is formatted 0xLLLLTTTT where LLLL is the
     * length of the field and TTTT is the type (0 for bloom filter, 1 for salt).
     */
    private byte[] createField(byte header, byte[] value) {
        return concat(new byte[]{header}, value);
    }

    public int getTxPower() {
        return options.getTxPowerLevel();
    }

    @Nullable
    byte[] getServiceData() {
        byte[] packet =
                isDiscoverable()
                        ? modelIdServiceData(/* forAdvertising= */ true)
                        : !getAccountKeys().isEmpty() ? accountKeysServiceData() : null;
        return addBatteryValues(packet);
    }

    @Nullable
    private byte[] addBatteryValues(byte[] packet) {
        if (batteryValues.isEmpty() || packet == null) {
            return packet;
        }

        return concat(packet, generateBatteryData());
    }

    private byte[] generateBatteryData() {
        // Byte 0: Battery length and type, first 4 bits are the number of battery values, second
        // 4 are the type.
        // Byte 1 - length: Battery values, the first bit is charging status, the remaining bits are
        // the actual value between 0 and 100, or -1 for unknown.
        byte[] batteryData = new byte[batteryValues.size() + 1];
        batteryData[0] = (byte) (batteryValues.size() << 4
                | (suppressBatteryNotification ? 0b0100 : 0b0011));

        int batteryValueIndex = 1;
        for (BatteryValue batteryValue : batteryValues) {
            batteryData[batteryValueIndex++] =
                    (byte)
                            ((batteryValue.charging ? 0b10000000 : 0b00000000)
                                    | (0b01111111 & batteryValue.level));
        }

        return batteryData;
    }

    private byte[] generateAccountKeyData(BloomFilter bloomFilter) {
        // Byte 0: length and type, first 4 bits are the length of bloom filter, second 4 are the
        // type which indicating the subsequent pairing notification is suppressed or not.
        // The following bytes are the data of bloom filter.
        byte[] filterBytes = bloomFilter.asBytes();
        byte lengthAndType = (byte) (filterBytes.length << 4
                | (suppressSubsequentPairingNotification ? 0b0010 : 0b0000));
        logger.log(
                "Generate bloom filter with suppress subsequent pairing notification:%b",
                suppressSubsequentPairingNotification);
        return createField(lengthAndType, filterBytes);
    }

    /** Disconnects all connected devices. */
    private void disconnect() {
        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            if (device.getBluetoothClass().getMajorDeviceClass() == Major.PHONE) {
                removeBond(device);
            }
        }
    }

    public void disconnect(BluetoothProfile profile, BluetoothDevice device) {
        try {
            Reflect.on(profile).withMethod("disconnect", BluetoothDevice.class).invoke(device);
        } catch (ReflectionException e) {
            logger.log(e, "Error disconnecting device=%s from profile=%s", device, profile);
        }
    }

    public void removeBond(BluetoothDevice device) {
        try {
            Reflect.on(device).withMethod("removeBond").invoke();
        } catch (ReflectionException e) {
            logger.log(e, "Error removing bond for device=%s", device);
        }
    }

    public void resetAccountKeys() {
        fastPairSimulatorDatabase.setAccountKeys(new HashSet<>());
        fastPairSimulatorDatabase.setFastPairSeekerDevices(new HashSet<>());
        accountKey = null;
        ownerAccountKey = null;
        logger.log("Remove all account keys");
    }

    public void addAccountKey(byte[] key) {
        addAccountKey(key, /* device= */ null);
    }

    private void addAccountKey(byte[] key, @Nullable BluetoothDevice device) {
        accountKey = key;
        if (ownerAccountKey == null) {
            ownerAccountKey = key;
        }

        fastPairSimulatorDatabase.addAccountKey(key);
        fastPairSimulatorDatabase.addFastPairSeekerDevice(device, key);
        logger.log("Add account key: key=%s, device=%s", base16().encode(key), device);
    }

    private Set<ByteString> getAccountKeys() {
        return fastPairSimulatorDatabase.getAccountKeys();
    }

    /** Get the latest account key. */
    @Nullable
    public ByteString getAccountKey() {
        if (accountKey == null) {
            return null;
        }
        return ByteString.copyFrom(accountKey);
    }

    /** Get the owner account key (the first account key registered). */
    @Nullable
    public ByteString getOwnerAccountKey() {
        if (ownerAccountKey == null) {
            return null;
        }
        return ByteString.copyFrom(ownerAccountKey);
    }

    public void resetDeviceName() {
        fastPairSimulatorDatabase.setLocalDeviceName(null);
        // Trigger simulator to update device name text view.
        if (deviceNameCallback != null) {
            deviceNameCallback.onNameChanged(getDeviceName());
        }
    }

    // This method is used in test case with default name in provider.
    public void setDeviceName(String deviceName) {
        setDeviceName(deviceName.getBytes(StandardCharsets.UTF_8));
    }

    private void setDeviceName(@Nullable byte[] deviceName) {
        fastPairSimulatorDatabase.setLocalDeviceName(deviceName);

        logger.log("Save device name : %s", getDeviceName());
        // Trigger simulator to update device name text view.
        if (deviceNameCallback != null) {
            deviceNameCallback.onNameChanged(getDeviceName());
        }
    }

    @Nullable
    private byte[] getDeviceNameInBytes() {
        return fastPairSimulatorDatabase.getLocalDeviceName();
    }

    @Nullable
    public String getDeviceName() {
        String providerDeviceName =
                getDeviceNameInBytes() != null
                        ? new String(getDeviceNameInBytes(), StandardCharsets.UTF_8)
                        : null;
        logger.log("get device name = %s", providerDeviceName);
        return providerDeviceName;
    }

    /**
     * Bit index: Description - Value
     *
     * <ul>
     *   <li>0-1: Role - 0b10 (Provider only)
     *   <li>2: Transport Data Incomplete: 0 (false)
     *   <li>3-4: Transport State (0b00: Off, 0b01: On, 0b10: Temporarily Unavailable)
     *   <li>5-7: Reserved for future use
     * </ul>
     */
    private static byte tdsFlags(TransportState transportState) {
        return (byte) (0b00000010 & (transportState.byteValue << 3));
    }

    /** Detailed information about battery value. */
    public static class BatteryValue {
        boolean charging;

        // The range is 0 ~ 100, and -1 represents the battery level is unknown.
        int level;

        public BatteryValue(boolean charging, int level) {
            this.charging = charging;
            this.level = level;
        }
    }
}
