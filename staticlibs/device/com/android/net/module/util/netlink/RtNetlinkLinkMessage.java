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

package com.android.net.module.util.netlink;

import static android.system.OsConstants.AF_UNSPEC;

import static com.android.net.module.util.NetworkStackConstants.ETHER_ADDR_LEN;
import static com.android.net.module.util.netlink.NetlinkConstants.IFF_UP;
import static com.android.net.module.util.netlink.NetlinkConstants.RTM_GETLINK;
import static com.android.net.module.util.netlink.NetlinkConstants.RTM_NEWLINK;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_REQUEST_ACK;

import android.net.MacAddress;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A NetlinkMessage subclass for rtnetlink link messages.
 *
 * RtNetlinkLinkMessage.parse() must be called with a ByteBuffer that contains exactly one netlink
 * message.
 *
 * see also:
 *
 *     include/uapi/linux/rtnetlink.h
 *
 * @hide
 */
public class RtNetlinkLinkMessage extends NetlinkMessage {
    public static final short IFLA_ADDRESS   = 1;
    public static final short IFLA_IFNAME    = 3;
    public static final short IFLA_MTU       = 4;
    public static final short IFLA_INET6_ADDR_GEN_MODE = 8;
    public static final short IFLA_AF_SPEC = 26;

    public static final short IN6_ADDR_GEN_MODE_NONE = 1;

    // The maximum buffer size to hold an interface name including the null-terminator '\0'.
    private static final int IFNAMSIZ = 16;
    // The default value of MTU, which means the MTU is unspecified.
    private static final int DEFAULT_MTU = 0;

    @NonNull
    private final StructIfinfoMsg mIfinfomsg;
    private final int mMtu;
    @Nullable
    private final MacAddress mHardwareAddress;
    @Nullable
    private final String mInterfaceName;

    /**
     * Creates an {@link RtNetlinkLinkMessage} instance.
     *
     * <p>This method validates the arguments and returns {@code null} if any of them are invalid.
     * nlmsghdr's nlmsg_len will be updated to the correct length before creation.
     *
     * @param nlmsghdr The Netlink message header. Must not be {@code null}.
     * @param ifinfomsg The interface information message. Must not be {@code null}.
     * @param mtu The Maximum Transmission Unit (MTU) value for the link.
     * @param hardwareAddress The hardware address (MAC address) of the link. May be {@code null}.
     * @param interfaceName The name of the interface. May be {@code null}.
     * @return A new {@link RtNetlinkLinkMessage} instance, or {@code null} if the input arguments
     *         are invalid.
     */
    @Nullable
    public static RtNetlinkLinkMessage build(@NonNull StructNlMsgHdr nlmsghdr,
            @NonNull StructIfinfoMsg ifinfomsg, int mtu, @Nullable MacAddress hardwareAddress,
            @Nullable String interfaceName) {
        if (mtu < 0) {
            return null;
        }
        if (interfaceName != null
                && (interfaceName.isEmpty() || interfaceName.length() + 1 > IFNAMSIZ)) {
            return null;
        }

        nlmsghdr.nlmsg_len = calculateMessageLength(mtu, hardwareAddress, interfaceName);
        return new RtNetlinkLinkMessage(nlmsghdr, ifinfomsg, mtu, hardwareAddress, interfaceName);
    }

    private RtNetlinkLinkMessage(@NonNull StructNlMsgHdr nlmsghdr,
            @NonNull StructIfinfoMsg ifinfomsg, int mtu, @Nullable MacAddress hardwareAddress,
            @Nullable String interfaceName) {
        super(nlmsghdr);
        mIfinfomsg = ifinfomsg;
        mMtu = mtu;
        mHardwareAddress = hardwareAddress;
        mInterfaceName = interfaceName;
    }

    public int getMtu() {
        return mMtu;
    }

    @NonNull
    public StructIfinfoMsg getIfinfoHeader() {
        return mIfinfomsg;
    }

    @Nullable
    public MacAddress getHardwareAddress() {
        return mHardwareAddress;
    }

    @Nullable
    public String getInterfaceName() {
        return mInterfaceName;
    }

