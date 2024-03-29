/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.os.Build
import androidx.test.filters.SmallTest
import com.android.modules.utils.build.SdkLevel
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.assertParcelingIsLossless
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(DevSdkIgnoreRunner::class)
class CaptivePortalDataTest {
    @Rule @JvmField
    val ignoreRule = DevSdkIgnoreRule()

    private val data = CaptivePortalData.Builder()
            .setRefreshTime(123L)
            .setUserPortalUrl(Uri.parse("https://portal.example.com/test"))
            .setVenueInfoUrl(Uri.parse("https://venue.example.com/test"))
            .setSessionExtendable(true)
            .setBytesRemaining(456L)
            .setExpiryTime(789L)
            .setCaptive(true)
            .apply {
                if (SdkLevel.isAtLeastS()) {
                    setVenueFriendlyName("venue friendly name")
                }
            }
            .build()

    private val dataFromPasspoint = CaptivePortalData.Builder()
            .setCaptive(true)
            .apply {
                if (SdkLevel.isAtLeastS()) {
                    setVenueFriendlyName("venue friendly name")
                    setUserPortalUrl(Uri.parse("https://tc.example.com/passpoint"),
                            CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT)
                    setVenueInfoUrl(Uri.parse("https://venue.example.com/passpoint"),
                            CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT)
                }
            }
            .build()

    private fun makeBuilder() = CaptivePortalData.Builder(data)

    @Test
    fun testParcelUnparcel() {
        assertParcelingIsLossless(data)
        assertParcelingIsLossless(dataFromPasspoint)

        assertParcelingIsLossless(makeBuilder().setUserPortalUrl(null).build())
        assertParcelingIsLossless(makeBuilder().setVenueInfoUrl(null).build())
    }

    @Test
    fun testEquals() {
        assertEquals(data, makeBuilder().build())

        assertNotEqualsAfterChange { it.setRefreshTime(456L) }
        assertNotEqualsAfterChange { it.setUserPortalUrl(Uri.parse("https://example.com/")) }
        assertNotEqualsAfterChange { it.setUserPortalUrl(null) }
        assertNotEqualsAfterChange { it.setVenueInfoUrl(Uri.parse("https://example.com/")) }
        assertNotEqualsAfterChange { it.setVenueInfoUrl(null) }
        assertNotEqualsAfterChange { it.setSessionExtendable(false) }
        assertNotEqualsAfterChange { it.setBytesRemaining(789L) }
        assertNotEqualsAfterChange { it.setExpiryTime(12L) }
        assertNotEqualsAfterChange { it.setCaptive(false) }

        if (SdkLevel.isAtLeastS()) {
            assertNotEqualsAfterChange { it.setVenueFriendlyName("another friendly name") }
            assertNotEqualsAfterChange { it.setVenueFriendlyName(null) }

            assertEquals(dataFromPasspoint, CaptivePortalData.Builder(dataFromPasspoint).build())
            assertNotEqualsAfterChange { it.setUserPortalUrl(
                    Uri.parse("https://tc.example.com/passpoint")) }
            assertNotEqualsAfterChange { it.setUserPortalUrl(
                    Uri.parse("https://tc.example.com/passpoint"),
                    CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_OTHER) }
            assertNotEqualsAfterChange { it.setUserPortalUrl(
                    Uri.parse("https://tc.example.com/other"),
                    CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT) }
            assertNotEqualsAfterChange { it.setUserPortalUrl(
                    Uri.parse("https://tc.example.com/passpoint"),
                    CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_OTHER) }
            assertNotEqualsAfterChange { it.setVenueInfoUrl(
                    Uri.parse("https://venue.example.com/passpoint")) }
            assertNotEqualsAfterChange { it.setVenueInfoUrl(
                    Uri.parse("https://venue.example.com/other"),
                    CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT) }
            assertNotEqualsAfterChange { it.setVenueInfoUrl(
                    Uri.parse("https://venue.example.com/passpoint"),
                    CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_OTHER) }
        }
    }

    @Test
    fun testUserPortalUrl() {
        assertEquals(Uri.parse("https://portal.example.com/test"), data.userPortalUrl)
    }

    @Test
    fun testVenueInfoUrl() {
        assertEquals(Uri.parse("https://venue.example.com/test"), data.venueInfoUrl)
    }

    @Test
    fun testIsSessionExtendable() {
        assertTrue(data.isSessionExtendable)
    }

    @Test
    fun testByteLimit() {
        assertEquals(456L, data.byteLimit)
        // Test byteLimit unset.
        assertEquals(-1L, CaptivePortalData.Builder(null).build().byteLimit)
    }

    @Test
    fun testRefreshTimeMillis() {
        assertEquals(123L, data.refreshTimeMillis)
    }

    @Test
    fun testExpiryTimeMillis() {
        assertEquals(789L, data.expiryTimeMillis)
        // Test expiryTimeMillis unset.
        assertEquals(-1L, CaptivePortalData.Builder(null).build().expiryTimeMillis)
    }

    @Test
    fun testIsCaptive() {
        assertTrue(data.isCaptive)
        assertFalse(makeBuilder().setCaptive(false).build().isCaptive)
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.R)
    fun testVenueFriendlyName() {
        assertEquals("venue friendly name", data.venueFriendlyName)
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.R)
    fun testGetVenueInfoUrlSource() {
        assertEquals(CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_OTHER,
                data.venueInfoUrlSource)
        assertEquals(CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT,
                dataFromPasspoint.venueInfoUrlSource)
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.R)
    fun testGetUserPortalUrlSource() {
        assertEquals(CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_OTHER,
                data.userPortalUrlSource)
        assertEquals(CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT,
                dataFromPasspoint.userPortalUrlSource)
    }

    private fun CaptivePortalData.mutate(mutator: (CaptivePortalData.Builder) -> Unit) =
            CaptivePortalData.Builder(this).apply { mutator(this) }.build()

    private fun assertNotEqualsAfterChange(mutator: (CaptivePortalData.Builder) -> Unit) {
        assertNotEquals(data, data.mutate(mutator))
    }
}