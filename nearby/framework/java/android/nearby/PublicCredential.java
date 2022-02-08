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
 * Represents a public credential.
 *
 * @hide
 */
public final class PublicCredential extends PresenceCredential implements Parcelable {
    @NonNull
    public static final Creator<PublicCredential> CREATOR = new Creator<PublicCredential>() {
        @Override
        public PublicCredential createFromParcel(Parcel in) {
            in.readInt(); // Skip the type as it's used by parent class only.
            return createFromParcelBody(in);
        }

        @Override
        public PublicCredential[] newArray(int size) {
            return new PublicCredential[size];
        }
    };

    private final byte[] mPublicKey;
    private final byte[] mEncryptedMetadata;
    private final byte[] mMetaDataEncryptionKeyTag;

    private PublicCredential(int identityType, byte[] secreteId, byte[] authenticityKey,
            List<CredentialElement> credentialElements, byte[] publicKey, byte[] encryptedMetadata,
            byte[] metaDataEncryptionKeyTag) {
        super(CREDENTIAL_TYPE_PUBLIC, identityType, secreteId, authenticityKey, credentialElements);
        mPublicKey = publicKey;
        mEncryptedMetadata = encryptedMetadata;
        mMetaDataEncryptionKeyTag = metaDataEncryptionKeyTag;
    }

    private PublicCredential(Parcel in) {
        super(CREDENTIAL_TYPE_PUBLIC, in);
        mPublicKey = new byte[in.readInt()];
        in.readByteArray(mPublicKey);
        mEncryptedMetadata = new byte[in.readInt()];
        in.readByteArray(mEncryptedMetadata);
        mMetaDataEncryptionKeyTag = new byte[in.readInt()];
        in.readByteArray(mMetaDataEncryptionKeyTag);
    }

    static PublicCredential createFromParcelBody(Parcel in) {
        return new PublicCredential(in);
    }

    /**
     * Returns the public key associated with this credential.
     */
    @NonNull
    public byte[] getPublicKey() {
        return mPublicKey;
    }

    /**
     * Returns the encrypted metadata associated with this credential.
     */
    @NonNull
    public byte[] getEncryptedMetadata() {
        return mEncryptedMetadata;
    }

    /**
     * Returns the metadata encryption key tag associated with this credential.
     */
    @NonNull
    public byte[] getMetaDataEncryptionKeyTag() {
        return mMetaDataEncryptionKeyTag;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mPublicKey.length);
        dest.writeByteArray(mPublicKey);
        dest.writeInt(mEncryptedMetadata.length);
        dest.writeByteArray(mEncryptedMetadata);
        dest.writeInt(mMetaDataEncryptionKeyTag.length);
        dest.writeByteArray(mMetaDataEncryptionKeyTag);
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
        private byte[] mPublicKey;
        private byte[] mEncryptedMetadata;
        private byte[] mMetaDataEncryptionKeyTag;

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
         * Adds an element to the credential.
         */
        @NonNull
        public Builder addCredentialElement(@NonNull CredentialElement credentialElement) {
            mCredentialElements.add(credentialElement);
            return this;
        }

        /**
         * Sets the public key for the credential.
         */
        @NonNull
        public Builder setPublicKey(@NonNull byte[] publicKey) {
            mPublicKey = publicKey;
            return this;
        }

        /**
         * Sets the encrypted metadata.
         */
        @NonNull
        public Builder setEncryptedMetadata(@NonNull byte[] encryptedMetadata) {
            mEncryptedMetadata = encryptedMetadata;
            return this;
        }

        /**
         * Sets the metadata encryption key tag.
         */
        @NonNull
        public Builder setMetaDataEncryptionKeyTag(@NonNull byte[] metaDataEncryptionKeyTag) {
            mMetaDataEncryptionKeyTag = metaDataEncryptionKeyTag;
            return this;
        }

        /**
         * Builds the {@link PresenceCredential}.
         */
        @NonNull
        public PublicCredential build() {
            Preconditions.checkState(mSecreteId.length > 0, "secrete id cannot be empty");
            Preconditions.checkState(mAuthenticityKey.length > 0,
                    "authenticity key cannot be empty");
            return new PublicCredential(mIdentityType, mSecreteId, mAuthenticityKey,
                    mCredentialElements, mPublicKey, mEncryptedMetadata, mMetaDataEncryptionKeyTag);
        }

    }
}
