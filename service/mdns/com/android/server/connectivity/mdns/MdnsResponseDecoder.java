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
import android.os.SystemClock;

import com.android.server.connectivity.mdns.util.MdnsLogger;

import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/** A class that decodes mDNS responses from UDP packets. */
public class MdnsResponseDecoder {

    public static final int SUCCESS = 0;
    private static final String TAG = "MdnsResponseDecoder";
    private static final MdnsLogger LOGGER = new MdnsLogger(TAG);
    private final boolean allowMultipleSrvRecordsPerHost =
            MdnsConfigs.allowMultipleSrvRecordsPerHost();
    @Nullable private final String[] serviceType;
    private final Clock clock;

    /** Constructs a new decoder that will extract responses for the given service type. */
    public MdnsResponseDecoder(@NonNull Clock clock, @Nullable String[] serviceType) {
        this.clock = clock;
        this.serviceType = serviceType;
    }

    private static void skipMdnsRecord(MdnsPacketReader reader) throws IOException {
        reader.skip(2 + 4); // skip the class and TTL
        int dataLength = reader.readUInt16();
        reader.skip(dataLength);
    }

    private static MdnsResponse findResponseWithPointer(
            List<MdnsResponse> responses, String[] pointer) {
        if (responses != null) {
            for (MdnsResponse response : responses) {
                List<MdnsPointerRecord> pointerRecords = response.getPointerRecords();
                if (pointerRecords == null) {
                    continue;
                }
                for (MdnsPointerRecord pointerRecord : pointerRecords) {
                    if (Arrays.equals(pointerRecord.getPointer(), pointer)) {
                        return response;
                    }
                }
            }
        }
        return null;
    }

    private static MdnsResponse findResponseWithHostName(
            List<MdnsResponse> responses, String[] hostName) {
        if (responses != null) {
            for (MdnsResponse response : responses) {
                MdnsServiceRecord serviceRecord = response.getServiceRecord();
                if (serviceRecord == null) {
                    continue;
                }
                if (Arrays.equals(serviceRecord.getServiceHost(), hostName)) {
                    return response;
                }
            }
        }
        return null;
    }

