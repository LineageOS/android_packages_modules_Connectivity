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

package android.nearby.cts;

import static android.nearby.PresenceCredential.IDENTITY_TYPE_PRIVATE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.nearby.BroadcastCallback;
import android.nearby.BroadcastRequest;
import android.nearby.NearbyDevice;
import android.nearby.NearbyManager;
import android.nearby.PresenceBroadcastRequest;
import android.nearby.PrivateCredential;
import android.nearby.ScanCallback;
import android.nearby.ScanRequest;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Collections;
import java.util.concurrent.Executors;

/**
 * TODO(b/215435939) This class doesn't include any logic yet. Because SELinux denies access to
 * NearbyManager.
 */
@RunWith(AndroidJUnit4.class)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class NearbyManagerTest {
    private static final byte[] SALT = new byte[] {1, 2};
    private static final byte[] SECRETE_ID = new byte[]{1, 2, 3, 4};
    private static final byte[] AUTHENTICITY_KEY = new byte[]{0, 1, 1, 1};
    private static final int BLE_MEDIUM = 1;

    @Mock private Context mContext;
    @Mock private NearbyManager mNearbyManager;

    @Before
    public void setUp() {
        initMocks(this);

        when(mContext.getSystemService(Context.NEARBY_SERVICE)).thenReturn(mNearbyManager);
        when(mContext.getContentResolver()).thenReturn(
                InstrumentationRegistry.getInstrumentation().getContext().getContentResolver());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void test_startAndStopScan() {
        ScanRequest scanRequest = new ScanRequest.Builder()
                .setScanType(ScanRequest.SCAN_TYPE_FAST_PAIR)
                .setScanMode(ScanRequest.SCAN_MODE_LOW_LATENCY)
                .setEnableBle(true)
                .build();
        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onDiscovered(@NonNull NearbyDevice device) {
            }

            @Override
            public void onUpdated(@NonNull NearbyDevice device) {

            }

            @Override
            public void onLost(@NonNull NearbyDevice device) {

            }
        };
        mNearbyManager.startScan(scanRequest, Executors.newSingleThreadExecutor(), scanCallback);
        mNearbyManager.stopScan(scanCallback);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testStartStopBroadcast() {
        PrivateCredential credential = new PrivateCredential.Builder(SECRETE_ID, AUTHENTICITY_KEY)
                .setIdentityType(IDENTITY_TYPE_PRIVATE)
                .build();
        BroadcastRequest broadcastRequest =
                new PresenceBroadcastRequest.Builder(
                        Collections.singletonList(BLE_MEDIUM), SALT, credential)
                        .build();

        BroadcastCallback callback = status -> {
        };
        mNearbyManager.startBroadcast(broadcastRequest, Executors.newSingleThreadExecutor(),
                callback);
        mNearbyManager.stopBroadcast(callback);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testSettingsEnable() {
        NearbyManager.setFastPairScanEnabled(mContext, false);
        assertThat(NearbyManager.getFastPairScanEnabled(mContext, true)).isFalse();
    }
}
