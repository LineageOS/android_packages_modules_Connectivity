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

package com.android.server.ethernet;

import android.annotation.Nullable;
import android.net.MacAddress;
import android.text.TextUtils;

import java.util.Objects;

/**
 * A class representing an ethernet port. This class is used as an internal class to track existing
 * ethernet interfaces managed by EthernetTracker.
 */
public class EthernetPort {
    private final String mInterfaceName;

    private final MacAddress mMacAddress;

    private final int mInterfaceIndex;

    /**
     * Creates an EthernetPort for the given interface name, MAC address and index.
     */
    public EthernetPort(String interfaceName, MacAddress macAddress, int index) {
        if (TextUtils.isEmpty(interfaceName) || macAddress == null || index <= 0) {
            throw new IllegalArgumentException("Ethernet port should have valid interface name, "
                    + "MAC address and interface index.");
        }

        mInterfaceName = interfaceName;
        mMacAddress = macAddress;
        mInterfaceIndex = index;
    }

    /**
     * Get the name of the ethernet interface port.
     */
    public String getInterfaceName() {
        return mInterfaceName;
    }

    /**
     * Get the MAC address of the ethernet port.
     */
    public MacAddress getMacAddress() {
        return mMacAddress;
    }

    /**
     * Get the interface index of the ethernet port.
     */
    public int getInterfaceIndex() {
        return mInterfaceIndex;
    }

    @Override
    public String toString() {
        return "EthernetPort(" + mInterfaceName + ", ifindex=" + mInterfaceIndex
                + ", MAC=" + mMacAddress + ")";
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMacAddress, mInterfaceName, mInterfaceIndex);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof EthernetPort)) {
            return false;
        }

        final EthernetPort other = (EthernetPort) o;
        return Objects.equals(this.mInterfaceName, other.mInterfaceName)
                && Objects.equals(this.mMacAddress, other.mMacAddress)
                && this.mInterfaceIndex == other.mInterfaceIndex;
    }
}
