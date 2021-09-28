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

package com.android.server.nearby.common.bluetooth.util;

import static com.android.server.nearby.common.bluetooth.util.BluetoothGattUtils.getMessageForStatusCode;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.nearby.common.bluetooth.BluetoothException;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;

/** Unit tests for {@link BluetoothAddress}. */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothGattUtilsTest {
    private static final UUID TEST_UUID = UUID.randomUUID();
    private static final ImmutableSet<String> GATT_HIDDEN_CONSTANTS = ImmutableSet.of(
            "GATT_WRITE_REQUEST_BUSY", "GATT_WRITE_REQUEST_FAIL", "GATT_WRITE_REQUEST_SUCCESS");

    @Test
    public void testGetMessageForStatusCode() throws Exception {
        Field[] publicFields = BluetoothGatt.class.getFields();
        for (Field field : publicFields) {
            if ((field.getModifiers() & Modifier.STATIC) == 0
                    || field.getDeclaringClass() != BluetoothGatt.class) {
                continue;
            }
            String fieldName = field.getName();
            if (!fieldName.startsWith("GATT_") || GATT_HIDDEN_CONSTANTS.contains(fieldName)) {
                continue;
            }
            int fieldValue = (Integer) field.get(null);
            assertThat(getMessageForStatusCode(fieldValue)).isEqualTo(fieldName);
        }
    }

    @Test
    public void testCloneDescriptor() throws BluetoothException {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(TEST_UUID,
                BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
                        | BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM);
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(TEST_UUID,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED
                        | BluetoothGattDescriptor.PERMISSION_WRITE_SIGNED_MITM);
        characteristic.addDescriptor(descriptor);

        BluetoothGattDescriptor result = BluetoothGattUtils.clone(descriptor);

        assertThat(result.getUuid()).isEqualTo(descriptor.getUuid());
        assertThat(result.getPermissions()).isEqualTo(descriptor.getPermissions());
        assertThat(result.getCharacteristic()).isEqualTo(descriptor.getCharacteristic());
    }

    @Test
    public void testCloneCharacteristic() throws BluetoothException {
        BluetoothGattService service =
                new BluetoothGattService(TEST_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(TEST_UUID,
                BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
                        | BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM);
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        service.addCharacteristic(characteristic);
        BluetoothGattCharacteristic result = BluetoothGattUtils.clone(characteristic);

        assertThat(result.getUuid()).isEqualTo(characteristic.getUuid());
        assertThat(result.getPermissions()).isEqualTo(characteristic.getPermissions());
        assertThat(result.getProperties()).isEqualTo(characteristic.getProperties());
        assertThat(result.getService()).isEqualTo(characteristic.getService());
        assertThat(result.getInstanceId()).isEqualTo(characteristic.getInstanceId());
        assertThat(result.getWriteType()).isEqualTo(characteristic.getWriteType());    }
}
