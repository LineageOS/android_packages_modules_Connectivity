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

package android.net.thread.cts;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.THREAD_NETWORK_PRIVILEGED;
import static android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO;
import static android.net.NetworkCapabilities.TRANSPORT_THREAD;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_STOPPED;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.net.thread.flags.Flags.FLAG_THREAD_MOBILE_ENABLED;

import static com.google.common.io.BaseEncoding.base16;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.ThreadNetworkSpecifier;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;

import com.android.testutils.TestableNetworkCallback;
import com.android.testutils.TestableNetworkCallback.Event;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for requesting a Thread network via the {@link ConnectivityManager#requestNetwork} and
 * {@link ThreadNetworkSpecifier} API.
 */
@LargeTest
@RequiresThreadFeature
@RequiresFlagsEnabled(FLAG_THREAD_MOBILE_ENABLED)
public final class ThreadNetworkRequestTest {
    private static final int JOIN_TIMEOUT_MILLIS = 30_000;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(2_000);

    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset new":
    // Active Timestamp: 1
    // Channel: 13
    // Wake-up Channel: 18
    // Channel Mask: 0x07fff800
    // Ext PAN ID: 492cc112e634e51b
    // Mesh Local Prefix: fd2f:357c:dd90:2120::/64
    // Network Key: 9f55333f52eb25dbfdbe62121b39bee4
    // Network Name: OpenThread-145a
    // PAN ID: 0x145a
    // PSKc: 6d81fe700160ab9f63f50f78ccf0733c
    // Security Policy: 672 onrc 0
    private static final byte[] DATASET_TLVS_1 =
            base16().ignoreCase()
                    .decode(
                            "0e080000000000010000000300000d4a0300001235060004001fff"
                                + "e00208492cc112e634e51b0708fd2f357cdd90212005109f55333f"
                                + "52eb25dbfdbe62121b39bee4030f4f70656e5468726561642d3134"
                                + "35610102145a04106d81fe700160ab9f63f50f78ccf0733c0c0402a0f7f8");
    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset new":
    // Active Timestamp: 1
    // Channel: 24
    // Wake-up Channel: 19
    // Channel Mask: 0x07fff800
    // Ext PAN ID: 779732128edb4591
    // Mesh Local Prefix: fdbd:3671:821d:18c1::/64
    // Network Key: 6a643ec02d38ef55b21b26cec46f622d
    // Network Name: OpenThread-64be
    // PAN ID: 0x64be
    // PSKc: 37968aab079fac83fa67b14c9bb7f598
    // Security Policy: 672 onrc 0
    private static final byte[] DATASET_TLVS_2 =
            base16().ignoreCase()
                    .decode(
                            "0e08000000000001000000030000184a0300001335060004001fff"
                                + "e00208779732128edb45910708fdbd3671821d18c105106a643ec0"
                                + "2d38ef55b21b26cec46f622d030f4f70656e5468726561642d3634"
                                + "6265010264be041037968aab079fac83fa67b14c9bb7f5980c0402a0f7f8");

    private static final ActiveOperationalDataset TEST_DATASET_1 =
            ActiveOperationalDataset.fromThreadTlvs(DATASET_TLVS_1);
    private static final ActiveOperationalDataset TEST_DATASET_2 =
            ActiveOperationalDataset.fromThreadTlvs(DATASET_TLVS_2);

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ConnectivityManager cm;
    private ThreadNetworkControllerWrapper mController;
    private List<TestableNetworkCallback> mCallbacks;
    private Set<String> mGrantedPermissions;

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

    @Before
    public void setUp() throws Exception {
        mCallbacks = new ArrayList<>();
        mGrantedPermissions = new HashSet<String>();
        cm = mContext.getSystemService(ConnectivityManager.class);
        mController = ThreadNetworkControllerWrapper.newInstance(mContext);
        mController.setBorderRouterEnabledAndWait(false);
        mController.leaveAndWait();
    }

    @After
    public void tearDown() throws Exception {
        dropAllPermissions();
        for (var callback : mCallbacks) {
            cm.unregisterNetworkCallback(callback);
        }
        mController.waitForRole(DEVICE_ROLE_STOPPED, STOP_TIMEOUT);
    }

    private TestableNetworkCallback newNetworkCallback() {
        var callback =
                new TestableNetworkCallback(
                        250L /* timeoutMs */,
                        250L /* noCallbackTimeoutMs */,
                        () -> {} /* waiterFunc */,
                        "ThreadNetworkRequestTest" /* logTag */,
                        FLAG_INCLUDE_LOCATION_INFO);
        mCallbacks.add(callback);
        return callback;
    }

    private NetworkRequest newThreadNetworkRequest(
            ActiveOperationalDataset dataset, boolean createPartitionIfNotFound) {
        var specifier =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(dataset)
                        .setShouldCreatePartitionIfNotFound(createPartitionIfNotFound)
                        .build();
        return new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_THREAD)
                .setNetworkSpecifier(specifier)
                .build();
    }

    @Test
    public void requestNetwork_withoutThreadPrivilegedPermission_failsWithUnavailable() {
        dropAllPermissions();
        var request = newThreadNetworkRequest(TEST_DATASET_1, true /* createPartitionIfNotFound */);
        var callback = newNetworkCallback();

        assertThrows(SecurityException.class, () -> cm.requestNetwork(request, callback));
        mCallbacks.remove(callback);
    }

    @Test
    public void requestNetwork_withCreatePartitionEnabled_succeedsWithAvailable() {
        grantPermissions(THREAD_NETWORK_PRIVILEGED, ACCESS_FINE_LOCATION);
        var request = newThreadNetworkRequest(TEST_DATASET_1, true /* createPartitionIfNotFound */);

        var callback = newNetworkCallback();
        cm.requestNetwork(request, callback);

        callback.eventuallyExpect(Event.AVAILABLE, JOIN_TIMEOUT_MILLIS);
    }

    @Test
    public void requestNetwork_multipleRequests_theLastOneWins() {
        grantPermissions(THREAD_NETWORK_PRIVILEGED, ACCESS_FINE_LOCATION);
        var request1 =
                newThreadNetworkRequest(TEST_DATASET_1, true /* createPartitionIfNotFound */);
        var request2 =
                newThreadNetworkRequest(TEST_DATASET_2, true /* createPartitionIfNotFound */);

        var callback1 = newNetworkCallback();
        var callback2 = newNetworkCallback();
        cm.requestNetwork(request1, callback1);
        cm.requestNetwork(request2, callback2);

        callback2.eventuallyExpect(Event.AVAILABLE, JOIN_TIMEOUT_MILLIS);
    }

    @Test
    public void unregisterNetworkCallback_threadIsStopped() throws Exception {
        grantPermissions(THREAD_NETWORK_PRIVILEGED, ACCESS_FINE_LOCATION);
        var request = newThreadNetworkRequest(TEST_DATASET_1, true /* createPartitionIfNotFound */);
        var callback = newNetworkCallback();
        cm.requestNetwork(request, callback);
        callback.eventuallyExpect(Event.AVAILABLE, JOIN_TIMEOUT_MILLIS);

        cm.unregisterNetworkCallback(callback);
        mCallbacks.remove(callback);

        dropAllPermissions();
        mController.waitForRole(DEVICE_ROLE_STOPPED, STOP_TIMEOUT);
    }
}
