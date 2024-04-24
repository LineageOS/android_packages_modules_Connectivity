/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.net.module.util.HexDump.toHexString;

import android.annotation.NonNull;
import android.annotation.Nullable;

import androidx.annotation.VisibleForTesting;

import java.io.IOException;
import java.util.Arrays;

/** An mDNS "KEY" record, which contains a public key for a name. See RFC 2535. */
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
public class MdnsKeyRecord extends MdnsRecord {
    @Nullable private byte[] rData;

    public MdnsKeyRecord(@NonNull String[] name, @NonNull MdnsPacketReader reader)
            throws IOException {
        this(name, reader, false);
    }

    public MdnsKeyRecord(@NonNull String[] name, @NonNull MdnsPacketReader reader,
            boolean isQuestion) throws IOException {
        super(name, TYPE_KEY, reader, isQuestion);
    }

    public MdnsKeyRecord(@NonNull String[] name, boolean isUnicast) {
        super(name, TYPE_KEY,
                MdnsConstants.QCLASS_INTERNET | (isUnicast ? MdnsConstants.QCLASS_UNICAST : 0),
                0L /* receiptTimeMillis */, false /* cacheFlush */, 0L /* ttlMillis */);
    }

    public MdnsKeyRecord(@NonNull String[] name, long receiptTimeMillis, boolean cacheFlush,
            long ttlMillis, @Nullable byte[] rData) {
        super(name, TYPE_KEY, MdnsConstants.QCLASS_INTERNET, receiptTimeMillis, cacheFlush,
                ttlMillis);
        if (rData != null) {
            this.rData = Arrays.copyOf(rData, rData.length);
        }
    }
    /** Returns the KEY RDATA in bytes **/
    public byte[] getRData() {
        if (rData == null) {
            return null;
        }
        return Arrays.copyOf(rData, rData.length);
    }

    @Override
    protected void readData(MdnsPacketReader reader) throws IOException {
        rData = new byte[reader.getRemaining()];
        reader.readBytes(rData);
    }

    @Override
    protected void writeData(MdnsPacketWriter writer) throws IOException {
        if (rData != null) {
            writer.writeBytes(rData);
        }
    }

    @Override
    public String toString() {
        return "KEY: " + toHexString(rData);
    }

    @Override
    public int hashCode() {
        return (super.hashCode() * 31) + Arrays.hashCode(rData);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MdnsKeyRecord)) {
            return false;
        }

        return super.equals(other) && Arrays.equals(rData, ((MdnsKeyRecord) other).rData);
    }
}