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

package com.android.net.module.util;

import static com.android.testutils.Cleanup.testAndCleanup;
import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertEquals;

import android.util.Log;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class CleanupTestJava {
    private static final String TAG = CleanupTestJava.class.getSimpleName();
    private static final class TestException1 extends Exception {}
    private static final class TestException2 extends Exception {}

    @Test
    public void testNotThrow() {
        final AtomicInteger x = new AtomicInteger(1);
        testAndCleanup(() -> {
            x.compareAndSet(1, 2);
            Log.e(TAG, "Do nothing");
        }, () -> {
                x.compareAndSet(2, 3);
                Log.e(TAG, "Do nothing");
            });
        assertEquals(3, x.get());
    }

    @Test
    public void testThrowTry() {
        final AtomicInteger x = new AtomicInteger(1);
        assertThrows(TestException1.class, () ->
                testAndCleanup(() -> {
                    x.compareAndSet(1, 2);
                    throw new TestException1();
                    // Java refuses to call x.set(3) here because this line is unreachable
                }, () -> {
                        x.compareAndSet(2, 3);
                        Log.e(TAG, "Do nothing");
                    })
        );
        assertEquals(3, x.get());
    }

    @Test
    public void testThrowCleanup() {
        final AtomicInteger x = new AtomicInteger(1);
        assertThrows(TestException2.class, () ->
                testAndCleanup(() -> {
                    x.compareAndSet(1, 2);
                    Log.e(TAG, "Do nothing");
                }, () -> {
                        x.compareAndSet(2, 3);
                        throw new TestException2();
                        // Java refuses to call x.set(4) here because this line is unreachable
                    })
        );
        assertEquals(3, x.get());
    }

    @Test
    public void testThrowBoth() {
        final AtomicInteger x = new AtomicInteger(1);
        assertThrows(TestException1.class, () ->
                testAndCleanup(() -> {
                    x.compareAndSet(1, 2);
                    throw new TestException1();
                }, () -> {
                        x.compareAndSet(2, 3);
                        throw new TestException2();
                    })
        );
        assertEquals(3, x.get());
    }
}
