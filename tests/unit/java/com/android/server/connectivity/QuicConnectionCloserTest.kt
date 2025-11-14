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

package com.android.server.connectivity

import android.net.InetAddresses
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.TEST_IFACE
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants.SOL_SOCKET
import android.util.SparseArray
import com.android.net.module.util.SkDestroyListener
import com.android.net.module.util.netlink.InetDiagMessage
import com.android.net.module.util.netlink.StructInetDiagSockId
import com.android.net.module.util.netlink.StructNlMsgHdr
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.visibleOnHandlerThread
import java.net.InetSocketAddress
import java.util.function.Consumer
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.InOrder
import org.mockito.Mockito.any
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

private const val TEST_UID = 1234
private const val TEST_NETID = 789
private const val TEST_SOCKET_COOKIE = 12321L

// TODO: Use OsConstants.SO_MARK once this API is available
private const val SO_MARK = 36

private val TEST_SRC_ADDRESS = InetAddresses.parseNumericAddress("2001:db8:1:2::2")
private val TEST_SRC_SOCKET_ADDRESS = InetSocketAddress(
        TEST_SRC_ADDRESS,
        1234
)
private val TEST_DST_SOCKET_ADDRESS = InetSocketAddress(
        InetAddresses.parseNumericAddress("2001:db8:1:2::3"),
        443
)
private val TEST_LP = LinkProperties().apply {
    interfaceName = TEST_IFACE
    addLinkAddress(LinkAddress(TEST_SRC_ADDRESS, 64))
}
private val TEST_NETWORK = Network(TEST_NETID)
private val TEST_PAYLOAD = byteArrayOf(0, 1, 2, 3, 4, 5)

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
class QuicConnectionCloserTest {
    private val pfd = mock(ParcelFileDescriptor::class.java)
    private val skDestroyListener = mock(SkDestroyListener::class.java)
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val mDeps = mock(QuicConnectionCloser.Dependencies::class.java).also {
        doReturn(TEST_NETID).`when`(it).getsockoptInt(any(), eq(SOL_SOCKET), eq(SO_MARK))
        doReturn(TEST_SOCKET_COOKIE).`when`(it).getSocketCookie(any())
        doReturn(TEST_SRC_SOCKET_ADDRESS).`when`(it).getsockname(any())
        doReturn(TEST_DST_SOCKET_ADDRESS).`when`(it).getpeername(any())
        doReturn(skDestroyListener).`when`(it).makeSkDestroyListener(any(), any())
    }

    private val nai = mock(NetworkAgentInfo::class.java).also {
        it.linkProperties = TEST_LP
        doReturn(TEST_NETWORK).`when`(it).network()
    }

    private val networkForNetId = SparseArray<NetworkAgentInfo>().apply {
        put(TEST_NETID, nai)
    }

    private val mQuicConnectionCloser = QuicConnectionCloser(networkForNetId, handler, mDeps)

    private fun InOrder.expectDestroyUdpSocket() = verify(mDeps).destroyUdpSocket(
            TEST_SRC_SOCKET_ADDRESS,
            TEST_DST_SOCKET_ADDRESS,
            TEST_SOCKET_COOKIE
    )

    private fun InOrder.assertNoDestroyUdpSocket() = verify(mDeps, never()).destroyUdpSocket(
            any(),
            any(),
            anyLong()
    )

    private fun InOrder.expectSendQuicConnectionClosePayload() =
            verify(mDeps).sendQuicConnectionClosePayload(
                    TEST_NETWORK,
                    TEST_SRC_SOCKET_ADDRESS,
                    TEST_DST_SOCKET_ADDRESS,
                    TEST_PAYLOAD
            )

    private fun InOrder.assertNoSendQuicConnectionClosePayload() =
            verify(mDeps, never()).sendQuicConnectionClosePayload(
                    any(),
                    any(),
                    any(),
                    any()
            )

    @Test
    fun testCloseQuicConnectionByUids() {
        mQuicConnectionCloser.registerQuicConnectionClosePayload(TEST_UID, pfd, TEST_PAYLOAD)
        visibleOnHandlerThread(handler) {
            mQuicConnectionCloser.closeQuicConnectionByUids(setOf(TEST_UID))
        }

        val inOrder = inOrder(mDeps)
        inOrder.expectDestroyUdpSocket()
        inOrder.expectSendQuicConnectionClosePayload()
    }

