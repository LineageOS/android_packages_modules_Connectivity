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

package android.nearby;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Filter for scanning a nearby presence device.
 *
 * @hide
 */
public final class PresenceScanFilter extends ScanFilter implements Parcelable {

    private final List<byte[]> mCertificates;
    private final List<Integer> mPresenceIdentities;
    private final List<Integer> mPresenceActions;
    private final Bundle mExtendedProperties;

    /**
     * A list of certificates to filter on.
     */
    @NonNull
    public List<byte[]> getCertificates() {
        return mCertificates;
    }

    /**
     * A list of presence identities for matching.
     */
    @NonNull
    public List<Integer> getPresenceIdentities() {
        return mPresenceIdentities;
    }

    /**
     * A list of presence actions for matching.
     */
    @NonNull
    public List<Integer> getPresenceActions() {
        return mPresenceActions;
    }

    /**
     * A bundle of extended properties for matching.
     */
    @NonNull
    public Bundle getExtendedProperties() {
        return mExtendedProperties;
    }

    private PresenceScanFilter(int rssiThreshold, List<byte[]> certificates,
            List<Integer> presenceIdentities, List<Integer> presenceActions,
            Bundle extendedProperties) {
        super(ScanRequest.SCAN_TYPE_NEARBY_PRESENCE, rssiThreshold);
        mCertificates = new ArrayList<>(certificates);
        mPresenceIdentities = new ArrayList<>(presenceIdentities);
        mPresenceActions = new ArrayList<>(presenceActions);
        mExtendedProperties = extendedProperties;
    }

    private PresenceScanFilter(Parcel in) {
        super(ScanRequest.SCAN_TYPE_NEARBY_PRESENCE, in);
        mCertificates = new ArrayList<>();
        int size = in.readInt();
        for (int i = 0; i < size; i++) {
            int len = in.readInt();
            byte[] certificate = new byte[len];
            in.readByteArray(certificate);
            mCertificates.add(certificate);
        }
        mPresenceIdentities = new ArrayList<>();
        if (in.readInt() != 0) {
            in.readList(mPresenceIdentities, Integer.class.getClassLoader(), Integer.class);
        }
        mPresenceActions = new ArrayList<>();
        if (in.readInt() != 0) {
            in.readList(mPresenceActions, Integer.class.getClassLoader(), Integer.class);
        }
        mExtendedProperties = new Bundle();
        Bundle bundle = in.readBundle(getClass().getClassLoader());
        for (String key : bundle.keySet()) {
            mExtendedProperties.putString(key, bundle.getString(key));
        }
    }

    @NonNull
    public static final Creator<PresenceScanFilter> CREATOR = new Creator<PresenceScanFilter>() {
        @Override
        public PresenceScanFilter createFromParcel(Parcel in) {
            // Skip Scan Filter type as it's used for parent class.
            in.readInt();
            return createFromParcelBody(in);
        }

        @Override
        public PresenceScanFilter[] newArray(int size) {
            return new PresenceScanFilter[size];
        }
    };

    /**
     * Create a {@link PresenceScanFilter} from the parcel body. Scan Filter type is skipped.
     */
    static PresenceScanFilter createFromParcelBody(Parcel in) {
        return new PresenceScanFilter(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mCertificates.size());
        for (byte[] certificate : mCertificates) {
            dest.writeInt(certificate.length);
            dest.writeByteArray(certificate);
        }
        dest.writeInt(mPresenceIdentities.size());
        if (!mPresenceIdentities.isEmpty()) {
            dest.writeList(mPresenceIdentities);
        }
        dest.writeInt(mPresenceActions.size());
        if (!mPresenceActions.isEmpty()) {
            dest.writeList(mPresenceActions);
        }
        dest.writeBundle(mExtendedProperties);
    }

    /**
     * Builder for {@link PresenceScanFilter}.
     *
     * @hide
     */
    public static final class Builder {
        private int mRssiThreshold;
        private final Set<byte[]> mCertificates;
        private final Set<Integer> mPresenceIdentities;
        private final Set<Integer> mPresenceActions;
        private final Bundle mExtendedProperties;

        public Builder() {
            mRssiThreshold = -100;
            mCertificates = new ArraySet<>();
            mPresenceIdentities = new ArraySet<>();
            mPresenceActions = new ArraySet<>();
            mExtendedProperties = new Bundle();
        }

        /**
         * Sets the rssi threshold for the scan request.
         */
        @NonNull
        public Builder setRssiThreshold(int rssiThreshold) {
            mRssiThreshold = rssiThreshold;
            return this;
        }

        /**
         * Adds a list of certificates the scan filter is expected to match.
         */

        @NonNull
        public Builder addCertificate(@NonNull byte[] certificate) {
            mCertificates.add(certificate);
            return this;
        }

        /**
         * Adds a presence identity for filtering.
         */
        @NonNull
        public Builder addPresenceIdentity(int identity) {
            mPresenceIdentities.add(identity);
            return this;
        }

        /**
         * Adds a presence action for filtering.
         */
        @NonNull
        public Builder addPresenceAction(int action) {
            mPresenceActions.add(action);
            return this;
        }

        /**
         * Add an extended property for scan filtering.
         */
        @NonNull
        public Builder addExtendedProperty(@NonNull String key, @Nullable String value) {
            mExtendedProperties.putCharSequence(key, value);
            return this;
        }

        /**
         * Builds the scan filter.
         */
        @NonNull
        public PresenceScanFilter build() {
            Preconditions.checkState(!mCertificates.isEmpty(), "certificates cannot be empty");
            return new PresenceScanFilter(mRssiThreshold, new ArrayList<>(mCertificates),
                    new ArrayList<>(mPresenceIdentities),
                    new ArrayList<>(mPresenceActions),
                    mExtendedProperties);
        }
    }
}
