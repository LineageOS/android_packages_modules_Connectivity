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

package com.android.server.nearby.util;

/**
 * Hex class that contains hex related functions.
 */
public class Hex {
    private static final char[] HEX_LOWERCASE = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    /**
     * Bytes array to lower case string.
     */
    public static String bytesToStringLowercase(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        int j = 0;
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[j++] = HEX_LOWERCASE[v >>> 4];
            hexChars[j++] = HEX_LOWERCASE[v & 0x0F];
        }
        return new String(hexChars);
    }
}
