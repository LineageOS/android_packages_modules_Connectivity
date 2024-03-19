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

package com.android.net.module.util.bpf;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.net.Inet4Address;

/** Value type for clat ingress IPv6 maps. */
public class ClatIngress6Value extends Struct {
    @Field(order = 0, type = Type.S32)
    public final int oif; // The output interface to redirect to (0 means don't redirect)

    @Field(order = 1, type = Type.Ipv4Address)
    public final Inet4Address local4; // The destination IPv4 address

    @Field(order = 2, type = Type.U63)
    public final long packets; // Count of translated gso (large) packets

    @Field(order = 3, type = Type.U63)
    public final long bytes; // Sum of post-translation skb->len

    public ClatIngress6Value(final int oif, final Inet4Address local4, final long packets,
            final long bytes) {
        this.oif = oif;
        this.local4 = local4;
        this.packets = packets;
        this.bytes = bytes;
    }

    public ClatIngress6Value(final int oif, final Inet4Address local4) {
        this(oif, local4, 0, 0);
    }
}
