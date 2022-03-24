/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.nearby;

import android.annotation.BinderThread;
import android.content.Context;
import android.nearby.aidl.IFastPairClient;
import android.nearby.aidl.IFastPairStatusCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * 0p API for controlling Fast Pair. It communicates between main thread and service.
 *
 * @hide
 */
public class FastPairClient {

    private static final String TAG = "FastPairClient";
    private final IBinder mBinder;
    private final WeakReference<Context> mWeakContext;
    IFastPairClient mFastPairClient;
    PairStatusCallbackIBinder mPairStatusCallbackIBinder;

    /**
     * The Ibinder instance should be from
     * {@link com.android.server.nearby.fastpair.halfsheet.FastPairService} so that the client can
     * talk with the service.
     */
    public FastPairClient(Context context, IBinder binder) {
        mBinder = binder;
        mFastPairClient = IFastPairClient.Stub.asInterface(mBinder);
        mWeakContext = new WeakReference<>(context);
    }

    /**
     * Registers a callback at service to get UI updates.
     */
    public void registerHalfSheet(FastPairStatusCallback fastPairStatusCallback) {
        if (mPairStatusCallbackIBinder != null) {
            return;
        }
        mPairStatusCallbackIBinder = new PairStatusCallbackIBinder(fastPairStatusCallback);
        try {
            mFastPairClient.registerHalfSheet(mPairStatusCallbackIBinder);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to register fastPairStatusCallback", e);
        }
    }

    /**
     * Pairs the device at service.
     */
    public void connect(FastPairDevice fastPairDevice) {
        try {
            mFastPairClient.connect(fastPairDevice);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to connect Fast Pair device" + fastPairDevice, e);
        }
    }

    private class PairStatusCallbackIBinder extends IFastPairStatusCallback.Stub {
        private final FastPairStatusCallback mStatusCallback;

        private PairStatusCallbackIBinder(FastPairStatusCallback fastPairStatusCallback) {
            mStatusCallback = fastPairStatusCallback;
        }

        @BinderThread
        @Override
        public synchronized void onPairUpdate(FastPairDevice fastPairDevice,
                PairStatusMetadata pairStatusMetadata) {
            Context context = mWeakContext.get();
            if (context != null) {
                Handler handler = new Handler(context.getMainLooper());
                handler.post(() ->
                        mStatusCallback.onPairUpdate(fastPairDevice, pairStatusMetadata));
            }
        }
    }
}
