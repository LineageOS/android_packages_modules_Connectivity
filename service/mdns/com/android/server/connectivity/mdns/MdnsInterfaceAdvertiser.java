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
import android.net.LinkAddress;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;

import java.util.List;

/**
 * A class that handles advertising services on a {@link MdnsInterfaceSocket} tied to an interface.
 */
public class MdnsInterfaceAdvertiser {
    private static final boolean DBG = MdnsAdvertiser.DBG;
    @NonNull
    private final String mTag;
    @NonNull
    private final ProbingCallback mProbingCallback = new ProbingCallback();
    @NonNull
    private final AnnouncingCallback mAnnouncingCallback = new AnnouncingCallback();
    @NonNull
    private final Callback mCb;
    @NonNull
    private final MdnsInterfaceSocket mSocket;
    @NonNull
    private final MdnsAnnouncer mAnnouncer;
    @NonNull
    private final MdnsProber mProber;
    @NonNull
    private final MdnsReplySender mReplySender;

    /**
     * Callbacks called by {@link MdnsInterfaceAdvertiser} to report status updates.
     */
    interface Callback {
        /**
         * Called by the advertiser after it successfully registered a service, after probing.
         */
        void onRegisterServiceSucceeded(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId);

        /**
         * Called by the advertiser when a conflict was found, during or after probing.
         *
         * If a conflict is found during probing, the {@link #renameServiceForConflict} must be
         * called to restart probing and attempt registration with a different name.
         */
        void onServiceConflict(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId);

        /**
         * Called by the advertiser when it destroyed itself.
         *
         * This can happen after a call to {@link #destroyNow()}, or after all services were
         * unregistered and the advertiser finished sending exit announcements.
         */
        void onDestroyed(@NonNull MdnsInterfaceSocket socket);
    }

    /**
     * Callbacks from {@link MdnsProber}.
     */
    private class ProbingCallback implements
            MdnsPacketRepeater.PacketRepeaterCallback<MdnsProber.ProbingInfo> {
        @Override
        public void onFinished(MdnsProber.ProbingInfo info) {
            // TODO: probing finished, start announcements
        }
    }

    /**
     * Callbacks from {@link MdnsAnnouncer}.
     */
    private class AnnouncingCallback
            implements MdnsPacketRepeater.PacketRepeaterCallback<MdnsAnnouncer.AnnouncementInfo> {
        // TODO: implement
    }

    public MdnsInterfaceAdvertiser(@NonNull String logTag,
            @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> initialAddresses,
            @NonNull Looper looper, @NonNull byte[] packetCreationBuffer, @NonNull Callback cb) {
        mTag = MdnsInterfaceAdvertiser.class.getSimpleName() + "/" + logTag;
        mSocket = socket;
        mCb = cb;
        mReplySender = new MdnsReplySender(looper, socket, packetCreationBuffer);
        mAnnouncer = new MdnsAnnouncer(logTag, looper, mReplySender,
                mAnnouncingCallback);
        mProber = new MdnsProber(logTag, looper, mReplySender, mProbingCallback);
    }

    /**
     * Start the advertiser.
     *
     * The advertiser will stop itself when all services are removed and exit announcements sent,
     * notifying via {@link Callback#onDestroyed}. This can also be triggered manually via
     * {@link #destroyNow()}.
     */
    public void start() {
        // TODO: implement
    }

    /**
     * Start advertising a service.
     *
     * @throws NameConflictException There is already a service being advertised with that name.
     */
    public void addService(int id, NsdServiceInfo service) throws NameConflictException {
        // TODO: implement
    }

    /**
     * Stop advertising a service.
     *
     * This will trigger exit announcements for the service.
     */
    public void removeService(int id) {
        // TODO: implement
    }

    /**
     * Update interface addresses used to advertise.
     *
     * This causes new address records to be announced.
     */
    public void updateAddresses(@NonNull List<LinkAddress> newAddresses) {
        // TODO: implement
    }

    /**
     * Destroy the advertiser immediately, not sending any exit announcement.
     *
     * <p>Useful when the underlying network went away. This will trigger an onDestroyed callback.
     */
    public void destroyNow() {
        // TODO: implement
    }

    /**
     * Reset a service to the probing state due to a conflict found on the network.
     */
    public void restartProbingForConflict(int serviceId) {
        // TODO: implement
    }

    /**
     * Rename a service following a conflict found on the network, and restart probing.
     */
    public void renameServiceForConflict(int serviceId, NsdServiceInfo newInfo) {
        // TODO: implement
    }

    /**
     * Indicates whether probing is in progress for the given service on this interface.
     *
     * Also returns false if the specified service is not registered.
     */
    public boolean isProbing(int serviceId) {
        // TODO: implement
        return true;
    }
}
