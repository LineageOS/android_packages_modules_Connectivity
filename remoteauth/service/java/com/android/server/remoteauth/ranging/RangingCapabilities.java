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

import androidx.annotation.IntDef;

/** The ranging capabilities of the device. */
public class RangingCapabilities {

    /** Possible ranging methods */
    @IntDef(
            value = {
                RANGING_METHOD_UNKNOWN,
                RANGING_METHOD_UWB,
            })
    public @interface RangingMethod {}

    /** Unknown ranging method. */
    public static final int RANGING_METHOD_UNKNOWN = 0x0;

    /** Ultra-wideband ranging. */
    public static final int RANGING_METHOD_UWB = 0x1;
}
