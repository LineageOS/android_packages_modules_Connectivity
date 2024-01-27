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

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_CHILD;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_ROUTER;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_STOPPED;
import static android.net.thread.ThreadNetworkController.STATE_DISABLED;
import static android.net.thread.ThreadNetworkController.STATE_DISABLING;
import static android.net.thread.ThreadNetworkController.STATE_ENABLED;
import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;
import static android.net.thread.ThreadNetworkException.ERROR_ABORTED;
import static android.net.thread.ThreadNetworkException.ERROR_FAILED_PRECONDITION;
import static android.net.thread.ThreadNetworkException.ERROR_REJECTED_BY_PEER;
import static android.net.thread.ThreadNetworkException.ERROR_THREAD_DISABLED;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeNotNull;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.OperationalDatasetTimestamp;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.OperationalDatasetCallback;
import android.net.thread.ThreadNetworkController.StateCallback;
import android.net.thread.ThreadNetworkException;
import android.net.thread.ThreadNetworkManager;
import android.os.Build;
import android.os.OutcomeReceiver;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;

import com.android.net.module.util.ArrayTrackRecord;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** CTS tests for {@link ThreadNetworkController}. */
@LargeTest
@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU) // Thread is available on only U+
public class ThreadNetworkControllerTest {
    private static final int JOIN_TIMEOUT_MILLIS = 30 * 1000;
    private static final int LEAVE_TIMEOUT_MILLIS = 2_000;
    private static final int MIGRATION_TIMEOUT_MILLIS = 40 * 1_000;
    private static final int NETWORK_CALLBACK_TIMEOUT_MILLIS = 10 * 1000;
    private static final int CALLBACK_TIMEOUT_MILLIS = 1_000;
    private static final int ENABLED_TIMEOUT_MILLIS = 2_000;
    private static final String PERMISSION_THREAD_NETWORK_PRIVILEGED =
            "android.permission.THREAD_NETWORK_PRIVILEGED";

    @Rule public DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ExecutorService mExecutor;
    private ThreadNetworkManager mManager;

    private Set<String> mGrantedPermissions;

    @Before
    public void setUp() throws Exception {
        mExecutor = Executors.newSingleThreadExecutor();
        mManager = mContext.getSystemService(ThreadNetworkManager.class);
        mGrantedPermissions = new HashSet<String>();

        // TODO: we will also need it in tearDown(), it's better to have a Rule to skip
        // tests if a feature is not available.
        assumeNotNull(mManager);

        for (ThreadNetworkController controller : getAllControllers()) {
            setEnabledAndWait(controller, true);
        }
    }

    @After
    public void tearDown() throws Exception {
        if (mManager != null) {
            dropAllPermissions();
            leaveAndWait();
        }
    }

    private List<ThreadNetworkController> getAllControllers() {
        return mManager.getAllThreadNetworkControllers();
    }

