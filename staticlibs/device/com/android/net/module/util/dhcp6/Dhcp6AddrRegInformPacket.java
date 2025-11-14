/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.net.module.util.dhcp6;

import static com.android.net.module.util.NetworkStackConstants.DHCP_MAX_LENGTH;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;

/**
 * DHCPv6 ADDR-REG-INFORM packet class, a client sends an an ADDR-REG-INFORM message to register
 * an IPv6 address is in use to DHCPv6 addr-reg server.
 *
 * https://www.rfc-editor.org/rfc/rfc9686.html#section-4.2
 */
public class Dhcp6AddrRegInformPacket extends Dhcp6Packet {
    /**
     * Generates a ADDR-REG-INFORM packet with the specified parameters.
     */
    Dhcp6AddrRegInformPacket(int transId, int elapsedTime, @NonNull final byte[] clientDuid,
            @NonNull final byte[] iaAddress) {
        super(transId, elapsedTime, clientDuid, null /* serverDuid */, null /* iapd */);
        mIaAddress = iaAddress;
    }

    /**
     * Build a DHCPv6 ADDR-REG-INFORM message with the specific parameters.
     */
    public ByteBuffer buildPacket() {
        final ByteBuffer packet = ByteBuffer.allocate(DHCP_MAX_LENGTH);
        final int msgTypeAndTransId = (DHCP6_MESSAGE_TYPE_ADDR_REG_INFORM << 24) | mTransId;
        packet.putInt(msgTypeAndTransId);

        addTlv(packet, DHCP6_CLIENT_IDENTIFIER, mClientDuid);
        addTlv(packet, DHCP6_ELAPSED_TIME, (short) (mElapsedTime & 0xFFFF));
        addTlv(packet, DHCP6_IA_ADDR, mIaAddress);

        packet.flip();
        return packet;
    }
}
