/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.thread;

import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.WRITE_ALLOWLISTED_DEVICE_CONFIG;
import static android.Manifest.permission.WRITE_DEVICE_CONFIG;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_THREAD;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.thread.ActiveOperationalDataset.CHANNEL_PAGE_24_GHZ;
import static android.net.thread.ThreadNetworkController.STATE_DISABLED;
import static android.net.thread.ThreadNetworkController.STATE_ENABLED;
import static android.net.thread.ThreadNetworkException.ERROR_FAILED_PRECONDITION;
import static android.net.thread.ThreadNetworkException.ERROR_INTERNAL_ERROR;
import static android.net.thread.ThreadNetworkException.ERROR_THREAD_DISABLED;
import static android.net.thread.ThreadNetworkManager.DISALLOW_THREAD_NETWORK;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_TESTING;

import static com.android.server.thread.ThreadNetworkControllerService.getMeshcopTxtAttributes;
import static com.android.server.thread.ThreadNetworkCountryCode.DEFAULT_COUNTRY_CODE;
import static com.android.server.thread.ThreadPersistentSettings.KEY_THREAD_ENABLED;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_INVALID_STATE;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.IActiveOperationalDatasetReceiver;
import android.net.thread.IOperationReceiver;
import android.net.thread.IOutputReceiver;
import android.net.thread.ThreadConfiguration;
import android.net.thread.ThreadNetworkException;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.DeviceConfig;
import android.util.AtomicFile;

import androidx.test.annotation.UiThreadTest;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.connectivity.resources.R;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.net.module.util.RoutingCoordinatorManager;
import com.android.server.connectivity.ConnectivityResources;
import com.android.server.connectivity.MockableSystemProperties;
import com.android.server.thread.openthread.DnsTxtAttribute;
import com.android.server.thread.openthread.IOtStatusReceiver;
import com.android.server.thread.openthread.MeshcopTxtAttributes;
import com.android.server.thread.openthread.testing.FakeOtDaemon;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Unit tests for {@link ThreadNetworkControllerService}. */
@SmallTest
@RunWith(Parameterized.class)
// This test doesn't really need to run on the UI thread, but @Before and @Test annotated methods
// need to run in the same thread because there are code in {@code ThreadNetworkControllerService}
// checking that all its methods are running in the thread of the handler it's using. This is due
// to a bug in TestLooper that it executes all tasks on the current thread rather than the thread
// associated to the backed Looper object.
@UiThreadTest
public final class ThreadNetworkControllerServiceTest {
    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset new":
    // Active Timestamp: 1
    // Channel: 19
    // Channel Mask: 0x07FFF800
    // Ext PAN ID: ACC214689BC40BDF
    // Mesh Local Prefix: fd64:db12:25f4:7e0b::/64
    // Network Key: F26B3153760F519A63BAFDDFFC80D2AF
    // Network Name: OpenThread-d9a0
    // PAN ID: 0xD9A0
    // PSKc: A245479C836D551B9CA557F7B9D351B4
    // Security Policy: 672 onrcb
    private static final byte[] DEFAULT_ACTIVE_DATASET_TLVS =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");
    private static final ActiveOperationalDataset DEFAULT_ACTIVE_DATASET =
            ActiveOperationalDataset.fromThreadTlvs(DEFAULT_ACTIVE_DATASET_TLVS);
    private static final String DEFAULT_NETWORK_NAME = "thread-wpan0";
    private static final int OT_ERROR_NONE = 0;
    private static final int DEFAULT_SUPPORTED_CHANNEL_MASK = 0x07FFF800; // from channel 11 to 26

    // The DEFAULT_PREFERRED_CHANNEL_MASK is the ot-daemon preferred channel mask. Channel 25 and
    // 26 are not preferred by dataset. The ThreadNetworkControllerService will only select channel
    // 11 when it creates randomized dataset.
    private static final int DEFAULT_PREFERRED_CHANNEL_MASK = 0x06000800; // channel 11, 25 and 26
    private static final int DEFAULT_SELECTED_CHANNEL = 11;
    private static final byte[] DEFAULT_SUPPORTED_CHANNEL_MASK_ARRAY = base16().decode("001FFFE0");

    private static final String TEST_VENDOR_OUI = "AC-DE-48";
    private static final byte[] TEST_VENDOR_OUI_BYTES = new byte[] {(byte) 0xAC, (byte) 0xDE, 0x48};
    private static final String TEST_VENDOR_NAME = "test vendor";
    private static final String TEST_MODEL_NAME = "test model";
    private static final LinkAddress TEST_NAT64_CIDR = new LinkAddress("192.168.255.0/24");

    @Mock private MockableSystemProperties mMockSystemProperties;
    @Mock private ConnectivityManager mMockConnectivityManager;
    @Mock private RoutingCoordinatorManager mMockRoutingCoordinatorManager;
    @Mock private NetworkAgent mMockNetworkAgent;
    @Mock private TunInterfaceController mMockTunIfController;
    @Mock private ParcelFileDescriptor mMockTunFd;
    @Mock private InfraInterfaceController mMockInfraIfController;
    @Mock private NsdPublisher mMockNsdPublisher;
    @Mock private UserManager mMockUserManager;
    @Mock private IBinder mIBinder;
    @Mock Resources mResources;
    @Mock ConnectivityResources mConnectivityResources;
    @Mock Map<Network, LinkProperties> mMockNetworkToLinkProperties;

    private Context mContext;
    private TestLooper mTestLooper;
    private FakeOtDaemon mFakeOtDaemon;
    private ThreadPersistentSettings mPersistentSettings;
    private ThreadNetworkControllerService mService;
    @Captor private ArgumentCaptor<ActiveOperationalDataset> mActiveDatasetCaptor;

