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

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;

import static com.android.testutils.TestableNetworkCallback.Event.LINK_PROPERTIES_CHANGED;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.os.Looper;

import com.android.testutils.TestableNetworkAgent;
import com.android.testutils.TestableNetworkCallback;

import java.io.IOException;
import java.time.Duration;

/** A class that can create/destroy a test network based on TAP interface. */
public final class TapTestNetworkTracker {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private final Context mContext;
    private final Looper mLooper;
    private TestNetworkInterface mInterface;
    private TestableNetworkAgent mAgent;
    private Network mNetwork;
    private final TestableNetworkCallback mNetworkCallback;
    private final ConnectivityManager mConnectivityManager;

    /**
     * Constructs a {@link TapTestNetworkTracker}.
     *
     * <p>It creates a TAP interface (e.g. testtap0) and registers a test network using that
     * interface. It also requests the test network by {@link ConnectivityManager#requestNetwork} so
     * the test network won't be automatically turned down by {@link
     * com.android.server.ConnectivityService}.
     */
    public TapTestNetworkTracker(Context context, Looper looper) {
        mContext = context;
        mLooper = looper;
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mNetworkCallback = new TestableNetworkCallback();
        runAsShell(MANAGE_TEST_NETWORKS, this::setUpTestNetwork);
    }

    /** Tears down the test network. */
    public void tearDown() {
        runAsShell(MANAGE_TEST_NETWORKS, this::tearDownTestNetwork);
    }

    /** Returns the interface name of the test network. */
    public String getInterfaceName() {
        return mInterface.getInterfaceName();
    }

    /** Returns the {@link android.net.Network} of the test network. */
    public Network getNetwork() {
        return mNetwork;
    }

    private void setUpTestNetwork() throws Exception {
        mInterface = mContext.getSystemService(TestNetworkManager.class).createTapInterface();

        mConnectivityManager.requestNetwork(
                TestableNetworkAgent.Companion.makeNetworkRequestForInterface(
                        mInterface.getInterfaceName()),
                mNetworkCallback);

        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(getInterfaceName());
        mAgent =
                TestableNetworkAgent.Companion.createOnInterface(
                        mContext, mLooper, mInterface.getInterfaceName(), TIMEOUT.toMillis());

        mNetworkCallback.eventuallyExpect(
                LINK_PROPERTIES_CHANGED,
                TIMEOUT.toMillis(),
                l -> !l.getLp().getAddresses().isEmpty());
    }

    private void tearDownTestNetwork() throws IOException {
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        mAgent.unregister();
        mInterface.getFileDescriptor().close();
        mAgent.waitForIdle(TIMEOUT.toMillis());
    }
}
