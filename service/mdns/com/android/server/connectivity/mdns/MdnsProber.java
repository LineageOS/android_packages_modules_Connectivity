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
import android.os.Looper;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Sends mDns probe requests to verify service records are unique on the network.
 *
 * TODO: implement receiving replies and handling conflicts.
 */
public class MdnsProber extends MdnsPacketRepeater<MdnsProber.ProbingInfo> {
    @NonNull
    private final String mLogTag;

    public MdnsProber(@NonNull String interfaceTag, @NonNull Looper looper,
            @NonNull MdnsReplySender replySender,
            @NonNull PacketRepeaterCallback<ProbingInfo> cb) {
        // 3 packets as per https://datatracker.ietf.org/doc/html/rfc6762#section-8.1
        super(looper, replySender, cb);
        mLogTag = MdnsProber.class.getSimpleName() + "/" + interfaceTag;
    }

    static class ProbingInfo implements Request {

        private final int mServiceId;
        @NonNull
        private final MdnsPacket mPacket;
        @NonNull
        private final Supplier<Iterable<SocketAddress>> mDestinationsSupplier;

        /**
         * Create a new ProbingInfo
         * @param serviceId Service to probe for.
         * @param probeRecords Records to be probed for uniqueness.
         * @param destinationsSupplier Supplier for the probe destinations. Will be called on the
         *                             probe handler thread for each probe.
         */
        ProbingInfo(int serviceId, @NonNull List<MdnsRecord> probeRecords,
                @NonNull Supplier<Iterable<SocketAddress>> destinationsSupplier) {
            mServiceId = serviceId;
            mPacket = makePacket(probeRecords);
            mDestinationsSupplier = destinationsSupplier;
        }

        public int getServiceId() {
            return mServiceId;
        }

        @NonNull
        @Override
        public MdnsPacket getPacket(int index) {
            return mPacket;
        }

        @NonNull
        @Override
        public Iterable<SocketAddress> getDestinations(int index) {
            return mDestinationsSupplier.get();
        }

        @Override
        public long getDelayMs(int nextIndex) {
            // As per https://datatracker.ietf.org/doc/html/rfc6762#section-8.1
            return 250L;
        }

        @Override
        public int getNumSends() {
            // 3 packets as per https://datatracker.ietf.org/doc/html/rfc6762#section-8.1
            return 3;
        }

        private static MdnsPacket makePacket(@NonNull List<MdnsRecord> records) {
            final ArrayList<MdnsRecord> questions = new ArrayList<>(records.size());
            for (final MdnsRecord record : records) {
                if (containsName(questions, record.getName())) {
                    // Already added this name
                    continue;
                }

                // TODO: legacy Android mDNS used to send the first probe (only) as unicast, even
                //  though https://datatracker.ietf.org/doc/html/rfc6762#section-8.1 says they
                // SHOULD all be. rfc6762 15.1 says that if the port is shared with another
                // responder unicast questions should not be used, and the legacy mdnsresponder may
                // be running, so not using unicast at all may be better. Consider using legacy
                // behavior if this causes problems.
                questions.add(new MdnsAnyRecord(record.getName(), false /* unicast */));
            }

            return new MdnsPacket(
                    MdnsConstants.FLAGS_QUERY,
                    questions,
                    Collections.emptyList() /* answers */,
                    records /* authorityRecords */,
                    Collections.emptyList() /* additionalRecords */);
        }

        /**
         * Return whether the specified name is present in the list of records.
         */
        private static boolean containsName(@NonNull List<MdnsRecord> records,
                @NonNull String[] name) {
            return CollectionUtils.any(records, r -> Arrays.equals(name, r.getName()));
        }
    }

    @NonNull
    @Override
    protected String getTag() {
        return mLogTag;
    }

    @VisibleForTesting
    protected long getInitialDelay() {
        // First wait for a random time in 0-250ms
        // as per https://datatracker.ietf.org/doc/html/rfc6762#section-8.1
        return (long) (Math.random() * 250);
    }

    /**
     * Start sending packets for probing.
     */
    public void startProbing(@NonNull ProbingInfo info) {
        startProbing(info, getInitialDelay());
    }

    private void startProbing(@NonNull ProbingInfo info, long delay) {
        startSending(info.getServiceId(), info, delay);
    }
}
