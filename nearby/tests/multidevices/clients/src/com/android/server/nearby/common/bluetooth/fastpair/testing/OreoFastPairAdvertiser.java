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

import static com.google.common.io.BaseEncoding.base16;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Build.VERSION_CODES;
import android.os.ParcelUuid;

import androidx.annotation.Nullable;

import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService;
import com.android.server.nearby.common.bluetooth.fastpair.Reflect;
import com.android.server.nearby.common.bluetooth.fastpair.ReflectionException;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Fast Pair advertiser taking advantage of new Android Oreo advertising features. */
@TargetApi(VERSION_CODES.O)
public final class OreoFastPairAdvertiser implements FastPairAdvertiser {
    private static final String TAG = "OreoFastPairAdvertiser";
    private final Logger logger = new Logger(TAG);

    private final FastPairSimulator simulator;
    private final BluetoothLeAdvertiser advertiser;
    private final AdvertisingSetCallback advertisingSetCallback;
    private AdvertisingSet advertisingSet;

    public OreoFastPairAdvertiser(FastPairSimulator simulator) {
        this.simulator = simulator;
        this.advertiser = BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
        this.advertisingSetCallback =
                new AdvertisingSetCallback() {
                    @Override
                    public void onAdvertisingSetStarted(AdvertisingSet set, int txPower, int status) {
                        if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                            logger.log("Advertising succeeded, advertising at %s dBm", txPower);
                            simulator.setIsAdvertising(true);
                            advertisingSet = set;

                            try {
                                // Requires custom Android build, see callback below.
                                Reflect.on(set).withMethod("getOwnAddress").invoke();
                            } catch (ReflectionException e) {
                                logger.log(e, "Error calling getOwnAddress for AdvertisingSet");
                            }
                        } else {
                            logger.log(
                                    new IllegalStateException(), "Advertising failed, error code=%d", status);
                        }
                    }

                    @Override
                    public void onAdvertisingDataSet(AdvertisingSet set, int status) {
                        if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                            logger.log(
                                    new IllegalStateException(),
                                    "Updating advertisement failed, error code=%d",
                                    status);
                            stopAdvertising();
                        }
                    }

                    // Called via reflection with AdvertisingSet.getOwnAddress().
                    public void onOwnAddressRead(AdvertisingSet set, int addressType, String address) {
                        if (!address.equals(simulator.getBleAddress())) {
                            logger.log(
                                    "Read own BLE address=%s at %s",
                                    address,
                                    new SimpleDateFormat("HH:mm:ss:SSS", Locale.US)
                                            .format(Calendar.getInstance().getTime()));
                            simulator.setBleAddress(address);
                        }
                    }
                };
    }

    @Override
    public void startAdvertising(@Nullable byte[] serviceData) {
        // To be informed that BLE address is rotated, we need to polling query it asynchronously.
        if (advertisingSet != null) {
            try {
                // Requires custom Android build, see callback: onOwnAddressRead.
                Reflect.on(advertisingSet).withMethod("getOwnAddress").invoke();
            } catch (ReflectionException ignored) {
                // Ignore it due to user already knows it when setting advertisingSet.
            }
        }

        if (simulator.isDestroyed()) {
            return;
        }

        if (serviceData == null) {
            logger.log("Service data is null, stop advertising");
            stopAdvertising();
            return;
        }

        AdvertiseData data =
                new AdvertiseData.Builder()
                        .addServiceData(new ParcelUuid(FastPairService.ID), serviceData)
                        .setIncludeTxPowerLevel(true)
                        .build();

        logger.log("Advertising FE2C service data=%s", base16().encode(serviceData));

        if (advertisingSet != null) {
            advertisingSet.setAdvertisingData(data);
            return;
        }

        stopAdvertising();
        AdvertisingSetParameters parameters =
                new AdvertisingSetParameters.Builder()
                        .setLegacyMode(true)
                        .setConnectable(true)
                        .setScannable(true)
                        .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                        .setTxPowerLevel(convertAdvertiseSettingsTxPower(simulator.getTxPower()))
                        .build();
        advertiser.startAdvertisingSet(parameters, data, null, null, null, advertisingSetCallback);
    }

    private static int convertAdvertiseSettingsTxPower(int txPower) {
        switch (txPower) {
            case AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW:
                return AdvertisingSetParameters.TX_POWER_ULTRA_LOW;
            case AdvertiseSettings.ADVERTISE_TX_POWER_LOW:
                return AdvertisingSetParameters.TX_POWER_LOW;
            case AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM:
                return AdvertisingSetParameters.TX_POWER_MEDIUM;
            default:
                return AdvertisingSetParameters.TX_POWER_HIGH;
        }
    }

    @Override
    public void stopAdvertising() {
        if (simulator.isDestroyed()) {
            return;
        }

        advertiser.stopAdvertisingSet(advertisingSetCallback);
        advertisingSet = null;
        simulator.setIsAdvertising(false);
    }
}
