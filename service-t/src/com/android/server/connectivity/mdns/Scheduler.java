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

package com.android.server.connectivity.mdns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The interface for scheduler.
 */
public interface Scheduler {
    /**
     * Set a message to be sent after a specified delay.
     */
    boolean sendDelayedMessage(int what, int arg1, int arg2, @Nullable Object obj,
            long delayMs);

    /**
     * Remove a scheduled message.
     */
    void removeDelayedMessage(int what);

    /**
     * Check if there is a scheduled message.
     */
    boolean hasDelayedMessage(int what);

    /**
     * Set a runnable to be executed after a specified delay.
     */
    boolean postDelayed(@NonNull Runnable runnable, long delayMs);

    /**
     * Close this object.
     */
    void close();
}
