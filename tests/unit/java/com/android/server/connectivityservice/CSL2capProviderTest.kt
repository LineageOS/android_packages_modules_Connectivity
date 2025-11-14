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

package com.android.server

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.INetworkMonitor
import android.net.INetworkMonitorCallbacks
import android.net.IpPrefix
import android.net.L2capNetworkSpecifier
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_6LOWPAN
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_NONE
import android.net.L2capNetworkSpecifier.ROLE_CLIENT
import android.net.L2capNetworkSpecifier.ROLE_SERVER
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.MacAddress
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.RouteInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import com.android.server.net.L2capNetwork.L2capIpClient
import com.android.server.net.L2capPacketForwarder
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Lost
import com.android.testutils.TestableNetworkCallback.Event.Reserved
import com.android.testutils.TestableNetworkCallback.Event.Unavailable
import com.android.testutils.anyNetwork
import com.android.testutils.waitForIdle
import java.io.IOException
import java.util.Optional
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify

private const val PSM = 0x85
private val REMOTE_MAC = byteArrayOf(1, 2, 3, 4, 5, 6)
private val REQUEST = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_BLUETOOTH)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .removeCapability(NET_CAPABILITY_NOT_RESTRICTED)
        .build()

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
@DevSdkIgnoreRunner.MonitorThreadLeak
class CSL2capProviderTest : CSTest() {
    private val networkMonitor = mock<INetworkMonitor>()

    private val btAdapter = mock<BluetoothAdapter>()
    private val btDevice = mock<BluetoothDevice>()
    private val btServerSocket = mock<BluetoothServerSocket>()
    private val btSocket = mock<BluetoothSocket>()
    private val tunInterface = mock<ParcelFileDescriptor>()
    private val l2capIpClient = mock<L2capIpClient>()
    private val packetForwarder = mock<L2capPacketForwarder>()
    private val providerDeps = mock<L2capNetworkProvider.Dependencies>()

    // BlockingQueue does not support put(null) operations, as null is used as an internal sentinel
    // value. Therefore, use Optional<BluetoothSocket> where an empty optional signals the
    // BluetoothServerSocket#close() operation.
    private val acceptQueue = LinkedBlockingQueue<Optional<BluetoothSocket>>()

    private val handlerThread = HandlerThread("CSL2capProviderTest thread").apply { start() }
    private val registeredCallbacks = ArrayList<TestableNetworkCallback>()

    // Requires Dependencies mock to be setup before creation.
    private lateinit var provider: L2capNetworkProvider

    @Before
    fun innerSetUp() {
        doReturn(btAdapter).`when`(bluetoothManager).getAdapter()
        doReturn(btServerSocket).`when`(btAdapter).listenUsingInsecureL2capChannel()
        doReturn(PSM).`when`(btServerSocket).getPsm()
        doReturn(btDevice).`when`(btAdapter).getRemoteDevice(eq(REMOTE_MAC))
        doReturn(btSocket).`when`(btDevice).createInsecureL2capChannel(eq(PSM))

        doAnswer {
            val sock = acceptQueue.take()
            if (sock == null || !sock.isPresent()) throw IOException()
            sock.get()
        }.`when`(btServerSocket).accept()

        doAnswer {
            acceptQueue.put(Optional.empty())
        }.`when`(btServerSocket).close()

        doReturn(handlerThread).`when`(providerDeps).getHandlerThread()
        doReturn(tunInterface).`when`(providerDeps).createTunInterface(any())
        doReturn(packetForwarder).`when`(providerDeps)
                .createL2capPacketForwarder(any(), any(), any(), any(), any())
        doReturn(l2capIpClient).`when`(providerDeps).createL2capIpClient(any(), any(), any())

        val lp = LinkProperties()
        val ifname = "l2cap-tun0"
        lp.setInterfaceName(ifname)
        lp.addLinkAddress(LinkAddress("fe80::1/64"))
        lp.addRoute(RouteInfo(IpPrefix("fe80::/64"), null /* nextHop */, ifname))
        doReturn(lp).`when`(l2capIpClient).start()

        // Note: In order to properly register a NetworkAgent, a NetworkMonitor must be created for
        // the agent. CSAgentWrapper already does some of this, but requires adding additional
        // Dependencies to the production code. Create a mocked NM inside this test instead.
        doAnswer { i ->
            val cb = i.arguments[2] as INetworkMonitorCallbacks
            cb.onNetworkMonitorCreated(networkMonitor)
        }.`when`(networkStack).makeNetworkMonitor(
                any() /* network */,
                isNull() /* name */,
                any() /* callbacks */
        )

        provider = L2capNetworkProvider(providerDeps, context)
        provider.start()
    }

