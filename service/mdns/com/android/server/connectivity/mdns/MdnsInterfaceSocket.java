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

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.MdnsSocket.MULTICAST_IPV4_ADDRESS;
import static com.android.server.connectivity.mdns.MdnsSocket.MULTICAST_IPV6_ADDRESS;

import android.annotation.NonNull;
import android.net.LinkAddress;
import android.net.util.SocketUtils;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.List;

/**
 * {@link MdnsInterfaceSocket} provides a similar interface to {@link MulticastSocket} and binds to
 * an available multicast network interfaces.
 *
 * <p>This isn't thread safe and should be always called on the same thread unless specified
 * otherwise.
 *
 * @see MulticastSocket for javadoc of each public method.
 */
public class MdnsInterfaceSocket {
    private static final String TAG = MdnsInterfaceSocket.class.getSimpleName();
    @NonNull private final MulticastSocket mMulticastSocket;
    @NonNull private final NetworkInterface mNetworkInterface;
    private boolean mJoinedIpv4 = false;
    private boolean mJoinedIpv6 = false;

    public MdnsInterfaceSocket(@NonNull NetworkInterface networkInterface, int port)
            throws IOException {
        mNetworkInterface = networkInterface;
        mMulticastSocket = new MulticastSocket(port);
        // RFC Spec: https://tools.ietf.org/html/rfc6762. Time to live is set 255
        mMulticastSocket.setTimeToLive(255);
        mMulticastSocket.setNetworkInterface(networkInterface);

        // Bind socket to the interface for receiving from that interface only.
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(mMulticastSocket)) {
            SocketUtils.bindSocketToInterface(pfd.getFileDescriptor(), mNetworkInterface.getName());
        } catch (ErrnoException e) {
            throw new IOException("Error setting socket options", e);
        }
    }

    /**
     * Sends a datagram packet from this socket.
     *
     * <p>This method could be used on any thread.
     */
    public void send(@NonNull DatagramPacket packet) throws IOException {
        mMulticastSocket.send(packet);
    }

    /**
     * Receives a datagram packet from this socket.
     *
     * <p>This method could be used on any thread.
     */
    public void receive(@NonNull DatagramPacket packet) throws IOException {
        mMulticastSocket.receive(packet);
    }

    private boolean hasIpv4Address(List<LinkAddress> addresses) {
        for (LinkAddress address : addresses) {
            if (address.isIpv4()) return true;
        }
        return false;
    }

    private boolean hasIpv6Address(List<LinkAddress> addresses) {
        for (LinkAddress address : addresses) {
            if (address.isIpv6()) return true;
        }
        return false;
    }

    /*** Joins both IPv4 and IPv6 multicast groups. */
    public void joinGroup(@NonNull List<LinkAddress> addresses) {
        maybeJoinIpv4(addresses);
        maybeJoinIpv6(addresses);
    }

    private boolean joinGroup(InetSocketAddress multicastAddress) {
        try {
            mMulticastSocket.joinGroup(multicastAddress, mNetworkInterface);
            return true;
        } catch (IOException e) {
            // The address may have just been removed
            Log.e(TAG, "Error joining multicast group for " + mNetworkInterface, e);
            return false;
        }
    }

    private void maybeJoinIpv4(List<LinkAddress> addresses) {
        final boolean hasAddr = hasIpv4Address(addresses);
        if (!mJoinedIpv4 && hasAddr) {
            mJoinedIpv4 = joinGroup(MULTICAST_IPV4_ADDRESS);
        } else if (!hasAddr) {
            // Lost IPv4 address
            mJoinedIpv4 = false;
        }
    }

    private void maybeJoinIpv6(List<LinkAddress> addresses) {
        final boolean hasAddr = hasIpv6Address(addresses);
        if (!mJoinedIpv6 && hasAddr) {
            mJoinedIpv6 = joinGroup(MULTICAST_IPV6_ADDRESS);
        } else if (!hasAddr) {
            // Lost IPv6 address
            mJoinedIpv6 = false;
        }
    }

    /*** Destroy this socket by leaving all joined multicast groups and closing this socket. */
    public void destroy() {
        if (mJoinedIpv4) {
            try {
                mMulticastSocket.leaveGroup(MULTICAST_IPV4_ADDRESS, mNetworkInterface);
            } catch (IOException e) {
                Log.e(TAG, "Error leaving IPv4 group for " + mNetworkInterface, e);
            }
        }
        if (mJoinedIpv6) {
            try {
                mMulticastSocket.leaveGroup(MULTICAST_IPV6_ADDRESS, mNetworkInterface);
            } catch (IOException e) {
                Log.e(TAG, "Error leaving IPv4 group for " + mNetworkInterface, e);
            }
        }
        mMulticastSocket.close();
    }

    /**
     * Returns the index of the network interface that this socket is bound to. If the interface
     * cannot be determined, returns -1.
     *
     * <p>This method could be used on any thread.
     */
    public int getInterfaceIndex() {
        return mNetworkInterface.getIndex();
    }

    /*** Returns whether this socket has joined IPv4 group */
    public boolean hasJoinedIpv4() {
        return mJoinedIpv4;
    }

    /*** Returns whether this socket has joined IPv6 group */
    public boolean hasJoinedIpv6() {
        return mJoinedIpv6;
    }
}