    @Rule(order = 1)
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private final boolean mIsBorderRouterEnabled;

    @Parameterized.Parameters
    public static Collection configArguments() {
        return Arrays.asList(new Object[][] {{false}, {true}});
    }

    public ThreadNetworkControllerServiceTest(boolean isBorderRouterEnabled) {
        mIsBorderRouterEnabled = isBorderRouterEnabled;
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = spy(ApplicationProvider.getApplicationContext());
        doNothing()
                .when(mContext)
                .enforceCallingOrSelfPermission(
                        eq(PERMISSION_THREAD_NETWORK_PRIVILEGED), anyString());
        doNothing()
                .when(mContext)
                .enforceCallingOrSelfPermission(eq(PERMISSION_THREAD_NETWORK_TESTING), anyString());
        doNothing()
                .when(mContext)
                .enforceCallingOrSelfPermission(eq(NETWORK_SETTINGS), anyString());

        mTestLooper = new TestLooper();
        final Handler handler = new Handler(mTestLooper.getLooper());
        NetworkProvider networkProvider =
                new NetworkProvider(mContext, mTestLooper.getLooper(), "ThreadNetworkProvider");

        when(mMockRoutingCoordinatorManager.requestDownstreamAddress(any()))
                .thenReturn(TEST_NAT64_CIDR);

        mFakeOtDaemon = spy(new FakeOtDaemon(handler));
        when(mMockTunIfController.getTunFd()).thenReturn(mMockTunFd);

        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(false);

        when(mConnectivityResources.get()).thenReturn(mResources);
        when(mResources.getBoolean(eq(R.bool.config_thread_default_enabled))).thenReturn(true);
        when(mResources.getBoolean(eq(R.bool.config_thread_border_router_default_enabled)))
                .thenReturn(mIsBorderRouterEnabled);
        when(mResources.getBoolean(
                        eq(R.bool.config_thread_srp_server_wait_for_border_routing_enabled)))
                .thenReturn(true);
        when(mResources.getBoolean(eq(R.bool.config_thread_border_router_auto_join_enabled)))
                .thenReturn(true);
        when(mResources.getString(eq(R.string.config_thread_vendor_name)))
                .thenReturn(TEST_VENDOR_NAME);
        when(mResources.getString(eq(R.string.config_thread_vendor_oui)))
                .thenReturn(TEST_VENDOR_OUI);
        when(mResources.getString(eq(R.string.config_thread_model_name)))
                .thenReturn(TEST_MODEL_NAME);
        when(mResources.getStringArray(eq(R.array.config_thread_mdns_vendor_specific_txts)))
                .thenReturn(new String[] {});
        when(mResources.getBoolean(eq(R.bool.config_thread_country_code_enabled))).thenReturn(true);

        final AtomicFile storageFile = new AtomicFile(tempFolder.newFile("thread_settings.xml"));
        mPersistentSettings = new ThreadPersistentSettings(storageFile, mConnectivityResources);
        mPersistentSettings.initialize();

        mService =
                new ThreadNetworkControllerService(
                        mContext,
                        handler,
                        mMockSystemProperties,
                        networkProvider,
                        () -> mFakeOtDaemon,
                        mMockConnectivityManager,
                        mMockRoutingCoordinatorManager,
                        mMockTunIfController,
                        mMockInfraIfController,
                        mPersistentSettings,
                        mMockNsdPublisher,
                        mMockUserManager,
                        mConnectivityResources,
                        () -> DEFAULT_COUNTRY_CODE,
                        mMockNetworkToLinkProperties);
        mService.setTestNetworkAgent(mMockNetworkAgent);
    }

    @After
    public void tearDown() throws Exception {
        runAsShell(
                WRITE_DEVICE_CONFIG,
                WRITE_ALLOWLISTED_DEVICE_CONFIG,
                () -> DeviceConfig.deleteProperty("thread_network", "TrelFeature__enabled"));
    }

    @Test
    public void initialize_tunInterfaceAndNsdPublisherSetToOtDaemon() throws Exception {
        when(mMockTunIfController.getTunFd()).thenReturn(mMockTunFd);

        mService.initialize();
        mTestLooper.dispatchAll();

        verify(mMockTunIfController, times(1)).createTunInterface();
        assertThat(mFakeOtDaemon.getTunFd()).isEqualTo(mMockTunFd);
        assertThat(mFakeOtDaemon.getNsdPublisher()).isEqualTo(mMockNsdPublisher);
    }

