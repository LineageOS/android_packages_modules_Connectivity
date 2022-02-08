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
 * Represents a private credential.
 *
 * @hide
 */
public final class PrivateCredential extends PresenceCredential implements Parcelable {

    @NonNull
    public static final Creator<PrivateCredential> CREATOR = new Creator<PrivateCredential>() {
        @Override
        public PrivateCredential createFromParcel(Parcel in) {
            in.readInt(); // Skip the type as it's used by parent class only.
            return createFromParcelBody(in);
        }

        @Override
        public PrivateCredential[] newArray(int size) {
            return new PrivateCredential[size];
        }
    };

    private byte[] mMetaDataEncryptionKey;
    private String mDeviceName;

    private PrivateCredential(Parcel in) {
        super(CREDENTIAL_TYPE_PRIVATE, in);
        mMetaDataEncryptionKey = new byte[in.readInt()];
        in.readByteArray(mMetaDataEncryptionKey);
        mDeviceName = in.readString();
    }

    private PrivateCredential(int identityType, byte[] secreteId,
            String deviceName, byte[] authenticityKey, List<CredentialElement> credentialElements,
            byte[] metaDataEncryptionKey) {
        super(CREDENTIAL_TYPE_PRIVATE, identityType, secreteId, authenticityKey,
                credentialElements);
        mDeviceName = deviceName;
        mMetaDataEncryptionKey = metaDataEncryptionKey;
    }

    static PrivateCredential createFromParcelBody(Parcel in) {
        return new PrivateCredential(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mMetaDataEncryptionKey.length);
        dest.writeByteArray(mMetaDataEncryptionKey);
        dest.writeString(mDeviceName);
    }

    /**
     * Returns the metadata encryption key associated with this credential.
     */
    @NonNull
    public byte[] getMetaDataEncryptionKey() {
        return mMetaDataEncryptionKey;
    }

    /**
     * Returns the device name associated with this credential.
     */
    @NonNull
    public String getDeviceName() {
        return mDeviceName;
    }

    /**
     * Builder class for {@link PresenceCredential}.
     *
     * @hide
     */
    public static final class Builder {
        private final List<CredentialElement> mCredentialElements;

        private @IdentityType int mIdentityType;
        private byte[] mSecreteId;
        private byte[] mAuthenticityKey;
        private byte[] mMetaDataEncryptionKey;
        private String mDeviceName;

        public Builder() {
            mCredentialElements = new ArrayList<>();
        }

        /**
         * Sets the identity type for the presence credential.
         */
        @NonNull
        public Builder setIdentityType(@IdentityType int identityType) {
            mIdentityType = identityType;
            return this;
        }

        /**
         * Sets the secrete id for the presence credential.
         */
        @NonNull
        public Builder setSecretId(@NonNull byte[] secreteId) {
            mSecreteId = secreteId;
            return this;
        }

        /**
         * Sets the authenticity key for the presence credential.
         */
        @NonNull
        public Builder setAuthenticityKey(@NonNull byte[] authenticityKey) {
            mAuthenticityKey = authenticityKey;
            return this;
        }

        /**
         * Sets the metadata encryption key to the credential.
         */
        @NonNull
        public Builder setMetaDataEncryptionKey(@NonNull byte[] metaDataEncryptionKey) {
            mMetaDataEncryptionKey = metaDataEncryptionKey;
            return this;
        }

        /**
         * Sets the device name of the credential.
         */
        @NonNull
        public Builder setDeviceName(@NonNull String deviceName) {
            mDeviceName = deviceName;
            return this;
        }

        /**
         * Adds an element to the credential.
         */
        @NonNull
        public Builder addCredentialElement(@NonNull CredentialElement credentialElement) {
            mCredentialElements.add(credentialElement);
            return this;
        }

        /**
         * Builds the {@link PresenceCredential}.
         */
        @NonNull
        public PrivateCredential build() {
            Preconditions.checkState(mSecreteId != null && mSecreteId.length > 0,
                    "secrete id cannot be empty");
            Preconditions.checkState(mAuthenticityKey != null && mAuthenticityKey.length > 0,
                    "authenticity key cannot be empty");
            return new PrivateCredential(mIdentityType, mSecreteId, mDeviceName,
                    mAuthenticityKey, mCredentialElements, mMetaDataEncryptionKey);
        }

    }
}
