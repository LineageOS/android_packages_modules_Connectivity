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

package com.android.net.module.util

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.testutils.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@SmallTest
class NetworkStatsUtilsTest {
    @Test
    fun testMultiplySafeByRational() {
        // Verify basic cases that the method equals to a * b / c.
        assertEquals(3 * 5 / 2, NetworkStatsUtils.multiplySafeByRational(3, 5, 2))

        // Verify input with zeros.
        assertEquals(0 * 7 / 3, NetworkStatsUtils.multiplySafeByRational(0, 7, 3))
        assertEquals(7 * 0 / 3, NetworkStatsUtils.multiplySafeByRational(7, 0, 3))
        assertEquals(0 * 0 / 1, NetworkStatsUtils.multiplySafeByRational(0, 0, 1))
        assertEquals(0, NetworkStatsUtils.multiplySafeByRational(0, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0, NetworkStatsUtils.multiplySafeByRational(Long.MAX_VALUE, 0, Long.MAX_VALUE))
        assertThrows(ArithmeticException::class.java) {
            NetworkStatsUtils.multiplySafeByRational(7, 3, 0)
        }
        assertThrows(ArithmeticException::class.java) {
            NetworkStatsUtils.multiplySafeByRational(0, 0, 0)
        }

        // Verify cases where a * b overflows.
        assertEquals(101, NetworkStatsUtils.multiplySafeByRational(
                101, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(721, NetworkStatsUtils.multiplySafeByRational(
                Long.MAX_VALUE, 721, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, NetworkStatsUtils.multiplySafeByRational(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertThrows(ArithmeticException::class.java) {
            NetworkStatsUtils.multiplySafeByRational(Long.MAX_VALUE, Long.MAX_VALUE, 0)
        }
    }
}