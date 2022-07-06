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
package com.android.net.module.util;

import android.util.Base64;

import androidx.annotation.NonNull;

/**
 * The classes and the methods for BPF dump utilization.
 */
public class BpfDump {
    // Using "," as a separator between base64 encoded key and value is safe because base64
    // characters are [0-9a-zA-Z/=+].
    public static final String BASE64_DELIMITER = ",";

    /**
     * Encode BPF key and value into a base64 format string which uses the delimiter ',':
     * <base64 encoded key>,<base64 encoded value>
     */
    public static <K extends Struct, V extends Struct> String toBase64EncodedString(
            @NonNull final K key, @NonNull final V value) {
        final byte[] keyBytes = key.writeToBytes();
        final String keyBase64Str = Base64.encodeToString(keyBytes, Base64.DEFAULT)
                .replace("\n", "");
        final byte[] valueBytes = value.writeToBytes();
        final String valueBase64Str = Base64.encodeToString(valueBytes, Base64.DEFAULT)
                .replace("\n", "");

        return keyBase64Str + BASE64_DELIMITER + valueBase64Str;
    }

    // TODO: add a helper to dump bpf map content with the map name, the header line
    // (ex: "BPF ingress map: iif nat64Prefix v6Addr -> v4Addr oif"), a lambda that
    // knows how to dump each line, and the PrintWriter.
}
