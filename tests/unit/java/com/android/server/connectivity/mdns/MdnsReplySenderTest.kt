/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.net.InetAddresses
import android.net.LinkAddress
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import com.android.net.module.util.SharedLog
import com.android.server.connectivity.mdns.MdnsConstants.IPV4_SOCKET_ADDR
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.argThat
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify

private const val TEST_PORT = 12345
private const val DEFAULT_TIMEOUT_MS = 2000L
private const val LONG_TTL = 4_500_000L
private const val SHORT_TTL = 120_000L

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsReplySenderTest {
    private val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
    private val serviceType = arrayOf("_testservice", "_tcp", "local")
    private val hostname = arrayOf("Android_000102030405060708090A0B0C0D0E0F", "local")
    private val hostAddresses = listOf(
            LinkAddress(InetAddresses.parseNumericAddress("192.0.2.111"), 24),
            LinkAddress(InetAddresses.parseNumericAddress("2001:db8::111"), 64),
            LinkAddress(InetAddresses.parseNumericAddress("2001:db8::222"), 64))
    private val answers = listOf(
            MdnsPointerRecord(serviceType, 0L /* receiptTimeMillis */, false /* cacheFlush */,
                    LONG_TTL, serviceName))
    private val additionalAnswers = listOf(
            MdnsTextRecord(serviceName, 0L /* receiptTimeMillis */, true /* cacheFlush */, LONG_TTL,
                    listOf() /* entries */),
            MdnsServiceRecord(serviceName, 0L /* receiptTimeMillis */, true /* cacheFlush */,
                    SHORT_TTL, 0 /* servicePriority */, 0 /* serviceWeight */, TEST_PORT, hostname),
            MdnsInetAddressRecord(hostname, 0L /* receiptTimeMillis */, true /* cacheFlush */,
                    SHORT_TTL, hostAddresses[0].address),
            MdnsInetAddressRecord(hostname, 0L /* receiptTimeMillis */, true /* cacheFlush */,
                    SHORT_TTL, hostAddresses[1].address),
            MdnsInetAddressRecord(hostname, 0L /* receiptTimeMillis */, true /* cacheFlush */,
                    SHORT_TTL, hostAddresses[2].address),
            MdnsNsecRecord(serviceName, 0L /* receiptTimeMillis */, true /* cacheFlush */, LONG_TTL,
                    serviceName /* nextDomain */,
                    intArrayOf(MdnsRecord.TYPE_TXT, MdnsRecord.TYPE_SRV)),
            MdnsNsecRecord(hostname, 0L /* receiptTimeMillis */, true /* cacheFlush */, SHORT_TTL,
                    hostname /* nextDomain */, intArrayOf(MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA)))
    private val thread = HandlerThread(MdnsReplySenderTest::class.simpleName)
    private val socket = mock(MdnsInterfaceSocket::class.java)
    private val buffer = ByteArray(1500)
    private val sharedLog = SharedLog(MdnsReplySenderTest::class.simpleName)
    private val deps = mock(MdnsReplySender.Dependencies::class.java)
    private val handler by lazy { Handler(thread.looper) }
    private val replySender by lazy {
        MdnsReplySender(thread.looper, socket, buffer, sharedLog, false /* enableDebugLog */, deps)
    }

    @Before
    fun setUp() {
        thread.start()
        doReturn(true).`when`(socket).hasJoinedIpv4()
        doReturn(true).`when`(socket).hasJoinedIpv6()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    private fun <T> runningOnHandlerAndReturn(functor: (() -> T)): T {
        val future = CompletableFuture<T>()
        handler.post {
            future.complete(functor())
        }
        return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun sendNow(packet: MdnsPacket, destination: InetSocketAddress):
            Unit = runningOnHandlerAndReturn { replySender.sendNow(packet, destination) }

    private fun queueReply(reply: MdnsReplyInfo):
            Unit = runningOnHandlerAndReturn { replySender.queueReply(reply) }

    @Test
    fun testSendNow() {
        val packet = MdnsPacket(0x8400,
                listOf() /* questions */,
                answers,
                listOf() /* authorityRecords */,
                additionalAnswers)
        sendNow(packet, IPV4_SOCKET_ADDR)
        verify(socket).send(argThat{ it.socketAddress.equals(IPV4_SOCKET_ADDR) })
    }

    @Test
    fun testQueueReply() {
        val reply = MdnsReplyInfo(answers, additionalAnswers, 20L /* sendDelayMs */,
                IPV4_SOCKET_ADDR)
        val handlerCaptor = ArgumentCaptor.forClass(Handler::class.java)
        val messageCaptor = ArgumentCaptor.forClass(Message::class.java)
        queueReply(reply)
        verify(deps).sendMessageDelayed(handlerCaptor.capture(), messageCaptor.capture(), eq(20L))

        val realHandler = handlerCaptor.value
        val delayMessage = messageCaptor.value
        realHandler.sendMessage(delayMessage)
        verify(socket, timeout(DEFAULT_TIMEOUT_MS)).send(argThat{
            it.socketAddress.equals(IPV4_SOCKET_ADDR)
        })
    }
}
