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

import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.NET_CAPABILITY_WIFI_P2P
import android.net.NetworkCapabilities.RES_ID_MATCH_ALL_RESERVATIONS
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkProvider
import android.net.NetworkProvider.NetworkOfferCallback
import android.net.NetworkRequest
import android.net.NetworkScore
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Reserved
import com.android.testutils.TestableNetworkCallback.Event.Unavailable
import com.android.testutils.TestableNetworkOfferCallback
import com.android.testutils.TestableNetworkOfferCallback.Event.Needed
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private val ETHERNET_SCORE = NetworkScore.Builder().build()
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

private const val TIMEOUT_MS = 5_000L
private const val NO_CB_TIMEOUT_MS = 200L

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSNetworkReservationTest : CSTest() {
    private lateinit var provider: NetworkProvider
    private val blanketOffer = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)

    @Before
    fun subclassSetUp() {
        provider = NetworkProvider(context, csHandlerThread.looper, "Ethernet provider")
        cm.registerNetworkProvider(provider)

        // register a blanket offer for use in tests.
        provider.registerNetworkOffer(ETHERNET_SCORE, BLANKET_CAPS, blanketOffer)
    }

    fun NetworkCapabilities.copyWithReservationId(resId: Int) = NetworkCapabilities(this).also {
        it.reservationId = resId
    }

    fun NetworkProvider.registerNetworkOffer(
            score: NetworkScore,
            caps: NetworkCapabilities,
            cb: NetworkOfferCallback
    ) {
        registerNetworkOffer(score, caps, {r -> r.run()}, cb)
    }

    @Test
    fun testReservationRequest() {
        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, csHandler, cb)

        // validate the reservation matches the blanket offer.
        val reservationReq = blanketOffer.expectOnNetworkNeeded(BLANKET_CAPS).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        // bring up reserved reservation offer
        val reservedOfferCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val reservedOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, reservedOfferCaps, reservedOfferCb)

        // validate onReserved was sent to the app
        val onReservedCaps = cb.expect<Reserved>().caps
        assertEquals(reservedOfferCaps, onReservedCaps)

        // validate the reservation matches the reserved offer.
        reservedOfferCb.expectOnNetworkNeeded(reservedOfferCaps)

        // reserved offer goes away
        provider.unregisterNetworkOffer(reservedOfferCb)
        cb.expect<Unavailable>()
    }

    fun TestableNetworkOfferCallback.expectNoCallbackWhere(
            predicate: (TestableNetworkOfferCallback.Event) -> Boolean
    ) {
        val event = history.poll(NO_CB_TIMEOUT_MS) { predicate(it) }
        assertNull(event)
    }

    @Test
    fun testReservationRequest_notDeliveredToRegularOffer() {
        val offerCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, ETHERNET_CAPS, {r -> r.run()}, offerCb)

        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, csHandler, cb)

        // validate the offer does not receive onNetworkNeeded for reservation request
        offerCb.expectNoCallbackWhere {
            it is Needed && it.request.type == NetworkRequest.Type.RESERVATION
        }
    }

    @Test
    fun testReservedOffer_preventReservationIdUpdate() {
        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, csHandler, cb)

        // validate the reservation matches the blanket offer.
        val reservationReq = blanketOffer.expectOnNetworkNeeded(BLANKET_CAPS).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        // bring up reserved offer
        val reservedCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val reservedOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, reservedCaps, reservedOfferCb)

        cb.expect<Reserved>()
        reservedOfferCb.expectOnNetworkNeeded(reservedCaps)

        // try to update the offer's reservationId by reusing the same callback object.
        // first file a new request to try and match the offer later.
        val cb2 = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, csHandler, cb2)

        val reservationReq2 = blanketOffer.expectOnNetworkNeeded(BLANKET_CAPS).request
        val reservationId2 = reservationReq2.networkCapabilities.reservationId

        // try to update the offer's reservationId to an existing reservationId.
        val updatedCaps = ETHERNET_CAPS.copyWithReservationId(reservationId2)
        provider.registerNetworkOffer(ETHERNET_SCORE, updatedCaps, reservedOfferCb)

        // validate the original offer disappeared.
        cb.expect<Unavailable>()
        // validate the new offer was rejected by CS.
        reservedOfferCb.expectOnNetworkUnneeded(reservedCaps)
        // validate cb2 never sees onReserved().
        cb2.assertNoCallback()
    }

    @Test
    fun testReservedOffer_capabilitiesCannotBeUpdated() {
        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, csHandler, cb)

        val reservationReq = blanketOffer.expectOnNetworkNeeded(BLANKET_CAPS).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        val reservedCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val reservedOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, reservedCaps, reservedOfferCb)

        cb.expect<Reserved>()
        reservedOfferCb.expectOnNetworkNeeded(reservedCaps)

        // update reserved offer capabilities
        val updatedCaps = NetworkCapabilities(reservedCaps).addCapability(NET_CAPABILITY_WIFI_P2P)
        provider.registerNetworkOffer(ETHERNET_SCORE, updatedCaps, reservedOfferCb)

        cb.expect<Unavailable>()
        reservedOfferCb.expectOnNetworkUnneeded(reservedCaps)
        reservedOfferCb.assertNoCallback()
    }

    @Test
    fun testBlanketOffer_updateAllowed() {
        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, csHandler, cb)
        blanketOffer.expectOnNetworkNeeded(BLANKET_CAPS)

        val updatedCaps = NetworkCapabilities(BLANKET_CAPS).addCapability(NET_CAPABILITY_WIFI_P2P)
        provider.registerNetworkOffer(ETHERNET_SCORE, updatedCaps, blanketOffer)
        blanketOffer.assertNoCallback()

        // Note: NetworkRequest.Builder(NetworkRequest) *does not* perform a defensive copy but
        // changes the underlying request.
        val p2pRequest = NetworkRequest.Builder(NetworkRequest(ETHERNET_REQUEST))
                .addCapability(NET_CAPABILITY_WIFI_P2P)
                .build()
        cm.reserveNetwork(p2pRequest, csHandler, cb)
        blanketOffer.expectOnNetworkNeeded(updatedCaps)
    }

    @Test
    fun testReservationOffer_onlyAllowSingleOffer() {
        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, csHandler, cb)

        val reservationReq = blanketOffer.expectOnNetworkNeeded(BLANKET_CAPS).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        val offerCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        val caps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        provider.registerNetworkOffer(ETHERNET_SCORE, caps, offerCb)
        offerCb.expectOnNetworkNeeded(caps)
        cb.expect<Reserved>()

        val newOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, caps, newOfferCb)
        newOfferCb.assertNoCallback()
        cb.assertNoCallback()

        // File a regular request and validate only the old offer gets onNetworkNeeded.
        val cb2 = TestableNetworkCallback()
        cm.requestNetwork(ETHERNET_REQUEST, cb2, csHandler)
        offerCb.expectOnNetworkNeeded(caps)
        newOfferCb.assertNoCallback()
    }

    @Test
    fun testReservationOffer_updateScore() {
        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, csHandler, cb)

        val reservationReq = blanketOffer.expectOnNetworkNeeded(BLANKET_CAPS).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        val reservedCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val reservedOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, reservedCaps, reservedOfferCb)
        reservedOfferCb.expectOnNetworkNeeded(reservedCaps)
        reservedOfferCb.assertNoCallback()
        cb.expect<Reserved>()

        // update reserved offer capabilities
        val newScore = NetworkScore.Builder().setShouldYieldToBadWifi(true).build()
        provider.registerNetworkOffer(newScore, reservedCaps, reservedOfferCb)
        cb.assertNoCallback()

        val cb2 = TestableNetworkCallback()
        cm.requestNetwork(ETHERNET_REQUEST, cb2, csHandler)
        reservedOfferCb.expectOnNetworkNeeded(reservedCaps)
        reservedOfferCb.assertNoCallback()
    }

    @Test
    fun testReservationOffer_regularOfferCanBeUpdated() {
        val offerCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, ETHERNET_CAPS, offerCb)

        val cb = TestableNetworkCallback()
        cm.requestNetwork(ETHERNET_REQUEST, cb, csHandler)
        offerCb.expectOnNetworkNeeded(ETHERNET_CAPS)
        offerCb.assertNoCallback()

        val updatedCaps = NetworkCapabilities(ETHERNET_CAPS).addCapability(NET_CAPABILITY_WIFI_P2P)
        val newScore = NetworkScore.Builder().setShouldYieldToBadWifi(true).build()
        provider.registerNetworkOffer(newScore, updatedCaps, offerCb)
        offerCb.assertNoCallback()

        val cb2 = TestableNetworkCallback()
        cm.requestNetwork(ETHERNET_REQUEST, cb2, csHandler)
        offerCb.expectOnNetworkNeeded(ETHERNET_CAPS)
        offerCb.assertNoCallback()
    }
}
