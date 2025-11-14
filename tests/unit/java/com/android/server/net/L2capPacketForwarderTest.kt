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

package com.android.server.net

import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants.AF_UNIX
import android.system.OsConstants.SHUT_RD
import android.system.OsConstants.SHUT_RDWR
import android.system.OsConstants.SHUT_WR
import android.system.OsConstants.SOCK_SEQPACKET
import android.system.OsConstants.SOL_SOCKET
import android.system.OsConstants.SO_RCVTIMEO
import android.system.OsConstants.SO_SNDTIMEO
import android.system.StructTimeval
import com.android.server.net.L2capPacketForwarder.BluetoothSocketWrapper
import com.android.server.net.L2capPacketForwarder.TunWrapper
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.google.common.truth.Truth.assertThat
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.arrayOf
import kotlin.random.Random
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

private const val TIMEOUT = 1000L

@ConnectivityModuleTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class L2capPacketForwarderTest {
    private lateinit var forwarder: L2capPacketForwarder
    private val tunFds = arrayOf(FileDescriptor(), FileDescriptor())
    private val l2capFds = arrayOf(FileDescriptor(), FileDescriptor())
    private lateinit var l2capInputStream: BluetoothL2capInputStream
    private lateinit var l2capOutputStream: BluetoothL2capOutputStream
    @Mock private lateinit var bluetoothSocket: BluetoothSocket
    @Mock private lateinit var callback: L2capPacketForwarder.ICallback

    private val handlerThread = HandlerThread("L2capPacketForwarderTest thread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /** Imitates the behavior of an L2CAP BluetoothSocket */
    private class BluetoothL2capInputStream(
        val fd: FileDescriptor,
    ) : InputStream() {
        val l2capBuffer = ByteBuffer.wrap(ByteArray(0xffff)).apply {
            limit(0)
        }

        override fun read(): Int {
            throw NotImplementedError("b/391623333: not implemented correctly for L2cap sockets")
        }

        /** See BluetoothSocket#read(buf, off, len) */
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            // If no more bytes are remaining, read from the fd into the intermediate buffer.
            if (l2capBuffer.remaining() == 0) {
                // fillL2capRxBuffer()
                // refill buffer and return - 1
                val backingArray = l2capBuffer.array()
                var bytesRead = 0
                try {
                    bytesRead = Os.read(fd, backingArray, 0 /*off*/, backingArray.size)
                } catch (e: Exception) {
                    // read failed, timed out, or was interrupted
                    // InputStream throws IOException
                    throw IOException(e)
                }
                l2capBuffer.rewind()
                l2capBuffer.limit(bytesRead)
            }

            val bytesToRead = if (len > l2capBuffer.remaining()) l2capBuffer.remaining() else len
            l2capBuffer.get(b, off, bytesToRead)
            return bytesToRead
        }

        override fun available(): Int {
            throw NotImplementedError("b/391623333: not implemented correctly for L2cap sockets")
        }

        override fun close() {
            try {
                Os.shutdown(fd, SHUT_RD)
            } catch (e: Exception) {
                // InputStream throws IOException
                throw IOException(e)
            }
        }
    }

    /** Imitates the behavior of an L2CAP BluetoothSocket */
    private class BluetoothL2capOutputStream(
        val fd: FileDescriptor,
    ) : OutputStream() {

        override fun write(b: Int) {
            throw NotImplementedError("This method does not maintain packet boundaries, do not use")
        }

        /** See BluetoothSocket#write(buf, off, len) */
        override fun write(b: ByteArray, off: Int, len: Int) {
            try {
                Os.write(fd, b, off, len)
            } catch (e: Exception) {
                // OutputStream throws IOException
                throw IOException(e)
            }
        }

        override fun close() {
            try {
                Os.shutdown(fd, SHUT_WR)
            } catch (e: Exception) {
                // OutputStream throws IOException
                throw IOException(e)
            }
        }
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        Os.socketpair(AF_UNIX, SOCK_SEQPACKET, 0, tunFds[0], tunFds[1])
        Os.socketpair(AF_UNIX, SOCK_SEQPACKET, 0, l2capFds[0], l2capFds[1])

        // Set socket i/o timeout for test end.
        Os.setsockoptTimeval(tunFds[1], SOL_SOCKET, SO_RCVTIMEO, StructTimeval.fromMillis(5000))
        Os.setsockoptTimeval(tunFds[1], SOL_SOCKET, SO_SNDTIMEO, StructTimeval.fromMillis(5000))
        Os.setsockoptTimeval(l2capFds[1], SOL_SOCKET, SO_RCVTIMEO, StructTimeval.fromMillis(5000))
        Os.setsockoptTimeval(l2capFds[1], SOL_SOCKET, SO_SNDTIMEO, StructTimeval.fromMillis(5000))

        l2capInputStream = BluetoothL2capInputStream(l2capFds[0])
        l2capOutputStream = BluetoothL2capOutputStream(l2capFds[0])
        doReturn(l2capInputStream).`when`(bluetoothSocket).getInputStream()
        doReturn(l2capOutputStream).`when`(bluetoothSocket).getOutputStream()
        doAnswer({
            l2capInputStream.close()
            l2capOutputStream.close()
            try {
                // libcore's Linux_close properly invalidates the FileDescriptor, so it is safe to
                // close multiple times.
                Os.close(l2capFds[0])
            } catch (e: Exception) {
                // BluetoothSocket#close can be called multiple times. Catch EBADF on subsequent
                // invocations.
            }
        }).`when`(bluetoothSocket).close()

        // TunWrapper does not shutdown fd as tun is not a connected socket, i.e. closing interrupts
        // blocking operations.
        val parcelFd = ParcelFileDescriptor(tunFds[0])
        val tunWrapper = object : TunWrapper(parcelFd) {
            override fun close() {
                Os.shutdown(parcelFd.fileDescriptor, SHUT_RDWR)
                parcelFd.close()
            }
        }

        forwarder = L2capPacketForwarder(
                handler,
                tunWrapper,
                BluetoothSocketWrapper(bluetoothSocket),
                false /* compressHeaders */,
                callback
        )
    }

    @After
    fun tearDown() {
        if (::forwarder.isInitialized) {
            // forwarder closes tunFds[0] and l2capFds[0]
            forwarder.tearDown()
        } else {
            Os.close(tunFds[0])
            Os.close(l2capFds[0])
        }
        Os.close(tunFds[1])
        Os.close(l2capFds[1])

        handlerThread.quitSafely()
        handlerThread.join()
    }

    fun sendPacket(fd: FileDescriptor, size: Int = 1280): ByteArray {
        val packet = ByteArray(size)
        Random.nextBytes(packet)
        Os.write(fd, packet, 0 /*off*/, packet.size)
        return packet
    }

    fun assertPacketReceived(fd: FileDescriptor, expected: ByteArray) {
        val readBuffer = ByteArray(expected.size)
        Os.read(fd, readBuffer, 0 /*off*/, readBuffer.size)
        assertThat(readBuffer).isEqualTo(expected)
    }

    @Test
    fun testForwarding_withoutHeaderCompression() {
        var packet = sendPacket(l2capFds[1])
        var packet2 = sendPacket(l2capFds[1])
        assertPacketReceived(tunFds[1], packet)
        assertPacketReceived(tunFds[1], packet2)

        packet = sendPacket(tunFds[1])
        packet2 = sendPacket(tunFds[1])
        assertPacketReceived(l2capFds[1], packet)
        assertPacketReceived(l2capFds[1], packet2)
    }

    @Test
    fun testForwarding_packetExceedsMtu() {
        // Reading from tun drops packets that exceed MTU.
        // drop
        sendPacket(tunFds[1], L2capPacketForwarder.MTU + 1)
        // drop
        sendPacket(tunFds[1], L2capPacketForwarder.MTU + 42)
        var packet = sendPacket(l2capFds[1], 1280)
        assertPacketReceived(tunFds[1], packet)

        // On the BluetoothSocket side, reads that exceed MTU stop forwarding.
        sendPacket(l2capFds[1], L2capPacketForwarder.MTU + 1)
        verify(callback, timeout(TIMEOUT)).onError()
    }
}
