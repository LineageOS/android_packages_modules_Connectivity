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

import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;

import static com.android.net.module.util.Inet4AddressUtils.inet4AddressToIntHTH;
import static com.android.net.module.util.Inet4AddressUtils.intToInet4AddressHTH;
import static com.android.net.module.util.Inet4AddressUtils.prefixLengthToV4NetmaskIntHTH;

import static java.util.Arrays.asList;

import android.content.Context;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.util.ArrayMap;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * This class coordinate IP addresses conflict problem.
 *
 * Tethering downstream IP addresses may conflict with network assigned addresses. This
 * coordinator is responsible for recording all of network assigned addresses and dispatched
 * free address to downstream interfaces.
 *
 * This class is not thread-safe.
 * @hide
 */
public class PrivateAddressCoordinator {
    // WARNING: Keep in sync with chooseDownstreamAddress
    public static final int PREFIX_LENGTH = 24;

    public static final String TETHER_FORCE_RANDOM_PREFIX_BASE_SELECTION =
            "tether_force_random_prefix_base_selection";

    // Upstream monitor would be stopped when tethering is down. When tethering restart, downstream
    // address may be requested before coordinator get current upstream notification. To ensure
    // coordinator do not select conflict downstream prefix, mUpstreamPrefixMap would not be cleared
    // when tethering is down. Instead tethering would remove all deprecated upstreams from
    // mUpstreamPrefixMap when tethering is starting. See #maybeRemoveDeprecatedUpstreams().
    private final ArrayMap<Network, List<IpPrefix>> mUpstreamPrefixMap;
    // The downstreams are indexed by Ipv4PrefixRequest, which is a wrapper of the Binder object of
    // IIpv4PrefixRequest.
    private final ArrayMap<Ipv4PrefixRequest, LinkAddress> mDownstreams;
    private static final String LEGACY_WIFI_P2P_IFACE_ADDRESS = "192.168.49.1/24";
    private static final String LEGACY_BLUETOOTH_IFACE_ADDRESS = "192.168.44.1/24";
    private final List<IpPrefix> mTetheringPrefixes;
    // A supplier that returns ConnectivityManager#getAllNetworks.
    private final Supplier<Network[]> mGetAllNetworksSupplier;
    private final Dependencies mDeps;
    // keyed by downstream type(TetheringManager.TETHERING_*).
    private final ArrayMap<AddressKey, LinkAddress> mCachedAddresses;
    private final Random mRandom;

    /** Capture PrivateAddressCoordinator dependencies for injection. */
    public static class Dependencies {
        private final Context mContext;

        Dependencies(Context context) {
            mContext = context;
        }

        /**
         * Check whether one specific experimental feature in Tethering module
         * from {@link DeviceConfig} is not disabled.
         *
         * @param featureName The feature's name to look up.
         * @return true if this feature is enabled, or false if disabled.
         */
        public boolean isFeatureNotChickenedOut(String featureName) {
            return DeviceConfigUtils.isTetheringFeatureNotChickenedOut(mContext, featureName);
        }
    }

    public PrivateAddressCoordinator(Supplier<Network[]> getAllNetworksSupplier, Context context) {
        this(getAllNetworksSupplier, new Dependencies(context));
    }

    @VisibleForTesting
    public PrivateAddressCoordinator(Supplier<Network[]> getAllNetworksSupplier,
                                     Dependencies deps) {
        mDownstreams = new ArrayMap<>();
        mUpstreamPrefixMap = new ArrayMap<>();
        mGetAllNetworksSupplier = getAllNetworksSupplier;
        mDeps = deps;
        mCachedAddresses = new ArrayMap<AddressKey, LinkAddress>();
        // Reserved static addresses for bluetooth and wifi p2p.
        mCachedAddresses.put(new AddressKey(TETHERING_BLUETOOTH, CONNECTIVITY_SCOPE_GLOBAL),
                new LinkAddress(LEGACY_BLUETOOTH_IFACE_ADDRESS));
        mCachedAddresses.put(new AddressKey(TETHERING_WIFI_P2P, CONNECTIVITY_SCOPE_LOCAL),
                new LinkAddress(LEGACY_WIFI_P2P_IFACE_ADDRESS));

        mTetheringPrefixes = new ArrayList<>(Arrays.asList(new IpPrefix("192.168.0.0/16"),
            new IpPrefix("172.16.0.0/12"), new IpPrefix("10.0.0.0/8")));
        mRandom = new Random();
    }

