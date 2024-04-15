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

package com.android.net.module.util.netlink;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.nio.ByteBuffer;

/**
 * struct prefixmsg {
 *     unsigned char  prefix_family;
 *     unsigned char  prefix_pad1;
 *     unsigned short prefix_pad2;
 *     int            prefix_ifindex;
 *     unsigned char  prefix_type;
 *     unsigned char  prefix_len;
 *     unsigned char  prefix_flags;
 *     unsigned char  prefix_pad3;
 * }
 *
 * see also:
 *
 *     include/uapi/linux/rtnetlink.h
 *
 * @hide
 */
public class StructPrefixMsg extends Struct {
    // Already aligned.
    public static final int STRUCT_SIZE = 12;

    @Field(order = 0, type = Type.U8, padding = 3)
    public final short prefix_family;
    @Field(order = 1, type = Type.S32)
    public final int prefix_ifindex;
    @Field(order = 2, type = Type.U8)
    public final short prefix_type;
    @Field(order = 3, type = Type.U8)
    public final short prefix_len;
    @Field(order = 4, type = Type.U8, padding = 1)
    public final short prefix_flags;

    @VisibleForTesting
    public StructPrefixMsg(short family, int ifindex, short type, short len, short flags) {
        this.prefix_family = family;
        this.prefix_ifindex = ifindex;
        this.prefix_type = type;
        this.prefix_len = len;
        this.prefix_flags = flags;
    }

    /**
     * Parse a prefixmsg struct from a {@link ByteBuffer}.
     *
     * @param byteBuffer The buffer from which to parse the prefixmsg.
     * @return the parsed prefixmsg struct, or throw IllegalArgumentException if the prefixmsg
     *         struct could not be parsed successfully (for example, if it was truncated).
     */
    public static StructPrefixMsg parse(@NonNull final ByteBuffer byteBuffer) {
        if (byteBuffer.remaining() < STRUCT_SIZE) {
            throw new IllegalArgumentException("Invalid bytebuffer remaining size "
                    + byteBuffer.remaining() + "for prefix_msg struct.");
        }

        // The ByteOrder must already have been set to native order.
        return Struct.parse(StructPrefixMsg.class, byteBuffer);
    }

    /**
     * Write a prefixmsg struct to {@link ByteBuffer}.
     */
    public void pack(@NonNull final ByteBuffer byteBuffer) {
        // The ByteOrder must already have been set to native order.
        writeToByteBuffer(byteBuffer);
    }
}
