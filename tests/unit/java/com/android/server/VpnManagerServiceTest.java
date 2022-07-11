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

package com.android.server;

import static android.os.Build.VERSION_CODES.R;

import static com.android.testutils.ContextUtils.mockService;
import static com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.UserIdInt;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.filters.SmallTest;

import com.android.server.connectivity.Vpn;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(R) // VpnManagerService is not available before R
@SmallTest
public class VpnManagerServiceTest extends VpnTestBase {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private static final int TIMEOUT_MS = 2_000;

    @Mock Context mContext;
    @Mock Context mSystemContext;
    @Mock Context mUserAllContext;
    private HandlerThread mHandlerThread;
    @Mock private Vpn mVpn;
    @Mock private INetworkManagementService mNms;
    @Mock private ConnectivityManager mCm;
    @Mock private UserManager mUserManager;
    @Mock private INetd mNetd;
    @Mock private PackageManager mPackageManager;

    private VpnManagerServiceDependencies mDeps;
    private VpnManagerService mService;
    private BroadcastReceiver mUserPresentReceiver;
    private BroadcastReceiver mIntentReceiver;
    private final String mNotMyVpnPkg = "com.not.my.vpn";

    class VpnManagerServiceDependencies extends VpnManagerService.Dependencies {
        @Override
        public HandlerThread makeHandlerThread() {
            return mHandlerThread;
        }

        @Override
        public INetworkManagementService getINetworkManagementService() {
            return mNms;
        }

        @Override
        public INetd getNetd() {
            return mNetd;
        }

        @Override
        public Vpn createVpn(Looper looper, Context context, INetworkManagementService nms,
                INetd netd, @UserIdInt int userId) {
            return mVpn;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread("TestVpnManagerService");
        mDeps = new VpnManagerServiceDependencies();
        doReturn(mUserAllContext).when(mContext).createContextAsUser(UserHandle.ALL, 0);
        doReturn(mSystemContext).when(mContext).createContextAsUser(UserHandle.SYSTEM, 0);
        doReturn(mPackageManager).when(mContext).getPackageManager();
        setMockedPackages(mPackageManager, sPackages);

        mockService(mContext, ConnectivityManager.class, Context.CONNECTIVITY_SERVICE, mCm);
        mockService(mContext, UserManager.class, Context.USER_SERVICE, mUserManager);
        doReturn(SYSTEM_USER).when(mUserManager).getUserInfo(eq(SYSTEM_USER_ID));

        mService = new VpnManagerService(mContext, mDeps);
        mService.systemReady();

        final ArgumentCaptor<BroadcastReceiver> intentReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        final ArgumentCaptor<BroadcastReceiver> userPresentReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mSystemContext).registerReceiver(
                userPresentReceiverCaptor.capture(), any(), any(), any());
        verify(mUserAllContext, times(2)).registerReceiver(
                intentReceiverCaptor.capture(), any(), any(), any());
        mUserPresentReceiver = userPresentReceiverCaptor.getValue();
        mIntentReceiver = intentReceiverCaptor.getValue();

        // Add user to create vpn in mVpn
        onUserStarted(SYSTEM_USER_ID);
        assertNotNull(mService.mVpns.get(SYSTEM_USER_ID));
    }

    @Test
    public void testUpdateAppExclusionList() {
        // Start vpn
        mService.startVpnProfile(TEST_VPN_PKG);
        verify(mVpn).startVpnProfile(eq(TEST_VPN_PKG));

        // Remove package due to package replaced.
        onPackageRemoved(PKGS[0], PKG_UIDS[0], true /* isReplacing */);
        verify(mVpn, never()).refreshPlatformVpnAppExclusionList();

        // Add package due to package replaced.
        onPackageAdded(PKGS[0], PKG_UIDS[0], true /* isReplacing */);
        verify(mVpn, never()).refreshPlatformVpnAppExclusionList();

        // Remove package
        onPackageRemoved(PKGS[0], PKG_UIDS[0], false /* isReplacing */);
        verify(mVpn).refreshPlatformVpnAppExclusionList();

        // Add the package back
        onPackageAdded(PKGS[0], PKG_UIDS[0], false /* isReplacing */);
        verify(mVpn, times(2)).refreshPlatformVpnAppExclusionList();
    }

    @Test
    public void testStartVpnProfileFromDiffPackage() {
        assertThrows(
                SecurityException.class, () -> mService.startVpnProfile(mNotMyVpnPkg));
    }

    @Test
    public void testStopVpnProfileFromDiffPackage() {
        assertThrows(SecurityException.class, () -> mService.stopVpnProfile(mNotMyVpnPkg));
    }

    @Test
    public void testGetProvisionedVpnProfileStateFromDiffPackage() {
        assertThrows(SecurityException.class, () ->
                mService.getProvisionedVpnProfileState(mNotMyVpnPkg));
    }

    @Test
    public void testGetProvisionedVpnProfileState() {
        mService.getProvisionedVpnProfileState(TEST_VPN_PKG);
        verify(mVpn).getProvisionedVpnProfileState(TEST_VPN_PKG);
    }

    private Intent buildIntent(String action, String packageName, int userId, int uid,
            boolean isReplacing) {
        final Intent intent = new Intent(action);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.putExtra(Intent.EXTRA_REPLACING, isReplacing);
        if (packageName != null) {
            intent.setData(Uri.fromParts("package" /* scheme */, packageName, null /* fragment */));
        }

        return intent;
    }

    private void sendIntent(Intent intent) {
        final Handler h = mHandlerThread.getThreadHandler();

        // Send in handler thread.
        h.post(() -> mIntentReceiver.onReceive(mContext, intent));
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);
    }

    private void onUserStarted(int userId) {
        sendIntent(buildIntent(Intent.ACTION_USER_STARTED,
                null /* packageName */, userId, -1 /* uid */, false /* isReplacing */));
    }

    private void onPackageAdded(String packageName, int userId, int uid, boolean isReplacing) {
        sendIntent(buildIntent(Intent.ACTION_PACKAGE_ADDED, packageName, userId, uid, isReplacing));
    }

    private void onPackageAdded(String packageName, int uid, boolean isReplacing) {
        onPackageAdded(packageName, UserHandle.USER_SYSTEM, uid, isReplacing);
    }

    private void onPackageRemoved(String packageName, int userId, int uid, boolean isReplacing) {
        sendIntent(buildIntent(Intent.ACTION_PACKAGE_REMOVED, packageName, userId, uid,
                isReplacing));
    }

    private void onPackageRemoved(String packageName, int uid, boolean isReplacing) {
        onPackageRemoved(packageName, UserHandle.USER_SYSTEM, uid, isReplacing);
    }
}
