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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;

import java.util.Objects;

/**
 * A class that identifies a socket.
 *
 * <p> A socket is typically created with an associated network. However, tethering interfaces do
 * not have an associated network, only an interface index. This means that the socket cannot be
 * identified in some places. Therefore, this class is necessary for identifying a socket. It
 * includes both the network and interface index.
 */
public class SocketKey {
    @Nullable
    private final Network mNetwork;
    private final int mInterfaceIndex;
    /**
     * The interface name is only for offload interface comparison and isn't used for equality or
     * hashing, which could lead to a behavior change.
     */
    @NonNull
    private final String mInterfaceName;
    private final int mHashCode;

    SocketKey(int interfaceIndex, @NonNull String interfaceName) {
        this(null /* network */, interfaceIndex, interfaceName);
    }

    SocketKey(@Nullable Network network, int interfaceIndex, @NonNull String interfaceName) {
        mNetwork = network;
        mInterfaceIndex = interfaceIndex;
        mInterfaceName = interfaceName;

        // Equivalent to Objects.hash(mNetwork, mInterfaceIndex), but without
        // the unnecessary array allocation.
        int hashCode = 1;
        hashCode = 31 * hashCode + (mNetwork == null ? 0 : mNetwork.hashCode());
        hashCode = 31 * hashCode + Integer.hashCode(mInterfaceIndex);
        mHashCode = hashCode;
    }

    @Nullable
    public Network getNetwork() {
        return mNetwork;
    }

    public int getInterfaceIndex() {
        return mInterfaceIndex;
    }

    @NonNull
    public String getInterfaceName() {
        return mInterfaceName;
    }

    @Override
    public int hashCode() {
        return mHashCode;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof SocketKey)) {
            return false;
        }
        return Objects.equals(mNetwork, ((SocketKey) other).mNetwork)
                && mInterfaceIndex == ((SocketKey) other).mInterfaceIndex;
    }

    @Override
    public String toString() {
        return "SocketKey{ network=" + mNetwork
                + " interfaceIndex=" + mInterfaceIndex
                + " interfaceName=" + mInterfaceName + " }";
    }
}
