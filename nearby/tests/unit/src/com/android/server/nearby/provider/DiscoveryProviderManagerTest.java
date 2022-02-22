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

package com.android.server.nearby.provider;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.nearby.IScanListener;
import android.nearby.NearbyDeviceParcelable;
import android.nearby.ScanRequest;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import com.android.server.nearby.injector.Injector;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

@SmallTest
public final class DiscoveryProviderManagerTest {
    @Mock
    private Context mContext;
    @Mock
    private BluetoothAdapter mBluetoothAdapter;
    private TestInjector mInjector;
    // TODO(b/215435710) NearbyDeviceParcelable Cannot be accessed because it is a system API.
    // Need to fix this to add more test logic.
    private DiscoveryProviderManager mManager;

    @Before
    public void setUp() {
        mInjector = new TestInjector();
        mManager = new DiscoveryProviderManager(mContext, mInjector);
    }

    private class TestInjector implements Injector {
        @Override
        public BluetoothAdapter getBluetoothAdapter() {
            return mBluetoothAdapter;
        }
    }

    @Test
    public void testRegisterListener() throws Exception {
        IScanListener listener = createMockScanListener();
        mManager.registerScanListener(createScanRequest(), listener);
    }

    private ScanRequest createScanRequest() {
        return new ScanRequest.Builder()
                .setScanType(ScanRequest.SCAN_TYPE_FAST_PAIR)
                .setEnableBle(true)
                .build();
    }

    private IScanListener createMockScanListener() {
        return new IScanListener() {
            @Override
            public void onDiscovered(NearbyDeviceParcelable nearbyDeviceParcelable)
                    throws RemoteException {

            }

            @Override
            public void onUpdated(NearbyDeviceParcelable nearbyDeviceParcelable)
                    throws RemoteException {

            }

            @Override
            public void onLost(NearbyDeviceParcelable nearbyDeviceParcelable)
                    throws RemoteException {

            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        };
    }
}
