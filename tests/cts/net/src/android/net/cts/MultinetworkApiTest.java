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

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkUtils;
import android.net.cts.util.CtsNetUtils;
import android.platform.test.annotations.AppModeFull;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.net.module.util.CollectionUtils;
import com.android.testutils.AutoReleaseNetworkCallbackRule;
import com.android.testutils.ConnectivityDiagnosticsCollector;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.DeviceConfigRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

@DevSdkIgnoreRunner.RestoreDefaultNetwork
@RunWith(DevSdkIgnoreRunner.class)
public class MultinetworkApiTest {
    @Rule(order = 1)
    public final DeviceConfigRule mDeviceConfigRule = new DeviceConfigRule();

    @Rule(order = 2)
    public final AutoReleaseNetworkCallbackRule
            mNetworkCallbackRule = new AutoReleaseNetworkCallbackRule();

    static {
        System.loadLibrary("nativemultinetwork_jni");
    }

    private static final String TAG = "MultinetworkNativeApiTest";
    static final String GOOGLE_PRIVATE_DNS_SERVER = "dns.google";

    public static class QueryTestResult {
        public final int sourcePort;
        public final int attempts;
        public final int errNo;

        public QueryTestResult(int sourcePort, int attempts, int errNo) {
            this.sourcePort = sourcePort;
            this.attempts = attempts;
            this.errNo = errNo;
        }

        @Override
        public String toString() {
            return "QueryTestResult{"
                    + "sourcePort=" + sourcePort
                    + ", attempts=" + attempts
                    + ", errNo=" + errNo
                    + '}';
        }
    }

    /**
     * @return 0 on success
     */
    private static native int runGetaddrinfoCheck(long networkHandle);
    private static native int runSetprocnetwork(long networkHandle);
    private static native int runSetsocknetwork(long networkHandle);
    private static native QueryTestResult runDatagramCheck(long networkHandle, int sourcePort);
    private static native void runResNapiMalformedCheck(long networkHandle);
    private static native void runResNcancelCheck(long networkHandle);
    private static native void runResNqueryCheck(long networkHandle);
    private static native void runResNsendCheck(long networkHandle);
    private static native void runResNnxDomainCheck(long networkHandle);


