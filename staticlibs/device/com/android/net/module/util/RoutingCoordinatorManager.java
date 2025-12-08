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

import android.content.Context;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A manager class for talking to the routing coordinator service.
 *
 * This class should only be used by the connectivity and tethering module. This is enforced
 * by the build rules. Do not change build rules to gain access to this class from elsewhere.
 *
 * This class has following functionalities:
 * - Manage routes and forwarding for networks.
 * - Manage IPv4 prefix allocation for network interfaces.
 *
 * @hide
 */
public class RoutingCoordinatorManager {
    @NonNull final Context mContext;
    @NonNull final IRoutingCoordinator mService;

    public RoutingCoordinatorManager(@NonNull final Context context,
            @NonNull final IBinder binder) {
        mContext = context;
        mService = IRoutingCoordinator.Stub.asInterface(binder);
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
    public void addInterfaceToNetwork(final int netId, final String iface) {
        try {
            mService.addInterfaceToNetwork(netId, iface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
    public void removeInterfaceFromNetwork(final int netId, final String iface) {
        try {
            mService.removeInterfaceFromNetwork(netId, iface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add forwarding ip rule
     *
     * @param fromIface interface name to add forwarding ip rule
     * @param toIface interface name to add forwarding ip rule
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    public void addInterfaceForward(final String fromIface, final String toIface) {
        try {
            mService.addInterfaceForward(fromIface, toIface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
    public void removeInterfaceForward(final String fromIface, final String toIface) {
        try {
            mService.removeInterfaceForward(fromIface, toIface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // PrivateAddressCoordinator methods:

    /** Update the prefix of an upstream. */
    public void updateUpstreamPrefix(LinkProperties lp, NetworkCapabilities nc, Network network) {
        try {
            mService.updateUpstreamPrefix(lp, nc, network);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Remove the upstream prefix of the given {@link Network}. */
    public void removeUpstreamPrefix(Network network) {
        try {
            mService.removeUpstreamPrefix(network);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Remove the deprecated upstream networks if any. */
    public void maybeRemoveDeprecatedUpstreams() {
        try {
            mService.maybeRemoveDeprecatedUpstreams();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
    @Nullable
    public LinkAddress requestStickyDownstreamAddress(
            int interfaceType,
            int scope,
            IIpv4PrefixRequest request) {
        try {
            return mService.requestStickyDownstreamAddress(interfaceType, scope, request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request an IPv4 address for the downstream.
     *
     * @param request a {@link IIpv4PrefixRequest} to report conflicts
     * @return an IPv4 address allocated for the downstream, could be null
     */
    public LinkAddress requestDownstreamAddress(IIpv4PrefixRequest request) {
        try {
            return mService.requestDownstreamAddress(request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Release the IPv4 address allocated for the downstream. */
    public void releaseDownstream(IIpv4PrefixRequest request) {
        try {
            mService.releaseDownstream(request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
