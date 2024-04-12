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

import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_DETACHED;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_STOPPED;
import static android.net.thread.utils.IntegrationTestUtils.CALLBACK_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.RESTART_JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.getIpv6LinkAddresses;
import static android.net.thread.utils.IntegrationTestUtils.isInMulticastGroup;
import static android.net.thread.utils.IntegrationTestUtils.waitFor;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow;
import static com.android.server.thread.openthread.IOtDaemon.TUN_IF_NAME;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.thread.utils.FullThreadDevice;
import android.net.thread.utils.OtDaemonController;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tests for E2E Android Thread integration with ot-daemon, ConnectivityService, etc.. */
@LargeTest
@RequiresThreadFeature
@RunWith(AndroidJUnit4.class)
public class ThreadIntegrationTest {
    // The byte[] buffer size for UDP tests
    private static final int UDP_BUFFER_SIZE = 1024;

    // The maximum time for OT addresses to be propagated to the TUN interface "thread-wpan"
    private static final Duration TUN_ADDR_UPDATE_TIMEOUT = Duration.ofSeconds(1);

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

    private static final Inet6Address GROUP_ADDR_ALL_ROUTERS =
            (Inet6Address) InetAddresses.parseNumericAddress("ff02::2");

    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    private ExecutorService mExecutor;
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final ThreadNetworkControllerWrapper mController =
            ThreadNetworkControllerWrapper.newInstance(mContext);
    private OtDaemonController mOtCtl;
    private FullThreadDevice mFtd;

    @Before
    public void setUp() throws Exception {
        mExecutor = Executors.newSingleThreadExecutor();
        mOtCtl = new OtDaemonController();
        mController.leaveAndWait();

        // TODO: b/323301831 - This is a workaround to avoid unnecessary delay to re-form a network
        mOtCtl.factoryReset();

        mFtd = new FullThreadDevice(10 /* nodeId */);
    }

    @After
    public void tearDown() throws Exception {
        mController.setTestNetworkAsUpstreamAndWait(null);
        mController.leaveAndWait();

        mFtd.destroy();
        mExecutor.shutdownNow();
    }

    @Test
    public void otDaemonRestart_notJoinedAndStopped_deviceRoleIsStopped() throws Exception {
        mController.leaveAndWait();

        runShellCommand("stop ot-daemon");
        // TODO(b/323331973): the sleep is needed to workaround the race conditions
        SystemClock.sleep(200);

        mController.waitForRole(DEVICE_ROLE_STOPPED, CALLBACK_TIMEOUT);
    }

    @Test
    public void otDaemonRestart_JoinedNetworkAndStopped_autoRejoinedAndTunIfStateConsistent()
            throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        runShellCommand("stop ot-daemon");

