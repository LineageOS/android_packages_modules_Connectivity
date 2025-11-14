/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.net.cts;

import static android.app.usage.NetworkStats.Bucket.DEFAULT_NETWORK_ALL;
import static android.app.usage.NetworkStats.Bucket.DEFAULT_NETWORK_NO;
import static android.app.usage.NetworkStats.Bucket.DEFAULT_NETWORK_YES;
import static android.app.usage.NetworkStats.Bucket.METERED_ALL;
import static android.app.usage.NetworkStats.Bucket.METERED_NO;
import static android.app.usage.NetworkStats.Bucket.METERED_YES;
import static android.app.usage.NetworkStats.Bucket.ROAMING_ALL;
import static android.app.usage.NetworkStats.Bucket.ROAMING_NO;
import static android.app.usage.NetworkStats.Bucket.ROAMING_YES;
import static android.app.usage.NetworkStats.Bucket.STATE_ALL;
import static android.app.usage.NetworkStats.Bucket.STATE_DEFAULT;
import static android.app.usage.NetworkStats.Bucket.STATE_FOREGROUND;
import static android.app.usage.NetworkStats.Bucket.TAG_NONE;
import static android.app.usage.NetworkStats.Bucket.UID_ALL;
import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_UID;
import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_UID_TAG;
import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_XT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkStatsCollection;
import android.net.NetworkStatsHistory;
import android.net.TrafficStats;
import android.net.netstats.NetworkStatsDataMigrationUtils;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.platform.test.annotations.AppModeFull;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.ShellIdentityUtils;
import com.android.compatibility.common.util.SystemUtil;
import com.android.modules.utils.build.SdkLevel;
import com.android.testutils.AutoReleaseNetworkCallbackRule;
import com.android.testutils.ConnectivityDiagnosticsCollector;
import com.android.testutils.ConnectivityModuleTest;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.TestableNetworkCallback;
import com.android.testutils.TestableNetworkCallback.Event;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// TODO: Fix thread leaks in testCallback and annotating with @MonitorThreadLeak.
@AppModeFull(reason = "instant apps cannot be granted USAGE_STATS")
@ConnectivityModuleTest
@DevSdkIgnoreRunner.RestoreDefaultNetwork
@RunWith(DevSdkIgnoreRunner.class)
public class NetworkStatsManagerTest {
    @Rule(order = 1)
    public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule(Build.VERSION_CODES.Q);
    @Rule(order = 2)
    public final AutoReleaseNetworkCallbackRule
            networkCallbackRule = new AutoReleaseNetworkCallbackRule();


    private static final String LOG_TAG = "NetworkStatsManagerTest";
    private static final String APPOPS_SET_SHELL_COMMAND = "appops set --user {0} {1} {2} {3}";
    private static final String APPOPS_GET_SHELL_COMMAND = "appops get --user {0} {1} {2}";

    private static final long MINUTE = 1000 * 60;
    private static final int TIMEOUT_MILLIS = 15000;

    private static final String CHECK_CONNECTIVITY_URL = "http://www.265.com/";
    private static final int HOST_RESOLUTION_RETRIES = 4;
    private static final int HOST_RESOLUTION_INTERVAL_MS = 500;

    private static final int NETWORK_TAG = 0xf00d;
    private static final long THRESHOLD_BYTES = 2 * 1024 * 1024;  // 2 MB
    private static final long SHORT_TOLERANCE = MINUTE / 2;
    private static final long LONG_TOLERANCE = MINUTE * 120;

    private abstract class NetworkInterfaceToTest {

        final TestableNetworkCallback mRequestNetworkCb = new TestableNetworkCallback();
        private boolean mMetered;
        private boolean mRoaming;
        private boolean mIsDefault;

        abstract int getNetworkType();

        abstract Network requestNetwork();

        void unrequestNetwork() {
            networkCallbackRule.unregisterNetworkCallback(mRequestNetworkCb);
        }

        public boolean getMetered() {
            return mMetered;
        }

        public void setMetered(boolean metered) {
            this.mMetered = metered;
        }

        public boolean getRoaming() {
            return mRoaming;
        }

        public void setRoaming(boolean roaming) {
            this.mRoaming = roaming;
        }

        public boolean getIsDefault() {
            return mIsDefault;
        }

        public void setIsDefault(boolean isDefault) {
            mIsDefault = isDefault;
        }

        abstract String getSystemFeature();

        @NonNull NetworkRequest buildRequestForTransport(int transport) {
            return new NetworkRequest.Builder()
                    .addTransportType(transport)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
        }
    }

