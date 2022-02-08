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

    private final List<PublicCredential> mCredentials;
    private final List<Integer> mPresenceActions;
    private final List<DataElement> mExtendedProperties;

    /**
     * A list of credentials to filter on.
     */
    @NonNull
    public List<PublicCredential> getCredentials() {
        return mCredentials;
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
    public List<DataElement> getExtendedProperties() {
        return mExtendedProperties;
    }

    private PresenceScanFilter(int rssiThreshold, List<PublicCredential> credentials,
            List<Integer> presenceActions, List<DataElement> extendedProperties) {
        super(ScanRequest.SCAN_TYPE_NEARBY_PRESENCE, rssiThreshold);
        mCredentials = new ArrayList<>(credentials);
        mPresenceActions = new ArrayList<>(presenceActions);
        mExtendedProperties = extendedProperties;
    }

    private PresenceScanFilter(Parcel in) {
        super(ScanRequest.SCAN_TYPE_NEARBY_PRESENCE, in);
        mCredentials = new ArrayList<>();
        if (in.readInt() != 0) {
            in.readParcelableList(mCredentials, PublicCredential.class.getClassLoader(),
                    PublicCredential.class);
        }
        mPresenceActions = new ArrayList<>();
        if (in.readInt() != 0) {
            in.readList(mPresenceActions, Integer.class.getClassLoader(), Integer.class);
        }
        mExtendedProperties = new ArrayList<>();
        if (in.readInt() != 0) {
            in.readParcelableList(mExtendedProperties, DataElement.class.getClassLoader(),
                    DataElement.class);
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
        dest.writeInt(mCredentials.size());
        if (!mCredentials.isEmpty()) {
            dest.writeParcelableList(mCredentials, 0);
        }
        dest.writeInt(mPresenceActions.size());
        if (!mPresenceActions.isEmpty()) {
            dest.writeList(mPresenceActions);
        }
        dest.writeInt(mExtendedProperties.size());
        if (!mExtendedProperties.isEmpty()) {
            dest.writeList(mExtendedProperties);
        }
    }

    /**
     * Builder for {@link PresenceScanFilter}.
     *
     * @hide
     */
    public static final class Builder {
        private int mRssiThreshold;
        private final Set<PublicCredential> mCredentials;
        private final Set<Integer> mPresenceIdentities;
        private final Set<Integer> mPresenceActions;
        private final List<DataElement> mExtendedProperties;

        public Builder() {
            mRssiThreshold = -100;
            mCredentials = new ArraySet<>();
            mPresenceIdentities = new ArraySet<>();
            mPresenceActions = new ArraySet<>();
            mExtendedProperties = new ArrayList<>();
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
         * Adds a list of credentials the scan filter is expected to match.
         */

        @NonNull
        public Builder addCredential(@NonNull PublicCredential credential) {
            mCredentials.add(credential);
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
        public Builder addExtendedProperty(@NonNull DataElement dataElement) {
            mExtendedProperties.add(dataElement);
            return this;
        }

        /**
         * Builds the scan filter.
         */
        @NonNull
        public PresenceScanFilter build() {
            Preconditions.checkState(!mCredentials.isEmpty(), "credentials cannot be empty");
            return new PresenceScanFilter(mRssiThreshold,
                    new ArrayList<>(mCredentials),
                    new ArrayList<>(mPresenceActions),
                    mExtendedProperties);
        }
    }
}
