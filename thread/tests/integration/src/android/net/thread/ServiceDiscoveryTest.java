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

import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.net.InetAddresses.parseNumericAddress;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;
import static android.net.thread.utils.IntegrationTestUtils.JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.RESTART_JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.SERVICE_DISCOVERY_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.discoverForServiceLost;
import static android.net.thread.utils.IntegrationTestUtils.discoverService;
import static android.net.thread.utils.IntegrationTestUtils.resolveService;
import static android.net.thread.utils.IntegrationTestUtils.resolveServiceUntil;
import static android.net.thread.utils.IntegrationTestUtils.waitFor;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assert.assertThrows;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.thread.utils.FullThreadDevice;
import android.net.thread.utils.TapTestNetworkTracker;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresSimulationThreadDevice;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.os.HandlerThread;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Correspondence;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/** Integration test cases for Service Discovery feature. */
@RunWith(AndroidJUnit4.class)
@RequiresThreadFeature
@RequiresSimulationThreadDevice
@LargeTest
@Ignore("TODO: b/328527773 - enable the test when it's stable")
public class ServiceDiscoveryTest {
    private static final String TAG = ServiceDiscoveryTest.class.getSimpleName();
    private static final int NUM_FTD = 3;

    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset init new".
    private static final byte[] DEFAULT_DATASET_TLVS =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");
    private static final ActiveOperationalDataset DEFAULT_DATASET =
            ActiveOperationalDataset.fromThreadTlvs(DEFAULT_DATASET_TLVS);

    private static final Correspondence<byte[], byte[]> BYTE_ARRAY_EQUALITY =
            Correspondence.from(Arrays::equals, "is equivalent to");

    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();

    private HandlerThread mHandlerThread;
    private ThreadNetworkController mController;
    private NsdManager mNsdManager;
    private TapTestNetworkTracker mTestNetworkTracker;
    private List<FullThreadDevice> mFtds;

    @Before
    public void setUp() throws Exception {
        final ThreadNetworkManager manager = mContext.getSystemService(ThreadNetworkManager.class);
        if (manager != null) {
            mController = manager.getAllThreadNetworkControllers().get(0);
        }

        // BR forms a network.
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> mController.join(DEFAULT_DATASET, directExecutor(), joinFuture::complete));
        joinFuture.get(RESTART_JOIN_TIMEOUT.toMillis(), MILLISECONDS);

        mNsdManager = mContext.getSystemService(NsdManager.class);

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();

