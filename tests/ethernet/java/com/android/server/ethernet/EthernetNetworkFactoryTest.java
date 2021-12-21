/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.ethernet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.EthernetNetworkSpecifier;
import android.net.IpConfiguration;
import android.net.LinkProperties;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.ip.IIpClient;
import android.net.ip.IpClientCallbacks;
import android.os.Handler;
import android.os.Looper;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.net.module.util.InterfaceParams;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class EthernetNetworkFactoryTest {
    private TestLooper mLooper = new TestLooper();
    private Handler mHandler;
    private EthernetNetworkFactory mNetFactory = null;
    private IpClientCallbacks mIpClientCallbacks;
    private int mNetworkRequestCount = 0;
    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private EthernetNetworkFactory.Dependencies mDeps;
    @Mock private IIpClient mIpClient;
    @Mock private EthernetNetworkAgent mNetworkAgent;
    @Mock private InterfaceParams mInterfaceParams;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandler = new Handler(mLooper.getLooper());
        mNetworkRequestCount = 0;

        mNetFactory = new EthernetNetworkFactory(mHandler, mContext, createDefaultFilterCaps(),
                mDeps);

        setupNetworkAgentMock();
        setupIpClientMock();
        setupContext();
    }

    private void setupNetworkAgentMock() {
        when(mDeps.makeEthernetNetworkAgent(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(new AnswerWithArguments() {
                                       public EthernetNetworkAgent answer(
                                               Context context,
                                               Looper looper,
                                               NetworkCapabilities nc,
                                               LinkProperties lp,
                                               NetworkAgentConfig config,
                                               NetworkProvider provider,
                                               EthernetNetworkAgent.Callbacks cb) {
                                           when(mNetworkAgent.getCallbacks()).thenReturn(cb);
                                           return mNetworkAgent;
                                       }
                                   }
        );
    }

    private void setupIpClientMock() throws Exception {
        doAnswer(inv -> {
            // these tests only support one concurrent IpClient, so make sure we do not accidentally
            // create a mess.
            assertNull("An IpClient has already been created.", mIpClientCallbacks);

            mIpClientCallbacks = inv.getArgument(2);
            mIpClientCallbacks.onIpClientCreated(mIpClient);
            mLooper.dispatchAll();
            return null;
        }).when(mDeps).makeIpClient(any(Context.class), anyString(), any());

        doAnswer(inv -> {
            mIpClientCallbacks.onQuit();
            mLooper.dispatchAll();
            mIpClientCallbacks = null;
            return null;
        }).when(mIpClient).shutdown();
    }

    private void setupContext() {
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getString(R.string.config_ethernet_tcp_buffers)).thenReturn(
                "524288,1048576,3145728,524288,1048576,2097152");
    }

    @After
    public void tearDown() {
        // looper is shared with the network agents, so there may still be messages to dispatch on
        // tear down.
        mLooper.dispatchAll();
    }

    private NetworkCapabilities createDefaultFilterCaps() {
        return NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
    }

    private NetworkCapabilities.Builder createInterfaceCapsBuilder(final int transportType) {
        return new NetworkCapabilities.Builder()
                .addTransportType(transportType)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED);
    }

    private NetworkRequest.Builder createDefaultRequestBuilder() {
        return new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private NetworkRequest createDefaultRequest() {
        return createDefaultRequestBuilder().build();
    }

    private IpConfiguration createDefaultIpConfig() {
        IpConfiguration ipConfig = new IpConfiguration();
        ipConfig.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
        ipConfig.setProxySettings(IpConfiguration.ProxySettings.NONE);
        return ipConfig;
    }

    // creates an interface with provisioning in progress (since updating the interface link state
    // automatically starts the provisioning process)
    private void createInterfaceUndergoingProvisioning(String iface) throws Exception {
        // Default to the ethernet transport type.
        createInterfaceUndergoingProvisioning(iface, NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    private void createInterfaceUndergoingProvisioning(
            @NonNull final String iface, final int transportType) throws Exception {
        mNetFactory.addInterface(iface, iface, createInterfaceCapsBuilder(transportType).build(),
                createDefaultIpConfig());
        assertTrue(mNetFactory.updateInterfaceLinkState(iface, true));
        verify(mDeps).makeIpClient(any(Context.class), anyString(), any());
        verify(mIpClient).startProvisioning(any());
        clearInvocations(mDeps);
        clearInvocations(mIpClient);
    }

    // creates a provisioned interface
    private void createAndVerifyProvisionedInterface(String iface) throws Exception {
        // Default to the ethernet transport type.
        createAndVerifyProvisionedInterface(iface, NetworkCapabilities.TRANSPORT_ETHERNET,
                ConnectivityManager.TYPE_ETHERNET);
    }

    private void createAndVerifyProvisionedInterface(
            @NonNull final String iface, final int transportType, final int expectedLegacyType)
            throws Exception {
        createInterfaceUndergoingProvisioning(iface, transportType);
        mIpClientCallbacks.onProvisioningSuccess(new LinkProperties());
        mLooper.dispatchAll();
        // provisioning succeeded, verify that the network agent is created, registered, marked
        // as connected and legacy type are correctly set.
        verify(mDeps).makeEthernetNetworkAgent(any(), any(), any(), any(),
                argThat(x -> x.getLegacyType() == expectedLegacyType), any(), any());
        verify(mNetworkAgent).register();
        verify(mNetworkAgent).markConnected();
        clearInvocations(mDeps);
        clearInvocations(mNetworkAgent);
    }

    // creates an unprovisioned interface
    private void createUnprovisionedInterface(String iface) throws Exception {
        // the only way to create an unprovisioned interface is by calling needNetworkFor
        // followed by releaseNetworkFor which will stop the NetworkAgent and IpClient. When
        // EthernetNetworkFactory#updateInterfaceLinkState(iface, true) is called, the interface
        // is automatically provisioned even if nobody has ever called needNetworkFor
        createAndVerifyProvisionedInterface(iface);

        // Interface is already provisioned, so startProvisioning / register should not be called
        // again
        mNetFactory.needNetworkFor(createDefaultRequest());
        verify(mIpClient, never()).startProvisioning(any());
        verify(mNetworkAgent, never()).register();

        mNetFactory.releaseNetworkFor(createDefaultRequest());
        verify(mIpClient).shutdown();
        verify(mNetworkAgent).unregister();

        clearInvocations(mIpClient);
        clearInvocations(mNetworkAgent);
    }

    @Test
    public void testAcceptRequest() throws Exception {
        createInterfaceUndergoingProvisioning("eth0");
        assertTrue(mNetFactory.acceptRequest(createDefaultRequest()));

        NetworkRequest wifiRequest = createDefaultRequestBuilder()
                .removeTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        assertFalse(mNetFactory.acceptRequest(wifiRequest));
    }

    @Test
    public void testUpdateInterfaceLinkStateForActiveProvisioningInterface() throws Exception {
        String iface = "eth0";
        createInterfaceUndergoingProvisioning(iface);
        // verify that the IpClient gets shut down when interface state changes to down.
        assertTrue(mNetFactory.updateInterfaceLinkState(iface, false));
        verify(mIpClient).shutdown();
    }

    @Test
    public void testUpdateInterfaceLinkStateForProvisionedInterface() throws Exception {
        String iface = "eth0";
        createAndVerifyProvisionedInterface(iface);
        assertTrue(mNetFactory.updateInterfaceLinkState(iface, false));
        verify(mIpClient).shutdown();
        verify(mNetworkAgent).unregister();
    }

    @Test
    public void testUpdateInterfaceLinkStateForUnprovisionedInterface() throws Exception {
        String iface = "eth0";
        createUnprovisionedInterface(iface);
        assertTrue(mNetFactory.updateInterfaceLinkState(iface, false));
        // There should not be an active IPClient or NetworkAgent.
        verify(mDeps, never()).makeIpClient(any(), any(), any());
        verify(mDeps, never())
                .makeEthernetNetworkAgent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void testUpdateInterfaceLinkStateForNonExistingInterface() throws Exception {
        // if interface was never added, link state cannot be updated.
        assertFalse(mNetFactory.updateInterfaceLinkState("eth1", true));
        verify(mDeps, never()).makeIpClient(any(), any(), any());
    }

    @Test
    public void testNeedNetworkForOnProvisionedInterface() throws Exception {
        createAndVerifyProvisionedInterface("eth0");
        mNetFactory.needNetworkFor(createDefaultRequest());
        verify(mIpClient, never()).startProvisioning(any());
    }

    @Test
    public void testNeedNetworkForOnUnprovisionedInterface() throws Exception {
        createUnprovisionedInterface("eth0");
        mNetFactory.needNetworkFor(createDefaultRequest());
        verify(mIpClient).startProvisioning(any());

        mIpClientCallbacks.onProvisioningSuccess(new LinkProperties());
        mLooper.dispatchAll();
        verify(mNetworkAgent).register();
        verify(mNetworkAgent).markConnected();
    }

    @Test
    public void testNeedNetworkForOnInterfaceUndergoingProvisioning() throws Exception {
        createInterfaceUndergoingProvisioning("eth0");
        mNetFactory.needNetworkFor(createDefaultRequest());
        verify(mIpClient, never()).startProvisioning(any());

        mIpClientCallbacks.onProvisioningSuccess(new LinkProperties());
        mLooper.dispatchAll();
        verify(mNetworkAgent).register();
        verify(mNetworkAgent).markConnected();
    }

    @Test
    public void testProvisioningLoss() throws Exception {
        String iface = "eth0";
        when(mDeps.getNetworkInterfaceByName(iface)).thenReturn(mInterfaceParams);
        createAndVerifyProvisionedInterface(iface);

        mIpClientCallbacks.onProvisioningFailure(new LinkProperties());
        mLooper.dispatchAll();
        verify(mIpClient).shutdown();
        verify(mNetworkAgent).unregister();
        // provisioning loss should trigger a retry, since the interface is still there
        verify(mIpClient).startProvisioning(any());
    }

    @Test
    public void testProvisioningLossForDisappearedInterface() throws Exception {
        String iface = "eth0";
        // mocked method returns null by default, but just to be explicit in the test:
        when(mDeps.getNetworkInterfaceByName(eq(iface))).thenReturn(null);

        createAndVerifyProvisionedInterface(iface);
        mIpClientCallbacks.onProvisioningFailure(new LinkProperties());
        mLooper.dispatchAll();
        verify(mIpClient).shutdown();
        verify(mNetworkAgent).unregister();
        // the interface disappeared and getNetworkInterfaceByName returns null, we should not retry
        verify(mIpClient, never()).startProvisioning(any());
    }

    @Test
    public void testIpClientIsNotStartedWhenLinkIsDown() throws Exception {
        String iface = "eth0";
        createUnprovisionedInterface(iface);
        mNetFactory.updateInterfaceLinkState(iface, false);

        mNetFactory.needNetworkFor(createDefaultRequest());

        NetworkRequest specificNetRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .setNetworkSpecifier(new EthernetNetworkSpecifier(iface))
                .build();
        mNetFactory.needNetworkFor(specificNetRequest);

        // TODO(b/155707957): BUG: IPClient should not be started when the interface link state
        //  is down.
        verify(mDeps).makeIpClient(any(), any(), any());
    }

    @Test
    public void testLinkPropertiesChanged() throws Exception {
        createAndVerifyProvisionedInterface("eth0");

        LinkProperties lp = new LinkProperties();
        mIpClientCallbacks.onLinkPropertiesChange(lp);
        mLooper.dispatchAll();
        verify(mNetworkAgent).sendLinkPropertiesImpl(same(lp));
    }

    @Test
    public void testNetworkUnwanted() throws Exception {
        createAndVerifyProvisionedInterface("eth0");

        mNetworkAgent.getCallbacks().onNetworkUnwanted();
        mLooper.dispatchAll();
        verify(mIpClient).shutdown();
        verify(mNetworkAgent).unregister();
    }

    @Test
    public void testNetworkUnwantedWithStaleNetworkAgent() throws Exception {
        String iface = "eth0";
        // ensures provisioning is restarted after provisioning loss
        when(mDeps.getNetworkInterfaceByName(iface)).thenReturn(mInterfaceParams);
        createAndVerifyProvisionedInterface(iface);

        EthernetNetworkAgent.Callbacks oldCbs = mNetworkAgent.getCallbacks();
        // replace network agent in EthernetNetworkFactory
        // Loss of provisioning will restart the ip client and network agent.
        mIpClientCallbacks.onProvisioningFailure(new LinkProperties());
        mLooper.dispatchAll();
        verify(mDeps).makeIpClient(any(), any(), any());

        mIpClientCallbacks.onProvisioningSuccess(new LinkProperties());
        mLooper.dispatchAll();
        verify(mDeps).makeEthernetNetworkAgent(any(), any(), any(), any(), any(), any(), any());

        // verify that unwanted is ignored
        clearInvocations(mIpClient);
        clearInvocations(mNetworkAgent);
        oldCbs.onNetworkUnwanted();
        verify(mIpClient, never()).shutdown();
        verify(mNetworkAgent, never()).unregister();
    }

    @Test
    public void testTransportOverrideIsCorrectlySet() throws Exception {
        final String iface = "eth0";
        // createProvisionedInterface() has verifications in place for transport override
        // functionality which for EthernetNetworkFactory is network score and legacy type mappings.
        createAndVerifyProvisionedInterface(iface, NetworkCapabilities.TRANSPORT_ETHERNET,
                ConnectivityManager.TYPE_ETHERNET);
        mNetFactory.removeInterface(iface);
        createAndVerifyProvisionedInterface(iface, NetworkCapabilities.TRANSPORT_BLUETOOTH,
                ConnectivityManager.TYPE_BLUETOOTH);
        mNetFactory.removeInterface(iface);
        createAndVerifyProvisionedInterface(iface, NetworkCapabilities.TRANSPORT_WIFI,
                ConnectivityManager.TYPE_WIFI);
        mNetFactory.removeInterface(iface);
        createAndVerifyProvisionedInterface(iface, NetworkCapabilities.TRANSPORT_CELLULAR,
                ConnectivityManager.TYPE_MOBILE);
        mNetFactory.removeInterface(iface);
        createAndVerifyProvisionedInterface(iface, NetworkCapabilities.TRANSPORT_LOWPAN,
                ConnectivityManager.TYPE_NONE);
        mNetFactory.removeInterface(iface);
        createAndVerifyProvisionedInterface(iface, NetworkCapabilities.TRANSPORT_WIFI_AWARE,
                ConnectivityManager.TYPE_NONE);
        mNetFactory.removeInterface(iface);
        createAndVerifyProvisionedInterface(iface, NetworkCapabilities.TRANSPORT_TEST,
                ConnectivityManager.TYPE_NONE);
        mNetFactory.removeInterface(iface);
    }

    @Test
    public void testReachabilityLoss() throws Exception {
        String iface = "eth0";
        createAndVerifyProvisionedInterface(iface);

        mIpClientCallbacks.onReachabilityLost("ReachabilityLost");
        mLooper.dispatchAll();

        // Reachability loss should trigger a stop and start, since the interface is still there
        verify(mIpClient).shutdown();
        verify(mNetworkAgent).unregister();

        verify(mDeps).makeIpClient(any(Context.class), anyString(), any());
        verify(mIpClient).startProvisioning(any());
    }
}
