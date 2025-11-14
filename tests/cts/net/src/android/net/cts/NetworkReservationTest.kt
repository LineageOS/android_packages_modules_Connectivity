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

package android.net.cts

import android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS
import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.Manifest.permission.NETWORK_SETTINGS
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE
import android.net.ConnectivityManager
import android.net.L2capNetworkSpecifier
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_6LOWPAN
import android.net.L2capNetworkSpecifier.ROLE_SERVER
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.RES_ID_MATCH_ALL_RESERVATIONS
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.platform.test.annotations.AppModeFull
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Reserved
import com.android.testutils.TestableNetworkCallback.Event.Unavailable
import com.android.testutils.TestableNetworkOfferCallback
import com.android.testutils.pollingCheck
import com.android.testutils.runAsShell
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "NetworkReservationTest"

private val NETWORK_SCORE = NetworkScore.Builder().build()
private val ETHERNET_CAPS = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_ETHERNET)
        .addTransportType(TRANSPORT_TEST)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_CONGESTED)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .build()
private val BLANKET_CAPS = NetworkCapabilities(ETHERNET_CAPS).apply {
    reservationId = RES_ID_MATCH_ALL_RESERVATIONS
}
private val ETHERNET_REQUEST = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_ETHERNET)
        .addTransportType(TRANSPORT_TEST)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .build()
private const val TIMEOUT_MS = 30_000L
private const val NO_CB_TIMEOUT_MS = 200L

// TODO: integrate with CSNetworkReservationTest and move to common tests.
@AppModeFull(reason = "CHANGE_NETWORK_STATE, MANAGE_TEST_NETWORKS not grantable to instant apps")
@ConnectivityModuleTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class NetworkReservationTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val handlerThread = HandlerThread("$TAG handler thread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val provider = NetworkProvider(context, handlerThread.looper, TAG)

    private val registeredCallbacks = ArrayList<TestableNetworkCallback>()
    private val bm = context.getSystemService(BluetoothManager::class.java)!!
    private var disableBluetoothInTearDown = false

    @Before
    fun setUp() {
        runAsShell(NETWORK_SETTINGS) {
            cm.registerNetworkProvider(provider)
        }
    }

    private fun enableBluetooth() {
        val adapter = bm.adapter
        assertNotNull(adapter)
        if (adapter.isEnabled()) return

        runShellCommandOrThrow("svc bluetooth enable")
        val bluetoothEnabled = pollingCheck(TIMEOUT_MS) {
            adapter.isEnabled()
        }
        assertTrue(bluetoothEnabled)
        // Only disable Bluetooth in tear down when it hasn't already been enabled.
        disableBluetoothInTearDown = true
    }

    private fun disableBluetooth() {
        // adapter can't actually be null here, because this function does not run unless
        // disableBluetoothInTearDown is true. Just in case, refrain from throwing an exception in
        // tearDown.
        val adapter = bm.adapter
        if (adapter == null) return

        runShellCommandOrThrow("svc bluetooth disable")
        // Wait for #isEnabled() to return false; ignore failures.
        pollingCheck(TIMEOUT_MS) {
            !adapter.isEnabled()
        }
    }

    @After
    fun tearDown() {
        registeredCallbacks.forEach { cm.unregisterNetworkCallback(it) }
        runAsShell(NETWORK_SETTINGS) {
            // unregisterNetworkProvider unregisters all associated NetworkOffers.
            cm.unregisterNetworkProvider(provider)
        }
        handlerThread.quitSafely()
        handlerThread.join()

        if (disableBluetoothInTearDown) {
            disableBluetooth()
        }
    }

    fun NetworkCapabilities.copyWithReservationId(resId: Int) = NetworkCapabilities(this).also {
        it.reservationId = resId
    }

    fun reserveNetwork(nr: NetworkRequest): TestableNetworkCallback {
        return TestableNetworkCallback().also {
            cm.reserveNetwork(nr, handler, it)
            registeredCallbacks.add(it)
        }
    }

    @Test
    fun testReserveNetwork() {
        // register blanket offer
        val blanketOffer = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        runAsShell(MANAGE_TEST_NETWORKS) {
            provider.registerNetworkOffer(NETWORK_SCORE, BLANKET_CAPS, handler::post, blanketOffer)
        }

        val cb = reserveNetwork(ETHERNET_REQUEST)

        // validate the reservation matches the blanket offer.
        val reservationReq = blanketOffer.expectOnNetworkNeeded(BLANKET_CAPS).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        // bring up reserved reservation offer
        val reservedCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val reservedOffer = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        runAsShell(MANAGE_TEST_NETWORKS) {
            provider.registerNetworkOffer(NETWORK_SCORE, reservedCaps, handler::post, reservedOffer)
        }

        // validate onReserved was sent to the app
        val appObservedCaps = cb.expect<Reserved>().caps
        assertEquals(reservedCaps, appObservedCaps)

        // validate the reservation matches the reserved offer.
        reservedOffer.expectOnNetworkNeeded(reservedCaps)

        // reserved offer goes away
        provider.unregisterNetworkOffer(reservedOffer)
        cb.expect<Unavailable>()
    }

    @Test
    fun testReserveL2capNetwork() {
        assumeTrue(context.packageManager.hasSystemFeature(FEATURE_BLUETOOTH_LE))
        enableBluetooth()

        val l2capReservationSpecifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        val l2capRequest = NetworkRequest.Builder()
                .addTransportType(TRANSPORT_BLUETOOTH)
                .removeCapability(NET_CAPABILITY_TRUSTED)
                .removeCapability(NET_CAPABILITY_NOT_RESTRICTED)
                .setNetworkSpecifier(l2capReservationSpecifier)
                .build()
        val cb = runAsShell(CONNECTIVITY_USE_RESTRICTED_NETWORKS) {
            reserveNetwork(l2capRequest)
        }

        val caps = cb.expect<Reserved>().caps
        val reservedSpec = caps.networkSpecifier
        assertTrue(reservedSpec is L2capNetworkSpecifier)
        assertContains(0x80..0xFF, reservedSpec.psm, "PSM is outside of dynamic range")
        assertEquals(HEADER_COMPRESSION_6LOWPAN, reservedSpec.headerCompression)
        assertNull(reservedSpec.remoteAddress)
    }
}
