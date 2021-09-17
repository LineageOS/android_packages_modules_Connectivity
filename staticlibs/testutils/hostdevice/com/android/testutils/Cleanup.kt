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

@file:JvmName("Cleanup")

package com.android.testutils

import com.android.testutils.ExceptionUtils.ThrowingRunnable
import javax.annotation.CheckReturnValue

/**
 * Utility to do cleanup in tests without replacing exceptions with those from a finally block.
 *
 * This utility is meant for tests that want to do cleanup after they execute their test
 * logic, whether the test fails (and throws) or not.
 *
 * The usual way of doing this is to have a try{}finally{} block and put cleanup in finally{}.
 * However, if any code in finally{} throws, the exception thrown in finally{} is thrown before
 * any thrown in try{} ; that means errors reported from tests are from finally{} even if they
 * have been caused by errors in try{}. This is unhelpful in tests, because it results in a
 * stacktrace for a symptom rather than a stacktrace for a cause.
 *
 * To alleviate this, tests are encouraged to make sure the code in finally{} can't throw, or
 * that the code in try{} can't cause it to fail. This is not always realistic ; not only does
 * it require the developer thinks about complex interactions of code, test code often relies
 * on bricks provided by other teams, not controlled by the team writing the test, which may
 * start throwing with an update (see b/198998862 for an example).
 *
 * This utility allows a different approach : it offers a new construct, tryTest{}cleanup{} similar
 * to try{}finally{}, but that will always throw the first exception that happens. In other words,
 * if only tryTest{} throws or only cleanup{} throws, that exception will be thrown, but contrary
 * to the standard try{}finally{}, if both throws, the construct throws the exception that happened
 * in tryTest{} rather than the one that happened in cleanup{}.
 *
 * Kotlin usage is as try{}finally{} :
 * tryTest {
 *   testing code
 * } cleanup {
 *   cleanup code
 * }
 *
 * Java doesn't allow this kind of syntax, so instead a function taking 2 lambdas is provided.
 * testAndCleanup(() -> {
 *   testing code
 * }, () -> {
 *   cleanup code
 * });
 */
class ExceptionCleanupBlock(val originalException: Exception?) {
    inline infix fun cleanup(block: () -> Unit) {
        try {
            block()
            if (null != originalException) throw originalException
        } catch (e: Exception) {
            if (null == originalException) {
                throw e
            } else {
                originalException.addSuppressed(e)
                throw originalException
            }
        }
    }
}

@CheckReturnValue
inline fun tryTest(block: () -> Unit): ExceptionCleanupBlock {
    try {
        block()
    } catch (e: Exception) {
        return ExceptionCleanupBlock(e)
    }
    return ExceptionCleanupBlock(null)
}

// Java support
fun testAndCleanup(tryBlock: ThrowingRunnable, cleanupBlock: ThrowingRunnable) {
    tryTest {
        tryBlock.run()
    } cleanup {
        cleanupBlock.run()
    }
}
