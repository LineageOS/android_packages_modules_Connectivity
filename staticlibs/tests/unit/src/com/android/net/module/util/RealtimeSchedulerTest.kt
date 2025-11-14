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

package com.android.net.module.util

import android.os.Build
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.tryTest
import com.android.testutils.visibleOnHandlerThread
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class RealtimeSchedulerTest {

    private val TIMEOUT_MS = 1000L
    private val TOLERANCE_MS = 50L
    private class TestHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            val pair = msg.obj as Pair<ConditionVariable, MutableList<Long>>
            val executionTimes = pair.second
            executionTimes.add(SystemClock.elapsedRealtime())
            val cv = pair.first
            cv.open()
        }
    }
    private val thread = HandlerThread(RealtimeSchedulerTest::class.simpleName).apply { start() }
    private val handler by lazy { TestHandler(thread.looper) }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    @Test
    fun testMultiplePostDelayedTasks() {
        val scheduler = RealtimeScheduler(handler)
        tryTest {
            val initialTimeMs = SystemClock.elapsedRealtime()
            val executionTimes = mutableListOf<Long>()
            val cv = ConditionVariable()
            handler.post {
                scheduler.postDelayed(
                    { executionTimes.add(SystemClock.elapsedRealtime() - initialTimeMs) }, 0)
                scheduler.postDelayed(
                    { executionTimes.add(SystemClock.elapsedRealtime() - initialTimeMs) }, 200)
                val toBeRemoved = Runnable {
                    executionTimes.add(SystemClock.elapsedRealtime() - initialTimeMs)
                }
                scheduler.postDelayed(toBeRemoved, 250)
                scheduler.removeDelayedRunnable(toBeRemoved)
                scheduler.postDelayed(
                    { executionTimes.add(SystemClock.elapsedRealtime() - initialTimeMs) }, 100)
                scheduler.postDelayed({
                    executionTimes.add(SystemClock.elapsedRealtime() - initialTimeMs)
                    cv.open() }, 300)
            }
            cv.block(TIMEOUT_MS)
            assertEquals(4, executionTimes.size)
            assertThat(executionTimes[0]).isIn(Range.closed(0L, TOLERANCE_MS))
            assertThat(executionTimes[1]).isIn(Range.closed(100L, 100 + TOLERANCE_MS))
            assertThat(executionTimes[2]).isIn(Range.closed(200L, 200 + TOLERANCE_MS))
            assertThat(executionTimes[3]).isIn(Range.closed(300L, 300 + TOLERANCE_MS))
        } cleanup {
            visibleOnHandlerThread(handler) { scheduler.close() }
        }
    }

    @Test
    fun testMultipleSendDelayedMessages() {
        val scheduler = RealtimeScheduler(handler)
        tryTest {
            val MSG_ID_0 = 0
            val MSG_ID_1 = 1
            val MSG_ID_2 = 2
            val MSG_ID_3 = 3
            val MSG_ID_4 = 4
            val initialTimeMs = SystemClock.elapsedRealtime()
            val executionTimes = mutableListOf<Long>()
            val cv = ConditionVariable()
            handler.post {
                scheduler.sendDelayedMessage(
                    MSG_ID_0, 0, 0, Pair(ConditionVariable(), executionTimes), 0)
                scheduler.sendDelayedMessage(
                    MSG_ID_1, 0, 0, Pair(ConditionVariable(), executionTimes), 200)
                scheduler.sendDelayedMessage(
                    MSG_ID_4, 0, 0, Pair(ConditionVariable(), executionTimes), 250)
                scheduler.removeDelayedMessage(MSG_ID_4)
                scheduler.sendDelayedMessage(
                    MSG_ID_2, 0, 0, Pair(ConditionVariable(), executionTimes), 100)
                scheduler.sendDelayedMessage(MSG_ID_3, 0, 0, Pair(cv, executionTimes), 300)
            }
            cv.block(TIMEOUT_MS)
            assertEquals(4, executionTimes.size)
            assertThat(executionTimes[0] - initialTimeMs).isIn(Range.closed(0L, TOLERANCE_MS))
            assertThat(executionTimes[1] - initialTimeMs)
                .isIn(Range.closed(100L, 100 + TOLERANCE_MS))
            assertThat(executionTimes[2] - initialTimeMs)
                .isIn(Range.closed(200L, 200 + TOLERANCE_MS))
            assertThat(executionTimes[3] - initialTimeMs)
                .isIn(Range.closed(300L, 300 + TOLERANCE_MS))
        } cleanup {
            visibleOnHandlerThread(handler) { scheduler.close() }
        }
    }
}
