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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.ArraySet
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import java.util.concurrent.Executor
import java.util.function.Consumer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

private const val PRIMARY_USER = 0
private const val SECONDARY_USER = 10
private val PRIMARY_USER_HANDLE = UserHandle.of(PRIMARY_USER)
private val SECONDARY_USER_HANDLE = UserHandle.of(SECONDARY_USER)

// sms app names
private const val SMS_APP1 = "sms_app_1"
private const val SMS_APP2 = "sms_app_2"

// sms app ids
private const val SMS_APP_ID1 = 100
private const val SMS_APP_ID2 = 101

// UID for app1 and app2 on primary user
// These app could become default sms app for user1
private val PRIMARY_USER_SMS_APP_UID1 = UserHandle.getUid(PRIMARY_USER, SMS_APP_ID1)
private val PRIMARY_USER_SMS_APP_UID2 = UserHandle.getUid(PRIMARY_USER, SMS_APP_ID2)

// UID for app1 and app2 on secondary user
// These app could become default sms app for user2
private val SECONDARY_USER_SMS_APP_UID1 = UserHandle.getUid(SECONDARY_USER, SMS_APP_ID1)
private val SECONDARY_USER_SMS_APP_UID2 = UserHandle.getUid(SECONDARY_USER, SMS_APP_ID2)

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class SatelliteAccessControllerTest {
    private val context = mock(Context::class.java)
    private val primaryUserContext = mock(Context::class.java)
    private val secondaryUserContext = mock(Context::class.java)
    private val mPackageManagerPrimaryUser = mock(PackageManager::class.java)
    private val mPackageManagerSecondaryUser = mock(PackageManager::class.java)
    private val mDeps = mock(SatelliteAccessController.Dependencies::class.java)
    private val mCallback = mock(Consumer::class.java) as Consumer<Set<Int>>
    private val userManager = mock(UserManager::class.java)
    private val mHandler = Handler(Looper.getMainLooper())
    private var mSatelliteAccessController =
        SatelliteAccessController(context, mDeps, mCallback, mHandler)
    private lateinit var mRoleHolderChangedListener: OnRoleHoldersChangedListener
    private lateinit var mUserRemovedReceiver: BroadcastReceiver

    private fun <T> mockService(name: String, clazz: Class<T>, service: T) {
        doReturn(name).`when`(context).getSystemServiceName(clazz)
        doReturn(service).`when`(context).getSystemService(name)
        if (context.getSystemService(clazz) == null) {
            // Test is using mockito-extended
            doReturn(service).`when`(context).getSystemService(clazz)
        }
    }

    @Before
    @Throws(PackageManager.NameNotFoundException::class)
    fun setup() {
        doReturn(emptyList<UserHandle>()).`when`(userManager).getUserHandles(true)
        mockService(Context.USER_SERVICE, UserManager::class.java, userManager)

        doReturn(primaryUserContext).`when`(context).createContextAsUser(PRIMARY_USER_HANDLE, 0)
        doReturn(mPackageManagerPrimaryUser).`when`(primaryUserContext).packageManager

        doReturn(secondaryUserContext).`when`(context).createContextAsUser(SECONDARY_USER_HANDLE, 0)
        doReturn(mPackageManagerSecondaryUser).`when`(secondaryUserContext).packageManager

        for (app in listOf(SMS_APP1, SMS_APP2)) {
            doReturn(PackageManager.PERMISSION_GRANTED)
                .`when`(mPackageManagerPrimaryUser)
                .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, app)
            doReturn(PackageManager.PERMISSION_GRANTED)
                .`when`(mPackageManagerSecondaryUser)
                .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, app)
        }

        // Initialise message application primary user package1
        val applicationInfo1 = ApplicationInfo()
        applicationInfo1.uid = PRIMARY_USER_SMS_APP_UID1
        doReturn(applicationInfo1)
            .`when`(mPackageManagerPrimaryUser)
            .getApplicationInfo(eq(SMS_APP1), anyInt())

        // Initialise message application primary user package2
        val applicationInfo2 = ApplicationInfo()
        applicationInfo2.uid = PRIMARY_USER_SMS_APP_UID2
        doReturn(applicationInfo2)
            .`when`(mPackageManagerPrimaryUser)
            .getApplicationInfo(eq(SMS_APP2), anyInt())

        // Initialise message application secondary user package1
        val applicationInfo3 = ApplicationInfo()
        applicationInfo3.uid = SECONDARY_USER_SMS_APP_UID1
        doReturn(applicationInfo3)
            .`when`(mPackageManagerSecondaryUser)
            .getApplicationInfo(eq(SMS_APP1), anyInt())

        // Initialise message application secondary user package2
        val applicationInfo4 = ApplicationInfo()
        applicationInfo4.uid = SECONDARY_USER_SMS_APP_UID2
        doReturn(applicationInfo4)
            .`when`(mPackageManagerSecondaryUser)
            .getApplicationInfo(eq(SMS_APP2), anyInt())
    }

    @Test
    fun test_onRoleHoldersChanged_SatelliteFallbackUid_Changed_SingleUser() {
        startSatelliteAccessController()
        doReturn(listOf<String>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback, never()).accept(any())

        // check DEFAULT_MESSAGING_APP1 is available as satellite network fallback uid
        doReturn(listOf(SMS_APP1))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID1))

        // check SMS_APP2 is available as satellite network Fallback uid
        doReturn(listOf(SMS_APP2)).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID2))

        // check no uid is available as satellite network fallback uid
        doReturn(listOf<String>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(ArraySet())
    }

    @Test
    fun test_onRoleHoldersChanged_NoSatelliteCommunicationPermission() {
        startSatelliteAccessController()
        doReturn(listOf<Any>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback, never()).accept(any())

        // check DEFAULT_MESSAGING_APP1 is not available as satellite network fallback uid
        // since satellite communication permission not available.
        doReturn(PackageManager.PERMISSION_DENIED)
            .`when`(mPackageManagerPrimaryUser)
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, SMS_APP1)
        doReturn(listOf(SMS_APP1))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback, never()).accept(any())
    }

    @Test
    fun test_onRoleHoldersChanged_RoleSms_NotAvailable() {
        startSatelliteAccessController()
        doReturn(listOf(SMS_APP1))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        mRoleHolderChangedListener.onRoleHoldersChanged(
            RoleManager.ROLE_BROWSER,
            PRIMARY_USER_HANDLE
        )
        verify(mCallback, never()).accept(any())
    }

    @Test
    fun test_onRoleHoldersChanged_SatelliteNetworkFallbackUid_Changed_multiUser() {
        startSatelliteAccessController()
        doReturn(listOf<String>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback, never()).accept(any())

        // check SMS_APP1 is available as satellite network fallback uid at primary user
        doReturn(listOf(SMS_APP1))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID1))

        // check SMS_APP2 is available as satellite network fallback uid at primary user
        doReturn(listOf(SMS_APP2)).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID2))

        // check SMS_APP1 is available as satellite network fallback uid at secondary user
        doReturn(listOf(SMS_APP1)).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            SECONDARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID2, SECONDARY_USER_SMS_APP_UID1))

        // check no uid is available as satellite network fallback uid at primary user
        doReturn(listOf<String>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        verify(mCallback).accept(setOf(SECONDARY_USER_SMS_APP_UID1))

        // check SMS_APP2 is available as satellite network fallback uid at secondary user
        doReturn(listOf(SMS_APP2))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        verify(mCallback).accept(setOf(SECONDARY_USER_SMS_APP_UID2))

        // check no uid is available as satellite network fallback uid at secondary user
        doReturn(listOf<String>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            SECONDARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        verify(mCallback).accept(ArraySet())
    }

    @Test
    fun test_SatelliteFallbackUidCallback_OnUserRemoval() {
        startSatelliteAccessController()
        // check SMS_APP2 is available as satellite network fallback uid at primary user
        doReturn(listOf(SMS_APP2)).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID2))

        // check SMS_APP1 is available as satellite network fallback uid at secondary user
        doReturn(listOf(SMS_APP1)).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            SECONDARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID2, SECONDARY_USER_SMS_APP_UID1))

        val userRemovalIntent = Intent(Intent.ACTION_USER_REMOVED)
        userRemovalIntent.putExtra(Intent.EXTRA_USER, SECONDARY_USER_HANDLE)
        mUserRemovedReceiver.onReceive(context, userRemovalIntent)
        verify(mCallback, times(2)).accept(setOf(PRIMARY_USER_SMS_APP_UID2))
    }

    @Test
    fun testOnStartUpCallbackSatelliteFallbackUidWithExistingUsers() {
        doReturn(
            listOf(PRIMARY_USER_HANDLE)
        ).`when`(userManager).getUserHandles(true)
        doReturn(listOf(SMS_APP1))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        // At start up, SatelliteAccessController must call CS callback with existing users'
        // default messaging apps uids.
        startSatelliteAccessController()
        verify(mCallback, timeout(500)).accept(setOf(PRIMARY_USER_SMS_APP_UID1))
    }

    private fun startSatelliteAccessController() {
        mSatelliteAccessController.start()
        // Get registered listener using captor
        val listenerCaptor = ArgumentCaptor.forClass(OnRoleHoldersChangedListener::class.java)
        verify(mDeps).addOnRoleHoldersChangedListenerAsUser(
            any(Executor::class.java),
            listenerCaptor.capture(),
            any(UserHandle::class.java)
        )
        mRoleHolderChangedListener = listenerCaptor.value

        // Get registered receiver using captor
        val userRemovedReceiverCaptor = ArgumentCaptor.forClass(BroadcastReceiver::class.java)
        verify(context).registerReceiver(
            userRemovedReceiverCaptor.capture(),
            any(IntentFilter::class.java),
            isNull(),
            any(Handler::class.java)
        )
         mUserRemovedReceiver = userRemovedReceiverCaptor.value
    }
}
