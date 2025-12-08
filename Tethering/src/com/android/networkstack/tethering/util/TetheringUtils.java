/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.networkstack.tethering.util;

import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.TRANSPORT_USB;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_NCM;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_VIRTUAL;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.TetheringManager.TETHERING_WIGIG;

import android.net.TetherStatsParcel;
import android.net.TetheringManager.TetheringRequest;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.net.module.util.JniUtil;
import com.android.net.module.util.bpf.TetherStatsValue;

import java.io.FileDescriptor;
import java.net.Inet6Address;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * The classes and the methods for tethering utilization.
 *
 * @hide
 */
public class TetheringUtils {
    static {
        System.loadLibrary(getTetheringJniLibraryName());
    }

    public static final byte[] ALL_NODES = new byte[] {
        (byte) 0xff, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
    };

    /** The name should be com_android_networkstack_tethering_util_jni. */
    public static String getTetheringJniLibraryName() {
        return JniUtil.getJniLibraryName(TetheringUtils.class.getPackage());
    }

    /**
     * Configures a socket for receiving and sending ICMPv6 neighbor advertisments.
     * @param fd the socket's {@link FileDescriptor}.
     */
    public static native void setupNaSocket(FileDescriptor fd)
            throws SocketException;

    /**
     * Configures a socket for receiving and sending ICMPv6 neighbor solicitations.
     * @param fd the socket's {@link FileDescriptor}.
     */
    public static native void setupNsSocket(FileDescriptor fd)
            throws SocketException;

    /**
     *  The object which records offload Tx/Rx forwarded bytes/packets.
     *  TODO: Replace the inner class ForwardedStats of class OffloadHardwareInterface with
     *  this class as well.
     */
    public static class ForwardedStats {
        public final long rxBytes;
        public final long rxPackets;
        public final long txBytes;
        public final long txPackets;

        public ForwardedStats() {
            rxBytes = 0;
            rxPackets = 0;
            txBytes = 0;
            txPackets = 0;
        }

        public ForwardedStats(long rxBytes, long txBytes) {
            this.rxBytes = rxBytes;
            this.rxPackets = 0;
            this.txBytes = txBytes;
            this.txPackets = 0;
        }

        public ForwardedStats(long rxBytes, long rxPackets, long txBytes, long txPackets) {
            this.rxBytes = rxBytes;
            this.rxPackets = rxPackets;
            this.txBytes = txBytes;
            this.txPackets = txPackets;
        }

        public ForwardedStats(@NonNull TetherStatsParcel tetherStats) {
            rxBytes = tetherStats.rxBytes;
            rxPackets = tetherStats.rxPackets;
            txBytes = tetherStats.txBytes;
            txPackets = tetherStats.txPackets;
        }

        public ForwardedStats(@NonNull TetherStatsValue tetherStats) {
            rxBytes = tetherStats.rxBytes;
            rxPackets = tetherStats.rxPackets;
            txBytes = tetherStats.txBytes;
            txPackets = tetherStats.txPackets;
        }

        public ForwardedStats(@NonNull ForwardedStats other) {
            rxBytes = other.rxBytes;
            rxPackets = other.rxPackets;
            txBytes = other.txBytes;
            txPackets = other.txPackets;
        }

        /** Add Tx/Rx bytes/packets and return the result as a new object. */
        @NonNull
        public ForwardedStats add(@NonNull ForwardedStats other) {
            return new ForwardedStats(rxBytes + other.rxBytes, rxPackets + other.rxPackets,
                    txBytes + other.txBytes, txPackets + other.txPackets);
        }

        /** Subtract Tx/Rx bytes/packets and return the result as a new object. */
        @NonNull
        public ForwardedStats subtract(@NonNull ForwardedStats other) {
            // TODO: Perhaps throw an exception if any negative difference value just in case.
            final long rxBytesDiff = Math.max(rxBytes - other.rxBytes, 0);
            final long rxPacketsDiff = Math.max(rxPackets - other.rxPackets, 0);
            final long txBytesDiff = Math.max(txBytes - other.txBytes, 0);
            final long txPacketsDiff = Math.max(txPackets - other.txPackets, 0);
            return new ForwardedStats(rxBytesDiff, rxPacketsDiff, txBytesDiff, txPacketsDiff);
        }

