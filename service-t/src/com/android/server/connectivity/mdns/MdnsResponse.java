/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.net.Network;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/** An mDNS response. */
public class MdnsResponse {
    private final List<MdnsRecord> records;
    private final List<MdnsPointerRecord> pointerRecords;
    private MdnsServiceRecord serviceRecord;
    private MdnsTextRecord textRecord;
    private MdnsInetAddressRecord inet4AddressRecord;
    private MdnsInetAddressRecord inet6AddressRecord;
    private long lastUpdateTime;
    private final int interfaceIndex;
    @Nullable private final Network network;
    @NonNull private final String[] serviceName;

    /** Constructs a new, empty response. */
    public MdnsResponse(long now, @NonNull String[] serviceName, int interfaceIndex,
            @Nullable Network network) {
        lastUpdateTime = now;
        records = new LinkedList<>();
        pointerRecords = new LinkedList<>();
        this.interfaceIndex = interfaceIndex;
        this.network = network;
        this.serviceName = serviceName;
    }

    public MdnsResponse(@NonNull MdnsResponse base) {
        records = new ArrayList<>(base.records);
        pointerRecords = new ArrayList<>(base.pointerRecords);
        serviceRecord = base.serviceRecord;
        textRecord = base.textRecord;
        inet4AddressRecord = base.inet4AddressRecord;
        inet6AddressRecord = base.inet6AddressRecord;
        lastUpdateTime = base.lastUpdateTime;
        serviceName = base.serviceName;
        interfaceIndex = base.interfaceIndex;
        network = base.network;
    }

    /**
     * Compare records for equality, including their TTL.
     *
     * MdnsRecord#equals ignores TTL and receiptTimeMillis, but methods in this class need to update
     * records when the TTL changes (especially for goodbye announcements).
     */
    private boolean recordsAreSame(MdnsRecord a, MdnsRecord b) {
        if (!Objects.equals(a, b)) return false;
        return a == null || a.getTtl() == b.getTtl();
    }

    /**
     * Adds a pointer record.
     *
     * @return <code>true</code> if the record was added, or <code>false</code> if a matching
     * pointer record is already present in the response with the same TTL.
     */
    public synchronized boolean addPointerRecord(MdnsPointerRecord pointerRecord) {
        if (!Arrays.equals(serviceName, pointerRecord.getPointer())) {
            throw new IllegalArgumentException(
                    "Pointer records for different service names cannot be added");
        }
        final int existing = pointerRecords.indexOf(pointerRecord);
        if (existing >= 0) {
            if (recordsAreSame(pointerRecord, pointerRecords.get(existing))) {
                return false;
            }
            pointerRecords.remove(existing);
            records.remove(existing);
        }
        pointerRecords.add(pointerRecord);
        records.add(pointerRecord);
        return true;
    }

    /** Gets the pointer records. */
    public synchronized List<MdnsPointerRecord> getPointerRecords() {
        // Returns a shallow copy.
        return new LinkedList<>(pointerRecords);
    }

    public synchronized boolean hasPointerRecords() {
        return !pointerRecords.isEmpty();
    }

    @VisibleForTesting
    synchronized void clearPointerRecords() {
        pointerRecords.clear();
    }

