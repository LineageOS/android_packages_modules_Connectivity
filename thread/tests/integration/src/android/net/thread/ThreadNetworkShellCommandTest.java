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

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Integration tests for {@link ThreadNetworkShellCommand}. */
@LargeTest
@RequiresThreadFeature
@RunWith(AndroidJUnit4.class)
public class ThreadNetworkShellCommandTest {
    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final ThreadNetworkControllerWrapper mController =
            ThreadNetworkControllerWrapper.newInstance(mContext);

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
    public void forceCountryCode_setCN_getCountryCodeReturnsCN() {
        runThreadCommand("force-country-code enabled CN");

        final String result = runThreadCommand("get-country-code");
        assertThat(result).contains("Thread country code = CN");
    }

    private static String runThreadCommand(String cmd) {
        return runShellCommand("cmd thread_network " + cmd);
    }
}
