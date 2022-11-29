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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.net.SocketAddress;

/**
 * A class that handles sending mDNS replies to a {@link MulticastSocket}, possibly queueing them
 * to be sent after some delay.
 *
 * TODO: implement sending after a delay, combining queued replies and duplicate answer suppression
 */
public class MdnsReplySender {
    @NonNull
    private final MulticastSocket mSocket;
    @NonNull
    private final Looper mLooper;
    @NonNull
    private final byte[] mPacketCreationBuffer;

    public MdnsReplySender(@NonNull Looper looper,
            @NonNull MulticastSocket socket, @NonNull byte[] packetCreationBuffer) {
        mLooper = looper;
        mSocket = socket;
        mPacketCreationBuffer = packetCreationBuffer;
    }

    /**
     * Send a packet immediately.
     *
     * Must be called on the looper thread used by the {@link MdnsReplySender}.
     */
    public void sendNow(@NonNull MdnsPacket packet, @NonNull SocketAddress destination)
            throws IOException {
        if (Thread.currentThread() != mLooper.getThread()) {
            throw new IllegalStateException("sendNow must be called in the handler thread");
        }

        // TODO: support packets over size (send in multiple packets with TC bit set)
        final MdnsPacketWriter writer = new MdnsPacketWriter(mPacketCreationBuffer);

        writer.writeUInt16(0); // Transaction ID (advertisement: 0)
        writer.writeUInt16(packet.flags); // Response, authoritative (rfc6762 18.4)
        writer.writeUInt16(packet.questions.size()); // questions count
        writer.writeUInt16(packet.answers.size()); // answers count
        writer.writeUInt16(packet.authorityRecords.size()); // authority entries count
        writer.writeUInt16(packet.additionalRecords.size()); // additional records count

        for (MdnsRecord record : packet.questions) {
            record.write(writer, 0L);
        }
        for (MdnsRecord record : packet.answers) {
            record.write(writer, 0L);
        }
        for (MdnsRecord record : packet.authorityRecords) {
            record.write(writer, 0L);
        }
        for (MdnsRecord record : packet.additionalRecords) {
            record.write(writer, 0L);
        }

        final int len = writer.getWritePosition();
        final byte[] outBuffer = new byte[len];
        System.arraycopy(mPacketCreationBuffer, 0, outBuffer, 0, len);

        mSocket.send(new DatagramPacket(outBuffer, 0, len, destination));
    }
}
