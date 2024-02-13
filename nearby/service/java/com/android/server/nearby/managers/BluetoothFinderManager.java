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

package com.android.server.nearby.managers;

import android.nearby.PoweredOffFindingEphemeralId;

import java.util.List;

/** Connects to {@link IBluetoothFinder} HAL and invokes its API. */
// A placeholder implementation until the HAL API can be used.
public class BluetoothFinderManager {

    private boolean mPoweredOffFindingModeEnabled = false;

    /** An empty implementation of the corresponding HAL API call. */
    public void sendEids(List<PoweredOffFindingEphemeralId> eids) {}

    /** A placeholder implementation of the corresponding HAL API call. */
    public void setPoweredOffFinderMode(boolean enable) {
        mPoweredOffFindingModeEnabled = enable;
    }

    /** A placeholder implementation of the corresponding HAL API call. */
    public boolean getPoweredOffFinderMode() {
        return mPoweredOffFindingModeEnabled;
    }
}
