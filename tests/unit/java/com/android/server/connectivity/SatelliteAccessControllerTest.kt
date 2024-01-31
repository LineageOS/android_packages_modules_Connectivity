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
package com.android.server.connectivity

import android.Manifest
import android.app.role.OnRoleHoldersChangedListener
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.UserHandle
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DEFAULT_MESSAGING_APP1 = "default_messaging_app_1"
private const val DEFAULT_MESSAGING_APP2 = "default_messaging_app_2"
private const val DEFAULT_MESSAGING_APP1_UID = 1234
private const val DEFAULT_MESSAGING_APP2_UID = 5678

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class SatelliteAccessControllerTest {
    private val context = mock(Context::class.java)
    private val mPackageManager = mock(PackageManager::class.java)
    private val mHandler = mock(Handler::class.java)
    private val mRoleManager =
        mock(SatelliteAccessController.Dependencies::class.java)
    private val mCallback = mock(Consumer::class.java) as Consumer<Set<Int>>
    private val mSatelliteAccessController by lazy {
        SatelliteAccessController(context, mRoleManager, mCallback, mHandler)}
    private var mRoleHolderChangedListener: OnRoleHoldersChangedListener? = null
    @Before
    @Throws(PackageManager.NameNotFoundException::class)
    fun setup() {
        doReturn(mPackageManager).`when`(context).packageManager
        doReturn(PackageManager.PERMISSION_GRANTED)
            .`when`(mPackageManager)
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, DEFAULT_MESSAGING_APP1)
        doReturn(PackageManager.PERMISSION_GRANTED)
            .`when`(mPackageManager)
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, DEFAULT_MESSAGING_APP2)

        // Initialise default message application package1
        val applicationInfo1 = ApplicationInfo()
        applicationInfo1.uid = DEFAULT_MESSAGING_APP1_UID
        doReturn(applicationInfo1)
            .`when`(mPackageManager)
            .getApplicationInfo(eq(DEFAULT_MESSAGING_APP1), anyInt())

        // Initialise default message application package2
        val applicationInfo2 = ApplicationInfo()
        applicationInfo2.uid = DEFAULT_MESSAGING_APP2_UID
        doReturn(applicationInfo2)
            .`when`(mPackageManager)
            .getApplicationInfo(eq(DEFAULT_MESSAGING_APP2), anyInt())

        // Get registered listener using captor
        val listenerCaptor = ArgumentCaptor.forClass(
            OnRoleHoldersChangedListener::class.java
        )
        mSatelliteAccessController.start()
        verify(mRoleManager).addOnRoleHoldersChangedListenerAsUser(
            any(Executor::class.java), listenerCaptor.capture(), any(UserHandle::class.java))
        mRoleHolderChangedListener = listenerCaptor.value
    }

    @Test
    fun test_onRoleHoldersChanged_SatellitePreferredUid_Changed() {
        doReturn(listOf<String>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        val satelliteNetworkPreferredSet =
            ArgumentCaptor.forClass(Set::class.java) as ArgumentCaptor<Set<Int>>
        mRoleHolderChangedListener?.onRoleHoldersChanged(RoleManager.ROLE_SMS, UserHandle.ALL)
        verify(mCallback, never()).accept(satelliteNetworkPreferredSet.capture())

        // check DEFAULT_MESSAGING_APP1 is available as satellite network preferred uid
        doReturn(listOf(DEFAULT_MESSAGING_APP1))
            .`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener?.onRoleHoldersChanged(RoleManager.ROLE_SMS, UserHandle.ALL)
        verify(mCallback).accept(satelliteNetworkPreferredSet.capture())
        var satelliteNetworkPreferredUids = satelliteNetworkPreferredSet.value
        assertEquals(1, satelliteNetworkPreferredUids.size)
        assertTrue(satelliteNetworkPreferredUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertFalse(satelliteNetworkPreferredUids.contains(DEFAULT_MESSAGING_APP2_UID))

        // check DEFAULT_MESSAGING_APP1 and DEFAULT_MESSAGING_APP2 is available
        // as satellite network preferred uid
        val dmas: MutableList<String> = ArrayList()
        dmas.add(DEFAULT_MESSAGING_APP1)
        dmas.add(DEFAULT_MESSAGING_APP2)
        doReturn(dmas).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener?.onRoleHoldersChanged(RoleManager.ROLE_SMS, UserHandle.ALL)
        verify(mCallback, times(2))
            .accept(satelliteNetworkPreferredSet.capture())
        satelliteNetworkPreferredUids = satelliteNetworkPreferredSet.value
        assertEquals(2, satelliteNetworkPreferredUids.size)
        assertTrue(satelliteNetworkPreferredUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertTrue(satelliteNetworkPreferredUids.contains(DEFAULT_MESSAGING_APP2_UID))

        // check no uid is available as satellite network preferred uid
        doReturn(listOf<String>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener?.onRoleHoldersChanged(RoleManager.ROLE_SMS, UserHandle.ALL)
        verify(mCallback, times(3))
            .accept(satelliteNetworkPreferredSet.capture())
        satelliteNetworkPreferredUids = satelliteNetworkPreferredSet.value
        assertEquals(0, satelliteNetworkPreferredUids.size)
        assertFalse(satelliteNetworkPreferredUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertFalse(satelliteNetworkPreferredUids.contains(DEFAULT_MESSAGING_APP2_UID))

        // No Change received at OnRoleSmsChanged, check callback not triggered
        doReturn(listOf<String>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener?.onRoleHoldersChanged(RoleManager.ROLE_SMS, UserHandle.ALL)
        verify(mCallback, times(3))
            .accept(satelliteNetworkPreferredSet.capture())
    }

    @Test
    fun test_onRoleHoldersChanged_NoSatelliteCommunicationPermission() {
        doReturn(listOf<Any>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        val satelliteNetworkPreferredSet =
            ArgumentCaptor.forClass(Set::class.java) as ArgumentCaptor<Set<Int>>
        mRoleHolderChangedListener?.onRoleHoldersChanged(RoleManager.ROLE_SMS, UserHandle.ALL)
        verify(mCallback, never()).accept(satelliteNetworkPreferredSet.capture())

        // check DEFAULT_MESSAGING_APP1 is not available as satellite network preferred uid
        // since satellite communication permission not available.
        doReturn(PackageManager.PERMISSION_DENIED)
            .`when`(mPackageManager)
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, DEFAULT_MESSAGING_APP1)
        doReturn(listOf(DEFAULT_MESSAGING_APP1))
            .`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener?.onRoleHoldersChanged(RoleManager.ROLE_SMS, UserHandle.ALL)
        verify(mCallback, never()).accept(satelliteNetworkPreferredSet.capture())
    }

    @Test
    fun test_onRoleHoldersChanged_RoleSms_NotAvailable() {
        doReturn(listOf(DEFAULT_MESSAGING_APP1))
            .`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        val satelliteNetworkPreferredSet =
            ArgumentCaptor.forClass(Set::class.java) as ArgumentCaptor<Set<Int>>
        mRoleHolderChangedListener?.onRoleHoldersChanged(RoleManager.ROLE_BROWSER, UserHandle.ALL)
        verify(mCallback, never()).accept(satelliteNetworkPreferredSet.capture())
    }
}
