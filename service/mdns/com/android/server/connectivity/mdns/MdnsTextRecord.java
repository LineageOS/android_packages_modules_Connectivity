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

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** An mDNS "TXT" record, which contains a list of text strings. */
// TODO(b/242631897): Resolve nullness suppression.
@SuppressWarnings("nullness")
@VisibleForTesting
public class MdnsTextRecord extends MdnsRecord {
    private List<String> strings;

    public MdnsTextRecord(String[] name, MdnsPacketReader reader) throws IOException {
        super(name, TYPE_TXT, reader);
    }

    /** Returns the list of strings. */
    public List<String> getStrings() {
        return Collections.unmodifiableList(strings);
    }

    @Override
    protected void readData(MdnsPacketReader reader) throws IOException {
        strings = new ArrayList<>();
        while (reader.getRemaining() > 0) {
            strings.add(reader.readString());
        }
    }

    @Override
    protected void writeData(MdnsPacketWriter writer) throws IOException {
        if (strings != null) {
            for (String string : strings) {
                writer.writeString(string);
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TXT: {");
        if (strings != null) {
            for (String string : strings) {
                sb.append(' ').append(string);
            }
        }
        sb.append("}");

        return sb.toString();
    }

    @Override
    public int hashCode() {
        return (super.hashCode() * 31) + Objects.hash(strings);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MdnsTextRecord)) {
            return false;
        }

        return super.equals(other) && Objects.equals(strings, ((MdnsTextRecord) other).strings);
    }
}