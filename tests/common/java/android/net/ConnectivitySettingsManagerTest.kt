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

package android.net

import android.content.pm.PackageManager
import android.net.ConnectivitySettingsManager.CAPTIVE_PORTAL_MODE
import android.net.ConnectivitySettingsManager.CAPTIVE_PORTAL_MODE_AVOID
import android.net.ConnectivitySettingsManager.CAPTIVE_PORTAL_MODE_IGNORE
import android.net.ConnectivitySettingsManager.CAPTIVE_PORTAL_MODE_PROMPT
import android.net.ConnectivitySettingsManager.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS
import android.net.ConnectivitySettingsManager.DATA_ACTIVITY_TIMEOUT_MOBILE
import android.net.ConnectivitySettingsManager.DATA_ACTIVITY_TIMEOUT_WIFI
import android.net.ConnectivitySettingsManager.DNS_RESOLVER_MAX_SAMPLES
import android.net.ConnectivitySettingsManager.DNS_RESOLVER_MIN_SAMPLES
import android.net.ConnectivitySettingsManager.DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS
import android.net.ConnectivitySettingsManager.DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT
import android.net.ConnectivitySettingsManager.MOBILE_DATA_ALWAYS_ON
import android.net.ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI
import android.net.ConnectivitySettingsManager.NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT
import android.net.ConnectivitySettingsManager.NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS
import android.net.ConnectivitySettingsManager.PRIVATE_DNS_DEFAULT_MODE
import android.net.ConnectivitySettingsManager.PRIVATE_DNS_MODE_OFF
import android.net.ConnectivitySettingsManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
import android.net.ConnectivitySettingsManager.WIFI_ALWAYS_REQUESTED
import android.net.ConnectivitySettingsManager.getCaptivePortalMode
import android.net.ConnectivitySettingsManager.getConnectivityKeepPendingIntentDuration
import android.net.ConnectivitySettingsManager.getDnsResolverSampleRanges
import android.net.ConnectivitySettingsManager.getDnsResolverSampleValidityDuration
import android.net.ConnectivitySettingsManager.getDnsResolverSuccessThresholdPercent
import android.net.ConnectivitySettingsManager.getIngressRateLimitInBytesPerSecond
import android.net.ConnectivitySettingsManager.getMobileDataActivityTimeout
import android.net.ConnectivitySettingsManager.getMobileDataAlwaysOn
import android.net.ConnectivitySettingsManager.getNetworkAvoidBadWifi
import android.net.ConnectivitySettingsManager.getNetworkSwitchNotificationMaximumDailyCount
import android.net.ConnectivitySettingsManager.getNetworkSwitchNotificationRateDuration
import android.net.ConnectivitySettingsManager.getPrivateDnsDefaultMode
import android.net.ConnectivitySettingsManager.getWifiAlwaysRequested
import android.net.ConnectivitySettingsManager.getWifiDataActivityTimeout
import android.net.ConnectivitySettingsManager.setCaptivePortalMode
import android.net.ConnectivitySettingsManager.setConnectivityKeepPendingIntentDuration
import android.net.ConnectivitySettingsManager.setDnsResolverSampleRanges
import android.net.ConnectivitySettingsManager.setDnsResolverSampleValidityDuration
import android.net.ConnectivitySettingsManager.setDnsResolverSuccessThresholdPercent
import android.net.ConnectivitySettingsManager.setIngressRateLimitInBytesPerSecond
import android.net.ConnectivitySettingsManager.setMobileDataActivityTimeout
import android.net.ConnectivitySettingsManager.setMobileDataAlwaysOn
import android.net.ConnectivitySettingsManager.setNetworkAvoidBadWifi
import android.net.ConnectivitySettingsManager.setNetworkSwitchNotificationMaximumDailyCount
import android.net.ConnectivitySettingsManager.setNetworkSwitchNotificationRateDuration
import android.net.ConnectivitySettingsManager.setPrivateDnsDefaultMode
import android.net.ConnectivitySettingsManager.setWifiAlwaysRequested
import android.net.ConnectivitySettingsManager.setWifiDataActivityTimeout
import android.net.ConnectivitySettingsManager.shouldShowAvoidBadWifiToggle
import android.net.platform.flags.Flags
import android.os.Build
import android.os.PersistableBundle
import android.platform.test.annotations.AppModeFull
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.provider.Settings
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.Range
import androidx.test.InstrumentationRegistry
import androidx.test.filters.SmallTest
import com.android.net.module.util.ConnectivitySettingsUtils.getAvoidBadWifiSettingKey
import com.android.net.module.util.ConnectivitySettingsUtils.getNetworkAvoidBadWifiSetting
import com.android.net.module.util.ConnectivitySettingsUtils.getNetworkLegacyGlobalAvoidBadWifiSetting
import com.android.net.module.util.ConnectivitySettingsUtils.getPrivateDnsModeAsString
import com.android.net.module.util.ConnectivitySettingsUtils.setNetworkAvoidBadWifiSetting
import com.android.net.module.util.ConnectivitySettingsUtils.setNetworkLegacyGlobalAvoidBadWifiSetting
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.com.android.testutils.CarrierConfigRule
import java.time.Duration
import java.util.Objects
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlin.test.assertFailsWith
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [ConnectivitySettingsManager].
 *
 * Build, install and run with:
 * atest android.net.ConnectivitySettingsManagerTest
 */
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
@SmallTest
@AppModeFull(reason = "WRITE_SECURE_SETTINGS permission can't be granted to instant apps")
class ConnectivitySettingsManagerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val resolver = context.contentResolver

    private val defaultDuration = Duration.ofSeconds(0L)
    private val testTime1 = 5L
    private val testTime2 = 10L
    private val settingsTypeGlobal = "global"
    private val settingsTypeSecure = "secure"

    private val defaultSubId = SubscriptionManager.getDefaultSubscriptionId()

    /*** Reset setting value or delete setting if the setting was not existed before testing. */
    private fun resetSettings(names: Array<String>, type: String, values: Array<String?>) {
        for (i in names.indices) {
            if (Objects.equals(values[i], null)) {
                instrumentation.uiAutomation.executeShellCommand(
                        "settings delete $type ${names[i]}"
                )
            } else {
                if (settingsTypeSecure.equals(type)) {
                    Settings.Secure.putString(resolver, names[i], values[i])
                } else {
                    Settings.Global.putString(resolver, names[i], values[i])
                }
            }
        }
    }

    private fun assertAvoidBadWifiSettingEquals(expect: Array<Pair<Int, String?>>) {
        for ((subId, value) in expect) {
            assertEquals(value, getNetworkAvoidBadWifiSetting(context, subId))
        }
    }

    fun <T> testIntSetting(
        names: Array<String>,
        type: String,
        value1: T,
        value2: T,
        getter: () -> T,
        setter: (value: T) -> Unit,
        testIntValues: IntArray
    ) {
        val originals: Array<String?> = Array(names.size) { i ->
            if (settingsTypeSecure.equals(type)) {
                Settings.Secure.getString(resolver, names[i])
            } else {
                Settings.Global.getString(resolver, names[i])
            }
        }

        try {
            for (i in names.indices) {
                if (settingsTypeSecure.equals(type)) {
                    Settings.Secure.putString(resolver, names[i], testIntValues[i].toString())
                } else {
                    Settings.Global.putString(resolver, names[i], testIntValues[i].toString())
                }
            }
            assertEquals(value1, getter())

            setter(value2)
            assertEquals(value2, getter())
        } finally {
            resetSettings(names, type, originals)
        }
    }

    fun overrideAvoidBadWifiCarrierConfig(
        avoidBadWifi: Boolean? = null,
        showAvoidBadWifiToggle: Boolean? = null
    ) {
        // Override carrier config to ignore entitlement check.
        val bundle = PersistableBundle()

        avoidBadWifi?.let { value ->
            bundle.putBoolean(CarrierConfigManager.KEY_AVOID_BAD_WIFI_BOOL, value)
        }

        showAvoidBadWifiToggle?.let { value ->
            bundle.putBoolean(CarrierConfigManager.KEY_SHOW_AVOID_BAD_WIFI_TOGGLE_BOOL, value)
        }

        mCarrierConfigRule.addConfigOverrides(defaultSubId, bundle)
    }

    @get:Rule
    val mCarrierConfigRule: CarrierConfigRule = CarrierConfigRule()

    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    @get:Rule
    val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()!!

    @Test
    fun testMobileDataActivityTimeout() {
        testIntSetting(
            names = arrayOf(DATA_ACTIVITY_TIMEOUT_MOBILE),
            type = settingsTypeGlobal,
            value1 = Duration.ofSeconds(testTime1),
            value2 = Duration.ofSeconds(testTime2),
            getter = { getMobileDataActivityTimeout(context, defaultDuration) },
            setter = { setMobileDataActivityTimeout(context, it) },
            testIntValues = intArrayOf(testTime1.toInt())
        )
    }

    @Test
    fun testWifiDataActivityTimeout() {
        testIntSetting(
            names = arrayOf(DATA_ACTIVITY_TIMEOUT_WIFI),
            type = settingsTypeGlobal,
            value1 = Duration.ofSeconds(testTime1),
            value2 = Duration.ofSeconds(testTime2),
            getter = { getWifiDataActivityTimeout(context, defaultDuration) },
            setter = { setWifiDataActivityTimeout(context, it) },
            testIntValues = intArrayOf(testTime1.toInt())
        )
    }

    @Test
    fun testDnsResolverSampleValidityDuration() {
        testIntSetting(
            names = arrayOf(DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS),
            type = settingsTypeGlobal,
            value1 = Duration.ofSeconds(testTime1),
            value2 = Duration.ofSeconds(testTime2),
            getter = { getDnsResolverSampleValidityDuration(context, defaultDuration) },
            setter = { setDnsResolverSampleValidityDuration(context, it) },
            testIntValues = intArrayOf(testTime1.toInt())
        )

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSampleValidityDuration(context, Duration.ofSeconds(-1L)) }
    }

    @Test
    fun testDnsResolverSuccessThresholdPercent() {
        testIntSetting(
            names = arrayOf(DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT),
            type = settingsTypeGlobal,
            value1 = 5,
            value2 = 10,
            getter = { getDnsResolverSuccessThresholdPercent(context, 0 /* def */) },
            setter = { setDnsResolverSuccessThresholdPercent(context, it) },
            testIntValues = intArrayOf(5)
        )

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSuccessThresholdPercent(context, -1) }
        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSuccessThresholdPercent(context, 120) }
    }

    @Test
    fun testDnsResolverSampleRanges() {
        testIntSetting(
            names = arrayOf(DNS_RESOLVER_MIN_SAMPLES, DNS_RESOLVER_MAX_SAMPLES),
            type = settingsTypeGlobal,
            value1 = Range(1, 63),
            value2 = Range(2, 62),
            getter = { getDnsResolverSampleRanges(context) },
            setter = { setDnsResolverSampleRanges(context, it) },
            testIntValues = intArrayOf(1, 63)
        )

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSampleRanges(context, Range(-1, 62)) }
        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSampleRanges(context, Range(2, 65)) }
    }

    @Test
    fun testNetworkSwitchNotificationMaximumDailyCount() {
        testIntSetting(
            names = arrayOf(NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT),
            type = settingsTypeGlobal,
            value1 = 5,
            value2 = 15,
            getter = { getNetworkSwitchNotificationMaximumDailyCount(context, 0 /* def */) },
            setter = { setNetworkSwitchNotificationMaximumDailyCount(context, it) },
            testIntValues = intArrayOf(5)
        )

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setNetworkSwitchNotificationMaximumDailyCount(context, -1) }
    }

    @Test
    fun testNetworkSwitchNotificationRateDuration() {
        testIntSetting(
            names = arrayOf(NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS),
            type = settingsTypeGlobal,
            value1 = Duration.ofMillis(testTime1),
            value2 = Duration.ofMillis(testTime2),
            getter = { getNetworkSwitchNotificationRateDuration(context, defaultDuration) },
            setter = { setNetworkSwitchNotificationRateDuration(context, it) },
            testIntValues = intArrayOf(testTime1.toInt())
        )

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setNetworkSwitchNotificationRateDuration(context, Duration.ofMillis(-1L)) }
    }

    @Test
    fun testCaptivePortalMode() {
        testIntSetting(
            names = arrayOf(CAPTIVE_PORTAL_MODE),
            type = settingsTypeGlobal,
            value1 = CAPTIVE_PORTAL_MODE_AVOID,
            value2 = CAPTIVE_PORTAL_MODE_PROMPT,
            getter = { getCaptivePortalMode(context, CAPTIVE_PORTAL_MODE_IGNORE) },
            setter = { setCaptivePortalMode(context, it) },
            testIntValues = intArrayOf(CAPTIVE_PORTAL_MODE_AVOID)
        )

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setCaptivePortalMode(context, 5 /* mode */) }
    }

    @Test
    fun testPrivateDnsDefaultMode() {
        val original = Settings.Global.getString(resolver, PRIVATE_DNS_DEFAULT_MODE)

        try {
            val mode = getPrivateDnsModeAsString(PRIVATE_DNS_MODE_OPPORTUNISTIC)
            Settings.Global.putString(resolver, PRIVATE_DNS_DEFAULT_MODE, mode)
            assertEquals(mode, getPrivateDnsDefaultMode(context))

            setPrivateDnsDefaultMode(context, PRIVATE_DNS_MODE_OFF)
            assertEquals(
                getPrivateDnsModeAsString(PRIVATE_DNS_MODE_OFF),
                getPrivateDnsDefaultMode(context)
            )
        } finally {
            resetSettings(
                names = arrayOf(PRIVATE_DNS_DEFAULT_MODE),
                type = settingsTypeGlobal,
                values = arrayOf(original)
            )
        }

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setPrivateDnsDefaultMode(context, -1) }
    }

    @Test
    fun testConnectivityKeepPendingIntentDuration() {
        testIntSetting(
            names = arrayOf(CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS),
            type = settingsTypeSecure,
            value1 = Duration.ofMillis(testTime1),
            value2 = Duration.ofMillis(testTime2),
            getter = { getConnectivityKeepPendingIntentDuration(context, defaultDuration) },
            setter = { setConnectivityKeepPendingIntentDuration(context, it) },
            testIntValues = intArrayOf(testTime1.toInt())
        )

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setConnectivityKeepPendingIntentDuration(context, Duration.ofMillis(-1L)) }
    }

    @Test
    fun testMobileDataAlwaysOn() {
        testIntSetting(
            names = arrayOf(MOBILE_DATA_ALWAYS_ON),
            type = settingsTypeGlobal,
            value1 = false,
            value2 = true,
            getter = { getMobileDataAlwaysOn(context, true /* def */) },
            setter = { setMobileDataAlwaysOn(context, it) },
            testIntValues = intArrayOf(0)
        )
    }

    @Test
    fun testWifiAlwaysRequested() {
        testIntSetting(
            names = arrayOf(WIFI_ALWAYS_REQUESTED),
            type = settingsTypeGlobal,
            value1 = false,
            value2 = true,
            getter = { getWifiAlwaysRequested(context, true /* def */) },
            setter = { setWifiAlwaysRequested(context, it) },
            testIntValues = intArrayOf(0)
        )
    }

    @ConnectivityModuleTest // get/setIngressRateLimitInBytesPerSecond was added via module update
    @Test
    fun testInternetNetworkRateLimitInBytesPerSecond() {
        val defaultRate = getIngressRateLimitInBytesPerSecond(context)
        val testRate = 1000L
        setIngressRateLimitInBytesPerSecond(context, testRate)
        assertEquals(testRate, getIngressRateLimitInBytesPerSecond(context))

        setIngressRateLimitInBytesPerSecond(context, defaultRate)
        assertEquals(defaultRate, getIngressRateLimitInBytesPerSecond(context))

        assertFailsWith<IllegalArgumentException>("Expected failure, but setting accepted") {
            setIngressRateLimitInBytesPerSecond(context, -10)
        }
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @RequiresFlagsEnabled(Flags.FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG)
    fun testSetNetworkAvoidBadWifiWithSettings() {
        val testSubId = 1000
        val orgDefaultSubIdSetting = getNetworkAvoidBadWifiSetting(context, defaultSubId)
        val orgTestSubIdSetting = getNetworkAvoidBadWifiSetting(context, testSubId)
        val orgLegacySetting = getNetworkLegacyGlobalAvoidBadWifiSetting(context)
        try {
            // initialize NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI and NETWORK_AVOID_BAD_WIFI to null
            setNetworkAvoidBadWifiSetting(context, defaultSubId, null)
            setNetworkLegacyGlobalAvoidBadWifiSetting(context, null)

            // test with if new key NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI is set.
            //   format: key: "network_carrier_aware_avoid_bad_wifi/{subId}"
            //           value: "value"
            // and if old key NETWORK_AVOID_BAD_WIFI is set as well.
            //   format: "value"
            setNetworkAvoidBadWifi(
                context,
                defaultSubId,
                ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI_AVOID
            )
            assertAvoidBadWifiSettingEquals(
                arrayOf(Pair(defaultSubId, "1"))
            )
            assertEquals(
                "1",
                getNetworkLegacyGlobalAvoidBadWifiSetting(context)
            )

            setNetworkAvoidBadWifi(
                context,
                defaultSubId,
                ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI_IGNORE
            )
            assertAvoidBadWifiSettingEquals(
                arrayOf(Pair(defaultSubId, "0"))
            )
            assertEquals(
                "0",
                getNetworkLegacyGlobalAvoidBadWifiSetting(context)
            )

            setNetworkAvoidBadWifi(
                context,
                testSubId,
                ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI_AVOID
            )
            assertAvoidBadWifiSettingEquals(
                arrayOf(
                    Pair(defaultSubId, "0"),
                    Pair(testSubId, "1")
                )
            )
            assertEquals(
                "1",
                getNetworkLegacyGlobalAvoidBadWifiSetting(context)
            )

            setNetworkAvoidBadWifi(
                context,
                testSubId,
                ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI_IGNORE
            )
            assertAvoidBadWifiSettingEquals(
                arrayOf(
                    Pair(defaultSubId, "0"),
                    Pair(testSubId, "0")
                )
            )
            assertEquals(
                "0",
                getNetworkLegacyGlobalAvoidBadWifiSetting(context)
            )

            setNetworkAvoidBadWifi(
                context,
                testSubId,
                ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI_PROMPT
            )
            assertAvoidBadWifiSettingEquals(
                arrayOf(
                    Pair(defaultSubId, "0"),
                    Pair(testSubId, null)
                )
            )
            assertEquals(
                null,
                getNetworkLegacyGlobalAvoidBadWifiSetting(context)
            )

            setNetworkAvoidBadWifi(
                context,
                defaultSubId,
                ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI_PROMPT
            )
            assertAvoidBadWifiSettingEquals(
                arrayOf(
                    Pair(defaultSubId, null),
                    Pair(testSubId, null)
                )
            )
            assertEquals(
                null,
                getNetworkLegacyGlobalAvoidBadWifiSetting(context)
            )
        } finally {
            resetSettings(
                names = arrayOf(
                    getAvoidBadWifiSettingKey(defaultSubId),
                    getAvoidBadWifiSettingKey(testSubId),
                    NETWORK_AVOID_BAD_WIFI
                ),
                type = settingsTypeGlobal,
                values = arrayOf(
                    orgDefaultSubIdSetting,
                    orgTestSubIdSetting,
                    orgLegacySetting
                )
            )
        }
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @RequiresFlagsEnabled(Flags.FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG)
    fun testGetNetworkAvoidBadWifiWithCarrierAwareSettings() {
        val testSubId = 1000
        val orgDefaultSubIdSetting = getNetworkAvoidBadWifiSetting(context, defaultSubId)
        val orgTestSubIdSetting = getNetworkAvoidBadWifiSetting(context, testSubId)
        try {
            // initialize NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI to null
            setNetworkAvoidBadWifiSetting(context, defaultSubId, null)
            setNetworkLegacyGlobalAvoidBadWifiSetting(context, null)

            setNetworkAvoidBadWifiSetting(context, defaultSubId, "0")
            assertFalse(getNetworkAvoidBadWifi(context, defaultSubId))
            // test the default value for other subId is not affected
            assertTrue(getNetworkAvoidBadWifi(context, testSubId))

            setNetworkAvoidBadWifiSetting(context, defaultSubId, "1")
            assertTrue(getNetworkAvoidBadWifi(context, defaultSubId))
            // test the default value for other subId is not affected
            assertTrue(getNetworkAvoidBadWifi(context, testSubId))
        } finally {
            resetSettings(
                names = arrayOf(
                    getAvoidBadWifiSettingKey(defaultSubId),
                    getAvoidBadWifiSettingKey(testSubId)
                ),
                type = settingsTypeGlobal,
                values = arrayOf(
                    orgDefaultSubIdSetting,
                    orgTestSubIdSetting
                )
            )
        }
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @RequiresFlagsEnabled(Flags.FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG)
    fun testGetNetworkAvoidBadWifiWithSettings() {
        val testSubId = 1000
        val orgDefaultSubIdSetting = getNetworkAvoidBadWifiSetting(context, defaultSubId)
        val orgTestSubIdSetting = getNetworkAvoidBadWifiSetting(context, testSubId)
        val orgLegacySetting = getNetworkLegacyGlobalAvoidBadWifiSetting(context)
        try {
            // test if NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI is not set,
            // then it check NETWORK_AVOID_BAD_WIFI for backward compatibility.
            setNetworkAvoidBadWifiSetting(context, defaultSubId, null)

            setNetworkLegacyGlobalAvoidBadWifiSetting(context, "0")
            assertFalse(getNetworkAvoidBadWifi(context, defaultSubId))
            assertFalse(getNetworkAvoidBadWifi(context, testSubId))

            setNetworkLegacyGlobalAvoidBadWifiSetting(context, "1")
            assertTrue(getNetworkAvoidBadWifi(context, defaultSubId))
            assertTrue(getNetworkAvoidBadWifi(context, testSubId))
        } finally {
            resetSettings(
                names = arrayOf(
                    getAvoidBadWifiSettingKey(defaultSubId),
                    getAvoidBadWifiSettingKey(testSubId),
                    NETWORK_AVOID_BAD_WIFI
                ),
                type = settingsTypeGlobal,
                values = arrayOf(
                    orgDefaultSubIdSetting,
                    orgTestSubIdSetting,
                    orgLegacySetting
                )
            )
        }
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @RequiresFlagsEnabled(Flags.FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG)
    fun testGetNetworkAvoidBadWifiWithCarrierConfig() {
        assumeTrue(
            "skip test if device does not support telephony subscription feature",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
        )

        val orgCarrierAwareSetting = getNetworkAvoidBadWifiSetting(context, defaultSubId)
        val orgSetting = getNetworkLegacyGlobalAvoidBadWifiSetting(context)
        try {
            // unset the global settings, then it depends on the carrier config
            mCarrierConfigRule.acquireCarrierPrivilege(defaultSubId)
            setNetworkAvoidBadWifiSetting(context, defaultSubId, null)
            setNetworkLegacyGlobalAvoidBadWifiSetting(context, null)

            // default should be true if no carrier config key is set
            assertTrue(getNetworkAvoidBadWifi(context, defaultSubId))

            overrideAvoidBadWifiCarrierConfig(avoidBadWifi = true)
            assertTrue(getNetworkAvoidBadWifi(context, defaultSubId))

            overrideAvoidBadWifiCarrierConfig(avoidBadWifi = false)
            assertFalse(getNetworkAvoidBadWifi(context, defaultSubId))
        } finally {
            resetSettings(
                names = arrayOf(
                    getAvoidBadWifiSettingKey(defaultSubId),
                    NETWORK_AVOID_BAD_WIFI
                ),
                type = settingsTypeGlobal,
                values = arrayOf(
                    orgCarrierAwareSetting,
                    orgSetting
                )
            )
        }
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @RequiresFlagsEnabled(Flags.FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG)
    fun testShouldShowAvoidBadWifiToggleWithSettings() {
        val orgCarrierAwareSetting = getNetworkAvoidBadWifiSetting(context, defaultSubId)
        val orgSetting = getNetworkLegacyGlobalAvoidBadWifiSetting(context)
        try {
            // unset the NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI, and NETWORK_AVOID_BAD_WIFI,
            // it should be false since no carrier config is set
            setNetworkAvoidBadWifiSetting(context, defaultSubId, null)
            setNetworkLegacyGlobalAvoidBadWifiSetting(context, null)
            assertFalse(shouldShowAvoidBadWifiToggle(context, defaultSubId))

            // if any global settings is set, it should display the toggle.
            setNetworkAvoidBadWifiSetting(context, defaultSubId, "1")
            assertTrue(shouldShowAvoidBadWifiToggle(context, defaultSubId))

            setNetworkAvoidBadWifiSetting(context, defaultSubId, "0")
            assertTrue(shouldShowAvoidBadWifiToggle(context, defaultSubId))

            // unset the NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI,
            // then it check NETWORK_AVOID_BAD_WIFI for backward compatibility.
            setNetworkAvoidBadWifiSetting(context, defaultSubId, null)
            setNetworkLegacyGlobalAvoidBadWifiSetting(context, "1")
            assertTrue(shouldShowAvoidBadWifiToggle(context, defaultSubId))

            setNetworkLegacyGlobalAvoidBadWifiSetting(context, "0")
            assertTrue(shouldShowAvoidBadWifiToggle(context, defaultSubId))
        } finally {
            resetSettings(
                names = arrayOf(
                    getAvoidBadWifiSettingKey(defaultSubId),
                    NETWORK_AVOID_BAD_WIFI
                ),
                type = settingsTypeGlobal,
                values = arrayOf(
                    orgCarrierAwareSetting,
                    orgSetting
                )
            )
        }
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @RequiresFlagsEnabled(Flags.FLAG_AVOID_BAD_WIFI_FROM_CARRIER_CONFIG)
    fun testShouldShowAvoidBadWifiToggleWithCarrierConfig() {
        assumeTrue(
            "skip test if device does not support telephony subscription feature",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
        )
        val orgCarrierAwareSetting = getNetworkAvoidBadWifiSetting(context, defaultSubId)
        val orgSetting = getNetworkLegacyGlobalAvoidBadWifiSetting(context)
        try {
            // unset both NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI and KEY_AVOID_BAD_WIFI_BOOL settings,
            // then it depends on the carrier config
            mCarrierConfigRule.acquireCarrierPrivilege(defaultSubId)
            setNetworkAvoidBadWifiSetting(context, defaultSubId, null)
            setNetworkLegacyGlobalAvoidBadWifiSetting(context, null)

            // default should be false if KEY_AVOID_BAD_WIFI_BOOL is not set
            assertFalse(shouldShowAvoidBadWifiToggle(context, defaultSubId))

            // if KEY_AVOID_BAD_WIFI_BOOL is true, then check KEY_SHOW_AVOID_BAD_WIFI_TOGGLE_BOOL
            // default should be false if KEY_SHOW_AVOID_BAD_WIFI_TOGGLE_BOOL is not set
            overrideAvoidBadWifiCarrierConfig(avoidBadWifi = true)
            assertFalse(shouldShowAvoidBadWifiToggle(context, defaultSubId))

            // it should be true if KEY_AVOID_BAD_WIFI_BOOL is false
            overrideAvoidBadWifiCarrierConfig(avoidBadWifi = false)
            assertTrue(shouldShowAvoidBadWifiToggle(context, defaultSubId))

            // Even if the carrier says not to show the toggle, when the carrier says
            // not to avoid bad wifi it should be shown.
            // This is because it should not be possible to force a device to be stuck on bad wifi
            // and not give the user an option to change this.
            overrideAvoidBadWifiCarrierConfig(avoidBadWifi = false, showAvoidBadWifiToggle = false)
            assertTrue(shouldShowAvoidBadWifiToggle(context, defaultSubId))

            overrideAvoidBadWifiCarrierConfig(avoidBadWifi = false, showAvoidBadWifiToggle = true)
            assertTrue(shouldShowAvoidBadWifiToggle(context, defaultSubId))

            // if KEY_AVOID_BAD_WIFI_BOOL is true, then check KEY_SHOW_AVOID_BAD_WIFI_TOGGLE_BOOL
            overrideAvoidBadWifiCarrierConfig(avoidBadWifi = true, showAvoidBadWifiToggle = false)
            assertFalse(shouldShowAvoidBadWifiToggle(context, defaultSubId))

            overrideAvoidBadWifiCarrierConfig(avoidBadWifi = true, showAvoidBadWifiToggle = true)
            assertTrue(shouldShowAvoidBadWifiToggle(context, defaultSubId))
        } finally {
            resetSettings(
                names = arrayOf(
                    getAvoidBadWifiSettingKey(defaultSubId),
                    NETWORK_AVOID_BAD_WIFI
                ),
                type = settingsTypeGlobal,
                values = arrayOf(
                    orgCarrierAwareSetting,
                    orgSetting
                )
            )
        }
    }
}
