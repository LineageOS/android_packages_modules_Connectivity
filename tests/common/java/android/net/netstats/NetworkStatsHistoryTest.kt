/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.net.netstats

import android.net.NetworkStatsHistory
import android.text.format.DateUtils
import androidx.test.filters.SmallTest
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.SC_V2
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ConnectivityModuleTest
@RunWith(JUnit4::class)
@SmallTest
class NetworkStatsHistoryTest {
    @Rule
    @JvmField
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = SC_V2)

    @Test
    fun testBuilder() {
        val entry1 = NetworkStatsHistory.Entry(10, 30, 40, 4, 50, 5, 60)
        val entry2 = NetworkStatsHistory.Entry(30, 15, 3, 41, 7, 1, 0)
        val entry3 = NetworkStatsHistory.Entry(7, 301, 11, 14, 31, 2, 80)
        val statsEmpty = NetworkStatsHistory
                .Builder(DateUtils.HOUR_IN_MILLIS, /* initialCapacity */ 10).build()
        assertEquals(0, statsEmpty.entries.size)
        assertEquals(DateUtils.HOUR_IN_MILLIS, statsEmpty.bucketDuration)
        val statsSingle = NetworkStatsHistory
                .Builder(DateUtils.HOUR_IN_MILLIS, /* initialCapacity */ 8)
                .addEntry(entry1)
                .build()
        statsSingle.assertEntriesEqual(entry1)
        assertEquals(DateUtils.HOUR_IN_MILLIS, statsSingle.bucketDuration)

        // Verify the builder throws if the timestamp of added entry is not greater than
        // that of any previously-added entry.
        assertFailsWith(IllegalArgumentException::class) {
            NetworkStatsHistory
                    .Builder(DateUtils.SECOND_IN_MILLIS, /* initialCapacity */ 0)
                    .addEntry(entry1).addEntry(entry2).addEntry(entry3)
                    .build()
        }

        val statsMultiple = NetworkStatsHistory
                .Builder(DateUtils.SECOND_IN_MILLIS, /* initialCapacity */ 0)
                .addEntry(entry3).addEntry(entry1).addEntry(entry2)
                .build()
        assertEquals(DateUtils.SECOND_IN_MILLIS, statsMultiple.bucketDuration)
        statsMultiple.assertEntriesEqual(entry3, entry1, entry2)
    }

    fun NetworkStatsHistory.assertEntriesEqual(vararg entries: NetworkStatsHistory.Entry) {
        assertEquals(entries.size, this.entries.size)
        entries.forEachIndexed { i, element ->
            assertEquals(element, this.entries[i])
        }
    }
}