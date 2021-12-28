/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.ethernet;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import com.android.server.SystemService;

public final class EthernetService extends SystemService {

    private static final String TAG = "EthernetService";
    private static final String THREAD_NAME = "EthernetServiceThread";
    private final EthernetServiceImpl mImpl;

    public EthernetService(Context context) {
        super(context);
        final HandlerThread handlerThread = new HandlerThread(THREAD_NAME);
        handlerThread.start();
        mImpl = new EthernetServiceImpl(
                    context, handlerThread.getThreadHandler(),
                    new EthernetTracker(context, handlerThread.getThreadHandler()));
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Registering service " + Context.ETHERNET_SERVICE);
        publishBinderService(Context.ETHERNET_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mImpl.start();
        }
    }
}
