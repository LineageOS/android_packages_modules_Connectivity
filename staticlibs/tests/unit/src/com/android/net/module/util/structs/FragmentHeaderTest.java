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

package com.android.net.module.util.structs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class FragmentHeaderTest {
    private static final byte[] HEADER_BYTES = new byte[] {
        17, /* nextHeader  */
        0, /* reserved */
        15, 1, /* fragmentOffset */
        1, 2, 3, 4 /* identification */
    };

    @Test
    public void testConstructor() {
        FragmentHeader fragHdr = new FragmentHeader((short) 10 /* nextHeader */,
                (byte) 11 /* reserved */,
                12 /* fragmentOffset */,
                13 /* identification */);

        assertEquals(10, fragHdr.nextHeader);
        assertEquals(11, fragHdr.reserved);
        assertEquals(12, fragHdr.fragmentOffset);
        assertEquals(13, fragHdr.identification);
    }

    @Test
    public void testParseFragmentHeader() {
        final ByteBuffer buf = ByteBuffer.wrap(HEADER_BYTES);
        buf.order(ByteOrder.BIG_ENDIAN);
        FragmentHeader fragHdr = FragmentHeader.parse(FragmentHeader.class, buf);

        assertEquals(17, fragHdr.nextHeader);
        assertEquals(0, fragHdr.reserved);
        assertEquals(0xF01, fragHdr.fragmentOffset);
        assertEquals(0x1020304, fragHdr.identification);
    }

    @Test
    public void testWriteToBytes() {
        FragmentHeader fragHdr = new FragmentHeader((short) 17 /* nextHeader */,
                (byte) 0 /* reserved */,
                0xF01 /* fragmentOffset */,
                0x1020304 /* identification */);

        byte[] bytes = fragHdr.writeToBytes(ByteOrder.BIG_ENDIAN);

        assertArrayEquals("bytes = " + Arrays.toString(bytes), HEADER_BYTES, bytes);
    }
}
