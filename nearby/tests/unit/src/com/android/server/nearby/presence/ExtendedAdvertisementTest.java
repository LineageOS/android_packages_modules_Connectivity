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

package com.android.server.nearby.presence;

import static com.google.common.truth.Truth.assertThat;

import android.nearby.BroadcastRequest;
import android.nearby.DataElement;
import android.nearby.PresenceBroadcastRequest;
import android.nearby.PresenceCredential;
import android.nearby.PrivateCredential;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ExtendedAdvertisementTest {
    private static final int IDENTITY_TYPE = PresenceCredential.IDENTITY_TYPE_PRIVATE;
    private static final int DATA_TYPE_MODEL_ID = 10;
    private static final int DATA_TYPE_BLE_ADDRESS = 2;
    private static final int DATA_TYPE_PUBLIC_IDENTITY = 6;
    private static final byte[] MODE_ID_DATA =
            new byte[]{2, 1, 30, 2, 10, -16, 6, 22, 44, -2, -86, -69, -52};
    private static final byte[] BLE_ADDRESS = new byte[]{124, 4, 56, 60, 120, -29, -90};
    private static final DataElement MODE_ID_ADDRESS_ELEMENT =
            new DataElement(DATA_TYPE_MODEL_ID, MODE_ID_DATA);
    private static final DataElement BLE_ADDRESS_ELEMENT =
            new DataElement(DATA_TYPE_BLE_ADDRESS, BLE_ADDRESS);

    private static final byte[] IDENTITY =
            new byte[]{1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4};
    private static final int MEDIUM_TYPE_BLE = 0;
    private static final byte[] SALT = {2, 3};
    private static final int PRESENCE_ACTION_1 = 1;
    private static final int PRESENCE_ACTION_2 = 2;

    private static final byte[] SECRET_ID = new byte[]{1, 2, 3, 4};
    private static final byte[] AUTHENTICITY_KEY = new byte[]{12, 13, 14};
    private static final String DEVICE_NAME = "test_device";


    private PresenceBroadcastRequest.Builder mBuilder;
    private PrivateCredential mCredential;

    @Before
    public void setUp() {
        mCredential =
                new PrivateCredential.Builder(SECRET_ID, AUTHENTICITY_KEY, IDENTITY, DEVICE_NAME)
                        .setIdentityType(PresenceCredential.IDENTITY_TYPE_PRIVATE)
                        .build();
        mBuilder =
                new PresenceBroadcastRequest.Builder(Collections.singletonList(MEDIUM_TYPE_BLE),
                        SALT, mCredential)
                        .setVersion(BroadcastRequest.PRESENCE_VERSION_V1)
                        .addAction(PRESENCE_ACTION_1)
                        .addAction(PRESENCE_ACTION_2)
                        .addExtendedProperty(new DataElement(DATA_TYPE_BLE_ADDRESS, BLE_ADDRESS))
                        .addExtendedProperty(new DataElement(DATA_TYPE_MODEL_ID, MODE_ID_DATA));
    }

    @Test
    public void test_createFromRequest() {
        ExtendedAdvertisement originalAdvertisement = ExtendedAdvertisement.createFromRequest(
                mBuilder.build());

        assertThat(originalAdvertisement.getActions())
                .containsExactly(PRESENCE_ACTION_1, PRESENCE_ACTION_2);
        assertThat(originalAdvertisement.getIdentity()).isEqualTo(IDENTITY);
        assertThat(originalAdvertisement.getIdentityType()).isEqualTo(IDENTITY_TYPE);
        assertThat(originalAdvertisement.getLength()).isEqualTo(49);
        assertThat(originalAdvertisement.getVersion()).isEqualTo(
                BroadcastRequest.PRESENCE_VERSION_V1);
        assertThat(originalAdvertisement.getSalt()).isEqualTo(SALT);
        assertThat(originalAdvertisement.getDataElements())
                .containsExactly(MODE_ID_ADDRESS_ELEMENT, BLE_ADDRESS_ELEMENT);
    }

    @Test
    public void test_toBytes() {
        ExtendedAdvertisement adv = ExtendedAdvertisement.createFromRequest(mBuilder.build());
        assertThat(adv.toBytes()).isEqualTo(getExtendedAdvertisementByteArray());
    }

    @Test
    public void test_fromBytes() {
        byte[] originalBytes = getExtendedAdvertisementByteArray();
        ExtendedAdvertisement adv = ExtendedAdvertisement.fromBytes(originalBytes);

        assertThat(adv.getActions())
                .containsExactly(PRESENCE_ACTION_1, PRESENCE_ACTION_2);
        assertThat(adv.getIdentity()).isEqualTo(IDENTITY);
        assertThat(adv.getIdentityType()).isEqualTo(IDENTITY_TYPE);
        assertThat(adv.getLength()).isEqualTo(49);
        assertThat(adv.getVersion()).isEqualTo(
                BroadcastRequest.PRESENCE_VERSION_V1);
        assertThat(adv.getSalt()).isEqualTo(SALT);
        assertThat(adv.getDataElements())
                .containsExactly(MODE_ID_ADDRESS_ELEMENT, BLE_ADDRESS_ELEMENT);
    }

    @Test
    public void test_toString() {
        ExtendedAdvertisement adv = ExtendedAdvertisement.createFromRequest(mBuilder.build());
        assertThat(adv.toString()).isEqualTo("ExtendedAdvertisement:"
                + "<VERSION: 1, length: 49, dataElementCount: 2, identityType: 1, "
                + "identity: [1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4], salt: [2, 3],"
                + " actions: [1, 2]>");
    }

    @Test
    public void test_getDataElements_accordingToType() {
        ExtendedAdvertisement adv = ExtendedAdvertisement.createFromRequest(mBuilder.build());
        List<DataElement> dataElements = new ArrayList<>();

        dataElements.add(BLE_ADDRESS_ELEMENT);
        assertThat(adv.getDataElements(DATA_TYPE_BLE_ADDRESS)).isEqualTo(dataElements);
        assertThat(adv.getDataElements(DATA_TYPE_PUBLIC_IDENTITY)).isEmpty();
    }

    private static byte[] getExtendedAdvertisementByteArray() {
        ByteBuffer buffer = ByteBuffer.allocate(49);
        buffer.put((byte) 0b00100000); // Header V1
        buffer.put((byte) 0b00100011); // Salt header: length 2, type 3
        // Salt data
        buffer.put(SALT);
        // Identity header: length 16, type 4
        buffer.put(new byte[]{(byte) 0b10010000, (byte) 0b00000100});
        // Identity data
        buffer.put(IDENTITY);
        // Action1 header: length 1, type 9
        buffer.put(new byte[]{(byte) 0b00011001});
        // Action1 data
        buffer.put((byte) PRESENCE_ACTION_1);
        // Action2 header: length 1, type 9
        buffer.put(new byte[]{(byte) 0b00011001});
        // Action2 data
        buffer.put((byte) PRESENCE_ACTION_2);
        // Ble address header: length 7, type 2
        buffer.put((byte) 0b01110010);
        // Ble address data
        buffer.put(BLE_ADDRESS);
        // model id header: length 13, type 10
        buffer.put(new byte[]{(byte) 0b10001101, (byte) 0b00001010});
        // model id data
        buffer.put(MODE_ID_DATA);

        return buffer.array();
    }
}
