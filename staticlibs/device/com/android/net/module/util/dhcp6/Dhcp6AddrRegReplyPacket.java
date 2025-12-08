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
import androidx.annotation.VisibleForTesting;

import com.android.net.module.util.structs.IaAddressOption;

import java.net.Inet6Address;
import java.nio.ByteBuffer;

/**
 * DHCPv6 ADDR-REG-REPLY packet class, a server acknowledges receipt of a valid ADDR-REG-INFORM
 * message by sending a ADDR-REG-REPLY message back.
 *
 * https://www.rfc-editor.org/rfc/rfc9686#section-4.3
 */
public class Dhcp6AddrRegReplyPacket extends Dhcp6Packet {
    @NonNull
    public final Inet6Address mIaAddress;
    public final long mPreferred;
    public final long mValid;

    /**
     * Generates a ADDR-REG-REPLY packet with the specified parameters.
     */
    @VisibleForTesting
    public Dhcp6AddrRegReplyPacket(int transId, @NonNull final byte[] clientDuid,
            @NonNull final byte[] serverDuid, @NonNull final Inet6Address iaAddress,
            long preferred, long valid) {
        super(transId, 0 /* elapsedTime */, clientDuid, serverDuid, null /* iapd */);
        mIaAddress = iaAddress;
        mPreferred = preferred;
        mValid = valid;
    }

    /**
     * Return the DHCPv6 ADDR_REG_REPLY message type (37).
     */
    public byte getMessageType() {
        return DHCP6_MESSAGE_TYPE_ADDR_REG_REPLY;
    }

    /**
     * Build a DHCPv6 ADDR-REG-REPLY message with the specific parameters.
     */
    public ByteBuffer buildPacket() {
        final ByteBuffer packet = ByteBuffer.allocate(DHCP_MAX_LENGTH);
        final int msgTypeAndTransId = (DHCP6_MESSAGE_TYPE_ADDR_REG_REPLY << 24) | mTransId;
        packet.putInt(msgTypeAndTransId);

        addTlv(packet, DHCP6_CLIENT_IDENTIFIER, mClientDuid);
        addTlv(packet, DHCP6_SERVER_IDENTIFIER, mServerDuid);
        final ByteBuffer iaAddressOption = IaAddressOption.build(
                (short) IaAddressOption.LENGTH, mIaAddress, mPreferred, mValid);
        addTlv(packet, iaAddressOption);

        packet.flip();
        return packet;
    }
}
