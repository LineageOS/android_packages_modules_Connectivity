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

@file:JvmName("ConcurrentUtils")

package com.android.testutils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.system.measureTimeMillis
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// For Java usage
fun durationOf(fn: Runnable) = measureTimeMillis { fn.run() }

fun CountDownLatch.await(timeoutMs: Long): Boolean = await(timeoutMs, TimeUnit.MILLISECONDS)

/**
 * Quit resources provided as a list by a supplier.
 *
 * The supplier may return more resources as the process progresses, for example while interrupting
 * threads and waiting for them to finish they may spawn more threads, so this implements a
 * [maxRetryCount] which, in this case, would be the maximum length of the thread chain that can be
 * terminated.
 */
fun <T> quitResources(
    maxRetryCount: Int,
    supplier: () -> List<T>,
    terminator: Consumer<T>
) {
    // Run it multiple times since new threads might be generated in a thread
    // that is about to be terminated
    for (retryCount in 0 until maxRetryCount) {
        val resourcesToBeCleared = supplier()
        if (resourcesToBeCleared.isEmpty()) return
        for (resource in resourcesToBeCleared) {
            terminator.accept(resource)
        }
    }
    assertEmpty(supplier())
}

/**
 * Implementation of [quitResources] to interrupt and wait for [ExecutorService]s to finish.
 */
@JvmOverloads
fun quitExecutorServices(
    maxRetryCount: Int,
    interrupt: Boolean = true,
    timeoutMs: Long = 10_000L,
    supplier: () -> List<ExecutorService>
) {
    quitResources(maxRetryCount, supplier) { ecs ->
        if (interrupt) {
            ecs.shutdownNow()
        }
        assertTrue(ecs.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS),
            "ExecutorServices did not terminate within timeout")
    }
}

/**
 * Implementation of [quitResources] to interrupt and wait for [Thread]s to finish.
 */
@JvmOverloads
fun quitThreads(
    maxRetryCount: Int,
    interrupt: Boolean = true,
    timeoutMs: Long = 10_000L,
    supplier: () -> List<Thread>
) {
    quitResources(maxRetryCount, supplier) { th ->
        if (interrupt) {
            th.interrupt()
        }
        th.join(timeoutMs)
        assertFalse(th.isAlive, "Threads did not terminate within timeout.")
    }
}
