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

package com.android.server.net

import android.net.NetworkStats.Entry
import com.android.testutils.DevSdkIgnoreRunner
import java.time.Clock
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@RunWith(DevSdkIgnoreRunner::class)
class TrafficStatsRateLimitCacheTest {
    companion object {
        private const val expiryDurationMs = 1000L
        private const val maxSize = 2
    }

    private val clock = mock(Clock::class.java)
    private val entry = mock(Entry::class.java)
    private val cache = TrafficStatsRateLimitCache(clock, expiryDurationMs, maxSize)

    @Test
    fun testGet_returnsEntryIfNotExpired() {
        cache.put("iface", 2, entry)
        doReturn(500L).`when`(clock).millis() // Set clock to before expiry
        val result = cache.get("iface", 2)
        assertEquals(entry, result)
    }

    @Test
    fun testGet_returnsNullIfExpired() {
        cache.put("iface", 2, entry)
        doReturn(2000L).`when`(clock).millis() // Set clock to after expiry
        assertNull(cache.get("iface", 2))
    }

    @Test
    fun testGet_returnsNullForNonExistentKey() {
        val result = cache.get("otherIface", 99)
        assertNull(result)
    }

    @Test
    fun testPutAndGet_retrievesCorrectEntryForDifferentKeys() {
        val entry1 = mock(Entry::class.java)
        val entry2 = mock(Entry::class.java)

        cache.put("iface1", 2, entry1)
        cache.put("iface2", 4, entry2)

        assertEquals(entry1, cache.get("iface1", 2))
        assertEquals(entry2, cache.get("iface2", 4))
    }

    @Test
    fun testPut_overridesExistingEntry() {
        val entry1 = mock(Entry::class.java)
        val entry2 = mock(Entry::class.java)

        cache.put("iface", 2, entry1)
        cache.put("iface", 2, entry2) // Put with the same key

        assertEquals(entry2, cache.get("iface", 2))
    }

    @Test
    fun testPut_removeLru() {
        // Assumes max size is 2. Verify eldest entry get removed.
        val entry1 = mock(Entry::class.java)
        val entry2 = mock(Entry::class.java)
        val entry3 = mock(Entry::class.java)

        cache.put("iface1", 2, entry1)
        cache.put("iface2", 4, entry2)
        cache.put("iface3", 8, entry3)

        assertNull(cache.get("iface1", 2))
        assertEquals(entry2, cache.get("iface2", 4))
        assertEquals(entry3, cache.get("iface3", 8))
    }

    @Test
    fun testGetOrCompute_cacheHit() {
        val entry1 = mock(Entry::class.java)

        cache.put("iface1", 2, entry1)

        // Set clock to before expiry.
        doReturn(500L).`when`(clock).millis()

        // Now call getOrCompute
        val result = cache.getOrCompute("iface1", 2) {
            fail("Supplier should not be called")
        }

        // Assertions
        assertEquals(entry1, result) // Should get the cached entry.
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testGetOrCompute_cacheMiss() {
        val entry1 = mock(Entry::class.java)

        cache.put("iface1", 2, entry1)

        // Set clock to after expiry.
        doReturn(1500L).`when`(clock).millis()

        // Mock the supplier to return our network stats entry.
        val supplier = mock(Supplier::class.java) as Supplier<Entry>
        doReturn(entry1).`when`(supplier).get()

        // Now call getOrCompute.
        val result = cache.getOrCompute("iface1", 2, supplier)

        // Assertions.
        assertEquals(entry1, result) // Should get the cached entry.
        verify(supplier).get()
    }

    @Test
    fun testClear() {
        cache.put("iface", 2, entry)
        cache.clear()
        assertNull(cache.get("iface", 2))
    }
}
