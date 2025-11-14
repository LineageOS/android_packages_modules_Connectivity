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

package com.android.server

import android.Manifest.permission.NETWORK_SETTINGS
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.TRANSPORT_SATELLITE
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkProvider
import android.net.NetworkScore
import android.net.OemNetworkPreferences
import android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_TEST
import android.os.Build
import android.os.ConditionVariable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Lost
import com.android.testutils.TestableNetworkOfferCallback
import com.android.testutils.TestableNetworkOfferCallback.Event.Needed
import com.android.testutils.TestableNetworkOfferCallback.Event.Unneeded
import com.android.testutils.postAndWait
import com.android.testutils.runAsShell
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.eq

const val UID1 = 184
const val UID2 = 10184
const val UID3 = 10193
const val SMSUID = 124

private const val DEFAULT_TIMEOUT_MS = 5000L
private const val DEFAULT_NO_CALLBACK_TIMEOUT_MS = 200L

private fun satelliteNc(restricted: Boolean) = nc(
    TRANSPORT_SATELLITE,
    NET_CAPABILITY_INTERNET,
).apply {
    removeCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
    if (restricted) removeCapability(NET_CAPABILITY_NOT_RESTRICTED)
}

@SuppressLint("VisibleForTests", "MissingPermission")
@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSPreferenceTest : CSTest() {
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    val handler = Handler(Looper.getMainLooper())

    @Test
    fun testBasicOemPreference() {
        val myAppId = UserHandle.getAppId(Process.myUid())
        doAnswer { invocation ->
            val handle = invocation.getArgument<UserHandle>(2)
            ApplicationInfo().apply { uid = handle.getUid(myAppId) }
        }.`when`(packageManager).getApplicationInfoAsUser(
            eq(context.packageName),
            eq(0), // flags
            any<UserHandle>()
        )

        val wifiAgent = Agent(nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()
        val testAgent = Agent(TRANSPORT_TEST)
        testAgent.connect()

        val cb = TestableNetworkCallback()
        cm.registerDefaultNetworkCallback(cb)
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        val pr = OemNetworkPreferences.Builder()
            .addNetworkPreference(context.packageName, OEM_NETWORK_PREFERENCE_TEST)
            .build()
        val cv = ConditionVariable()
        cm.setOemNetworkPreference(pr, Runnable::run) { cv.open() }
        cv.block()

        cb.expectAvailableCallbacks(testAgent.network, validated = false)

        cv.close()
        cm.setOemNetworkPreference(
            OemNetworkPreferences.Builder().build(),
            Runnable::run
        ) {
            cv.open()
        }
        cv.block()

        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)
    }

    private fun ConnectivityManager.registerDefaultNetworkCallbackForUid(
        uid: Int,
        cb: NetworkCallback
    ) = runAsShell(NETWORK_SETTINGS) {
        registerDefaultNetworkCallbackForUid(uid, cb, handler)
    }

    private fun defaultCallbacksForUids(vararg uids: Int) = uids.map { uid ->
        val cb = TestableNetworkCallback()
        cm.registerDefaultNetworkCallbackForUid(uid, cb)
        uid to cb
    }.toMap()

    private fun <K, V> Map<K, V>.eachValue(what: (V) -> Unit) = forEach { what(it.value) }

    private fun updateSatellitePreference(roleUids: Set<Int>, optinUids: Set<Int>) =
        csHandler.postAndWait {
            deps.satelliteNetworkFallbackUidUpdate.accept(roleUids, optinUids)
        }

    fun doTestSatellitePreference_PreferenceInstalledFirst(satelliteRestricted: Boolean) {
        // Connect some wifi agent and file callbacks.
        val wifiAgent = Agent(nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()

        val callbacks = defaultCallbacksForUids(UID1, UID2, UID3, SMSUID)
        callbacks.eachValue { it.expectAvailableCallbacks(wifiAgent.network, validated = false) }

        // Now file the preference and make sure no callbacks are sent.
        updateSatellitePreference(setOf(SMSUID), setOf(UID1, UID2))
        callbacks.eachValue { it.assertNoCallback() }

        // Connect the satellite agent. Because there is a default network, no callbacks are
        // sent yet.
        val satelliteAgent = Agent(
            nc = satelliteNc(satelliteRestricted),
            score = keepConnectedScore()
        )
        satelliteAgent.connect()
        callbacks.eachValue { it.assertNoCallback() }

        // Disconnect wifi. The two opted-in UIDs fall back to satellite iff unrestricted. The SMS
        // UID falls back to the satellite in any case.
        wifiAgent.disconnect()
        callbacks.eachValue { it.expect<Lost>(wifiAgent.network) }
        if (satelliteRestricted) {
            callbacks[UID1]!!.assertNoCallback()
            callbacks[UID2]!!.assertNoCallback()
        } else {
            callbacks[UID1]!!.expectAvailableCallbacks(satelliteAgent.network, validated = false)
            callbacks[UID2]!!.expectAvailableCallbacks(satelliteAgent.network, validated = false)
        }
        callbacks[UID3]!!.assertNoCallback()
        callbacks[SMSUID]!!.expectAvailableCallbacks(satelliteAgent.network, validated = false)

        // Disconnect satellite
        satelliteAgent.disconnect()
        if (satelliteRestricted) {
            callbacks[UID1]!!.assertNoCallback()
            callbacks[UID2]!!.assertNoCallback()
        } else {
            callbacks[UID1]!!.expect<Lost>(satelliteAgent.network)
            callbacks[UID2]!!.expect<Lost>(satelliteAgent.network)
        }
        callbacks[UID3]!!.assertNoCallback()
        callbacks[SMSUID]!!.expect<Lost>(satelliteAgent.network)

        callbacks.eachValue { cm.unregisterNetworkCallback(it) }
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testOptInSatellitePreference_PreferenceInstalledFirst_UnrestrictedSatellite() =
        doTestSatellitePreference_PreferenceInstalledFirst(satelliteRestricted = false)
    @Test @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testOptInSatellitePreference_PreferenceInstalledFirst_RestrictedSatellite() =
        doTestSatellitePreference_PreferenceInstalledFirst(satelliteRestricted = true)

    fun doTestSatellitePreference_NetworkConnectedFirst(satelliteRestricted: Boolean) {
        // Connect some wifi agent and a satellite agent.
        val wifiAgent = Agent(nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()

        val firstSatelliteAgent = Agent(
            nc = satelliteNc(satelliteRestricted),
            score = keepConnectedScore()
        )
        firstSatelliteAgent.connect()

        // File callbacks. They all have wifi as their default network.
        val callbacks = defaultCallbacksForUids(UID1, UID2, UID3, SMSUID)
        callbacks.eachValue { it.expectAvailableCallbacks(wifiAgent.network, validated = false) }

        // Now file the preference and make sure no callbacks are sent because there
        // is a wifi agent.
        updateSatellitePreference(setOf(SMSUID), setOf(UID1, UID2))
        callbacks.eachValue { it.assertNoCallback() }

        // Disconnect satellite. No callbacks are sent since everyone is on wifi.
        firstSatelliteAgent.disconnect()
        callbacks.eachValue { it.assertNoCallback() }

        // Remove all settings
        updateSatellitePreference(emptySet(), emptySet())

        // Connect a new satellite agent. No callback is sent yet, wifi is still connected
        val satelliteAgent = Agent(
            nc = satelliteNc(satelliteRestricted),
            score = keepConnectedScore()
        )
        satelliteAgent.connect()
        callbacks.eachValue { it.assertNoCallback() }

        // Disconnect wifi, everyone loses their default network
        wifiAgent.disconnect()
        callbacks.eachValue { it.expect<Lost>(wifiAgent.network) }
        callbacks.eachValue { it.assertNoCallback() }

        // Install the preference again. If satellite is unrestricted, then the opted-in
        // UIDs go on it.
        updateSatellitePreference(setOf(SMSUID), setOf(UID1, UID2))
        if (satelliteRestricted) {
            callbacks[UID1]!!.assertNoCallback()
            callbacks[UID2]!!.assertNoCallback()
        } else {
            callbacks[UID1]!!.expectAvailableCallbacks(satelliteAgent.network, validated = false)
            callbacks[UID2]!!.expectAvailableCallbacks(satelliteAgent.network, validated = false)
        }
        callbacks[UID3]!!.assertNoCallback()
        callbacks[SMSUID]!!.expectAvailableCallbacks(satelliteAgent.network, validated = false)

        // Disconnect satellite
        satelliteAgent.disconnect()
        if (satelliteRestricted) {
            callbacks[UID1]!!.assertNoCallback()
            callbacks[UID2]!!.assertNoCallback()
        } else {
            callbacks[UID1]!!.expect<Lost>(satelliteAgent.network)
            callbacks[UID2]!!.expect<Lost>(satelliteAgent.network)
        }
        callbacks[UID3]!!.assertNoCallback()
        callbacks[SMSUID]!!.expect<Lost>(satelliteAgent.network)

        callbacks.eachValue { cm.unregisterNetworkCallback(it) }
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testOptInSatellitePreference_NetworkConnectedFirst_UnrestrictedSatellite() =
        doTestSatellitePreference_NetworkConnectedFirst(satelliteRestricted = false)
    @Test @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testOptInSatellitePreference_NetworkConnectedFirst_RestrictedSatellite() =
        doTestSatellitePreference_NetworkConnectedFirst(satelliteRestricted = true)

    private val Needed.isRestricted
        get() = !request.hasCapability(NET_CAPABILITY_NOT_RESTRICTED)
    private val Unneeded.isRestricted
        get() = !request.hasCapability(NET_CAPABILITY_NOT_RESTRICTED)

    /**
     * This test ensures that the network offers for the satellite provider (normally telephony)
     * are correctly updated.
     *
     * Generally, as long as there is a general internet-providing default network, the satellite
     * network is not requested.
     * When there is no general default network, satellite should be requested if and only if
     * there are UIDs that can use it. If there is an apps with the SMS role, it will request a
     * network without NOT_RESTRICTED capability (this app is allowed to use a restricted network).
     * If there are any opted-in apps, they will request a network with NOT_RESTRICTED.
     */
    @Test @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testSatelliteRequest() {
        val provider = NetworkProvider(context, csHandlerThread.looper, "Test provider")
        cm.registerNetworkProvider(provider)

        val satelliteCallback = TestableNetworkOfferCallback()
        provider.registerNetworkOffer(
            NetworkScore.Builder().build(),
            satelliteNc(restricted = false),
            Runnable::run,
            satelliteCallback
        )
        val restrictedSatelliteCallback = TestableNetworkOfferCallback()
        provider.registerNetworkOffer(
            NetworkScore.Builder().build(),
            satelliteNc(restricted = true),
            Runnable::run,
            restrictedSatelliteCallback
        )
        satelliteCallback.assertNoCallback()

        updateSatellitePreference(emptySet(), setOf(UID1))
        satelliteCallback.expect<Needed>()
        satelliteCallback.assertNoCallback()
        restrictedSatelliteCallback.assertNoCallback()

        updateSatellitePreference(emptySet(), emptySet())
        satelliteCallback.expect<Unneeded>()
        satelliteCallback.assertNoCallback()
        restrictedSatelliteCallback.assertNoCallback()

        updateSatellitePreference(setOf(SMSUID), emptySet())
        satelliteCallback.expect<Needed> { it.isRestricted }
        restrictedSatelliteCallback.expect<Needed> { it.isRestricted }
        satelliteCallback.assertNoCallback()
        restrictedSatelliteCallback.assertNoCallback()

        updateSatellitePreference(setOf(SMSUID), setOf(UID1))
        // TODO : ideally ConnectivityService would not send unneeded then needed. This is
        // happening because updating the preferences removes the requests, which causes a
        // rematch (where the requests are not registered), then adds the requests again, which
        // causes another rematch.
        satelliteCallback.expect<Unneeded> { it.isRestricted }
        restrictedSatelliteCallback.expect<Unneeded> { it.isRestricted }

        val mark1 = satelliteCallback.mark
        satelliteCallback.eventuallyExpect<Needed>(from = mark1) { !it.isRestricted }
        satelliteCallback.eventuallyExpect<Needed>(from = mark1) { it.isRestricted }
        assertEquals(2, satelliteCallback.mark - mark1)
        restrictedSatelliteCallback.expect<Needed> { it.isRestricted }
        satelliteCallback.assertNoCallback()
        restrictedSatelliteCallback.assertNoCallback()

        updateSatellitePreference(emptySet(), setOf(UID1))
        val mark2 = satelliteCallback.mark
        satelliteCallback.eventuallyExpect<Unneeded>(from = mark2) { it.isRestricted }
        satelliteCallback.eventuallyExpect<Unneeded>(from = mark2) { !it.isRestricted }
        assertEquals(2, satelliteCallback.mark - mark2)
        satelliteCallback.expect<Needed> { !it.isRestricted }
        restrictedSatelliteCallback.expect<Unneeded> { it.isRestricted }
        satelliteCallback.assertNoCallback()
        restrictedSatelliteCallback.assertNoCallback()

        updateSatellitePreference(setOf(SMSUID), setOf(UID1))
        restrictedSatelliteCallback.expect<Needed> { it.isRestricted }
        satelliteCallback.expect<Unneeded> { !it.isRestricted }
        val mark3 = satelliteCallback.mark
        satelliteCallback.eventuallyExpect<Needed>(from = mark3) { it.isRestricted }
        satelliteCallback.eventuallyExpect<Needed>(from = mark3) { !it.isRestricted }
        assertEquals(2, satelliteCallback.mark - mark3)
        satelliteCallback.assertNoCallback()
        restrictedSatelliteCallback.assertNoCallback()

        val wifiAgent = Agent(nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()
        val mark4 = satelliteCallback.mark
        satelliteCallback.eventuallyExpect<Unneeded>(from = mark4) { it.isRestricted }
        satelliteCallback.eventuallyExpect<Unneeded>(from = mark4) { !it.isRestricted }
        assertEquals(2, satelliteCallback.mark - mark4)
        restrictedSatelliteCallback.expect<Unneeded> { it.isRestricted }
        satelliteCallback.assertNoCallback()
        restrictedSatelliteCallback.assertNoCallback()

        wifiAgent.disconnect()
        val mark5 = satelliteCallback.mark
        satelliteCallback.eventuallyExpect<Needed>(from = mark5) { it.isRestricted }
        satelliteCallback.eventuallyExpect<Needed>(from = mark5) { !it.isRestricted }
        assertEquals(2, satelliteCallback.mark - mark5)
        restrictedSatelliteCallback.expect<Needed> { it.isRestricted }
        satelliteCallback.assertNoCallback()
        restrictedSatelliteCallback.assertNoCallback()
    }
}
