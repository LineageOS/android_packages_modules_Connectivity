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

package android.nearby;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/**
 * This class provides a way to perform Nearby related operations such as scanning and connecting
 * to nearby devices.
 *
 * <p> To get a {@link NearbyManager} instance, call the
 * <code>Context.getSystemService(NearbyManager.class)</code>.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.NEARBY_SERVICE)
public class NearbyManager {

    private static final String TAG = "NearbyManager";
    @GuardedBy("sScanListeners")
    private static final WeakHashMap<ScanCallback, WeakReference<ScanListenerTransport>>
            sScanListeners = new WeakHashMap<>();
    private final INearbyManager mService;

    /**
     * Creates a new NearbyManager.
     *
     * @param service The service object.
     */
    NearbyManager(@NonNull INearbyManager service) {
        mService = service;
    }

    private static NearbyDevice toClientNearbyDevice(
            NearbyDeviceParcelable nearbyDeviceParcelable,
            @ScanRequest.ScanType int scanType) {
        if (scanType == ScanRequest.SCAN_TYPE_FAST_PAIR) {
            return new FastPairDevice.Builder()
                    .setName(nearbyDeviceParcelable.getName())
                    .setMedium(nearbyDeviceParcelable.getMedium())
                    .setRssi(nearbyDeviceParcelable.getRssi())
                    .setModelId(nearbyDeviceParcelable.getFastPairModelId())
                    .setBluetoothAddress(nearbyDeviceParcelable.getBluetoothAddress())
                    .setData(nearbyDeviceParcelable.getData()).build();
        }
        return null;
    }

    /**
     * Start scan for nearby devices with given parameters. Devices matching {@link ScanRequest}
     * will be delivered through the given callback.
     *
     * @param scanRequest Various parameters clients send when requesting scanning.
     * @param executor Executor where the listener method is called.
     * @param scanCallback The callback to notify clients when there is a scan result.
     */
    public void startScan(@NonNull ScanRequest scanRequest,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull ScanCallback scanCallback) {
        Objects.requireNonNull(scanRequest, "scanRequest must not be null");
        Objects.requireNonNull(scanCallback, "scanCallback must not be null");
        Objects.requireNonNull(executor, "executor must not be null");

        try {
            synchronized (sScanListeners) {
                WeakReference<ScanListenerTransport> reference = sScanListeners.get(scanCallback);
                ScanListenerTransport transport = reference != null ? reference.get() : null;
                if (transport == null) {
                    transport = new ScanListenerTransport(scanRequest.getScanType(), scanCallback,
                            executor);
                } else {
                    Preconditions.checkState(transport.isRegistered());
                    transport.setExecutor(executor);
                }
                mService.registerScanListener(scanRequest, transport);
                sScanListeners.put(scanCallback, new WeakReference<>(transport));
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stops the nearby device scan for the specified callback. The given callback
     * is guaranteed not to receive any invocations that happen after this method
     * is invoked.
     *
     * Suppressed lint: Registration methods should have overload that accepts delivery Executor.
     * Already have executor in startScan() method.
     *
     * @param scanCallback  The callback that was used to start the scan.
     */
    @SuppressLint("ExecutorRegistration")
    public void stopScan(@NonNull ScanCallback scanCallback) {
        Preconditions.checkArgument(scanCallback != null,
                "invalid null scanCallback");
        try {
            synchronized (sScanListeners) {
                WeakReference<ScanListenerTransport> reference = sScanListeners.remove(
                        scanCallback);
                ScanListenerTransport transport = reference != null ? reference.get() : null;
                if (transport != null) {
                    transport.unregister();
                    mService.unregisterScanListener(transport);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class ScanListenerTransport extends IScanListener.Stub {

        private @ScanRequest.ScanType int mScanType;
        private volatile @Nullable ScanCallback mScanCallback;
        private Executor mExecutor;

        ScanListenerTransport(@ScanRequest.ScanType int scanType, ScanCallback scanCallback,
                @CallbackExecutor Executor executor) {
            Preconditions.checkArgument(scanCallback != null,
                    "invalid null callback");
            Preconditions.checkState(ScanRequest.isValidScanType(scanType),
                    "invalid scan type : " + scanType
                            + ", scan type must be one of ScanRequest#SCAN_TYPE_");
            mScanType = scanType;
            mScanCallback = scanCallback;
            mExecutor = executor;
        }

        void setExecutor(Executor executor) {
            Preconditions.checkArgument(
                    executor != null, "invalid null executor");
            mExecutor = executor;
        }

        boolean isRegistered() {
            return mScanCallback != null;
        }

        void unregister() {
            mScanCallback = null;
        }

        @Override
        public void onDiscovered(NearbyDeviceParcelable nearbyDeviceParcelable)
                throws RemoteException {
            mExecutor.execute(() -> mScanCallback.onDiscovered(
                    toClientNearbyDevice(nearbyDeviceParcelable, mScanType)));
        }

        @Override
        public void onUpdated(NearbyDeviceParcelable nearbyDeviceParcelable)
                throws RemoteException {
            mExecutor.execute(
                    () -> mScanCallback.onUpdated(
                            toClientNearbyDevice(nearbyDeviceParcelable, mScanType)));
        }

        @Override
        public void onLost(NearbyDeviceParcelable nearbyDeviceParcelable) throws RemoteException {
            mExecutor.execute(
                    () -> mScanCallback.onLost(
                            toClientNearbyDevice(nearbyDeviceParcelable, mScanType)));
        }
    }
}
