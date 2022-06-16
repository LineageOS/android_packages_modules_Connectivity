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

package com.android.server.nearby.common.ble;

import static com.google.common.truth.Truth.assertThat;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

/** Test for Bluetooth LE {@link BleSighting}. */
public class BleSightingTest {
    private static final String DEVICE_NAME = "device1";
    private static final String OTHER_DEVICE_NAME = "device2";
    private static final long TIME_EPOCH_MILLIS = 123456;
    private static final long OTHER_TIME_EPOCH_MILLIS = 456789;
    private static final int RSSI = 1;
    private static final int OTHER_RSSI = 2;

    private final BluetoothDevice mBluetoothDevice1 =
            BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55");
    private final BluetoothDevice mBluetoothDevice2 =
            BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:FF");


    @Test
    public void testEquals() {
        BleSighting sighting =
                buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        BleSighting sighting2 =
                buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        assertThat(sighting.equals(sighting2)).isTrue();
        assertThat(sighting2.equals(sighting)).isTrue();
        assertThat(sighting.hashCode()).isEqualTo(sighting2.hashCode());

        // Transitive property.
        BleSighting sighting3 =
                buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        assertThat(sighting2.equals(sighting3)).isTrue();
        assertThat(sighting.equals(sighting3)).isTrue();

        // Set different values for each field, one at a time.
        sighting2 = buildBleSighting(mBluetoothDevice2, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        assertSightingsNotEquals(sighting, sighting2);

        sighting2 = buildBleSighting(mBluetoothDevice1, OTHER_DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);
        assertSightingsNotEquals(sighting, sighting2);

        sighting2 = buildBleSighting(mBluetoothDevice1, DEVICE_NAME, OTHER_TIME_EPOCH_MILLIS, RSSI);
        assertSightingsNotEquals(sighting, sighting2);

        sighting2 = buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, OTHER_RSSI);
        assertSightingsNotEquals(sighting, sighting2);
    }

    @Test
    public void getNormalizedRSSI_usingNearbyRssiOffset_getCorrectValue() {
        BleSighting sighting =
                buildBleSighting(mBluetoothDevice1, DEVICE_NAME, TIME_EPOCH_MILLIS, RSSI);

        int defaultRssiOffset = 3;
        assertThat(sighting.getNormalizedRSSI()).isEqualTo(RSSI + defaultRssiOffset);
    }

    /** Builds a BleSighting instance which will correctly match filters by device name. */
    private static BleSighting buildBleSighting(
            BluetoothDevice bluetoothDevice, String deviceName, long timeEpochMillis, int rssi) {
        byte[] nameBytes = deviceName.getBytes(UTF_8);
        byte[] bleRecordBytes = new byte[nameBytes.length + 2];
        bleRecordBytes[0] = (byte) (nameBytes.length + 1);
        bleRecordBytes[1] = 0x09; // Value of private BleRecord.DATA_TYPE_LOCAL_NAME_COMPLETE;
        System.arraycopy(nameBytes, 0, bleRecordBytes, 2, nameBytes.length);

        return new BleSighting(bluetoothDevice, bleRecordBytes,
                rssi, TimeUnit.MILLISECONDS.toNanos(timeEpochMillis));
    }

    private static void assertSightingsNotEquals(BleSighting sighting1, BleSighting sighting2) {
        assertThat(sighting1.equals(sighting2)).isFalse();
        assertThat(sighting1.hashCode()).isNotEqualTo(sighting2.hashCode());
    }
}
