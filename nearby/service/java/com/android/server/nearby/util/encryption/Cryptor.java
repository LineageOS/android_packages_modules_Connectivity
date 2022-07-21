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

package com.android.server.nearby.util.encryption;

import androidx.annotation.Nullable;

/** Class for encryption/decryption functionality. */
public interface Cryptor {

    /**
     * Encrypt the provided data blob.
     *
     * @param data data blob to be encrypted.
     * @param salt used for IV
     * @param secretKeyBytes secrete key accessed from credentials
     * @return encrypted data, {@code null} if failed to encrypt.
     */
    @Nullable
    default byte[] encrypt(byte[] data, byte[] salt, byte[] secretKeyBytes) {
        return data;
    }

    /**
     * Decrypt the original data blob from the provided byte array.
     *
     * @param encryptedData data blob to be decrypted.
     * @param salt used for IV
     * @param secretKeyBytes secrete key accessed from credentials
     * @return decrypted data, {@code null} if failed to decrypt.
     */
    @Nullable
    default byte[] decrypt(byte[] encryptedData, byte[] salt, byte[] secretKeyBytes) {
        return encryptedData;
    }
}
