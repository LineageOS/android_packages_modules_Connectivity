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
package android.net.thread.utils;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;
import static android.net.thread.utils.IntegrationTestUtils.CALLBACK_TIMEOUT;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.annotation.Nullable;
import android.content.Context;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.StateCallback;
import android.net.thread.ThreadNetworkException;
import android.net.thread.ThreadNetworkManager;
import android.os.OutcomeReceiver;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** A helper class which provides synchronous API wrappers for {@link ThreadNetworkController}. */
public final class ThreadNetworkControllerWrapper {
    public static final Duration JOIN_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration LEAVE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CALLBACK_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration SET_ENABLED_TIMEOUT = Duration.ofSeconds(2);

    private final ThreadNetworkController mController;

    /**
     * Returns a new {@link ThreadNetworkControllerWrapper} instance or {@code null} if Thread
     * feature is not supported on this device.
     */
    @Nullable
    public static ThreadNetworkControllerWrapper newInstance(Context context) {
        final ThreadNetworkManager manager = context.getSystemService(ThreadNetworkManager.class);
        if (manager == null) {
            return null;
        }
        return new ThreadNetworkControllerWrapper(manager.getAllThreadNetworkControllers().get(0));
    }

    private ThreadNetworkControllerWrapper(ThreadNetworkController controller) {
        mController = controller;
    }

    /**
     * Returns the Thread enabled state.
     *
     * <p>The value can be one of {@code ThreadNetworkController#STATE_*}.
     */
    public final int getEnabledState()
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        StateCallback callback =
                new StateCallback() {
                    @Override
                    public void onThreadEnableStateChanged(int enabledState) {
                        future.complete(enabledState);
                    }

                    @Override
                    public void onDeviceRoleChanged(int deviceRole) {}
                };

        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> mController.registerStateCallback(directExecutor(), callback));
        try {
            return future.get(CALLBACK_TIMEOUT.toSeconds(), SECONDS);
        } finally {
            runAsShell(ACCESS_NETWORK_STATE, () -> mController.unregisterStateCallback(callback));
        }
    }

    /**
     * Returns the Thread device role.
     *
     * <p>The value can be one of {@code ThreadNetworkController#DEVICE_ROLE_*}.
     */
    public final int getDeviceRole()
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        StateCallback callback = future::complete;

        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> mController.registerStateCallback(directExecutor(), callback));
        try {
            return future.get(CALLBACK_TIMEOUT.toSeconds(), SECONDS);
        } finally {
            runAsShell(ACCESS_NETWORK_STATE, () -> mController.unregisterStateCallback(callback));
        }
    }

    /** An synchronous variant of {@link ThreadNetworkController#setEnabled}. */
    public void setEnabledAndWait(boolean enabled)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<Void> future = new CompletableFuture<>();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () ->
                        mController.setEnabled(
                                enabled, directExecutor(), newOutcomeReceiver(future)));
        future.get(SET_ENABLED_TIMEOUT.toSeconds(), SECONDS);
    }

    /** Joins the given network and wait for this device to become attached. */
    public void joinAndWait(ActiveOperationalDataset activeDataset)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<Void> future = new CompletableFuture<>();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () ->
                        mController.join(
                                activeDataset, directExecutor(), newOutcomeReceiver(future)));
        future.get(JOIN_TIMEOUT.toSeconds(), SECONDS);
    }

    /** An synchronous variant of {@link ThreadNetworkController#leave}. */
    public void leaveAndWait() throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<Void> future = new CompletableFuture<>();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> mController.leave(directExecutor(), future::complete));
        future.get(LEAVE_TIMEOUT.toSeconds(), SECONDS);
    }

    /** Waits for the device role to become {@code deviceRole}. */
    public int waitForRole(int deviceRole, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        return waitForRoleAnyOf(List.of(deviceRole), timeout);
    }

    /** Waits for the device role to become one of the values specified in {@code deviceRoles}. */
    public int waitForRoleAnyOf(List<Integer> deviceRoles, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        ThreadNetworkController.StateCallback callback =
                newRole -> {
                    if (deviceRoles.contains(newRole)) {
                        future.complete(newRole);
                    }
                };

        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> mController.registerStateCallback(directExecutor(), callback));

        try {
            return future.get(timeout.toSeconds(), SECONDS);
        } finally {
            mController.unregisterStateCallback(callback);
        }
    }

    /** An synchronous variant of {@link ThreadNetworkController#setTestNetworkAsUpstream}. */
    public void setTestNetworkAsUpstreamAndWait(@Nullable String networkInterfaceName)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<Void> future = new CompletableFuture<>();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                NETWORK_SETTINGS,
                () -> {
                    mController.setTestNetworkAsUpstream(
                            networkInterfaceName, directExecutor(), future::complete);
                });
        future.get(CALLBACK_TIMEOUT.toSeconds(), SECONDS);
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
