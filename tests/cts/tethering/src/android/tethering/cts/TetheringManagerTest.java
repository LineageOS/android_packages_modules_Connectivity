/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.tethering.test;

import static android.Manifest.permission.CREATE_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MODIFY_PHONE_STATE;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.Manifest.permission.WRITE_SETTINGS;
import static android.app.ActivityManager.getCurrentUser;
import static android.content.pm.PackageManager.FEATURE_TELEPHONY;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_NCM;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_VIRTUAL;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.TetheringManager.TETHER_ERROR_DUPLICATE_REQUEST;
import static android.net.TetheringManager.TETHER_ERROR_ENTITLEMENT_UNKNOWN;
import static android.net.TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_UNKNOWN_REQUEST;
import static android.net.TetheringManager.TETHER_ERROR_UNSUPPORTED;
import static android.net.cts.util.CtsTetheringUtils.isAnyIfaceMatch;
import static android.os.Process.INVALID_UID;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.net.TetheringManager.OnTetheringEntitlementResultListener;
import android.net.TetheringManager.TetheringInterfaceRegexps;
import android.net.TetheringManager.TetheringRequest;
import android.net.cts.util.CtsNetUtils;
import android.net.cts.util.CtsNetUtils.TestNetworkCallback;
import android.net.cts.util.CtsTetheringUtils;
import android.net.cts.util.CtsTetheringUtils.StartTetheringCallback;
import android.net.cts.util.CtsTetheringUtils.TestTetheringEventCallback;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.UserType;
import com.android.bedstead.harrier.annotations.UserTest;
import com.android.modules.utils.build.SdkLevel;
import com.android.testutils.ConnectivityDiagnosticsCollector;
import com.android.testutils.ParcelUtils;
import com.android.testutils.com.android.testutils.CarrierConfigRule;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@RunWith(BedsteadJUnit4.class)
public class TetheringManagerTest {
    @Rule
    public final CarrierConfigRule mCarrierConfigRule = new CarrierConfigRule();
    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private Context mContext;

    private ConnectivityManager mCm;
    private TetheringManager mTM;
    private WifiManager mWm;
    private PackageManager mPm;

    private TetherChangeReceiver mTetherChangeReceiver;
    private CtsNetUtils mCtsNetUtils;
    private CtsTetheringUtils mCtsTetheringUtils;

