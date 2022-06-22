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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.UserManager;

import androidx.test.filters.SmallTest;

import com.android.server.connectivity.Vpn;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(R) // VpnManagerService is not available before R
@SmallTest
public class VpnManagerServiceTest extends VpnTestBase {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    @Spy Context mContext;
    private HandlerThread mHandlerThread;
    @Mock private Handler mHandler;
    @Mock private Vpn mVpn;
    @Mock private INetworkManagementService mNms;
    @Mock private ConnectivityManager mCm;
    @Mock private UserManager mUserManager;
    @Mock private INetd mNetd;
    @Mock private PackageManager mPackageManager;
    private VpnManagerServiceDependencies mDeps;
    private VpnManagerService mService;

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
        doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());
        doReturn(mPackageManager).when(mContext).getPackageManager();
        setMockedPackages(mPackageManager, sPackages);

        mockService(mContext, ConnectivityManager.class, Context.CONNECTIVITY_SERVICE, mCm);
        mockService(mContext, UserManager.class, Context.USER_SERVICE, mUserManager);

        doReturn(new Intent()).when(mContext).registerReceiver(
                any() /* receiver */,
                any() /* intentFilter */,
                any() /* broadcastPermission */,
                eq(mHandler) /* scheduler */);
        doReturn(SYSTEM_USER).when(mUserManager).getUserInfo(eq(SYSTEM_USER_ID));
        mService = new VpnManagerService(mContext, mDeps);

        // Add user to create vpn in mVpn
        mService.onUserStarted(SYSTEM_USER_ID);
        assertNotNull(mService.mVpns.get(SYSTEM_USER_ID));
    }

    @Test
    public void testUpdateAppExclusionList() {
        // Start vpn
        mService.startVpnProfile(TEST_VPN_PKG);
        verify(mVpn).startVpnProfile(eq(TEST_VPN_PKG));

        // Remove package due to package replaced.
        mService.onPackageRemoved(PKGS[0], PKG_UIDS[0], true /* isReplacing */);
        verify(mVpn, never()).refreshPlatformVpnAppExclusionList();

        // Add package due to package replaced.
        mService.onPackageAdded(PKGS[0], PKG_UIDS[0], true /* isReplacing */);
        verify(mVpn, never()).refreshPlatformVpnAppExclusionList();

        // Remove package
        mService.onPackageRemoved(PKGS[0], PKG_UIDS[0], false /* isReplacing */);
        verify(mVpn).refreshPlatformVpnAppExclusionList();

        // Add the package back
        mService.onPackageAdded(PKGS[0], PKG_UIDS[0], false /* isReplacing */);
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
}
