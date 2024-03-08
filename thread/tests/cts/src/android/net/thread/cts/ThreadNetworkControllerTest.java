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

import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_STOPPED;
import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;
import static android.net.thread.ThreadNetworkException.ERROR_ABORTED;
import static android.net.thread.ThreadNetworkException.ERROR_FAILED_PRECONDITION;
import static android.net.thread.ThreadNetworkException.ERROR_REJECTED_BY_PEER;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeNotNull;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.Manifest.permission;
import android.content.Context;
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

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import com.google.common.util.concurrent.SettableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** CTS tests for {@link ThreadNetworkController}. */
@LargeTest
@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU) // Thread is available on only U+
public class ThreadNetworkControllerTest {
    private static final int CALLBACK_TIMEOUT_MILLIS = 1000;
    private static final String PERMISSION_THREAD_NETWORK_PRIVILEGED =
            "android.permission.THREAD_NETWORK_PRIVILEGED";

    @Rule public DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ExecutorService mExecutor;
    private ThreadNetworkManager mManager;

    private Set<String> mGrantedPermissions;

    @Before
    public void setUp() {
        mExecutor = Executors.newSingleThreadExecutor();
        mManager = mContext.getSystemService(ThreadNetworkManager.class);
        mGrantedPermissions = new HashSet<String>();

        // TODO: we will also need it in tearDown(), it's better to have a Rule to skip
        // tests if a feature is not available.
        assumeNotNull(mManager);
    }

    @After
    public void tearDown() throws Exception {
        if (mManager != null) {
            leaveAndWait();
            dropAllPermissions();
        }
    }

    private List<ThreadNetworkController> getAllControllers() {
        return mManager.getAllThreadNetworkControllers();
    }

    private void leaveAndWait() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Void> future = SettableFuture.create();
            controller.leave(mExecutor, future::set);
            future.get();
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
        SettableFuture<ActiveOperationalDataset> future = SettableFuture.create();
        controller.createRandomizedDataset(networkName, directExecutor(), future::set);
        return future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private static boolean isAttached(ThreadNetworkController controller) throws Exception {
        return ThreadNetworkController.isAttached(getDeviceRole(controller));
    }

    private static int getDeviceRole(ThreadNetworkController controller) throws Exception {
        SettableFuture<Integer> future = SettableFuture.create();
        StateCallback callback = future::set;
        controller.registerStateCallback(directExecutor(), callback);
        int role = future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
        controller.unregisterStateCallback(callback);
        return role;
    }

    private static int waitForStateAnyOf(
            ThreadNetworkController controller, List<Integer> deviceRoles) throws Exception {
        SettableFuture<Integer> future = SettableFuture.create();
        StateCallback callback =
                newRole -> {
                    if (deviceRoles.contains(newRole)) {
                        future.set(newRole);
                    }
                };
        controller.registerStateCallback(directExecutor(), callback);
        int role = future.get();
        controller.unregisterStateCallback(callback);
        return role;
    }

    private static ActiveOperationalDataset getActiveOperationalDataset(
            ThreadNetworkController controller) throws Exception {
        SettableFuture<ActiveOperationalDataset> future = SettableFuture.create();
        OperationalDatasetCallback callback = future::set;
        controller.registerOperationalDatasetCallback(directExecutor(), callback);
        ActiveOperationalDataset dataset = future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
        controller.unregisterOperationalDatasetCallback(callback);
        return dataset;
    }

    private static PendingOperationalDataset getPendingOperationalDataset(
            ThreadNetworkController controller) throws Exception {
        SettableFuture<ActiveOperationalDataset> activeFuture = SettableFuture.create();
        SettableFuture<PendingOperationalDataset> pendingFuture = SettableFuture.create();
        controller.registerOperationalDatasetCallback(
                directExecutor(), newDatasetCallback(activeFuture, pendingFuture));
        return pendingFuture.get();
    }

