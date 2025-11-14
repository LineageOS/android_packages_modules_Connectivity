/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.connectivity;

import android.annotation.Nullable;
import android.content.Context;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.BpfNetMaps;

import java.util.Map;

/**
 * InterfaceTracker is responsible for providing interface mapping and tracking.
 * @hide
 */
public class InterfaceTracker {
    static {
        if (BpfNetMaps.isAtLeast25Q2()) {
            System.loadLibrary("service-connectivity");
        }
    }
    private static final String TAG = "InterfaceTracker";
    private final Dependencies mDeps;
    @GuardedBy("mInterfaceMap")
    private final Map<String, Integer> mInterfaceMap;

    public InterfaceTracker(final Context context) {
        this(context, new Dependencies());
    }

    @VisibleForTesting
    public InterfaceTracker(final Context context, final Dependencies deps) {
        this.mInterfaceMap = new ArrayMap<>();
        this.mDeps = deps;
    }

    /**
     * To add interface to tracking
     * @param interfaceName name of interface added.
     */
    public void addInterface(@Nullable final String interfaceName) {
        final int interfaceIndex;
        if (interfaceName == null) {
            interfaceIndex = 0;
        } else {
            interfaceIndex = mDeps.getIfIndex(interfaceName);
        }
        if (interfaceIndex == 0) {
            Log.e(TAG, "Failed to get interface index for " + interfaceName);
            return;
        }
        synchronized (mInterfaceMap) {
            mInterfaceMap.put(interfaceName, interfaceIndex);
        }
    }

    /**
     * To remove interface from tracking
     * @param interfaceName name of interface removed.
     * @return true if the value was present and now removed.
     */
    public boolean removeInterface(@Nullable final String interfaceName) {
        if (interfaceName == null) return false;
        synchronized (mInterfaceMap) {
            return mInterfaceMap.remove(interfaceName) != null;
        }
    }

    /**
     * Get interface index from interface name.
     * @param interfaceName name of interface
     * @return interface index for given interface name or 0 if interface is not found.
     */
    public int getInterfaceIndex(@Nullable final String interfaceName) {
        final int interfaceIndex;
        if (interfaceName != null) {
            synchronized (mInterfaceMap) {
                interfaceIndex = mInterfaceMap.getOrDefault(interfaceName, 0);
            }
        } else {
            interfaceIndex = 0;
        }
        return interfaceIndex;
    }

    /**
     * Dependencies of InterfaceTracker, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Get interface index.
         */
        public int getIfIndex(final String ifName) {
            return Os.if_nametoindex(ifName);
        }

        /**
         * Get interface name
         */
        public String getIfName(final int ifIndex) {
            return Os.if_indextoname(ifIndex);
        }

    }
}