        mController.waitForRole(DEVICE_ROLE_DETACHED, CALLBACK_TIMEOUT);
        mController.waitForRole(DEVICE_ROLE_LEADER, RESTART_JOIN_TIMEOUT);
        assertThat(mOtCtl.isInterfaceUp()).isTrue();
        assertThat(runShellCommand("ifconfig thread-wpan")).contains("UP POINTOPOINT RUNNING");
    }

    @Test
    public void otDaemonFactoryReset_deviceRoleIsStopped() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        mOtCtl.factoryReset();

        assertThat(mController.getDeviceRole()).isEqualTo(DEVICE_ROLE_STOPPED);
    }

    @Test
    public void otDaemonFactoryReset_addressesRemoved() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        mOtCtl.factoryReset();

        String ifconfig = runShellCommand("ifconfig thread-wpan");
        assertThat(ifconfig).doesNotContain("inet6 addr");
    }

    // TODO (b/323300829): add test for removing an OT address
    @Test
    public void tunInterface_joinedNetwork_otAddressesAddedToTunInterface() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        List<Inet6Address> otAddresses = mOtCtl.getAddresses();
        assertThat(otAddresses).isNotEmpty();
        // TODO: it's cleaner to have a retry() method to retry failed asserts in given delay so
        // that we can write assertThat() in the Predicate
        waitFor(
                () -> {
                    String ifconfig = runShellCommand("ifconfig thread-wpan");
                    return otAddresses.stream()
                            .allMatch(addr -> ifconfig.contains(addr.getHostAddress()));
                },
                TUN_ADDR_UPDATE_TIMEOUT);
    }

    @Test
    public void otDaemonRestart_latestCountryCodeIsSetToOtDaemon() throws Exception {
        runThreadCommand("force-country-code enabled CN");

        runShellCommand("stop ot-daemon");
        // TODO(b/323331973): the sleep is needed to workaround the race conditions
        SystemClock.sleep(200);
        mController.waitForRole(DEVICE_ROLE_STOPPED, CALLBACK_TIMEOUT);

        assertThat(mOtCtl.getCountryCode()).isEqualTo("CN");
    }

    @Test
    public void udp_appStartEchoServer_endDeviceUdpEchoSuccess() throws Exception {
        // Topology:
        //   Test App ------ thread-wpan ------ End Device

        mController.joinAndWait(DEFAULT_DATASET);
        startFtdChild(mFtd, DEFAULT_DATASET);
        final Inet6Address serverAddress = mOtCtl.getMeshLocalAddresses().get(0);
        final int serverPort = 9527;

        mExecutor.execute(() -> startUdpEchoServerAndWait(serverAddress, serverPort));
        mFtd.udpOpen();
        mFtd.udpSend("Hello,Thread", serverAddress, serverPort);
        String udpReply = mFtd.udpReceive();

        assertThat(udpReply).isEqualTo("Hello,Thread");
    }

    @Test
    public void joinNetworkWithBrDisabled_meshLocalAddressesArePreferred() throws Exception {
        // When BR feature is disabled, there is no OMR address, so the mesh-local addresses are
        // expected to be preferred.
        mOtCtl.executeCommand("br disable");
        mController.joinAndWait(DEFAULT_DATASET);

        IpPrefix meshLocalPrefix = DEFAULT_DATASET.getMeshLocalPrefix();
        List<LinkAddress> linkAddresses = getIpv6LinkAddresses("thread-wpan");
        for (LinkAddress address : linkAddresses) {
            if (meshLocalPrefix.contains(address.getAddress())) {
                assertThat(address.getDeprecationTime())
                        .isGreaterThan(SystemClock.elapsedRealtime());
                assertThat(address.isPreferred()).isTrue();
            }
        }

        mOtCtl.executeCommand("br enable");
    }

    @Test
    public void joinNetwork_tunInterfaceJoinsAllRouterMulticastGroup() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        assertTunInterfaceMemberOfGroup(GROUP_ADDR_ALL_ROUTERS);
    }

    // TODO (b/323300829): add more tests for integration with linux platform and
    // ConnectivityService

    private static String runThreadCommand(String cmd) {
        return runShellCommandOrThrow("cmd thread_network " + cmd);
    }

    private void startFtdChild(FullThreadDevice ftd, ActiveOperationalDataset activeDataset)
            throws Exception {
        ftd.factoryReset();
        ftd.joinNetwork(activeDataset);
        ftd.waitForStateAnyOf(List.of("router", "child"), Duration.ofSeconds(8));
    }

    /**
     * Starts a UDP echo server and replies to the first UDP message.
     *
     * <p>This method exits when the first UDP message is received and the reply is sent
     */
    private void startUdpEchoServerAndWait(InetAddress serverAddress, int serverPort) {
        try (var udpServerSocket = new DatagramSocket(serverPort, serverAddress)) {
            DatagramPacket recvPacket =
                    new DatagramPacket(new byte[UDP_BUFFER_SIZE], UDP_BUFFER_SIZE);
            udpServerSocket.receive(recvPacket);
            byte[] sendBuffer = Arrays.copyOf(recvPacket.getData(), recvPacket.getData().length);
            udpServerSocket.send(
                    new DatagramPacket(
                            sendBuffer,
                            sendBuffer.length,
                            (Inet6Address) recvPacket.getAddress(),
                            recvPacket.getPort()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertTunInterfaceMemberOfGroup(Inet6Address address) throws Exception {
        waitFor(() -> isInMulticastGroup(TUN_IF_NAME, address), TUN_ADDR_UPDATE_TIMEOUT);
    }
}
