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

import android.annotation.NonNull;
import android.nearby.DataElement;
import android.nearby.NearbyDevice;
import android.nearby.NearbyDeviceParcelable;
import android.nearby.PresenceDevice;
import android.nearby.PresenceScanFilter;
import android.nearby.PublicCredential;
import android.util.ArraySet;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Represents a Presence discovery result. */
public class PresenceDiscoveryResult {

    /** Creates a {@link PresenceDiscoveryResult} from the scan data. */
    public static PresenceDiscoveryResult fromDevice(NearbyDeviceParcelable device) {
        byte[] salt = device.getSalt();
        if (salt == null) {
            salt = new byte[0];
        }
        return new PresenceDiscoveryResult.Builder()
                .setTxPower(device.getTxPower())
                .setRssi(device.getRssi())
                .setSalt(salt)
                .addPresenceAction(device.getAction())
                .setPublicCredential(device.getPublicCredential())
                .addExtendedProperties(device.getPresenceDevice().getExtendedProperties())
                .build();
    }

    private final int mTxPower;
    private final int mRssi;
    private final byte[] mSalt;
    private final List<Integer> mPresenceActions;
    private final PublicCredential mPublicCredential;
    private final List<DataElement> mExtendedProperties;

    private PresenceDiscoveryResult(
            int txPower,
            int rssi,
            byte[] salt,
            List<Integer> presenceActions,
            PublicCredential publicCredential,
            List<DataElement> extendedProperties) {
        mTxPower = txPower;
        mRssi = rssi;
        mSalt = salt;
        mPresenceActions = presenceActions;
        mPublicCredential = publicCredential;
        mExtendedProperties = extendedProperties;
    }

    /** Returns whether the discovery result matches the scan filter. */
    public boolean matches(PresenceScanFilter scanFilter) {
        if (accountKeyMatches(scanFilter.getExtendedProperties())) {
            return true;
        }
        return pathLossMatches(scanFilter.getMaxPathLoss())
                && actionMatches(scanFilter.getPresenceActions())
                && credentialMatches(scanFilter.getCredentials());
    }

    private boolean pathLossMatches(int maxPathLoss) {
        return (mTxPower - mRssi) <= maxPathLoss;
    }

    private boolean actionMatches(List<Integer> filterActions) {
        if (filterActions.isEmpty()) {
            return true;
        }
        return filterActions.stream().anyMatch(mPresenceActions::contains);
    }

    private boolean credentialMatches(List<PublicCredential> credentials) {
        return credentials.contains(mPublicCredential);
    }

    private boolean accountKeyMatches(List<DataElement> extendedProperties) {
        Set<byte[]> accountKeys = new ArraySet<>();
        for (DataElement requestedDe : mExtendedProperties) {
            if (requestedDe.getKey() != DataElement.DataType.ACCOUNT_KEY) {
                continue;
            }
            accountKeys.add(requestedDe.getValue());
        }
        for (DataElement scannedDe : extendedProperties) {
            if (scannedDe.getKey() != DataElement.DataType.ACCOUNT_KEY) {
                continue;
            }
            // If one account key matches, then returns true.
            for (byte[] key : accountKeys) {
                if (Arrays.equals(key, scannedDe.getValue())) {
                    Log.d("NearbyService", "PresenceDiscoveryResult account key matched");
                    return true;
                }
            }
        }
        return false;
    }

    /** Converts a presence device from the discovery result. */
    public PresenceDevice toPresenceDevice() {
        return new PresenceDevice.Builder(
                // Use the public credential hash as the device Id.
                String.valueOf(mPublicCredential.hashCode()),
                mSalt,
                mPublicCredential.getSecretId(),
                mPublicCredential.getEncryptedMetadata())
                .setRssi(mRssi)
                .addMedium(NearbyDevice.Medium.BLE)
                .build();
    }

    /** Builder for {@link PresenceDiscoveryResult}. */
    public static class Builder {
        private int mTxPower;
        private int mRssi;
        private byte[] mSalt;

        private PublicCredential mPublicCredential;
        private final List<Integer> mPresenceActions;
        private final List<DataElement> mExtendedProperties;

        public Builder() {
            mPresenceActions = new ArrayList<>();
            mExtendedProperties = new ArrayList<>();
        }

        /** Sets the calibrated tx power for the discovery result. */
        public Builder setTxPower(int txPower) {
            mTxPower = txPower;
            return this;
        }

        /** Sets the rssi for the discovery result. */
        public Builder setRssi(int rssi) {
            mRssi = rssi;
            return this;
        }

        /** Sets the salt for the discovery result. */
        public Builder setSalt(byte[] salt) {
            mSalt = salt;
            return this;
        }

        /** Sets the public credential for the discovery result. */
        public Builder setPublicCredential(PublicCredential publicCredential) {
            mPublicCredential = publicCredential;
            return this;
        }

        /** Adds presence action of the discovery result. */
        public Builder addPresenceAction(int presenceAction) {
            mPresenceActions.add(presenceAction);
            return this;
        }

        /** Adds presence {@link DataElement}s of the discovery result. */
        public Builder addExtendedProperties(@NonNull List<DataElement> dataElements) {
            mExtendedProperties.addAll(dataElements);
            return this;
        }

        /** Builds a {@link PresenceDiscoveryResult}. */
        public PresenceDiscoveryResult build() {
            return new PresenceDiscoveryResult(
                    mTxPower, mRssi, mSalt, mPresenceActions,
                    mPublicCredential, mExtendedProperties);
        }
    }
}
