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

import static android.net.thread.ThreadNetworkException.ERROR_UNSUPPORTED_OPERATION;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.os.OutcomeReceiver;
import android.util.SparseIntArray;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tests for hide methods of {@link ThreadNetworkController}. */
@LargeTest
@RequiresThreadFeature
@RunWith(AndroidJUnit4.class)
public class ThreadNetworkControllerTest {
    private static final int VALID_POWER = 32_767;
    private static final int INVALID_POWER = 32_768;
    private static final int VALID_CHANNEL = 20;
    private static final int INVALID_CHANNEL = 10;
    private static final String THREAD_NETWORK_PRIVILEGED =
            "android.permission.THREAD_NETWORK_PRIVILEGED";

    private static final SparseIntArray CHANNEL_MAX_POWERS =
            new SparseIntArray() {
                {
                    put(20, 32767);
                }
            };

    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ExecutorService mExecutor;
    private ThreadNetworkController mController;

    @Before
    public void setUp() throws Exception {
        mController =
                mContext.getSystemService(ThreadNetworkManager.class)
                        .getAllThreadNetworkControllers()
                        .get(0);

        mExecutor = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() throws Exception {
        dropAllPermissions();
    }

    @Test
    public void setChannelMaxPowers_withPrivilegedPermission_success() throws Exception {
        CompletableFuture<Void> powerFuture = new CompletableFuture<>();

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () ->
                        mController.setChannelMaxPowers(
                                CHANNEL_MAX_POWERS, mExecutor, newOutcomeReceiver(powerFuture)));

        try {
            assertThat(powerFuture.get()).isNull();
        } catch (ExecutionException exception) {
            ThreadNetworkException thrown = (ThreadNetworkException) exception.getCause();
            assertThat(thrown.getErrorCode()).isEqualTo(ERROR_UNSUPPORTED_OPERATION);
        }
    }

    @Test
    public void setChannelMaxPowers_withoutPrivilegedPermission_throwsSecurityException()
            throws Exception {
        dropAllPermissions();

        assertThrows(
                SecurityException.class,
                () -> mController.setChannelMaxPowers(CHANNEL_MAX_POWERS, mExecutor, v -> {}));
    }

    @Test
    public void setChannelMaxPowers_emptyChannelMaxPower_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mController.setChannelMaxPowers(new SparseIntArray(), mExecutor, v -> {}));
    }

    @Test
    public void setChannelMaxPowers_invalidChannel_throwsIllegalArgumentException() {
        final SparseIntArray INVALID_CHANNEL_ARRAY =
                new SparseIntArray() {
                    {
                        put(INVALID_CHANNEL, VALID_POWER);
                    }
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.setChannelMaxPowers(INVALID_CHANNEL_ARRAY, mExecutor, v -> {}));
    }

    @Test
    public void setChannelMaxPowers_invalidPower_throwsIllegalArgumentException() {
        final SparseIntArray INVALID_POWER_ARRAY =
                new SparseIntArray() {
                    {
                        put(VALID_CHANNEL, INVALID_POWER);
                    }
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.setChannelMaxPowers(INVALID_POWER_ARRAY, mExecutor, v -> {}));
    }

    private static void dropAllPermissions() {
        getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    private static <V> OutcomeReceiver<V, ThreadNetworkException> newOutcomeReceiver(
            CompletableFuture<V> future) {
        return new OutcomeReceiver<V, ThreadNetworkException>() {
            @Override
            public void onResult(V result) {
                future.complete(result);
            }

            @Override
            public void onError(ThreadNetworkException e) {
                future.completeExceptionally(e);
            }
        };
    }
}
