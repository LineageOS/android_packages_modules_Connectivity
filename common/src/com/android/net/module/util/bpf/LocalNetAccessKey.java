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

package com.android.net.module.util.bpf;

import com.android.net.module.util.Struct;

import java.net.InetAddress;

public class LocalNetAccessKey extends Struct {

    @Field(order = 0, type = Type.U32)
    public final long lpmBitlen;
    @Field(order = 1, type = Type.U32)
    public final long ifIndex;
    @Field(order = 2, type = Type.IpAddress)
    public final InetAddress remoteAddress;
    @Field(order = 3, type = Type.U16)
    public final int protocol;
    @Field(order = 4, type = Type.UBE16)
    public final int remotePort;

    public LocalNetAccessKey(long lpmBitlen, long ifIndex, InetAddress remoteAddress, int protocol,
            int remotePort) {
        this.lpmBitlen = lpmBitlen;
        this.ifIndex = ifIndex;
        this.protocol = protocol;
        this.remotePort = remotePort;
        this.remoteAddress = remoteAddress;
    }

    @Override
    public String toString() {
        String s = "LocalNetAccessKey{lpmBitlen=" + lpmBitlen;

        long bits = lpmBitlen;

        // u32 ifIndex
        if (bits <= 0 && ifIndex != 0) s += " ??";
        if (bits > 0 || ifIndex != 0) s += " ifIndex=" + ifIndex;
        if (bits > 0 && bits < 32) s += "/" + bits + "[LE]";
        bits -= 32;

        // u128 remoteAddress
        if (bits <= 0 && !remoteAddress.isAnyLocalAddress()) s += " ??";
        if (bits > 0 || !remoteAddress.isAnyLocalAddress()) {
            s += " remoteAddress=";
            String ip = remoteAddress.toString();
            if (ip.startsWith("/::ffff:")) { // technically wrong IPv4-mapped IPv6 address detection
              s += ip.substring(8);
              if (bits >= 96 && bits < 128) s += "/" + (bits - 96);
            } else if (ip.startsWith("/")) {
              s += ip.substring(1);
              if (bits >= 0 && bits < 128) s += "/" + bits;
            } else { // WTF, includes a hostname or what?
              s += ip;
            }
        }
        bits -= 128;

        // u16 protocol
        if (bits <= 0 && protocol != 0) s += " ??";
        if (bits > 0 || protocol != 0) s += " protocol=" + protocol;
        if (bits > 0 && bits < 16) s += "/" + bits + "[LE16]";
        bits -= 16;

        // be16 remotePort
        if (bits <= 0 && remotePort != 0) s += " ??";
        if (bits > 0 || remotePort != 0) s += " remotePort=" + remotePort;
        if (bits > 0 && bits < 16) s += "/" + bits + "[BE16]";
        bits -= 16;

        s += "}";
        return s;
    }
}
