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

import static com.google.common.io.BaseEncoding.base16;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.ThreadNetworkSpecifier;
import android.os.test.TestLooper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link ThreadNetworkFactory}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public final class ThreadNetworkFactoryTest {
    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset new":
    // Active Timestamp: 1
    // Channel: 19
    // Channel Mask: 0x07FFF800
    // Ext PAN ID: ACC214689BC40BDF
    // Mesh Local Prefix: fd64:db12:25f4:7e0b::/64
    // Network Key: F26B3153760F519A63BAFDDFFC80D2AF
    // Network Name: OpenThread-d9a0
    // PAN ID: 0xD9A0
    // PSKc: A245479C836D551B9CA557F7B9D351B4
    // Security Policy: 672 onrcb
    private static final byte[] DATASET_TLVS =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");
    private static final ActiveOperationalDataset ACTIVE_DATASET =
            ActiveOperationalDataset.fromThreadTlvs(DATASET_TLVS);

    private Context mContext;
    private TestLooper mTestLooper;
    @Mock private ThreadNetworkControllerService mMockControllerService;

    private ThreadNetworkFactory mFactory;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = ApplicationProvider.getApplicationContext();
        mTestLooper = new TestLooper();
        mFactory = new ThreadNetworkFactory(mContext, mTestLooper.getLooper());
        mFactory.initialize(mMockControllerService);
    }

    @Test
    public void needNetworkFor_noThreadTransport_doNothing() {
        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();

        mFactory.needNetworkFor(request);

        verifyNoInteractions(mMockControllerService);
    }

    @Test
    public void needNetworkFor_threadTransportAndNoSpecifier_doNothing() {
        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                        .build();

        mFactory.needNetworkFor(request);

        verifyNoInteractions(mMockControllerService);
    }

    @Test
    public void needNetworkFor_threadTransportAndSpecifier_joinIsCalled() {
        ThreadNetworkSpecifier specifier =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(ACTIVE_DATASET)
                        .build();
        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                        .setNetworkSpecifier(specifier)
                        .build();

        mFactory.needNetworkFor(request);

        verify(mMockControllerService).joinWithSpecifier(eq(specifier), any());
    }

    @Test
    public void needNetworkFor_specifierWithNoDataset_doNothing() {
        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                        .setNetworkSpecifier(new ThreadNetworkSpecifier.Builder().build())
                        .build();

        mFactory.needNetworkFor(request);

        verifyNoInteractions(mMockControllerService);
    }
}
