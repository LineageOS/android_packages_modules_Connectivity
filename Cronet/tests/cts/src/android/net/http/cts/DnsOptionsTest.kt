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
import android.net.http.DnsOptions.DNS_OPTION_ENABLED
import android.net.http.DnsOptions.DNS_OPTION_UNSPECIFIED
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DnsOptionsTest {

    @Test
    fun testDnsOptions_defaultValues() {
        val options = DnsOptions.Builder().build()

        assertEquals(DNS_OPTION_UNSPECIFIED, options.persistHostCacheEnabled)
        assertNull(options.persistHostCachePeriod)
        assertEquals(DNS_OPTION_UNSPECIFIED, options.staleDnsEnabled)
        assertNull(options.staleDnsOptions)
        assertEquals(DNS_OPTION_UNSPECIFIED, options.useHttpStackDnsResolverEnabled)
        assertEquals(DNS_OPTION_UNSPECIFIED,
                options.preestablishConnectionsToStaleDnsResultsEnabled)
    }

    @Test
    fun testDnsOptions_persistHostCache_returnSetValue() {
        val options = DnsOptions.Builder()
                .setPersistHostCacheEnabled(DNS_OPTION_ENABLED)
                .build()

        assertEquals(DNS_OPTION_ENABLED, options.persistHostCacheEnabled)
    }

    @Test
    fun testDnsOptions_persistHostCachePeriod_returnSetValue() {
        val period = Duration.ofMillis(12345)
        val options = DnsOptions.Builder().setPersistHostCachePeriod(period).build()

        assertEquals(period, options.persistHostCachePeriod)
    }

    @Test
    fun testDnsOptions_enableStaleDns_returnSetValue() {
        val options = DnsOptions.Builder()
                .setStaleDnsEnabled(DNS_OPTION_ENABLED)
                .build()

        assertEquals(DNS_OPTION_ENABLED, options.staleDnsEnabled)
    }

    @Test
    fun testDnsOptions_useHttpStackDnsResolver_returnsSetValue() {
        val options = DnsOptions.Builder()
                .setUseHttpStackDnsResolverEnabled(DNS_OPTION_ENABLED)
                .build()

        assertEquals(DNS_OPTION_ENABLED, options.useHttpStackDnsResolverEnabled)
    }

    @Test
    fun testDnsOptions_preestablishConnectionsToStaleDnsResults_returnsSetValue() {
        val options = DnsOptions.Builder()
                .setPreestablishConnectionsToStaleDnsResultsEnabled(DNS_OPTION_ENABLED)
                .build()

        assertEquals(DNS_OPTION_ENABLED,
                options.preestablishConnectionsToStaleDnsResultsEnabled)
    }

    @Test
    fun testDnsOptions_setStaleDnsOptions_returnsSetValues() {
        val staleOptions = DnsOptions.StaleDnsOptions.Builder()
                .setAllowCrossNetworkUsageEnabled(DNS_OPTION_ENABLED)
                .setFreshLookupTimeout(Duration.ofMillis(1234))
                .build()
        val options = DnsOptions.Builder()
                .setStaleDnsEnabled(DNS_OPTION_ENABLED)
                .setStaleDnsOptions(staleOptions)
                .build()

        assertEquals(DNS_OPTION_ENABLED, options.staleDnsEnabled)
        assertEquals(staleOptions, options.staleDnsOptions)
    }

    @Test
    fun testStaleDnsOptions_defaultValues() {
        val options = DnsOptions.StaleDnsOptions.Builder().build()

        assertEquals(DNS_OPTION_UNSPECIFIED, options.allowCrossNetworkUsageEnabled)
        assertNull(options.freshLookupTimeout)
        assertNull(options.maxExpiredDelay)
        assertEquals(DNS_OPTION_UNSPECIFIED, options.useStaleOnNameNotResolvedEnabled)
    }

    @Test
    fun testStaleDnsOptions_allowCrossNetworkUsage_returnsSetValue() {
        val options = DnsOptions.StaleDnsOptions.Builder()
                .setAllowCrossNetworkUsageEnabled(DNS_OPTION_ENABLED).build()

        assertEquals(DNS_OPTION_ENABLED, options.allowCrossNetworkUsageEnabled)
    }

    @Test
    fun testStaleDnsOptions_freshLookupTimeout_returnsSetValue() {
        val duration = Duration.ofMillis(12345)
        val options = DnsOptions.StaleDnsOptions.Builder().setFreshLookupTimeout(duration).build()

        assertNotNull(options.freshLookupTimeout)
        assertEquals(duration, options.freshLookupTimeout!!)
    }

    @Test
    fun testStaleDnsOptions_useStaleOnNameNotResolved_returnsSetValue() {
        val options = DnsOptions.StaleDnsOptions.Builder()
                .setUseStaleOnNameNotResolvedEnabled(DNS_OPTION_ENABLED)
                .build()

        assertEquals(DNS_OPTION_ENABLED, options.useStaleOnNameNotResolvedEnabled)
    }

    @Test
    fun testStaleDnsOptions_maxExpiredDelayMillis_returnsSetValue() {
        val duration = Duration.ofMillis(12345)
        val options = DnsOptions.StaleDnsOptions.Builder().setMaxExpiredDelay(duration).build()

        assertNotNull(options.maxExpiredDelay)
        assertEquals(duration, options.maxExpiredDelay!!)
    }
}