    /**
     * Record a new upstream IpPrefix which may conflict with tethering downstreams. The downstreams
     * will be notified if a conflict is found. When updateUpstreamPrefix is called,
     * UpstreamNetworkState must have an already populated LinkProperties.
     */
    public void updateUpstreamPrefix(
            final LinkProperties lp, final NetworkCapabilities nc, final Network network) {
        // Do not support VPN as upstream. Normally, networkCapabilities is not expected to be null,
        // but just checking to be sure.
        if (nc != null && nc.hasTransport(TRANSPORT_VPN)) {
            removeUpstreamPrefix(network);
            return;
        }

        final ArrayList<IpPrefix> ipv4Prefixes = getIpv4Prefixes(lp.getAllLinkAddresses());
        if (ipv4Prefixes.isEmpty()) {
            removeUpstreamPrefix(network);
            return;
        }

        mUpstreamPrefixMap.put(network, ipv4Prefixes);
        handleMaybePrefixConflict(ipv4Prefixes);
    }

    private ArrayList<IpPrefix> getIpv4Prefixes(final List<LinkAddress> linkAddresses) {
        final ArrayList<IpPrefix> list = new ArrayList<>();
        for (LinkAddress address : linkAddresses) {
            if (!address.isIpv4()) continue;

            list.add(asIpPrefix(address));
        }

        return list;
    }

    private void handleMaybePrefixConflict(final List<IpPrefix> prefixes) {
        for (Map.Entry<Ipv4PrefixRequest, LinkAddress> entry : mDownstreams.entrySet()) {
            final Ipv4PrefixRequest request = entry.getKey();
            final LinkAddress downstream = entry.getValue();
            final IpPrefix target = asIpPrefix(downstream);

            for (IpPrefix source : prefixes) {
                if (isConflictPrefix(source, target)) {
                    try {
                        request.getRequest().onIpv4PrefixConflict(target);
                    } catch (RemoteException ignored) {
                        // ignore
                    }
                    break;
                }
            }
        }
    }

    /** Remove IpPrefix records corresponding to input network. */
    public void removeUpstreamPrefix(final Network network) {
        mUpstreamPrefixMap.remove(network);
    }

    /**
     * Maybe remove deprecated upstream records, this would be called once tethering started without
     * any exiting tethered downstream.
     */
    public void maybeRemoveDeprecatedUpstreams() {
        if (mUpstreamPrefixMap.isEmpty()) return;

        // Remove all upstreams that are no longer valid networks
        final Set<Network> toBeRemoved = new HashSet<>(mUpstreamPrefixMap.keySet());
        toBeRemoved.removeAll(asList(mGetAllNetworksSupplier.get()));

        mUpstreamPrefixMap.removeAll(toBeRemoved);
    }

    // TODO: There needs to be a reserveDownstreamAddress() method for the cases where
    // TetheringRequest has been set a static IPv4 address.

    /**
     * Request a downstream address for the provided IIpv4PrefixRequest.
     *
     * This method will first try to return the last time used address for the provided
     * (interfaceType, scope) pair if possible. If not, it will pick a random available address and
     * mark its prefix as in use for the provided IIpv4PrefixRequest.
     */
    @Nullable
    public LinkAddress requestStickyDownstreamAddress(int interfaceType, final int scope,
            IIpv4PrefixRequest request) {
        final Ipv4PrefixRequest wrappedRequest = new Ipv4PrefixRequest(request);
        final AddressKey addrKey = new AddressKey(interfaceType, scope);
        // This ensures that tethering isn't started on 2 different interfaces with the same type.
        // Once tethering could support multiple interface with the same type,
        // TetheringSoftApCallback would need to handle it among others.
        final LinkAddress cachedAddress = mCachedAddresses.get(addrKey);
        if (cachedAddress != null && !isConflictWithUpstream(asIpPrefix(cachedAddress))) {
            mDownstreams.put(wrappedRequest, cachedAddress);
            return cachedAddress;
        }

        final LinkAddress newAddress = requestDownstreamAddress(request);
        if (newAddress != null) {
            mCachedAddresses.put(addrKey, newAddress);
        }
        return newAddress;
    }

