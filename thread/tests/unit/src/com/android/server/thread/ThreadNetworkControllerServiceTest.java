/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.net.thread.ThreadNetworkException.ERROR_INTERNAL_ERROR;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkAgent;
import android.net.NetworkProvider;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.IOperationReceiver;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.test.TestLooper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.thread.openthread.testing.FakeOtDaemon;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link ThreadNetworkControllerService}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class ThreadNetworkControllerServiceTest {
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
    private static final byte[] DEFAULT_ACTIVE_DATASET_TLVS =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");
    private static final ActiveOperationalDataset DEFAULT_ACTIVE_DATASET =
            ActiveOperationalDataset.fromThreadTlvs(DEFAULT_ACTIVE_DATASET_TLVS);

    @Mock private ConnectivityManager mMockConnectivityManager;
    @Mock private NetworkAgent mMockNetworkAgent;
    @Mock private TunInterfaceController mMockTunIfController;
    @Mock private ParcelFileDescriptor mMockTunFd;
    @Mock private InfraInterfaceController mMockInfraIfController;
    @Mock private ThreadPersistentSettings mMockPersistentSettings;
    @Mock private NsdPublisher mMockNsdPublisher;
    private Context mContext;
    private TestLooper mTestLooper;
    private FakeOtDaemon mFakeOtDaemon;
    private ThreadNetworkControllerService mService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = ApplicationProvider.getApplicationContext();
        mTestLooper = new TestLooper();
        final Handler handler = new Handler(mTestLooper.getLooper());
        NetworkProvider networkProvider =
                new NetworkProvider(mContext, mTestLooper.getLooper(), "ThreadNetworkProvider");

        mFakeOtDaemon = new FakeOtDaemon(handler);

        when(mMockTunIfController.getTunFd()).thenReturn(mMockTunFd);

        when(mMockPersistentSettings.get(any())).thenReturn(true);

        mService =
                new ThreadNetworkControllerService(
                        ApplicationProvider.getApplicationContext(),
                        handler,
                        networkProvider,
                        () -> mFakeOtDaemon,
                        mMockConnectivityManager,
                        mMockTunIfController,
                        mMockInfraIfController,
                        mMockPersistentSettings,
                        mMockNsdPublisher);
        mService.setTestNetworkAgent(mMockNetworkAgent);
    }

    @Test
    public void initialize_tunInterfaceAndNsdPublisherSetToOtDaemon() throws Exception {
        when(mMockTunIfController.getTunFd()).thenReturn(mMockTunFd);

        mService.initialize();
        mTestLooper.dispatchAll();

        verify(mMockTunIfController, times(1)).createTunInterface();
        assertThat(mFakeOtDaemon.getTunFd()).isEqualTo(mMockTunFd);
        assertThat(mFakeOtDaemon.getNsdPublisher()).isEqualTo(mMockNsdPublisher);
    }

    @Test
    public void join_otDaemonRemoteFailure_returnsInternalError() throws Exception {
        mService.initialize();
        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        mFakeOtDaemon.setJoinException(new RemoteException("ot-daemon join() throws"));

        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> mService.join(DEFAULT_ACTIVE_DATASET, mockReceiver));
        mTestLooper.dispatchAll();

        verify(mockReceiver, never()).onSuccess();
        verify(mockReceiver, times(1)).onError(eq(ERROR_INTERNAL_ERROR), anyString());
    }

    @Test
    public void join_succeed_threadNetworkRegistered() throws Exception {
        mService.initialize();
        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);

        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> mService.join(DEFAULT_ACTIVE_DATASET, mockReceiver));
        // Here needs to call Testlooper#dispatchAll twices because TestLooper#moveTimeForward
        // operates on only currently enqueued messages but the delayed message is posted from
        // another Handler task.
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(FakeOtDaemon.JOIN_DELAY.toMillis() + 100);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
        verify(mMockNetworkAgent, times(1)).register();
    }
}
