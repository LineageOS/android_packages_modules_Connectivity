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

package android.nearby.cts;

import static com.google.common.truth.Truth.assertThat;

import android.nearby.NearbyDevice;
import android.nearby.NearbyDeviceParcelable;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class NearbyDeviceParcelableTest {

    private static final String BLUETOOTH_ADDRESS = "00:11:22:33:FF:EE";

    /** Verify toString returns expected string. */
    @Test
    public void testToString() {
        NearbyDeviceParcelable nearbyDeviceParcelable =  new NearbyDeviceParcelable.Builder()
                .setName("testDevice")
                .setMedium(NearbyDevice.Medium.BLE)
                .setRssi(-60)
                .setFastPairModelId(null)
                .setBluetoothAddress(BLUETOOTH_ADDRESS)
                .setData(null)
                .build();

        assertThat(nearbyDeviceParcelable.toString()).isEqualTo(
                "NearbyDeviceParcelable[name=testDevice, medium=BLE, rssi=-60, "
                        + "bluetoothAddress="
                        + BLUETOOTH_ADDRESS + ", fastPairModelId=null, data=null]");
    }

    @Test
    public void test_defaultNullFields() {
        NearbyDeviceParcelable nearbyDeviceParcelable =  new NearbyDeviceParcelable.Builder()
                .setMedium(NearbyDevice.Medium.BLE)
                .setRssi(-60)
                .build();

        assertThat(nearbyDeviceParcelable.getName()).isNull();
        assertThat(nearbyDeviceParcelable.getFastPairModelId()).isNull();
        assertThat(nearbyDeviceParcelable.getBluetoothAddress()).isNull();
        assertThat(nearbyDeviceParcelable.getData()).isNull();

        assertThat(nearbyDeviceParcelable.getMedium()).isEqualTo(NearbyDevice.Medium.BLE);
        assertThat(nearbyDeviceParcelable.getRssi()).isEqualTo(-60);
    }
}