    @Test
    public void initialize_resourceOverlayValuesAreSetToOtDaemon() throws Exception {
        when(mResources.getBoolean(
                        eq(R.bool.config_thread_srp_server_wait_for_border_routing_enabled)))
                .thenReturn(false);
        when(mResources.getBoolean(eq(R.bool.config_thread_border_router_auto_join_enabled)))
                .thenReturn(false);
        when(mResources.getString(eq(R.string.config_thread_vendor_name)))
                .thenReturn(TEST_VENDOR_NAME);
        when(mResources.getString(eq(R.string.config_thread_vendor_oui)))
                .thenReturn(TEST_VENDOR_OUI);
        when(mResources.getString(eq(R.string.config_thread_model_name)))
                .thenReturn(TEST_MODEL_NAME);
        when(mResources.getStringArray(eq(R.array.config_thread_mdns_vendor_specific_txts)))
                .thenReturn(new String[] {"vt=test"});

        mService.initialize();
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.getConfiguration().srpServerWaitForBorderRoutingEnabled).isFalse();
        assertThat(mFakeOtDaemon.getConfiguration().borderRouterAutoJoinEnabled).isFalse();
        MeshcopTxtAttributes meshcopTxts = mFakeOtDaemon.getOverriddenMeshcopTxtAttributes();
        assertThat(meshcopTxts.vendorName).isEqualTo(TEST_VENDOR_NAME);
        assertThat(meshcopTxts.vendorOui).isEqualTo(TEST_VENDOR_OUI_BYTES);
        assertThat(meshcopTxts.modelName).isEqualTo(TEST_MODEL_NAME);
        assertThat(meshcopTxts.nonStandardTxtEntries)
                .containsExactly(new DnsTxtAttribute("vt", "test".getBytes(UTF_8)));
    }

    @Test
    public void initialize_vendorAndModelNameSetToProperty_propertiesAreSetToOtDaemon()
            throws Exception {
        when(mMockSystemProperties.get(eq("ro.product.manufacturer"))).thenReturn("Banana");
        when(mResources.getString(eq(R.string.config_thread_vendor_name)))
                .thenReturn("ro.product.manufacturer");
        when(mMockSystemProperties.get(eq("ro.product.model"))).thenReturn("Orange");
        when(mResources.getString(eq(R.string.config_thread_model_name)))
                .thenReturn("ro.product.model");

        mService.initialize();
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.getConfiguration().vendorName).isEqualTo("Banana");
        assertThat(mFakeOtDaemon.getConfiguration().modelName).isEqualTo("Orange");
    }

    @Test
    public void initialize_nat64Disabled_doesNotRequestNat64CidrAndConfiguresOtDaemon()
            throws Exception {
        ThreadConfiguration config =
                new ThreadConfiguration.Builder().setNat64Enabled(false).build();
        mPersistentSettings.putConfiguration(config);
        mService.initialize();
        mTestLooper.dispatchAll();

        verify(mMockRoutingCoordinatorManager, never()).requestDownstreamAddress(any());
        assertThat(mFakeOtDaemon.getNat64Cidr()).isNull();
    }

    @Test
    public void initialize_nat64Enabled_requestsNat64CidrAndConfiguresAtOtDaemon()
            throws Exception {
        ThreadConfiguration config =
                new ThreadConfiguration.Builder().setNat64Enabled(true).build();
        mPersistentSettings.putConfiguration(config);
        mService.initialize();
        mTestLooper.dispatchAll();

        verify(mMockRoutingCoordinatorManager, times(1)).requestDownstreamAddress(any());
        assertThat(mFakeOtDaemon.getConfiguration().nat64Enabled).isTrue();
        assertThat(mFakeOtDaemon.getNat64Cidr()).isEqualTo(TEST_NAT64_CIDR.toString());
    }

    @Test
    public void initialize_trelFeatureDisabled_trelDisabledAtOtDaemon() throws Exception {
        runAsShell(
                WRITE_DEVICE_CONFIG,
                WRITE_ALLOWLISTED_DEVICE_CONFIG,
                () ->
                        DeviceConfig.setProperty(
                                "thread_network", "TrelFeature__enabled", "false", false));

        mService.initialize();
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.isTrelEnabled()).isFalse();
    }

    @Test
    public void initialize_trelFeatureEnabled_setTrelEnabledAtOtDamon() throws Exception {
        runAsShell(
                WRITE_DEVICE_CONFIG,
                WRITE_ALLOWLISTED_DEVICE_CONFIG,
                () ->
                        DeviceConfig.setProperty(
                                "thread_network", "TrelFeature__enabled", "true", false));
        mService.initialize();
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.isTrelEnabled()).isTrue();
    }

    @Test
    public void getMeshcopTxtAttributes_emptyVendorName_accepted() {
        when(mResources.getString(eq(R.string.config_thread_vendor_name))).thenReturn("");

        MeshcopTxtAttributes meshcopTxts =
                getMeshcopTxtAttributes(mResources, mMockSystemProperties);

        assertThat(meshcopTxts.vendorName).isEqualTo("");
    }

    @Test
    public void getMeshcopTxtAttributes_tooLongVendorName_throwsIllegalStateException() {
        when(mResources.getString(eq(R.string.config_thread_vendor_name)))
                .thenReturn("vendor name is 25 bytes!!");

        assertThrows(
                IllegalStateException.class,
                () -> getMeshcopTxtAttributes(mResources, mMockSystemProperties));
    }

    @Test
    public void getMeshcopTxtAttributes_VendorNameSetToManufacturer_manufacturerPropertyIsUsed() {
        when(mMockSystemProperties.get(eq("ro.product.manufacturer"))).thenReturn("Banana");
        when(mResources.getString(eq(R.string.config_thread_vendor_name)))
                .thenReturn("ro.product.manufacturer");

        MeshcopTxtAttributes meshcopTxts =
                getMeshcopTxtAttributes(mResources, mMockSystemProperties);

        assertThat(meshcopTxts.vendorName).isEqualTo("Banana");
    }

    @Test
    public void getMeshcopTxtAttributes_ModelNameSetToModelProperty_modelPropertyIsUsed() {
        when(mMockSystemProperties.get(eq("ro.product.model"))).thenReturn("Orange");
        when(mResources.getString(eq(R.string.config_thread_model_name)))
                .thenReturn("ro.product.model");

        MeshcopTxtAttributes meshcopTxts =
                getMeshcopTxtAttributes(mResources, mMockSystemProperties);

        assertThat(meshcopTxts.modelName).isEqualTo("Orange");
    }

    @Test
    public void getMeshcopTxtAttributes_tooLongModelName_throwsIllegalStateException() {
        when(mResources.getString(eq(R.string.config_thread_model_name)))
                .thenReturn("model name is 25 bytes!!!");

        assertThrows(
                IllegalStateException.class,
                () -> getMeshcopTxtAttributes(mResources, mMockSystemProperties));
    }

    @Test
    public void getMeshcopTxtAttributes_emptyModelName_accepted() {
        when(mResources.getString(eq(R.string.config_thread_model_name))).thenReturn("");

        var meshcopTxts = getMeshcopTxtAttributes(mResources, mMockSystemProperties);
        assertThat(meshcopTxts.modelName).isEqualTo("");
    }

    @Test
    public void getMeshcopTxtAttributes_invalidVendorOui_throwsIllegalStateException() {
        assertThrows(
                IllegalStateException.class, () -> getMeshcopTxtAttributesWithVendorOui("ABCDEFA"));
        assertThrows(
                IllegalStateException.class, () -> getMeshcopTxtAttributesWithVendorOui("ABCDEG"));
        assertThrows(
                IllegalStateException.class, () -> getMeshcopTxtAttributesWithVendorOui("ABCD"));
        assertThrows(
                IllegalStateException.class,
                () -> getMeshcopTxtAttributesWithVendorOui("AB.CD.EF"));
    }

    @Test
    public void getMeshcopTxtAttributes_validVendorOui_accepted() {
        assertThat(getMeshcopTxtAttributesWithVendorOui("010203")).isEqualTo(new byte[] {1, 2, 3});
        assertThat(getMeshcopTxtAttributesWithVendorOui("01-02-03"))
                .isEqualTo(new byte[] {1, 2, 3});
        assertThat(getMeshcopTxtAttributesWithVendorOui("01:02:03"))
                .isEqualTo(new byte[] {1, 2, 3});
        assertThat(getMeshcopTxtAttributesWithVendorOui("ABCDEF"))
                .isEqualTo(new byte[] {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF});
        assertThat(getMeshcopTxtAttributesWithVendorOui("abcdef"))
                .isEqualTo(new byte[] {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF});
    }

    private byte[] getMeshcopTxtAttributesWithVendorOui(String vendorOui) {
        when(mResources.getString(eq(R.string.config_thread_vendor_oui))).thenReturn(vendorOui);
        return getMeshcopTxtAttributes(mResources, mMockSystemProperties).vendorOui;
    }

    @Test
    public void makeVendorSpecificTxtAttrs_validTxts_returnsParsedTxtAttrs() {
        String[] txts = new String[] {"va=123", "vb=", "vc"};

        List<DnsTxtAttribute> attrs = mService.makeVendorSpecificTxtAttrs(txts);

        assertThat(attrs)
                .containsExactly(
                        new DnsTxtAttribute("va", "123".getBytes(UTF_8)),
                        new DnsTxtAttribute("vb", new byte[] {}),
                        new DnsTxtAttribute("vc", new byte[] {}));
    }

    @Test
    public void makeVendorSpecificTxtAttrs_txtKeyNotStartWithV_throwsIllegalArgument() {
        String[] txts = new String[] {"abc=123"};

        assertThrows(
                IllegalArgumentException.class, () -> mService.makeVendorSpecificTxtAttrs(txts));
    }

    @Test
    public void makeVendorSpecificTxtAttrs_txtIsTooShort_throwsIllegalArgument() {
        String[] txtEmptyKey = new String[] {"=123"};
        String[] txtSingleCharKey = new String[] {"v=456"};

        assertThrows(
                IllegalArgumentException.class,
                () -> mService.makeVendorSpecificTxtAttrs(txtEmptyKey));
        assertThrows(
                IllegalArgumentException.class,
                () -> mService.makeVendorSpecificTxtAttrs(txtSingleCharKey));
    }

    @Test
    public void makeVendorSpecificTxtAttrs_txtValueIsEmpty_parseSuccess() {
        String[] txts = new String[] {"va=", "vb"};

        List<DnsTxtAttribute> attrs = mService.makeVendorSpecificTxtAttrs(txts);

        assertThat(attrs)
                .containsExactly(
                        new DnsTxtAttribute("va", new byte[] {}),
                        new DnsTxtAttribute("vb", new byte[] {}));
    }

    @Test
    public void makeVendorSpecificTxtAttrs_multipleEquals_splittedByTheFirstEqual() {
        String[] txts = new String[] {"va=abc=def=123"};

        List<DnsTxtAttribute> attrs = mService.makeVendorSpecificTxtAttrs(txts);

        assertThat(attrs).containsExactly(new DnsTxtAttribute("va", "abc=def=123".getBytes(UTF_8)));
    }

    @Test
    public void join_otDaemonRemoteFailure_returnsInternalError() throws Exception {
        mService.initialize();
        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        mFakeOtDaemon.setJoinException(new RemoteException("ot-daemon join() throws"));

        mService.join(DEFAULT_ACTIVE_DATASET, mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, never()).onSuccess();
        verify(mockReceiver, times(1)).onError(eq(ERROR_INTERNAL_ERROR), anyString());
    }

    @Test
    public void join_succeed_threadNetworkRegistered() throws Exception {
        mService.initialize();
        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);

        mService.join(DEFAULT_ACTIVE_DATASET, mockReceiver);
        // Here needs to call Testlooper#dispatchAll twices because TestLooper#moveTimeForward
        // operates on only currently enqueued messages but the delayed message is posted from
        // another Handler task.
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(FakeOtDaemon.JOIN_DELAY.toMillis() + 100);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
        verify(mMockNetworkAgent, times(1)).register();
    }

    @Test
    public void userRestriction_initWithUserRestricted_otDaemonNotStarted() {
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(true);

        mService.initialize();
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.isInitialized()).isFalse();
    }

    @Test
    public void userRestriction_initWithUserNotRestricted_threadIsEnabled() {
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(false);

        mService.initialize();
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.getEnabledState()).isEqualTo(STATE_ENABLED);
    }

    @Test
    public void userRestriction_userBecomesRestricted_stateIsDisabledButNotPersisted() {
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(false);
        AtomicReference<BroadcastReceiver> receiverRef =
                captureBroadcastReceiver(UserManager.ACTION_USER_RESTRICTIONS_CHANGED);
        mService.initialize();
        mTestLooper.dispatchAll();

        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(true);
        receiverRef.get().onReceive(mContext, new Intent());
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.getEnabledState()).isEqualTo(STATE_DISABLED);
        assertThat(mPersistentSettings.get(KEY_THREAD_ENABLED)).isTrue();
    }

    @Test
    public void userRestriction_userBecomesNotRestricted_stateIsEnabled() {
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(true);
        AtomicReference<BroadcastReceiver> receiverRef =
                captureBroadcastReceiver(UserManager.ACTION_USER_RESTRICTIONS_CHANGED);
        mService.initialize();
        mTestLooper.dispatchAll();

        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(false);
        receiverRef.get().onReceive(mContext, new Intent());
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.getEnabledState()).isEqualTo(STATE_ENABLED);
    }

    @Test
    public void userRestriction_setEnabledWhenUserRestricted_failedPreconditionError() {
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(true);
        mService.initialize();

        CompletableFuture<Void> setEnabledFuture = new CompletableFuture<>();
        mService.setEnabled(true, newOperationReceiver(setEnabledFuture));
        mTestLooper.dispatchAll();

        var thrown = assertThrows(ExecutionException.class, () -> setEnabledFuture.get());
        ThreadNetworkException failure = (ThreadNetworkException) thrown.getCause();
        assertThat(failure.getErrorCode()).isEqualTo(ERROR_FAILED_PRECONDITION);
    }

    @Test
    public void setEnabled_success_threadNetworkEnabled() throws Exception {
        mService.initialize();

        CompletableFuture<Void> setEnabledFuture = new CompletableFuture<>();
        mService.setEnabled(true, newOperationReceiver(setEnabledFuture));
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.getEnabledState()).isEqualTo(STATE_ENABLED);
    }

    @Test
    public void setDisabled_success_threadNetworkDisabled() throws Exception {
        mService.initialize();

        CompletableFuture<Void> setEnabledFuture = new CompletableFuture<>();
        mService.setEnabled(false, newOperationReceiver(setEnabledFuture));
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.getEnabledState()).isEqualTo(STATE_DISABLED);
    }

    @Test
    public void setEnabled_otDaemonRemoteFailure_returnsInternalError() throws Exception {
        mService.initialize();
        CompletableFuture<Void> setEnabledFuture = new CompletableFuture<>();
        mFakeOtDaemon.setSetEnabledException(
                new RemoteException("ot-daemon setThreadEnabled() throws"));

        mService.setEnabled(true, newOperationReceiver(setEnabledFuture));
        mTestLooper.dispatchAll();

        var thrown = assertThrows(ExecutionException.class, () -> setEnabledFuture.get());
        ThreadNetworkException failure = (ThreadNetworkException) thrown.getCause();
        assertThat(failure.getErrorCode()).isEqualTo(ERROR_INTERNAL_ERROR);
    }

    private AtomicReference<BroadcastReceiver> captureBroadcastReceiver(String action) {
        AtomicReference<BroadcastReceiver> receiverRef = new AtomicReference<>();

        doAnswer(
                        invocation -> {
                            receiverRef.set((BroadcastReceiver) invocation.getArguments()[0]);
                            return null;
                        })
                .when(mContext)
                .registerReceiver(
                        any(BroadcastReceiver.class),
                        argThat(actualIntentFilter -> actualIntentFilter.hasAction(action)),
                        any(),
                        any());

        return receiverRef;
    }

    private static IOperationReceiver newOperationReceiver(CompletableFuture<Void> future) {
        return new IOperationReceiver.Stub() {
            @Override
            public void onSuccess() {
                future.complete(null);
            }

            @Override
            public void onError(int errorCode, String errorMessage) {
                future.completeExceptionally(new ThreadNetworkException(errorCode, errorMessage));
            }
        };
    }

    @Test
    public void
            createRandomizedDataset_noNetworkTimeClock_datasetActiveTimestampIsNotAuthoritative()
                    throws Exception {
        MockitoSession session =
                ExtendedMockito.mockitoSession().mockStatic(SystemClock.class).startMocking();
        final IActiveOperationalDatasetReceiver mockReceiver =
                ExtendedMockito.mock(IActiveOperationalDatasetReceiver.class);

        try {
            ExtendedMockito.when(SystemClock.currentNetworkTimeClock())
                    .thenThrow(new DateTimeException("fake throw"));
            mService.createRandomizedDataset(DEFAULT_NETWORK_NAME, mockReceiver);
            mTestLooper.dispatchAll();
        } finally {
            session.finishMocking();
        }

        verify(mockReceiver, never()).onError(anyInt(), anyString());
        verify(mockReceiver, times(1)).onSuccess(mActiveDatasetCaptor.capture());
        ActiveOperationalDataset activeDataset = mActiveDatasetCaptor.getValue();
        assertThat(activeDataset.getActiveTimestamp().isAuthoritativeSource()).isFalse();
    }

    @Test
    public void createRandomizedDataset_zeroNanoseconds_returnsZeroTicks() throws Exception {
        Instant now = Instant.ofEpochSecond(0, 0);
        Clock clock = Clock.fixed(now, ZoneId.systemDefault());
        MockitoSession session =
                ExtendedMockito.mockitoSession().mockStatic(SystemClock.class).startMocking();
        final IActiveOperationalDatasetReceiver mockReceiver =
                ExtendedMockito.mock(IActiveOperationalDatasetReceiver.class);

        try {
            ExtendedMockito.when(SystemClock.currentNetworkTimeClock()).thenReturn(clock);
            mService.createRandomizedDataset(DEFAULT_NETWORK_NAME, mockReceiver);
            mTestLooper.dispatchAll();
        } finally {
            session.finishMocking();
        }

        verify(mockReceiver, never()).onError(anyInt(), anyString());
        verify(mockReceiver, times(1)).onSuccess(mActiveDatasetCaptor.capture());
        ActiveOperationalDataset activeDataset = mActiveDatasetCaptor.getValue();
        assertThat(activeDataset.getActiveTimestamp().getTicks()).isEqualTo(0);
    }

    @Test
    public void createRandomizedDataset_maxNanoseconds_returnsMaxTicks() throws Exception {
        // The nanoseconds to ticks conversion is rounded in the current implementation.
        // 32767.5 / 32768 * 1000000000 = 999984741.2109375, using 999984741 to
        // produce the maximum ticks.
        Instant now = Instant.ofEpochSecond(0, 999984741);
        Clock clock = Clock.fixed(now, ZoneId.systemDefault());
        MockitoSession session =
                ExtendedMockito.mockitoSession().mockStatic(SystemClock.class).startMocking();
        final IActiveOperationalDatasetReceiver mockReceiver =
                ExtendedMockito.mock(IActiveOperationalDatasetReceiver.class);

        try {
            ExtendedMockito.when(SystemClock.currentNetworkTimeClock()).thenReturn(clock);
            mService.createRandomizedDataset(DEFAULT_NETWORK_NAME, mockReceiver);
            mTestLooper.dispatchAll();
        } finally {
            session.finishMocking();
        }

        verify(mockReceiver, never()).onError(anyInt(), anyString());
        verify(mockReceiver, times(1)).onSuccess(mActiveDatasetCaptor.capture());
        ActiveOperationalDataset activeDataset = mActiveDatasetCaptor.getValue();
        assertThat(activeDataset.getActiveTimestamp().getTicks()).isEqualTo(32767);
    }

    @Test
    public void createRandomizedDataset_hasNetworkTimeClock_datasetActiveTimestampIsAuthoritative()
            throws Exception {
        MockitoSession session =
                ExtendedMockito.mockitoSession().mockStatic(SystemClock.class).startMocking();
        final IActiveOperationalDatasetReceiver mockReceiver =
                ExtendedMockito.mock(IActiveOperationalDatasetReceiver.class);

        try {
            ExtendedMockito.when(SystemClock.currentNetworkTimeClock())
                    .thenReturn(Clock.systemUTC());
            mService.createRandomizedDataset(DEFAULT_NETWORK_NAME, mockReceiver);
            mTestLooper.dispatchAll();
        } finally {
            session.finishMocking();
        }

        verify(mockReceiver, never()).onError(anyInt(), anyString());
        verify(mockReceiver, times(1)).onSuccess(mActiveDatasetCaptor.capture());
        ActiveOperationalDataset activeDataset = mActiveDatasetCaptor.getValue();
        assertThat(activeDataset.getActiveTimestamp().isAuthoritativeSource()).isTrue();
    }

    @Test
    public void createRandomizedDataset_succeed_activeDatasetCreated() throws Exception {
        final IActiveOperationalDatasetReceiver mockReceiver =
                mock(IActiveOperationalDatasetReceiver.class);
        mFakeOtDaemon.setChannelMasks(
                DEFAULT_SUPPORTED_CHANNEL_MASK, DEFAULT_PREFERRED_CHANNEL_MASK);
        mFakeOtDaemon.setChannelMasksReceiverOtError(OT_ERROR_NONE);

        mService.createRandomizedDataset(DEFAULT_NETWORK_NAME, mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, never()).onError(anyInt(), anyString());
        verify(mockReceiver, times(1)).onSuccess(mActiveDatasetCaptor.capture());
        ActiveOperationalDataset activeDataset = mActiveDatasetCaptor.getValue();
        assertThat(activeDataset.getNetworkName()).isEqualTo(DEFAULT_NETWORK_NAME);
        assertThat(activeDataset.getChannelMask().size()).isEqualTo(1);
        assertThat(activeDataset.getChannelMask().get(CHANNEL_PAGE_24_GHZ))
                .isEqualTo(DEFAULT_SUPPORTED_CHANNEL_MASK_ARRAY);
        assertThat(activeDataset.getChannel()).isEqualTo(DEFAULT_SELECTED_CHANNEL);
    }

    @Test
    public void createRandomizedDataset_otDaemonRemoteFailure_returnsPreconditionError()
            throws Exception {
        final IActiveOperationalDatasetReceiver mockReceiver =
                mock(IActiveOperationalDatasetReceiver.class);
        mFakeOtDaemon.setChannelMasksReceiverOtError(OT_ERROR_INVALID_STATE);
        when(mockReceiver.asBinder()).thenReturn(mIBinder);

        mService.createRandomizedDataset(DEFAULT_NETWORK_NAME, mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, never()).onSuccess(any(ActiveOperationalDataset.class));
        verify(mockReceiver, times(1)).onError(eq(ERROR_INTERNAL_ERROR), anyString());
    }

    @Test
    public void forceStopOtDaemonForTest_noPermission_throwsSecurityException() {
        doThrow(new SecurityException(""))
                .when(mContext)
                .enforceCallingOrSelfPermission(eq(PERMISSION_THREAD_NETWORK_PRIVILEGED), any());

        assertThrows(
                SecurityException.class,
                () -> mService.forceStopOtDaemonForTest(true, new IOperationReceiver.Default()));
    }

    @Test
    public void forceStopOtDaemonForTest_enabled_otDaemonDiesAndJoinFails() throws Exception {
        mService.initialize();
        IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        IOperationReceiver mockJoinReceiver = mock(IOperationReceiver.class);

        mService.forceStopOtDaemonForTest(true, mockReceiver);
        mTestLooper.dispatchAll();
        mService.join(DEFAULT_ACTIVE_DATASET, mockJoinReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
        assertThat(mFakeOtDaemon.isInitialized()).isFalse();
        verify(mockJoinReceiver, times(1)).onError(eq(ERROR_THREAD_DISABLED), anyString());
    }

    @Test
    public void forceStopOtDaemonForTest_disable_otDaemonRestartsAndJoinSccess() throws Exception {
        mService.initialize();
        IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        IOperationReceiver mockJoinReceiver = mock(IOperationReceiver.class);

        mService.forceStopOtDaemonForTest(true, mock(IOperationReceiver.class));
        mTestLooper.dispatchAll();
        mService.forceStopOtDaemonForTest(false, mockReceiver);
        mTestLooper.dispatchAll();
        mService.join(DEFAULT_ACTIVE_DATASET, mockJoinReceiver);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(FakeOtDaemon.JOIN_DELAY.toMillis() + 100);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
        assertThat(mFakeOtDaemon.isInitialized()).isTrue();
        verify(mockJoinReceiver, times(1)).onSuccess();
    }

    @Test
    public void onOtDaemonDied_joinedNetwork_interfaceStateBackToUp() throws Exception {
        mService.initialize();
        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        mService.join(DEFAULT_ACTIVE_DATASET, mockReceiver);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(FakeOtDaemon.JOIN_DELAY.toMillis() + 100);
        mTestLooper.dispatchAll();

        Mockito.reset(mMockInfraIfController);
        mFakeOtDaemon.terminate();
        mTestLooper.dispatchAll();

        verify(mMockTunIfController, times(1)).onOtDaemonDied();
        InOrder inOrder = Mockito.inOrder(mMockTunIfController);
        inOrder.verify(mMockTunIfController, times(1)).setInterfaceUp(false);
        inOrder.verify(mMockTunIfController, times(1)).setInterfaceUp(true);
    }

    @Test
    public void setConfiguration_configurationUpdated() throws Exception {
        mService.initialize();
        final IOperationReceiver mockReceiver1 = mock(IOperationReceiver.class);
        final IOperationReceiver mockReceiver2 = mock(IOperationReceiver.class);
        final IOperationReceiver mockReceiver3 = mock(IOperationReceiver.class);
        ThreadConfiguration config1 =
                new ThreadConfiguration.Builder()
                        .setNat64Enabled(false)
                        .setDhcpv6PdEnabled(false)
                        .build();
        ThreadConfiguration config2 =
                new ThreadConfiguration.Builder().setNat64Enabled(true).build();
        ThreadConfiguration config3 =
                new ThreadConfiguration.Builder(config2).build(); // Same as config2

        mService.setConfiguration(config1, mockReceiver1);
        mService.setConfiguration(config2, mockReceiver2);
        mService.setConfiguration(config3, mockReceiver3);
        mTestLooper.dispatchAll();

        assertThat(mPersistentSettings.getConfiguration()).isEqualTo(config3);
        InOrder inOrder = Mockito.inOrder(mockReceiver1, mockReceiver2, mockReceiver3);
        inOrder.verify(mockReceiver1).onSuccess();
        inOrder.verify(mockReceiver2).onSuccess();
        inOrder.verify(mockReceiver3).onSuccess();
    }

    @Test
    public void setConfiguration_enablesNat64_requestsNat64CidrAndConfiguresOtdaemon()
            throws Exception {
        mService.initialize();
        mTestLooper.dispatchAll();
        clearInvocations(mMockRoutingCoordinatorManager, mFakeOtDaemon);

        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        mService.setConfiguration(
                new ThreadConfiguration.Builder().setNat64Enabled(true).build(), mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
        verify(mMockRoutingCoordinatorManager, times(1)).requestDownstreamAddress(any());
        assertThat(mFakeOtDaemon.getConfiguration().nat64Enabled).isTrue();
        assertThat(mFakeOtDaemon.getNat64Cidr()).isEqualTo(TEST_NAT64_CIDR.toString());
    }

    @Test
    public void setConfiguration_enablesNat64AndOtDaemonRemoteFailure_serviceDoesNotCrash()
            throws Exception {
        mService.initialize();
        mTestLooper.dispatchAll();
        clearInvocations(mMockRoutingCoordinatorManager, mFakeOtDaemon);
        mFakeOtDaemon.setSetNat64CidrException(
                new RemoteException("ot-daemon setNat64Cidr() throws"));

        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        mService.setConfiguration(
                new ThreadConfiguration.Builder().setNat64Enabled(true).build(), mockReceiver);
        mTestLooper.dispatchAll();

        verify(mFakeOtDaemon, times(1))
                .setNat64Cidr(eq(TEST_NAT64_CIDR.toString()), any(IOtStatusReceiver.class));
    }

    @Test
    public void setConfiguration_disablesNat64_releasesNat64CidrAndConfiguresOtdaemon()
            throws Exception {
        mPersistentSettings.putConfiguration(
                new ThreadConfiguration.Builder().setNat64Enabled(true).build());
        mService.initialize();
        mTestLooper.dispatchAll();
        clearInvocations(mMockRoutingCoordinatorManager, mFakeOtDaemon);

        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        mService.setConfiguration(
                new ThreadConfiguration.Builder().setNat64Enabled(false).build(), mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
        verify(mMockRoutingCoordinatorManager, times(1)).releaseDownstream(any());
        verify(mMockRoutingCoordinatorManager, never()).requestDownstreamAddress(any());
        assertThat(mFakeOtDaemon.getConfiguration().nat64Enabled).isFalse();
        assertThat(mFakeOtDaemon.getNat64Cidr()).isNull();
    }

    @Test
    public void initialize_borderRouterEnabled_upstreamNetworkRequestHasExpectedTransportAndCaps() {
        assumeTrue(mIsBorderRouterEnabled);

        mService.initialize();
        mTestLooper.dispatchAll();

        ArgumentCaptor<NetworkRequest> networkRequestCaptor =
                ArgumentCaptor.forClass(NetworkRequest.class);
        verify(mMockConnectivityManager, atLeastOnce())
                .registerNetworkCallback(
                        networkRequestCaptor.capture(),
                        any(ConnectivityManager.NetworkCallback.class),
                        any(Handler.class));
        List<NetworkRequest> upstreamNetworkRequests =
                networkRequestCaptor.getAllValues().stream()
                        .filter(nr -> !nr.hasTransport(TRANSPORT_THREAD))
                        .toList();
        assertThat(upstreamNetworkRequests.size()).isEqualTo(1);
        NetworkRequest upstreamNetworkRequest = upstreamNetworkRequests.get(0);
        assertThat(upstreamNetworkRequest.hasTransport(TRANSPORT_WIFI)).isTrue();
        assertThat(upstreamNetworkRequest.hasTransport(TRANSPORT_ETHERNET)).isTrue();
        assertThat(upstreamNetworkRequest.hasCapability(NET_CAPABILITY_NOT_VPN)).isTrue();
        assertThat(upstreamNetworkRequest.hasCapability(NET_CAPABILITY_INTERNET)).isTrue();
    }

    @Test
    public void setTestNetworkAsUpstream_upstreamNetworkRequestAlwaysDisallowsVpn() {
        mService.initialize();
        mTestLooper.dispatchAll();
        clearInvocations(mMockConnectivityManager);

        final IOperationReceiver mockReceiver1 = mock(IOperationReceiver.class);
        final IOperationReceiver mockReceiver2 = mock(IOperationReceiver.class);
        mService.setTestNetworkAsUpstream("test-network", mockReceiver1);
        mService.setTestNetworkAsUpstream(null, mockReceiver2);
        mTestLooper.dispatchAll();

        ArgumentCaptor<NetworkRequest> networkRequestCaptor =
                ArgumentCaptor.forClass(NetworkRequest.class);
        verify(mMockConnectivityManager, times(2))
                .registerNetworkCallback(
                        networkRequestCaptor.capture(),
                        any(ConnectivityManager.NetworkCallback.class),
                        any(Handler.class));
        assertThat(networkRequestCaptor.getAllValues().size()).isEqualTo(2);
        NetworkRequest networkRequest1 = networkRequestCaptor.getAllValues().get(0);
        NetworkRequest networkRequest2 = networkRequestCaptor.getAllValues().get(1);
        assertThat(networkRequest1.getNetworkSpecifier()).isNotNull();
        assertThat(networkRequest1.hasCapability(NET_CAPABILITY_NOT_VPN)).isTrue();
        assertThat(networkRequest2.getNetworkSpecifier()).isNull();
        assertThat(networkRequest2.hasCapability(NET_CAPABILITY_NOT_VPN)).isTrue();
    }

    @Test
    public void runOtCtlCommand_noPermission_throwsSecurityException() {
        doThrow(new SecurityException(""))
                .when(mContext)
                .enforceCallingOrSelfPermission(eq(PERMISSION_THREAD_NETWORK_PRIVILEGED), any());
        doThrow(new SecurityException(""))
                .when(mContext)
                .enforceCallingOrSelfPermission(eq(PERMISSION_THREAD_NETWORK_TESTING), any());

        assertThrows(
                SecurityException.class,
                () -> mService.runOtCtlCommand("", false, new IOutputReceiver.Default()));
    }

    @Test
    public void runOtCtlCommand_otDaemonRemoteFailure_receiverOnErrorIsCalled() throws Exception {
        mService.initialize();
        final IOutputReceiver mockReceiver = mock(IOutputReceiver.class);
        mFakeOtDaemon.setRunOtCtlCommandException(
                new RemoteException("ot-daemon runOtCtlCommand() throws"));

        mService.runOtCtlCommand("ot-ctl state", false, mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onError(eq(ERROR_INTERNAL_ERROR), anyString());
    }

    @Test
    public void activateEphemeralKeyMode_borderRouterEnabled_succeed() throws Exception {
        assumeTrue(mIsBorderRouterEnabled);
        mService.initialize();
        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);

        mService.activateEphemeralKeyMode(1_000L, mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
    }

    @Test
    public void deactivateEphemeralKeyMode_borderRouterEnabled_succeed() throws Exception {
        assumeTrue(mIsBorderRouterEnabled);
        mService.initialize();
        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);

        mService.deactivateEphemeralKeyMode(mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
    }
}
