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

import static com.android.server.nearby.NearbyService.TAG;

import android.annotation.Nullable;
import android.nearby.BroadcastRequest;
import android.nearby.DataElement;
import android.nearby.PresenceBroadcastRequest;
import android.nearby.PresenceCredential;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A Nearby Presence advertisement to be advertised on BT5.0 devices.
 *
 * <p>Serializable between Java object and bytes formats. Java object is used at the upper scanning
 * and advertising interface as an abstraction of the actual bytes. Bytes format is used at the
 * underlying BLE and mDNS stacks, which do necessary slicing and merging based on advertising
 * capacities.
 *
 * The extended advertisement is defined in the format below:
 * Header (1 byte) | salt (1+2 bytes) | Identity + filter (2+16 bytes)
 * | repeated DE fields (various bytes)
 * The header contains:
 * version (3 bits) | 5 bit reserved for future use (RFU)
 */
public class ExtendedAdvertisement extends Advertisement{

    public static final int SALT_DATA_LENGTH = 2;

    static final int HEADER_LENGTH = 1;

    static final int IDENTITY_DATA_LENGTH = 16;

    private final List<DataElement> mDataElements;

    // All Data Elements including salt and identity.
    // Each list item (byte array) is a Data Element (with its header).
    private final List<byte[]> mCompleteDataElementsBytes;

    /**
     * Creates an {@link ExtendedAdvertisement} from a Presence Broadcast Request.
     * @return {@link ExtendedAdvertisement} object. {@code null} when the request is illegal.
     */
    @Nullable
    public static ExtendedAdvertisement createFromRequest(PresenceBroadcastRequest request) {
        if (request.getVersion() != BroadcastRequest.PRESENCE_VERSION_V1) {
            Log.v(TAG, "ExtendedAdvertisement only supports V1 now.");
            return null;
        }

        byte[] salt = request.getSalt();
        if (salt.length != SALT_DATA_LENGTH) {
            Log.v(TAG, "Salt does not match correct length");
            return null;
        }

        byte[] identity = request.getCredential().getMetadataEncryptionKey();
        if (identity.length != IDENTITY_DATA_LENGTH) {
            Log.v(TAG, "Identity does not match correct length");
            return null;
        }

        List<Integer> actions = request.getActions();
        if (actions.isEmpty()) {
            Log.v(TAG, "ExtendedAdvertisement must contain at least one action");
            return null;
        }

        List<DataElement> dataElements = request.getExtendedProperties();
        return new ExtendedAdvertisement(
                request.getCredential().getIdentityType(),
                identity,
                salt,
                actions,
                dataElements);
    }

    /** Serialize an {@link ExtendedAdvertisement} object into bytes. */
    @Override
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(getLength());

        // Header
        buffer.put(ExtendedAdvertisementUtils.constructHeader(getVersion()));

