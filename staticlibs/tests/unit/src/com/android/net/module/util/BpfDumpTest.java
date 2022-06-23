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

package com.android.net.module.util;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BpfDumpTest {
    @Test
    public void testToBase64EncodedString() {
        final Struct.U32 key = new Struct.U32(123);
        final Struct.U32 value = new Struct.U32(456);

        // Verified in python:
        //   import base64
        //   print(base64.b64encode(b'\x7b\x00\x00\x00')) # key: ewAAAA==
        //   print(base64.b64encode(b'\xc8\x01\x00\x00')) # value: yAEAAA==
        assertEquals("7B000000", HexDump.toHexString(key.writeToBytes()));
        assertEquals("C8010000", HexDump.toHexString(value.writeToBytes()));
        assertEquals("ewAAAA==,yAEAAA==", BpfDump.toBase64EncodedString(key, value));
    }
}
