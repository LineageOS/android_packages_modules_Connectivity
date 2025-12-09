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

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.RouteInfoParcel;

import com.android.net.module.util.IIpv4PrefixRequest;

/** @hide */
// TODO: b/350630377 - This @Descriptor annotation workaround is to prevent the DESCRIPTOR from
// being jarjared which changes the DESCRIPTOR and casues "java.lang.SecurityException: Binder
// invocation to an incorrect interface" when calling the IPC.
@Descriptor("value=no.jarjar.com.android.net.module.util.IRoutingCoordinator")
interface IRoutingCoordinator {

   /**
    * Add a route for specific network
    *
    * @param netId the network to add the route to
    * @param route the route to add
    * @throws ServiceSpecificException in case of failure, with an error code indicating the
    *         cause of the failure.
    */
    void addRouteParcel(int netId, in RouteInfoParcel route);

   /**
    * Remove a route for specific network
    *
    * @param netId the network to remove the route from
    * @param route the route to remove
    * @throws ServiceSpecificException in case of failure, with an error code indicating the
    *         cause of the failure.
    */
    void removeRouteParcel(int netId, in RouteInfoParcel route);

    /**
    * Update a route for specific network
    *
    * @param netId the network to update the route for
    * @param route parcelable with route information
    * @throws ServiceSpecificException in case of failure, with an error code indicating the
    *         cause of the failure.
    */
    void updateRouteParcel(int netId, in RouteInfoParcel route);

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
    void addInterfaceToNetwork(int netId, in String iface);

    /**
     * Removes an interface from a network. The interface must be assigned to the specified network.
     *
     * @param netId the network to remove the interface from.
     * @param iface the name of the interface to remove.
     *
     * @throws ServiceSpecificException in case of failure, with an error code corresponding to the
     *         unix errno.
     */
     void removeInterfaceFromNetwork(int netId, in String iface);

   /**
    * Add forwarding ip rule
    *
    * @param fromIface interface name to add forwarding ip rule
    * @param toIface interface name to add forwarding ip rule
    * @throws ServiceSpecificException in case of failure, with an error code indicating the
    *         cause of the failure.
    */
    void addInterfaceForward(in String fromIface, in String toIface);

   /**
    * Remove forwarding ip rule
    *
    * @param fromIface interface name to remove forwarding ip rule
    * @param toIface interface name to remove forwarding ip rule
    * @throws ServiceSpecificException in case of failure, with an error code indicating the
    *         cause of the failure.
    */
    void removeInterfaceForward(in String fromIface, in String toIface);

    /** Update the prefix of an upstream. */
    void updateUpstreamPrefix(in @nullable LinkProperties lp,
                              in @nullable NetworkCapabilities nc,
                              in Network network);

    /** Remove the upstream prefix of the given {@link Network}. */
    void removeUpstreamPrefix(in Network network);

    /** Remove the deprecated upstream networks if any. */
    void maybeRemoveDeprecatedUpstreams();

   /**
    * Request an IPv4 address for the downstream. Return the last time used address for the
    * provided (interfaceType, scope) pair if possible.
    *
    * @param interfaceType the Tethering type (see TetheringManager#TETHERING_*).
    * @param scope CONNECTIVITY_SCOPE_GLOBAL or CONNECTIVITY_SCOPE_LOCAL
    * @param request a {@link IIpv4PrefixRequest} to report conflicts
    * @return an IPv4 address allocated for the downstream, could be null
    */
    @nullable
    LinkAddress requestStickyDownstreamAddress(
            in int interfaceType,
            in int scope,
            in IIpv4PrefixRequest request);
   /**
    * Request an IPv4 address for the downstream.
    *
    * @param request a {@link IIpv4PrefixRequest} to report conflicts
    * @return an IPv4 address allocated for the downstream, could be null
    */
    @nullable
    LinkAddress requestDownstreamAddress(in IIpv4PrefixRequest request);

    /** Release the IPv4 address allocated for the downstream. */
    void releaseDownstream(in IIpv4PrefixRequest request);
}