    private static final int DEFAULT_TIMEOUT_MS = 60_000;
    private static final String TETHERING_CONNECTOR_CLASS = "android.net.ITetheringConnector";
    // String replacement is needed to prevent the class name from being modified
    // by NetworkStack JarJar rules.
    private static final String NETWORKSTACK_CONNECTOR_CLASS =
            "android$net$INetworkStackConnector".replace('$', '.');

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mCm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mTM = (TetheringManager) mContext.getSystemService(Context.TETHERING_SERVICE);
        mWm = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mPm = mContext.getPackageManager();
        mCtsNetUtils = new CtsNetUtils(mContext);
        mCtsTetheringUtils = new CtsTetheringUtils(mContext);
        mTetherChangeReceiver = new TetherChangeReceiver();
        final IntentFilter filter = new IntentFilter(
                TetheringManager.ACTION_TETHER_STATE_CHANGED);
        final Intent intent = mContext.registerReceiver(mTetherChangeReceiver, filter);
        if (intent != null) mTetherChangeReceiver.onReceive(null, intent);
    }

    @After
    public void tearDown() throws Exception {
        mCtsTetheringUtils.stopAllTethering();
        mContext.unregisterReceiver(mTetherChangeReceiver);
    }

    private class TetherChangeReceiver extends BroadcastReceiver {
        private class TetherState {
            final ArrayList<String> mAvailable;
            final ArrayList<String> mActive;
            final ArrayList<String> mErrored;

            TetherState(Intent intent) {
                mAvailable = intent.getStringArrayListExtra(
                        TetheringManager.EXTRA_AVAILABLE_TETHER);
                mActive = intent.getStringArrayListExtra(
                        TetheringManager.EXTRA_ACTIVE_TETHER);
                mErrored = intent.getStringArrayListExtra(
                        TetheringManager.EXTRA_ERRORED_TETHER);
            }
        }

        @Override
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (action.equals(TetheringManager.ACTION_TETHER_STATE_CHANGED)) {
                mResult.add(new TetherState(intent));
            }
        }

        public final LinkedBlockingQueue<TetherState> mResult = new LinkedBlockingQueue<>();

        // Expects that tethering reaches the desired state.
        // - If active is true, expects that tethering is enabled on at least one interface
        //   matching ifaceRegexs.
        // - If active is false, expects that tethering is disabled on all the interfaces matching
        //   ifaceRegexs.
        // Fails if any interface matching ifaceRegexs becomes errored.
        public void expectTethering(final boolean active, final String[] ifaceRegexs) {
            while (true) {
                final TetherState state = pollAndAssertNoError(DEFAULT_TIMEOUT_MS, ifaceRegexs);
                assertNotNull("Did not receive expected state change, active: " + active, state);

                if (isIfaceActive(ifaceRegexs, state) == active) return;
            }
        }

        private TetherState pollAndAssertNoError(final int timeout, final String[] ifaceRegexs) {
            final TetherState state = pollTetherState(timeout);
            assertNoErroredIfaces(state, ifaceRegexs);
            return state;
        }

        private TetherState pollTetherState(final int timeout) {
            try {
                return mResult.poll(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                fail("No result after " + timeout + " ms");
                return null;
            }
        }

        private boolean isIfaceActive(final String[] ifaceRegexs, final TetherState state) {
            return isAnyIfaceMatch(ifaceRegexs, state.mActive);
        }

        private void assertNoErroredIfaces(final TetherState state, final String[] ifaceRegexs) {
            if (state == null || state.mErrored == null) return;

            if (isAnyIfaceMatch(ifaceRegexs, state.mErrored)) {
                fail("Found failed tethering interfaces: " + Arrays.toString(state.mErrored.toArray()));
            }
        }
    }

    @Test
    public void testStartTetheringWithStateChangeBroadcast() throws Exception {
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();

            final String[] wifiRegexs = mTM.getTetherableWifiRegexs();
            mCtsTetheringUtils.startWifiTethering(tetherEventCallback);

            mTetherChangeReceiver.expectTethering(true /* active */, wifiRegexs);

            mCtsTetheringUtils.stopWifiTethering(tetherEventCallback);
            mTetherChangeReceiver.expectTethering(false /* active */, wifiRegexs);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }

    }

    @Test
    public void testStartTetheringDuplicateRequestRejected() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();

            final String[] wifiRegexs = mTM.getTetherableWifiRegexs();
            mCtsTetheringUtils.startWifiTethering(tetherEventCallback);
            mTetherChangeReceiver.expectTethering(true /* active */, wifiRegexs);

            final StartTetheringCallback startTetheringCallback = new StartTetheringCallback();
            runAsShell(TETHER_PRIVILEGED, () -> {
                mTM.startTethering(new TetheringRequest.Builder(TETHERING_WIFI).build(),
                        c -> c.run() /* executor */, startTetheringCallback);
                startTetheringCallback.expectTetheringFailed(TETHER_ERROR_DUPLICATE_REQUEST);
            });
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    private SoftApConfiguration createSoftApConfiguration(@NonNull String ssid) {
        SoftApConfiguration config;
        if (SdkLevel.isAtLeastT()) {
            config = new SoftApConfiguration.Builder()
                    .setWifiSsid(WifiSsid.fromBytes(ssid.getBytes(StandardCharsets.UTF_8)))
                    .build();
        } else {
            config = new SoftApConfiguration.Builder()
                    .setSsid(ssid)
                    .build();
        }
        return config;
    }

    @Test
    public void testTetheringRequest() {
        SoftApConfiguration softApConfiguration = createSoftApConfiguration("SSID");
        final TetheringRequest tr = new TetheringRequest.Builder(TETHERING_WIFI)
                .setSoftApConfiguration(softApConfiguration)
                .build();
        assertEquals(TETHERING_WIFI, tr.getTetheringType());
        assertNull(tr.getLocalIpv4Address());
        assertNull(tr.getClientStaticIpv4Address());
        assertFalse(tr.isExemptFromEntitlementCheck());
        assertTrue(tr.getShouldShowEntitlementUi());
        assertEquals(CONNECTIVITY_SCOPE_GLOBAL, tr.getConnectivityScope());
        assertEquals(softApConfiguration, tr.getSoftApConfiguration());
        assertEquals(INVALID_UID, tr.getUid());
        assertNull(tr.getPackageName());
        assertEquals(tr.toString(), "TetheringRequest[ "
                + "TETHERING_WIFI, "
                + "showProvisioningUi, "
                + "CONNECTIVITY_SCOPE_GLOBAL, "
                + "softApConfig=" + softApConfiguration.toString()
                + " ]");

        final LinkAddress localAddr = new LinkAddress("192.168.24.5/24");
        final LinkAddress clientAddr = new LinkAddress("192.168.24.100/24");
        final TetheringRequest tr2 = new TetheringRequest.Builder(TETHERING_USB)
                .setStaticIpv4Addresses(localAddr, clientAddr)
                .setExemptFromEntitlementCheck(true)
                .setShouldShowEntitlementUi(false)
                .setConnectivityScope(CONNECTIVITY_SCOPE_LOCAL)
                .build();
        int uid = 1000;
        String packageName = "package";
        tr2.setUid(uid);
        tr2.setPackageName(packageName);

        assertEquals(localAddr, tr2.getLocalIpv4Address());
        assertEquals(clientAddr, tr2.getClientStaticIpv4Address());
        assertEquals(TETHERING_USB, tr2.getTetheringType());
        assertTrue(tr2.isExemptFromEntitlementCheck());
        assertFalse(tr2.getShouldShowEntitlementUi());
        assertEquals(CONNECTIVITY_SCOPE_LOCAL, tr2.getConnectivityScope());
        assertNull(tr2.getSoftApConfiguration());
        assertEquals(uid, tr2.getUid());
        assertEquals(packageName, tr2.getPackageName());
        assertEquals(tr2.toString(), "TetheringRequest[ "
                + "TETHERING_USB, "
                + "localIpv4Address=" + localAddr + ", "
                + "staticClientAddress=" + clientAddr + ", "
                + "exemptFromEntitlementCheck, "
                + "CONNECTIVITY_SCOPE_LOCAL, "
                + "uid=1000, "
                + "packageName=package"
                + " ]");

        final TetheringRequest tr3 = new TetheringRequest.Builder(TETHERING_USB)
                .setStaticIpv4Addresses(localAddr, clientAddr)
                .setExemptFromEntitlementCheck(true)
                .setShouldShowEntitlementUi(false)
                .setConnectivityScope(CONNECTIVITY_SCOPE_LOCAL)
                .build();
        tr3.setUid(uid);
        tr3.setPackageName(packageName);
        assertEquals(tr2, tr3);

        final String interfaceName = "test_iface";
        final TetheringRequest tr4 = new TetheringRequest.Builder(TETHERING_VIRTUAL)
                .setInterfaceName(interfaceName)
                .setConnectivityScope(CONNECTIVITY_SCOPE_GLOBAL).build();
        assertEquals(interfaceName, tr4.getInterfaceName());
        assertEquals(CONNECTIVITY_SCOPE_GLOBAL, tr4.getConnectivityScope());
    }

    @Test
    public void testTetheringRequestSetSoftApConfigurationFailsWhenNotWifi() {
        final SoftApConfiguration softApConfiguration = createSoftApConfiguration("SSID");
        for (int type : List.of(TETHERING_USB, TETHERING_BLUETOOTH, TETHERING_WIFI_P2P,
                TETHERING_NCM, TETHERING_ETHERNET)) {
            try {
                new TetheringRequest.Builder(type).setSoftApConfiguration(softApConfiguration);
                fail("Was able to set SoftApConfiguration for tethering type " + type);
            } catch (IllegalArgumentException e) {
                // Success
            }
        }
    }

    @Test
    public void testTetheringRequestSetInterfaceNameFailsExceptTetheringVirtual() {
        for (int type : List.of(TETHERING_USB, TETHERING_BLUETOOTH, TETHERING_NCM,
                TETHERING_ETHERNET)) {
            try {
                new TetheringRequest.Builder(type).setInterfaceName("test_iface");
                fail("Was able to set interface name for tethering type " + type);
            } catch (IllegalArgumentException e) {
                // Success
            }
        }
    }

    @Test
    public void testTetheringRequestParcelable() {
        final SoftApConfiguration softApConfiguration = createSoftApConfiguration("SSID");
        final LinkAddress localAddr = new LinkAddress("192.168.24.5/24");
        final LinkAddress clientAddr = new LinkAddress("192.168.24.100/24");
        final TetheringRequest withApConfig = new TetheringRequest.Builder(TETHERING_WIFI)
                .setSoftApConfiguration(softApConfiguration)
                .setStaticIpv4Addresses(localAddr, clientAddr)
                .setExemptFromEntitlementCheck(true)
                .setShouldShowEntitlementUi(false).build();
        final TetheringRequest withoutApConfig = new TetheringRequest.Builder(TETHERING_WIFI)
                .setStaticIpv4Addresses(localAddr, clientAddr)
                .setExemptFromEntitlementCheck(true)
                .setShouldShowEntitlementUi(false).build();
        assertEquals(withApConfig, ParcelUtils.parcelingRoundTrip(withApConfig));
        assertEquals(withoutApConfig, ParcelUtils.parcelingRoundTrip(withoutApConfig));
        assertNotEquals(withApConfig, ParcelUtils.parcelingRoundTrip(withoutApConfig));
        assertNotEquals(withoutApConfig, ParcelUtils.parcelingRoundTrip(withApConfig));

        final TetheringRequest withIfaceName = new TetheringRequest.Builder(TETHERING_VIRTUAL)
                .setInterfaceName("test_iface").build();
        assertEquals(withIfaceName, ParcelUtils.parcelingRoundTrip(withIfaceName));
    }

    @Test
    public void testRegisterTetheringEventCallback() throws Exception {
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();

        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();

            SoftApConfiguration softApConfig = SdkLevel.isAtLeastB()
                    ? createSoftApConfiguration("SSID") : null;
            final TetheringInterface tetheredIface =
                    mCtsTetheringUtils.startWifiTethering(tetherEventCallback, softApConfig);

            assertNotNull(tetheredIface);
            final String wifiTetheringIface = tetheredIface.getInterface();
            if  (SdkLevel.isAtLeastB()) {
                assertEquals(softApConfig, tetheredIface.getSoftApConfiguration());
            }

            mCtsTetheringUtils.stopWifiTethering(tetherEventCallback);

            if (SdkLevel.isAtLeastB()) {
                assertThrows(UnsupportedOperationException.class,
                        () -> runAsShell(TETHER_PRIVILEGED,
                                () -> mTM.tether(wifiTetheringIface)));
            } else {
                final int ret = runAsShell(TETHER_PRIVILEGED, () -> mTM.tether(wifiTetheringIface));
                assertEquals(TETHER_ERROR_UNSUPPORTED, ret);
            }
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testGetTetherableInterfaceRegexps() {
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        tetherEventCallback.assumeTetheringSupported();

        final TetheringInterfaceRegexps tetherableRegexs =
                tetherEventCallback.getTetheringInterfaceRegexps();
        final List<String> wifiRegexs = tetherableRegexs.getTetherableWifiRegexs();
        final List<String> usbRegexs = tetherableRegexs.getTetherableUsbRegexs();
        final List<String> btRegexs = tetherableRegexs.getTetherableBluetoothRegexs();

        assertEquals(wifiRegexs, Arrays.asList(mTM.getTetherableWifiRegexs()));
        assertEquals(usbRegexs, Arrays.asList(mTM.getTetherableUsbRegexs()));
        assertEquals(btRegexs, Arrays.asList(mTM.getTetherableBluetoothRegexs()));

        //Verify that any regex name should only contain in one array.
        wifiRegexs.forEach(s -> assertFalse(usbRegexs.contains(s)));
        wifiRegexs.forEach(s -> assertFalse(btRegexs.contains(s)));
        usbRegexs.forEach(s -> assertFalse(btRegexs.contains(s)));

        mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
    }

    @Test
    public void testStopAllTethering() throws Exception {
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);

            // TODO: start ethernet tethering here when TetheringManagerTest is moved to
            // TetheringIntegrationTest.

            mCtsTetheringUtils.startWifiTethering(tetherEventCallback);

            mCtsTetheringUtils.stopAllTethering();
            tetherEventCallback.expectNoTetheringActive();
        } catch (Throwable e) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
                // This test fails on T devices in rare cases due to hostapd hanging. Log the
                // hostapd callstack to help analyze the failure the next time it happens.
                final ConnectivityDiagnosticsCollector collector =
                        ConnectivityDiagnosticsCollector.getInstance();
                if (collector != null) {
                    collector.collectCommandOutput(
                            "getprop init.svc_debug_pid.hostapd | xargs debuggerd -b", "sh", e);
                }
            }
            throw e;
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testStopTetheringRequestNoMatchFailure() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();

            final StartTetheringCallback startTetheringCallback = new StartTetheringCallback();
            mTM.startTethering(new TetheringRequest.Builder(TETHERING_VIRTUAL).build(),
                    c -> c.run(), startTetheringCallback);

            // Stopping a non-matching request should have no effect
            TetheringRequest nonMatchingRequest = new TetheringRequest.Builder(TETHERING_VIRTUAL)
                    .setInterfaceName("iface")
                    .build();
            mCtsTetheringUtils.stopTethering(nonMatchingRequest, false /* success */);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testStopTetheringRequestMatchSuccess() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();

            SoftApConfiguration softApConfig = new SoftApConfiguration.Builder()
                    .setWifiSsid(WifiSsid.fromBytes("This is one config"
                            .getBytes(StandardCharsets.UTF_8))).build();
            mCtsTetheringUtils.startWifiTethering(tetherEventCallback, softApConfig);

            // Stopping the active request should stop tethering.
            TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI)
                    .setSoftApConfiguration(softApConfig)
                    .build();
            mCtsTetheringUtils.stopTethering(request, true /* success */);
            tetherEventCallback.expectNoTetheringActive();
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testStopTetheringRequestFuzzyMatchSuccess() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();

            SoftApConfiguration softApConfig = new SoftApConfiguration.Builder()
                    .setWifiSsid(WifiSsid.fromBytes("This is one config"
                            .getBytes(StandardCharsets.UTF_8))).build();
            mCtsTetheringUtils.startWifiTethering(tetherEventCallback, softApConfig);

            // Stopping with a fuzzy matching request should stop tethering.
            final LinkAddress localAddr = new LinkAddress("192.168.24.5/24");
            final LinkAddress clientAddr = new LinkAddress("192.168.24.100/24");
            TetheringRequest fuzzyMatchingRequest = new TetheringRequest.Builder(TETHERING_WIFI)
                    .setSoftApConfiguration(softApConfig)
                    .setShouldShowEntitlementUi(true)
                    .setStaticIpv4Addresses(localAddr, clientAddr)
                    .build();
            mCtsTetheringUtils.stopTethering(fuzzyMatchingRequest, true /* success */);
            tetherEventCallback.expectNoTetheringActive();
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testStartTetheringNoPermission() throws Exception {
        final StartTetheringCallback startTetheringCallback = new StartTetheringCallback();

        // No permission
        mTM.startTethering(new TetheringRequest.Builder(TETHERING_WIFI).build(),
                c -> c.run() /* executor */, startTetheringCallback);
        startTetheringCallback.expectTetheringFailed(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);

        // WRITE_SETTINGS not sufficient
        if (SdkLevel.isAtLeastB()) {
            runAsShell(WRITE_SETTINGS, () -> {
                mTM.startTethering(new TetheringRequest.Builder(TETHERING_WIFI).build(),
                        c -> c.run() /* executor */, startTetheringCallback);
                startTetheringCallback.expectTetheringFailed(
                        TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
            });
        }
    }

    private class EntitlementResultListener implements OnTetheringEntitlementResultListener {
        private final CompletableFuture<Integer> future = new CompletableFuture<>();

        @Override
        public void onTetheringEntitlementResult(int result) {
            future.complete(result);
        }

        public int get(long timeout, TimeUnit unit) throws Exception {
            return future.get(timeout, unit);
        }

    }

    private void assertEntitlementResult(final Consumer<EntitlementResultListener> functor,
            final int expect) throws Exception {
        runAsShell(TETHER_PRIVILEGED, () -> {
            final EntitlementResultListener listener = new EntitlementResultListener();
            functor.accept(listener);

            assertEquals(expect, listener.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        });
    }

    private boolean isTetheringSupported() {
        return runAsShell(TETHER_PRIVILEGED, () -> mTM.isTetheringSupported());
    }

    @Test
    public void testRequestLatestEntitlementResult() throws Exception {
        assumeTrue(isTetheringSupported());
        assumeTrue(mPm.hasSystemFeature(FEATURE_TELEPHONY));
        // Verify that requestLatestTetheringEntitlementResult() can get entitlement
        // result(TETHER_ERROR_ENTITLEMENT_UNKNOWN due to invalid downstream type) via listener.
        assertEntitlementResult(listener -> mTM.requestLatestTetheringEntitlementResult(
                TETHERING_WIFI_P2P, false, c -> c.run(), listener),
                TETHER_ERROR_ENTITLEMENT_UNKNOWN);

        // Verify that requestLatestTetheringEntitlementResult() can get entitlement
        // result(TETHER_ERROR_ENTITLEMENT_UNKNOWN due to invalid downstream type) via receiver.
        assertEntitlementResult(listener -> mTM.requestLatestTetheringEntitlementResult(
                TETHERING_WIFI_P2P,
                new ResultReceiver(null /* handler */) {
                    @Override
                    public void onReceiveResult(int resultCode, Bundle resultData) {
                        listener.onTetheringEntitlementResult(resultCode);
                    }
                }, false),
                TETHER_ERROR_ENTITLEMENT_UNKNOWN);

        // Do not request TETHERING_WIFI entitlement result if TETHERING_WIFI is not available.
        assumeTrue(mTM.getTetherableWifiRegexs().length > 0);

        // Verify that null listener will cause IllegalArgumentException.
        try {
            mTM.requestLatestTetheringEntitlementResult(
                    TETHERING_WIFI, false, c -> c.run(), null);
        } catch (IllegalArgumentException expect) { }

        // Override carrier config to ignore entitlement check.
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(CarrierConfigManager.KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL, false);
        mCarrierConfigRule.addConfigOverrides(
                SubscriptionManager.getDefaultSubscriptionId(), bundle);

        // Verify that requestLatestTetheringEntitlementResult() can get entitlement
        // result TETHER_ERROR_NO_ERROR due to provisioning bypassed.
        assertEntitlementResult(listener -> mTM.requestLatestTetheringEntitlementResult(
                TETHERING_WIFI, false, c -> c.run(), listener), TETHER_ERROR_NO_ERROR);
    }

    private boolean isTetheringApnRequired() {
        final TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        return runAsShell(MODIFY_PHONE_STATE, () -> tm.isTetheringApnRequired());

    }

    @Test
    public void testTetheringUpstream() throws Exception {
        assumeTrue(mPm.hasSystemFeature(FEATURE_TELEPHONY));
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();

        boolean previousWifiEnabledState = false;

        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();

            previousWifiEnabledState = mWm.isWifiEnabled();
            if (previousWifiEnabledState) {
                mCtsNetUtils.ensureWifiDisconnected(null);
            }

            final TestNetworkCallback networkCallback = new TestNetworkCallback();
            Network activeNetwork = null;
            try {
                mCm.registerDefaultNetworkCallback(networkCallback);
                activeNetwork = networkCallback.waitForAvailable();
            } finally {
                mCm.unregisterNetworkCallback(networkCallback);
            }

            assertNotNull("No active network. Please ensure the device has working mobile data.",
                    activeNetwork);
            final NetworkCapabilities activeNetCap = mCm.getNetworkCapabilities(activeNetwork);

            // If active nework is ETHERNET, tethering may not use cell network as upstream.
            assumeFalse(activeNetCap.hasTransport(TRANSPORT_ETHERNET));

            assertTrue(activeNetCap.hasTransport(TRANSPORT_CELLULAR));

            mCtsTetheringUtils.startWifiTethering(tetherEventCallback);

            final int expectedCap = isTetheringApnRequired()
                    ? NET_CAPABILITY_DUN : NET_CAPABILITY_INTERNET;
            final Network network = tetherEventCallback.getCurrentValidUpstream();
            final NetworkCapabilities netCap = mCm.getNetworkCapabilities(network);
            assertTrue(netCap.hasTransport(TRANSPORT_CELLULAR));
            assertTrue(netCap.hasCapability(expectedCap));

            mCtsTetheringUtils.stopWifiTethering(tetherEventCallback);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
            if (previousWifiEnabledState) {
                mCtsNetUtils.connectToWifi();
            }
        }
    }

    @Test
    public void testLegacyTetherApisThrowUnsupportedOperationExceptionAfterV() {
        assumeTrue(SdkLevel.isAtLeastB());
        assertThrows(UnsupportedOperationException.class, () -> mTM.tether("iface"));
        assertThrows(UnsupportedOperationException.class, () -> mTM.untether("iface"));
    }

    @Test
    public void testCarrierPrivilegedIsTetheringSupported() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        assumeTrue(mPm.hasSystemFeature(FEATURE_TELEPHONY));
        int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
        mCarrierConfigRule.acquireCarrierPrivilege(defaultSubId);
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();

            assertTrue(mTM.isTetheringSupported());
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testCarrierPrivilegedStartTetheringNonWifiFails() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        assumeTrue(mPm.hasSystemFeature(FEATURE_TELEPHONY));
        int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
        mCarrierConfigRule.acquireCarrierPrivilege(defaultSubId);
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();
            StartTetheringCallback callback = new StartTetheringCallback();
            TetheringRequest request = new TetheringRequest.Builder(TETHERING_USB).build();

            mTM.startTethering(request, Runnable::run, callback);

            callback.expectTetheringFailed(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testCarrierPrivilegedStartTetheringWifiWithoutConfigFails() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        assumeTrue(mPm.hasSystemFeature(FEATURE_TELEPHONY));
        int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
        mCarrierConfigRule.acquireCarrierPrivilege(defaultSubId);
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();
            StartTetheringCallback callback = new StartTetheringCallback();
            TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();

            mTM.startTethering(request, Runnable::run, callback);

            callback.expectTetheringFailed(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testCarrierPrivilegedStartTetheringWifiWithConfigSucceeds() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        assumeTrue(mPm.hasSystemFeature(FEATURE_TELEPHONY));
        int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
        mCarrierConfigRule.acquireCarrierPrivilege(defaultSubId);
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();
            SoftApConfiguration softApConfig = createSoftApConfiguration("Carrier-privileged");

            mCtsTetheringUtils.startWifiTetheringNoPermissions(tetherEventCallback, softApConfig);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testCarrierPrivilegedStopTetheringNonWifiFails() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        assumeTrue(mPm.hasSystemFeature(FEATURE_TELEPHONY));
        int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
        mCarrierConfigRule.acquireCarrierPrivilege(defaultSubId);
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();
            TetheringRequest request = new TetheringRequest.Builder(TETHERING_USB).build();
            CtsTetheringUtils.StopTetheringCallback
                    callback = new CtsTetheringUtils.StopTetheringCallback();

            mTM.stopTethering(request, Runnable::run, callback);

            callback.expectStopTetheringFailed(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testCarrierPrivilegedStopTetheringWifiWithoutConfigFails() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        assumeTrue(mPm.hasSystemFeature(FEATURE_TELEPHONY));
        int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
        mCarrierConfigRule.acquireCarrierPrivilege(defaultSubId);
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();
            TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
            CtsTetheringUtils.StopTetheringCallback
                    callback = new CtsTetheringUtils.StopTetheringCallback();

            mTM.stopTethering(request, Runnable::run, callback);

            callback.expectStopTetheringFailed(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testCarrierPrivilegedStopTetheringWifiWithConfigButNoActiveRequestFails()
            throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        assumeTrue(mPm.hasSystemFeature(FEATURE_TELEPHONY));
        int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
        mCarrierConfigRule.acquireCarrierPrivilege(defaultSubId);
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();
            SoftApConfiguration softApConfig = createSoftApConfiguration("Carrier-privileged");
            TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI)
                    .setSoftApConfiguration(softApConfig)
                    .build();
            CtsTetheringUtils.StopTetheringCallback
                    callback = new CtsTetheringUtils.StopTetheringCallback();

            mTM.stopTethering(request, Runnable::run, callback);

            callback.expectStopTetheringFailed(TETHER_ERROR_UNKNOWN_REQUEST);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    @Test
    public void testCarrierPrivilegedStopTetheringWifiWithConfigSucceeds() throws Exception {
        assumeTrue(SdkLevel.isAtLeastB());
        assumeTrue(mPm.hasSystemFeature(FEATURE_TELEPHONY));
        int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
        mCarrierConfigRule.acquireCarrierPrivilege(defaultSubId);
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();
            SoftApConfiguration softApConfig = createSoftApConfiguration("Carrier-privileged");
            mCtsTetheringUtils.startWifiTetheringNoPermissions(tetherEventCallback, softApConfig);
            TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI)
                    .setSoftApConfiguration(softApConfig)
                    .build();
            CtsTetheringUtils.StopTetheringCallback
                    callback = new CtsTetheringUtils.StopTetheringCallback();

            mTM.stopTethering(request, Runnable::run, callback);

            callback.verifyStopTetheringSucceeded();
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    // Verify the existence of the Tethering/NetworkStack package for the current user.
    // This test will be executed on both SYSTEM and FULL users.
    @Test
    @UserTest({UserType.INITIAL_USER, UserType.SECONDARY_USER})
    public void testCreatePackageContextAsUser() throws PackageManager.NameNotFoundException {
        final List<String> packageNames = List.of(
                resolvePackageName(TETHERING_CONNECTOR_CLASS),
                resolvePackageName(NETWORKSTACK_CONNECTOR_CLASS));
        // Permission CREATE_USERS is required for Android R or below.
        final int currentUserId = runAsShell(CREATE_USERS, INTERACT_ACROSS_USERS,
                () -> getCurrentUser());
        final UserHandle currentUser = UserHandle.of(currentUserId);
        for (String packageName : packageNames) {
            mContext.createPackageContextAsUser(packageName, 0 /* flags */, currentUser);
        }
    }

    @NonNull
    private String resolvePackageName(@NonNull String action) {
        final Intent intent = new Intent(action);
        final List<ResolveInfo> resolveInfoList = mContext.getPackageManager()
                .queryIntentServices(intent, MATCH_SYSTEM_ONLY);
        assertFalse("Failed to resolve package for " + action, resolveInfoList.isEmpty());
        return Objects.requireNonNull(resolveInfoList.get(0).getComponentInfo().packageName);
    }
}
