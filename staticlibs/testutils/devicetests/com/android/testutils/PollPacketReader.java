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

package com.android.testutils;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.net.module.util.ArrayTrackRecord;
import com.android.net.module.util.PacketReader;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import kotlin.Lazy;
import kotlin.LazyKt;

/**
 * A packet reader that can poll for received packets and send responses on a fd.
 *
 * It also implements facilities to reply to received packets.
 */
public class PollPacketReader extends PacketReader {
    private final FileDescriptor mFd;
    private final ArrayTrackRecord<byte[]> mReceivedPackets = new ArrayTrackRecord<>();
    private final Lazy<ArrayTrackRecord<byte[]>.ReadHead> mReadHead =
            LazyKt.lazy(mReceivedPackets::newReadHead);

    public PollPacketReader(Handler h, FileDescriptor fd, int maxPacketSize) {
        super(h, maxPacketSize);
        mFd = fd;
    }


    /**
     * Attempt to start the FdEventsReader on its handler thread.
     *
     * As opposed to {@link android.net.util.FdEventsReader#start()}, this method will not report
     * failure to start, so it is only appropriate in tests that will fail later if that happens.
     */
    public void startAsyncForTest() {
        getHandler().post(this::start);
    }

    @Override
    protected FileDescriptor createFd() {
        return mFd;
    }

    @Override
    protected void handlePacket(byte[] recvbuf, int length) {
        final byte[] newPacket = Arrays.copyOf(recvbuf, length);
        if (!mReceivedPackets.add(newPacket)) {
            throw new AssertionError("More than " + Integer.MAX_VALUE + " packets outstanding!");
        }
    }

    /**
     * @deprecated This method does not actually "pop" (which generally means the last packet).
     * Use {@link #poll(long)}, which has the same behavior, instead.
     */
    @Nullable
    @Deprecated
    public byte[] popPacket(long timeoutMs) {
        return poll(timeoutMs);
    }

    /**
     * @deprecated This method does not actually "pop" (which generally means the last packet).
     * Use {@link #poll(long, Predicate)}, which has the same behavior, instead.
     */
    @Nullable
    @Deprecated
    public byte[] popPacket(long timeoutMs, @NonNull Predicate<byte[]> filter) {
        return poll(timeoutMs, filter);
    }

    /**
     * Get the next packet that was received on the interface.
     */
    @Nullable
    public byte[] poll(long timeoutMs) {
        return mReadHead.getValue().poll(timeoutMs, packet -> true);
    }

    /**
     * Get the next packet that was received on the interface and matches the specified filter.
     */
    @Nullable
    public byte[] poll(long timeoutMs, @NonNull Predicate<byte[]> filter) {
        return mReadHead.getValue().poll(timeoutMs, filter::test);
    }

    /**
     * Get all packets received since before the start of the last poll operation.
     */
    public List<byte[]> backtrace() {
        return mReadHead.getValue().backtrace();
    }

    /**
     * Get the {@link ArrayTrackRecord} that records all packets received by the reader since its
     * creation.
     */
    public ArrayTrackRecord<byte[]> getReceivedPackets() {
        return mReceivedPackets;
    }

    /*
     * Send a response on the fd.
     *
     * The passed ByteBuffer is flipped after use.
     *
     * @param packet The packet to send.
     * @throws IOException if the interface can't be written to.
     */
    public void sendResponse(final ByteBuffer packet) throws IOException {
        try (FileOutputStream out = new FileOutputStream(mFd)) {
            byte[] packetBytes = new byte[packet.limit()];
            packet.get(packetBytes);
            packet.flip();  // So we can reuse it in the future.
            out.write(packetBytes);
        }
    }
}