    /**
     * Pick a random available address and mark its prefix as in use for the provided
     * IIpv4PrefixRequest. Return null if there is no available address.
     */
    @Nullable
    public LinkAddress requestDownstreamAddress(IIpv4PrefixRequest request) {
        final Ipv4PrefixRequest wrappedRequest = new Ipv4PrefixRequest(request);
        final int prefixIndex = getRandomPrefixIndex();
        for (int i = 0; i < mTetheringPrefixes.size(); i++) {
            final IpPrefix prefixRange = mTetheringPrefixes.get(
                    (prefixIndex + i) % mTetheringPrefixes.size());
            final LinkAddress newAddress = chooseDownstreamAddress(prefixRange);
            if (newAddress != null) {
                mDownstreams.put(wrappedRequest, newAddress);
                return newAddress;
            }
        }

        // No available address.
        return null;
    }

    private int getRandomPrefixIndex() {
        if (!mDeps.isFeatureNotChickenedOut(TETHER_FORCE_RANDOM_PREFIX_BASE_SELECTION)) return 0;

        final int random = getRandomInt() & 0xffffff;
        // This is to select the starting prefix range (/8, /12, or /16) instead of the actual
        // LinkAddress. To avoid complex operations in the selection logic and make the selected
        // rate approximate consistency with that /8 is around 2^4 times of /12 and /12 is around
        // 2^4 times of /16, we simply define a map between the value and the prefix value like
        // this:
        //
        // Value 0 ~ 0xffff (65536/16777216 = 0.39%) -> 192.168.0.0/16
        // Value 0x10000 ~ 0xfffff (983040/16777216 = 5.86%) -> 172.16.0.0/12
        // Value 0x100000 ~ 0xffffff (15728640/16777216 = 93.7%) -> 10.0.0.0/8
        if (random > 0xfffff) {
            return 2;
        } else if (random > 0xffff) {
            return 1;
        } else {
            return 0;
        }
    }

    private int getPrefixBaseAddress(final IpPrefix prefix) {
        return inet4AddressToIntHTH((Inet4Address) prefix.getAddress());
    }

    /**
     * Check whether input prefix conflict with upstream prefixes or in-use downstream prefixes.
     * If yes, return one of them.
     */
    private IpPrefix getConflictPrefix(final IpPrefix prefix) {
        final IpPrefix upstream = getConflictWithUpstream(prefix);
        if (upstream != null) return upstream;

        return getInUseDownstreamPrefix(prefix);
    }

    @VisibleForTesting
    public LinkAddress chooseDownstreamAddress(final IpPrefix prefixRange) {
        // The netmask of the prefix assignment block (e.g., 0xfff00000 for 172.16.0.0/12).
        final int prefixRangeMask = prefixLengthToV4NetmaskIntHTH(prefixRange.getPrefixLength());

        // The zero address in the block (e.g., 0xac100000 for 172.16.0.0/12).
        final int baseAddress = getPrefixBaseAddress(prefixRange);

        // Try to get an address within the given prefix that does not conflict with any other
        // prefix in the system.
        for (int i = 0; i < 20; ++i) {
            final int randomSuffix = mRandom.nextInt() & ~prefixRangeMask;
            final int randomAddress = baseAddress | randomSuffix;

            // Avoid selecting x.x.x.[0, 1, 255] addresses.
            switch (randomAddress & 0xFF) {
                case 0:
                case 1:
                case 255:
                    // Try selecting a different address
                    continue;
            }

            // Avoid selecting commonly used subnets.
            switch (randomAddress & 0xFFFFFF00) {
                case 0xC0A80000: // 192.168.0.0/24
                case 0xC0A80100: // 192.168.1.0/24
                case 0xC0A85800: // 192.168.88.0/24
                case 0xC0A86400: // 192.168.100.0/24
                    continue;
            }

            // Avoid 10.0.0.0 - 10.10.255.255
            if (randomAddress >= 0x0A000000 && randomAddress <= 0x0A0AFFFF) {
                continue;
            }

            final InetAddress address = intToInet4AddressHTH(randomAddress);
            final IpPrefix prefix = new IpPrefix(address, PREFIX_LENGTH);
            if (getConflictPrefix(prefix) != null) {
                // Prefix is conflicting with another prefix used in the system, find another one.
                continue;
            }
            return new LinkAddress(address, PREFIX_LENGTH);
        }
        // Could not find a prefix, return null and let caller try another range.
        return null;
    }

