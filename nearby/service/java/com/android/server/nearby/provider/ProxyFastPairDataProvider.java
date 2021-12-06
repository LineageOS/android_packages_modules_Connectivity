/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.nearby.provider;

import android.annotation.Nullable;
import android.content.Context;
import android.nearby.FastPairDeviceMetadataParcel;
import android.nearby.FastPairDeviceMetadataRequestParcel;
import android.nearby.IFastPairDataCallback;
import android.nearby.IFastPairDataProvider;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.WorkerThread;

import com.android.server.nearby.common.servicemonitor.CurrentUserServiceProvider;
import com.android.server.nearby.common.servicemonitor.CurrentUserServiceProvider.BoundServiceInfo;
import com.android.server.nearby.common.servicemonitor.ServiceMonitor;
import com.android.server.nearby.common.servicemonitor.ServiceMonitor.ServiceListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import service.proto.Rpcs;

/**
 * Proxy for IFastPairDataProvider implementations.
 */
public class ProxyFastPairDataProvider implements ServiceListener<BoundServiceInfo> {

    /**
     * Creates and registers this proxy. If no suitable service is available for the proxy, returns
     * null.
     */
    @Nullable
    public static ProxyFastPairDataProvider create(Context context, String action) {
        ProxyFastPairDataProvider proxy = new ProxyFastPairDataProvider(context, action);
        if (proxy.checkServiceResolves()) {
            return proxy;
        } else {
            return null;
        }
    }

    private final ServiceMonitor mServiceMonitor;

    private ProxyFastPairDataProvider(Context context, String action) {
        // safe to use direct executor since our locks are not acquired in a code path invoked by
        // our owning provider

        mServiceMonitor = ServiceMonitor.create(context, "FAST_PAIR_DATA_PROVIDER",
                CurrentUserServiceProvider.create(context, action), this);
    }

    private boolean checkServiceResolves() {
        return mServiceMonitor.checkServiceResolves();
    }

    /** User service watch to connect to actually services implemented by OEMs. */
    public void register() {
        mServiceMonitor.register();
    }

    // Fast Pair Data Provider doesn't maintain a long running state.
    // Therefore, it doesn't need setup at bind time.
    @Override
    public void onBind(IBinder binder, BoundServiceInfo boundServiceInfo) throws RemoteException {
    }

    // Fast Pair Data Provider doesn't maintain a long running state.
    // Therefore, it doesn't need tear down at unbind time.
    @Override
    public void onUnbind() {
    }

    /** Invoke loadFastPairDeviceMetadata. */
    @WorkerThread
    @Nullable
    Rpcs.GetObservedDeviceResponse loadFastPairDeviceMetadata(byte[] modelId) {
        final CountDownLatch waitForCompletionLatch = new CountDownLatch(1);
        final AtomicReference<Rpcs.GetObservedDeviceResponse> response = new AtomicReference<>();
        mServiceMonitor.runOnBinder(new ServiceMonitor.BinderOperation() {
            @Override
            public void run(IBinder binder) throws RemoteException {
                IFastPairDataProvider provider = IFastPairDataProvider.Stub.asInterface(binder);
                FastPairDeviceMetadataRequestParcel requestParcel =
                        new FastPairDeviceMetadataRequestParcel();
                requestParcel.modelId = modelId;
                IFastPairDataCallback callback = new IFastPairDataCallback.Stub() {
                    public void onFastPairDeviceMetadataReceived(
                            FastPairDeviceMetadataParcel metadata) {
                        response.set(Utils.convert(metadata));
                        waitForCompletionLatch.countDown();
                    }
                };
                provider.loadFastPairDeviceMetadata(requestParcel, callback);
            }

            @Override
            public void onError() {
                waitForCompletionLatch.countDown();
            }
        });
        try {
            waitForCompletionLatch.await(10000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // skip.
        }
        return response.get();
    }
}
