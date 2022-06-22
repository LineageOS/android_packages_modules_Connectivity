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

import static android.nearby.PresenceCredential.IDENTITY_TYPE_PRIVATE;
import static android.nearby.ScanRequest.SCAN_TYPE_NEARBY_PRESENCE;

import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.nearby.NearbyDeviceParcelable;
import android.nearby.PresenceScanFilter;
import android.nearby.PublicCredential;
import android.nearby.ScanFilter;

import com.android.server.nearby.injector.Injector;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

public class DiscoveryProviderManagerTest {
    @Mock Injector mInjector;
    @Mock Context mContext;
    @Mock AppOpsManager mAppOpsManager;
    private DiscoveryProviderManager mDiscoveryProviderManager;

    private static final int RSSI = -60;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mInjector.getAppOpsManager()).thenReturn(mAppOpsManager);
        mDiscoveryProviderManager =
                new DiscoveryProviderManager(mContext, mInjector);
    }


    @Test
    public void testOnNearbyDeviceDiscovered() {
        NearbyDeviceParcelable nearbyDeviceParcelable = new NearbyDeviceParcelable.Builder()
                .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                .build();
        mDiscoveryProviderManager.onNearbyDeviceDiscovered(nearbyDeviceParcelable);
    }

    @Test
    public void testInvalidateProviderScanMode() {
        mDiscoveryProviderManager.invalidateProviderScanMode();
    }

    @Test
    public void testStartChreProvider() {
        mDiscoveryProviderManager.startChreProvider();
    }

    private static PresenceScanFilter getPresenceScanFilter() {
        final byte[] secretId = new byte[]{1, 2, 3, 4};
        final byte[] authenticityKey = new byte[]{0, 1, 1, 1};
        final byte[] publicKey = new byte[]{1, 1, 2, 2};
        final byte[] encryptedMetadata = new byte[]{1, 2, 3, 4, 5};
        final byte[] metadataEncryptionKeyTag = new byte[]{1, 1, 3, 4, 5};

        PublicCredential credential = new PublicCredential.Builder(
                secretId, authenticityKey, publicKey, encryptedMetadata, metadataEncryptionKeyTag)
                .setIdentityType(IDENTITY_TYPE_PRIVATE)
                .build();

        final int action = 123;
        return new PresenceScanFilter.Builder()
                .addCredential(credential)
                .setMaxPathLoss(RSSI)
                .addPresenceAction(action)
                .build();
    }
}
