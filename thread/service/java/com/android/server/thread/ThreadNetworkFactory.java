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

package com.android.server.thread;

import static android.net.NetworkCapabilities.TRANSPORT_THREAD;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.MatchAllNetworkSpecifier;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.net.thread.IOperationReceiver;
import android.net.thread.ThreadNetworkException;
import android.net.thread.ThreadNetworkSpecifier;
import android.os.Looper;
import android.os.OutcomeReceiver;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.SharedLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// TODO(b/442787508): replace NetworkFactory subclass with direct usage of NetworkProvider
/**
 * Serves network requests that are initiated from apps via the {@link
 * ConnectivityManager#requestNetwork} API.
 */
public final class ThreadNetworkFactory extends NetworkFactory {
    private static final SharedLog LOG =
            ThreadNetworkLogger.forSubComponent("ThreadNetworkFactory");
    private final List<NetworkRequest> mActiveRequests = new ArrayList<>();
    private @Nullable ThreadNetworkControllerService mControllerService;

    /** Creates a new {@link ThreadNetworkFactory} instance. */
    public static ThreadNetworkFactory newInstance(Context context, Looper looper) {
        return new ThreadNetworkFactory(context, looper);
    }

    @VisibleForTesting
    ThreadNetworkFactory(Context context, Looper looper) {
        super(looper, context, LOG.getTag(), createBaseCapabilities());
        mControllerService = null;
    }

    void initialize(ThreadNetworkControllerService controllerService) {
        mControllerService = controllerService;
    }

    /** Returns the basic capabilities that all Thread networks initially have. */
    private static NetworkCapabilities createBaseCapabilities() {
        return new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_THREAD)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .setLinkUpstreamBandwidthKbps(100)
                .setLinkDownstreamBandwidthKbps(100)
                // By default accepts all network requests which have a specifier
                .setNetworkSpecifier(new MatchAllNetworkSpecifier())
                .build();
    }

    @Override
    public boolean acceptRequest(@NonNull final NetworkRequest request) {
        // the filtering is done in {@link #needNetworkFor}
        return true;
    }

    @Override
    public void needNetworkFor(@NonNull final NetworkRequest request) {
        LOG.i("network request is received for " + request);
        if (!request.hasTransport(TRANSPORT_THREAD)) {
            return;
        }

        if (request.getNetworkSpecifier() instanceof ThreadNetworkSpecifier specifier
                && specifier.getActiveOperationalDataset() != null) {
            mActiveRequests.add(request);
            join(specifier);
        } else {
            // Can do nothing if ThreadNetworkSpecifier or the Dataset is missing
            return;
        }
    }

    private void join(ThreadNetworkSpecifier specifier) {
        var receiver =
                new OutcomeReceiver<Void, ThreadNetworkException>() {
                    @Override
                    public void onError(ThreadNetworkException e) {
                        LOG.e("Failed to join Thread network: " + specifier, e);
                    }

                    @Override
                    public void onResult(Void v) {
                        LOG.i("Successfully joined Thread network: " + specifier);
                    }
                };
        mControllerService.joinWithSpecifier(specifier, receiver);
    }

    @Override
    public void releaseNetworkFor(@NonNull final NetworkRequest request) {
        LOG.i("network release request is received for " + request);
        if (mActiveRequests.isEmpty()) {
            LOG.w("Unexpect release of untracked request " + request);
            return;
        }

        NetworkRequest lastRequest = mActiveRequests.getLast();
        mActiveRequests.removeAll(Collections.singletonList(request));

        if (mActiveRequests.isEmpty()) {
            leave(request);
        } else if (!Objects.equals(mActiveRequests.getLast(), lastRequest)) {
            // If the last request is changed, let's re-join the network with the new latest
            // request
            join((ThreadNetworkSpecifier) mActiveRequests.getLast().getNetworkSpecifier());
        }
    }

    private void leave(NetworkRequest request) {
        var receiver =
                new IOperationReceiver.Stub() {
                    @Override
                    public void onSuccess() {
                        LOG.i("Successfully left Thread network for releasing request " + request);
                    }

                    @Override
                    public void onError(int errorCode, String errorMessage) {
                        var ex = new ThreadNetworkException(errorCode, errorMessage);
                        LOG.e(
                                "Failed to leave Thread network for releasing request " + request,
                                ex);
                    }
                };
        mControllerService.leave(receiver);
    }
}
