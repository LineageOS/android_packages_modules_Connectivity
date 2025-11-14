/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.connectivity.mdns;

import static android.net.InetAddresses.parseNumericAddress;
import static android.net.RouteInfo.RTN_UNICAST;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.os.Build;

import com.android.net.module.util.SharedLog;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.NetworkInterface;
import java.util.List;

/** Tests for {@link ConnectivityMonitor}. */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class ConnectivityMonitorWithConnectivityManagerTests {
    @Mock private Context mContext;
    @Mock private ConnectivityMonitor.Listener mockListener;
    @Mock private ConnectivityManager mConnectivityManager;
    @Mock private SharedLog sharedLog;

    private ConnectivityMonitorWithConnectivityManager monitor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mConnectivityManager).when(mContext)
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        monitor = new ConnectivityMonitorWithConnectivityManager(mContext, mockListener, sharedLog);
    }

    @Test
    public void testInitialState_shouldNotRegisterNetworkCallback() {
        verifyNetworkCallbackRegistered(0 /* time */);
        verifyNetworkCallbackUnregistered(0 /* time */);
    }

    @Test
    public void testStartDiscovery_shouldRegisterNetworkCallback() {
        monitor.startWatchingConnectivityChanges();

        verifyNetworkCallbackRegistered(1 /* time */);
        verifyNetworkCallbackUnregistered(0 /* time */);
    }

    @Test
    public void testStartDiscoveryTwice_shouldRegisterOneNetworkCallback() {
        monitor.startWatchingConnectivityChanges();
        monitor.startWatchingConnectivityChanges();

        verifyNetworkCallbackRegistered(1 /* time */);
        verifyNetworkCallbackUnregistered(0 /* time */);
    }

    @Test
    public void testStopDiscovery_shouldUnregisterNetworkCallback() {
        monitor.startWatchingConnectivityChanges();
        monitor.stopWatchingConnectivityChanges();

        verifyNetworkCallbackRegistered(1 /* time */);
        verifyNetworkCallbackUnregistered(1 /* time */);
    }

    @Test
    public void testStopDiscoveryTwice_shouldUnregisterNetworkCallback() {
        monitor.startWatchingConnectivityChanges();
        monitor.stopWatchingConnectivityChanges();

        verifyNetworkCallbackRegistered(1 /* time */);
        verifyNetworkCallbackUnregistered(1 /* time */);
    }

    @Test
    public void testIntentFired_shouldNotifyListener() {
        InOrder inOrder = inOrder(mockListener);
        final NetworkCallback callback = setupCallback();
        final Network testNetwork = mock(Network.class);

        // Simulate network available.
        callback.onLinkPropertiesChanged(testNetwork, new LinkProperties());
        inOrder.verify(mockListener).onConnectivityChanged();

        // Simulate network lost.
        callback.onLost(testNetwork);
        inOrder.verify(mockListener).onConnectivityChanged();

        // Simulate network unavailable.
        callback.onUnavailable();
        inOrder.verify(mockListener).onConnectivityChanged();
    }

    private NetworkCallback setupCallback() {
        monitor.startWatchingConnectivityChanges();
        final ArgumentCaptor<NetworkCallback> callbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        verify(mConnectivityManager, times(1)).registerNetworkCallback(
                any(NetworkRequest.class), callbackCaptor.capture());

        return callbackCaptor.getValue();
    }

    @Test
    public void testGuessNetworkOfRemoteHost_ipv4Address() {
        final NetworkCallback callback = setupCallback();

        final Network testNetwork1 = mock(Network.class);
        final Network testNetwork2 = mock(Network.class);
        final int ifIndex1 = 1;
        final NetworkInterfaceWrapper iface1 = getTestInterface("iface1", ifIndex1);
        final NetworkInterfaceWrapper iface2 = getTestInterface("iface2", 2);

        final LinkProperties lp1 = new LinkProperties();
        lp1.setInterfaceName("iface1");
        lp1.addRoute(new RouteInfo(
                new IpPrefix("192.0.1.123/24"), null, lp1.getInterfaceName(), RTN_UNICAST));
        lp1.addRoute(new RouteInfo(
                new IpPrefix("0.0.0.0/0"), parseNumericAddress("192.0.1.1"),
                lp1.getInterfaceName(), RTN_UNICAST));
        final LinkProperties lp2 = new LinkProperties();
        lp2.setInterfaceName("iface2");
        lp2.addRoute(new RouteInfo(
                new IpPrefix("192.0.2.123/24"), null, lp2.getInterfaceName(), RTN_UNICAST));
        lp2.addRoute(new RouteInfo(
                new IpPrefix("0.0.0.0/0"), parseNumericAddress("192.0.2.1"),
                lp2.getInterfaceName(), RTN_UNICAST));

        callback.onLinkPropertiesChanged(testNetwork1, lp1);
        callback.onLinkPropertiesChanged(testNetwork2, lp2);

        assertEquals(new SocketKey(testNetwork1, ifIndex1), monitor.guessNetworkOfRemoteHost(
                List.of(iface1, iface2), parseNumericAddress("192.0.1.124")));
    }

    @Test
    public void testGuessNetworkOfRemoteHost_ipv4LinkLocalAddress() {
        final NetworkCallback callback = setupCallback();

        final Network testNetwork = mock(Network.class);
        final int ifIndex = 1;
        final NetworkInterfaceWrapper iface = getTestInterface("iface1", ifIndex);

        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("iface1");
        lp.addRoute(new RouteInfo(
                new IpPrefix("0.0.0.0/0"), parseNumericAddress("192.0.1.1"), lp.getInterfaceName(),
                RTN_UNICAST));
        lp.addRoute(new RouteInfo(
                new IpPrefix("169.254.0.0/16"), null, lp.getInterfaceName(), RTN_UNICAST));

        callback.onLinkPropertiesChanged(testNetwork, lp);

        assertEquals(new SocketKey(testNetwork, ifIndex), monitor.guessNetworkOfRemoteHost(
                List.of(iface), parseNumericAddress("169.254.1.2")));
    }

    @Test
    public void testGuessNetworkOfRemoteHost_inet6LinkLocalAddress() throws Exception {
        final NetworkCallback callback = setupCallback();

        final Network testNetwork1 = mock(Network.class);
        final Network testNetwork2 = mock(Network.class);
        final NetworkInterfaceWrapper loIface = new NetworkInterfaceWrapper(
                NetworkInterface.getByName("lo"));
        final NetworkInterfaceWrapper wrongIface = getTestInterface(
                "wrongiface", loIface.getIndex() + 1);

        final LinkProperties lp1 = new LinkProperties();
        lp1.setInterfaceName("wrongiface");
        lp1.addRoute(new RouteInfo(
                new IpPrefix("fe80::123/64"), null, lp1.getInterfaceName(), RTN_UNICAST));
        lp1.addRoute(new RouteInfo(
                new IpPrefix("::/0"), parseNumericAddress("fe80::111"), lp1.getInterfaceName(),
                RTN_UNICAST));
        final LinkProperties lp2 = new LinkProperties();
        // Use an interface that is known to exist so the link-local address scope can be parsed
        lp2.setInterfaceName("lo");

        callback.onLinkPropertiesChanged(testNetwork1, lp1);
        callback.onLinkPropertiesChanged(testNetwork2, lp2);

        assertEquals(new SocketKey(testNetwork2, loIface.getIndex()),
                monitor.guessNetworkOfRemoteHost(List.of(wrongIface, loIface),
                        parseNumericAddress("fe80::124%lo")));
    }

    private NetworkInterfaceWrapper getTestInterface(String name, int index) {
        NetworkInterface iface = mock(NetworkInterface.class);
        doReturn(name).when(iface).getName();
        doReturn(index).when(iface).getIndex();
        return new NetworkInterfaceWrapper(iface);
    }

    private void verifyNetworkCallbackRegistered(int time) {
        verify(mConnectivityManager, times(time)).registerNetworkCallback(
                any(NetworkRequest.class), any(NetworkCallback.class));
    }

    private void verifyNetworkCallbackUnregistered(int time) {
        verify(mConnectivityManager, times(time))
                .unregisterNetworkCallback(any(NetworkCallback.class));
    }
}