    private static OperationalDatasetCallback newDatasetCallback(
            SettableFuture<ActiveOperationalDataset> activeFuture,
            SettableFuture<PendingOperationalDataset> pendingFuture) {
        return new OperationalDatasetCallback() {
            @Override
            public void onActiveOperationalDatasetChanged(
                    ActiveOperationalDataset activeOpDataset) {
                activeFuture.set(activeOpDataset);
            }

            @Override
            public void onPendingOperationalDatasetChanged(
                    PendingOperationalDataset pendingOpDataset) {
                pendingFuture.set(pendingOpDataset);
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
        grantPermissions(permission.ACCESS_NETWORK_STATE);

        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();
            StateCallback callback = deviceRole::set;

            try {
                controller.registerStateCallback(mExecutor, callback);

                assertThat(deviceRole.get()).isEqualTo(DEVICE_ROLE_STOPPED);
            } finally {
                controller.unregisterStateCallback(callback);
            }
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
        grantPermissions(permission.ACCESS_NETWORK_STATE);

        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();
            StateCallback callback = role -> deviceRole.set(role);
            controller.registerStateCallback(mExecutor, callback);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> controller.registerStateCallback(mExecutor, callback));
        }
    }

    @Test
    public void unregisterStateCallback_noPermissions_throwsSecurityException() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();
            StateCallback callback = role -> deviceRole.set(role);
            grantPermissions(permission.ACCESS_NETWORK_STATE);
            controller.registerStateCallback(mExecutor, callback);

