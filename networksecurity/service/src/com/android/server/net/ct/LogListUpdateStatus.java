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

import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.PUBLIC_KEY_INVALID;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.PUBLIC_KEY_NOT_ALLOWED;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.PUBLIC_KEY_NOT_FOUND;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.SIGNATURE_INVALID;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.SIGNATURE_NOT_FOUND;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.SIGNATURE_VERIFICATION_FAILED;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.SUCCESS;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.UNABLE_TO_READ_FILE;

import com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState;

import com.google.auto.value.AutoValue;

import java.util.Optional;

/** Class to represent the signature verification status for Certificate Transparency. */
@AutoValue
public abstract class LogListUpdateStatus {

    abstract CTLogListUpdateState state();

    abstract String signature();

    abstract long logListTimestamp();

    abstract int httpErrorStatusCode();

    abstract Optional<Integer> downloadStatus();

    boolean isPublicKeySet() {
        // Check that none of the public key setting failures have been set as the state
        return state() != PUBLIC_KEY_INVALID
                && state() != PUBLIC_KEY_NOT_ALLOWED
                && state() != UNABLE_TO_READ_FILE;
    }

    boolean isSignatureVerified() {
        // Check that none of the signature verification failures have been set as the state
        return state() != PUBLIC_KEY_NOT_FOUND
                && state() != SIGNATURE_INVALID
                && state() != SIGNATURE_NOT_FOUND
                && state() != SIGNATURE_VERIFICATION_FAILED
                && state() != UNABLE_TO_READ_FILE;
    }

    boolean hasSignature() {
        return signature() != null && signature().length() > 0;
    }

    boolean isSuccessful() {
        return state() == SUCCESS;
    }

    static LogListUpdateStatus getDefaultInstance() {
        return builder().build();
    }

    @AutoValue.Builder
    abstract static class Builder {
        abstract Builder setState(CTLogListUpdateState updateState);

        abstract Builder setSignature(String signature);

        abstract Builder setLogListTimestamp(long timestamp);

        abstract Builder setHttpErrorStatusCode(int httpStatusCode);

        abstract Builder setDownloadStatus(Optional<Integer> downloadStatus);

        abstract LogListUpdateStatus build();
    }

    abstract LogListUpdateStatus.Builder toBuilder();

    static Builder builder() {
        return new AutoValue_LogListUpdateStatus.Builder()
            .setState(CTLogListUpdateState.UNKNOWN_STATE)
            .setSignature("")
            .setLogListTimestamp(0L)
            .setHttpErrorStatusCode(0)
            .setDownloadStatus(Optional.empty());
    }
}
