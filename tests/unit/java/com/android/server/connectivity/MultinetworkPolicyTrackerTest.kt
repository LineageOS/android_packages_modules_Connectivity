/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager.MULTIPATH_PREFERENCE_HANDOVER
import android.net.ConnectivityManager.MULTIPATH_PREFERENCE_PERFORMANCE
import android.net.ConnectivityManager.MULTIPATH_PREFERENCE_RELIABILITY
import android.net.ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI
import android.net.ConnectivitySettingsManager.NETWORK_METERED_MULTIPATH_PREFERENCE
import android.net.platform.flags.Flags.FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG
import android.os.Build
import android.os.Handler
import android.os.test.TestLooper
import android.provider.Settings
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.test.mock.MockContentResolver
import androidx.test.filters.SmallTest
import com.android.connectivity.resources.R
import com.android.internal.os.BackgroundThread
import com.android.internal.util.test.FakeSettingsProvider
import com.android.modules.utils.build.SdkLevel
import com.android.server.connectivity.MultinetworkPolicyTracker.ActiveDataSubscriptionIdListener
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule.FeatureFlag
import com.android.testutils.postAndWait
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.any
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

const val HANDLER_TIMEOUT_MS = 400

/**
 * Tests for [MultinetworkPolicyTracker].
 *
 * Build, install and run with:
 * atest FrameworksNetTest:MultinetworkPolicyTrackerTest
 */
