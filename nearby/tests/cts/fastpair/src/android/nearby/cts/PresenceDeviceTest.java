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
import android.nearby.PresenceDevice;
import android.os.Build;
import android.os.Parcel;

import androidx.annotation.RequiresApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test for {@link PresenceDevice}.
 */
@RunWith(AndroidJUnit4.class)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class PresenceDeviceTest {
    private static final int DEVICE_TYPE = PresenceDevice.DeviceType.PHONE;
    private static final String DEVICE_ID = "123";
    private static final String IMAGE_URL = "http://example.com/imageUrl";
    private static final String SUPPORT_MEDIA = "SupportMedia";
    private static final String SUPPORT_MEDIA_VALUE = "true";
    private static final int RSSI = -40;
    private static final int MEDIUM = NearbyDevice.Medium.BLE;
    private static final String DEVICE_NAME = "testDevice";

    @Test
    public void testBuilder() {
        PresenceDevice device = new PresenceDevice.Builder()
                .setDeviceType(DEVICE_TYPE)
                .setDeviceId(DEVICE_ID)
                .setDeviceImageUrl(IMAGE_URL)
                .addExtendedProperty(SUPPORT_MEDIA, SUPPORT_MEDIA_VALUE)
                .setRssi(RSSI)
                .setMedium(MEDIUM)
                .setName(DEVICE_NAME)
                .build();

        assertThat(device.getDeviceType()).isEqualTo(DEVICE_TYPE);
        assertThat(device.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(device.getDeviceImageUrl()).isEqualTo(IMAGE_URL);
        assertThat(device.getExtendedProperties().get(SUPPORT_MEDIA)).isEqualTo(
                SUPPORT_MEDIA_VALUE);
        assertThat(device.getRssi()).isEqualTo(RSSI);
        assertThat(device.getMedium()).isEqualTo(MEDIUM);
        assertThat(device.getName()).isEqualTo(DEVICE_NAME);
    }

    @Test
    public void testWriteParcel() {
        PresenceDevice device = new PresenceDevice.Builder()
                .setDeviceId(DEVICE_ID)
                .addExtendedProperty(SUPPORT_MEDIA, SUPPORT_MEDIA_VALUE)
                .setRssi(RSSI)
                .setMedium(MEDIUM)
                .setName(DEVICE_NAME)
                .build();

        Parcel parcel = Parcel.obtain();
        device.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        PresenceDevice parcelDevice = PresenceDevice.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertThat(parcelDevice.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(parcelDevice.getExtendedProperties().get(SUPPORT_MEDIA)).isEqualTo(
                SUPPORT_MEDIA_VALUE);
        assertThat(parcelDevice.getRssi()).isEqualTo(RSSI);
        assertThat(parcelDevice.getMedium()).isEqualTo(MEDIUM);
        assertThat(parcelDevice.getName()).isEqualTo(DEVICE_NAME);
    }
}
