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

package com.android.net.module.util;

import android.annotation.NonNull;
import android.content.Context;
import android.net.INetd;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfoParcel;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Class to coordinate routing across multiple clients.
 *
 * At present this is just a wrapper for netd methods, but it will be used to host some more
 * coordination logic in the near future. It can be used to pull up some of the routing logic
 * from netd into Java land.
 *
 * Note that usage of this class is not thread-safe. Clients are responsible for their own
 * synchronization.
 */
public class RoutingCoordinatorService extends IRoutingCoordinator.Stub {
    private static final String TAG = RoutingCoordinatorService.class.getSimpleName();
    private final INetd mNetd;

    private final Object mPrivateAddressCoordinatorLock = new Object();
    @GuardedBy("mPrivateAddressCoordinatorLock")
    private final PrivateAddressCoordinator mPrivateAddressCoordinator;

    // TODO: Remove bluetoothTetheringUseRandomAddress argument once the beta flag is rolled out
    public RoutingCoordinatorService(@NonNull INetd netd,
            @NonNull Supplier<Network[]> getAllNetworksSupplier,
            @NonNull Context context,
            boolean bluetoothTetheringUseRandomAddress) {
        this(netd, getAllNetworksSupplier, new PrivateAddressCoordinator.Dependencies(context),
                bluetoothTetheringUseRandomAddress);
    }

    @VisibleForTesting
    public RoutingCoordinatorService(@NonNull INetd netd,
            @NonNull Supplier<Network[]> getAllNetworksSupplier,
            @NonNull PrivateAddressCoordinator.Dependencies pacDeps,
            boolean bluetoothTetheringUseRandomAddress) {
        mNetd = netd;
        mPrivateAddressCoordinator = new PrivateAddressCoordinator(
                getAllNetworksSupplier, pacDeps, bluetoothTetheringUseRandomAddress);
    }

