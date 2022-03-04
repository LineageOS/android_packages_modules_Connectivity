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

package com.android.server.nearby;

import android.content.Context;
import android.os.Binder;

/**
 * Stub NearbyService class, used until NearbyService code is available in all branches.
 *
 * This can be published as an empty service in branches that use it.
 */
public final class NearbyService extends Binder {
    public NearbyService(Context ctx) {
        throw new UnsupportedOperationException("This is a stub service");
    }

    /** Called by the service initializer on each boot phase */
    public void onBootPhase(int phase) {
        // Do nothing
    }
}