    @After
    fun innerTearDown() {
        // Unregistering a callback which has previously been unregistered by virtue of receiving
        // onUnavailable is a noop.
        registeredCallbacks.forEach { cm.unregisterNetworkCallback(it) }
        // Wait for CS handler idle, meaning the unregisterNetworkCallback has been processed and
        // L2capNetworkProvider has been notified.
        waitForIdle()

        // While quitSafely() effectively waits for idle, it is not enough, because the tear down
        // path itself posts on the handler thread. This means that waitForIdle() needs to run
        // twice. The first time, to ensure all active threads have been joined, and the second time
        // to run all associated clean up actions.
        handlerThread.waitForIdle(HANDLER_TIMEOUT_MS)
        handlerThread.quitSafely()
        handlerThread.join()
    }

    private fun reserveNetwork(nr: NetworkRequest) = TestableNetworkCallback().also {
        cm.reserveNetwork(nr, csHandler, it)
        registeredCallbacks.add(it)
    }

    private fun requestNetwork(nr: NetworkRequest) = TestableNetworkCallback().also {
        cm.requestNetwork(nr, it, csHandler)
        registeredCallbacks.add(it)
    }

    private fun NetworkRequest.copyWithSpecifier(specifier: NetworkSpecifier): NetworkRequest {
        // Note: NetworkRequest.Builder(NetworkRequest) *does not* perform a defensive copy but
        // changes the underlying request.
        return NetworkRequest.Builder(NetworkRequest(this))
                .setNetworkSpecifier(specifier)
                .build()
    }

    @Test
    fun testReservation() {
        val l2capServerSpecifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        val l2capReservation = REQUEST.copyWithSpecifier(l2capServerSpecifier)
        val reservationCb = reserveNetwork(l2capReservation)

        val reservedCaps = reservationCb.expect<Reserved>().caps
        val reservedSpec = reservedCaps.networkSpecifier as L2capNetworkSpecifier

        assertEquals(PSM, reservedSpec.getPsm())
        assertEquals(HEADER_COMPRESSION_6LOWPAN, reservedSpec.headerCompression)
        assertNull(reservedSpec.remoteAddress)

        reservationCb.assertNoCallback()
    }

    @Test
    fun testBlanketOffer_reservationWithoutSpecifier() {
        reserveNetwork(REQUEST).assertNoCallback()
    }

    @Test
    fun testBlanketOffer_reservationWithCorrectSpecifier() {
        var specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        var nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).expect<Reserved>()

        specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .build()
        nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).expect<Reserved>()
    }

    @Test
    fun testBlanketOffer_reservationWithIncorrectSpecifier() {
        var specifier = L2capNetworkSpecifier.Builder().build()
        var nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).assertNoCallback()

        specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .build()
        nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).assertNoCallback()

        specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .setPsm(0x81)
                .build()
        nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).assertNoCallback()

        specifier = L2capNetworkSpecifier.Builder()
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .build()
        nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).assertNoCallback()
    }

    @Test
    fun testBluetoothException_listenUsingInsecureL2capChannelThrows() {
        doThrow(IOException()).`when`(btAdapter).listenUsingInsecureL2capChannel()
        var specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        var nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).expect<Unavailable>()

        doReturn(btServerSocket).`when`(btAdapter).listenUsingInsecureL2capChannel()
        reserveNetwork(nr).expect<Reserved>()
    }

    @Test
    fun testBluetoothException_acceptThrows() {
        doThrow(IOException()).`when`(btServerSocket).accept()
        var specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        var nr = REQUEST.copyWithSpecifier(specifier)
        val cb = reserveNetwork(nr)
        cb.expect<Reserved>()
        cb.expect<Unavailable>()

        // BluetoothServerSocket#close() puts Optional.empty() on the acceptQueue.
        acceptQueue.clear()
        doAnswer {
            val sock = acceptQueue.take()
            assertFalse(sock.isPresent())
            throw IOException() // to indicate the socket was closed.
        }.`when`(btServerSocket).accept()
        val cb2 = reserveNetwork(nr)
        cb2.expect<Reserved>()
        cb2.assertNoCallback()
    }

    @Test
    fun testServerNetwork() {
        val specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        val nr = REQUEST.copyWithSpecifier(specifier)
        val cb = reserveNetwork(nr)
        cb.expect<Reserved>()

        // Unblock BluetoothServerSocket#accept()
        doReturn(true).`when`(btSocket).isConnected()
        acceptQueue.put(Optional.of(btSocket))

        cb.expectAvailableCallbacks(anyNetwork(), validated = false)
        cb.assertNoCallback()
        // Verify that packet forwarding was started.
        // TODO: stop mocking L2capPacketForwarder.
        verify(providerDeps).createL2capPacketForwarder(any(), any(), any(), any(), any())
    }

    @Test
    fun testBluetoothException_createInsecureL2capChannelThrows() {
        doThrow(IOException()).`when`(btDevice).createInsecureL2capChannel(any())

        val specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_CLIENT)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .setRemoteAddress(MacAddress.fromBytes(REMOTE_MAC))
                .setPsm(PSM)
                .build()
        val nr = REQUEST.copyWithSpecifier(specifier)
        val cb = requestNetwork(nr)

        cb.expect<Unavailable>()
    }

    @Test
    fun testBluetoothException_bluetoothSocketConnectThrows() {
        doThrow(IOException()).`when`(btSocket).connect()

        val specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_CLIENT)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .setRemoteAddress(MacAddress.fromBytes(REMOTE_MAC))
                .setPsm(PSM)
                .build()
        val nr = REQUEST.copyWithSpecifier(specifier)
        val cb = requestNetwork(nr)

        cb.expect<Unavailable>()
    }

    @Test
    fun testClientNetwork() {
        val specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_CLIENT)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .setRemoteAddress(MacAddress.fromBytes(REMOTE_MAC))
                .setPsm(PSM)
                .build()
        val nr = REQUEST.copyWithSpecifier(specifier)
        val cb = requestNetwork(nr)
        cb.expectAvailableCallbacks(anyNetwork(), validated = false)
    }

    @Test
    fun testClientNetwork_headerCompressionMismatch() {
        var specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_CLIENT)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .setRemoteAddress(MacAddress.fromBytes(REMOTE_MAC))
                .setPsm(PSM)
                .build()
        var nr = REQUEST.copyWithSpecifier(specifier)
        val cb = requestNetwork(nr)
        cb.expectAvailableCallbacks(anyNetwork(), validated = false)

        specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_CLIENT)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .setRemoteAddress(MacAddress.fromBytes(REMOTE_MAC))
                .setPsm(PSM)
                .build()
        nr = REQUEST.copyWithSpecifier(specifier)
        val cb2 = requestNetwork(nr)
        cb2.expect<Unavailable>()
    }

    @Test
    fun testClientNetwork_multipleRequests() {
        val specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_CLIENT)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .setRemoteAddress(MacAddress.fromBytes(REMOTE_MAC))
                .setPsm(PSM)
                .build()
        val nr = REQUEST.copyWithSpecifier(specifier)
        val cb = requestNetwork(nr)
        cb.expectAvailableCallbacks(anyNetwork(), validated = false)

        val cb2 = requestNetwork(nr)
        cb2.expectAvailableCallbacks(anyNetwork(), validated = false)
    }

    /** Test to ensure onLost() is sent before onUnavailable() when the network is torn down. */
    @Test
    fun testClientNetwork_gracefulTearDown() {
        val specifier = L2capNetworkSpecifier.Builder()
            .setRole(ROLE_CLIENT)
            .setHeaderCompression(HEADER_COMPRESSION_NONE)
            .setRemoteAddress(MacAddress.fromBytes(REMOTE_MAC))
            .setPsm(PSM)
            .build()

        val nr = REQUEST.copyWithSpecifier(specifier)
        val cb = requestNetwork(nr)
        cb.expectAvailableCallbacks(anyNetwork(), validated = false)

        // Capture the L2capPacketForwarder callback object to tear down the network.
        val handlerCaptor = ArgumentCaptor.forClass(Handler::class.java)
        val forwarderCbCaptor = ArgumentCaptor.forClass(L2capPacketForwarder.ICallback::class.java)
        verify(providerDeps).createL2capPacketForwarder(
                handlerCaptor.capture(), any(), any(), any(), forwarderCbCaptor.capture())
        val handler = handlerCaptor.value
        val forwarderCb = forwarderCbCaptor.value

        // Trigger a forwarding error
        handler.post { forwarderCb.onError() }
        handler.waitForIdle(HANDLER_TIMEOUT_MS)

        cb.expect<Lost>()
        cb.expect<Unavailable>()
    }
}