    /**
     * Add a route for specific network
     *
     * @param netId the network to add the route to
     * @param route the route to add
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    @Override
    public void addRouteParcel(int netId, RouteInfoParcel route)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkAddRouteParcel(netId, route);
    }

    /**
     * Remove a route for specific network
     *
     * @param netId the network to remove the route from
     * @param route the route to remove
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    @Override
    public void removeRouteParcel(int netId, RouteInfoParcel route)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkRemoveRouteParcel(netId, route);
    }

    /**
     * Update a route for specific network
     *
     * @param netId the network to update the route for
     * @param route parcelable with route information
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    @Override
    public void updateRouteParcel(int netId, RouteInfoParcel route)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkUpdateRouteParcel(netId, route);
    }

    /**
     * Adds an interface to a network. The interface must not be assigned to any network, including
     * the specified network.
     *
     * @param netId the network to add the interface to.
     * @param iface the name of the interface to add.
     *
     * @throws ServiceSpecificException in case of failure, with an error code corresponding to the
     *         unix errno.
     */
    @Override
    public void addInterfaceToNetwork(final int netId, final String iface)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkAddInterface(netId, iface);
    }

    /**
     * Removes an interface from a network. The interface must be assigned to the specified network.
     *
     * @param netId the network to remove the interface from.
     * @param iface the name of the interface to remove.
     *
     * @throws ServiceSpecificException in case of failure, with an error code corresponding to the
     *         unix errno.
     */
    @Override
    public void removeInterfaceFromNetwork(final int netId, final String iface)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkRemoveInterface(netId, iface);
    }

    private final Object mIfacesLock = new Object();
    private static final class ForwardingPair {
        @NonNull public final String fromIface;
        @NonNull public final String toIface;
        ForwardingPair(@NonNull final String fromIface, @NonNull final String toIface) {
            this.fromIface = fromIface;
            this.toIface = toIface;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof ForwardingPair)) return false;

            final ForwardingPair that = (ForwardingPair) o;

            return fromIface.equals(that.fromIface) && toIface.equals(that.toIface);
        }

        @Override
        public int hashCode() {
            int result = fromIface.hashCode();
            result = 2 * result + toIface.hashCode();
            return result;
        }
    }

    @GuardedBy("mIfacesLock")
    private final ArraySet<ForwardingPair> mForwardedInterfaces = new ArraySet<>();

    /**
     * Add forwarding ip rule
     *
     * @param fromIface interface name to add forwarding ip rule
     * @param toIface interface name to add forwarding ip rule
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    public void addInterfaceForward(final String fromIface, final String toIface)
            throws ServiceSpecificException, RemoteException {
        Objects.requireNonNull(fromIface);
        Objects.requireNonNull(toIface);
        Log.i(TAG, "Adding interface forward " + fromIface + " → " + toIface);
        synchronized (mIfacesLock) {
            if (mForwardedInterfaces.size() == 0) {
                mNetd.ipfwdEnableForwarding("RoutingCoordinator");
            }
            final ForwardingPair fwp = new ForwardingPair(fromIface, toIface);
            if (mForwardedInterfaces.contains(fwp)) {
                // TODO: remove if no reports are observed from the below log
                Log.wtf(TAG, "Forward already exists between ifaces "
                        + fromIface + " → " + toIface);
            }
            mForwardedInterfaces.add(fwp);
            // Enables NAT for v4 and filters packets from unknown interfaces
            mNetd.tetherAddForward(fromIface, toIface);
            mNetd.ipfwdAddInterfaceForward(fromIface, toIface);
        }
    }

    /**
     * Remove forwarding ip rule
     *
     * @param fromIface interface name to remove forwarding ip rule
     * @param toIface interface name to remove forwarding ip rule
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    public void removeInterfaceForward(final String fromIface, final String toIface)
            throws ServiceSpecificException, RemoteException {
        Objects.requireNonNull(fromIface);
        Objects.requireNonNull(toIface);
        Log.i(TAG, "Removing interface forward " + fromIface + " → " + toIface);
        synchronized (mIfacesLock) {
            final ForwardingPair fwp = new ForwardingPair(fromIface, toIface);
            if (!mForwardedInterfaces.contains(fwp)) {
                // This can happen when an upstream was unregisteredAfterReplacement. The forward
                // is removed immediately when the upstream is destroyed, but later when the
                // network actually disconnects CS does not know that and it asks for removal
                // again.
                // This can also happen if the network was destroyed before being set as an
                // upstream, because then CS does not set up the forward rules seeing how the
                // interface was removed anyway.
                // Either way, this is benign.
                Log.i(TAG, "No forward set up between interfaces " + fromIface + " → " + toIface);
                return;
            }
            mForwardedInterfaces.remove(fwp);
            try {
                mNetd.ipfwdRemoveInterfaceForward(fromIface, toIface);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Exception in ipfwdRemoveInterfaceForward", e);
            }
            try {
                mNetd.tetherRemoveForward(fromIface, toIface);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Exception in tetherRemoveForward", e);
            }
            if (mForwardedInterfaces.size() == 0) {
                mNetd.ipfwdDisableForwarding("RoutingCoordinator");
            }
        }
    }

    // PrivateAddressCoordinator methods:

    /** Update the prefix of an upstream. */
    @Override
    public void updateUpstreamPrefix(LinkProperties lp, NetworkCapabilities nc, Network network) {
        BinderUtils.withCleanCallingIdentity(
                () -> {
                    synchronized (mPrivateAddressCoordinatorLock) {
                        mPrivateAddressCoordinator.updateUpstreamPrefix(lp, nc, network);
                    }
                });
    }

    /** Remove the upstream prefix of the given {@link Network}. */
    @Override
    public void removeUpstreamPrefix(Network network) {
        Objects.requireNonNull(network);
        BinderUtils.withCleanCallingIdentity(
                () -> {
                    synchronized (mPrivateAddressCoordinatorLock) {
                        mPrivateAddressCoordinator.removeUpstreamPrefix(network);
                    }
                });
    }

    /** Remove the deprecated upstream networks if any. */
    @Override
    public void maybeRemoveDeprecatedUpstreams() {
        BinderUtils.withCleanCallingIdentity(
                () -> {
                    synchronized (mPrivateAddressCoordinatorLock) {
                        mPrivateAddressCoordinator.maybeRemoveDeprecatedUpstreams();
                    }
                });
    }

    /**
     * Request an IPv4 address for the downstream. Return the last time used address for the
     * provided (interfaceType, scope) pair if possible.
     *
     * @param interfaceType the Tethering type (see TetheringManager#TETHERING_*).
     * @param scope CONNECTIVITY_SCOPE_GLOBAL or CONNECTIVITY_SCOPE_LOCAL
     * @param request a {@link IIpv4PrefixRequest} to report conflicts
     * @return an IPv4 address allocated for the downstream, could be null
     */
    @Override
    public LinkAddress requestStickyDownstreamAddress(int interfaceType, int scope,
            IIpv4PrefixRequest request) {
        Objects.requireNonNull(request);
        return BinderUtils.withCleanCallingIdentity(
                () -> {
                    synchronized (mPrivateAddressCoordinatorLock) {
                        return mPrivateAddressCoordinator.requestStickyDownstreamAddress(
                                interfaceType, scope, request);
                    }
                });
    }

    /**
     * Request an IPv4 address for the downstream.
     *
     * @param request a {@link IIpv4PrefixRequest} to report conflicts
     * @return an IPv4 address allocated for the downstream, could be null
     */
    @Override
    public LinkAddress requestDownstreamAddress(IIpv4PrefixRequest request) {
        Objects.requireNonNull(request);
        return BinderUtils.withCleanCallingIdentity(
                () -> {
                    synchronized (mPrivateAddressCoordinatorLock) {
                        return mPrivateAddressCoordinator.requestDownstreamAddress(request);
                    }
                });
    }

    /** Release the IPv4 address allocated for the downstream. */
    @Override
    public void releaseDownstream(IIpv4PrefixRequest request) {
        Objects.requireNonNull(request);
        BinderUtils.withCleanCallingIdentity(
                () -> {
                    synchronized (mPrivateAddressCoordinatorLock) {
                        mPrivateAddressCoordinator.releaseDownstream(request);
                    }
                });
    }
}
