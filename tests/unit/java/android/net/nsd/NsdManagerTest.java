/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.nsd;

import static android.net.InetAddresses.parseNumericAddress;
import static android.net.nsd.NsdManager.checkServiceInfoForRegistration;

import static com.android.net.module.util.HexDump.hexStringToByteArray;

import static libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import static libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.Context;
import android.net.connectivity.ConnectivityCompatChanges;
import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.build.SdkLevel;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.FunctionalUtils.ThrowingConsumer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.time.Duration;

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class NsdManagerTest {

    static final int PROTOCOL = NsdManager.PROTOCOL_DNS_SD;

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Mock Context mContext;
    @Mock INsdManager mService;
    @Mock INsdServiceConnector mServiceConn;

    NsdManager mManager;
    INsdManagerCallback mCallback;

    long mTimeoutMs = 200; // non-final so that tests can adjust the value.

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        doReturn(mServiceConn).when(mService).connect(any(), anyBoolean());
        mManager = new NsdManager(mContext, mService);
        final ArgumentCaptor<INsdManagerCallback> cbCaptor = ArgumentCaptor.forClass(
                INsdManagerCallback.class);
        verify(mService).connect(cbCaptor.capture(), anyBoolean());
        mCallback = cbCaptor.getValue();
    }

    @Test
    @EnableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testResolveServiceS() throws Exception {
        verifyDaemonStarted(/* targetSdkPreS= */ false);
        doTestResolveService();
    }

    @Test
    @DisableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testResolveServicePreS() throws Exception {
        verifyDaemonStarted(/* targetSdkPreS= */ true);
        doTestResolveService();
    }

    @Test
    @EnableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testDiscoverServiceS() throws Exception {
        verifyDaemonStarted(/* targetSdkPreS= */ false);
        doTestDiscoverService();
    }

    @Test
    @DisableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testDiscoverServicePreS() throws Exception {
        verifyDaemonStarted(/* targetSdkPreS= */ true);
        doTestDiscoverService();
    }

    @Test
    @EnableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testParallelResolveServiceS() throws Exception {
        verifyDaemonStarted(/* targetSdkPreS= */ false);
        doTestParallelResolveService();
    }

    @Test
    @DisableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testParallelResolveServicePreS() throws Exception {
        verifyDaemonStarted(/* targetSdkPreS= */ true);
        doTestParallelResolveService();
    }

    @Test
    @EnableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testInvalidCallsS() throws Exception {
        verifyDaemonStarted(/* targetSdkPreS= */ false);
        doTestInvalidCalls();
    }

    @Test
    @DisableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testInvalidCallsPreS() throws Exception {
        verifyDaemonStarted(/* targetSdkPreS= */ true);
        doTestInvalidCalls();
    }

    @Test
    @EnableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testRegisterServiceS() throws Exception {
        verifyDaemonStarted(/* targetSdkPreS= */ false);
        doTestRegisterService();
    }

    @Test
    @DisableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testRegisterServicePreS() throws Exception {
        verifyDaemonStarted(/* targetSdkPreS= */ true);
        doTestRegisterService();
    }

    private void verifyDaemonStarted(boolean targetSdkPreS) throws Exception {
        if (targetSdkPreS && !SdkLevel.isAtLeastV()) {
            verify(mServiceConn).startDaemon();
        } else {
            verify(mServiceConn, never()).startDaemon();
        }
    }

    private void doTestResolveService() throws Exception {
        NsdManager manager = mManager;

        NsdServiceInfo request = new NsdServiceInfo("a_name", "a_type");
        NsdServiceInfo reply = new NsdServiceInfo("resolved_name", "resolved_type");
        NsdManager.ResolveListener listener = mock(NsdManager.ResolveListener.class);

        manager.resolveService(request, listener);
        int key1 = getRequestKey(req -> verify(mServiceConn).resolveService(req.capture(), any()));
        int err = 33;
        mCallback.onResolveServiceFailed(key1, err);
        verify(listener, timeout(mTimeoutMs).times(1)).onResolveFailed(request, err);

        manager.resolveService(request, listener);
        int key2 = getRequestKey(req ->
                verify(mServiceConn, times(2)).resolveService(req.capture(), any()));
        mCallback.onResolveServiceSucceeded(key2, reply);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceResolved(reply);
    }

    private void doTestParallelResolveService() throws Exception {
        NsdManager manager = mManager;

        NsdServiceInfo request = new NsdServiceInfo("a_name", "a_type");
        NsdServiceInfo reply = new NsdServiceInfo("resolved_name", "resolved_type");

        NsdManager.ResolveListener listener1 = mock(NsdManager.ResolveListener.class);
        NsdManager.ResolveListener listener2 = mock(NsdManager.ResolveListener.class);

        manager.resolveService(request, listener1);
        int key1 = getRequestKey(req -> verify(mServiceConn).resolveService(req.capture(), any()));

        manager.resolveService(request, listener2);
        int key2 = getRequestKey(req ->
                verify(mServiceConn, times(2)).resolveService(req.capture(), any()));

        mCallback.onResolveServiceSucceeded(key2, reply);
        mCallback.onResolveServiceSucceeded(key1, reply);

        verify(listener1, timeout(mTimeoutMs).times(1)).onServiceResolved(reply);
        verify(listener2, timeout(mTimeoutMs).times(1)).onServiceResolved(reply);
    }

    @Test
    public void testRegisterServiceWithAdvertisingRequest() throws Exception {
        final NsdManager manager = mManager;
        final NsdServiceInfo request = new NsdServiceInfo("another_name2", "another_type2");
        request.setPort(2203);
        final AdvertisingRequest advertisingRequest = new AdvertisingRequest.Builder(request,
                PROTOCOL).build();
        final NsdManager.RegistrationListener listener = mock(
                NsdManager.RegistrationListener.class);

        manager.registerService(advertisingRequest, Runnable::run, listener);
        int key4 = getRequestKey(req -> verify(mServiceConn).registerService(req.capture(), any()));
        mCallback.onRegisterServiceSucceeded(key4, request);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceRegistered(request);
    }

    @Test
    public void testRegisterServiceWithCustomTtl() throws Exception {
        final NsdManager manager = mManager;
        final NsdServiceInfo info = new NsdServiceInfo("another_name2", "another_type2");
        info.setPort(2203);
        final AdvertisingRequest request = new AdvertisingRequest.Builder(info, PROTOCOL)
                .setTtl(Duration.ofSeconds(30)).build();
        final NsdManager.RegistrationListener listener = mock(
                NsdManager.RegistrationListener.class);

        manager.registerService(request, Runnable::run, listener);

        AdvertisingRequest capturedRequest = getAdvertisingRequest(
                req -> verify(mServiceConn).registerService(anyInt(), req.capture()));
        assertEquals(request.getTtl(), capturedRequest.getTtl());
    }

    private void doTestRegisterService() throws Exception {
        NsdManager manager = mManager;

        NsdServiceInfo request1 = new NsdServiceInfo("a_name", "a_type");
        NsdServiceInfo request2 = new NsdServiceInfo("another_name", "another_type");
        request1.setPort(2201);
        request2.setPort(2202);
        NsdManager.RegistrationListener listener1 = mock(NsdManager.RegistrationListener.class);
        NsdManager.RegistrationListener listener2 = mock(NsdManager.RegistrationListener.class);

        // Register two services
        manager.registerService(request1, PROTOCOL, listener1);
        int key1 = getRequestKey(req -> verify(mServiceConn).registerService(req.capture(), any()));

        manager.registerService(request2, PROTOCOL, listener2);
        int key2 = getRequestKey(req ->
                verify(mServiceConn, times(2)).registerService(req.capture(), any()));

        // First reques fails, second request succeeds
        mCallback.onRegisterServiceSucceeded(key2, request2);
        verify(listener2, timeout(mTimeoutMs).times(1)).onServiceRegistered(request2);

        int err = 1;
        mCallback.onRegisterServiceFailed(key1, err);
        verify(listener1, timeout(mTimeoutMs).times(1)).onRegistrationFailed(request1, err);

        // Client retries first request, it succeeds
        manager.registerService(request1, PROTOCOL, listener1);
        int key3 = getRequestKey(req ->
                verify(mServiceConn, times(3)).registerService(req.capture(), any()));

        mCallback.onRegisterServiceSucceeded(key3, request1);
        verify(listener1, timeout(mTimeoutMs).times(1)).onServiceRegistered(request1);

        // First request is unregistered, it succeeds
        manager.unregisterService(listener1);
        int key3again = getRequestKey(req -> verify(mServiceConn).unregisterService(req.capture()));
        assertEquals(key3, key3again);

        mCallback.onUnregisterServiceSucceeded(key3again);
        verify(listener1, timeout(mTimeoutMs).times(1)).onServiceUnregistered(request1);

        // Second request is unregistered, it fails
        manager.unregisterService(listener2);
        int key2again = getRequestKey(req ->
                verify(mServiceConn, times(2)).unregisterService(req.capture()));
        assertEquals(key2, key2again);

        mCallback.onUnregisterServiceFailed(key2again, err);
        verify(listener2, timeout(mTimeoutMs).times(1)).onUnregistrationFailed(request2, err);

        // TODO: do not unregister listener until service is unregistered
        // Client retries unregistration of second request, it succeeds
        //manager.unregisterService(listener2);
        //int key2yetAgain = verifyRequest(NsdManager.UNREGISTER_SERVICE);
        //assertEquals(key2, key2yetAgain);

        //sendResponse(NsdManager.UNREGISTER_SERVICE_SUCCEEDED, 0, key2yetAgain, null);
        //verify(listener2, timeout(mTimeoutMs).times(1)).onServiceUnregistered(request2);
    }

    private void doTestDiscoverService() throws Exception {
        NsdManager manager = mManager;

        DiscoveryRequest request1 = new DiscoveryRequest.Builder("a_type").build();
        NsdServiceInfo reply1 = new NsdServiceInfo("a_name", "a_type");
        NsdServiceInfo reply2 = new NsdServiceInfo("another_name", "a_type");
        NsdServiceInfo reply3 = new NsdServiceInfo("a_third_name", "a_type");

        NsdManager.DiscoveryListener listener = mock(NsdManager.DiscoveryListener.class);

        // Client registers for discovery, request fails
        manager.discoverServices("a_type", PROTOCOL, listener);
        int key1 = getRequestKey(req ->
                verify(mServiceConn).discoverServices(req.capture(), any()));

        int err = 1;
        mCallback.onDiscoverServicesFailed(key1, err);
        verify(listener, timeout(mTimeoutMs).times(1)).onStartDiscoveryFailed("a_type", err);

        // Client retries, request succeeds
        manager.discoverServices("a_type", PROTOCOL, listener);
        int key2 = getRequestKey(req ->
                verify(mServiceConn, times(2)).discoverServices(req.capture(), any()));

        mCallback.onDiscoverServicesStarted(key2, request1);
        verify(listener, timeout(mTimeoutMs).times(1)).onDiscoveryStarted("a_type");


        // mdns notifies about services
        mCallback.onServiceFound(key2, reply1);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceFound(reply1);

        mCallback.onServiceFound(key2, reply2);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceFound(reply2);

        mCallback.onServiceLost(key2, reply2);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceLost(reply2);


        // Client unregisters its listener
        manager.stopServiceDiscovery(listener);
        int key2again = getRequestKey(req -> verify(mServiceConn).stopDiscovery(req.capture()));
        assertEquals(key2, key2again);

        // TODO: unregister listener immediately and stop notifying it about services
        // Notifications are still passed to the client's listener
        mCallback.onServiceLost(key2, reply1);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceLost(reply1);

        // Client is notified of complete unregistration
        mCallback.onStopDiscoverySucceeded(key2again);
        verify(listener, timeout(mTimeoutMs).times(1)).onDiscoveryStopped("a_type");

        // Notifications are not passed to the client anymore
        mCallback.onServiceFound(key2, reply3);
        verify(listener, timeout(mTimeoutMs).times(0)).onServiceLost(reply3);


        // Client registers for service discovery
        reset(listener);
        manager.discoverServices("a_type", PROTOCOL, listener);
        int key3 = getRequestKey(req ->
                verify(mServiceConn, times(3)).discoverServices(req.capture(), any()));

        mCallback.onDiscoverServicesStarted(key3, request1);
        verify(listener, timeout(mTimeoutMs).times(1)).onDiscoveryStarted("a_type");

        // Client unregisters immediately, it fails
        manager.stopServiceDiscovery(listener);
        int key3again = getRequestKey(req ->
                verify(mServiceConn, times(2)).stopDiscovery(req.capture()));
        assertEquals(key3, key3again);

        err = 2;
        mCallback.onStopDiscoveryFailed(key3again, err);
        verify(listener, timeout(mTimeoutMs).times(1)).onStopDiscoveryFailed("a_type", err);

        // New notifications are not passed to the client anymore
        mCallback.onServiceFound(key3, reply1);
        verify(listener, timeout(mTimeoutMs).times(0)).onServiceFound(reply1);
    }

    public void doTestInvalidCalls() {
        NsdManager manager = mManager;

        NsdManager.RegistrationListener listener1 = mock(NsdManager.RegistrationListener.class);
        NsdManager.DiscoveryListener listener2 = mock(NsdManager.DiscoveryListener.class);
        NsdManager.ResolveListener listener3 = mock(NsdManager.ResolveListener.class);
        NsdManager.RegistrationListener listener4 = mock(NsdManager.RegistrationListener.class);
        NsdManager.RegistrationListener listener5 = mock(NsdManager.RegistrationListener.class);
        NsdManager.RegistrationListener listener6 = mock(NsdManager.RegistrationListener.class);
        NsdManager.RegistrationListener listener7 = mock(NsdManager.RegistrationListener.class);

        NsdServiceInfo invalidService = new NsdServiceInfo(null, null);
        NsdServiceInfo validService = new NsdServiceInfo("a_name", "_a_type._tcp");
        NsdServiceInfo otherServiceWithSubtype = new NsdServiceInfo("b_name", "_a_type._tcp,_sub1");
        NsdServiceInfo validServiceDuplicate = new NsdServiceInfo("a_name", "_a_type._tcp");
        NsdServiceInfo validServiceSubtypeUpdate = new NsdServiceInfo("a_name",
                "_a_type._tcp,_sub1,_s2");
        NsdServiceInfo otherSubtypeUpdate = new NsdServiceInfo("a_name", "_a_type._tcp,_sub1,_s3");
        NsdServiceInfo dotSyntaxSubtypeUpdate = new NsdServiceInfo("a_name", "_sub1._a_type._tcp");

        validService.setPort(2222);
        otherServiceWithSubtype.setPort(2222);
        validServiceDuplicate.setPort(2222);
        validServiceSubtypeUpdate.setPort(2222);
        otherSubtypeUpdate.setPort(2222);
        dotSyntaxSubtypeUpdate.setPort(2222);

        NsdServiceInfo invalidMissingHostnameWithAddresses = new NsdServiceInfo(null, null);
        invalidMissingHostnameWithAddresses.setHostAddresses(
                List.of(
                        InetAddress.parseNumericAddress("192.168.82.14"),
                        InetAddress.parseNumericAddress("2001::1")));

        NsdServiceInfo validCustomHostWithAddresses = new NsdServiceInfo(null, null);
        validCustomHostWithAddresses.setHostname("a_host");
        validCustomHostWithAddresses.setHostAddresses(
                List.of(
                        InetAddress.parseNumericAddress("192.168.82.14"),
                        InetAddress.parseNumericAddress("2001::1")));

        NsdServiceInfo validServiceWithCustomHostAndAddresses =
                new NsdServiceInfo("a_name", "_a_type._tcp");
        validServiceWithCustomHostAndAddresses.setPort(2222);
        validServiceWithCustomHostAndAddresses.setHostname("a_host");
        validServiceWithCustomHostAndAddresses.setHostAddresses(
                List.of(
                        InetAddress.parseNumericAddress("192.168.82.14"),
                        InetAddress.parseNumericAddress("2001::1")));

        NsdServiceInfo validServiceWithCustomHostNoAddresses =
                new NsdServiceInfo("a_name", "_a_type._tcp");
        validServiceWithCustomHostNoAddresses.setPort(2222);
        validServiceWithCustomHostNoAddresses.setHostname("a_host");

        NsdServiceInfo validServiceWithPublicKey = new NsdServiceInfo("a_name", "_a_type._tcp");
        validServiceWithPublicKey.setPublicKey(
                hexStringToByteArray(
                        "0201030dc141d0637960b98cbc12cfca"
                                + "221d2879dac26ee5b460e9007c992e19"
                                + "02d897c391b03764d448f7d0c772fdb0"
                                + "3b1d9d6d52ff8886769e8e2362513565"
                                + "270962d3"));

        NsdServiceInfo invalidServiceWithTooShortPublicKey =
                new NsdServiceInfo("a_name", "_a_type._tcp");
        invalidServiceWithTooShortPublicKey.setPublicKey(hexStringToByteArray("0201"));

        // Service registration
        //  - invalid arguments
        mustFail(() -> { manager.unregisterService(null); });
        mustFail(() -> { manager.registerService(null, -1, null); });
        mustFail(() -> { manager.registerService(null, PROTOCOL, listener1); });
        mustFail(() -> { manager.registerService(invalidService, PROTOCOL, listener1); });
        mustFail(() -> { manager.registerService(validService, -1, listener1); });
        mustFail(() -> { manager.registerService(validService, PROTOCOL, null); });
        mustFail(() -> {
            manager.registerService(invalidMissingHostnameWithAddresses, PROTOCOL, listener1); });
        mustFail(() -> {
            manager.registerService(invalidServiceWithTooShortPublicKey, PROTOCOL, listener1); });
        manager.registerService(validService, PROTOCOL, listener1);
        //  - update without subtype is not allowed
        mustFail(() -> { manager.registerService(validServiceDuplicate, PROTOCOL, listener1); });
        //  - update with subtype is allowed
        manager.registerService(validServiceSubtypeUpdate, PROTOCOL, listener1);
        //  - re-updating to the same subtype is allowed
        manager.registerService(validServiceSubtypeUpdate, PROTOCOL, listener1);
        //  - updating to other subtypes is allowed
        manager.registerService(otherSubtypeUpdate, PROTOCOL, listener1);
        //  - update back to the service without subtype is allowed
        manager.registerService(validService, PROTOCOL, listener1);
        //  - updating to a subtype with _sub._type syntax is not allowed
        mustFail(() -> { manager.registerService(dotSyntaxSubtypeUpdate, PROTOCOL, listener1); });
        //  - updating to a different service name is not allowed
        mustFail(() -> { manager.registerService(otherServiceWithSubtype, PROTOCOL, listener1); });
        //  - listener already registered, and not using subtypes
        mustFail(() -> { manager.registerService(validService, PROTOCOL, listener1); });
        manager.unregisterService(listener1);
        // TODO: make listener immediately reusable
        //mustFail(() -> { manager.unregisterService(listener1); });
        //manager.registerService(validService, PROTOCOL, listener1);
        //  - registering a custom host without a service is valid
        manager.registerService(validCustomHostWithAddresses, PROTOCOL, listener4);
        manager.unregisterService(listener4);
        //  - registering a service with a custom host is valid
        manager.registerService(validServiceWithCustomHostAndAddresses, PROTOCOL, listener5);
        manager.unregisterService(listener5);
        //  - registering a service with a custom host with no addresses is valid
        manager.registerService(validServiceWithCustomHostNoAddresses, PROTOCOL, listener6);
        manager.unregisterService(listener6);
        //  - registering a service with a public key is valid
        manager.registerService(validServiceWithPublicKey, PROTOCOL, listener7);
        manager.unregisterService(listener7);

        // Discover service
        //  - invalid arguments
        mustFail(() -> { manager.stopServiceDiscovery(null); });
        mustFail(() -> { manager.discoverServices(null, -1, null); });
        mustFail(() -> { manager.discoverServices(null, PROTOCOL, listener2); });
        mustFail(() -> { manager.discoverServices("a_service", -1, listener2); });
        mustFail(() -> { manager.discoverServices("a_service", PROTOCOL, null); });
        manager.discoverServices("a_service", PROTOCOL, listener2);
        //  - listener already registered
        mustFail(() -> { manager.discoverServices("another_service", PROTOCOL, listener2); });
        manager.stopServiceDiscovery(listener2);
        // TODO: make listener immediately reusable
        //mustFail(() -> { manager.stopServiceDiscovery(listener2); });
        //manager.discoverServices("another_service", PROTOCOL, listener2);

        // Resolver service
        //  - invalid arguments
        mustFail(() -> { manager.resolveService(null, null); });
        mustFail(() -> { manager.resolveService(null, listener3); });
        mustFail(() -> { manager.resolveService(invalidService, listener3); });
        mustFail(() -> { manager.resolveService(validService, null); });
        manager.resolveService(validService, listener3);
        //  - listener already registered:w
        mustFail(() -> { manager.resolveService(validService, listener3); });
    }

    private static final class NsdServiceInfoBuilder {
        private static final String SERVICE_NAME = "TestService";
        private static final String SERVICE_TYPE = "_testservice._tcp";
        private static final int SERVICE_PORT = 12345;
        private static final String HOSTNAME = "TestHost";
        private static final List<InetAddress> HOST_ADDRESSES =
                List.of(parseNumericAddress("192.168.2.23"), parseNumericAddress("2001:db8::3"));
        private static final byte[] PUBLIC_KEY =
                hexStringToByteArray(
                        "0201030dc141d0637960b98cbc12cfca"
                                + "221d2879dac26ee5b460e9007c992e19"
                                + "02d897c391b03764d448f7d0c772fdb0"
                                + "3b1d9d6d52ff8886769e8e2362513565"
                                + "270962d3");

        private final NsdServiceInfo mNsdServiceInfo = new NsdServiceInfo();

        NsdServiceInfo build() {
            return mNsdServiceInfo;
        }

        NsdServiceInfoBuilder setNoService() {
            mNsdServiceInfo.setServiceName(null);
            mNsdServiceInfo.setServiceType(null);
            mNsdServiceInfo.setPort(0);
            return this;
        }

        NsdServiceInfoBuilder setService() {
            mNsdServiceInfo.setServiceName(SERVICE_NAME);
            mNsdServiceInfo.setServiceType(SERVICE_TYPE);
            mNsdServiceInfo.setPort(SERVICE_PORT);
            return this;
        }

        NsdServiceInfoBuilder setZeroPortService() {
            mNsdServiceInfo.setServiceName(SERVICE_NAME);
            mNsdServiceInfo.setServiceType(SERVICE_TYPE);
            mNsdServiceInfo.setPort(0);
            return this;
        }

        NsdServiceInfoBuilder setInvalidService() {
            mNsdServiceInfo.setServiceName(SERVICE_NAME);
            mNsdServiceInfo.setServiceType(null);
            mNsdServiceInfo.setPort(SERVICE_PORT);
            return this;
        }

        NsdServiceInfoBuilder setDefaultHost() {
            mNsdServiceInfo.setHostname(null);
            mNsdServiceInfo.setHostAddresses(Collections.emptyList());
            return this;
        }

        NsdServiceInfoBuilder setCustomHost() {
            mNsdServiceInfo.setHostname(HOSTNAME);
            mNsdServiceInfo.setHostAddresses(HOST_ADDRESSES);
            return this;
        }

        NsdServiceInfoBuilder setCustomHostNoAddress() {
            mNsdServiceInfo.setHostname(HOSTNAME);
            mNsdServiceInfo.setHostAddresses(Collections.emptyList());
            return this;
        }

        NsdServiceInfoBuilder setHostAddressesNoHostname() {
            mNsdServiceInfo.setHostname(null);
            mNsdServiceInfo.setHostAddresses(HOST_ADDRESSES);
            return this;
        }

        NsdServiceInfoBuilder setNoPublicKey() {
            mNsdServiceInfo.setPublicKey(null);
            return this;
        }

        NsdServiceInfoBuilder setPublicKey() {
            mNsdServiceInfo.setPublicKey(PUBLIC_KEY);
            return this;
        }

        NsdServiceInfoBuilder setInvalidPublicKey() {
            mNsdServiceInfo.setPublicKey(new byte[3]);
            return this;
        }
    }

    @Test
    public void testCheckServiceInfoForRegistration() {
        // The service is invalid
        mustFail(() -> checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setInvalidService()
                        .setCustomHost()
                        .setPublicKey().build()));
        // Keep compatible with the legacy behavior: It's allowed to set host
        // addresses for a service registration although the host addresses
        // won't be registered. To register the addresses for a host, the
        // hostname must be specified.
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setService()
                        .setHostAddressesNoHostname()
                        .setPublicKey().build());
        // The public key is invalid
        mustFail(() -> checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setService()
                        .setCustomHost()
                        .setInvalidPublicKey().build()));
        // Invalid combinations
        // 1. (service, custom host, key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setService()
                        .setCustomHost()
                        .setPublicKey().build());
        // 2. (service, custom host, no key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setService()
                        .setCustomHost()
                        .setNoPublicKey().build());
        // 3. (service, no-address custom host, key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setService()
                        .setCustomHostNoAddress()
                        .setPublicKey().build());
        // 4. (service, no-address custom host, no key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setService()
                        .setCustomHostNoAddress()
                        .setNoPublicKey().build());
        // 5. (service, default host, key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setService()
                        .setDefaultHost()
                        .setPublicKey().build());
        // 6. (service, default host, no key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setService()
                        .setDefaultHost()
                        .setNoPublicKey().build());
        // 7. (0-port service, custom host, valid key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setZeroPortService()
                        .setCustomHost()
                        .setPublicKey().build());
        // 8. (0-port service, custom host, no key): invalid
        mustFail(() -> checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setZeroPortService()
                        .setCustomHost()
                        .setNoPublicKey().build()));
        // 9. (0-port service, no-address custom host, key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setZeroPortService()
                        .setCustomHostNoAddress()
                        .setPublicKey().build());
        // 10. (0-port service, no-address custom host, no key): invalid
        mustFail(() -> checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setZeroPortService()
                        .setCustomHostNoAddress()
                        .setNoPublicKey().build()));
        // 11. (0-port service, default host, key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setZeroPortService()
                        .setDefaultHost()
                        .setPublicKey().build());
        // 12. (0-port service, default host, no key): invalid
        mustFail(() -> checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setZeroPortService()
                        .setDefaultHost()
                        .setNoPublicKey().build()));
        // 13. (no service, custom host, key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setNoService()
                        .setCustomHost()
                        .setPublicKey().build());
        // 14. (no service, custom host, no key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setNoService()
                        .setCustomHost()
                        .setNoPublicKey().build());
        // 15. (no service, no-address custom host, key): valid
        checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setNoService()
                        .setCustomHostNoAddress()
                        .setPublicKey().build());
        // 16. (no service, no-address custom host, no key): invalid
        mustFail(() -> checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setNoService()
                        .setCustomHostNoAddress()
                        .setNoPublicKey().build()));
        // 17. (no service, default host, key): invalid
        mustFail(() -> checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setNoService()
                        .setDefaultHost()
                        .setPublicKey().build()));
        // 18. (no service, default host, no key): invalid
        mustFail(() -> checkServiceInfoForRegistration(
                new NsdServiceInfoBuilder()
                        .setNoService()
                        .setDefaultHost()
                        .setNoPublicKey().build()));
    }

    public void mustFail(Runnable fn) {
        try {
            fn.run();
            fail();
        } catch (Exception expected) {
        }
    }

    int getRequestKey(ThrowingConsumer<ArgumentCaptor<Integer>> verifier)
            throws Exception {
        final ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verifier.accept(captor);
        return captor.getValue();
    }

    AdvertisingRequest getAdvertisingRequest(
            ThrowingConsumer<ArgumentCaptor<AdvertisingRequest>> verifier) throws Exception {
        final ArgumentCaptor<AdvertisingRequest> captor =
                ArgumentCaptor.forClass(AdvertisingRequest.class);
        verifier.accept(captor);
        return captor.getValue();
    }
}