    private final NetworkInterfaceToTest[] mNetworkInterfacesToTest =
            new NetworkInterfaceToTest[] {
                    new NetworkInterfaceToTest() {
                        @Override
                        public int getNetworkType() {
                            return ConnectivityManager.TYPE_WIFI;
                        }

                        @Override
                        public Network requestNetwork() {
                            networkCallbackRule.requestNetwork(buildRequestForTransport(
                                    NetworkCapabilities.TRANSPORT_WIFI),
                                    mRequestNetworkCb, TIMEOUT_MILLIS);
                            return mRequestNetworkCb.expect(Event.AVAILABLE,
                                    "Wifi network not available. "
                                            + "Please ensure the device has working wifi."
                            ).getNetwork();
                        }

                        @Override
                        public String getSystemFeature() {
                            return PackageManager.FEATURE_WIFI;
                        }
                    },
                    new NetworkInterfaceToTest() {
                        @Override
                        public int getNetworkType() {
                            return ConnectivityManager.TYPE_MOBILE;
                        }

                        @Override
                        public Network requestNetwork() {
                            networkCallbackRule.requestNetwork(buildRequestForTransport(
                                            NetworkCapabilities.TRANSPORT_CELLULAR),
                                    mRequestNetworkCb, TIMEOUT_MILLIS);
                            return mRequestNetworkCb.expect(Event.AVAILABLE,
                                    "Cell network not available. "
                                            + "Please ensure the device has working mobile data."
                            ).getNetwork();
                        }

                        @Override
                        public String getSystemFeature() {
                            return PackageManager.FEATURE_TELEPHONY;
                        }
                    }
            };

    private String mPkg;
    private Context mContext;
    private NetworkStatsManager mNsm;
    private ConnectivityManager mCm;
    private PackageManager mPm;
    private Instrumentation mInstrumentation;
    private long mStartTime;
    private long mEndTime;

    private String mWriteSettingsMode;
    private String mUsageStatsMode;

    // The test host only has IPv4. So on a dual-stack network where IPv6 connects before IPv4,
    // we need to wait until IPv4 is available or the test will spuriously fail.
    private static void waitForHostResolution(@NonNull Network network, @NonNull URL url) {
        for (int i = 0; i < HOST_RESOLUTION_RETRIES; i++) {
            try {
                network.getAllByName(url.getHost());
                return;
            } catch (UnknownHostException e) {
                SystemClock.sleep(HOST_RESOLUTION_INTERVAL_MS);
            }
        }
        fail(String.format("%s could not be resolved on network %s (%d attempts %dms apart)",
                url.getHost(), network, HOST_RESOLUTION_RETRIES, HOST_RESOLUTION_INTERVAL_MS));
    }

    private void exerciseRemoteHost(@NonNull Network network, @NonNull URL url) throws Exception {
        NetworkInfo networkInfo = mCm.getNetworkInfo(network);
        if (networkInfo == null) {
            Log.w(LOG_TAG, "Network info is null");
        } else {
            Log.w(LOG_TAG, "Network: " + networkInfo.toString());
        }
        BufferedInputStream in = null;
        HttpURLConnection urlc = null;
        String originalKeepAlive = System.getProperty("http.keepAlive");
        System.setProperty("http.keepAlive", "false");
        try {
            TrafficStats.setThreadStatsTag(NETWORK_TAG);
            urlc = (HttpURLConnection) network.openConnection(url);
            urlc.setConnectTimeout(TIMEOUT_MILLIS);
            urlc.setReadTimeout(TIMEOUT_MILLIS);
            urlc.setUseCaches(false);
            // Disable compression so we generate enough traffic that assertWithinPercentage will
            // not be affected by the small amount of traffic (5-10kB) sent by the test harness.
            urlc.setRequestProperty("Accept-Encoding", "identity");
            urlc.connect();
            boolean ping = urlc.getResponseCode() == 200;
            if (ping) {
                in = new BufferedInputStream((InputStream) urlc.getContent());
                while (in.read() != -1) {
                    // Comments to suppress lint error.
                }
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Badness during exercising remote server: " + e);
        } finally {
            TrafficStats.clearThreadStatsTag();
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // don't care
                }
            }
            if (urlc != null) {
                urlc.disconnect();
            }
            if (originalKeepAlive == null) {
                System.clearProperty("http.keepAlive");
            } else {
                System.setProperty("http.keepAlive", originalKeepAlive);
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mNsm = mContext.getSystemService(NetworkStatsManager.class);
        mNsm.setPollForce(true);

        mCm = mContext.getSystemService(ConnectivityManager.class);
        mPm = mContext.getPackageManager();
        mPkg = mContext.getPackageName();

        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mWriteSettingsMode = getAppOpsMode(AppOpsManager.OPSTR_WRITE_SETTINGS);
        setAppOpsMode(AppOpsManager.OPSTR_WRITE_SETTINGS, "allow");
        mUsageStatsMode = getAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS);
    }

