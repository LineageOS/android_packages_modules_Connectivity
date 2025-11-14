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

package com.android.server.net.ct;

/** Interface with logging to statsd for Certificate Transparency. */
public interface CertificateTransparencyLogger {

    /**
     * Logs a CTLogListUpdateStateChanged event to statsd.
     *
     * @param updateStatus status object containing details from this update event (e.g. log list
     * signature, log list timestamp, failure reason if applicable)
     */
    void logCTLogListUpdateStateChangedEvent(LogListUpdateStatus updateStatus);

    /**
     * Intermediate enum for use with CertificateTransparencyStatsLog.
     *
     * This enum primarily exists to avoid 100+ char line alert fatigue.
     */
    enum CTLogListUpdateState {
        UNKNOWN_STATE,
        HTTP_ERROR,
        LOG_LIST_INVALID,
        PUBLIC_KEY_INVALID,
        PUBLIC_KEY_NOT_ALLOWED,
        PUBLIC_KEY_NOT_FOUND,
        SIGNATURE_INVALID,
        SIGNATURE_NOT_FOUND,
        SIGNATURE_VERIFICATION_FAILED,
        SUCCESS,
        UNABLE_TO_READ_FILE,
        VERSION_ALREADY_EXISTS
    }
}