/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE;

import android.annotation.NonNull;


/**
 * Utilities to examine {@link android.net.NetworkCapabilities}.
 */
public final class NetworkCapabilitiesUtils {
    // Transports considered to classify networks in UI, in order of which transport should be
    // surfaced when there are multiple transports. Transports not in this list do not have
    // an ordering preference (in practice they will have a deterministic order based on the
    // transport int itself).
    private static final int[] DISPLAY_TRANSPORT_PRIORITIES = new int[] {
        // Users think of their VPNs as VPNs, not as any of the underlying nets
        TRANSPORT_VPN,
        // If the network has cell, prefer showing that because it's usually metered.
        TRANSPORT_CELLULAR,
        // If the network has WiFi aware, prefer showing that as it's a more specific use case.
        // Ethernet can masquerade as other transports, where the device uses ethernet to connect to
        // a box providing cell or wifi. Today this is represented by only the masqueraded type for
        // backward compatibility, but these networks should morally have Ethernet & the masqueraded
        // type. Because of this, prefer other transports instead of Ethernet.
        TRANSPORT_WIFI_AWARE,
        TRANSPORT_BLUETOOTH,
        TRANSPORT_WIFI,
        TRANSPORT_ETHERNET

        // Notably, TRANSPORT_TEST is not in this list as any network that has TRANSPORT_TEST and
        // one of the above transports should be counted as that transport, to keep tests as
        // realistic as possible.
    };

    /**
     * Get a transport that can be used to classify a network when displaying its info to users.
     *
     * While networks can have multiple transports, users generally think of them as "wifi",
     * "mobile data", "vpn" and expect them to be classified as such in UI such as settings.
     * @param transports Non-empty array of transports on a network
     * @return A single transport
     * @throws IllegalArgumentException The array is empty
     */
    public static int getDisplayTransport(@NonNull int[] transports) {
        for (int transport : DISPLAY_TRANSPORT_PRIORITIES) {
            if (CollectionUtils.contains(transports, transport)) {
                return transport;
            }
        }

        if (transports.length < 1) {
            // All NetworkCapabilities representing a network have at least one transport, so an
            // empty transport array would be created by the caller instead of extracted from
            // NetworkCapabilities.
            throw new IllegalArgumentException("No transport in the provided array");
        }
        return transports[0];
    }

    /**
     * Unpacks long value into an array of bits.
     */
    public static int[] unpackBits(long val) {
        int size = Long.bitCount(val);
        int[] result = new int[size];
        int index = 0;
        int bitPos = 0;
        while (val != 0) {
            if ((val & 1) == 1) result[index++] = bitPos;
            val = val >>> 1;
            bitPos++;
        }
        return result;
    }

    /**
     * Packs array of bits into a long value.
     */
    public static long packBits(int[] bits) {
        long packed = 0;
        for (int b : bits) {
            packed |= (1L << b);
        }
        return packed;
    }
}