        mTestNetworkTracker = new TapTestNetworkTracker(mContext, mHandlerThread.getLooper());
        assertThat(mTestNetworkTracker).isNotNull();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                NETWORK_SETTINGS,
                () -> {
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    mController.setTestNetworkAsUpstream(
                            mTestNetworkTracker.getInterfaceName(),
                            directExecutor(),
                            v -> future.complete(null));
                    future.get(5, SECONDS);
                });
        // Create the FTDs in setUp() so that the FTDs can be safely released in tearDown().
        // Don't create new FTDs in test cases.
        mFtds = new ArrayList<>();
        for (int i = 0; i < NUM_FTD; ++i) {
            FullThreadDevice ftd = new FullThreadDevice(10 + i /* node ID */);
            ftd.autoStartSrpClient();
            mFtds.add(ftd);
        }
    }

    @After
    public void tearDown() throws Exception {
        for (FullThreadDevice ftd : mFtds) {
            // Clear registered SRP hosts and services
            if (ftd.isSrpHostRegistered()) {
                ftd.removeSrpHost();
            }
            ftd.destroy();
        }
        if (mTestNetworkTracker != null) {
            mTestNetworkTracker.tearDown();
        }
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread.join();
        }
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                NETWORK_SETTINGS,
                () -> {
                    CompletableFuture<Void> setUpstreamFuture = new CompletableFuture<>();
                    CompletableFuture<Void> leaveFuture = new CompletableFuture<>();
                    mController.setTestNetworkAsUpstream(
                            null, directExecutor(), v -> setUpstreamFuture.complete(null));
                    mController.leave(directExecutor(), v -> leaveFuture.complete(null));
                    setUpstreamFuture.get(5, SECONDS);
                    leaveFuture.get(5, SECONDS);
                });
    }

    @Test
    public void advertisingProxy_multipleSrpClientsRegisterServices_servicesResolvableByMdns()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                    Thread
         *  Border Router -------------- Full Thread device 1
         *  (Cuttlefish)         |
         *                       +------ Full Thread device 2
         *                       |
         *                       +------ Full Thread device 3
         * </pre>
         */

        // Creates Full Thread Devices (FTD) and let them join the network.
        for (FullThreadDevice ftd : mFtds) {
            ftd.joinNetwork(DEFAULT_DATASET);
            ftd.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);
        }

        int randomId = new Random().nextInt(10_000);

        String serviceNamePrefix = "service-" + randomId + "-";
        String serviceTypePrefix = "_test" + randomId;
        String hostnamePrefix = "host-" + randomId + "-";

        // For every FTD, let it register an SRP service.
        for (int i = 0; i < mFtds.size(); ++i) {
            FullThreadDevice ftd = mFtds.get(i);
            ftd.setSrpHostname(hostnamePrefix + i);
            ftd.setSrpHostAddresses(List.of(ftd.getOmrAddress(), ftd.getMlEid()));
            ftd.addSrpService(
                    serviceNamePrefix + i,
                    serviceTypePrefix + i + "._tcp",
                    List.of("_sub1", "_sub2"),
                    12345 /* port */,
                    Map.of("key1", bytes(0x01, 0x02), "key2", bytes(i)));
        }

        // Check the advertised services are discoverable and resolvable by NsdManager
        for (int i = 0; i < mFtds.size(); ++i) {
            NsdServiceInfo discoveredService =
                    discoverService(mNsdManager, serviceTypePrefix + i + "._tcp");
            assertThat(discoveredService).isNotNull();
            NsdServiceInfo resolvedService = resolveService(mNsdManager, discoveredService);
            assertThat(resolvedService.getServiceName()).isEqualTo(serviceNamePrefix + i);
            assertThat(resolvedService.getServiceType()).isEqualTo(serviceTypePrefix + i + "._tcp");
            assertThat(resolvedService.getPort()).isEqualTo(12345);
            assertThat(resolvedService.getAttributes())
                    .comparingValuesUsing(BYTE_ARRAY_EQUALITY)
                    .containsExactly("key1", bytes(0x01, 0x02), "key2", bytes(i));
            assertThat(resolvedService.getHostname()).isEqualTo(hostnamePrefix + i);
            assertThat(resolvedService.getHostAddresses())
                    .containsExactly(mFtds.get(i).getOmrAddress());
        }
    }

    @Test
    public void advertisingProxy_srpClientUpdatesService_updatedServiceResolvableByMdns()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                    Thread
         *  Border Router -------------- Full Thread device
         *  (Cuttlefish)
         * </pre>
         */

        // Creates a Full Thread Devices (FTD) and let it join the network.
        FullThreadDevice ftd = mFtds.get(0);
        ftd.joinNetwork(DEFAULT_DATASET);
        ftd.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);
        ftd.setSrpHostname("my-host");
        ftd.setSrpHostAddresses(List.of((Inet6Address) parseNumericAddress("2001:db8::1")));
        ftd.addSrpService(
                "my-service",
                "_test._tcp",
                Collections.emptyList() /* subtypes */,
                12345 /* port */,
                Map.of("key1", bytes(0x01, 0x02), "key2", bytes(0x03)));

        // Update the host addresses
        ftd.setSrpHostAddresses(
                List.of(
                        (Inet6Address) parseNumericAddress("2001:db8::1"),
                        (Inet6Address) parseNumericAddress("2001:db8::2")));
        // Update the service
        ftd.updateSrpService(
                "my-service", "_test._tcp", List.of("_sub3"), 11111, Map.of("key1", bytes(0x04)));
        waitFor(ftd::isSrpHostRegistered, SERVICE_DISCOVERY_TIMEOUT);

        // Check the advertised service is discoverable and resolvable by NsdManager
        NsdServiceInfo discoveredService = discoverService(mNsdManager, "_test._tcp");
        assertThat(discoveredService).isNotNull();
        NsdServiceInfo resolvedService =
                resolveServiceUntil(
                        mNsdManager,
                        discoveredService,
                        s -> s.getPort() == 11111 && s.getHostAddresses().size() == 2);
        assertThat(resolvedService.getServiceName()).isEqualTo("my-service");
        assertThat(resolvedService.getServiceType()).isEqualTo("_test._tcp");
        assertThat(resolvedService.getPort()).isEqualTo(11111);
        assertThat(resolvedService.getAttributes())
                .comparingValuesUsing(BYTE_ARRAY_EQUALITY)
                .containsExactly("key1", bytes(0x04));
        assertThat(resolvedService.getHostname()).isEqualTo("my-host");
        assertThat(resolvedService.getHostAddresses())
                .containsExactly(
                        parseNumericAddress("2001:db8::1"), parseNumericAddress("2001:db8::2"));
    }

    @Test
    public void advertisingProxy_srpClientUnregistersService_serviceIsNotDiscoverableByMdns()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                    Thread
         *  Border Router -------------- Full Thread device
         *  (Cuttlefish)
         * </pre>
         */

        // Creates a Full Thread Devices (FTD) and let it join the network.
        FullThreadDevice ftd = mFtds.get(0);
        ftd.joinNetwork(DEFAULT_DATASET);
        ftd.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);
        ftd.setSrpHostname("my-host");
        ftd.setSrpHostAddresses(
                List.of(
                        (Inet6Address) parseNumericAddress("2001:db8::1"),
                        (Inet6Address) parseNumericAddress("2001:db8::2")));
        ftd.addSrpService(
                "my-service",
                "_test._udp",
                List.of("_sub1"),
                12345 /* port */,
                Map.of("key1", bytes(0x01, 0x02), "key2", bytes(0x03)));
        // Wait for the service to be discoverable by NsdManager.
        assertThat(discoverService(mNsdManager, "_test._udp")).isNotNull();

        // Unregister the service.
        CompletableFuture<NsdServiceInfo> serviceLostFuture = new CompletableFuture<>();
        NsdManager.DiscoveryListener listener =
                discoverForServiceLost(mNsdManager, "_test._udp", serviceLostFuture);
        ftd.removeSrpService("my-service", "_test._udp", true /* notifyServer */);

        // Verify the service becomes lost.
        try {
            serviceLostFuture.get(SERVICE_DISCOVERY_TIMEOUT.toMillis(), MILLISECONDS);
        } finally {
            mNsdManager.stopServiceDiscovery(listener);
        }
        assertThrows(TimeoutException.class, () -> discoverService(mNsdManager, "_test._udp"));
    }

    private static byte[] bytes(int... byteInts) {
        byte[] bytes = new byte[byteInts.length];
        for (int i = 0; i < byteInts.length; ++i) {
            bytes[i] = (byte) byteInts[i];
        }
        return bytes;
    }
}
