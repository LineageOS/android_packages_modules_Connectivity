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

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.nio.ByteBuffer;

/**
 * struct prefix_cacheinfo {
 *     __u32 preferred_time;
 *     __u32 valid_time;
 * }
 *
 * see also:
 *
 *     include/uapi/linux/if_addr.h
 *
 * @hide
 */
public class StructPrefixCacheInfo extends Struct {
    public static final int STRUCT_SIZE = 8;

    @Field(order = 0, type = Type.U32)
    public final long preferred_time;
    @Field(order = 1, type = Type.U32)
    public final long valid_time;

    StructPrefixCacheInfo(long preferred, long valid) {
        this.preferred_time = preferred;
        this.valid_time = valid;
    }

    /**
     * Parse a prefix_cacheinfo struct from a {@link ByteBuffer}.
     *
     * @param byteBuffer The buffer from which to parse the prefix_cacheinfo.
     * @return the parsed prefix_cacheinfo struct, or throw IllegalArgumentException if the
     *         prefix_cacheinfo struct could not be parsed successfully(for example, if it was
     *         truncated).
     */
    public static StructPrefixCacheInfo parse(@NonNull final ByteBuffer byteBuffer) {
        if (byteBuffer.remaining() < STRUCT_SIZE) {
            throw new IllegalArgumentException("Invalid bytebuffer remaining size "
                    + byteBuffer.remaining() + " for prefix_cacheinfo attribute");
        }

        // The ByteOrder must already have been set to native order.
        return Struct.parse(StructPrefixCacheInfo.class, byteBuffer);
    }

    /**
     * Write a prefix_cacheinfo struct to {@link ByteBuffer}.
     */
    public void pack(@NonNull final ByteBuffer byteBuffer) {
        // The ByteOrder must already have been set to native order.
        writeToByteBuffer(byteBuffer);
    }
}
