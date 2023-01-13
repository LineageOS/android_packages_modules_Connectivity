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

package com.android.server.connectivity.mdns

import android.net.InetAddresses.parseNumericAddress
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.HandlerThread
import com.android.server.connectivity.mdns.MdnsRecordRepository.Dependencies
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.net.NetworkInterface
import java.util.Collections
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_SERVICE_ID_1 = 42
private const val TEST_SERVICE_ID_2 = 43
private const val TEST_PORT = 12345
private val TEST_HOSTNAME = arrayOf("Android_000102030405060708090A0B0C0D0E0F", "local")
private val TEST_ADDRESSES = arrayOf(
        parseNumericAddress("192.0.2.111"),
        parseNumericAddress("2001:db8::111"),
        parseNumericAddress("2001:db8::222"))

private val TEST_SERVICE_1 = NsdServiceInfo().apply {
    serviceType = "_testservice._tcp"
    serviceName = "MyTestService"
    port = TEST_PORT
}

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsRecordRepositoryTest {
    private val thread = HandlerThread(MdnsRecordRepositoryTest::class.simpleName)
    private val deps = object : Dependencies() {
        override fun getHostname() = TEST_HOSTNAME
        override fun getInterfaceInetAddresses(iface: NetworkInterface) =
                Collections.enumeration(TEST_ADDRESSES.toList())
    }

    @Before
    fun setUp() {
        thread.start()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    @Test
    fun testAddServiceAndProbe() {
        val repository = MdnsRecordRepository(thread.looper, deps)
        assertEquals(0, repository.servicesCount)
        assertEquals(-1, repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1))
        assertEquals(1, repository.servicesCount)

        val probingInfo = repository.setServiceProbing(TEST_SERVICE_ID_1)
        assertNotNull(probingInfo)
        assertTrue(repository.isProbing(TEST_SERVICE_ID_1))

        assertEquals(TEST_SERVICE_ID_1, probingInfo.serviceId)
        val packet = probingInfo.getPacket(0)

        assertEquals(MdnsConstants.FLAGS_QUERY, packet.flags)
        assertEquals(0, packet.answers.size)
        assertEquals(0, packet.additionalRecords.size)

        assertEquals(1, packet.questions.size)
        val expectedName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        assertEquals(MdnsAnyRecord(expectedName, false /* unicast */), packet.questions[0])

        assertEquals(1, packet.authorityRecords.size)
        assertEquals(MdnsServiceRecord(expectedName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                120_000L /* ttlMillis */,
                0 /* servicePriority */, 0 /* serviceWeight */,
                TEST_PORT, TEST_HOSTNAME), packet.authorityRecords[0])

        assertContentEquals(intArrayOf(TEST_SERVICE_ID_1), repository.clearServices())
    }

    @Test
    fun testAddAndConflicts() {
        val repository = MdnsRecordRepository(thread.looper, deps)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        assertFailsWith(NameConflictException::class) {
            repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_1)
        }
    }

    @Test
    fun testExitingServiceReAdded() {
        val repository = MdnsRecordRepository(thread.looper, deps)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        repository.exitService(TEST_SERVICE_ID_1)

        assertEquals(TEST_SERVICE_ID_1, repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_1))
        assertEquals(1, repository.servicesCount)

        repository.removeService(TEST_SERVICE_ID_2)
        assertEquals(0, repository.servicesCount)
    }
}
