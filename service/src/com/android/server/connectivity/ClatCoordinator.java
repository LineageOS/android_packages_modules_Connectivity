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

package com.android.server.connectivity;

import static android.net.INetd.IF_STATE_UP;
import static android.net.INetd.PERMISSION_SYSTEM;

import static com.android.net.module.util.NetworkStackConstants.IPV6_MIN_MTU;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.InterfaceParams;
import com.android.net.module.util.TcUtils;
import com.android.net.module.util.bpf.ClatEgress4Key;
import com.android.net.module.util.bpf.ClatEgress4Value;
import com.android.net.module.util.bpf.ClatIngress6Key;
import com.android.net.module.util.bpf.ClatIngress6Value;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * This coordinator is responsible for providing clat relevant functionality.
 *
 * {@hide}
 */
public class ClatCoordinator {
    private static final String TAG = ClatCoordinator.class.getSimpleName();

    // Sync from external/android-clat/clatd.c
    // 40 bytes IPv6 header - 20 bytes IPv4 header + 8 bytes fragment header.
    @VisibleForTesting
    static final int MTU_DELTA = 28;
    @VisibleForTesting
    static final int CLAT_MAX_MTU = 65536;

    // This must match the interface prefix in clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    // For historical reasons, start with 192.0.0.4, and after that, use all subsequent addresses
    // in 192.0.0.0/29 (RFC 7335).
    @VisibleForTesting
    static final String INIT_V4ADDR_STRING = "192.0.0.4";
    @VisibleForTesting
    static final int INIT_V4ADDR_PREFIX_LEN = 29;
    private static final InetAddress GOOGLE_DNS_4 = InetAddress.parseNumericAddress("8.8.8.8");

    private static final int INVALID_IFINDEX = 0;