    /** Get random int which could be used to generate random address. */
    // TODO: get rid of this function and mock getRandomPrefixIndex in tests.
    @VisibleForTesting
    public int getRandomInt() {
        return mRandom.nextInt();
    }

    /** Release downstream record for IpServer. */
    public void releaseDownstream(IIpv4PrefixRequest request) {
        mDownstreams.remove(new Ipv4PrefixRequest(request));
    }

    /** Clear current upstream prefixes records. */
    public void clearUpstreamPrefixes() {
        mUpstreamPrefixMap.clear();
    }

    private IpPrefix getConflictWithUpstream(final IpPrefix prefix) {
        for (int i = 0; i < mUpstreamPrefixMap.size(); i++) {
            final List<IpPrefix> list = mUpstreamPrefixMap.valueAt(i);
            for (IpPrefix upstream : list) {
                if (isConflictPrefix(prefix, upstream)) return upstream;
            }
        }
        return null;
    }

    private boolean isConflictWithUpstream(final IpPrefix prefix) {
        return getConflictWithUpstream(prefix) != null;
    }

    private boolean isConflictPrefix(final IpPrefix prefix1, final IpPrefix prefix2) {
        if (prefix2.getPrefixLength() < prefix1.getPrefixLength()) {
            return prefix2.contains(prefix1.getAddress());
        }

        return prefix1.contains(prefix2.getAddress());
    }

    // InUse Prefixes are prefixes of mCachedAddresses which are active downstream addresses, last
    // downstream addresses(reserved for next time) and static addresses(e.g. bluetooth, wifi p2p).
    private IpPrefix getInUseDownstreamPrefix(final IpPrefix prefix) {
        for (int i = 0; i < mCachedAddresses.size(); i++) {
            final IpPrefix downstream = asIpPrefix(mCachedAddresses.valueAt(i));
            if (isConflictPrefix(prefix, downstream)) return downstream;
        }

        // IpServer may use manually-defined address (mStaticIpv4ServerAddr) which does not include
        // in mCachedAddresses.
        for (LinkAddress downstream : mDownstreams.values()) {
            final IpPrefix target = asIpPrefix(downstream);

            if (isConflictPrefix(prefix, target)) return target;
        }

        return null;
    }

    private static IpPrefix asIpPrefix(LinkAddress addr) {
        return new IpPrefix(addr.getAddress(), addr.getPrefixLength());
    }

    private static final class Ipv4PrefixRequest {
        private final IIpv4PrefixRequest mRequest;

        Ipv4PrefixRequest(IIpv4PrefixRequest request) {
            mRequest = request;
        }

        public IIpv4PrefixRequest getRequest() {
            return mRequest;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Ipv4PrefixRequest)) return false;
            return Objects.equals(
                    mRequest.asBinder(), ((Ipv4PrefixRequest) obj).mRequest.asBinder());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(mRequest.asBinder());
        }
    }

    private static class AddressKey {
        private final int mTetheringType;
        private final int mScope;

        private AddressKey(int type, int scope) {
            mTetheringType = type;
            mScope = scope;
        }

        @Override
        public int hashCode() {
            return (mTetheringType << 16) + mScope;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof AddressKey)) return false;
            final AddressKey other = (AddressKey) obj;

            return mTetheringType == other.mTetheringType && mScope == other.mScope;
        }

        @Override
        public String toString() {
            return "AddressKey(" + mTetheringType + ", " + mScope + ")";
        }
    }

    // TODO: dump PrivateAddressCoordinator when dumping RoutingCoordinatorService and apply
    // indentation.
    void dump(final PrintWriter pw) {
        pw.println("mTetheringPrefixes:");
        for (IpPrefix prefix : mTetheringPrefixes) {
            pw.println(prefix);
        }

        pw.println("mUpstreamPrefixMap:");
        for (int i = 0; i < mUpstreamPrefixMap.size(); i++) {
            pw.println(mUpstreamPrefixMap.keyAt(i) + " - " + mUpstreamPrefixMap.valueAt(i));
        }

        pw.println("mDownstreams:");
        for (LinkAddress downstream : mDownstreams.values()) {
            pw.println(downstream);
        }

        pw.println("mCachedAddresses:");
        for (int i = 0; i < mCachedAddresses.size(); i++) {
            pw.println(mCachedAddresses.keyAt(i) + " - " + mCachedAddresses.valueAt(i));
        }
    }
}
