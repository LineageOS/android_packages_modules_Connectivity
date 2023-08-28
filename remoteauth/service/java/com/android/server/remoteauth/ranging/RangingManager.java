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
package com.android.server.remoteauth.ranging;

import android.content.Context;

/**
 * Manages the creation of generic device to device ranging session and obtaining device's ranging
 * capabilities.
 *
 * <p>Out-of-band channel for ranging capabilities/parameters exchange is assumed being handled
 * outside of this class.
 */
public class RangingManager {

    public RangingManager(Context context) {}

    /**
     * Gets the {@link RangingCapabilities} of this device.
     *
     * @return RangingCapabilities.
     */
    public RangingCapabilities getRangingCapabilities() {
        return null;
    }

    /**
     * Creates a {@link RangingSession} based on the given {@link SessionParameters}, which shall be
     * provided based on the rangingCapabilities of the device.
     *
     * @param sessionParameters parameters used to setup the session.
     * @return the created RangingSession.
     */
    public RangingSession createSession(SessionParameters sessionParameters) {
        return null;
    }
}
