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

import static com.android.net.module.util.NetworkStackConstants.INFINITE_LEASE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.net.IpPrefix;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.structs.PrefixInformationOption;

import libcore.util.HexEncoding;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class StructNdOptPioTest {
    private static final IpPrefix TEST_PREFIX = new IpPrefix("2a00:79e1:abc:f605::/64");
    private static final byte TEST_PIO_FLAGS_P_UNSET = (byte) 0xC0; // L=1,A=1
    private static final byte TEST_PIO_FLAGS_P_SET   = (byte) 0xD0; // L=1,A=1,P=1
    private static final String PIO_BYTES =
            "0304"                                // type=3, length=4
            + "40"                                // prefix length=64
            + "C0"                                // L=1,A=1
            + "00278D00"                          // valid=259200
            + "00093A80"                          // preferred=604800
            + "00000000"                          // Reserved2
            + "2A0079E10ABCF6050000000000000000"; // prefix=2a00:79e1:abc:f605::

    private static final String PIO_WITH_P_FLAG_BYTES =
            "0304"                                // type=3, length=4
            + "40"                                // prefix length=64
            + "D0"                                // L=1,A=1,P=1
            + "00278D00"                          // valid=2592000
            + "00093A80"                          // preferred=604800
            + "00000000"                          // Reserved2
            + "2A0079E10ABCF6050000000000000000"; // prefix=2a00:79e1:abc:f605::

    private static final String PIO_WITH_P_FLAG_INFINITY_LIFETIME_BYTES =
            "0304"                                // type=3, length=4
            + "40"                                // prefix length=64
            + "D0"                                // L=1,A=1,P=1
            + "FFFFFFFF"                          // valid=infinity
            + "FFFFFFFF"                          // preferred=infintiy
            + "00000000"                          // Reserved2
            + "2A0079E10ABCF6050000000000000000"; // prefix=2a00:79e1:abc:f605::

    private static void assertPioOptMatches(final StructNdOptPio opt, int length, byte flags,
            long preferred, long valid, final IpPrefix prefix) {
        assertEquals(StructNdOptPio.TYPE, opt.type);
        assertEquals(length, opt.length);
        assertEquals(flags, opt.flags);
        assertEquals(preferred, opt.preferred);
        assertEquals(valid, opt.valid);
        assertEquals(prefix, opt.prefix);
    }

    private static void assertToByteBufferMatches(final StructNdOptPio opt, final String expected) {
        String actual = HexEncoding.encodeToString(opt.toByteBuffer().array());
        assertEquals(expected, actual);
    }

    private static void doPioParsingTest(final String optionHexString, int length, byte flags,
            long preferred, long valid, final IpPrefix prefix) {
        final byte[] rawBytes = HexEncoding.decode(optionHexString);
        final StructNdOptPio opt = StructNdOptPio.parse(ByteBuffer.wrap(rawBytes));
        assertPioOptMatches(opt, length, flags, preferred, valid, prefix);
        assertToByteBufferMatches(opt, optionHexString);
    }

    @Test
    public void testParsingPioWithoutPFlag() {
        doPioParsingTest(PIO_BYTES, 4 /* length */, TEST_PIO_FLAGS_P_UNSET,
                604800 /* preferred */, 2592000 /* valid */, TEST_PREFIX);
    }

    @Test
    public void testParsingPioWithPFlag() {
        doPioParsingTest(PIO_WITH_P_FLAG_BYTES, 4 /* length */, TEST_PIO_FLAGS_P_SET,
                604800 /* preferred */, 2592000 /* valid */, TEST_PREFIX);
    }

    @Test
    public void testParsingPioWithPFlag_infinityLifetime() {
        doPioParsingTest(PIO_WITH_P_FLAG_INFINITY_LIFETIME_BYTES, 4 /* length */,
                TEST_PIO_FLAGS_P_SET,
                Integer.toUnsignedLong(INFINITE_LEASE) /* preferred */,
                Integer.toUnsignedLong(INFINITE_LEASE) /* valid */,
                TEST_PREFIX);
    }

    @Test
    public void testToByteBuffer() {
        final StructNdOptPio pio =
                new StructNdOptPio(TEST_PIO_FLAGS_P_UNSET, 604800 /* preferred */,
                        2592000 /* valid */, TEST_PREFIX);
        assertToByteBufferMatches(pio, PIO_BYTES);
    }

    @Test
    public void testToByteBuffer_withPFlag() {
        final StructNdOptPio pio =
                new StructNdOptPio(TEST_PIO_FLAGS_P_SET, 604800 /* preferred */,
                        2592000 /* valid */, TEST_PREFIX);
        assertToByteBufferMatches(pio, PIO_WITH_P_FLAG_BYTES);
    }

    @Test
    public void testToByteBuffer_infinityLifetime() {
        final StructNdOptPio pio =
                new StructNdOptPio(TEST_PIO_FLAGS_P_SET,
                        Integer.toUnsignedLong(INFINITE_LEASE) /* preferred */,
                        Integer.toUnsignedLong(INFINITE_LEASE) /* valid */, TEST_PREFIX);
        assertToByteBufferMatches(pio, PIO_WITH_P_FLAG_INFINITY_LIFETIME_BYTES);
    }

    private static ByteBuffer makePioOption(byte type, byte length, byte prefixLen, byte flags,
            long valid, long preferred, final byte[] prefix) {
        final PrefixInformationOption pio = new PrefixInformationOption(type, length, prefixLen,
                flags, valid, preferred, 0 /* reserved */, prefix);
        return ByteBuffer.wrap(pio.writeToBytes(ByteOrder.BIG_ENDIAN));
    }

    @Test
    public void testParsing_invalidOptionType() {
        final ByteBuffer buf = makePioOption((byte) 24 /* wrong type:RIO */,
                (byte) 4 /* length */, (byte) 64 /* prefixLen */, TEST_PIO_FLAGS_P_SET,
                2592000 /* valid */, 604800 /* preferred */, TEST_PREFIX.getRawAddress());
        assertNull(StructNdOptPio.parse(buf));
    }

    @Test
    public void testParsing_invalidOptionLength() {
        final ByteBuffer buf = makePioOption((byte) 24 /* wrong type:RIO */,
                (byte) 3 /* wrong length */, (byte) 64 /* prefixLen */,
                TEST_PIO_FLAGS_P_SET, 2592000 /* valid */, 604800 /* preferred */,
                TEST_PREFIX.getRawAddress());
        assertNull(StructNdOptPio.parse(buf));
    }

    @Test
    public void testParsing_truncatedByteBuffer() {
        final ByteBuffer buf = makePioOption((byte) 3 /* type */, (byte) 4 /* length */,
                (byte) 64 /* prefixLen */, TEST_PIO_FLAGS_P_SET,
                2592000 /* valid */, 604800 /* preferred */, TEST_PREFIX.getRawAddress());
        final int len = buf.limit();
        for (int i = 0; i < buf.limit() - 1; i++) {
            buf.flip();
            buf.limit(i);
            assertNull("Option truncated to " + i + " bytes, should have returned null",
                    StructNdOptPio.parse(buf));
        }
        buf.flip();
        buf.limit(len);

        final StructNdOptPio opt = StructNdOptPio.parse(buf);
        assertPioOptMatches(opt, (byte) 4 /* length */, TEST_PIO_FLAGS_P_SET,
                604800 /* preferred */, 2592000 /* valid */, TEST_PREFIX);
    }

    @Test
    public void testParsing_invalidByteBufferLength() {
        final ByteBuffer buf = makePioOption((byte) 3 /* type */, (byte) 4 /* length */,
                (byte) 64 /* prefixLen */, TEST_PIO_FLAGS_P_SET,
                2592000 /* valid */, 604800 /* preferred */, TEST_PREFIX.getRawAddress());
        buf.limit(31); // less than 4 * 8
        assertNull(StructNdOptPio.parse(buf));
    }

    @Test
    public void testToString() {
        final ByteBuffer buf = makePioOption((byte) 3 /* type */, (byte) 4 /* length */,
                (byte) 64 /* prefixLen */, TEST_PIO_FLAGS_P_SET,
                2592000 /* valid */, 604800 /* preferred */, TEST_PREFIX.getRawAddress());
        final StructNdOptPio opt = StructNdOptPio.parse(buf);
        final String expected = "NdOptPio"
                + "(flags:D0, preferred lft:604800, valid lft:2592000,"
                + " prefix:2a00:79e1:abc:f605::/64)";
        assertEquals(expected, opt.toString());
    }
}