    private void leaveAndWait() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            leave(controller, future::complete);
            future.get(LEAVE_TIMEOUT_MILLIS, MILLISECONDS);
        }
    }

    private void grantPermissions(String... permissions) {
        for (String permission : permissions) {
            mGrantedPermissions.add(permission);
        }
        String[] allPermissions = new String[mGrantedPermissions.size()];
        mGrantedPermissions.toArray(allPermissions);
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(allPermissions);
    }

    private static void dropAllPermissions() {
        getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    private static ActiveOperationalDataset newRandomizedDataset(
            String networkName, ThreadNetworkController controller) throws Exception {
        CompletableFuture<ActiveOperationalDataset> future = new CompletableFuture<>();
        controller.createRandomizedDataset(networkName, directExecutor(), future::complete);
        return future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private static boolean isAttached(ThreadNetworkController controller) throws Exception {
        return ThreadNetworkController.isAttached(getDeviceRole(controller));
    }

    private static int getDeviceRole(ThreadNetworkController controller) throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        StateCallback callback = future::complete;
        controller.registerStateCallback(directExecutor(), callback);
        int role = future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
        controller.unregisterStateCallback(callback);
        return role;
    }

    private static int waitForAttachedState(ThreadNetworkController controller) throws Exception {
        List<Integer> attachedRoles = new ArrayList<>();
        attachedRoles.add(DEVICE_ROLE_CHILD);
        attachedRoles.add(DEVICE_ROLE_ROUTER);
        attachedRoles.add(DEVICE_ROLE_LEADER);
        return waitForStateAnyOf(controller, attachedRoles);
    }

    private static int waitForStateAnyOf(
            ThreadNetworkController controller, List<Integer> deviceRoles) throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        StateCallback callback =
                newRole -> {
                    if (deviceRoles.contains(newRole)) {
                        future.complete(newRole);
                    }
                };
        controller.registerStateCallback(directExecutor(), callback);
        int role = future.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);
        controller.unregisterStateCallback(callback);
        return role;
    }

    private static void waitForEnabledState(ThreadNetworkController controller, int state)
            throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        StateCallback callback =
                new ThreadNetworkController.StateCallback() {
                    @Override
                    public void onDeviceRoleChanged(int r) {}

                    @Override
                    public void onThreadEnableStateChanged(int enabled) {
                        if (enabled == state) {
                            future.complete(enabled);
                        }
                    }
                };
        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> controller.registerStateCallback(directExecutor(), callback));
        future.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
        runAsShell(ACCESS_NETWORK_STATE, () -> controller.unregisterStateCallback(callback));
    }

    private void leave(
            ThreadNetworkController controller,
            OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED, () -> controller.leave(mExecutor, receiver));
    }

    private void scheduleMigration(
            ThreadNetworkController controller,
            PendingOperationalDataset pendingDataset,
            OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> controller.scheduleMigration(pendingDataset, mExecutor, receiver));
    }

    private class EnabledStateListener {
        private ArrayTrackRecord<Integer> mEnabledStates = new ArrayTrackRecord<>();
        private final ArrayTrackRecord<Integer>.ReadHead mReadHead = mEnabledStates.newReadHead();
        ThreadNetworkController mController;
        StateCallback mCallback =
                new ThreadNetworkController.StateCallback() {
                    @Override
                    public void onDeviceRoleChanged(int r) {}

                    @Override
                    public void onThreadEnableStateChanged(int enabled) {
                        mEnabledStates.add(enabled);
                    }
                };

        EnabledStateListener(ThreadNetworkController controller) {
            this.mController = controller;
            runAsShell(
                    ACCESS_NETWORK_STATE,
                    () -> controller.registerStateCallback(mExecutor, mCallback));
        }

        public void expectThreadEnabledState(int enabled) {
            assertNotNull(mReadHead.poll(ENABLED_TIMEOUT_MILLIS, e -> (e == enabled)));
        }

        public void unregisterStateCallback() {
            runAsShell(ACCESS_NETWORK_STATE, () -> mController.unregisterStateCallback(mCallback));
        }
    }

    private int booleanToEnabledState(boolean enabled) {
        return enabled ? STATE_ENABLED : STATE_DISABLED;
    }

    private void setEnabledAndWait(ThreadNetworkController controller, boolean enabled)
            throws Exception {
        CompletableFuture<Void> setFuture = new CompletableFuture<>();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> controller.setEnabled(enabled, mExecutor, newOutcomeReceiver(setFuture)));
        setFuture.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
        waitForEnabledState(controller, booleanToEnabledState(enabled));
    }

    private CompletableFuture joinRandomizedDataset(ThreadNetworkController controller)
            throws Exception {
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture)));
        return joinFuture;
    }

    private void joinRandomizedDatasetAndWait(ThreadNetworkController controller) throws Exception {
        CompletableFuture<Void> joinFuture = joinRandomizedDataset(controller);
        joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);
        runAsShell(ACCESS_NETWORK_STATE, () -> assertThat(isAttached(controller)).isTrue());
    }

    private static ActiveOperationalDataset getActiveOperationalDataset(
            ThreadNetworkController controller) throws Exception {
        CompletableFuture<ActiveOperationalDataset> future = new CompletableFuture<>();
        OperationalDatasetCallback callback = future::complete;
        controller.registerOperationalDatasetCallback(directExecutor(), callback);
        ActiveOperationalDataset dataset = future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
        controller.unregisterOperationalDatasetCallback(callback);
        return dataset;
    }

    private static PendingOperationalDataset getPendingOperationalDataset(
            ThreadNetworkController controller) throws Exception {
        CompletableFuture<ActiveOperationalDataset> activeFuture = new CompletableFuture<>();
        CompletableFuture<PendingOperationalDataset> pendingFuture = new CompletableFuture<>();
        controller.registerOperationalDatasetCallback(
                directExecutor(), newDatasetCallback(activeFuture, pendingFuture));
        return pendingFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private static OperationalDatasetCallback newDatasetCallback(
            CompletableFuture<ActiveOperationalDataset> activeFuture,
            CompletableFuture<PendingOperationalDataset> pendingFuture) {
        return new OperationalDatasetCallback() {
            @Override
            public void onActiveOperationalDatasetChanged(
                    ActiveOperationalDataset activeOpDataset) {
                activeFuture.complete(activeOpDataset);
            }

            @Override
            public void onPendingOperationalDatasetChanged(
                    PendingOperationalDataset pendingOpDataset) {
                pendingFuture.complete(pendingOpDataset);
            }
        };
    }

    @Test
    public void getThreadVersion_returnsAtLeastThreadVersion1P3() {
        for (ThreadNetworkController controller : getAllControllers()) {
            assertThat(controller.getThreadVersion()).isAtLeast(THREAD_VERSION_1_3);
        }
    }

    @Test
    public void registerStateCallback_permissionsGranted_returnsCurrentStates() throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE);

        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
            StateCallback callback = deviceRole::complete;

            try {
                controller.registerStateCallback(mExecutor, callback);

                assertThat(deviceRole.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS))
                        .isEqualTo(DEVICE_ROLE_STOPPED);
            } finally {
                controller.unregisterStateCallback(callback);
            }
        }
    }

    @Test
    public void registerStateCallback_returnsUpdatedEnabledStates() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<Void> setFuture1 = new CompletableFuture<>();
            CompletableFuture<Void> setFuture2 = new CompletableFuture<>();
            EnabledStateListener listener = new EnabledStateListener(controller);

            runAsShell(
                    PERMISSION_THREAD_NETWORK_PRIVILEGED,
                    () -> {
                        controller.setEnabled(false, mExecutor, newOutcomeReceiver(setFuture1));
                    });
            setFuture1.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);

            runAsShell(
                    PERMISSION_THREAD_NETWORK_PRIVILEGED,
                    () -> {
                        controller.setEnabled(true, mExecutor, newOutcomeReceiver(setFuture2));
                    });
            setFuture2.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);

            listener.expectThreadEnabledState(STATE_ENABLED);
            listener.expectThreadEnabledState(STATE_DISABLING);
            listener.expectThreadEnabledState(STATE_DISABLED);
            listener.expectThreadEnabledState(STATE_ENABLED);

            listener.unregisterStateCallback();
        }
    }

    @Test
    public void registerStateCallback_noPermissions_throwsSecurityException() throws Exception {
        dropAllPermissions();

        for (ThreadNetworkController controller : getAllControllers()) {
            assertThrows(
                    SecurityException.class,
                    () -> controller.registerStateCallback(mExecutor, role -> {}));
        }
    }

    @Test
    public void registerStateCallback_alreadyRegistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE);

        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
            StateCallback callback = role -> deviceRole.complete(role);
            controller.registerStateCallback(mExecutor, callback);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> controller.registerStateCallback(mExecutor, callback));
        }
    }

    @Test
    public void unregisterStateCallback_noPermissions_throwsSecurityException() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
            StateCallback callback = role -> deviceRole.complete(role);
            grantPermissions(ACCESS_NETWORK_STATE);
            controller.registerStateCallback(mExecutor, callback);

            try {
                dropAllPermissions();
                assertThrows(
                        SecurityException.class,
                        () -> controller.unregisterStateCallback(callback));
            } finally {
                grantPermissions(ACCESS_NETWORK_STATE);
                controller.unregisterStateCallback(callback);
            }
        }
    }

    @Test
    public void unregisterStateCallback_callbackRegistered_success() throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE);
        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
            StateCallback callback = role -> deviceRole.complete(role);
            controller.registerStateCallback(mExecutor, callback);

            controller.unregisterStateCallback(callback);
        }
    }

    @Test
    public void unregisterStateCallback_callbackNotRegistered_throwsIllegalArgumentException()
            throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
            StateCallback callback = role -> deviceRole.complete(role);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> controller.unregisterStateCallback(callback));
        }
    }

    @Test
    public void unregisterStateCallback_alreadyUnregistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE);
        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
            StateCallback callback = deviceRole::complete;
            controller.registerStateCallback(mExecutor, callback);
            controller.unregisterStateCallback(callback);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> controller.unregisterStateCallback(callback));
        }
    }

    @Test
    public void registerOperationalDatasetCallback_permissionsGranted_returnsCurrentStates()
            throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<ActiveOperationalDataset> activeFuture = new CompletableFuture<>();
            CompletableFuture<PendingOperationalDataset> pendingFuture = new CompletableFuture<>();
            var callback = newDatasetCallback(activeFuture, pendingFuture);

            try {
                controller.registerOperationalDatasetCallback(mExecutor, callback);

                assertThat(activeFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isNull();
                assertThat(pendingFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isNull();
            } finally {
                controller.unregisterOperationalDatasetCallback(callback);
            }
        }
    }

    @Test
    public void registerOperationalDatasetCallback_noPermissions_throwsSecurityException()
            throws Exception {
        dropAllPermissions();

        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<ActiveOperationalDataset> activeFuture = new CompletableFuture<>();
            CompletableFuture<PendingOperationalDataset> pendingFuture = new CompletableFuture<>();
            var callback = newDatasetCallback(activeFuture, pendingFuture);

            assertThrows(
                    SecurityException.class,
                    () -> controller.registerOperationalDatasetCallback(mExecutor, callback));
        }
    }

    @Test
    public void unregisterOperationalDatasetCallback_callbackRegistered_success() throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<ActiveOperationalDataset> activeFuture = new CompletableFuture<>();
            CompletableFuture<PendingOperationalDataset> pendingFuture = new CompletableFuture<>();
            var callback = newDatasetCallback(activeFuture, pendingFuture);
            controller.registerOperationalDatasetCallback(mExecutor, callback);

            controller.unregisterOperationalDatasetCallback(callback);
        }
    }

    @Test
    public void unregisterOperationalDatasetCallback_noPermissions_throwsSecurityException()
            throws Exception {
        dropAllPermissions();

        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<ActiveOperationalDataset> activeFuture = new CompletableFuture<>();
            CompletableFuture<PendingOperationalDataset> pendingFuture = new CompletableFuture<>();
            var callback = newDatasetCallback(activeFuture, pendingFuture);
            grantPermissions(ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
            controller.registerOperationalDatasetCallback(mExecutor, callback);

            try {
                dropAllPermissions();
                assertThrows(
                        SecurityException.class,
                        () -> controller.unregisterOperationalDatasetCallback(callback));
            } finally {
                grantPermissions(ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
                controller.unregisterOperationalDatasetCallback(callback);
            }
        }
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

    @Test
    public void join_withPrivilegedPermission_success() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
            CompletableFuture<Void> joinFuture = new CompletableFuture<>();

            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);

            grantPermissions(ACCESS_NETWORK_STATE);
            assertThat(isAttached(controller)).isTrue();
            assertThat(getActiveOperationalDataset(controller)).isEqualTo(activeDataset);
        }
    }

    @Test
    public void join_withoutPrivilegedPermission_throwsSecurityException() throws Exception {
        dropAllPermissions();

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);

            assertThrows(
                    SecurityException.class,
                    () -> controller.join(activeDataset, mExecutor, v -> {}));
        }
    }

    @Test
    public void join_threadDisabled_failsWithErrorThreadDisabled() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            setEnabledAndWait(controller, false);

            CompletableFuture<Void> joinFuture = joinRandomizedDataset(controller);

            ThreadNetworkException thrown =
                    (ThreadNetworkException)
                            assertThrows(ExecutionException.class, joinFuture::get).getCause();
            assertThat(thrown.getErrorCode()).isEqualTo(ERROR_THREAD_DISABLED);
        }
    }

    @Test
    public void join_concurrentRequests_firstOneIsAborted() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        final byte[] KEY_1 = new byte[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        final byte[] KEY_2 = new byte[] {2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2};
        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset1 =
                    new ActiveOperationalDataset.Builder(
                                    newRandomizedDataset("TestNet", controller))
                            .setNetworkKey(KEY_1)
                            .build();
            ActiveOperationalDataset activeDataset2 =
                    new ActiveOperationalDataset.Builder(activeDataset1)
                            .setNetworkKey(KEY_2)
                            .build();
            CompletableFuture<Void> joinFuture1 = new CompletableFuture<>();
            CompletableFuture<Void> joinFuture2 = new CompletableFuture<>();

            controller.join(activeDataset1, mExecutor, newOutcomeReceiver(joinFuture1));
            controller.join(activeDataset2, mExecutor, newOutcomeReceiver(joinFuture2));

            ThreadNetworkException thrown =
                    (ThreadNetworkException)
                            assertThrows(ExecutionException.class, joinFuture1::get).getCause();
            assertThat(thrown.getErrorCode()).isEqualTo(ERROR_ABORTED);
            joinFuture2.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);
            grantPermissions(ACCESS_NETWORK_STATE);
            assertThat(isAttached(controller)).isTrue();
            assertThat(getActiveOperationalDataset(controller)).isEqualTo(activeDataset2);
        }
    }

    @Test
    public void leave_withPrivilegedPermission_success() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            joinRandomizedDatasetAndWait(controller);

            CompletableFuture<Void> leaveFuture = new CompletableFuture<>();
            leave(controller, newOutcomeReceiver(leaveFuture));
            leaveFuture.get(LEAVE_TIMEOUT_MILLIS, MILLISECONDS);

            grantPermissions(ACCESS_NETWORK_STATE);
            assertThat(getDeviceRole(controller)).isEqualTo(DEVICE_ROLE_STOPPED);
        }
    }

    @Test
    public void leave_withoutPrivilegedPermission_throwsSecurityException() {
        dropAllPermissions();

        for (ThreadNetworkController controller : getAllControllers()) {
            assertThrows(SecurityException.class, () -> controller.leave(mExecutor, v -> {}));
        }
    }

    @Test
    public void leave_threadDisabled_success() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            joinRandomizedDatasetAndWait(controller);

            CompletableFuture<Void> leaveFuture = new CompletableFuture<>();
            setEnabledAndWait(controller, false);
            leave(controller, newOutcomeReceiver(leaveFuture));

            leaveFuture.get(LEAVE_TIMEOUT_MILLIS, MILLISECONDS);
            runAsShell(
                    ACCESS_NETWORK_STATE,
                    () -> assertThat(getDeviceRole(controller)).isEqualTo(DEVICE_ROLE_STOPPED));
        }
    }

    @Test
    public void leave_concurrentRequests_bothSuccess() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
            CompletableFuture<Void> joinFuture = new CompletableFuture<>();
            CompletableFuture<Void> leaveFuture1 = new CompletableFuture<>();
            CompletableFuture<Void> leaveFuture2 = new CompletableFuture<>();
            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);

            controller.leave(mExecutor, newOutcomeReceiver(leaveFuture1));
            controller.leave(mExecutor, newOutcomeReceiver(leaveFuture2));

            leaveFuture1.get(LEAVE_TIMEOUT_MILLIS, MILLISECONDS);
            leaveFuture2.get(LEAVE_TIMEOUT_MILLIS, MILLISECONDS);
            grantPermissions(ACCESS_NETWORK_STATE);
            assertThat(getDeviceRole(controller)).isEqualTo(DEVICE_ROLE_STOPPED);
        }
    }

    @Test
    public void scheduleMigration_withPrivilegedPermission_newDatasetApplied() throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset1 =
                    new ActiveOperationalDataset.Builder(
                                    newRandomizedDataset("TestNet", controller))
                            .setActiveTimestamp(new OperationalDatasetTimestamp(1L, 0, false))
                            .setExtendedPanId(new byte[] {1, 1, 1, 1, 1, 1, 1, 1})
                            .build();
            ActiveOperationalDataset activeDataset2 =
                    new ActiveOperationalDataset.Builder(activeDataset1)
                            .setActiveTimestamp(new OperationalDatasetTimestamp(2L, 0, false))
                            .setNetworkName("ThreadNet2")
                            .build();
            PendingOperationalDataset pendingDataset2 =
                    new PendingOperationalDataset(
                            activeDataset2,
                            OperationalDatasetTimestamp.fromInstant(Instant.now()),
                            Duration.ofSeconds(30));
            CompletableFuture<Void> joinFuture = new CompletableFuture<>();
            CompletableFuture<Void> migrateFuture = new CompletableFuture<>();
            controller.join(activeDataset1, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);

            controller.scheduleMigration(
                    pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture));
            migrateFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);

            CompletableFuture<Boolean> dataset2IsApplied = new CompletableFuture<>();
            CompletableFuture<Boolean> pendingDatasetIsRemoved = new CompletableFuture<>();
            OperationalDatasetCallback datasetCallback =
                    new OperationalDatasetCallback() {
                        @Override
                        public void onActiveOperationalDatasetChanged(
                                ActiveOperationalDataset activeDataset) {
                            if (activeDataset.equals(activeDataset2)) {
                                dataset2IsApplied.complete(true);
                            }
                        }

                        @Override
                        public void onPendingOperationalDatasetChanged(
                                PendingOperationalDataset pendingDataset) {
                            if (pendingDataset == null) {
                                pendingDatasetIsRemoved.complete(true);
                            }
                        }
                    };
            controller.registerOperationalDatasetCallback(directExecutor(), datasetCallback);
            assertThat(dataset2IsApplied.get(MIGRATION_TIMEOUT_MILLIS, MILLISECONDS)).isTrue();
            assertThat(pendingDatasetIsRemoved.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isTrue();
            controller.unregisterOperationalDatasetCallback(datasetCallback);
        }
    }

    @Test
    public void scheduleMigration_whenNotAttached_failWithPreconditionError() throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            PendingOperationalDataset pendingDataset =
                    new PendingOperationalDataset(
                            newRandomizedDataset("TestNet", controller),
                            OperationalDatasetTimestamp.fromInstant(Instant.now()),
                            Duration.ofSeconds(30));
            CompletableFuture<Void> migrateFuture = new CompletableFuture<>();

            controller.scheduleMigration(
                    pendingDataset, mExecutor, newOutcomeReceiver(migrateFuture));

            ThreadNetworkException thrown =
                    (ThreadNetworkException)
                            assertThrows(ExecutionException.class, migrateFuture::get).getCause();
            assertThat(thrown.getErrorCode()).isEqualTo(ERROR_FAILED_PRECONDITION);
        }
    }

    @Test
    public void scheduleMigration_secondRequestHasSmallerTimestamp_rejectedByLeader()
            throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            final ActiveOperationalDataset activeDataset =
                    new ActiveOperationalDataset.Builder(
                                    newRandomizedDataset("testNet", controller))
                            .setActiveTimestamp(new OperationalDatasetTimestamp(1L, 0, false))
                            .build();
            ActiveOperationalDataset activeDataset1 =
                    new ActiveOperationalDataset.Builder(activeDataset)
                            .setActiveTimestamp(new OperationalDatasetTimestamp(2L, 0, false))
                            .setNetworkName("testNet1")
                            .build();
            PendingOperationalDataset pendingDataset1 =
                    new PendingOperationalDataset(
                            activeDataset1,
                            new OperationalDatasetTimestamp(100, 0, false),
                            Duration.ofSeconds(30));
            ActiveOperationalDataset activeDataset2 =
                    new ActiveOperationalDataset.Builder(activeDataset)
                            .setActiveTimestamp(new OperationalDatasetTimestamp(3L, 0, false))
                            .setNetworkName("testNet2")
                            .build();
            PendingOperationalDataset pendingDataset2 =
                    new PendingOperationalDataset(
                            activeDataset2,
                            new OperationalDatasetTimestamp(20, 0, false),
                            Duration.ofSeconds(30));
            CompletableFuture<Void> joinFuture = new CompletableFuture<>();
            CompletableFuture<Void> migrateFuture1 = new CompletableFuture<>();
            CompletableFuture<Void> migrateFuture2 = new CompletableFuture<>();
            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);

            controller.scheduleMigration(
                    pendingDataset1, mExecutor, newOutcomeReceiver(migrateFuture1));
            migrateFuture1.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
            controller.scheduleMigration(
                    pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture2));

            ThreadNetworkException thrown =
                    (ThreadNetworkException)
                            assertThrows(ExecutionException.class, migrateFuture2::get).getCause();
            assertThat(thrown.getErrorCode()).isEqualTo(ERROR_REJECTED_BY_PEER);
        }
    }

    @Test
    public void scheduleMigration_secondRequestHasLargerTimestamp_newDatasetApplied()
            throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            final ActiveOperationalDataset activeDataset =
                    new ActiveOperationalDataset.Builder(
                                    newRandomizedDataset("validName", controller))
                            .setActiveTimestamp(new OperationalDatasetTimestamp(1L, 0, false))
                            .build();
            ActiveOperationalDataset activeDataset1 =
                    new ActiveOperationalDataset.Builder(activeDataset)
                            .setActiveTimestamp(new OperationalDatasetTimestamp(2L, 0, false))
                            .setNetworkName("testNet1")
                            .build();
            PendingOperationalDataset pendingDataset1 =
                    new PendingOperationalDataset(
                            activeDataset1,
                            new OperationalDatasetTimestamp(100, 0, false),
                            Duration.ofSeconds(30));
            ActiveOperationalDataset activeDataset2 =
                    new ActiveOperationalDataset.Builder(activeDataset)
                            .setActiveTimestamp(new OperationalDatasetTimestamp(3L, 0, false))
                            .setNetworkName("testNet2")
                            .build();
            PendingOperationalDataset pendingDataset2 =
                    new PendingOperationalDataset(
                            activeDataset2,
                            new OperationalDatasetTimestamp(200, 0, false),
                            Duration.ofSeconds(30));
            CompletableFuture<Void> joinFuture = new CompletableFuture<>();
            CompletableFuture<Void> migrateFuture1 = new CompletableFuture<>();
            CompletableFuture<Void> migrateFuture2 = new CompletableFuture<>();
            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);

            controller.scheduleMigration(
                    pendingDataset1, mExecutor, newOutcomeReceiver(migrateFuture1));
            migrateFuture1.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
            controller.scheduleMigration(
                    pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture2));
            migrateFuture2.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);

            CompletableFuture<Boolean> dataset2IsApplied = new CompletableFuture<>();
            CompletableFuture<Boolean> pendingDatasetIsRemoved = new CompletableFuture<>();
            OperationalDatasetCallback datasetCallback =
                    new OperationalDatasetCallback() {
                        @Override
                        public void onActiveOperationalDatasetChanged(
                                ActiveOperationalDataset activeDataset) {
                            if (activeDataset.equals(activeDataset2)) {
                                dataset2IsApplied.complete(true);
                            }
                        }

                        @Override
                        public void onPendingOperationalDatasetChanged(
                                PendingOperationalDataset pendingDataset) {
                            if (pendingDataset == null) {
                                pendingDatasetIsRemoved.complete(true);
                            }
                        }
                    };
            controller.registerOperationalDatasetCallback(directExecutor(), datasetCallback);
            assertThat(dataset2IsApplied.get(MIGRATION_TIMEOUT_MILLIS, MILLISECONDS)).isTrue();
            assertThat(pendingDatasetIsRemoved.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isTrue();
            controller.unregisterOperationalDatasetCallback(datasetCallback);
        }
    }

    @Test
    public void scheduleMigration_threadDisabled_failsWithErrorThreadDisabled() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
            PendingOperationalDataset pendingDataset =
                    new PendingOperationalDataset(
                            activeDataset,
                            OperationalDatasetTimestamp.fromInstant(Instant.now()),
                            Duration.ofSeconds(30));
            joinRandomizedDatasetAndWait(controller);
            CompletableFuture<Void> migrationFuture = new CompletableFuture<>();

            setEnabledAndWait(controller, false);

            scheduleMigration(controller, pendingDataset, newOutcomeReceiver(migrationFuture));

            ThreadNetworkException thrown =
                    (ThreadNetworkException)
                            assertThrows(ExecutionException.class, migrationFuture::get).getCause();
            assertThat(thrown.getErrorCode()).isEqualTo(ERROR_THREAD_DISABLED);
        }
    }

    @Test
    public void createRandomizedDataset_wrongNetworkNameLength_throwsIllegalArgumentException() {
        for (ThreadNetworkController controller : getAllControllers()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> controller.createRandomizedDataset("", mExecutor, dataset -> {}));

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            controller.createRandomizedDataset(
                                    "ANetNameIs17Bytes", mExecutor, dataset -> {}));
        }
    }

    @Test
    public void createRandomizedDataset_validNetworkName_success() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset dataset = newRandomizedDataset("validName", controller);

            assertThat(dataset.getNetworkName()).isEqualTo("validName");
            assertThat(dataset.getPanId()).isLessThan(0xffff);
            assertThat(dataset.getChannelMask().size()).isAtLeast(1);
            assertThat(dataset.getExtendedPanId()).hasLength(8);
            assertThat(dataset.getNetworkKey()).hasLength(16);
            assertThat(dataset.getPskc()).hasLength(16);
            assertThat(dataset.getMeshLocalPrefix().getPrefixLength()).isEqualTo(64);
            assertThat(dataset.getMeshLocalPrefix().getRawAddress()[0]).isEqualTo((byte) 0xfd);
        }
    }

    @Test
    public void setEnabled_permissionsGranted_succeeds() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<Void> setFuture1 = new CompletableFuture<>();
            CompletableFuture<Void> setFuture2 = new CompletableFuture<>();

            runAsShell(
                    PERMISSION_THREAD_NETWORK_PRIVILEGED,
                    () -> controller.setEnabled(false, mExecutor, newOutcomeReceiver(setFuture1)));
            setFuture1.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
            waitForEnabledState(controller, booleanToEnabledState(false));

            runAsShell(
                    PERMISSION_THREAD_NETWORK_PRIVILEGED,
                    () -> controller.setEnabled(true, mExecutor, newOutcomeReceiver(setFuture2)));
            setFuture2.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
            waitForEnabledState(controller, booleanToEnabledState(true));
        }
    }

    @Test
    public void setEnabled_noPermissions_throwsSecurityException() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            CompletableFuture<Void> setFuture = new CompletableFuture<>();
            assertThrows(
                    SecurityException.class,
                    () -> controller.setEnabled(false, mExecutor, newOutcomeReceiver(setFuture)));
        }
    }

    @Test
    public void setEnabled_disable_leavesThreadNetwork() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            joinRandomizedDatasetAndWait(controller);

            setEnabledAndWait(controller, false);

            runAsShell(
                    ACCESS_NETWORK_STATE,
                    () -> assertThat(getDeviceRole(controller)).isEqualTo(DEVICE_ROLE_STOPPED));
        }
    }

    @Test
    public void setEnabled_toggleAfterJoin_joinsThreadNetworkAgain() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            joinRandomizedDatasetAndWait(controller);

            setEnabledAndWait(controller, false);

            runAsShell(
                    ACCESS_NETWORK_STATE,
                    () -> assertThat(getDeviceRole(controller)).isEqualTo(DEVICE_ROLE_STOPPED));

            setEnabledAndWait(controller, true);

            runAsShell(ACCESS_NETWORK_STATE, () -> waitForAttachedState(controller));
        }
    }

    @Test
    public void setEnabled_enableFollowedByDisable_allSucceed() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            joinRandomizedDatasetAndWait(controller);
            CompletableFuture<Void> setFuture1 = new CompletableFuture<>();
            CompletableFuture<Void> setFuture2 = new CompletableFuture<>();
            EnabledStateListener listener = new EnabledStateListener(controller);
            listener.expectThreadEnabledState(STATE_ENABLED);

            runAsShell(
                    PERMISSION_THREAD_NETWORK_PRIVILEGED,
                    () -> {
                        controller.setEnabled(true, mExecutor, newOutcomeReceiver(setFuture1));
                        controller.setEnabled(false, mExecutor, newOutcomeReceiver(setFuture2));
                    });

            setFuture1.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
            setFuture2.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);

            listener.expectThreadEnabledState(STATE_DISABLING);
            listener.expectThreadEnabledState(STATE_DISABLED);

            runAsShell(
                    ACCESS_NETWORK_STATE,
                    () -> assertThat(getDeviceRole(controller)).isEqualTo(DEVICE_ROLE_STOPPED));

            listener.unregisterStateCallback();
        }
    }
    // TODO (b/322437869): add test case to verify when Thread is in DISABLING state, any commands
    // (join/leave/scheduleMigration/setEnabled) fail with ERROR_BUSY. This is not currently tested
    // because DISABLING has very short lifecycle, it's not possible to guarantee the command can be
    // sent before state changes to DISABLED.

    @Test
    public void threadNetworkCallback_deviceAttached_threadNetworkIsAvailable() throws Exception {
        ThreadNetworkController controller = mManager.getAllThreadNetworkControllers().get(0);
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();
        CompletableFuture<Network> networkFuture = new CompletableFuture<>();
        ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        NetworkRequest networkRequest =
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                        .build();
        ConnectivityManager.NetworkCallback networkCallback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        networkFuture.complete(network);
                    }
                };

        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture)));
        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> cm.registerNetworkCallback(networkRequest, networkCallback));

        joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);
        runAsShell(ACCESS_NETWORK_STATE, () -> assertThat(isAttached(controller)).isTrue());
        assertThat(networkFuture.get(NETWORK_CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isNotNull();
    }
}
