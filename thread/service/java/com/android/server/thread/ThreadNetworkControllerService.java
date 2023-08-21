/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.thread;

import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;

import android.net.thread.IThreadNetworkController;
import android.net.thread.ThreadNetworkController;

/** Implementation of the {@link ThreadNetworkController} API. */
public final class ThreadNetworkControllerService extends IThreadNetworkController.Stub {

    @Override
    public int getThreadVersion() {
        return THREAD_VERSION_1_3;
    }
}
