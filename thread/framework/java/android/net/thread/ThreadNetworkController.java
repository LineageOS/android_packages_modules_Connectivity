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

package android.net.thread;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides the primary API for controlling all aspects of a Thread network.
 *
 * @hide
 */
@FlaggedApi(ThreadNetworkFlags.FLAG_THREAD_ENABLED)
@SystemApi
public final class ThreadNetworkController {

    /** Thread standard version 1.3. */
    public static final int THREAD_VERSION_1_3 = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({THREAD_VERSION_1_3})
    public @interface ThreadVersion {}

    private final IThreadNetworkController mControllerService;

    ThreadNetworkController(@NonNull IThreadNetworkController controllerService) {
        requireNonNull(controllerService, "controllerService cannot be null");

        mControllerService = controllerService;
    }

    /** Returns the Thread version this device is operating on. */
    @ThreadVersion
    public int getThreadVersion() {
        try {
            return mControllerService.getThreadVersion();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