    /**
     * Parse rtnetlink link message from {@link ByteBuffer}. This method must be called with a
     * ByteBuffer that contains exactly one netlink message.
     *
     * @param header netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes.
     */
    @Nullable
    public static RtNetlinkLinkMessage parse(@NonNull final StructNlMsgHdr header,
            @NonNull final ByteBuffer byteBuffer) {
        final StructIfinfoMsg ifinfoMsg = StructIfinfoMsg.parse(byteBuffer);
        if (ifinfoMsg == null) {
            return null;
        }

        // IFLA_MTU
        int mtu = DEFAULT_MTU;
        final int baseOffset = byteBuffer.position();
        StructNlAttr nlAttr = StructNlAttr.findNextAttrOfType(IFLA_MTU, byteBuffer);
        if (nlAttr != null) {
            mtu = nlAttr.getValueAsInt(DEFAULT_MTU);
        }

        // IFLA_ADDRESS
        MacAddress hardwareAddress = null;
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(IFLA_ADDRESS, byteBuffer);
        if (nlAttr != null) {
            hardwareAddress = nlAttr.getValueAsMacAddress();
        }

        // IFLA_IFNAME
        String interfaceName = null;
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(IFLA_IFNAME, byteBuffer);
        if (nlAttr != null) {
            interfaceName = nlAttr.getValueAsString();
        }

        return new RtNetlinkLinkMessage(header, ifinfoMsg, mtu, hardwareAddress, interfaceName);
    }

    /**
     *  Write a rtnetlink link message to {@link byte} array.
     */
    public byte[] pack(ByteOrder order) {
        byte[] bytes = new byte[mHeader.nlmsg_len];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
        pack(buffer);
        return bytes;
    }

    /**
     * Write a rtnetlink link message to {@link ByteBuffer}.
     */
    @VisibleForTesting
    protected void pack(ByteBuffer byteBuffer) {
        mHeader.pack(byteBuffer);
        mIfinfomsg.pack(byteBuffer);

        if (mMtu != DEFAULT_MTU) {
            final StructNlAttr mtu = new StructNlAttr(IFLA_MTU, mMtu);
            mtu.pack(byteBuffer);
        }
        if (mHardwareAddress != null) {
            final StructNlAttr hardwareAddress = new StructNlAttr(IFLA_ADDRESS, mHardwareAddress);
            hardwareAddress.pack(byteBuffer);
        }
        if (mInterfaceName != null) {
            final StructNlAttr ifname = new StructNlAttr(IFLA_IFNAME, mInterfaceName);
            ifname.pack(byteBuffer);
        }
    }

    /**
     *  Calculate the byte length of the packed buffer.
     */
    private static int calculateMessageLength(int mtu, MacAddress hardwareAddress,
            String interfaceName) {
        int length = StructNlMsgHdr.STRUCT_SIZE + StructIfinfoMsg.STRUCT_SIZE;

        if (mtu != DEFAULT_MTU) {
            length += NetlinkConstants.alignedLengthOf(StructNlAttr.NLA_HEADERLEN + Integer.BYTES);
        }
        if (hardwareAddress != null) {
            length += NetlinkConstants.alignedLengthOf(
                    StructNlAttr.NLA_HEADERLEN + ETHER_ADDR_LEN);
        }
        if (interfaceName != null) {
            length += NetlinkConstants.alignedLengthOf(
                    // The string should be end with '\0', so the length should plus 1.
                    StructNlAttr.NLA_HEADERLEN + interfaceName.length() + 1);
        }

        return length;
    }

    /**
     * Create a link message to set the operational state (up or down) of a network interface.
     *
     * @param ifIndex  The network interface index of the network interface to set state.
     * @param sequenceNumber The sequence number to use for the Netlink message.
     * @param isUp           {@code true} to set the interface up, {@code false} to set it down.
     * @return A `RtNetlinkLinkMessage` instance configured to set the link state, or return null
     *         in case of an error.
     */
    @Nullable
    public static RtNetlinkLinkMessage createSetLinkStateMessage(int ifIndex,
            int sequenceNumber, boolean isUp) {
        if (ifIndex <= 0) {
            return null;
        }

        return RtNetlinkLinkMessage.build(
                new StructNlMsgHdr(0, RTM_NEWLINK, NLM_F_REQUEST_ACK, sequenceNumber),
                new StructIfinfoMsg((short) AF_UNSPEC, (short) 0, ifIndex,
                                    isUp ? IFF_UP : 0, IFF_UP), DEFAULT_MTU, null, null);
    }

    /**
     * Create a link message to rename the network interface.
     *
     * @param ifIndex  The network interface index of the network interface to rename.
     * @param sequenceNumber The sequence number to use for the Netlink message.
     * @param newName        The new name of the network interface.
     * @return A `RtNetlinkLinkMessage` instance configured to rename the network interface,
     *         or return null in case of an error.
     */
    @Nullable
    public static RtNetlinkLinkMessage createSetLinkNameMessage(int ifIndex,
            int sequenceNumber, @NonNull String newName) {
        if (ifIndex <= 0) {
            return null;
        }

        return RtNetlinkLinkMessage.build(
                new StructNlMsgHdr(0, RTM_NEWLINK, NLM_F_REQUEST_ACK, sequenceNumber),
                new StructIfinfoMsg((short) AF_UNSPEC, (short) 0, ifIndex, 0, 0),
                DEFAULT_MTU, null, newName);
    }

