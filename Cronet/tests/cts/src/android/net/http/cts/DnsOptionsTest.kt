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

package android.net.http.cts

import android.net.http.DnsOptions
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DnsOptionsTest {

    @Test
    fun testDnsOptions_defaultValues() {
        val options = DnsOptions.Builder().build()

        assertNull(options.persistHostCache)
        assertNull(options.persistHostCachePeriod)
        assertNull(options.enableStaleDns)
        assertNull(options.staleDnsOptions)
        assertNull(options.useHttpStackDnsResolver)
        assertNull(options.preestablishConnectionsToStaleDnsResults)
    }

    @Test
    fun testDnsOptions_persistHostCache_returnSetValue() {
        val options = DnsOptions.Builder().setPersistHostCache(true).build()

        assertNotNull(options.persistHostCache)
        assertTrue(options.persistHostCache!!)
    }

    @Test
    fun testDnsOptions_persistHostCachePeriod_returnSetValue() {
        val period = Duration.ofMillis(12345)
        val options = DnsOptions.Builder().setPersistHostCachePeriod(period).build()

        assertEquals(period, options.persistHostCachePeriod)
    }

    @Test
    fun testDnsOptions_enableStaleDns_returnSetValue() {
        val options = DnsOptions.Builder().setEnableStaleDns(true).build()

        assertNotNull(options.enableStaleDns)
        assertTrue(options.enableStaleDns!!)
    }

    @Test
    fun testDnsOptions_useHttpStackDnsResolver_returnsSetValue() {
        val options = DnsOptions.Builder().setUseHttpStackDnsResolver(true).build()

        assertNotNull(options.useHttpStackDnsResolver)
        assertTrue(options.useHttpStackDnsResolver!!)
    }

    @Test
    fun testDnsOptions_preestablishConnectionsToStaleDnsResults_returnsSetValue() {
        val options = DnsOptions.Builder().setPreestablishConnectionsToStaleDnsResults(true).build()

        assertNotNull(options.preestablishConnectionsToStaleDnsResults)
        assertTrue(options.preestablishConnectionsToStaleDnsResults!!)
    }

    @Test
    fun testStaleDnsOptions_defaultValues() {
        val options = DnsOptions.StaleDnsOptions.Builder().build()

        assertNull(options.allowCrossNetworkUsage)
        assertNull(options.freshLookupTimeoutMillis)
        assertNull(options.maxExpiredDelayMillis)
        assertNull(options.useStaleOnNameNotResolved)
    }

    @Test
    fun testStaleDnsOptions_allowCrossNetworkUsage_returnsSetValue() {
        val options = DnsOptions.StaleDnsOptions.Builder().setAllowCrossNetworkUsage(true).build()

        assertNotNull(options.allowCrossNetworkUsage)
        assertTrue(options.allowCrossNetworkUsage!!)
    }

    @Test
    fun testStaleDnsOptions_freshLookupTimeout_returnsSetValue() {
        val duration = Duration.ofMillis(12345)
        val options = DnsOptions.StaleDnsOptions.Builder().setFreshLookupTimeout(duration).build()

        assertNotNull(options.freshLookupTimeoutMillis)
        assertEquals(duration.toMillis(), options.freshLookupTimeoutMillis!!)
    }

    @Test
    fun testStaleDnsOptions_useStaleOnNameNotResolved_returnsSetValue() {
        val options =
                DnsOptions.StaleDnsOptions.Builder().setUseStaleOnNameNotResolved(true).build()

        assertNotNull(options.useStaleOnNameNotResolved)
        assertTrue(options.useStaleOnNameNotResolved!!)
    }

    @Test
    fun testStaleDnsOptions_maxExpiredDelayMillis_returnsSetValue() {
        val duration = Duration.ofMillis(12345)
        val options = DnsOptions.StaleDnsOptions.Builder().setMaxExpiredDelay(duration).build()

        assertNotNull(options.maxExpiredDelayMillis)
        assertEquals(duration.toMillis(), options.maxExpiredDelayMillis!!)
    }
}