        // Data Elements (Already includes salt and identity)
        for (byte[] dataElement : mCompleteDataElementsBytes) {
            buffer.put(dataElement);
        }
        return buffer.array();
    }

    /** Deserialize from bytes into an {@link ExtendedAdvertisement} object.
     * {@code null} when there is something when parsing.
     */
    @Nullable
    public static ExtendedAdvertisement fromBytes(byte[] bytes) {
        @BroadcastRequest.BroadcastVersion
        int version = ExtendedAdvertisementUtils.getVersion(bytes);
        if (version != PresenceBroadcastRequest.PRESENCE_VERSION_V1) {
            Log.v(TAG, "ExtendedAdvertisement is used in V1 only.");
            return null;
        }

        int index = HEADER_LENGTH;
        // Salt
        byte[] saltHeaderArray = ExtendedAdvertisementUtils.getDataElementHeader(bytes, index);
        DataElementHeader saltHeader = DataElementHeader.fromBytes(version, saltHeaderArray);
        if (saltHeader == null || saltHeader.getDataType() != DataElement.DataType.SALT) {
            Log.v(TAG, "First data element has to be salt.");
            return null;
        }
        index += saltHeaderArray.length;
        byte[] salt = new byte[saltHeader.getDataLength()];
        for (int i = 0; i < saltHeader.getDataLength(); i++) {
            salt[i] = bytes[index++];
        }

        // Identity
        byte[] identityHeaderArray = ExtendedAdvertisementUtils.getDataElementHeader(bytes, index);
        DataElementHeader identityHeader =
                DataElementHeader.fromBytes(version, identityHeaderArray);
        if (identityHeader == null || identityHeader.getDataLength() != IDENTITY_DATA_LENGTH) {
            Log.v(TAG, "The second element has to be identity and the length should be "
                    + IDENTITY_DATA_LENGTH + " bytes.");
            return null;
        }
        index += identityHeaderArray.length;
        @PresenceCredential.IdentityType int identityType =
                toPresenceCredentialIdentityType(identityHeader.getDataType());
        if (identityType == PresenceCredential.IDENTITY_TYPE_UNKNOWN) {
            Log.v(TAG, "The identity type is unknown.");
            return null;
        }
        byte[] identity = new byte[identityHeader.getDataLength()];
        for (int i = 0; i < identityHeader.getDataLength(); i++) {
            identity[i] = bytes[index++];
        }

        // Other Data Elements
        List<Integer> actions = new ArrayList<>();
        List<DataElement> dataElements = new ArrayList<>();
        while (index < bytes.length) {
            byte[] deHeaderArray = ExtendedAdvertisementUtils.getDataElementHeader(bytes, index);
            DataElementHeader deHeader = DataElementHeader.fromBytes(version, deHeaderArray);
            index += deHeaderArray.length;

            @DataElement.DataType int type = Objects.requireNonNull(deHeader).getDataType();
            if (type == DataElement.DataType.INTENT) {
                if (deHeader.getDataLength() != 1) {
                    Log.v(TAG, "Action id should only 1 byte.");
                    return null;
                }
                actions.add((int) bytes[index++]);
            } else {
                if (isSaltOrIdentity(type)) {
                    Log.v(TAG, "Type " + type + " is duplicated. There should be only one salt"
                            + " and one identity in the advertisement.");
                    return null;
                }
                byte[] deData = new byte[deHeader.getDataLength()];
                for (int i = 0; i < deHeader.getDataLength(); i++) {
                    deData[i] = bytes[index++];
                }
                dataElements.add(new DataElement(type, deData));
            }
        }

        return new ExtendedAdvertisement(identityType, identity, salt, actions, dataElements);
    }

    /** Returns the {@link DataElement}s in the advertisement. */
    public List<DataElement> getDataElements() {
        return new ArrayList<>(mDataElements);
    }

    /** Returns the {@link DataElement}s in the advertisement according to the key. */
    public List<DataElement> getDataElements(@DataElement.DataType int key) {
        List<DataElement> res = new ArrayList<>();
        for (DataElement dataElement : mDataElements) {
            if (key == dataElement.getKey()) {
                res.add(dataElement);
            }
        }
        return res;
    }

    @Override
    public String toString() {
        return String.format(
                "ExtendedAdvertisement:"
                        + "<VERSION: %s, length: %s, dataElementCount: %s, identityType: %s,"
                        + " identity: %s, salt: %s, actions: %s>",
                getVersion(),
                getLength(),
                getDataElements().size(),
                getIdentityType(),
                Arrays.toString(getIdentity()),
                Arrays.toString(getSalt()),
                getActions());
    }

    ExtendedAdvertisement(
            @PresenceCredential.IdentityType int identityType,
            byte[] identity,
            byte[] salt,
            List<Integer> actions,
            List<DataElement> dataElements) {
        this.mVersion = BroadcastRequest.PRESENCE_VERSION_V1;
        this.mIdentityType = identityType;
        this.mIdentity = identity;
        this.mSalt = salt;
        this.mActions = actions;
        this.mDataElements = dataElements;
        this.mCompleteDataElementsBytes = new ArrayList<>();

        int length = HEADER_LENGTH; // header

        // Salt
        DataElement saltElement = new DataElement(DataElement.DataType.SALT, salt);
        byte[] saltByteArray = ExtendedAdvertisementUtils.convertDataElementToBytes(saltElement);
        mCompleteDataElementsBytes.add(saltByteArray);
        length += saltByteArray.length;

        // Identity
        DataElement identityElement = new DataElement(toDataType(identityType), identity);
        byte[] identityByteArray =
                ExtendedAdvertisementUtils.convertDataElementToBytes(identityElement);
        mCompleteDataElementsBytes.add(identityByteArray);
        length += identityByteArray.length;

        // Intents
        for (int action : mActions) {
            DataElement actionElement = new DataElement(DataElement.DataType.INTENT,
                    new byte[] {(byte) action});
            byte[] intentByteArray =
                    ExtendedAdvertisementUtils.convertDataElementToBytes(actionElement);
            mCompleteDataElementsBytes.add(intentByteArray);
            length += intentByteArray.length;
        }

        // Data Elements (Extended properties)
        for (DataElement dataElement : mDataElements) {
            byte[] deByteArray = ExtendedAdvertisementUtils.convertDataElementToBytes(dataElement);
            mCompleteDataElementsBytes.add(deByteArray);
            length += deByteArray.length;
        }

        this.mLength = length;
    }

    @PresenceCredential.IdentityType
    private static int toPresenceCredentialIdentityType(@DataElement.DataType int type) {
        switch (type) {
            case DataElement.DataType.PRIVATE_IDENTITY:
                return PresenceCredential.IDENTITY_TYPE_PRIVATE;
            case DataElement.DataType.PROVISIONED_IDENTITY:
                return PresenceCredential.IDENTITY_TYPE_PROVISIONED;
            case DataElement.DataType.TRUSTED_IDENTITY:
                return PresenceCredential.IDENTITY_TYPE_TRUSTED;
            case DataElement.DataType.PUBLIC_IDENTITY:
            default:
                return PresenceCredential.IDENTITY_TYPE_UNKNOWN;
        }
    }

    @DataElement.DataType
    private static int toDataType(@PresenceCredential.IdentityType int identityType) {
        switch (identityType) {
            case PresenceCredential.IDENTITY_TYPE_PRIVATE:
                return DataElement.DataType.PRIVATE_IDENTITY;
            case PresenceCredential.IDENTITY_TYPE_PROVISIONED:
                return DataElement.DataType.PROVISIONED_IDENTITY;
            case PresenceCredential.IDENTITY_TYPE_TRUSTED:
                return DataElement.DataType.TRUSTED_IDENTITY;
            case PresenceCredential.IDENTITY_TYPE_UNKNOWN:
            default:
                return DataElement.DataType.PUBLIC_IDENTITY;
        }
    }

    /**
     * Returns {@code true} if the given {@link DataElement.DataType} is salt, or one of the
     * identities. Identities should be able to convert to {@link PresenceCredential.IdentityType}s.
     */
    private static boolean isSaltOrIdentity(@DataElement.DataType int type) {
        return type == DataElement.DataType.SALT || type == DataElement.DataType.PRIVATE_IDENTITY
                || type == DataElement.DataType.TRUSTED_IDENTITY
                || type == DataElement.DataType.PROVISIONED_IDENTITY
                || type == DataElement.DataType.PUBLIC_IDENTITY;
    }
}