    private static final String CLAT_EGRESS4_MAP_PATH = makeMapPath("egress4");
    private static final String CLAT_INGRESS6_MAP_PATH = makeMapPath("ingress6");

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/map_clatd_clat_" + which + "_map";
    }

    @NonNull
    private final INetd mNetd;
    @NonNull
    private final Dependencies mDeps;
    @Nullable
    private final IBpfMap<ClatIngress6Key, ClatIngress6Value> mIngressMap;
    @Nullable
    private final IBpfMap<ClatEgress4Key, ClatEgress4Value> mEgressMap;
    @Nullable
    private ClatdTracker mClatdTracker = null;

    @VisibleForTesting
    abstract static class Dependencies {
        /**
          * Get netd.
          */
        @NonNull
        public abstract INetd getNetd();

        /**
         * @see ParcelFileDescriptor#adoptFd(int).
         */
        @NonNull
        public ParcelFileDescriptor adoptFd(int fd) {
            return ParcelFileDescriptor.adoptFd(fd);
        }

        /**
         * Get interface index for a given interface.
         */
        public int getInterfaceIndex(String ifName) {
            final InterfaceParams params = InterfaceParams.getByName(ifName);
            return params != null ? params.index : INVALID_IFINDEX;
        }

        /**
         * Create tun interface for a given interface name.
         */
        public int createTunInterface(@NonNull String tuniface) throws IOException {
            return native_createTunInterface(tuniface);
        }

        /**
         * Pick an IPv4 address for clat.
         */
        @NonNull
        public String selectIpv4Address(@NonNull String v4addr, int prefixlen)
                throws IOException {
            return native_selectIpv4Address(v4addr, prefixlen);
        }

        /**
         * Generate a checksum-neutral IID.
         */
        @NonNull
        public String generateIpv6Address(@NonNull String iface, @NonNull String v4,
                @NonNull String prefix64) throws IOException {
            return native_generateIpv6Address(iface, v4, prefix64);
        }

        /**
         * Detect MTU.
         */
        public int detectMtu(@NonNull String platSubnet, int platSuffix, int mark)
                throws IOException {
            return native_detectMtu(platSubnet, platSuffix, mark);
        }

        /**
         * Open packet socket.
         */
        public int openPacketSocket() throws IOException {
            return native_openPacketSocket();
        }

        /**
         * Open IPv6 raw socket and set SO_MARK.
         */
        public int openRawSocket6(int mark) throws IOException {
            return native_openRawSocket6(mark);
        }

        /**
         * Add anycast setsockopt.
         */
        public void addAnycastSetsockopt(@NonNull FileDescriptor sock, String v6, int ifindex)
                throws IOException {
            native_addAnycastSetsockopt(sock, v6, ifindex);
        }

        /**
         * Configure packet socket.
         */
        public void configurePacketSocket(@NonNull FileDescriptor sock, String v6, int ifindex)
                throws IOException {
            native_configurePacketSocket(sock, v6, ifindex);
        }

        /**
         * Start clatd.
         */
        public int startClatd(@NonNull FileDescriptor tunfd, @NonNull FileDescriptor readsock6,
                @NonNull FileDescriptor writesock6, @NonNull String iface, @NonNull String pfx96,
                @NonNull String v4, @NonNull String v6) throws IOException {
            return native_startClatd(tunfd, readsock6, writesock6, iface, pfx96, v4, v6);
        }

        /**
         * Stop clatd.
         */
        public void stopClatd(String iface, String pfx96, String v4, String v6, int pid)
                throws IOException {
            native_stopClatd(iface, pfx96, v4, v6, pid);
        }

        /**
         * Tag socket as clat.
         */
        public long tagSocketAsClat(@NonNull FileDescriptor sock) throws IOException {
            return native_tagSocketAsClat(sock);
        }

        /**
         * Untag socket.
         */
        public void untagSocket(long cookie) throws IOException {
            native_untagSocket(cookie);
        }

        /** Get ingress6 BPF map. */
        @Nullable
        public IBpfMap<ClatIngress6Key, ClatIngress6Value> getBpfIngress6Map() {
            // Pre-T devices don't use ClatCoordinator to access clat map. Since Nat464Xlat
            // initializes a ClatCoordinator object to avoid redundant null pointer check
            // while using, ignore the BPF map initialization on pre-T devices.
            // TODO: probably don't initialize ClatCoordinator object on pre-T devices.
            if (!SdkLevel.isAtLeastT()) return null;
            try {
                return new BpfMap<>(CLAT_INGRESS6_MAP_PATH,
                    BpfMap.BPF_F_RDWR, ClatIngress6Key.class, ClatIngress6Value.class);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create ingress6 map: " + e);
                return null;
            }
        }

        /** Get egress4 BPF map. */
        @Nullable
        public IBpfMap<ClatEgress4Key, ClatEgress4Value> getBpfEgress4Map() {
            // Pre-T devices don't use ClatCoordinator to access clat map. Since Nat464Xlat
            // initializes a ClatCoordinator object to avoid redundant null pointer check
            // while using, ignore the BPF map initialization on pre-T devices.
            // TODO: probably don't initialize ClatCoordinator object on pre-T devices.
            if (!SdkLevel.isAtLeastT()) return null;
            try {
                return new BpfMap<>(CLAT_EGRESS4_MAP_PATH,
                    BpfMap.BPF_F_RDWR, ClatEgress4Key.class, ClatEgress4Value.class);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create egress4 map: " + e);
                return null;
            }
        }

        /** Checks if the network interface uses an ethernet L2 header. */
        public boolean isEthernet(String iface) throws IOException {
            return TcUtils.isEthernet(iface);
        }
    }

    @VisibleForTesting
    static class ClatdTracker {
        @NonNull
        public final String iface;
        public final int ifIndex;
        @NonNull
        public final String v4iface;
        public final int v4ifIndex;
        @NonNull
        public final Inet4Address v4;
        @NonNull
        public final Inet6Address v6;
        @NonNull
        public final Inet6Address pfx96;
        public final int pid;
        public final long cookie;

        ClatdTracker(@NonNull String iface, int ifIndex, @NonNull String v4iface,
                int v4ifIndex, @NonNull Inet4Address v4, @NonNull Inet6Address v6,
                @NonNull Inet6Address pfx96, int pid, long cookie) {
            this.iface = iface;
            this.ifIndex = ifIndex;
            this.v4iface = v4iface;
            this.v4ifIndex = v4ifIndex;
            this.v4 = v4;
            this.v6 = v6;
            this.pfx96 = pfx96;
            this.pid = pid;
            this.cookie = cookie;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ClatdTracker)) return false;
            ClatdTracker that = (ClatdTracker) o;
            return Objects.equals(this.iface, that.iface)
                    && this.ifIndex == that.ifIndex
                    && Objects.equals(this.v4iface, that.v4iface)
                    && this.v4ifIndex == that.v4ifIndex
                    && Objects.equals(this.v4, that.v4)
                    && Objects.equals(this.v6, that.v6)
                    && Objects.equals(this.pfx96, that.pfx96)
                    && this.pid == that.pid
                    && this.cookie == that.cookie;
        }
    };

    @VisibleForTesting
    static int getFwmark(int netId) {
        // See union Fwmark in system/netd/include/Fwmark.h
        return (netId & 0xffff)
                | 0x1 << 16  // protectedFromVpn: true
                | 0x1 << 17  // explicitlySelected: true
                | (PERMISSION_SYSTEM & 0x3) << 18;
    }

    @VisibleForTesting
    static int adjustMtu(int mtu) {
        // clamp to minimum ipv6 mtu - this probably cannot ever trigger
        if (mtu < IPV6_MIN_MTU) mtu = IPV6_MIN_MTU;
        // clamp to buffer size
        if (mtu > CLAT_MAX_MTU) mtu = CLAT_MAX_MTU;
        // decrease by ipv6(40) + ipv6 fragmentation header(8) vs ipv4(20) overhead of 28 bytes
        mtu -= MTU_DELTA;

        return mtu;
    }

    public ClatCoordinator(@NonNull Dependencies deps) {
        mDeps = deps;
        mNetd = mDeps.getNetd();
        mIngressMap = mDeps.getBpfIngress6Map();
        mEgressMap = mDeps.getBpfEgress4Map();
    }

    private void maybeStartBpf(final ClatdTracker tracker) {
        if (mIngressMap == null || mEgressMap == null) return;

        final boolean isEthernet;
        try {
            isEthernet = mDeps.isEthernet(tracker.iface);
        } catch (IOException e) {
            Log.e(TAG, "Fail to call isEthernet for interface " + tracker.iface);
            return;
        }

        final ClatEgress4Key txKey = new ClatEgress4Key(tracker.v4ifIndex, tracker.v4);
        final ClatEgress4Value txValue = new ClatEgress4Value(tracker.ifIndex, tracker.v6,
                tracker.pfx96, (short) (isEthernet ? 1 /* ETHER */ : 0 /* RAWIP */));
        try {
            mEgressMap.insertEntry(txKey, txValue);
        } catch (ErrnoException | IllegalStateException e) {
            Log.e(TAG, "Could not insert entry (" + txKey + ", " + txValue + ") on egress map: "
                    + e);
            return;
        }

        final ClatIngress6Key rxKey = new ClatIngress6Key(tracker.ifIndex, tracker.pfx96,
                tracker.v6);
        final ClatIngress6Value rxValue = new ClatIngress6Value(tracker.v4ifIndex,
                tracker.v4);
        try {
            mIngressMap.insertEntry(rxKey, rxValue);
        } catch (ErrnoException | IllegalStateException e) {
            Log.e(TAG, "Could not insert entry (" + rxKey + ", " + rxValue + ") ingress map: "
                    + e);
            try {
                mEgressMap.deleteEntry(txKey);
            } catch (ErrnoException | IllegalStateException e2) {
                Log.e(TAG, "Could not delete entry (" + txKey + ") from egress map: " + e2);
            }
            return;
        }

        // TODO: attach program.
    }

    /**
     * Start clatd for a given interface and NAT64 prefix.
     */
    public String clatStart(final String iface, final int netId,
            @NonNull final IpPrefix nat64Prefix)
            throws IOException {
        if (mClatdTracker != null) {
            throw new IOException("Clatd is already running on " + mClatdTracker.iface
                    + " (pid " + mClatdTracker.pid + ")");
        }
        if (nat64Prefix.getPrefixLength() != 96) {
            throw new IOException("Prefix must be 96 bits long: " + nat64Prefix);
        }

        // [1] Pick an IPv4 address from 192.0.0.4, 192.0.0.5, 192.0.0.6 ..
        final String v4Str;
        try {
            v4Str = mDeps.selectIpv4Address(INIT_V4ADDR_STRING, INIT_V4ADDR_PREFIX_LEN);
        } catch (IOException e) {
            throw new IOException("no IPv4 addresses were available for clat: " + e);
        }

        final Inet4Address v4;
        try {
            v4 = (Inet4Address) InetAddresses.parseNumericAddress(v4Str);
        } catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid IPv4 address " + v4Str);
        }

        // [2] Generate a checksum-neutral IID.
        final String pfx96Str = nat64Prefix.getAddress().getHostAddress();
        final String v6Str;
        try {
            v6Str = mDeps.generateIpv6Address(iface, v4Str, pfx96Str);
        } catch (IOException e) {
            throw new IOException("no IPv6 addresses were available for clat: " + e);
        }

        final Inet6Address pfx96 = (Inet6Address) nat64Prefix.getAddress();
        final Inet6Address v6;
        try {
            v6 = (Inet6Address) InetAddresses.parseNumericAddress(v6Str);
        } catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid IPv6 address " + v6Str);
        }

        // [3] Open, configure and bring up the tun interface.
        // Create the v4-... tun interface.
        final String tunIface = CLAT_PREFIX + iface;
        final ParcelFileDescriptor tunFd;
        try {
            tunFd = mDeps.adoptFd(mDeps.createTunInterface(tunIface));
        } catch (IOException e) {
            throw new IOException("Create tun interface " + tunIface + " failed: " + e);
        }

        final int tunIfIndex = mDeps.getInterfaceIndex(tunIface);
        if (tunIfIndex == INVALID_IFINDEX) {
            tunFd.close();
            throw new IOException("Fail to get interface index for interface " + tunIface);
        }

        // disable IPv6 on it - failing to do so is not a critical error
        try {
            mNetd.interfaceSetEnableIPv6(tunIface, false /* enabled */);
        } catch (RemoteException | ServiceSpecificException e) {
            tunFd.close();
            Log.e(TAG, "Disable IPv6 on " + tunIface + " failed: " + e);
        }

        // Detect ipv4 mtu.
        final Integer fwmark = getFwmark(netId);
        final int detectedMtu = mDeps.detectMtu(pfx96Str,
                ByteBuffer.wrap(GOOGLE_DNS_4.getAddress()).getInt(), fwmark);
        final int mtu = adjustMtu(detectedMtu);
        Log.i(TAG, "ipv4 mtu is " + mtu);

        // TODO: add setIptablesDropRule

        // Config tun interface mtu, address and bring up.
        try {
            mNetd.interfaceSetMtu(tunIface, mtu);
        } catch (RemoteException | ServiceSpecificException e) {
            tunFd.close();
            throw new IOException("Set MTU " + mtu + " on " + tunIface + " failed: " + e);
        }
        final InterfaceConfigurationParcel ifConfig = new InterfaceConfigurationParcel();
        ifConfig.ifName = tunIface;
        ifConfig.ipv4Addr = v4Str;
        ifConfig.prefixLength = 32;
        ifConfig.hwAddr = "";
        ifConfig.flags = new String[] {IF_STATE_UP};
        try {
            mNetd.interfaceSetCfg(ifConfig);
        } catch (RemoteException | ServiceSpecificException e) {
            tunFd.close();
            throw new IOException("Setting IPv4 address to " + ifConfig.ipv4Addr + "/"
                    + ifConfig.prefixLength + " failed on " + ifConfig.ifName + ": " + e);
        }

        // [4] Open and configure local 464xlat read/write sockets.
        // Opens a packet socket to receive IPv6 packets in clatd.
        final ParcelFileDescriptor readSock6;
        try {
            // Use a JNI call to get native file descriptor instead of Os.socket() because we would
            // like to use ParcelFileDescriptor to manage file descriptor. But ctor
            // ParcelFileDescriptor(FileDescriptor fd) is a @hide function. Need to use native file
            // descriptor to initialize ParcelFileDescriptor object instead.
            readSock6 = mDeps.adoptFd(mDeps.openPacketSocket());
        } catch (IOException e) {
            tunFd.close();
            throw new IOException("Open packet socket failed: " + e);
        }

        // Opens a raw socket with a given fwmark to send IPv6 packets in clatd.
        final ParcelFileDescriptor writeSock6;
        try {
            // Use a JNI call to get native file descriptor instead of Os.socket(). See above
            // reason why we use jniOpenPacketSocket6().
            writeSock6 = mDeps.adoptFd(mDeps.openRawSocket6(fwmark));
        } catch (IOException e) {
            tunFd.close();
            readSock6.close();
            throw new IOException("Open raw socket failed: " + e);
        }

        final int ifIndex = mDeps.getInterfaceIndex(iface);
        if (ifIndex == INVALID_IFINDEX) {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
            throw new IOException("Fail to get interface index for interface " + iface);
        }

        // Start translating packets to the new prefix.
        try {
            mDeps.addAnycastSetsockopt(writeSock6.getFileDescriptor(), v6Str, ifIndex);
        } catch (IOException e) {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
            throw new IOException("add anycast sockopt failed: " + e);
        }

        // Tag socket as AID_CLAT to avoid duplicated CLAT data usage accounting.
        final long cookie;
        try {
            cookie = mDeps.tagSocketAsClat(writeSock6.getFileDescriptor());
        } catch (IOException e) {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
            throw new IOException("tag raw socket failed: " + e);
        }

        // Update our packet socket filter to reflect the new 464xlat IP address.
        try {
            mDeps.configurePacketSocket(readSock6.getFileDescriptor(), v6Str, ifIndex);
        } catch (IOException e) {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
            throw new IOException("configure packet socket failed: " + e);
        }

        // [5] Start clatd.
        final int pid;
        try {
            pid = mDeps.startClatd(tunFd.getFileDescriptor(), readSock6.getFileDescriptor(),
                    writeSock6.getFileDescriptor(), iface, pfx96Str, v4Str, v6Str);
        } catch (IOException e) {
            // TODO: probably refactor to handle the exception of #untagSocket if any.
            mDeps.untagSocket(cookie);
            throw new IOException("Error start clatd on " + iface + ": " + e);
        } finally {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
        }

        // [6] Initialize and store clatd tracker object.
        mClatdTracker = new ClatdTracker(iface, ifIndex, tunIface, tunIfIndex, v4, v6, pfx96,
                pid, cookie);

        // [7] Start BPF
        maybeStartBpf(mClatdTracker);

        return v6Str;
    }

    private void maybeStopBpf(final ClatdTracker tracker) {
        if (mIngressMap == null || mEgressMap == null) return;

        final ClatEgress4Key txKey = new ClatEgress4Key(tracker.v4ifIndex, tracker.v4);
        try {
            mEgressMap.deleteEntry(txKey);
        } catch (ErrnoException | IllegalStateException e) {
            Log.e(TAG, "Could not delete entry (" + txKey + "): " + e);
        }

        final ClatIngress6Key rxKey = new ClatIngress6Key(tracker.ifIndex, tracker.pfx96,
                tracker.v6);
        try {
            mIngressMap.deleteEntry(rxKey);
        } catch (ErrnoException | IllegalStateException e) {
            Log.e(TAG, "Could not delete entry (" + rxKey + "): " + e);
        }

        // TODO: dettach program.
    }

    /**
     * Stop clatd
     */
    public void clatStop() throws IOException {
        if (mClatdTracker == null) {
            throw new IOException("Clatd has not started");
        }
        Log.i(TAG, "Stopping clatd pid=" + mClatdTracker.pid + " on " + mClatdTracker.iface);

        maybeStopBpf(mClatdTracker);
        mDeps.stopClatd(mClatdTracker.iface, mClatdTracker.pfx96.getHostAddress(),
                mClatdTracker.v4.getHostAddress(), mClatdTracker.v6.getHostAddress(),
                mClatdTracker.pid);
        mDeps.untagSocket(mClatdTracker.cookie);

        Log.i(TAG, "clatd on " + mClatdTracker.iface + " stopped");
        mClatdTracker = null;
    }

    /**
     * Get clatd tracker. For test only.
     */
    @VisibleForTesting
    @Nullable
    ClatdTracker getClatdTrackerForTesting() {
        return mClatdTracker;
    }

    private static native String native_selectIpv4Address(String v4addr, int prefixlen)
            throws IOException;
    private static native String native_generateIpv6Address(String iface, String v4,
            String prefix64) throws IOException;
    private static native int native_createTunInterface(String tuniface) throws IOException;
    private static native int native_detectMtu(String platSubnet, int platSuffix, int mark)
            throws IOException;
    private static native int native_openPacketSocket() throws IOException;
    private static native int native_openRawSocket6(int mark) throws IOException;
    private static native void native_addAnycastSetsockopt(FileDescriptor sock, String v6,
            int ifindex) throws IOException;
    private static native void native_configurePacketSocket(FileDescriptor sock, String v6,
            int ifindex) throws IOException;
    private static native int native_startClatd(FileDescriptor tunfd, FileDescriptor readsock6,
            FileDescriptor writesock6, String iface, String pfx96, String v4, String v6)
            throws IOException;
    private static native void native_stopClatd(String iface, String pfx96, String v4, String v6,
            int pid) throws IOException;
    private static native long native_tagSocketAsClat(FileDescriptor sock) throws IOException;
    private static native void native_untagSocket(long cookie) throws IOException;
}
