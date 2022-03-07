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

package android.nearby.fastpair.provider.simulator.app;

import android.util.Log;

import com.google.errorprone.annotations.FormatMethod;

/** Sends log to logcat with TAG. */
public class AppLogger {
    private static final String TAG = "FastPairSimulator";

    @FormatMethod
    public static void log(String message, Object... objects) {
        Log.i(TAG, String.format(message, objects));
    }

    @FormatMethod
    public static void warning(String message, Object... objects) {
        Log.w(TAG, String.format(message, objects));
    }

    @FormatMethod
    public static void error(String message, Object... objects) {
        Log.e(TAG, String.format(message, objects));
    }

    private AppLogger() {
    }
}