    public synchronized boolean hasSubtypes() {
        for (MdnsPointerRecord pointerRecord : pointerRecords) {
            if (pointerRecord.hasSubtype()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public synchronized List<String> getSubtypes() {
        List<String> subtypes = null;
        for (MdnsPointerRecord pointerRecord : pointerRecords) {
            String pointerRecordSubtype = pointerRecord.getSubtype();
            if (pointerRecordSubtype != null) {
                if (subtypes == null) {
                    subtypes = new LinkedList<>();
                }
                subtypes.add(pointerRecordSubtype);
            }
        }

        return subtypes;
    }

    @VisibleForTesting
    public synchronized void removeSubtypes() {
        Iterator<MdnsPointerRecord> iter = pointerRecords.iterator();
        while (iter.hasNext()) {
            MdnsPointerRecord pointerRecord = iter.next();
            if (pointerRecord.hasSubtype()) {
                iter.remove();
            }
        }
    }

    /** Sets the service record. */
    public synchronized boolean setServiceRecord(MdnsServiceRecord serviceRecord) {
        if (recordsAreSame(this.serviceRecord, serviceRecord)) {
            return false;
        }
        if (this.serviceRecord != null) {
            records.remove(this.serviceRecord);
        }
        this.serviceRecord = serviceRecord;
        if (this.serviceRecord != null) {
            records.add(this.serviceRecord);
        }
        return true;
    }

    /** Gets the service record. */
    public synchronized MdnsServiceRecord getServiceRecord() {
        return serviceRecord;
    }

    public synchronized boolean hasServiceRecord() {
        return serviceRecord != null;
    }

    /** Sets the text record. */
    public synchronized boolean setTextRecord(MdnsTextRecord textRecord) {
        if (recordsAreSame(this.textRecord, textRecord)) {
            return false;
        }
        if (this.textRecord != null) {
            records.remove(this.textRecord);
        }
        this.textRecord = textRecord;
        if (this.textRecord != null) {
            records.add(this.textRecord);
        }
        return true;
    }

    /** Gets the text record. */
    public synchronized MdnsTextRecord getTextRecord() {
        return textRecord;
    }

    public synchronized boolean hasTextRecord() {
        return textRecord != null;
    }

    /** Sets the IPv4 address record. */
    public synchronized boolean setInet4AddressRecord(
            @Nullable MdnsInetAddressRecord newInet4AddressRecord) {
        if (recordsAreSame(this.inet4AddressRecord, newInet4AddressRecord)) {
            return false;
        }
        if (this.inet4AddressRecord != null) {
            records.remove(this.inet4AddressRecord);
            this.inet4AddressRecord = null;
        }
        if (newInet4AddressRecord != null && newInet4AddressRecord.getInet4Address() != null) {
            this.inet4AddressRecord = newInet4AddressRecord;
            records.add(this.inet4AddressRecord);
        }
        return true;
    }

    /** Gets the IPv4 address record. */
    public synchronized MdnsInetAddressRecord getInet4AddressRecord() {
        return inet4AddressRecord;
    }

    public synchronized boolean hasInet4AddressRecord() {
        return inet4AddressRecord != null;
    }

    /** Sets the IPv6 address record. */
    public synchronized boolean setInet6AddressRecord(
            @Nullable MdnsInetAddressRecord newInet6AddressRecord) {
        if (recordsAreSame(this.inet6AddressRecord, newInet6AddressRecord)) {
            return false;
        }
        if (this.inet6AddressRecord != null) {
            records.remove(this.inet6AddressRecord);
            this.inet6AddressRecord = null;
        }
        if (newInet6AddressRecord != null && newInet6AddressRecord.getInet6Address() != null) {
            this.inet6AddressRecord = newInet6AddressRecord;
            records.add(this.inet6AddressRecord);
        }
        return true;
    }

    /**
     * Returns the index of the network interface at which this response was received. Can be set to
     * {@link MdnsSocket#INTERFACE_INDEX_UNSPECIFIED} if unset.
     */
    public int getInterfaceIndex() {
        return interfaceIndex;
    }

    /**
     * Returns the network at which this response was received, or null if the network is unknown.
     */
    @Nullable
    public Network getNetwork() {
        return network;
    }

    /** Gets the IPv6 address record. */
    public synchronized MdnsInetAddressRecord getInet6AddressRecord() {
        return inet6AddressRecord;
    }

    public synchronized boolean hasInet6AddressRecord() {
        return inet6AddressRecord != null;
    }

    /** Gets all of the records. */
    public synchronized List<MdnsRecord> getRecords() {
        return new LinkedList<>(records);
    }

    /**
     * Drop address records if they are for a hostname that does not match the service record.
     *
     * @return True if the records were dropped.
     */
    public synchronized boolean dropUnmatchedAddressRecords() {
        if (this.serviceRecord == null) return false;
        boolean dropAddressRecords = false;

        if (this.inet4AddressRecord != null) {
            if (!Arrays.equals(
                    this.serviceRecord.getServiceHost(), this.inet4AddressRecord.getName())) {
                dropAddressRecords = true;
            }
        }
        if (this.inet6AddressRecord != null) {
            if (!Arrays.equals(
                    this.serviceRecord.getServiceHost(), this.inet6AddressRecord.getName())) {
                dropAddressRecords = true;
            }
        }

        if (dropAddressRecords) {
            setInet4AddressRecord(null);
            setInet6AddressRecord(null);
            return true;
        }
        return false;
    }

    /**
     * Tests if the response is complete. A response is considered complete if it contains SRV,
     * TXT, and A (for IPv4) or AAAA (for IPv6) records. The service type->name mapping is always
     * known when constructing a MdnsResponse, so this may return true when there is no PTR record.
     */
    public synchronized boolean isComplete() {
        return (serviceRecord != null)
                && (textRecord != null)
                && (inet4AddressRecord != null || inet6AddressRecord != null);
    }

    /**
     * Returns the key for this response. The key uniquely identifies the response by its service
     * name.
     */
    @Nullable
    public String getServiceInstanceName() {
        return serviceName.length > 0 ? serviceName[0] : null;
    }

    @NonNull
    public String[] getServiceName() {
        return serviceName;
    }

    /**
     * Tests if this response is a goodbye message. This will be true if a service record is present
     * and any of the records have a TTL of 0.
     */
    public synchronized boolean isGoodbye() {
        if (getServiceInstanceName() != null) {
            for (MdnsRecord record : records) {
                // Expiring PTR records with subtypes just signal a change in known supported
                // criteria, not the device itself going offline, so ignore those.
                if ((record instanceof MdnsPointerRecord)
                        && ((MdnsPointerRecord) record).hasSubtype()) {
                    continue;
                }

                if (record.getTtl() == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Writes the response to a packet.
     *
     * @param writer The writer to use.
     * @param now    The current time. This is used to write updated TTLs that reflect the remaining
     *               TTL
     *               since the response was received.
     * @return The number of records that were written.
     * @throws IOException If an error occurred while writing (typically indicating overflow).
     */
    public synchronized int write(MdnsPacketWriter writer, long now) throws IOException {
        int count = 0;
        for (MdnsPointerRecord pointerRecord : pointerRecords) {
            pointerRecord.write(writer, now);
            ++count;
        }

        if (serviceRecord != null) {
            serviceRecord.write(writer, now);
            ++count;
        }

        if (textRecord != null) {
            textRecord.write(writer, now);
            ++count;
        }

        if (inet4AddressRecord != null) {
            inet4AddressRecord.write(writer, now);
            ++count;
        }

        if (inet6AddressRecord != null) {
            inet6AddressRecord.write(writer, now);
            ++count;
        }

        return count;
    }
}
