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

import static android.net.InetAddresses.parseNumericAddress;
import static android.net.nsd.NsdManager.PROTOCOL_DNS_SD;
import static android.net.thread.utils.IntegrationTestUtils.JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.SERVICE_DISCOVERY_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.discoverForServiceLost;
import static android.net.thread.utils.IntegrationTestUtils.discoverService;
import static android.net.thread.utils.IntegrationTestUtils.resolveService;
import static android.net.thread.utils.IntegrationTestUtils.resolveServiceUntil;
import static android.net.thread.utils.IntegrationTestUtils.waitFor;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.thread.utils.FullThreadDevice;
import android.net.thread.utils.OtDaemonController;
import android.net.thread.utils.TapTestNetworkTracker;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresSimulationThreadDevice;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Integration test cases for Service Discovery feature. */
@RunWith(AndroidJUnit4.class)
@RequiresThreadFeature
@RequiresSimulationThreadDevice
@LargeTest
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
    private final ThreadNetworkControllerWrapper mController =
            ThreadNetworkControllerWrapper.newInstance(mContext);
    private final OtDaemonController mOtCtl = new OtDaemonController();
    private HandlerThread mHandlerThread;
    private NsdManager mNsdManager;
    private TapTestNetworkTracker mTestNetworkTracker;
    private final List<FullThreadDevice> mFtds = new ArrayList<>();
    private final List<RegistrationListener> mRegistrationListeners = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        mOtCtl.factoryReset();
        mController.setEnabledAndWait(true);
        mController.joinAndWait(DEFAULT_DATASET);
        mNsdManager = mContext.getSystemService(NsdManager.class);

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();

        mTestNetworkTracker = new TapTestNetworkTracker(mContext, mHandlerThread.getLooper());
        assertThat(mTestNetworkTracker).isNotNull();
        mController.setTestNetworkAsUpstreamAndWait(mTestNetworkTracker.getInterfaceName());

        // Create the FTDs in setUp() so that the FTDs can be safely released in tearDown().
        // Don't create new FTDs in test cases.
        for (int i = 0; i < NUM_FTD; ++i) {
            FullThreadDevice ftd = new FullThreadDevice(10 + i /* node ID */);
            ftd.autoStartSrpClient();
            mFtds.add(ftd);
        }
    }

    @After
    public void tearDown() throws Exception {
        for (RegistrationListener listener : mRegistrationListeners) {
            unregisterService(listener);
        }
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
        mController.setTestNetworkAsUpstreamAndWait(null);
        mController.leaveAndWait();
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

    @Test
    public void meshcopOverlay_vendorAndModelNameAreSetToOverlayValue() throws Exception {
        NsdServiceInfo discoveredService = discoverService(mNsdManager, "_meshcop._udp");
        assertThat(discoveredService).isNotNull();
        NsdServiceInfo meshcopService = resolveService(mNsdManager, discoveredService);

        Map<String, byte[]> txtMap = meshcopService.getAttributes();
        assertThat(txtMap.get("vn")).isEqualTo("Android".getBytes(UTF_8));
        assertThat(txtMap.get("mn")).isEqualTo("Thread Border Router".getBytes(UTF_8));
    }

    @Test
    @Ignore("TODO: b/332452386 - Enable this test case when it handles the multi-client case well")
    public void discoveryProxy_multipleClientsBrowseAndResolveServiceOverMdns() throws Exception {
        /*
         * <pre>
         * Topology:
         *                    Thread
         *  Border Router -------------- Full Thread device
         *  (Cuttlefish)
         * </pre>
         */

        RegistrationListener listener = new RegistrationListener();
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceType("_testservice._tcp");
        info.setServiceName("test-service");
        info.setPort(12345);
        info.setHostname("testhost");
        info.setHostAddresses(List.of(parseNumericAddress("2001::1")));
        info.setAttribute("key1", bytes(0x01, 0x02));
        info.setAttribute("key2", bytes(0x03));
        registerService(info, listener);
        mRegistrationListeners.add(listener);
        for (int i = 0; i < NUM_FTD; ++i) {
            FullThreadDevice ftd = mFtds.get(i);
            ftd.joinNetwork(DEFAULT_DATASET);
            ftd.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);
            ftd.setDnsServerAddress(mOtCtl.getMlEid().getHostAddress());
        }
        final ArrayList<NsdServiceInfo> browsedServices = new ArrayList<>();
        final ArrayList<NsdServiceInfo> resolvedServices = new ArrayList<>();
        final ArrayList<Thread> threads = new ArrayList<>();
        for (int i = 0; i < NUM_FTD; ++i) {
            browsedServices.add(null);
            resolvedServices.add(null);
        }
        for (int i = 0; i < NUM_FTD; ++i) {
            final FullThreadDevice ftd = mFtds.get(i);
            final int index = i;
            Runnable task =
                    () -> {
                        browsedServices.set(
                                index,
                                ftd.browseService("_testservice._tcp.default.service.arpa."));
                        resolvedServices.set(
                                index,
                                ftd.resolveService(
                                        "test-service", "_testservice._tcp.default.service.arpa."));
                    };
            threads.add(new Thread(task));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        for (int i = 0; i < NUM_FTD; ++i) {
            NsdServiceInfo browsedService = browsedServices.get(i);
            assertThat(browsedService.getServiceName()).isEqualTo("test-service");
            assertThat(browsedService.getPort()).isEqualTo(12345);

            NsdServiceInfo resolvedService = resolvedServices.get(i);
            assertThat(resolvedService.getServiceName()).isEqualTo("test-service");
            assertThat(resolvedService.getPort()).isEqualTo(12345);
            assertThat(resolvedService.getHostname()).isEqualTo("testhost.default.service.arpa.");
            assertThat(resolvedService.getHostAddresses())
                    .containsExactly(parseNumericAddress("2001::1"));
            assertThat(resolvedService.getAttributes())
                    .comparingValuesUsing(BYTE_ARRAY_EQUALITY)
                    .containsExactly("key1", bytes(0x01, 0x02), "key2", bytes(3));
        }
    }

    @Test
    public void discoveryProxy_browseAndResolveServiceAtSrpServer() throws Exception {
        /*
         * <pre>
         * Topology:
         *                    Thread
         *  Border Router -------+------ SRP client
         *  (Cuttlefish)         |
         *                       +------ DNS client
         *
         * </pre>
         */
        FullThreadDevice srpClient = mFtds.get(0);
        srpClient.joinNetwork(DEFAULT_DATASET);
        srpClient.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);
        srpClient.setSrpHostname("my-host");
        srpClient.setSrpHostAddresses(List.of((Inet6Address) parseNumericAddress("2001::1")));
        srpClient.addSrpService(
                "my-service",
                "_test._udp",
                List.of("_sub1"),
                12345 /* port */,
                Map.of("key1", bytes(0x01, 0x02), "key2", bytes(0x03)));

        FullThreadDevice dnsClient = mFtds.get(1);
        dnsClient.joinNetwork(DEFAULT_DATASET);
        dnsClient.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);
        dnsClient.setDnsServerAddress(mOtCtl.getMlEid().getHostAddress());

        NsdServiceInfo browsedService = dnsClient.browseService("_test._udp.default.service.arpa.");
        assertThat(browsedService.getServiceName()).isEqualTo("my-service");
        assertThat(browsedService.getPort()).isEqualTo(12345);
        assertThat(browsedService.getHostname()).isEqualTo("my-host.default.service.arpa.");
        assertThat(browsedService.getHostAddresses())
                .containsExactly(parseNumericAddress("2001::1"));
        assertThat(browsedService.getAttributes())
                .comparingValuesUsing(BYTE_ARRAY_EQUALITY)
                .containsExactly("key1", bytes(0x01, 0x02), "key2", bytes(3));

        NsdServiceInfo resolvedService =
                dnsClient.resolveService("my-service", "_test._udp.default.service.arpa.");
        assertThat(resolvedService.getServiceName()).isEqualTo("my-service");
        assertThat(resolvedService.getPort()).isEqualTo(12345);
        assertThat(resolvedService.getHostname()).isEqualTo("my-host.default.service.arpa.");
        assertThat(resolvedService.getHostAddresses())
                .containsExactly(parseNumericAddress("2001::1"));
        assertThat(resolvedService.getAttributes())
                .comparingValuesUsing(BYTE_ARRAY_EQUALITY)
                .containsExactly("key1", bytes(0x01, 0x02), "key2", bytes(3));
    }

    private void registerService(NsdServiceInfo serviceInfo, RegistrationListener listener)
            throws InterruptedException, ExecutionException, TimeoutException {
        mNsdManager.registerService(serviceInfo, PROTOCOL_DNS_SD, listener);
        listener.waitForRegistered();
    }

    private void unregisterService(RegistrationListener listener)
            throws InterruptedException, ExecutionException, TimeoutException {
        mNsdManager.unregisterService(listener);
        listener.waitForUnregistered();
    }

    private static class RegistrationListener implements NsdManager.RegistrationListener {
        private final CompletableFuture<Void> mRegisteredFuture = new CompletableFuture<>();
        private final CompletableFuture<Void> mUnRegisteredFuture = new CompletableFuture<>();

        RegistrationListener() {}

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            mRegisteredFuture.complete(null);
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            mUnRegisteredFuture.complete(null);
        }

        public void waitForRegistered()
                throws InterruptedException, ExecutionException, TimeoutException {
            mRegisteredFuture.get(SERVICE_DISCOVERY_TIMEOUT.toMillis(), MILLISECONDS);
        }

        public void waitForUnregistered()
                throws InterruptedException, ExecutionException, TimeoutException {
            mUnRegisteredFuture.get(SERVICE_DISCOVERY_TIMEOUT.toMillis(), MILLISECONDS);
        }
    }

    private static byte[] bytes(int... byteInts) {
        byte[] bytes = new byte[byteInts.length];
        for (int i = 0; i < byteInts.length; ++i) {
            bytes[i] = (byte) byteInts[i];
        }
        return bytes;
    }
}
