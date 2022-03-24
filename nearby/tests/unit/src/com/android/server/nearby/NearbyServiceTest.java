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

package com.android.server.nearby;

import static android.Manifest.permission.READ_DEVICE_CONFIG;

import static org.mockito.MockitoAnnotations.initMocks;

import android.app.UiAutomation;
import android.content.Context;
import android.nearby.IScanListener;
import android.nearby.ScanRequest;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public final class NearbyServiceTest {

    private Context mContext;
    private NearbyService mService;
    private ScanRequest mScanRequest;
    private UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();

    @Mock
    private IScanListener mScanListener;

    @Before
    public void setup() {
        initMocks(this);
        mUiAutomation.adoptShellPermissionIdentity(READ_DEVICE_CONFIG);
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mService = new NearbyService(mContext);
        mScanRequest = createScanRequest();
    }

    @Test
    public void test_register() {
        mService.registerScanListener(mScanRequest, mScanListener);
    }

    @Test
    public void test_unregister() {
        mService.unregisterScanListener(mScanListener);
    }

    private ScanRequest createScanRequest() {
        return new ScanRequest.Builder()
                .setScanType(ScanRequest.SCAN_TYPE_FAST_PAIR)
                .setBleEnabled(true)
                .build();
    }
}
