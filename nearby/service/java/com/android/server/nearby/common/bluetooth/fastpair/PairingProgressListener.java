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

package com.android.server.nearby.common.bluetooth.fastpair;

/** Callback interface for pairing progress. */
public interface PairingProgressListener {
    /** Enum for pairing events. */
    enum PairingEvent {
        START,
        SUCCESS,
        FAILED,
        UNKNOWN;

        public static PairingEvent fromOrdinal(int ordinal) {
            PairingEvent[] values = PairingEvent.values();
            if (ordinal < 0 || ordinal >= values.length) {
                return UNKNOWN;
            }
            return values[ordinal];
        }
    }

    /** Callback function upon pairing progress update. */
    void onPairingProgressUpdating(PairingEvent event, String message);
}
