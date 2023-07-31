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

import android.annotation.NonNull;

import androidx.annotation.IntDef;

import java.util.concurrent.Executor;

/**
 * The controller for starting and stopping ranging during which callers receive callbacks with
 * {@link RangingReport}s and {@link RangingError}s."
 *
 * <p>A session can be started and stopped multiple times. After starting, updates ({@link
 * RangingReport}, {@link RangingError}, etc) will be reported via the provided {@link
 * RangingCallback}. BaseKey and SyncData are used for auto derivation of supported ranging
 * parameters, which will be implementation specific.
 *
 * <p>Ranging method specific implementation shall be implemented in the extended class.
 */
public abstract class RangingSession {

    /** Types of ranging error. */
    @IntDef(
            value = {
                RANGING_ERROR_UNKNOWN,
            })
    public @interface RangingError {}

    /** Unknown ranging error type. */
    public static final int RANGING_ERROR_UNKNOWN = 0x0;

    /** Interface for ranging update callbacks. */
    public interface RangingCallback {
        /**
         * Call upon new {@link RangingReport}.
         *
         * @param sessionInfo info about this ranging session.
         * @param rangingReport new ranging report
         */
        void onRangingReport(SessionInfo sessionInfo, RangingReport rangingReport);

        /**
         * Call upon any ranging error events.
         *
         * @param sessionInfo info about this ranging session.
         * @param rangingError error type
         */
        void onError(SessionInfo sessionInfo, @RangingError int rangingError);
    }

    /**
     * Starts ranging based on the given {@link RangingParameters}.
     *
     * <p>Start can be called again after {@link #stop()} has been called, else it will result in a
     * no-op.
     *
     * @param rangingParameters parameters to start the ranging.
     * @param executor Executor to run the rangingCallback.
     * @param rangingCallback callback to notify of ranging events.
     */
    public abstract void start(
            @NonNull RangingParameters rangingParameters,
            @NonNull Executor executor,
            @NonNull RangingCallback rangingCallback);

    /**
     * Stops ranging.
     *
     * <p>Calling stop without first calling {@link #start()} will result in a no-op.
     */
    public abstract void stop();

    /**
     * Resets the base key that's used to derive all possible ranging parameters. The baseKey shall
     * be reset whenever there is a risk that it may no longer be valid and secured. For example,
     * the secure connection between the devices is lost.
     *
     * @param baseKey new baseKey must be 16 or 32 bytes.
     */
    public void resetBaseKey(byte[] baseKey) {}

    /**
     * Resets the synchronization by giving a new syncData used for ranging parameters derivation.
     * Resetting the syncData is not required before each {@link #start}, but the more time the
     * derivations are done before resetting syncData, the higher the risk the derivation will be
     * out of sync between the devices. Therefore, syncData shall be refreshed in a best effort
     * manner.
     *
     * @param syncData new syncData must be 16 bytes.
     */
    public void resetSyncData(byte[] syncData) {}
}
