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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkAddress;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * MdnsAdvertiser manages advertising services per {@link com.android.server.NsdService} requests.
 *
 * All methods except the constructor must be called on the looper thread.
 */
public class MdnsAdvertiser {
    private static final String TAG = MdnsAdvertiser.class.getSimpleName();
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final Looper mLooper;
    private final AdvertiserCallback mCb;

    // Max-sized buffers to be used as temporary buffer to read/build packets. May be used by
    // multiple components, but only for self-contained operations in the looper thread, so not
    // concurrently.
    // TODO: set according to MTU. 1300 should fit for ethernet MTU 1500 with some overhead.
    private final byte[] mPacketCreationBuffer = new byte[1300];

    private final MdnsSocketProvider mSocketProvider;
    private final ArrayMap<Network, InterfaceAdvertiserRequest> mAdvertiserRequests =
            new ArrayMap<>();
    private final ArrayMap<MdnsInterfaceSocket, MdnsInterfaceAdvertiser> mAllAdvertisers =
            new ArrayMap<>();
    private final SparseArray<Registration> mRegistrations = new SparseArray<>();
    private final Dependencies mDeps;

    /**
     * Dependencies for {@link MdnsAdvertiser}, useful for testing.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * @see MdnsInterfaceAdvertiser
         */
        public MdnsInterfaceAdvertiser makeAdvertiser(@NonNull MdnsInterfaceSocket socket,
                @NonNull List<LinkAddress> initialAddresses,
                @NonNull Looper looper, @NonNull byte[] packetCreationBuffer,
                @NonNull MdnsInterfaceAdvertiser.Callback cb) {
            // Note NetworkInterface is final and not mockable
            final String logTag = socket.getInterface().getName();
            return new MdnsInterfaceAdvertiser(logTag, socket, initialAddresses, looper,
                    packetCreationBuffer, cb);
        }
    }

    private final MdnsInterfaceAdvertiser.Callback mInterfaceAdvertiserCb =
            new MdnsInterfaceAdvertiser.Callback() {
        @Override
        public void onRegisterServiceSucceeded(
                @NonNull MdnsInterfaceAdvertiser advertiser, int serviceId) {
            // Wait for all current interfaces to be done probing before notifying of success.
            if (anyAdvertiser(a -> a.isProbing(serviceId))) return;
            // The service may still be unregistered/renamed if a conflict is found on a later added
            // interface, or if a conflicting announcement/reply is detected (RFC6762 9.)

            final Registration registration = mRegistrations.get(serviceId);
            if (registration == null) {
                Log.wtf(TAG, "Register succeeded for unknown registration");
                return;
            }
            if (!registration.mNotifiedRegistrationSuccess) {
                mCb.onRegisterServiceSucceeded(serviceId, registration.getServiceInfo());
                registration.mNotifiedRegistrationSuccess = true;
            }
        }

        @Override
        public void onServiceConflict(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId) {
            // TODO: handle conflicts found after registration (during or after probing)
        }

        @Override
        public void onDestroyed(@NonNull MdnsInterfaceSocket socket) {
            for (int i = mAdvertiserRequests.size() - 1; i >= 0; i--) {
                if (mAdvertiserRequests.valueAt(i).onAdvertiserDestroyed(socket)) {
                    mAdvertiserRequests.removeAt(i);
                }
            }
            mAllAdvertisers.remove(socket);
        }
    };

    /**
     * A request for a {@link MdnsInterfaceAdvertiser}.
     *
     * This class tracks services to be advertised on all sockets provided via a registered
     * {@link MdnsSocketProvider.SocketCallback}.
     */
    private class InterfaceAdvertiserRequest implements MdnsSocketProvider.SocketCallback {
        /** Registrations to add to newer MdnsInterfaceAdvertisers when sockets are created. */
        @NonNull
        private final SparseArray<Registration> mPendingRegistrations = new SparseArray<>();
        @NonNull
        private final ArrayMap<MdnsInterfaceSocket, MdnsInterfaceAdvertiser> mAdvertisers =
                new ArrayMap<>();

        InterfaceAdvertiserRequest(@Nullable Network requestedNetwork) {
            mSocketProvider.requestSocket(requestedNetwork, this);
        }

        /**
         * Called when an advertiser was destroyed, after all services were unregistered and it sent
         * exit announcements, or the interface is gone.
         *
         * @return true if this {@link InterfaceAdvertiserRequest} should now be deleted.
         */
        boolean onAdvertiserDestroyed(@NonNull MdnsInterfaceSocket socket) {
            mAdvertisers.remove(socket);
            if (mAdvertisers.size() == 0 && mPendingRegistrations.size() == 0) {
                // No advertiser is using sockets from this request anymore (in particular for exit
                // announcements), and there is no registration so newer sockets will not be
                // necessary, so the request can be unregistered.
                mSocketProvider.unrequestSocket(this);
                return true;
            }
            return false;
        }

        /**
         * Get the ID of a conflicting service, or -1 if none.
         */
        int getConflictingService(@NonNull NsdServiceInfo info) {
            for (int i = 0; i < mPendingRegistrations.size(); i++) {
                final NsdServiceInfo other = mPendingRegistrations.valueAt(i).getServiceInfo();
                if (info.getServiceName().equals(other.getServiceName())
                        && info.getServiceType().equals(other.getServiceType())) {
                    return mPendingRegistrations.keyAt(i);
                }
            }
            return -1;
        }

        void addService(int id, Registration registration)
                throws NameConflictException {
            final int conflicting = getConflictingService(registration.getServiceInfo());
            if (conflicting >= 0) {
                throw new NameConflictException(conflicting);
            }

            mPendingRegistrations.put(id, registration);
            for (int i = 0; i < mAdvertisers.size(); i++) {
                mAdvertisers.valueAt(i).addService(id, registration.getServiceInfo());
            }
        }

        void removeService(int id) {
            mPendingRegistrations.remove(id);
            for (int i = 0; i < mAdvertisers.size(); i++) {
                mAdvertisers.valueAt(i).removeService(id);
            }
        }

        @Override
        public void onSocketCreated(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket,
                @NonNull List<LinkAddress> addresses) {
            MdnsInterfaceAdvertiser advertiser = mAllAdvertisers.get(socket);
            if (advertiser == null) {
                advertiser = mDeps.makeAdvertiser(socket, addresses, mLooper, mPacketCreationBuffer,
                        mInterfaceAdvertiserCb);
                mAllAdvertisers.put(socket, advertiser);
                advertiser.start();
            }
            mAdvertisers.put(socket, advertiser);
            for (int i = 0; i < mPendingRegistrations.size(); i++) {
                try {
                    advertiser.addService(mPendingRegistrations.keyAt(i),
                            mPendingRegistrations.valueAt(i).getServiceInfo());
                } catch (NameConflictException e) {
                    Log.wtf(TAG, "Name conflict adding services that should have unique names", e);
                }
            }
        }

        @Override
        public void onInterfaceDestroyed(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket) {
            final MdnsInterfaceAdvertiser advertiser = mAdvertisers.get(socket);
            if (advertiser != null) advertiser.destroyNow();
        }

        @Override
        public void onAddressesChanged(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {
            final MdnsInterfaceAdvertiser advertiser = mAdvertisers.get(socket);
            if (advertiser != null) advertiser.updateAddresses(addresses);
        }
    }

    private static class Registration {
        @NonNull
        final String mOriginalName;
        boolean mNotifiedRegistrationSuccess;
        private int mConflictCount;
        @NonNull
        private NsdServiceInfo mServiceInfo;

        private Registration(@NonNull NsdServiceInfo serviceInfo) {
            this.mOriginalName = serviceInfo.getServiceName();
            this.mServiceInfo = serviceInfo;
        }

        /**
         * Update the registration to use a different service name, after a conflict was found.
         *
         * If a name conflict was found during probing or because different advertising requests
         * used the same name, the registration is attempted again with a new name (here using
         * a number suffix, (1), (2) etc). Registration success is notified once probing succeeds
         * with a new name. This matches legacy behavior based on mdnsresponder, and appendix D of
         * RFC6763.
         * @return The new service info with the updated name.
         */
        @NonNull
        private NsdServiceInfo updateForConflict() {
            mConflictCount++;
            // In case of conflict choose a different service name. After the first conflict use
            // "Name (2)", then "Name (3)" etc.
            // TODO: use a hidden method in NsdServiceInfo once MdnsAdvertiser is moved to service-t
            final NsdServiceInfo newInfo = new NsdServiceInfo();
            newInfo.setServiceName(mOriginalName + " (" + (mConflictCount + 1) + ")");
            newInfo.setServiceType(mServiceInfo.getServiceType());
            for (Map.Entry<String, byte[]> attr : mServiceInfo.getAttributes().entrySet()) {
                newInfo.setAttribute(attr.getKey(), attr.getValue());
            }
            newInfo.setHost(mServiceInfo.getHost());
            newInfo.setPort(mServiceInfo.getPort());
            newInfo.setNetwork(mServiceInfo.getNetwork());
            // interfaceIndex is not set when registering

            mServiceInfo = newInfo;
            return mServiceInfo;
        }

        @NonNull
        public NsdServiceInfo getServiceInfo() {
            return mServiceInfo;
        }
    }

    /**
     * Callbacks for advertising services.
     *
     * Every method is called on the MdnsAdvertiser looper thread.
     */
    public interface AdvertiserCallback {
        /**
         * Called when a service was successfully registered, after probing.
         *
         * @param serviceId ID of the service provided when registering.
         * @param registeredInfo Registered info, which may be different from the requested info,
         *                       after probing and possibly choosing alternative service names.
         */
        void onRegisterServiceSucceeded(int serviceId, NsdServiceInfo registeredInfo);

        /**
         * Called when service registration failed.
         *
         * @param serviceId ID of the service provided when registering.
         * @param errorCode One of {@code NsdManager.FAILURE_*}
         */
        void onRegisterServiceFailed(int serviceId, int errorCode);

        // Unregistration is notified immediately as success in NsdService so no callback is needed
        // here.
    }

    public MdnsAdvertiser(@NonNull Looper looper, @NonNull MdnsSocketProvider socketProvider,
            @NonNull AdvertiserCallback cb) {
        this(looper, socketProvider, cb, new Dependencies());
    }

    @VisibleForTesting
    MdnsAdvertiser(@NonNull Looper looper, @NonNull MdnsSocketProvider socketProvider,
            @NonNull AdvertiserCallback cb, @NonNull Dependencies deps) {
        mLooper = looper;
        mCb = cb;
        mSocketProvider = socketProvider;
        mDeps = deps;
    }

    private void checkThread() {
        if (Thread.currentThread() != mLooper.getThread()) {
            throw new IllegalStateException("This must be called on the looper thread");
        }
    }

    /**
     * Add a service to advertise.
     * @param id A unique ID for the service.
     * @param service The service info to advertise.
     */
    public void addService(int id, NsdServiceInfo service) {
        checkThread();
        if (mRegistrations.get(id) != null) {
            Log.e(TAG, "Adding duplicate registration for " + service);
            // TODO (b/264986328): add a more specific error code
            mCb.onRegisterServiceFailed(id, NsdManager.FAILURE_INTERNAL_ERROR);
            return;
        }

        if (DBG) {
            Log.i(TAG, "Adding service " + service + " with ID " + id);
        }

        try {
            final Registration registration = new Registration(service);
            while (!tryAddRegistration(id, registration)) {
                registration.updateForConflict();
            }

            mRegistrations.put(id, registration);
        } catch (IOException e) {
            Log.e(TAG, "Error adding service " + service, e);
            removeService(id);
            // TODO (b/264986328): add a more specific error code
            mCb.onRegisterServiceFailed(id, NsdManager.FAILURE_INTERNAL_ERROR);
        }
    }

    private boolean tryAddRegistration(int id, @NonNull Registration registration)
            throws IOException {
        final NsdServiceInfo serviceInfo = registration.getServiceInfo();
        final Network network = serviceInfo.getNetwork();
        try {
            InterfaceAdvertiserRequest advertiser = mAdvertiserRequests.get(network);
            if (advertiser == null) {
                advertiser = new InterfaceAdvertiserRequest(network);
                mAdvertiserRequests.put(network, advertiser);
            }
            advertiser.addService(id, registration);
        } catch (NameConflictException e) {
            if (DBG) {
                Log.i(TAG, "Service name conflicts: " + serviceInfo.getServiceName());
            }
            removeService(id);
            return false;
        }

        // When adding a service to a specific network, check that it does not conflict with other
        // registrations advertising on all networks
        final InterfaceAdvertiserRequest allNetworksAdvertiser = mAdvertiserRequests.get(null);
        if (network != null && allNetworksAdvertiser != null
                && allNetworksAdvertiser.getConflictingService(serviceInfo) >= 0) {
            if (DBG) {
                Log.i(TAG, "Service conflicts with advertisement on all networks: "
                        + serviceInfo.getServiceName());
            }
            removeService(id);
            return false;
        }

        mRegistrations.put(id, registration);
        return true;
    }

    /**
     * Remove a previously added service.
     * @param id ID used when registering.
     */
    public void removeService(int id) {
        checkThread();
        if (!mRegistrations.contains(id)) return;
        if (DBG) {
            Log.i(TAG, "Removing service with ID " + id);
        }
        for (int i = mAdvertiserRequests.size() - 1; i >= 0; i--) {
            final InterfaceAdvertiserRequest advertiser = mAdvertiserRequests.valueAt(i);
            advertiser.removeService(id);
        }
        mRegistrations.remove(id);
    }

    private boolean anyAdvertiser(@NonNull Predicate<MdnsInterfaceAdvertiser> predicate) {
        for (int i = 0; i < mAllAdvertisers.size(); i++) {
            if (predicate.test(mAllAdvertisers.valueAt(i))) {
                return true;
            }
        }
        return false;
    }
}
