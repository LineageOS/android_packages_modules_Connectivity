/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import static com.android.testutils.ContextUtils.mockService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetd;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.TetheringManager;
import android.net.TetheringManager.TetheringEventCallback;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import com.android.net.module.util.ArrayTrackRecord;
import com.android.server.connectivity.mdns.MdnsSocketProvider.Dependencies;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class MdnsSocketProviderTest {
    private static final String TEST_IFACE_NAME = "test";
    private static final String LOCAL_ONLY_IFACE_NAME = "local_only";
    private static final String TETHERED_IFACE_NAME = "tethered";
    private static final long DEFAULT_TIMEOUT = 2000L;
    private static final long NO_CALLBACK_TIMEOUT = 200L;
    private static final LinkAddress LINKADDRV4 = new LinkAddress("192.0.2.0/24");
    private static final LinkAddress LINKADDRV6 =
            new LinkAddress("2001:0db8:85a3:0000:0000:8a2e:0370:7334/64");
    private static final Network TEST_NETWORK = new Network(123);
    private static final Network LOCAL_NETWORK = new Network(INetd.LOCAL_NET_ID);

    @Mock private Context mContext;
    @Mock private Dependencies mDeps;
    @Mock private ConnectivityManager mCm;
    @Mock private TetheringManager mTm;
    @Mock private NetworkInterfaceWrapper mTestNetworkIfaceWrapper;
    @Mock private NetworkInterfaceWrapper mLocalOnlyIfaceWrapper;
    @Mock private NetworkInterfaceWrapper mTetheredIfaceWrapper;
    private Handler mHandler;
    private MdnsSocketProvider mSocketProvider;
    private NetworkCallback mNetworkCallback;
    private TetheringEventCallback mTetheringEventCallback;

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);
        mockService(mContext, ConnectivityManager.class, Context.CONNECTIVITY_SERVICE, mCm);
        mockService(mContext, TetheringManager.class, Context.TETHERING_SERVICE, mTm);
        doReturn(true).when(mDeps).canScanOnInterface(any());
        doReturn(mTestNetworkIfaceWrapper).when(mDeps).getNetworkInterfaceByName(TEST_IFACE_NAME);
        doReturn(mLocalOnlyIfaceWrapper).when(mDeps)
                .getNetworkInterfaceByName(LOCAL_ONLY_IFACE_NAME);
        doReturn(mTetheredIfaceWrapper).when(mDeps).getNetworkInterfaceByName(TETHERED_IFACE_NAME);
        doReturn(mock(MdnsInterfaceSocket.class))
                .when(mDeps).createMdnsInterfaceSocket(any(), anyInt());
        final HandlerThread thread = new HandlerThread("MdnsSocketProviderTest");
        thread.start();
        mHandler = new Handler(thread.getLooper());

        final ArgumentCaptor<NetworkCallback> nwCallbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        final ArgumentCaptor<TetheringEventCallback> teCallbackCaptor =
                ArgumentCaptor.forClass(TetheringEventCallback.class);
        mSocketProvider = new MdnsSocketProvider(mContext, thread.getLooper(), mDeps);
        mHandler.post(mSocketProvider::startMonitoringSockets);
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mCm).registerNetworkCallback(any(), nwCallbackCaptor.capture(), any());
        verify(mTm).registerTetheringEventCallback(any(), teCallbackCaptor.capture());

        mNetworkCallback = nwCallbackCaptor.getValue();
        mTetheringEventCallback = teCallbackCaptor.getValue();
    }

    private class TestSocketCallback implements MdnsSocketProvider.SocketCallback {
        private class SocketEvent {
            public final Network mNetwork;
            public final List<LinkAddress> mAddresses;

            SocketEvent(Network network, List<LinkAddress> addresses) {
                mNetwork = network;
                mAddresses = Collections.unmodifiableList(addresses);
            }
        }

        private class SocketCreatedEvent extends SocketEvent {
            SocketCreatedEvent(Network nw, List<LinkAddress> addresses) {
                super(nw, addresses);
            }
        }

        private class InterfaceDestroyedEvent extends SocketEvent {
            InterfaceDestroyedEvent(Network nw, List<LinkAddress> addresses) {
                super(nw, addresses);
            }
        }

        private class AddressesChangedEvent extends SocketEvent {
            AddressesChangedEvent(Network nw, List<LinkAddress> addresses) {
                super(nw, addresses);
            }
        }

        private final ArrayTrackRecord<SocketEvent>.ReadHead mHistory =
                new ArrayTrackRecord<SocketEvent>().newReadHead();

        @Override
        public void onSocketCreated(Network network, MdnsInterfaceSocket socket,
                List<LinkAddress> addresses) {
            mHistory.add(new SocketCreatedEvent(network, addresses));
        }

        @Override
        public void onInterfaceDestroyed(Network network, MdnsInterfaceSocket socket) {
            mHistory.add(new InterfaceDestroyedEvent(network, List.of()));
        }

        @Override
        public void onAddressesChanged(Network network, List<LinkAddress> addresses) {
            mHistory.add(new AddressesChangedEvent(network, addresses));
        }

        public void expectedSocketCreatedForNetwork(Network network, List<LinkAddress> addresses) {
            final SocketEvent event = mHistory.poll(DEFAULT_TIMEOUT, c -> true);
            assertNotNull(event);
            assertTrue(event instanceof SocketCreatedEvent);
            assertEquals(network, event.mNetwork);
            assertEquals(addresses, event.mAddresses);
        }

        public void expectedInterfaceDestroyedForNetwork(Network network) {
            final SocketEvent event = mHistory.poll(DEFAULT_TIMEOUT, c -> true);
            assertNotNull(event);
            assertTrue(event instanceof InterfaceDestroyedEvent);
            assertEquals(network, event.mNetwork);
        }

        public void expectedAddressesChangedForNetwork(Network network,
                List<LinkAddress> addresses) {
            final SocketEvent event = mHistory.poll(DEFAULT_TIMEOUT, c -> true);
            assertNotNull(event);
            assertTrue(event instanceof AddressesChangedEvent);
            assertEquals(network, event.mNetwork);
            assertEquals(event.mAddresses, addresses);
        }

        public void expectedNoCallback() {
            final SocketEvent event = mHistory.poll(NO_CALLBACK_TIMEOUT, c -> true);
            assertNull(event);
        }
    }

    @Test
    public void testSocketRequestAndUnrequestSocket() {
        final TestSocketCallback testCallback1 = new TestSocketCallback();
        mHandler.post(() -> mSocketProvider.requestSocket(TEST_NETWORK, testCallback1));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        testCallback1.expectedNoCallback();

        final LinkProperties testLp = new LinkProperties();
        testLp.setInterfaceName(TEST_IFACE_NAME);
        testLp.setLinkAddresses(List.of(LINKADDRV4));
        mHandler.post(() -> mNetworkCallback.onLinkPropertiesChanged(TEST_NETWORK, testLp));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mTestNetworkIfaceWrapper).getNetworkInterface();
        testCallback1.expectedSocketCreatedForNetwork(TEST_NETWORK, List.of(LINKADDRV4));

        final TestSocketCallback testCallback2 = new TestSocketCallback();
        mHandler.post(() -> mSocketProvider.requestSocket(TEST_NETWORK, testCallback2));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        testCallback1.expectedNoCallback();
        testCallback2.expectedSocketCreatedForNetwork(TEST_NETWORK, List.of(LINKADDRV4));

        final TestSocketCallback testCallback3 = new TestSocketCallback();
        mHandler.post(() -> mSocketProvider.requestSocket(null /* network */, testCallback3));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        testCallback1.expectedNoCallback();
        testCallback2.expectedNoCallback();
        testCallback3.expectedSocketCreatedForNetwork(TEST_NETWORK, List.of(LINKADDRV4));

        mHandler.post(() -> mTetheringEventCallback.onLocalOnlyInterfacesChanged(
                List.of(LOCAL_ONLY_IFACE_NAME)));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mLocalOnlyIfaceWrapper).getNetworkInterface();
        testCallback1.expectedNoCallback();
        testCallback2.expectedNoCallback();
        testCallback3.expectedSocketCreatedForNetwork(LOCAL_NETWORK, List.of());

        mHandler.post(() -> mTetheringEventCallback.onTetheredInterfacesChanged(
                List.of(TETHERED_IFACE_NAME)));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mTetheredIfaceWrapper).getNetworkInterface();
        testCallback1.expectedNoCallback();
        testCallback2.expectedNoCallback();
        testCallback3.expectedSocketCreatedForNetwork(LOCAL_NETWORK, List.of());

        mHandler.post(() -> mSocketProvider.unrequestSocket(testCallback1));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        testCallback1.expectedNoCallback();
        testCallback2.expectedNoCallback();
        testCallback3.expectedNoCallback();

        mHandler.post(() -> mNetworkCallback.onLost(TEST_NETWORK));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        testCallback1.expectedNoCallback();
        testCallback2.expectedInterfaceDestroyedForNetwork(TEST_NETWORK);
        testCallback3.expectedInterfaceDestroyedForNetwork(TEST_NETWORK);

        mHandler.post(() -> mTetheringEventCallback.onLocalOnlyInterfacesChanged(List.of()));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        testCallback1.expectedNoCallback();
        testCallback2.expectedNoCallback();
        testCallback3.expectedInterfaceDestroyedForNetwork(LOCAL_NETWORK);

        mHandler.post(() -> mSocketProvider.unrequestSocket(testCallback3));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        testCallback1.expectedNoCallback();
        testCallback2.expectedNoCallback();
        testCallback3.expectedNoCallback();
    }

    @Test
    public void testAddressesChanged() throws Exception {
        final TestSocketCallback testCallback = new TestSocketCallback();
        mHandler.post(() -> mSocketProvider.requestSocket(TEST_NETWORK, testCallback));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        testCallback.expectedNoCallback();

        final LinkProperties testLp = new LinkProperties();
        testLp.setInterfaceName(TEST_IFACE_NAME);
        testLp.setLinkAddresses(List.of(LINKADDRV4));
        mHandler.post(() -> mNetworkCallback.onLinkPropertiesChanged(TEST_NETWORK, testLp));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mTestNetworkIfaceWrapper, times(1)).getNetworkInterface();
        testCallback.expectedSocketCreatedForNetwork(TEST_NETWORK, List.of(LINKADDRV4));

        final LinkProperties newTestLp = new LinkProperties();
        newTestLp.setInterfaceName(TEST_IFACE_NAME);
        newTestLp.setLinkAddresses(List.of(LINKADDRV4, LINKADDRV6));
        mHandler.post(() -> mNetworkCallback.onLinkPropertiesChanged(TEST_NETWORK, newTestLp));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mTestNetworkIfaceWrapper, times(1)).getNetworkInterface();
        testCallback.expectedAddressesChangedForNetwork(
                TEST_NETWORK, List.of(LINKADDRV4, LINKADDRV6));
    }
}
