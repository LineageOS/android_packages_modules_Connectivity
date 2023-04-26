/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.connectivity.mdns.util;

import android.annotation.NonNull;

/**
 * Mdns utility functions.
 */
public class MdnsUtils {

    private MdnsUtils() { }

    /**
     * Convert the string to DNS case-insensitive lowercase
     *
     * Per rfc6762#page-46, accented characters are not defined to be automatically equivalent to
     * their unaccented counterparts. So the "DNS lowercase" should be if character is A-Z then they
     * transform into a-z. Otherwise, they are kept as-is.
     */
    public static String toDnsLowerCase(@NonNull String string) {
        final char[] outChars = new char[string.length()];
        for (int i = 0; i < string.length(); i++) {
            outChars[i] = toDnsLowerCase(string.charAt(i));
        }
        return new String(outChars);
    }

    /**
     * Compare two strings by DNS case-insensitive lowercase.
     */
    public static boolean equalsIgnoreDnsCase(@NonNull String a, @NonNull String b) {
        if (a.length() != b.length()) return false;
        for (int i = 0; i < a.length(); i++) {
            if (toDnsLowerCase(a.charAt(i)) != toDnsLowerCase(b.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static char toDnsLowerCase(char a) {
        return a >= 'A' && a <= 'Z' ? (char) (a + ('a' - 'A')) : a;
    }
}