        /** Returns the string representation of this object. */
        @NonNull
        public String toString() {
            return String.format("ForwardedStats(rxb: %d, rxp: %d, txb: %d, txp: %d)", rxBytes,
                    rxPackets, txBytes, txPackets);
        }
    }

    /**
     * Configures a socket for receiving ICMPv6 router solicitations and sending advertisements.
     * @param fd the socket's {@link FileDescriptor}.
     * @param ifIndex the interface index.
     */
    public static native void setupRaSocket(FileDescriptor fd, int ifIndex)
            throws SocketException;

    /**
     * Read s as an unsigned 16-bit integer.
     */
    public static int uint16(short s) {
        return s & 0xffff;
    }

    /** Get inet6 address for all nodes given scope ID. */
    public static Inet6Address getAllNodesForScopeId(int scopeId) {
        try {
            return Inet6Address.getByAddress("ff02::1", ALL_NODES, scopeId);
        } catch (UnknownHostException uhe) {
            Log.wtf("TetheringUtils", "Failed to construct Inet6Address from "
                    + Arrays.toString(ALL_NODES) + " and scopedId " + scopeId);
            return null;
        }
    }

    /**
     * Create a legacy tethering request for calls to the legacy tether() API, which doesn't take an
     * explicit request. These are always CONNECTIVITY_SCOPE_GLOBAL, per historical behavior.
     */
    @NonNull
    public static TetheringRequest createLegacyGlobalScopeTetheringRequest(int type) {
        final TetheringRequest request = new TetheringRequest.Builder(type).build();
        request.getParcel().requestType = TetheringRequest.REQUEST_TYPE_LEGACY;
        request.getParcel().connectivityScope = CONNECTIVITY_SCOPE_GLOBAL;
        return request;
    }

    /**
     * Create a local-only implicit tethering request. This is used for Wifi local-only hotspot and
     * Wifi P2P, which start tethering based on the WIFI_(AP/P2P)_STATE_CHANGED broadcasts.
     */
    @NonNull
    public static TetheringRequest createImplicitLocalOnlyTetheringRequest(int type) {
        final TetheringRequest request = new TetheringRequest.Builder(type).build();
        request.getParcel().requestType = TetheringRequest.REQUEST_TYPE_IMPLICIT;
        request.getParcel().connectivityScope = CONNECTIVITY_SCOPE_LOCAL;
        return request;
    }

    /**
     * Create a placeholder request. This is used in case we try to find a pending request but there
     * is none (e.g. stopTethering removed a pending request), or for cases where we only have the
     * tethering type (e.g. stopTethering(int)).
     */
    @NonNull
    public static TetheringRequest createPlaceholderRequest(int type) {
        final TetheringRequest request = new TetheringRequest.Builder(type).build();
        request.getParcel().requestType = TetheringRequest.REQUEST_TYPE_PLACEHOLDER;
        request.getParcel().connectivityScope = CONNECTIVITY_SCOPE_GLOBAL;
        return request;
    }

    /**
     * Returns the transport type for the given interface type.
     *
     * @param interfaceType The interface type.
     * @return The transport type.
     * @throws IllegalArgumentException if the interface type is invalid.
     */
    public static int getTransportTypeForTetherableType(int interfaceType) {
        switch (interfaceType) {
            case TETHERING_WIFI:
            case TETHERING_WIGIG:
            case TETHERING_WIFI_P2P:
                return TRANSPORT_WIFI;
            case TETHERING_USB:
            case TETHERING_NCM:
                return TRANSPORT_USB;
            case TETHERING_BLUETOOTH:
                return TRANSPORT_BLUETOOTH;
            case TETHERING_ETHERNET:
            case TETHERING_VIRTUAL: // For virtual machines.
                return TRANSPORT_ETHERNET;
            default:
                throw new IllegalArgumentException("Invalid interface type: " + interfaceType);
        }
    }
}
