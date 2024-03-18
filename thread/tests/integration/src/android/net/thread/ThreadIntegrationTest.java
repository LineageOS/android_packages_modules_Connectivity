/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.net.thread;

import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_DETACHED;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_STOPPED;
import static android.net.thread.utils.IntegrationTestUtils.CALLBACK_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.RESTART_JOIN_TIMEOUT;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.net.thread.utils.OtDaemonController;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet6Address;
import java.util.List;

/** Tests for E2E Android Thread integration with ot-daemon, ConnectivityService, etc.. */
@LargeTest
@RequiresThreadFeature
@RunWith(AndroidJUnit4.class)
public class ThreadIntegrationTest {
    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset init new".
    private static final byte[] DEFAULT_DATASET_TLVS =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");
    private static final ActiveOperationalDataset DEFAULT_DATASET =
            ActiveOperationalDataset.fromThreadTlvs(DEFAULT_DATASET_TLVS);

    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final ThreadNetworkControllerWrapper mController =
            ThreadNetworkControllerWrapper.newInstance(mContext);
    private OtDaemonController mOtCtl;

    @Before
    public void setUp() throws Exception {
        mOtCtl = new OtDaemonController();
        mController.leaveAndWait();

        // TODO: b/323301831 - This is a workaround to avoid unnecessary delay to re-form a network
        mOtCtl.factoryReset();
    }

    @After
    public void tearDown() throws Exception {
        mController.setTestNetworkAsUpstreamAndWait(null);
        mController.leaveAndWait();
    }

    @Test
    public void otDaemonRestart_notJoinedAndStopped_deviceRoleIsStopped() throws Exception {
        mController.leaveAndWait();

        runShellCommand("stop ot-daemon");
        // TODO(b/323331973): the sleep is needed to workaround the race conditions
        SystemClock.sleep(200);

        mController.waitForRole(DEVICE_ROLE_STOPPED, CALLBACK_TIMEOUT);
    }

    @Test
    public void otDaemonRestart_JoinedNetworkAndStopped_autoRejoined() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        runShellCommand("stop ot-daemon");

        mController.waitForRole(DEVICE_ROLE_DETACHED, CALLBACK_TIMEOUT);
        mController.waitForRole(DEVICE_ROLE_LEADER, RESTART_JOIN_TIMEOUT);
    }

    @Test
    public void otDaemonFactoryReset_deviceRoleIsStopped() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        mOtCtl.factoryReset();

        assertThat(mController.getDeviceRole()).isEqualTo(DEVICE_ROLE_STOPPED);
    }

    @Test
    public void otDaemonFactoryReset_addressesRemoved() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        mOtCtl.factoryReset();
        String ifconfig = runShellCommand("ifconfig thread-wpan");

        assertThat(ifconfig).doesNotContain("inet6 addr");
    }

    @Test
    public void tunInterface_joinedNetwork_otAddressesAddedToTunInterface() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        String ifconfig = runShellCommand("ifconfig thread-wpan");
        List<Inet6Address> otAddresses = mOtCtl.getAddresses();
        assertThat(otAddresses).isNotEmpty();
        for (Inet6Address otAddress : otAddresses) {
            assertThat(ifconfig).contains(otAddress.getHostAddress());
        }
    }

    // TODO (b/323300829): add more tests for integration with linux platform and
    // ConnectivityService
}