    /**
     * Decodes all mDNS responses for the desired service type from a packet. The class does not
     * check
     * the responses for completeness; the caller should do that.
     *
     * @param packet The packet to read from.
     * @param interfaceIndex the network interface index (or {@link
     *     MdnsSocket#INTERFACE_INDEX_UNSPECIFIED} if not known) at which the packet was received
     * @param network the network at which the packet was received, or null if it is unknown.
     * @return A list of mDNS responses, or null if the packet contained no appropriate responses.
     */
    public int decode(@NonNull DatagramPacket packet, @NonNull List<MdnsResponse> responses,
            int interfaceIndex, @Nullable Network network) {
        MdnsPacketReader reader = new MdnsPacketReader(packet);

        List<MdnsRecord> records;
        try {
            reader.readUInt16(); // transaction ID (not used)
            int flags = reader.readUInt16();
            if ((flags & MdnsConstants.FLAGS_RESPONSE_MASK) != MdnsConstants.FLAGS_RESPONSE) {
                return MdnsResponseErrorCode.ERROR_NOT_RESPONSE_MESSAGE;
            }

            int numQuestions = reader.readUInt16();
            int numAnswers = reader.readUInt16();
            int numAuthority = reader.readUInt16();
            int numRecords = reader.readUInt16();

            LOGGER.log(String.format(
                    "num questions: %d, num answers: %d, num authority: %d, num records: %d",
                    numQuestions, numAnswers, numAuthority, numRecords));

            if (numAnswers < 1) {
                return MdnsResponseErrorCode.ERROR_NO_ANSWERS;
            }

            records = new LinkedList<>();

            for (int i = 0; i < (numAnswers + numAuthority + numRecords); ++i) {
                String[] name;
                try {
                    name = reader.readLabels();
                } catch (IOException e) {
                    LOGGER.e("Failed to read labels from mDNS response.", e);
                    return MdnsResponseErrorCode.ERROR_READING_RECORD_NAME;
                }
                int type = reader.readUInt16();

                switch (type) {
                    case MdnsRecord.TYPE_A: {
                        try {
                            records.add(new MdnsInetAddressRecord(name, MdnsRecord.TYPE_A, reader));
                        } catch (IOException e) {
                            LOGGER.e("Failed to read A record from mDNS response.", e);
                            return MdnsResponseErrorCode.ERROR_READING_A_RDATA;
                        }
                        break;
                    }

                    case MdnsRecord.TYPE_AAAA: {
                        try {
                            // AAAA should only contain the IPv6 address.
                            MdnsInetAddressRecord record =
                                    new MdnsInetAddressRecord(name, MdnsRecord.TYPE_AAAA, reader);
                            if (record.getInet6Address() != null) {
                                records.add(record);
                            }
                        } catch (IOException e) {
                            LOGGER.e("Failed to read AAAA record from mDNS response.", e);
                            return MdnsResponseErrorCode.ERROR_READING_AAAA_RDATA;
                        }
                        break;
                    }

                    case MdnsRecord.TYPE_PTR: {
                        try {
                            records.add(new MdnsPointerRecord(name, reader));
                        } catch (IOException e) {
                            LOGGER.e("Failed to read PTR record from mDNS response.", e);
                            return MdnsResponseErrorCode.ERROR_READING_PTR_RDATA;
                        }
                        break;
                    }

                    case MdnsRecord.TYPE_SRV: {
                        if (name.length == 4) {
                            try {
                                records.add(new MdnsServiceRecord(name, reader));
                            } catch (IOException e) {
                                LOGGER.e("Failed to read SRV record from mDNS response.", e);
                                return MdnsResponseErrorCode.ERROR_READING_SRV_RDATA;
                            }
                        } else {
                            try {
                                skipMdnsRecord(reader);
                            } catch (IOException e) {
                                LOGGER.e("Failed to skip SVR record from mDNS response.", e);
                                return MdnsResponseErrorCode.ERROR_SKIPPING_SRV_RDATA;
                            }
                        }
                        break;
                    }

                    case MdnsRecord.TYPE_TXT: {
                        try {
                            records.add(new MdnsTextRecord(name, reader));
                        } catch (IOException e) {
                            LOGGER.e("Failed to read TXT record from mDNS response.", e);
                            return MdnsResponseErrorCode.ERROR_READING_TXT_RDATA;
                        }
                        break;
                    }

                    default: {
                        try {
                            skipMdnsRecord(reader);
                        } catch (IOException e) {
                            LOGGER.e("Failed to skip mDNS record.", e);
                            return MdnsResponseErrorCode.ERROR_SKIPPING_UNKNOWN_RECORD;
                        }
                    }
                }
            }
        } catch (EOFException e) {
            LOGGER.e("Reached the end of the mDNS response unexpectedly.", e);
            return MdnsResponseErrorCode.ERROR_END_OF_FILE;
        }

        // The response records are structured in a hierarchy, where some records reference
        // others, as follows:
        //
        //        PTR
        //        / \
        //       /   \
        //      TXT  SRV
        //           / \
        //          /   \
        //         A   AAAA
        //
        // But the order in which these records appear in the response packet is completely
        // arbitrary. This means that we need to rescan the record list to construct each level of
        // this hierarchy.
        //
        // PTR: service type -> service instance name
        //
        // SRV: service instance name -> host name (priority, weight)
        //
        // TXT: service instance name -> machine readable txt entries.
        //
        // A: host name -> IP address

        // Loop 1: find PTR records, which identify distinct service instances.
        long now = SystemClock.elapsedRealtime();
        for (MdnsRecord record : records) {
            if (record instanceof MdnsPointerRecord) {
                String[] name = record.getName();
                if ((serviceType == null)
                        || Arrays.equals(name, serviceType)
                        || ((name.length == (serviceType.length + 2))
                        && name[1].equals(MdnsConstants.SUBTYPE_LABEL)
                        && MdnsRecord.labelsAreSuffix(serviceType, name))) {
                    MdnsPointerRecord pointerRecord = (MdnsPointerRecord) record;
                    // Group PTR records that refer to the same service instance name into a single
                    // response.
                    MdnsResponse response = findResponseWithPointer(responses,
                            pointerRecord.getPointer());
                    if (response == null) {
                        response = new MdnsResponse(now, interfaceIndex, network);
                        responses.add(response);
                    }
                    // Set interface index earlier because some responses have PTR record only.
                    // Need to know every response is getting from which interface.
                    response.addPointerRecord((MdnsPointerRecord) record);
                }
            }
        }

        // Loop 2: find SRV and TXT records, which reference the pointer in the PTR record.
        for (MdnsRecord record : records) {
            if (record instanceof MdnsServiceRecord) {
                MdnsServiceRecord serviceRecord = (MdnsServiceRecord) record;
                MdnsResponse response = findResponseWithPointer(responses, serviceRecord.getName());
                if (response != null) {
                    response.setServiceRecord(serviceRecord);
                }
            } else if (record instanceof MdnsTextRecord) {
                MdnsTextRecord textRecord = (MdnsTextRecord) record;
                MdnsResponse response = findResponseWithPointer(responses, textRecord.getName());
                if (response != null) {
                    response.setTextRecord(textRecord);
                }
            }
        }

        // Loop 3: find A and AAAA records, which reference the host name in the SRV record.
        for (MdnsRecord record : records) {
            if (record instanceof MdnsInetAddressRecord) {
                MdnsInetAddressRecord inetRecord = (MdnsInetAddressRecord) record;
                if (allowMultipleSrvRecordsPerHost) {
                    List<MdnsResponse> matchingResponses =
                            findResponsesWithHostName(responses, inetRecord.getName());
                    for (MdnsResponse response : matchingResponses) {
                        assignInetRecord(response, inetRecord);
                    }
                } else {
                    MdnsResponse response =
                            findResponseWithHostName(responses, inetRecord.getName());
                    if (response != null) {
                        assignInetRecord(response, inetRecord);
                    }
                }
            }
        }

        return SUCCESS;
    }

    private static void assignInetRecord(MdnsResponse response, MdnsInetAddressRecord inetRecord) {
        if (inetRecord.getInet4Address() != null) {
            response.setInet4AddressRecord(inetRecord);
        } else if (inetRecord.getInet6Address() != null) {
            response.setInet6AddressRecord(inetRecord);
        }
    }

    private static List<MdnsResponse> findResponsesWithHostName(
            @Nullable List<MdnsResponse> responses, String[] hostName) {
        if (responses == null || responses.isEmpty()) {
            return List.of();
        }

        List<MdnsResponse> result = null;
        for (MdnsResponse response : responses) {
            MdnsServiceRecord serviceRecord = response.getServiceRecord();
            if (serviceRecord == null) {
                continue;
            }
            if (Arrays.equals(serviceRecord.getServiceHost(), hostName)) {
                if (result == null) {
                    result = new ArrayList<>(/* initialCapacity= */ responses.size());
                }
                result.add(response);
            }
        }
        return result == null ? List.of() : result;
    }

    public static class Clock {
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }
    }
}