    @Test
    fun testCloseQuicConnectionByUids_unregisterQuicConnectionCloseInfo() {
        mQuicConnectionCloser.registerQuicConnectionClosePayload(TEST_UID, pfd, TEST_PAYLOAD)
        mQuicConnectionCloser.unregisterQuicConnectionClosePayload(pfd)
        visibleOnHandlerThread(handler) {
            mQuicConnectionCloser.closeQuicConnectionByUids(setOf(TEST_UID))
        }

        val inOrder = inOrder(mDeps)
        inOrder.assertNoDestroyUdpSocket()
        inOrder.assertNoSendQuicConnectionClosePayload()
    }

    @Test
    fun testCloseQuicConnectionByUids_networkDisconnected() {
        mQuicConnectionCloser.registerQuicConnectionClosePayload(TEST_UID, pfd, TEST_PAYLOAD)
        // closeQuicConnectionByUids determines that the network is disconnected by
        // checking if it's absent from the networkForNetId set.
        synchronized (networkForNetId) {
            networkForNetId.clear()
        }
        visibleOnHandlerThread(handler) {
            mQuicConnectionCloser.closeQuicConnectionByUids(setOf(TEST_UID))
        }

        val inOrder = inOrder(mDeps)
        inOrder.assertNoDestroyUdpSocket()
        inOrder.assertNoSendQuicConnectionClosePayload()
    }

    @Test
    fun testCloseQuicConnectionByUids_networkAddressChange() {
        mQuicConnectionCloser.registerQuicConnectionClosePayload(TEST_UID, pfd, TEST_PAYLOAD)
        // Update address to different address from TEST_SRC_SOCKET_ADDRESS
        nai.linkProperties = LinkProperties().apply {
            interfaceName = TEST_IFACE
            addLinkAddress(LinkAddress(InetAddresses.parseNumericAddress("2001:db8:1:3::2"), 64))
        }
        visibleOnHandlerThread(handler) {
            mQuicConnectionCloser.closeQuicConnectionByUids(setOf(TEST_UID))
        }

        val inOrder = inOrder(mDeps)
        inOrder.assertNoDestroyUdpSocket()
        inOrder.assertNoSendQuicConnectionClosePayload()
    }

    private fun getSkDestroyListenerCallback(): Consumer<InetDiagMessage> {
        val captor = ArgumentCaptor.forClass(Consumer::class.java)
                as ArgumentCaptor<Consumer<InetDiagMessage>>
        verify(mDeps).makeSkDestroyListener(captor.capture(), any())
        return captor.value
    }

    @Test
    fun testHandleUdpSocketDestroy() {
        mQuicConnectionCloser.registerQuicConnectionClosePayload(TEST_UID, pfd, TEST_PAYLOAD)

        val inetDiagMessage = InetDiagMessage(StructNlMsgHdr())
        inetDiagMessage.inetDiagMsg.id = StructInetDiagSockId(
                TEST_SRC_SOCKET_ADDRESS,
                TEST_DST_SOCKET_ADDRESS,
                0 /* ifindex */,
                TEST_SOCKET_COOKIE
        )
        getSkDestroyListenerCallback().accept(inetDiagMessage)

        val inOrder = inOrder(mDeps)
        // DestroyUdpSocket is not called since the socket is already closed
        inOrder.assertNoDestroyUdpSocket()
        inOrder.expectSendQuicConnectionClosePayload()
    }

    @Test
    fun testHandleUdpSocketDestroy_unregisterBeforeSocketClose() {
        mQuicConnectionCloser.registerQuicConnectionClosePayload(TEST_UID, pfd, TEST_PAYLOAD)
        mQuicConnectionCloser.unregisterQuicConnectionClosePayload(pfd)

        val inetDiagMessage = InetDiagMessage(StructNlMsgHdr())
        inetDiagMessage.inetDiagMsg.id = StructInetDiagSockId(
                TEST_SRC_SOCKET_ADDRESS,
                TEST_DST_SOCKET_ADDRESS,
                0 /* ifindex */,
                TEST_SOCKET_COOKIE
        )
        getSkDestroyListenerCallback().accept(inetDiagMessage)

        val inOrder = inOrder(mDeps)
        inOrder.assertNoDestroyUdpSocket()
        inOrder.assertNoSendQuicConnectionClosePayload()
    }
}
