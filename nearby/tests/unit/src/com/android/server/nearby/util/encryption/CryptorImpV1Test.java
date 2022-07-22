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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit test for {@link CryptorImpV1}
 */
public final class CryptorImpV1Test {
    private static final byte[] SALT = new byte[] {102, 22};
    private static final byte[] DATA =
            new byte[] {107, -102, 101, 107, 20, 62, 2, 73, 113, 59, 8, -14, -58, 122};
    private static final byte[] AUTHENTICITY_KEY =
            new byte[] {-89, 88, -50, -42, -99, 57, 84, -24, 121, 1, -104, -8, -26, -73, -36, 100};

    @Test
    public void decryptionEncryptionTest() {
        Cryptor v1Cryptor = CryptorImpV1.getInstance();
        byte[] encryptedData = v1Cryptor.encrypt(DATA, SALT, AUTHENTICITY_KEY);
        byte[] decryptedData =
                v1Cryptor.decrypt(encryptedData, SALT, AUTHENTICITY_KEY);
        assertThat(decryptedData).isEqualTo(DATA);
    }
}
