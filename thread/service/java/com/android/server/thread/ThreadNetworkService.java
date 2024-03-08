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

import android.annotation.Nullable;
import android.content.Context;
import android.net.thread.IThreadNetworkController;
import android.net.thread.IThreadNetworkManager;

import com.android.server.SystemService;

import java.util.Collections;
import java.util.List;

/**
 * Implementation of the Thread network service. This is the entry point of Android Thread feature.
 */
public class ThreadNetworkService extends IThreadNetworkManager.Stub {
    private final Context mContext;
    @Nullable private ThreadNetworkControllerService mControllerService;

    /** Creates a new {@link ThreadNetworkService} object. */
    public ThreadNetworkService(Context context) {
        mContext = context;
    }

    /**
     * Called by the service initializer.
     *
     * @see com.android.server.SystemService#onBootPhase
     */
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mControllerService = ThreadNetworkControllerService.newInstance(mContext);
            mControllerService.initialize();
        }
    }

    @Override
    public List<IThreadNetworkController> getAllThreadNetworkControllers() {
        if (mControllerService == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(mControllerService);
    }
}
