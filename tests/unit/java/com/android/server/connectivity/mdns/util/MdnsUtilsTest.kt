/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package com.android.server.connectivity.mdns.util

import android.os.Build
import com.android.server.connectivity.mdns.MdnsConstants
import com.android.server.connectivity.mdns.MdnsConstants.FLAG_TRUNCATED
import com.android.server.connectivity.mdns.MdnsPacket
import com.android.server.connectivity.mdns.MdnsPacketReader
import com.android.server.connectivity.mdns.MdnsPointerRecord
import com.android.server.connectivity.mdns.MdnsRecord
import com.android.server.connectivity.mdns.util.MdnsUtils.createQueryDatagramPackets
import com.android.server.connectivity.mdns.util.MdnsUtils.equalsDnsLabelIgnoreDnsCase
import com.android.server.connectivity.mdns.util.MdnsUtils.equalsIgnoreDnsCase
import com.android.server.connectivity.mdns.util.MdnsUtils.toDnsLabelsLowerCase
import com.android.server.connectivity.mdns.util.MdnsUtils.toDnsLowerCase
import com.android.server.connectivity.mdns.util.MdnsUtils.truncateServiceName
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.net.DatagramPacket
import kotlin.test.assertContentEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsUtilsTest {
    @Test
    fun testToDnsLowerCase() {
        assertEquals("test", toDnsLowerCase("TEST"))
        assertEquals("test", toDnsLowerCase("TeSt"))
        assertEquals("test", toDnsLowerCase("test"))
        assertEquals("tÉst", toDnsLowerCase("TÉST"))
        assertEquals("ţést", toDnsLowerCase("ţést"))
        // Unicode characters 0x10000 (𐀀), 0x10001 (𐀁), 0x10041 (𐁁)
        // Note the last 2 bytes of 0x10041 are identical to 'A', but it should remain unchanged.
        assertEquals("test: -->\ud800\udc00 \ud800\udc01 \ud800\udc41<-- ",
                toDnsLowerCase("Test: -->\ud800\udc00 \ud800\udc01 \ud800\udc41<-- "))
        // Also test some characters where the first surrogate is not \ud800
        assertEquals("test: >\ud83c\udff4\udb40\udc67\udb40\udc62\udb40" +
                "\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f<",
                toDnsLowerCase("Test: >\ud83c\udff4\udb40\udc67\udb40\udc62\udb40" +
                        "\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f<"))
    }

    @Test
    fun testToDnsLabelsLowerCase() {
        assertArrayEquals(arrayOf("test", "tÉst", "ţést"),
            toDnsLabelsLowerCase(arrayOf("TeSt", "TÉST", "ţést")))
    }

    @Test
    fun testEqualsIgnoreDnsCase() {
        assertTrue(equalsIgnoreDnsCase("TEST", "Test"))
        assertTrue(equalsIgnoreDnsCase("TEST", "test"))
        assertTrue(equalsIgnoreDnsCase("test", "TeSt"))
        assertTrue(equalsIgnoreDnsCase("Tést", "tést"))
        assertFalse(equalsIgnoreDnsCase("ŢÉST", "ţést"))
        // Unicode characters 0x10000 (𐀀), 0x10001 (𐀁), 0x10041 (𐁁)
        // Note the last 2 bytes of 0x10041 are identical to 'A', but it should remain unchanged.
        assertTrue(equalsIgnoreDnsCase("test: -->\ud800\udc00 \ud800\udc01 \ud800\udc41<-- ",
                "Test: -->\ud800\udc00 \ud800\udc01 \ud800\udc41<-- "))
        // Also test some characters where the first surrogate is not \ud800
        assertTrue(equalsIgnoreDnsCase("test: >\ud83c\udff4\udb40\udc67\udb40\udc62\udb40" +
                "\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f<",
                "Test: >\ud83c\udff4\udb40\udc67\udb40\udc62\udb40" +
                        "\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f<"))
    }

    @Test
    fun testTruncateServiceName() {
        assertEquals(truncateServiceName("测试abcde", 7), "测试a")
        assertEquals(truncateServiceName("测试abcde", 100), "测试abcde")
    }

    @Test
    fun testEqualsLabelIgnoreDnsCase() {
        assertTrue(equalsDnsLabelIgnoreDnsCase(arrayOf("TEST", "Test"), arrayOf("test", "test")))
        assertFalse(equalsDnsLabelIgnoreDnsCase(arrayOf("TEST", "Test"), arrayOf("test")))
        assertFalse(equalsDnsLabelIgnoreDnsCase(arrayOf("Test"), arrayOf("test", "test")))
        assertFalse(equalsDnsLabelIgnoreDnsCase(arrayOf("TEST", "Test"), arrayOf("test", "tést")))
    }

    @Test
    fun testTypeEqualsOrIsSubtype() {
        assertTrue(MdnsUtils.typeEqualsOrIsSubtype(arrayOf("_type", "_tcp", "local"),
            arrayOf("_type", "_TCP", "local")))
        assertTrue(MdnsUtils.typeEqualsOrIsSubtype(arrayOf("_type", "_tcp", "local"),
            arrayOf("a", "_SUB", "_type", "_TCP", "local")))
        assertFalse(MdnsUtils.typeEqualsOrIsSubtype(arrayOf("_sub", "_type", "_tcp", "local"),
                arrayOf("_type", "_TCP", "local")))
        assertFalse(MdnsUtils.typeEqualsOrIsSubtype(
                arrayOf("a", "_other", "_type", "_tcp", "local"),
                arrayOf("a", "_SUB", "_type", "_TCP", "local")))
    }

    @Test
    fun testCreateQueryDatagramPackets() {
        // Question data bytes:
        // Name label(17)(duplicated labels) + PTR type(2) + cacheFlush(2) = 21
        //
        // Known answers data bytes:
        // Name label(17)(duplicated labels) + PTR type(2) + cacheFlush(2) + receiptTimeMillis(4)
        // + Data length(2) + Pointer data(18)(duplicated labels) = 45
        val questions = mutableListOf<MdnsRecord>()
        val knownAnswers = mutableListOf<MdnsRecord>()
        for (i in 1..100) {
            questions.add(MdnsPointerRecord(arrayOf("_testservice$i", "_tcp", "local"), false))
            knownAnswers.add(MdnsPointerRecord(
                    arrayOf("_testservice$i", "_tcp", "local"),
                    0L,
                    false,
                    4_500_000L,
                    arrayOf("MyTestService$i", "_testservice$i", "_tcp", "local")
            ))
        }
        // MdnsPacket data bytes:
        // Questions(21 * 100) + Answers(45 * 100) = 6600 -> at least 5 packets
        val query = MdnsPacket(
                MdnsConstants.FLAGS_QUERY,
                questions as List<MdnsRecord>,
                knownAnswers as List<MdnsRecord>,
                emptyList(),
                emptyList()
        )
        // Expect the oversize MdnsPacket to be separated into 5 DatagramPackets.
        val bufferSize = 1500
        val packets = createQueryDatagramPackets(
                ByteArray(bufferSize),
                query,
                MdnsConstants.IPV4_SOCKET_ADDR
        )
        assertEquals(5, packets.size)
        assertTrue(packets.all { packet -> packet.length < bufferSize })

        val mdnsPacket = createMdnsPacketFromMultipleDatagramPackets(packets)
        assertEquals(query.flags, mdnsPacket.flags)
        assertContentEquals(query.questions, mdnsPacket.questions)
        assertContentEquals(query.answers, mdnsPacket.answers)
    }

    private fun createMdnsPacketFromMultipleDatagramPackets(
            packets: List<DatagramPacket>
    ): MdnsPacket {
        var flags = 0
        val questions = mutableListOf<MdnsRecord>()
        val answers = mutableListOf<MdnsRecord>()
        for ((index, packet) in packets.withIndex()) {
            val mdnsPacket = MdnsPacket.parse(MdnsPacketReader(packet))
            if (index != packets.size - 1) {
                assertTrue((mdnsPacket.flags and FLAG_TRUNCATED) == FLAG_TRUNCATED)
            }
            flags = mdnsPacket.flags
            questions.addAll(mdnsPacket.questions)
            answers.addAll(mdnsPacket.answers)
        }
        return MdnsPacket(flags, questions, answers, emptyList(), emptyList())
    }
}
