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

package android.net.thread.cts;

import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeNotNull;

import android.content.Context;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkManager;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** CTS tests for {@link ThreadNetworkController}. */
@SmallTest
@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU) // Thread is available on only U+
public class ThreadNetworkControllerTest {
    @Rule public DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ThreadNetworkManager mManager;

    @Before
    public void setUp() {
        mManager = mContext.getSystemService(ThreadNetworkManager.class);

        // TODO: we will also need it in tearDown(), it's better to have a Rule to skip
        // tests if a feature is not available.
        assumeNotNull(mManager);
    }

    private List<ThreadNetworkController> getAllControllers() {
        return mManager.getAllThreadNetworkControllers();
    }

    @Test
    public void getThreadVersion_returnsAtLeastThreadVersion1P3() {
        for (ThreadNetworkController controller : getAllControllers()) {
            assertThat(controller.getThreadVersion()).isAtLeast(THREAD_VERSION_1_3);
        }
    }
}
