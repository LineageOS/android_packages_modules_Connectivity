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

import static android.net.thread.ThreadNetworkController.STATE_DISABLED;
import static android.net.thread.ThreadNetworkController.STATE_ENABLED;
import static android.net.thread.ThreadNetworkException.ERROR_THREAD_DISABLED;

import static com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;

/** Integration tests for {@link ThreadNetworkShellCommand}. */
@LargeTest
@RequiresThreadFeature
@RunWith(AndroidJUnit4.class)
public class ThreadNetworkShellCommandTest {
    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final ThreadNetworkControllerWrapper mController =
            ThreadNetworkControllerWrapper.newInstance(mContext);

    @Before
    public void setUp() {
        ensureThreadEnabled();
    }

    @After
    public void tearDown() {
        ensureThreadEnabled();
    }

    private static void ensureThreadEnabled() {
        runThreadCommand("force-stop-ot-daemon disabled");
        runThreadCommand("enable");
    }

    @Test
    public void enable_threadStateIsEnabled() throws Exception {
        runThreadCommand("enable");

        assertThat(mController.getEnabledState()).isEqualTo(STATE_ENABLED);
    }

    @Test
    public void disable_threadStateIsDisabled() throws Exception {
        runThreadCommand("disable");

        assertThat(mController.getEnabledState()).isEqualTo(STATE_DISABLED);
    }

    @Test
    public void forceStopOtDaemon_forceStopEnabled_otDaemonServiceDisappear() {
        runThreadCommand("force-stop-ot-daemon enabled");

        assertThat(runShellCommandOrThrow("service list")).doesNotContain("ot_daemon");
    }

    @Test
    public void forceStopOtDaemon_forceStopEnabled_canNotEnableThread() throws Exception {
        runThreadCommand("force-stop-ot-daemon enabled");

        ExecutionException thrown =
                assertThrows(ExecutionException.class, () -> mController.setEnabledAndWait(true));
        ThreadNetworkException cause = (ThreadNetworkException) thrown.getCause();
        assertThat(cause.getErrorCode()).isEqualTo(ERROR_THREAD_DISABLED);
    }

    @Test
    public void forceStopOtDaemon_forceStopDisabled_otDaemonServiceAppears() throws Exception {
        runThreadCommand("force-stop-ot-daemon disabled");

        assertThat(runShellCommandOrThrow("service list")).contains("ot_daemon");
    }

    @Test
    public void forceStopOtDaemon_forceStopDisabled_canEnableThread() throws Exception {
        runThreadCommand("force-stop-ot-daemon disabled");

        mController.setEnabledAndWait(true);
        assertThat(mController.getEnabledState()).isEqualTo(STATE_ENABLED);
    }

    @Test
    public void forceCountryCode_setCN_getCountryCodeReturnsCN() {
        runThreadCommand("force-country-code enabled CN");

        final String result = runThreadCommand("get-country-code");
        assertThat(result).contains("Thread country code = CN");
    }

    private static String runThreadCommand(String cmd) {
        return runShellCommandOrThrow("cmd thread_network " + cmd);
    }
}