    /**
     * Creates an {@link RtNetlinkLinkMessage} instance that can be used to get the link information
     * of a network interface.
     *
     * @param ifIndex The index of the network interface to query.
     * @param sequenceNumber The sequence number for the Netlink message.
     * @return An `RtNetlinkLinkMessage` instance representing the request to query the interface,
     *         or return null in case of an error.
     */
    @Nullable
    public static RtNetlinkLinkMessage createGetLinkMessage(int ifIndex,
            int sequenceNumber) {
        if (ifIndex <= 0) {
            return null;
        }

        return RtNetlinkLinkMessage.build(
                new StructNlMsgHdr(0, RTM_GETLINK, NLM_F_REQUEST_ACK, sequenceNumber),
                new StructIfinfoMsg((short) AF_UNSPEC, (short) 0, ifIndex, 0, 0),
                DEFAULT_MTU, null, null);
    }

    /**
     * Creates an {@link RtNetlinkLinkMessage} instance that can be used to set the flags of a
     * network interface.
     *
     * @param ifIndex The index of the network interface to configure.
     * @param sequenceNumber The sequence number for the Netlink message.
     * @param flags power-of-two integer flags to set or unset. A flag to set should be passed as
     *        is as a power-of-two value, and a flag to remove should be passed inversed as -1 with
     *        a single bit down. For example: IFF_UP, ~IFF_BROADCAST...
     * @return An `RtNetlinkLinkMessage` instance representing the request to query the interface,
     *         or return null in case of an error.
     */
    @Nullable
    public static RtNetlinkLinkMessage createSetFlagsMessage(int ifIndex,
            int sequenceNumber, int... flags) {
        if (ifIndex <= 0) {
            return null;
        }

        int flagsBits = 0;
        int changeBits = 0;
        for (int f : flags) {
            if (Integer.bitCount(f) == 1) {
                flagsBits |= f;
                changeBits |= f;
            } else if (Integer.bitCount(~f) == 1) {
                flagsBits &= f;
                changeBits |= ~f;
            } else {
                return null;
            }
        }
        // RTM_NEWLINK is used here for create, modify, or notify changes about a internet
        // interface, including change in administrative state. While RTM_SETLINK is used to
        // modify an existing link rather than creating a new one.
        return RtNetlinkLinkMessage.build(
                new StructNlMsgHdr(
                        /*payloadLen*/ 0, RTM_NEWLINK, NLM_F_REQUEST_ACK, sequenceNumber),
                new StructIfinfoMsg((short) AF_UNSPEC, /*type*/ 0, ifIndex,
                        flagsBits, changeBits),
                DEFAULT_MTU, /*hardwareAddress*/ null, /*interfaceName*/ null);
    }

    /**
     * Creates an {@link RtNetlinkLinkMessage} instance that can be used to set the MTU of a
     * network interface.
     *
     * @param ifIndex The index of the network interface to configure.
     * @param sequenceNumber The sequence number for the Netlink message.
     * @param mtu MTU value to set for the interface.
     * @return An `RtNetlinkLinkMessage` instance representing the request to query the interface,
     *         or return null in case of an error.
     */
    @Nullable
    public static RtNetlinkLinkMessage createSetMtuMessage(int ifIndex,
            int sequenceNumber, int mtu) {
        if (ifIndex <= 0) {
            return null;
        }
        return RtNetlinkLinkMessage.build(
            new StructNlMsgHdr(/*payloadLen*/ 0, RTM_NEWLINK, NLM_F_REQUEST_ACK , sequenceNumber),
            new StructIfinfoMsg((short) AF_UNSPEC, /*type*/ 0, ifIndex,
                /*flags*/ 0, /*change*/ 0),
            mtu, /*hardwareAddress*/ null, /*interfaceName*/ null);
    }

    @Override
    public String toString() {
        return "RtNetlinkLinkMessage{ "
                + "nlmsghdr{" + mHeader.toString(OsConstants.NETLINK_ROUTE) + "}, "
                + "Ifinfomsg{" + mIfinfomsg + "}, "
                + "Hardware Address{" + mHardwareAddress + "}, "
                + "MTU{" + mMtu + "}, "
                + "Ifname{" + mInterfaceName + "} "
                + "}";
    }
}
