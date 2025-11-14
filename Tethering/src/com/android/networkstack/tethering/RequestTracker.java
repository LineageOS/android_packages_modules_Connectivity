/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static com.android.networkstack.tethering.util.TetheringUtils.createPlaceholderRequest;

import android.net.TetheringManager.TetheringRequest;
import android.net.ip.IpServer;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helper class to keep track of tethering requests.
 * The intended usage of this class is
 * 1) Add a pending request with {@link #addPendingRequest(TetheringRequest)} before asking the link
 *    layer to start.
 * 2) When the link layer is up, use {@link #getOrCreatePendingRequest(int)} to get a request to
 *    start IP serving with.
 * 3) Remove pending request with {@link #removePendingRequest(TetheringRequest)}.
 * Note: This class is not thread-safe.
 */
public class RequestTracker {
    private static final String TAG = "TetheringRequestTracker";

    @NonNull
    private final boolean mUseFuzzyMatching;

    public RequestTracker(boolean useFuzzyMatching) {
        mUseFuzzyMatching = useFuzzyMatching;
    }

    public enum AddResult {
        /**
         * Request was successfully added
         */
        SUCCESS,
        /**
         * Failure indicating that the request could not be added due to a request of the same type
         * with conflicting parameters already pending. If so, we must stop tethering for the
         * pending request before trying to add the result again. Only returned on V-.
         */
        FAILURE_DUPLICATE_REQUEST_RESTART,
        /**
         * Failure indicating that the request could not be added due to a fuzzy-matched request
         * already pending or serving. Only returned on B+.
         */
        FAILURE_DUPLICATE_REQUEST_ERROR,
    }

    /**
     * List of pending requests added by {@link #addPendingRequest(TetheringRequest)}
     * There can be only one per type, since we remove every request of the
     * same type when we add a request.
     */
    private final List<TetheringRequest> mPendingRequests = new ArrayList<>();
    /**
     * List of serving requests added by
     * {@link #promoteRequestToServing(IpServer, TetheringRequest)}.
     */
    private final Map<IpServer, TetheringRequest> mServingRequests = new ArrayMap<>();

    @VisibleForTesting
    List<TetheringRequest> getPendingTetheringRequests() {
        return new ArrayList<>(mPendingRequests);
    }

    /**
     * Adds a pending request or fails with FAILURE_CONFLICTING_REQUEST_FAIL if the request
     * fuzzy-matches an existing request (either pending or serving).
     */
    public AddResult addPendingRequestFuzzyMatched(@NonNull final TetheringRequest newRequest) {
        List<TetheringRequest> existingRequests = new ArrayList<>();
        existingRequests.addAll(mServingRequests.values());
        existingRequests.addAll(mPendingRequests);
        for (TetheringRequest request : existingRequests) {
            if (request.fuzzyMatches(newRequest)) {
                Log.i(TAG, "Cannot add pending request due to existing fuzzy-matched "
                        + "request: " + request);
                return AddResult.FAILURE_DUPLICATE_REQUEST_ERROR;
            }
        }

        mPendingRequests.add(newRequest);
        return AddResult.SUCCESS;
    }

    /**
     * Add a pending request and listener. The request should be added before asking the link layer
     * to start, and should be retrieved with {@link #getNextPendingRequest(int)} once the link
     * layer comes up. The result of the add operation will be returned as an AddResult code.
     */
    public AddResult addPendingRequest(@NonNull final TetheringRequest newRequest) {
        if (mUseFuzzyMatching) {
            return addPendingRequestFuzzyMatched(newRequest);
        }

        // Check the existing requests to see if it is OK to add the new request.
        for (TetheringRequest existingRequest : mPendingRequests) {
            if (existingRequest.getTetheringType() != newRequest.getTetheringType()) {
                continue;
            }

            // Can't add request if there's a request of the same type with different
            // parameters.
            if (!existingRequest.equalsIgnoreUidPackage(newRequest)) {
                return AddResult.FAILURE_DUPLICATE_REQUEST_RESTART;
            }
        }

        // Remove the existing pending request of the same type. We already filter out for
        // conflicting parameters above, so these would have been equivalent anyway (except for
        // UID).
        removeAllPendingRequests(newRequest.getTetheringType());
        mPendingRequests.add(newRequest);
        return AddResult.SUCCESS;
    }

    /**
     * Gets the next pending TetheringRequest of a given type, or creates a placeholder request if
     * there are none.
     * Note: There are edge cases where the pending request is absent and we must temporarily
     * synthesize a placeholder request, such as if stopTethering was called before link
     * layer went up, or if the link layer goes up without us poking it (e.g. adb shell
     * cmd wifi start-softap). These placeholder requests only specify the tethering type
     * and the default connectivity scope.
     */
    @NonNull
    public TetheringRequest getOrCreatePendingRequest(int type) {
        TetheringRequest pending = getNextPendingRequest(type);
        if (pending != null) return pending;

        Log.w(TAG, "No pending TetheringRequest for type " + type + " found, creating a"
                + " placeholder request");
        return createPlaceholderRequest(type);
    }

    /**
     * Same as {@link #getOrCreatePendingRequest(int)} but returns {@code null} if there's no
     * pending request found.
     *
     * @param type Tethering type of the pending request
     * @return pending request or {@code null} if there are none.
     */
    @Nullable
    public TetheringRequest getNextPendingRequest(int type) {
        for (TetheringRequest request : mPendingRequests) {
            if (request.getTetheringType() == type) return request;
        }
        return null;
    }

    /**
     * Removes all pending requests of the given tethering type.
     *
     * @param type Tethering type
     */
    public void removeAllPendingRequests(final int type) {
        mPendingRequests.removeIf(r -> r.getTetheringType() == type);
    }

    /**
     * Removes a specific pending request.
     *
     * Note: For V-, this will be the same as removeAllPendingRequests to align with historical
     * behavior.
     *
     * @param request Request to be removed
     */
    public void removePendingRequest(@NonNull TetheringRequest request) {
        if (!mUseFuzzyMatching) {
            // Remove all requests of the same type to match the historical behavior.
            removeAllPendingRequests(request.getTetheringType());
            return;
        }

        mPendingRequests.removeIf(r -> r.equals(request));
    }

    /**
     * Removes a tethering request from the pending list and promotes it to serving with the
     * IpServer that is using it.
     * Note: If mUseFuzzyMatching is false, then the request will be removed from the pending list,
     * but it will not be added to serving list.
     */
    public void promoteRequestToServing(@NonNull final IpServer ipServer,
            @NonNull final TetheringRequest tetheringRequest) {
        removePendingRequest(tetheringRequest);
        if (!mUseFuzzyMatching) return;
        mServingRequests.put(ipServer, tetheringRequest);
    }


    /**
     * Returns the serving request tied to the given IpServer, or null if there is none.
     * Note: If mUseFuzzyMatching is false, then this will always return null.
     */
    @Nullable
    public TetheringRequest getServingRequest(@NonNull final IpServer ipServer) {
        return mServingRequests.get(ipServer);
    }

    /**
     * Removes the serving request tied to the given IpServer.
     * Note: If mUseFuzzyMatching is false, then this is a no-op since serving requests are unused
     * for that configuration.
     */
    public void removeServingRequest(@NonNull final IpServer ipServer) {
        mServingRequests.remove(ipServer);
    }

    /**
     * Removes all serving requests of the given tethering type.
     *
     * @param type Tethering type
     */
    public void removeAllServingRequests(final int type) {
        mServingRequests.entrySet().removeIf(e -> e.getValue().getTetheringType() == type);
    }

    @VisibleForTesting
    List<TetheringRequest> getServingTetheringRequests() {
        return new ArrayList<>(mServingRequests.values());
    }

    /**
     * Returns an existing (pending or serving) request that fuzzy matches the given request.
     * Optionally specify matchUid to only return requests with the same uid.
     */
    public TetheringRequest findFuzzyMatchedRequest(
            @NonNull final TetheringRequest tetheringRequest, boolean matchUid) {
        List<TetheringRequest> allRequests = new ArrayList<>();
        allRequests.addAll(getPendingTetheringRequests());
        allRequests.addAll(mServingRequests.values());
        for (TetheringRequest request : allRequests) {
            if (!request.fuzzyMatches(tetheringRequest)) continue;
            if (matchUid && tetheringRequest.getUid() != request.getUid()) continue;
            return request;
        }
        return null;
    }
}