            try {
                dropAllPermissions();
                assertThrows(
                        SecurityException.class,
                        () -> controller.unregisterStateCallback(callback));
            } finally {
                grantPermissions(permission.ACCESS_NETWORK_STATE);
                controller.unregisterStateCallback(callback);
            }
        }
    }

    @Test
    public void unregisterStateCallback_callbackRegistered_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE);
        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();
            StateCallback callback = role -> deviceRole.set(role);
            controller.registerStateCallback(mExecutor, callback);

            controller.unregisterStateCallback(callback);
        }
    }

    @Test
    public void unregisterStateCallback_callbackNotRegistered_throwsIllegalArgumentException()
            throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();
            StateCallback callback = role -> deviceRole.set(role);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> controller.unregisterStateCallback(callback));
        }
    }

    @Test
    public void unregisterStateCallback_alreadyUnregistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE);
        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();
            StateCallback callback = deviceRole::set;
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
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<ActiveOperationalDataset> activeFuture = SettableFuture.create();
            SettableFuture<PendingOperationalDataset> pendingFuture = SettableFuture.create();
            var callback = newDatasetCallback(activeFuture, pendingFuture);

            try {
                controller.registerOperationalDatasetCallback(mExecutor, callback);

                assertThat(activeFuture.get()).isNull();
                assertThat(pendingFuture.get()).isNull();
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
            SettableFuture<ActiveOperationalDataset> activeFuture = SettableFuture.create();
            SettableFuture<PendingOperationalDataset> pendingFuture = SettableFuture.create();
            var callback = newDatasetCallback(activeFuture, pendingFuture);

            assertThrows(
                    SecurityException.class,
                    () -> controller.registerOperationalDatasetCallback(mExecutor, callback));
        }
    }

    @Test
    public void unregisterOperationalDatasetCallback_callbackRegistered_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<ActiveOperationalDataset> activeFuture = SettableFuture.create();
            SettableFuture<PendingOperationalDataset> pendingFuture = SettableFuture.create();
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
            SettableFuture<ActiveOperationalDataset> activeFuture = SettableFuture.create();
            SettableFuture<PendingOperationalDataset> pendingFuture = SettableFuture.create();
            var callback = newDatasetCallback(activeFuture, pendingFuture);
            grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
            controller.registerOperationalDatasetCallback(mExecutor, callback);

            try {
                dropAllPermissions();
                assertThrows(
                        SecurityException.class,
                        () -> controller.unregisterOperationalDatasetCallback(callback));
            } finally {
                grantPermissions(
                        permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
                controller.unregisterOperationalDatasetCallback(callback);
            }
        }
    }

    private static <V> OutcomeReceiver<V, ThreadNetworkException> newOutcomeReceiver(
            SettableFuture<V> future) {
        return new OutcomeReceiver<V, ThreadNetworkException>() {
            @Override
            public void onResult(V result) {
                future.set(result);
            }

            @Override
            public void onError(ThreadNetworkException e) {
                future.setException(e);
            }
        };
    }

    @Test
    public void join_withPrivilegedPermission_success() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
            SettableFuture<Void> joinFuture = SettableFuture.create();

            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get();

            grantPermissions(permission.ACCESS_NETWORK_STATE);
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
            SettableFuture<Void> joinFuture1 = SettableFuture.create();
            SettableFuture<Void> joinFuture2 = SettableFuture.create();

            controller.join(activeDataset1, mExecutor, newOutcomeReceiver(joinFuture1));
            controller.join(activeDataset2, mExecutor, newOutcomeReceiver(joinFuture2));

            ThreadNetworkException thrown =
                    (ThreadNetworkException)
                            assertThrows(ExecutionException.class, joinFuture1::get).getCause();
            assertThat(thrown.getErrorCode()).isEqualTo(ERROR_ABORTED);
            joinFuture2.get();
            grantPermissions(permission.ACCESS_NETWORK_STATE);
            assertThat(isAttached(controller)).isTrue();
            assertThat(getActiveOperationalDataset(controller)).isEqualTo(activeDataset2);
        }
    }

    @Test
    public void leave_withPrivilegedPermission_success() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
            SettableFuture<Void> joinFuture = SettableFuture.create();
            SettableFuture<Void> leaveFuture = SettableFuture.create();
            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get();

            controller.leave(mExecutor, newOutcomeReceiver(leaveFuture));
            leaveFuture.get();

            grantPermissions(permission.ACCESS_NETWORK_STATE);
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
    public void leave_concurrentRequests_bothSuccess() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
            SettableFuture<Void> joinFuture = SettableFuture.create();
            SettableFuture<Void> leaveFuture1 = SettableFuture.create();
            SettableFuture<Void> leaveFuture2 = SettableFuture.create();
            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get();

            controller.leave(mExecutor, newOutcomeReceiver(leaveFuture1));
            controller.leave(mExecutor, newOutcomeReceiver(leaveFuture2));

            leaveFuture1.get();
            leaveFuture2.get();
            grantPermissions(permission.ACCESS_NETWORK_STATE);
            assertThat(getDeviceRole(controller)).isEqualTo(DEVICE_ROLE_STOPPED);
        }
    }

    @Test
    public void scheduleMigration_withPrivilegedPermission_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

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
            SettableFuture<Void> joinFuture = SettableFuture.create();
            SettableFuture<Void> migrateFuture = SettableFuture.create();
            controller.join(activeDataset1, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get();

            controller.scheduleMigration(
                    pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture));

            migrateFuture.get();
            Thread.sleep(35 * 1000);
            assertThat(getActiveOperationalDataset(controller)).isEqualTo(activeDataset2);
            assertThat(getPendingOperationalDataset(controller)).isNull();
        }
    }

    @Test
    public void scheduleMigration_whenNotAttached_failWithPreconditionError() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            PendingOperationalDataset pendingDataset =
                    new PendingOperationalDataset(
                            newRandomizedDataset("TestNet", controller),
                            OperationalDatasetTimestamp.fromInstant(Instant.now()),
                            Duration.ofSeconds(30));
            SettableFuture<Void> migrateFuture = SettableFuture.create();

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
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

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
            SettableFuture<Void> joinFuture = SettableFuture.create();
            SettableFuture<Void> migrateFuture1 = SettableFuture.create();
            SettableFuture<Void> migrateFuture2 = SettableFuture.create();
            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get();

            controller.scheduleMigration(
                    pendingDataset1, mExecutor, newOutcomeReceiver(migrateFuture1));
            migrateFuture1.get();
            controller.scheduleMigration(
                    pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture2));

            ThreadNetworkException thrown =
                    (ThreadNetworkException)
                            assertThrows(ExecutionException.class, migrateFuture2::get).getCause();
            assertThat(thrown.getErrorCode()).isEqualTo(ERROR_REJECTED_BY_PEER);
        }
    }

    @Test
    public void scheduleMigration_secondRequestHasLargerTimestamp_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

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
            SettableFuture<Void> joinFuture = SettableFuture.create();
            SettableFuture<Void> migrateFuture1 = SettableFuture.create();
            SettableFuture<Void> migrateFuture2 = SettableFuture.create();
            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get();

            controller.scheduleMigration(
                    pendingDataset1, mExecutor, newOutcomeReceiver(migrateFuture1));
            migrateFuture1.get();
            controller.scheduleMigration(
                    pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture2));

            migrateFuture2.get();
            Thread.sleep(35 * 1000);
            assertThat(getActiveOperationalDataset(controller)).isEqualTo(activeDataset2);
            assertThat(getPendingOperationalDataset(controller)).isNull();
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
}
