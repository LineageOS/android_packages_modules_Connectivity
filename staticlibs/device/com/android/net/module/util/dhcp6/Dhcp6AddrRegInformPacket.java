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

import com.android.net.module.util.structs.IaAddressOption;

import java.net.Inet6Address;
import java.nio.ByteBuffer;

/**
 * DHCPv6 ADDR-REG-INFORM packet class, a client sends an an ADDR-REG-INFORM message to register
 * an IPv6 address is in use to DHCPv6 addr-reg server.
 *
 * https://www.rfc-editor.org/rfc/rfc9686.html#section-4.2
 */
public class Dhcp6AddrRegInformPacket extends Dhcp6Packet {
    @NonNull
    public final Inet6Address mIaAddress;
    public final long mPreferred;
    public final long mValid;

    /**
     * Generates a ADDR-REG-INFORM packet with the specified parameters.
     */
    public Dhcp6AddrRegInformPacket(int transId, int elapsedTime, @NonNull final byte[] clientDuid,
            @NonNull final Inet6Address iaAddress, long preferred, long valid) {
        super(transId, elapsedTime, clientDuid, null /* serverDuid */, null /* iapd */);
        mIaAddress = iaAddress;
        mPreferred = preferred;
        mValid = valid;
    }

    /**
     * Return the DHCPv6 ADDR_REG_INFORM message type (36).
     */
    public byte getMessageType() {
        return DHCP6_MESSAGE_TYPE_ADDR_REG_INFORM;
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
        final ByteBuffer iaAddressOption = IaAddressOption.build(
                (short) IaAddressOption.LENGTH, mIaAddress, mPreferred, mValid);
        addTlv(packet, iaAddressOption);

        packet.flip();
        return packet;
    }
}
