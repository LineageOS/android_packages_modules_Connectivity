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

import android.util.Log
import com.android.testutils.tryTest
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.fail

private val TAG = CleanupTest::class.toString()

@RunWith(JUnit4::class)
class CleanupTest {
    class TestException1 : Exception()
    class TestException2 : Exception()

    @Test
    fun testNotThrow() {
        var x = 1
        tryTest {
            x = 2
            Log.e(TAG, "Do nothing")
        } cleanup {
            assert(x == 2)
            x = 3
            Log.e(TAG, "Do nothing")
        }
        assert(x == 3)
    }

    @Test
    fun testThrowTry() {
        var x = 1
        assertFailsWith<TestException1> {
            tryTest {
                x = 2
                throw TestException1()
                x = 4
            } cleanup {
                assert(x == 2)
                x = 3
                Log.e(TAG, "Do nothing")
            }
        }
        assert(x == 3)
    }

    @Test
    fun testThrowCleanup() {
        var x = 1
        assertFailsWith<TestException2> {
            tryTest {
                x = 2
                Log.e(TAG, "Do nothing")
            } cleanup {
                assert(x == 2)
                x = 3
                throw TestException2()
                x = 4
            }
        }
        assert(x == 3)
    }

    @Test
    fun testThrowBoth() {
        var x = 1
        try {
            tryTest {
                x = 2
                throw TestException1()
                x = 3
            } cleanup {
                assert(x == 2)
                x = 4
                throw TestException2()
                x = 5
            }
            fail("Expected failure with TestException1")
        } catch (e: TestException1) {
            assert(e.suppressedExceptions[0] is TestException2)
        }
        assert(x == 4)
    }
}
