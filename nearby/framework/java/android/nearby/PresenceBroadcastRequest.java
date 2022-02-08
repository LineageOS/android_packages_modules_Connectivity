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

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * Request for Nearby Presence Broadcast.
 *
 * @hide
 */
public final class PresenceBroadcastRequest extends BroadcastRequest implements Parcelable {
    private final byte[] mSalt;
    private final List<Integer> mActions;
    private final PrivateCredential mCredential;
    private final List<DataElement> mExtendedProperties;

    private PresenceBroadcastRequest(@BroadcastVersion int version, int txPower,
            List<Integer> mediums, byte[] salt, List<Integer> actions,
            PrivateCredential credential, List<DataElement> extendedProperties) {
        super(BROADCAST_TYPE_NEARBY_PRESENCE, version, txPower, mediums);
        mSalt = salt;
        mActions = actions;
        mCredential = credential;
        mExtendedProperties = extendedProperties;
    }

    private PresenceBroadcastRequest(Parcel in) {
        super(BROADCAST_TYPE_NEARBY_PRESENCE, in);
        mSalt = new byte[in.readInt()];
        in.readByteArray(mSalt);

        mActions = new ArrayList<>();
        in.readList(mActions, Integer.class.getClassLoader(), Integer.class);
        mCredential = in.readParcelable(PrivateCredential.class.getClassLoader(),
                PrivateCredential.class);
        mExtendedProperties = new ArrayList<>();
        in.readList(mExtendedProperties, DataElement.class.getClassLoader(), DataElement.class);
    }

    @NonNull
    public static final Creator<PresenceBroadcastRequest> CREATOR =
            new Creator<PresenceBroadcastRequest>() {
                @Override
                public PresenceBroadcastRequest createFromParcel(Parcel in) {
                    // Skip Broadcast request type - it's used by parent class.
                    in.readInt();
                    return createFromParcelBody(in);
                }

                @Override
                public PresenceBroadcastRequest[] newArray(int size) {
                    return new PresenceBroadcastRequest[size];
                }
            };

    static PresenceBroadcastRequest createFromParcelBody(Parcel in) {
        return new PresenceBroadcastRequest(in);
    }

    /**
     * Returns the salt associated with this broadcast request.
     */
    @NonNull
    public byte[] getSalt() {
        return mSalt;
    }

    /**
     * Returns actions associated with this broadcast request.
     */
    @NonNull
    public List<Integer> getActions() {
        return mActions;
    }

    /**
     * Returns the private credential associated with this broadcast request.
     */
    @NonNull
    public PrivateCredential getCredential() {
        return mCredential;
    }

    /**
     * Returns extended property information associated with this broadcast request.
     */
    @NonNull
    public List<DataElement> getExtendedProperties() {
        return mExtendedProperties;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mSalt.length);
        dest.writeByteArray(mSalt);
        dest.writeList(mActions);
        dest.writeParcelable(mCredential, /** parcelableFlags= */0);
        dest.writeList(mExtendedProperties);
    }

    /**
     * Builder for {@link PresenceBroadcastRequest}.
     *
     * @hide
     */
    public static final class Builder {
        private final List<Integer> mMediums;
        private final List<Integer> mActions;
        private final List<DataElement> mExtendedProperties;

        private int mVersion;
        private int mTxPower;
        private byte[] mSalt;
        private PrivateCredential mCredential;

        public Builder() {
            mVersion = PRESENCE_VERSION_V0;
            mTxPower = UNKNOWN_TX_POWER;
            mMediums = new ArrayList<>();
            mActions = new ArrayList<>();
            mExtendedProperties = new ArrayList<>();
        }

        /**
         * Sets the version for this request.
         */
        @NonNull
        public Builder setVersion(@BroadcastVersion int version) {
            mVersion = version;
            return this;
        }

        /**
         * Sets the calibrated tx power level for this request.
         */
        @NonNull
        public Builder setTxPower(int txPower) {
            mTxPower = txPower;
            return this;
        }

        /**
         * Add a medium for the presence broadcast request.
         */
        @NonNull
        public Builder addMediums(int medium) {
            mMediums.add(medium);
            return this;
        }

        /**
         * Sets the salt for the presence broadcast request.
         */
        @NonNull
        public Builder setSalt(byte[] salt) {
            mSalt = salt;
            return this;
        }

        /**
         * Adds an action for the presence broadcast request.
         */
        @NonNull
        public Builder addAction(int action) {
            mActions.add(action);
            return this;
        }

        /**
         * Sets the credential associated with the presence broadcast request.
         */
        @NonNull
        public Builder setCredential(@NonNull PrivateCredential credential) {
            mCredential = credential;
            return this;
        }

        /**
         * Adds an extended property for the presence broadcast request.
         */
        @NonNull
        public Builder addExtendedProperty(DataElement dataElement) {
            mExtendedProperties.add(dataElement);
            return this;
        }

        /**
         * Builds a {@link PresenceBroadcastRequest}.
         */
        @NonNull
        public PresenceBroadcastRequest build() {
            Preconditions.checkState(!mMediums.isEmpty(), "mediums cannot be empty");
            Preconditions.checkState(mSalt != null && mSalt.length > 0, "salt cannot be empty");
            return new PresenceBroadcastRequest(mVersion, mTxPower, mMediums, mSalt, mActions,
                    mCredential, mExtendedProperties);
        }
    }
}
