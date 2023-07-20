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

package com.android.metrics;

/**
 * Class to record the NetworkNsdReported into statsd. Each client should create this class to
 * report its data.
 */
public class NetworkNsdReportedMetrics {
    // Whether this client is using legacy backend.
    private final boolean mIsLegacy;
    // The client id.
    private final int mClientId;

    public NetworkNsdReportedMetrics(boolean isLegacy, int clientId) {
        mIsLegacy = isLegacy;
        mClientId = clientId;
    }

    // TODO: Report metrics data.
}