    @After
    public void tearDown() throws Exception {
        if (mWriteSettingsMode != null) {
            setAppOpsMode(AppOpsManager.OPSTR_WRITE_SETTINGS, mWriteSettingsMode);
        }
        if (mUsageStatsMode != null) {
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, mUsageStatsMode);
        }
    }

    private void setAppOpsMode(String appop, String mode) throws Exception {
        final String command = MessageFormat.format(APPOPS_SET_SHELL_COMMAND,
                UserHandle.myUserId(), mPkg, appop, mode);
        SystemUtil.runShellCommand(mInstrumentation, command);
    }

    private String getAppOpsMode(String appop) throws Exception {
        final String command = MessageFormat.format(APPOPS_GET_SHELL_COMMAND,
                UserHandle.myUserId(), mPkg, appop);
        String result = SystemUtil.runShellCommand(mInstrumentation, command);
        if (result == null) {
            Log.w(LOG_TAG, "App op " + appop + " could not be read.");
        }
        return result;
    }

    private boolean isInForeground() throws IOException {
        String result = SystemUtil.runShellCommand(mInstrumentation,
                "cmd activity get-uid-state " + Process.myUid());
        return result.contains("FOREGROUND");
    }

    private boolean shouldTestThisNetworkType(int networkTypeIndex) {
        return mPm.hasSystemFeature(mNetworkInterfacesToTest[networkTypeIndex].getSystemFeature());
    }

    @NonNull
    private Network requestNetworkAndSetAttributes(
            @NonNull NetworkInterfaceToTest networkInterface) {
        final Network network = networkInterface.requestNetwork();

        // These attributes are needed when performing NetworkStats queries.
        // Fetch caps from the first capabilities changed event since the
        // interested attributes are not mutable, and not expected to be
        // changed during the test.
        final NetworkCapabilities caps = networkInterface.mRequestNetworkCb.expect(
                Event.NETWORK_CAPS_UPDATED, network).getCaps();
        networkInterface.setMetered(!caps.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        networkInterface.setRoaming(!caps.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING));
        networkInterface.setIsDefault(network.equals(mCm.getActiveNetwork()));

        return network;
    }

    private void requestNetworkAndGenerateTraffic(int networkTypeIndex, final long tolerance)
            throws Exception {
        final NetworkInterfaceToTest networkInterface = mNetworkInterfacesToTest[networkTypeIndex];
        final Network network = requestNetworkAndSetAttributes(networkInterface);

        mStartTime = System.currentTimeMillis() - tolerance;
        waitForHostResolution(network, new URL(CHECK_CONNECTIVITY_URL));
        exerciseRemoteHost(network, new URL(CHECK_CONNECTIVITY_URL));
        mEndTime = System.currentTimeMillis() + tolerance;

        // It is fine if the test fails and this line is not reached.
        // The AutoReleaseNetworkCallbackRule will eventually release
        // all unwanted callbacks.
        networkInterface.unrequestNetwork();
    }

    private String getSubscriberId(int networkIndex) {
        int networkType = mNetworkInterfacesToTest[networkIndex].getNetworkType();
        if (ConnectivityManager.TYPE_MOBILE == networkType) {
            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            return ShellIdentityUtils.invokeMethodWithShellPermissions(tm,
                    (telephonyManager) -> telephonyManager.getSubscriberId());
        }
        return "";
    }

    @Test
    public void testDeviceSummary() throws Exception {
        for (int i = 0; i < mNetworkInterfacesToTest.length; ++i) {
            if (!shouldTestThisNetworkType(i)) {
                continue;
            }
            requestNetworkAndGenerateTraffic(i, SHORT_TOLERANCE);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats.Bucket bucket = null;
            try {
                bucket = mNsm.querySummaryForDevice(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime);
            } catch (RemoteException | SecurityException e) {
                fail("testDeviceSummary fails with exception: " + e.toString());
            }
            assertNotNull(bucket);
            assertTimestamps(bucket);
            assertEquals(bucket.getState(), STATE_ALL);
            assertEquals(bucket.getUid(), UID_ALL);
            assertEquals(bucket.getMetered(), METERED_ALL);
            assertEquals(bucket.getRoaming(), ROAMING_ALL);
            assertEquals(bucket.getDefaultNetworkStatus(), DEFAULT_NETWORK_ALL);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                bucket = mNsm.querySummaryForDevice(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime);
                fail("negative testDeviceSummary fails: no exception thrown.");
            } catch (RemoteException e) {
                fail("testDeviceSummary fails with exception: " + e.toString());
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    @Test
    public void testUserSummary() throws Exception {
        for (int i = 0; i < mNetworkInterfacesToTest.length; ++i) {
            if (!shouldTestThisNetworkType(i)) {
                continue;
            }
            requestNetworkAndGenerateTraffic(i, SHORT_TOLERANCE);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats.Bucket bucket = null;
            try {
                bucket = mNsm.querySummaryForUser(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime);
            } catch (RemoteException | SecurityException e) {
                fail("testUserSummary fails with exception: " + e.toString());
            }
            assertNotNull(bucket);
            assertTimestamps(bucket);
            assertEquals(bucket.getState(), STATE_ALL);
            assertEquals(bucket.getUid(), UID_ALL);
            assertEquals(bucket.getMetered(), METERED_ALL);
            assertEquals(bucket.getRoaming(), ROAMING_ALL);
            assertEquals(bucket.getDefaultNetworkStatus(), DEFAULT_NETWORK_ALL);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                bucket = mNsm.querySummaryForUser(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime);
                fail("negative testUserSummary fails: no exception thrown.");
            } catch (RemoteException e) {
                fail("testUserSummary fails with exception: " + e.toString());
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    @Test
    public void testAppSummary() throws Exception {
        for (int i = 0; i < mNetworkInterfacesToTest.length; ++i) {
            if (!shouldTestThisNetworkType(i)) {
                continue;
            }
            // Use tolerance value that large enough to make sure stats of at
            // least one bucket is included. However, this is possible that
            // the test will see data of different app but with the same UID
            // that created before testing.
            // TODO: Consider query stats before testing and use the difference to verify.
            requestNetworkAndGenerateTraffic(i, LONG_TOLERANCE);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats result = null;
            try {
                result = mNsm.querySummary(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime);
                assertNotNull(result);
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                long totalTxPackets = 0;
                long totalRxPackets = 0;
                long totalTxBytes = 0;
                long totalRxBytes = 0;
                boolean hasCorrectMetering = false;
                boolean hasCorrectRoaming = false;
                boolean hasCorrectDefaultStatus = false;
                int expectedMetering = mNetworkInterfacesToTest[i].getMetered()
                        ? METERED_YES : METERED_NO;
                int expectedRoaming = mNetworkInterfacesToTest[i].getRoaming()
                        ? ROAMING_YES : ROAMING_NO;
                int expectedDefaultStatus = mNetworkInterfacesToTest[i].getIsDefault()
                        ? DEFAULT_NETWORK_YES : DEFAULT_NETWORK_NO;
                while (result.hasNextBucket()) {
                    assertTrue(result.getNextBucket(bucket));
                    assertTimestamps(bucket);
                    hasCorrectMetering |= bucket.getMetered() == expectedMetering;
                    hasCorrectRoaming |= bucket.getRoaming() == expectedRoaming;
                    if (bucket.getUid() == Process.myUid()) {
                        totalTxPackets += bucket.getTxPackets();
                        totalRxPackets += bucket.getRxPackets();
                        totalTxBytes += bucket.getTxBytes();
                        totalRxBytes += bucket.getRxBytes();
                        hasCorrectDefaultStatus |=
                                bucket.getDefaultNetworkStatus() == expectedDefaultStatus;
                    }
                }
                assertFalse(result.getNextBucket(bucket));
                assertTrue("Incorrect metering for NetworkType: "
                        + mNetworkInterfacesToTest[i].getNetworkType(), hasCorrectMetering);
                assertTrue("Incorrect roaming for NetworkType: "
                        + mNetworkInterfacesToTest[i].getNetworkType(), hasCorrectRoaming);
                assertTrue("Incorrect isDefault for NetworkType: "
                        + mNetworkInterfacesToTest[i].getNetworkType(), hasCorrectDefaultStatus);
                assertTrue("No Rx bytes usage for uid " + Process.myUid(), totalRxBytes > 0);
                assertTrue("No Rx packets usage for uid " + Process.myUid(), totalRxPackets > 0);
                assertTrue("No Tx bytes usage for uid " + Process.myUid(), totalTxBytes > 0);
                assertTrue("No Tx packets usage for uid " + Process.myUid(), totalTxPackets > 0);
            } finally {
                if (result != null) {
                    result.close();
                }
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                result = mNsm.querySummary(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime);
                fail("negative testAppSummary fails: no exception thrown.");
            } catch (RemoteException e) {
                fail("testAppSummary fails with exception: " + e.toString());
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    @Test
    public void testAppDetails() throws Exception {
        for (int i = 0; i < mNetworkInterfacesToTest.length; ++i) {
            if (!shouldTestThisNetworkType(i)) {
                continue;
            }
            // Relatively large tolerance to accommodate for history bucket size.
            requestNetworkAndGenerateTraffic(i, LONG_TOLERANCE);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats result = null;
            try {
                result = mNsm.queryDetails(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime);
                long totalBytesWithSubscriberId = getTotalAndAssertNotEmpty(result);

                // Test without filtering by subscriberId
                result = mNsm.queryDetails(
                        mNetworkInterfacesToTest[i].getNetworkType(), null,
                        mStartTime, mEndTime);

                assertTrue("More bytes with subscriberId filter than without.",
                        getTotalAndAssertNotEmpty(result) >= totalBytesWithSubscriberId);
            } catch (RemoteException | SecurityException e) {
                fail("testAppDetails fails with exception: " + e.toString());
            } finally {
                if (result != null) {
                    result.close();
                }
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                result = mNsm.queryDetails(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime);
                fail("negative testAppDetails fails: no exception thrown.");
            } catch (RemoteException e) {
                fail("testAppDetails fails with exception: " + e.toString());
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    @Test
    public void testUidDetails() throws Exception {
        for (int i = 0; i < mNetworkInterfacesToTest.length; ++i) {
            // Relatively large tolerance to accommodate for history bucket size.
            if (!shouldTestThisNetworkType(i)) {
                continue;
            }
            requestNetworkAndGenerateTraffic(i, LONG_TOLERANCE);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats result = null;
            try {
                result = mNsm.queryDetailsForUid(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime, Process.myUid());
                assertNotNull(result);
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                long totalTxPackets = 0;
                long totalRxPackets = 0;
                long totalTxBytes = 0;
                long totalRxBytes = 0;
                while (result.hasNextBucket()) {
                    assertTrue(result.getNextBucket(bucket));
                    assertTimestamps(bucket);
                    assertEquals(bucket.getState(), STATE_ALL);
                    assertEquals(bucket.getMetered(), METERED_ALL);
                    assertEquals(bucket.getRoaming(), ROAMING_ALL);
                    assertEquals(bucket.getDefaultNetworkStatus(), DEFAULT_NETWORK_ALL);
                    assertEquals(bucket.getUid(), Process.myUid());
                    totalTxPackets += bucket.getTxPackets();
                    totalRxPackets += bucket.getRxPackets();
                    totalTxBytes += bucket.getTxBytes();
                    totalRxBytes += bucket.getRxBytes();
                }
                assertFalse(result.getNextBucket(bucket));
                assertTrue("No Rx bytes usage for uid " + Process.myUid(), totalRxBytes > 0);
                assertTrue("No Rx packets usage for uid " + Process.myUid(), totalRxPackets > 0);
                assertTrue("No Tx bytes usage for uid " + Process.myUid(), totalTxBytes > 0);
                assertTrue("No Tx packets usage for uid " + Process.myUid(), totalTxPackets > 0);
            } finally {
                if (result != null) {
                    result.close();
                }
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                result = mNsm.queryDetailsForUid(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime, Process.myUid());
                fail("negative testUidDetails fails: no exception thrown.");
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    @Test
    public void testTagDetails() throws Exception {
        for (int i = 0; i < mNetworkInterfacesToTest.length; ++i) {
            // Relatively large tolerance to accommodate for history bucket size.
            if (!shouldTestThisNetworkType(i)) {
                continue;
            }
            requestNetworkAndGenerateTraffic(i, LONG_TOLERANCE);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats result = null;
            try {
                result = mNsm.queryDetailsForUidTag(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime, Process.myUid(), NETWORK_TAG);
                assertNotNull(result);
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                long totalTxPackets = 0;
                long totalRxPackets = 0;
                long totalTxBytes = 0;
                long totalRxBytes = 0;
                while (result.hasNextBucket()) {
                    assertTrue(result.getNextBucket(bucket));
                    assertTimestamps(bucket);
                    assertEquals(bucket.getState(), STATE_ALL);
                    assertEquals(bucket.getMetered(), METERED_ALL);
                    assertEquals(bucket.getRoaming(), ROAMING_ALL);
                    assertEquals(bucket.getDefaultNetworkStatus(), DEFAULT_NETWORK_ALL);
                    assertEquals(bucket.getUid(), Process.myUid());
                    if (bucket.getTag() == NETWORK_TAG) {
                        totalTxPackets += bucket.getTxPackets();
                        totalRxPackets += bucket.getRxPackets();
                        totalTxBytes += bucket.getTxBytes();
                        totalRxBytes += bucket.getRxBytes();
                    }
                }
                assertTrue("No Rx bytes tagged with 0x" + Integer.toHexString(NETWORK_TAG)
                        + " for uid " + Process.myUid(), totalRxBytes > 0);
                assertTrue("No Rx packets tagged with 0x" + Integer.toHexString(NETWORK_TAG)
                        + " for uid " + Process.myUid(), totalRxPackets > 0);
                assertTrue("No Tx bytes tagged with 0x" + Integer.toHexString(NETWORK_TAG)
                        + " for uid " + Process.myUid(), totalTxBytes > 0);
                assertTrue("No Tx packets tagged with 0x" + Integer.toHexString(NETWORK_TAG)
                        + " for uid " + Process.myUid(), totalTxPackets > 0);
            } finally {
                if (result != null) {
                    result.close();
                }
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                result = mNsm.queryDetailsForUidTag(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime, Process.myUid(), NETWORK_TAG);
                fail("negative testUidDetails fails: no exception thrown.");
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    class QueryResults {
        private static class QueryKey {
            private final int mTag;
            private final int mState;

            QueryKey(int tag, int state) {
                this.mTag = tag;
                this.mState = state;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof QueryKey)) return false;

                QueryKey queryKey = (QueryKey) o;
                return mTag == queryKey.mTag && mState == queryKey.mState;
            }

            @Override
            public int hashCode() {
                return Objects.hash(mTag, mState);
            }

            @Override
            public String toString() {
                return String.format("QueryKey(tag=%s, state=%s)", tagToString(mTag),
                        stateToString(mState));
            }
        }

        private final HashMap<QueryKey, Long> mSnapshot = new HashMap<>();

        public long get(int tag, int state) {
            // Expect all results are stored before access.
            return Objects.requireNonNull(mSnapshot.get(new QueryKey(tag, state)));
        }

        public void put(int tag, int state, long total) {
            mSnapshot.put(new QueryKey(tag, state), total);
        }
    }

    private long getTotalForTagState(int i, int tag, int state, boolean assertNotEmpty,
            long startTime, long endTime) {
        final NetworkStats stats = mNsm.queryDetailsForUidTagState(
                mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                startTime, endTime, Process.myUid(), tag, state);
        final long total = getTotal(stats, tag, state, assertNotEmpty, startTime, endTime);
        stats.close();
        return total;
    }

    private void assertWithinPercentage(String msg, long expected, long actual, int percentage) {
        long lowerBound = expected * (100 - percentage) / 100;
        long upperBound = expected * (100 + percentage) / 100;
        msg = String.format("%s: %d not within %d%% of %d", msg, actual, percentage, expected);
        assertTrue(msg, lowerBound <= actual);
        assertTrue(msg, upperBound >= actual);
    }

    private void assertAlmostNoUnexpectedTraffic(long total, int expectedTag,
            int expectedState, long maxUnexpected) {
        if (total <= maxUnexpected) return;

        fail(String.format("More than %d bytes of traffic when querying for tag %s state %s.",
                maxUnexpected, tagToString(expectedTag), stateToString(expectedState)));
    }

    @ConnectivityDiagnosticsCollector.CollectTcpdumpOnFailure
    @Test
    public void testUidTagStateDetails() throws Exception {
        for (int i = 0; i < mNetworkInterfacesToTest.length; ++i) {
            if (!shouldTestThisNetworkType(i)) {
                continue;
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");

            int currentState = isInForeground() ? STATE_FOREGROUND : STATE_DEFAULT;
            int otherState = (currentState == STATE_DEFAULT) ? STATE_FOREGROUND : STATE_DEFAULT;

            final List<Integer> statesWithTraffic = List.of(currentState, STATE_ALL);
            final List<Integer> statesWithNoTraffic = List.of(otherState);
            final ArrayList<Integer> allStates = new ArrayList<>();
            allStates.addAll(statesWithTraffic);
            allStates.addAll(statesWithNoTraffic);

            final List<Integer> tagsWithTraffic = List.of(NETWORK_TAG, TAG_NONE);
            final List<Integer> tagsWithNoTraffic = List.of(NETWORK_TAG + 1);
            final ArrayList<Integer> allTags = new ArrayList<>();
            allTags.addAll(tagsWithTraffic);
            allTags.addAll(tagsWithNoTraffic);

            // Relatively large tolerance to accommodate for history bucket size,
            // and covering the entire test duration.
            final long now = System.currentTimeMillis();
            final long startTime = now - LONG_TOLERANCE;
            final long endTime = now + LONG_TOLERANCE;

            // Collect a baseline before generating network traffic.
            QueryResults baseline = new QueryResults();
            final ArrayList<String> logNonEmptyBaseline = new ArrayList<>();
            for (int tag : allTags) {
                for (int state : allStates) {
                    final long total = getTotalForTagState(i, tag, state, false,
                            startTime, endTime);
                    baseline.put(tag, state, total);
                    if (total > 0) {
                        logNonEmptyBaseline.add(
                                new QueryResults.QueryKey(tag, state) + "=" + total);
                    }
                }
            }
            // TODO: Remove debug log for b/368624224.
            if (logNonEmptyBaseline.size() > 0) {
                Log.v(LOG_TAG, "Baseline=" + logNonEmptyBaseline);
            }

            // Generate some traffic and release the network.
            requestNetworkAndGenerateTraffic(i, LONG_TOLERANCE);

            QueryResults results = new QueryResults();
            // Collect results for all combinations of tags and states.
            for (int tag : allTags) {
                for (int state : allStates) {
                    final boolean assertNotEmpty = tagsWithTraffic.contains(tag)
                            && statesWithTraffic.contains(state);
                    final long total = getTotalForTagState(i, tag, state, assertNotEmpty,
                            startTime, endTime) - baseline.get(tag, state);
                    results.put(tag, state, total);
                }
            }

            // Expect that the results are within a few percentage points of each other.
            // This is ensures that FIN retransmits after the transfer is complete don't cause
            // the test to be flaky. The test URL currently returns just over 100k so this
            // should not be too noisy. It also ensures that the traffic sent by the test
            // harness, which is untagged, won't cause a failure.
            long totalOfNetworkTagAndCurrentState = results.get(NETWORK_TAG, currentState);
            for (int tag : allTags) {
                for (int state : allStates) {
                    final long result = results.get(tag, state);
                    final String queryKeyStr = new QueryResults.QueryKey(tag, state).toString();
                    if (tagsWithTraffic.contains(tag) && statesWithTraffic.contains(state)) {
                        assertWithinPercentage(queryKeyStr,
                                totalOfNetworkTagAndCurrentState, result, 16);
                    } else {
                        // Expect to see no traffic when querying for any combination with tag
                        // in tagsWithNoTraffic or any state in statesWithNoTraffic.
                        assertAlmostNoUnexpectedTraffic(result, tag, state,
                                totalOfNetworkTagAndCurrentState / 100);
                    }
                }
            }

            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                mNsm.queryDetailsForUidTag(
                        mNetworkInterfacesToTest[i].getNetworkType(), getSubscriberId(i),
                        mStartTime, mEndTime, Process.myUid(), NETWORK_TAG);
                fail("negative testUidDetails fails: no exception thrown.");
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    @Test
    public void testCallback() throws Exception {
        for (int i = 0; i < mNetworkInterfacesToTest.length; ++i) {
            // Relatively large tolerance to accommodate for history bucket size.
            if (!shouldTestThisNetworkType(i)) {
                continue;
            }
            requestNetworkAndGenerateTraffic(i, SHORT_TOLERANCE);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");

            TestUsageCallback usageCallback = new TestUsageCallback();
            HandlerThread thread = new HandlerThread("callback-thread");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            mNsm.registerUsageCallback(mNetworkInterfacesToTest[i].getNetworkType(),
                    getSubscriberId(i), THRESHOLD_BYTES, usageCallback, handler);

            // TODO: Force traffic and check whether the callback is invoked.
            // Right now the test only covers whether the callback can be registered, but not
            // whether it is invoked upon data usage since we don't have a scalable way of
            // storing files of >2MB in CTS.

            mNsm.unregisterUsageCallback(usageCallback);

            // For T- devices, the registerUsageCallback invocation below will need a looper
            // from the thread that calls into the API, which is not available in the test.
            if (SdkLevel.isAtLeastT()) {
                mNsm.registerUsageCallback(mNetworkInterfacesToTest[i].getNetworkType(),
                        getSubscriberId(i), THRESHOLD_BYTES, usageCallback);
                mNsm.unregisterUsageCallback(usageCallback);
            }
        }
    }

    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
    @Test
    public void testDataMigrationUtils() throws Exception {
        final List<String> prefixes = List.of(PREFIX_UID, PREFIX_XT, PREFIX_UID_TAG);
        for (final String prefix : prefixes) {
            final long duration = TextUtils.equals(PREFIX_XT, prefix) ? TimeUnit.HOURS.toMillis(1)
                    : TimeUnit.HOURS.toMillis(2);

            final NetworkStatsCollection collection =
                    NetworkStatsDataMigrationUtils.readPlatformCollection(prefix, duration);

            final long now = System.currentTimeMillis();
            final Set<Map.Entry<NetworkStatsCollection.Key, NetworkStatsHistory>> entries =
                    collection.getEntries().entrySet();
            for (final Map.Entry<NetworkStatsCollection.Key, NetworkStatsHistory> entry : entries) {
                for (final NetworkStatsHistory.Entry historyEntry : entry.getValue().getEntries()) {
                    // Verify all value fields are reasonable.
                    assertTrue(historyEntry.getBucketStart() <= now);
                    assertTrue(historyEntry.getActiveTime() <= duration);
                    assertTrue(historyEntry.getRxBytes() >= 0);
                    assertTrue(historyEntry.getRxPackets() >= 0);
                    assertTrue(historyEntry.getTxBytes() >= 0);
                    assertTrue(historyEntry.getTxPackets() >= 0);
                    assertTrue(historyEntry.getOperations() >= 0);
                }
            }
        }
    }

    private static String tagToString(Integer tag) {
        if (tag == null) return "null";
        switch (tag) {
            case TAG_NONE:
                return "TAG_NONE";
            default:
                return "0x" + Integer.toHexString(tag);
        }
    }

    private static String stateToString(Integer state) {
        if (state == null) return "null";
        switch (state) {
            case STATE_ALL:
                return "STATE_ALL";
            case STATE_DEFAULT:
                return "STATE_DEFAULT";
            case STATE_FOREGROUND:
                return "STATE_FOREGROUND";
        }
        throw new IllegalArgumentException("Unknown state " + state);
    }

    private long getTotal(NetworkStats result, Integer expectedTag,
            Integer expectedState, boolean assertNotEmpty, long startTime, long endTime) {
        assertTrue(result != null);
        NetworkStats.Bucket bucket = new NetworkStats.Bucket();
        long totalTxPackets = 0;
        long totalRxPackets = 0;
        long totalTxBytes = 0;
        long totalRxBytes = 0;
        while (result.hasNextBucket()) {
            assertTrue(result.getNextBucket(bucket));
            assertTimestamps(bucket, startTime, endTime);
            if (expectedTag != null) assertEquals(bucket.getTag(), (int) expectedTag);
            if (expectedState != null) assertEquals(bucket.getState(), (int) expectedState);
            assertEquals(bucket.getMetered(), METERED_ALL);
            assertEquals(bucket.getRoaming(), ROAMING_ALL);
            assertEquals(bucket.getDefaultNetworkStatus(), DEFAULT_NETWORK_ALL);
            if (bucket.getUid() == Process.myUid()) {
                totalTxPackets += bucket.getTxPackets();
                totalRxPackets += bucket.getRxPackets();
                totalTxBytes += bucket.getTxBytes();
                totalRxBytes += bucket.getRxBytes();
            }
        }
        assertFalse(result.getNextBucket(bucket));
        String msg = String.format("uid %d tag %s state %s",
                Process.myUid(), tagToString(expectedTag), stateToString(expectedState));
        if (assertNotEmpty) {
            assertTrue("No Rx bytes usage for " + msg, totalRxBytes > 0);
            assertTrue("No Rx packets usage for " + msg, totalRxPackets > 0);
            assertTrue("No Tx bytes usage for " + msg, totalTxBytes > 0);
            assertTrue("No Tx packets usage for " + msg, totalTxPackets > 0);
        }

        return totalRxBytes + totalTxBytes;
    }

    private long getTotalAndAssertNotEmpty(NetworkStats result) {
        return getTotal(result, null, STATE_ALL, true /*assertEmpty*/, mStartTime, mEndTime);
    }

    private void assertTimestamps(final NetworkStats.Bucket bucket) {
        assertTimestamps(bucket, mStartTime, mEndTime);
    }

    private void assertTimestamps(final NetworkStats.Bucket bucket, long startTime, long endTime) {
        assertTrue("Start timestamp " + bucket.getStartTimeStamp() + " is less than "
                + startTime, bucket.getStartTimeStamp() >= startTime);
        assertTrue("End timestamp " + bucket.getEndTimeStamp() + " is greater than "
                + endTime, bucket.getEndTimeStamp() <= endTime);
    }

    private static class TestUsageCallback extends NetworkStatsManager.UsageCallback {
        @Override
        public void onThresholdReached(int networkType, String subscriberId) {
            Log.v(LOG_TAG, "Called onThresholdReached for networkType=" + networkType
                    + " subscriberId=" + subscriberId);
        }
    }
}