    private ConnectivityManager mCM;
    private CtsNetUtils mCtsNetUtils;

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mCM = context.getSystemService(ConnectivityManager.class);
        mCtsNetUtils = new CtsNetUtils(context);
        // In instant mode, do not request cell or Wi-Fi connection, as this requires the
        // CHANGE_NETWORK_STATE permission which cannot be granted.
        if (!context.getApplicationInfo().isInstantApp()) {
            mNetworkCallbackRule.requestCellIfSupported();
            mNetworkCallbackRule.requestWifiIfSupported();
        }
    }

    @Test
    public void testGetaddrinfo() throws Exception {
        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            int errno = runGetaddrinfoCheck(network.getNetworkHandle());
            if (errno != 0) {
                throw new ErrnoException(
                        "getaddrinfo on " + mCM.getNetworkInfo(network), -errno);
            }
        }
    }

    @Test
    @AppModeFull(reason = "CHANGE_NETWORK_STATE permission can't be granted to instant apps")
    public void testSetprocnetwork() throws Exception {
        // Hopefully no prior test in this process space has set a default network.
        assertNull(mCM.getProcessDefaultNetwork());
        assertEquals(0, NetworkUtils.getBoundNetworkForProcess());

        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            mCM.setProcessDefaultNetwork(null);
            assertNull(mCM.getProcessDefaultNetwork());

            int errno = runSetprocnetwork(network.getNetworkHandle());
            if (errno != 0) {
                throw new ErrnoException(
                        "setprocnetwork on " + mCM.getNetworkInfo(network), -errno);
            }
            Network processDefault = mCM.getProcessDefaultNetwork();
            assertNotNull(processDefault);
            assertEquals(network, processDefault);
            // TODO: open DatagramSockets, connect them to 192.0.2.1 and 2001:db8::,
            // and ensure that the source address is in fact on this network as
            // determined by mCM.getLinkProperties(network).

            mCM.setProcessDefaultNetwork(null);
        }

        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            NetworkUtils.bindProcessToNetwork(0);
            assertNull(mCM.getBoundNetworkForProcess());

            int errno = runSetprocnetwork(network.getNetworkHandle());
            if (errno != 0) {
                throw new ErrnoException(
                        "setprocnetwork on " + mCM.getNetworkInfo(network), -errno);
            }
            assertEquals(network, new Network(mCM.getBoundNetworkForProcess()));
            // TODO: open DatagramSockets, connect them to 192.0.2.1 and 2001:db8::,
            // and ensure that the source address is in fact on this network as
            // determined by mCM.getLinkProperties(network).

            NetworkUtils.bindProcessToNetwork(0);
        }
    }

    @Test
    @AppModeFull(reason = "CHANGE_NETWORK_STATE permission can't be granted to instant apps")
    public void testSetsocknetwork() throws Exception {
        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            int errno = runSetsocknetwork(network.getNetworkHandle());
            if (errno != 0) {
                throw new ErrnoException(
                        "setsocknetwork on " + mCM.getNetworkInfo(network), -errno);
            }
        }
    }

    private void runNativeDatagramTransmissionDiagnostics(Network network,
            QueryTestResult failedResult) {
        final ConnectivityDiagnosticsCollector collector = ConnectivityDiagnosticsCollector
                .getInstance();
        if (collector == null) {
            Log.e(TAG, "Missing ConnectivityDiagnosticsCollector, not adding diagnostics");
            return;
        }

        final int numReruns = 10;
        final ArrayList<QueryTestResult> reruns = new ArrayList<>(numReruns);
        for (int i = 0; i < numReruns; i++) {
            final QueryTestResult rerunResult =
                    runDatagramCheck(network.getNetworkHandle(), 0 /* sourcePort */);
            Log.d(TAG, "Rerun result " + i + ": " + rerunResult);
            reruns.add(rerunResult);
        }
        // Rerun on the original port after trying the other ports, to check that the results are
        // consistent, as opposed to the network recovering halfway through.
        int originalPortFailedReruns = 0;
        for (int i = 0; i < numReruns; i++) {
            final QueryTestResult originalPortRerun = runDatagramCheck(network.getNetworkHandle(),
                    failedResult.sourcePort);
            Log.d(TAG, "Rerun result " + i + " with original port: " + originalPortRerun);
            if (originalPortRerun.errNo != 0) {
                originalPortFailedReruns++;
            }
        }

        final int noRetrySuccessResults = reruns.stream()
                .filter(result -> result.errNo == 0 && result.attempts == 1)
                .mapToInt(result -> 1)
                .sum();
        final int failedResults = reruns.stream()
                .filter(result -> result.errNo != 0)
                .mapToInt(result -> 1)
                .sum();
        collector.addFailureAttribute("numReruns", numReruns);
        collector.addFailureAttribute("noRetrySuccessReruns", noRetrySuccessResults);
        collector.addFailureAttribute("failedReruns", failedResults);
        collector.addFailureAttribute("originalPortFailedReruns", originalPortFailedReruns);
    }

    @Test
    public void testNativeDatagramTransmission() throws Exception {
        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            final QueryTestResult result = runDatagramCheck(network.getNetworkHandle(),
                    0 /* sourcePort */);
            if (result.errNo == 0) {
                continue;
            }
            final NetworkCapabilities nc = mCM.getNetworkCapabilities(network);
            final int[] transports = nc != null ? nc.getTransportTypes() : null;
            if (CollectionUtils.contains(transports, TRANSPORT_WIFI)) {
                runNativeDatagramTransmissionDiagnostics(network, result);
            }

            // Log the whole result (with source port and attempts) to logcat, but use only the
            // errno and transport in the fail message so similar failures have consistent messages
            final String error = "DatagramCheck on transport " + Arrays.toString(transports)
                    + " failed: " + result.errNo;
            Log.e(TAG, error + ", result: " + result);
            fail(error);
        }
    }

    @Test
    public void testNoSuchNetwork() throws Exception {
        final Network eNoNet = new Network(54321);
        assertNull(mCM.getNetworkInfo(eNoNet));

        final long eNoNetHandle = eNoNet.getNetworkHandle();
        assertEquals(-OsConstants.ENONET, runSetsocknetwork(eNoNetHandle));
        assertEquals(-OsConstants.ENONET, runSetprocnetwork(eNoNetHandle));
        // TODO: correct test permissions so this call is not silently re-mapped
        // to query on the default network.
        // assertEquals(-OsConstants.ENONET, runGetaddrinfoCheck(eNoNetHandle));
    }

    @Test
    public void testNetworkHandle() throws Exception {
        // Test Network -> NetworkHandle -> Network results in the same Network.
        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            long networkHandle = network.getNetworkHandle();
            Network newNetwork = Network.fromNetworkHandle(networkHandle);
            assertEquals(newNetwork, network);
        }

        // Test that only obfuscated handles are allowed.
        try {
            Network.fromNetworkHandle(100);
            fail();
        } catch (IllegalArgumentException e) {}
        try {
            Network.fromNetworkHandle(-1);
            fail();
        } catch (IllegalArgumentException e) {}
        try {
            Network.fromNetworkHandle(0);
            fail();
        } catch (IllegalArgumentException e) {}
    }

    @Test
    public void testResNApi() throws Exception {
        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            // Throws AssertionError directly in jni function if test fail.
            runResNqueryCheck(network.getNetworkHandle());
            runResNsendCheck(network.getNetworkHandle());
            runResNcancelCheck(network.getNetworkHandle());
            runResNapiMalformedCheck(network.getNetworkHandle());

            final NetworkCapabilities nc = mCM.getNetworkCapabilities(network);
            // Some cellular networks configure their DNS servers never to return NXDOMAIN, so don't
            // test NXDOMAIN on these DNS servers.
            // b/144521720
            if (nc != null && !nc.hasTransport(TRANSPORT_CELLULAR)) {
                runResNnxDomainCheck(network.getNetworkHandle());
            }
        }
    }

    @Test
    @AppModeFull(reason = "WRITE_SECURE_SETTINGS permission can't be granted to instant apps")
    public void testResNApiNXDomainPrivateDns() throws Exception {
        // Use async private DNS resolution to avoid flakes due to races applying the setting
        mDeviceConfigRule.setConfig(NAMESPACE_CONNECTIVITY,
                "networkmonitor_async_privdns_resolution", "1");
        mCtsNetUtils.reconnectWifiIfSupported();
        mCtsNetUtils.reconnectCellIfSupported();

        mCtsNetUtils.storePrivateDnsSetting();

        mDeviceConfigRule.runAfterNextCleanup(() -> {
            mCtsNetUtils.reconnectWifiIfSupported();
            mCtsNetUtils.reconnectCellIfSupported();
        });
        // Enable private DNS strict mode and set server to dns.google before doing NxDomain test.
        // b/144521720
        try {
            mCtsNetUtils.setPrivateDnsStrictMode(GOOGLE_PRIVATE_DNS_SERVER);
            for (Network network : mCtsNetUtils.getTestableNetworks()) {
              // Wait for private DNS setting to propagate.
              mCtsNetUtils.awaitPrivateDnsSetting("NxDomain test wait private DNS setting timeout",
                        network, GOOGLE_PRIVATE_DNS_SERVER, true);
              runResNnxDomainCheck(network.getNetworkHandle());
            }
        } finally {
            mCtsNetUtils.restorePrivateDnsSetting();
        }
    }
}