// This test class is initialized with 'supportCarrierConfigManager',
// which indicates whether CarrierConfigManager is supported. This value
// is 'false' if FEATURE_TELEPHONY_SUBSCRIPTION is not supported on the device.
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class MultinetworkPolicyTrackerTest(private val supportCarrierConfigManager: Boolean) {
    companion object {
        @Parameterized.Parameters
        @JvmStatic
        fun data() = listOf(false, true)
    }

    // This wrapper class prevents JUnit from attempting to load unsupported system classes
    // that are present in the System Test (S/T) image, which would otherwise cause test failures.
    private class CarrierConfigChangeRunner(
        val csLooper: TestLooper,
        val bgHandler: Handler,
        val listener: CarrierConfigManager.CarrierConfigChangeListener
    ) {

        fun runCarrierConfigChangeOnBackgroundThread(subId: Int) {
            bgHandler.postAndWait {
                    listener.onCarrierConfigChanged(
                        0, /* logicalSlotIndex */
                        subId, /* subscriptionId */
                        0, /* carrierId */
                        0 /* specificCarrierId */
                    )
                }
                csLooper.dispatchNext()
        }
    }

    private val featureFlags = HashSet<String>()

    var carrierConfigManager: CarrierConfigManager? = null

    // This will set feature flags from @FeatureFlag annotations
    // into the map before setUp() runs.
    @get:Rule
    val setFeatureFlagsRule = SetFeatureFlagsRule(
        { name, enabled ->
            if (enabled == true) featureFlags.add(name) else featureFlags.remove(name) },
        { name -> featureFlags.contains(name) }
    )

    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    private val resources = mock(Resources::class.java).also {
        doReturn(0).`when`(it).getInteger(R.integer.config_networkAvoidBadWifi)
        doReturn(0).`when`(it).getInteger(R.integer.config_activelyPreferBadWifi)
    }

    private val telephonyManager = mock(TelephonyManager::class.java)
    private val subscriptionManager = mock(SubscriptionManager::class.java).also {
        doReturn(null).`when`(it).getActiveSubscriptionInfo(anyInt())
    }
    private val packageManager = mock(PackageManager::class.java).also {
        doReturn(true).`when`(it)
            .hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
    }
    private val resolver = MockContentResolver().apply {
        addProvider(Settings.AUTHORITY, FakeSettingsProvider()) }
    private val context = mock(Context::class.java).also {
        doReturn(Context.TELEPHONY_SERVICE).`when`(it)
            .getSystemServiceName(TelephonyManager::class.java)
        doReturn(telephonyManager).`when`(it).getSystemService(Context.TELEPHONY_SERVICE)
        if (it.getSystemService(TelephonyManager::class.java) == null) {
            // Test is using mockito extended
            doCallRealMethod().`when`(it).getSystemService(TelephonyManager::class.java)
        }
        doReturn(subscriptionManager).`when`(it)
            .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
        doReturn(resolver).`when`(it).contentResolver
        doReturn(resources).`when`(it).resources
        doReturn(it).`when`(it).createConfigurationContext(any())
        doReturn(packageManager).`when`(it).packageManager
        Settings.Global.putString(resolver, NETWORK_AVOID_BAD_WIFI, "1")
        ConnectivityResources.setResourcesContextForTest(it)
    }
    private val csLooper = TestLooper()
    private val handler = Handler(csLooper.looper)
    private val bgHandler = BackgroundThread.getHandler()
    private val trackerDependencies =
        MultinetworkPolicyTrackerTestDependencies(resources)

    // this needs to be initialized in setUp() because
    // the featureFlag from the annotation is not ready here
    private lateinit var tracker: MultinetworkPolicyTracker
    private fun assertMultipathPreference(preference: Int) {
        Settings.Global.putString(
            resolver,
            NETWORK_METERED_MULTIPATH_PREFERENCE,
                preference.toString()
        )
        tracker.updateMeteredMultipathPreference()
        assertEquals(preference, tracker.meteredMultipathPreference)
    }

    private fun runOnActiveDataSubscriptionIdChanged() {
        val testSubId = 1000
        val subscriptionInfo = SubscriptionInfo(testSubId, ""/* iccId */, 1/* iccId */,
            "TMO"/* displayName */, "TMO"/* carrierName */, 1/* nameSource */, 1/* iconTint */,
            "123"/* number */, 1/* roaming */, null/* icon */, "310"/* mcc */, "210"/* mnc */,
            ""/* countryIso */, false/* isEmbedded */, null/* nativeAccessRules */,
            "1"/* cardString */)
        doReturn(subscriptionInfo).`when`(subscriptionManager).getActiveSubscriptionInfo(testSubId)

        // Modify avoidBadWifi and meteredMultipathPreference settings value and local variables in
        // MultinetworkPolicyTracker should be also updated after subId changed.
        Settings.Global.putString(resolver, NETWORK_AVOID_BAD_WIFI, "0")
        Settings.Global.putString(
            resolver,
            NETWORK_METERED_MULTIPATH_PREFERENCE,
            MULTIPATH_PREFERENCE_PERFORMANCE.toString()
        )

        assertTrue(tracker.avoidBadWifi)

        val listenerCaptor = ArgumentCaptor.forClass(
            ActiveDataSubscriptionIdListener::class.java
        )
        verify(telephonyManager, times(1))
            .registerTelephonyCallback(any(), listenerCaptor.capture())
        val listener = listenerCaptor.value
        csLooper.dispatchAll()

        // Mock the carrier configuration to return false
        trackerDependencies.setAvoidBadWifiCarrierConfigForSubId(testSubId, false)
        listener.onActiveDataSubscriptionIdChanged(testSubId)

        // Check if avoidBadWifi and meteredMultipathPreference values have been updated.
        assertFalse(tracker.avoidBadWifi)
        assertEquals(MULTIPATH_PREFERENCE_PERFORMANCE, tracker.meteredMultipathPreference)
    }

    @Before
    fun setUp() {
        trackerDependencies.setAvoidBadWifiFromCarrierConfigFeature(
            featureFlags.contains(FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG)
        )

        if (supportCarrierConfigManager) {
            carrierConfigManager = mock(CarrierConfigManager::class.java)
        } else {
            carrierConfigManager = null
        }

        doReturn(carrierConfigManager).`when`(context)
            .getSystemService(CarrierConfigManager::class.java)

        trackerDependencies.setBackgroundThreadHandler(bgHandler)
        tracker = MultinetworkPolicyTracker(
            context,
            handler,
            null /* avoidBadWifiCallback */,
            trackerDependencies
        )
        tracker.start()
    }

    @After
    fun tearDown() {
        ConnectivityResources.setResourcesContextForTest(null)
        trackerDependencies.resetAvoidBadWifiCarrierConfigForSubIdMap()
    }

    @Test
    @FeatureFlag(name = FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG, false)
    fun testUpdateMeteredMultipathPreference() {
        assertMultipathPreference(MULTIPATH_PREFERENCE_HANDOVER)
        assertMultipathPreference(MULTIPATH_PREFERENCE_RELIABILITY)
        assertMultipathPreference(MULTIPATH_PREFERENCE_PERFORMANCE)
    }

    @Test
    @FeatureFlag(name = FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG, false)
    fun testUpdateAvoidBadWifi() {
        doReturn(0).`when`(resources).getInteger(R.integer.config_activelyPreferBadWifi)
        Settings.Global.putString(resolver, NETWORK_AVOID_BAD_WIFI, "0")
        assertTrue(tracker.updateAvoidBadWifi())
        assertFalse(tracker.avoidBadWifi)

        doReturn(1).`when`(resources).getInteger(R.integer.config_networkAvoidBadWifi)
        assertTrue(tracker.updateAvoidBadWifi())
        assertTrue(tracker.avoidBadWifi)

        if (SdkLevel.isAtLeastU()) {
            // On U+, the system always prefers bad wifi.
            assertTrue(tracker.activelyPreferBadWifi)
        } else {
            assertFalse(tracker.activelyPreferBadWifi)
        }

        doReturn(1).`when`(resources).getInteger(R.integer.config_activelyPreferBadWifi)
        if (SdkLevel.isAtLeastU()) {
            // On U+, this didn't change the setting
            assertFalse(tracker.updateAvoidBadWifi())
        } else {
            // On T-, this must have changed the setting
            assertTrue(tracker.updateAvoidBadWifi())
        }
        // In all cases, now the system actively prefers bad wifi
        assertTrue(tracker.activelyPreferBadWifi)

        // Remaining tests are only useful on T-, which support both the old and new mode.
        if (SdkLevel.isAtLeastU()) return

        doReturn(0).`when`(resources).getInteger(R.integer.config_activelyPreferBadWifi)
        assertTrue(tracker.updateAvoidBadWifi())
        assertFalse(tracker.activelyPreferBadWifi)

        // Simulate update of device config
        trackerDependencies.putConfigActivelyPreferBadWifi(1)
        csLooper.dispatchAll()
        assertTrue(tracker.activelyPreferBadWifi)
    }

    @Test
    @FeatureFlag(name = FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG, false)
    fun testOnActiveDataSubscriptionIdChangedFromResources() {
        runOnActiveDataSubscriptionIdChanged()
    }

    @Test
    @FeatureFlag(name = FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG, true)
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    fun testOnActiveDataSubscriptionIdChangedFromCarrierConfig() {
        runOnActiveDataSubscriptionIdChanged()
    }

    @Test
    @FeatureFlag(name = FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG, true)
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    fun testAvoidBadWifiWithGlobalSetting() {
        val activeSubId = 1000
        // Mock the initial global setting to false
        Settings.Global.putString(resolver, NETWORK_AVOID_BAD_WIFI, "0")
        // Mock the initial carrier configuration to return true
        trackerDependencies.setAvoidBadWifiCarrierConfigForSubId(activeSubId, true)

        val listenerCaptor = ArgumentCaptor.forClass(
            ActiveDataSubscriptionIdListener::class.java
        )
        verify(telephonyManager, times(1))
            .registerTelephonyCallback(any(), listenerCaptor.capture())
        val listener = listenerCaptor.value
        csLooper.dispatchAll()

        // Simulate a change in the active data subscription ID to activeSubId.
        listener.onActiveDataSubscriptionIdChanged(activeSubId)

        // Assert that the tracker's avoidBadWifi flag is false based on global setting
        assertFalse(tracker.avoidBadWifi)
        assertTrue(tracker.activelyPreferBadWifi)

        // Mock the initial global setting to true
        Settings.Global.putString(resolver, NETWORK_AVOID_BAD_WIFI, "1")
        // Mock the initial carrier configuration to return false
        trackerDependencies.setAvoidBadWifiCarrierConfigForSubId(activeSubId, false)

        // Assert that the tracker's avoidBadWifi flag is true based on global setting
        assertTrue(tracker.updateAvoidBadWifi())
        assertTrue(tracker.avoidBadWifi)
        assertTrue(tracker.activelyPreferBadWifi)
    }

    @Test
    @FeatureFlag(name = FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG, true)
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    fun testUpdateAvoidBadWifiOnCarrierConfigChange() {
        assumeTrue(
            "skip test if carrierConfigManager is not supported",
            supportCarrierConfigManager
        )

        // Mock the initial global setting to null
        Settings.Global.putString(resolver, NETWORK_AVOID_BAD_WIFI, null)

        val activeSubId = 1000
        val listenerCaptor = ArgumentCaptor.forClass(
            ActiveDataSubscriptionIdListener::class.java
        )
        verify(telephonyManager, times(1))
            .registerTelephonyCallback(any(), listenerCaptor.capture())
        val listener = listenerCaptor.value
        // Simulate a change in the active data subscription ID to activeSubId.
        listener.onActiveDataSubscriptionIdChanged(activeSubId)

        val carrierConfiglistenCaptor = ArgumentCaptor.forClass(
            CarrierConfigManager.CarrierConfigChangeListener::class.java
        )

        // Mock the initial carrier configuration to return false
        trackerDependencies.setAvoidBadWifiCarrierConfigForSubId(activeSubId, false)
        verify(carrierConfigManager, times(1))
            ?.registerCarrierConfigChangeListener(any(), carrierConfiglistenCaptor.capture())

        val carrierConfiglistener = carrierConfiglistenCaptor.value
        // dispatch for the first carrier config initialization on the handler thread
        csLooper.dispatchAll()

        val runner = CarrierConfigChangeRunner(csLooper, bgHandler, carrierConfiglistener)
        // Simulate a carrier configuration change for the initial activeSubId (1000),
        // with avoid bad Wi-Fi set to false.
        runner.runCarrierConfigChangeOnBackgroundThread(activeSubId)

        // Assert that the tracker's avoidBadWifi flag is false after the carrier config change.
        assertFalse(tracker.avoidBadWifi)
        assertTrue(tracker.activelyPreferBadWifi)

        // Mock the carrier configuration to now return true for KEY_AVOID_BAD_WIFI_BOOL.
        trackerDependencies.setAvoidBadWifiCarrierConfigForSubId(activeSubId, true)
        // Simulate another carrier configuration change for the same activeSubId (1000),
        // now with avoid bad Wi-Fi set to true.
        runner.runCarrierConfigChangeOnBackgroundThread(activeSubId)

        // Assert that the tracker's avoidBadWifi flag is now true.
        assertTrue(tracker.avoidBadWifi)
        assertTrue(tracker.activelyPreferBadWifi)

        val changedActiveSubId = 1001
        // Mock the carrier configuration to return false
        trackerDependencies.setAvoidBadWifiCarrierConfigForSubId(changedActiveSubId, false)
        // Simulate a carrier configuration change for a different subscription ID.
        runner.runCarrierConfigChangeOnBackgroundThread(changedActiveSubId)

        // Assert that the tracker's avoidBadWifi flag remains true,
        // because the config change was for a non-active subscription.
        assertTrue(tracker.avoidBadWifi)
        assertTrue(tracker.activelyPreferBadWifi)

        // Simulate a change in the active data subscription ID to the new ID (1001).
        listener.onActiveDataSubscriptionIdChanged(changedActiveSubId)
        // Assert that the tracker's avoidBadWifi flag is now false,
        // reflecting the carrier config of the new active subscription.
        assertFalse(tracker.avoidBadWifi)
        assertTrue(tracker.activelyPreferBadWifi)
    }
}
