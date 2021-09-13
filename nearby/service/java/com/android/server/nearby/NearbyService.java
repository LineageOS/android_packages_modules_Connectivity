/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.nearby;

import android.content.Context;
import android.util.Log;

import com.android.server.SystemService;

/**
 * Service implementing nearby functionality. The actual implementation is delegated to
 * {@link NearbyServiceImpl}.
 */
// TODO(189954300): Implement nearby service.
public class NearbyService extends SystemService {
    private static final String TAG = "NearbyService";
    private static final boolean DBG = true;

    private final NearbyServiceImpl mImpl;

    public NearbyService(Context contextBase) {
        super(contextBase);
        mImpl = new NearbyServiceImpl(contextBase);
    }

    @Override
    public void onStart() {
        if (DBG) {
            Log.d(TAG, "Publishing NearbyService");
        }

    }

    @Override
    public void onBootPhase(int phase) {
    }
}
