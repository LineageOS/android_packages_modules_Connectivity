/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
import android.content.Intent.ACTION_PACKAGE_ADDED
import android.content.Intent.ACTION_PACKAGE_REMOVED
import android.content.Intent.ACTION_PACKAGE_REPLACED
import android.content.Intent.ACTION_USER_ADDED
import android.content.Intent.ACTION_USER_REMOVED
import android.content.Intent.EXTRA_CHANGED_PACKAGE_LIST
import android.content.Intent.EXTRA_UID
import android.content.Intent.EXTRA_USER
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.UserHandle
import android.os.UserManager
import com.android.compatibility.common.util.MoreMatchers.anyOrNull
import com.android.server.connectivity.BroadcastReceiveHelper.Delegate
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.waitForIdle
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

private const val TEST_PACKAGE_NAME = "com.example.app"
private const val TEST_UID = 1000
private const val TIMEOUT_MS = 2000

private inline fun <reified T> any() = org.mockito.Mockito.any(T::class.java)

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
class BroadcastReceiveHelperTest {
    private val mockContext = mock(Context::class.java)
    private val mockDelegate = mock(Delegate::class.java)
    private val mockUserManager = mock(UserManager::class.java)

    // Create an InOrder object to verify the sequence of delegate calls.
    private val delegateInOrderVerifier = org.mockito.Mockito.inOrder(mockDelegate)

    // lateinit is used here because thread and handler need to be initialized in the
    // @Before setUp method.
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var broadcastReceiveHelper: BroadcastReceiveHelper
    private lateinit var packageReceiver: BroadcastReceiver
    private lateinit var externalAppReceiver: BroadcastReceiver
    private lateinit var userReceiver: BroadcastReceiver

    @Before
    fun setUp() {
        handlerThread = HandlerThread("TestThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        broadcastReceiveHelper = BroadcastReceiveHelper(mockContext, handler, mockDelegate)

        // Capture intent receivers.
        doReturn(mockUserManager).`when`(mockContext).getSystemService(UserManager::class.java)
        doReturn(mockContext).`when`(mockContext).createContextAsUser(UserHandle.ALL, 0)
        broadcastReceiveHelper.registerReceivers()
        userReceiver = captureIntentReceiver(ACTION_USER_ADDED)
        packageReceiver = captureIntentReceiver(ACTION_PACKAGE_ADDED)
        externalAppReceiver = captureIntentReceiver(ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
    }

    private fun captureIntentReceiver(action: String): BroadcastReceiver {
        val receiverCaptor = ArgumentCaptor.forClass(BroadcastReceiver::class.java)
        verify(mockContext, times(1)).registerReceiver(
                receiverCaptor.capture(),
                argThat { it.hasAction(action) },
                anyOrNull(String::class.java),
                eq(handler)
        )
        // Return the first match.
        return receiverCaptor.value
    }

    @After
    fun tearDown() {
        broadcastReceiveHelper.unregisterReceivers()
        handlerThread.quitSafely()
        handlerThread.join()
    }

    @Test
    fun testPackageAdded() {
        val intent = Intent(ACTION_PACKAGE_ADDED).apply {
            data = Uri.fromParts("package", TEST_PACKAGE_NAME, null)
            putExtra(EXTRA_UID, TEST_UID)
        }
        processOnHandlerThread { packageReceiver.onReceive(mockContext, intent) }
        delegateInOrderVerifier.verify(mockDelegate).onPackageAdded(TEST_PACKAGE_NAME, TEST_UID)
        delegateInOrderVerifier.verifyNoMoreInteractions()
    }

    @Test
    fun testPackageRemoved() {
        val intent = Intent(ACTION_PACKAGE_REMOVED).apply {
            data = Uri.fromParts("package", TEST_PACKAGE_NAME, null)
            putExtra(EXTRA_UID, TEST_UID)
        }
        processOnHandlerThread { packageReceiver.onReceive(mockContext, intent) }
        delegateInOrderVerifier.verify(mockDelegate).onPackageRemoved(TEST_PACKAGE_NAME, TEST_UID)
        delegateInOrderVerifier.verifyNoMoreInteractions()
    }

    @Test
    fun testPackageReplaced() {
        val intent = Intent(ACTION_PACKAGE_REPLACED).apply {
            data = Uri.fromParts("package", TEST_PACKAGE_NAME, null)
            putExtra(EXTRA_UID, TEST_UID)
        }
        processOnHandlerThread { packageReceiver.onReceive(mockContext, intent) }
        delegateInOrderVerifier.verify(mockDelegate).onPackageReplaced(TEST_PACKAGE_NAME, TEST_UID)
        delegateInOrderVerifier.verifyNoMoreInteractions()
    }

    @Test
    fun testExternalAppsAvailable() {
        val packageList = arrayOf("com.example.app1", "com.example.app2")
        val intent = Intent(ACTION_EXTERNAL_APPLICATIONS_AVAILABLE).apply {
            putExtra(EXTRA_CHANGED_PACKAGE_LIST, packageList)
        }
        processOnHandlerThread { externalAppReceiver.onReceive(mockContext, intent) }
        delegateInOrderVerifier.verify(mockDelegate).onExternalApplicationsAvailable(packageList)
        delegateInOrderVerifier.verifyNoMoreInteractions()
    }

    @Test
    fun testUserAdded() {
        val userHandle = mock(UserHandle::class.java)
        val intent = Intent(ACTION_USER_ADDED).apply {
            putExtra(EXTRA_USER, userHandle)
        }
        processOnHandlerThread { userReceiver.onReceive(mockContext, intent) }
        delegateInOrderVerifier.verify(mockDelegate).onUserAdded(userHandle)
        delegateInOrderVerifier.verifyNoMoreInteractions()
    }

    @Test
    fun testUserRemoved() {
        val userHandle = mock(UserHandle::class.java)
        val intent = Intent(ACTION_USER_REMOVED).apply {
            putExtra(EXTRA_USER, userHandle)
        }
        processOnHandlerThread { userReceiver.onReceive(mockContext, intent) }
        delegateInOrderVerifier.verify(mockDelegate).onUserRemoved(userHandle)
        delegateInOrderVerifier.verifyNoMoreInteractions()
    }

    @Test
    fun testUnregisterReceivers() {
        broadcastReceiveHelper.unregisterReceivers()
        verify(mockContext).unregisterReceiver(userReceiver)
        verify(mockContext).unregisterReceiver(packageReceiver)
        verify(mockContext).unregisterReceiver(externalAppReceiver)
    }

    @Test
    fun testBroadcastOrder() {
        val numEvents = 50
        val random = java.util.Random()
        val eventList = mutableListOf<Intent>()

        // Function to append a test package name extra.
        fun Intent.withPackageNameForIndex(index: Int): Intent {
            data = Uri.fromParts("package", "com.example.app$index", null)
            putExtra(EXTRA_UID, index)
            return this
        }

        // Function to append a mock UserHandle extra.
        fun Intent.withUserHandleForIndex(index: Int): Intent {
            putExtra(EXTRA_USER, UserHandle.of(index))
            return this
        }

        // Generate a list of 50 random actions and their corresponding intents.
        repeat(numEvents) { index ->
            val intent = when (random.nextInt(6)) {
                0 -> Intent(ACTION_PACKAGE_ADDED).withPackageNameForIndex(index)
                1 -> Intent(ACTION_PACKAGE_REMOVED).withPackageNameForIndex(index)
                2 -> Intent(ACTION_PACKAGE_REPLACED).withPackageNameForIndex(index)
                3 -> Intent(ACTION_EXTERNAL_APPLICATIONS_AVAILABLE).apply {
                    val packageList = arrayOf("com.example.app$index")
                    putExtra(EXTRA_CHANGED_PACKAGE_LIST, packageList)
                }

                4 -> Intent(ACTION_USER_ADDED).withUserHandleForIndex(index)
                5 -> Intent(ACTION_USER_REMOVED).withUserHandleForIndex(index)

                else -> throw IllegalStateException("Unexpected random number")
            }
            eventList.add(intent)
        }

        // Post all intents to the handler thread at once.
        eventList.forEach {
            handler.post {
                when (it.action) {
                    ACTION_PACKAGE_ADDED, ACTION_PACKAGE_REMOVED, ACTION_PACKAGE_REPLACED ->
                        packageReceiver.onReceive(mockContext, it)

                    ACTION_EXTERNAL_APPLICATIONS_AVAILABLE ->
                        externalAppReceiver.onReceive(mockContext, it)

                    ACTION_USER_ADDED, ACTION_USER_REMOVED ->
                        userReceiver.onReceive(mockContext, it)
                }
            }
        }

        // Wait for all intents to be processed at once.
        handler.waitForIdle(TIMEOUT_MS)

        // Verify the order of delegate calls.
        eventList.forEach {
            when (it.action) {
                ACTION_PACKAGE_ADDED -> delegateInOrderVerifier.verify(mockDelegate)
                        .onPackageAdded(
                                it.data?.schemeSpecificPart!!,
                                it.getIntExtra(EXTRA_UID, -1)
                        )

                ACTION_PACKAGE_REMOVED -> delegateInOrderVerifier.verify(mockDelegate)
                        .onPackageRemoved(
                                it.data?.schemeSpecificPart!!,
                                it.getIntExtra(EXTRA_UID, -1)
                        )

                ACTION_PACKAGE_REPLACED -> delegateInOrderVerifier.verify(mockDelegate)
                        .onPackageReplaced(
                                it.data?.schemeSpecificPart!!,
                                it.getIntExtra(EXTRA_UID, -1)
                        )

                ACTION_EXTERNAL_APPLICATIONS_AVAILABLE ->
                    delegateInOrderVerifier.verify(mockDelegate)
                            .onExternalApplicationsAvailable(it.getStringArrayExtra(
                                    EXTRA_CHANGED_PACKAGE_LIST
                            )!!)

                ACTION_USER_ADDED -> delegateInOrderVerifier.verify(mockDelegate)
                        .onUserAdded(it.getParcelableExtra(EXTRA_USER)!!)

                ACTION_USER_REMOVED -> delegateInOrderVerifier.verify(mockDelegate)
                        .onUserRemoved(it.getParcelableExtra(EXTRA_USER)!!)
            }
        }

        // Ensure no other calls were made to the delegate.
        delegateInOrderVerifier.verifyNoMoreInteractions()
    }

    private fun processOnHandlerThread(function: Runnable) {
        handler.post { function.run() }
        // Wait twice since the onReceive defers actual handling logic to another message.
        // See BroadcastReceiveHelper#HandlerPostReceiver.
        handler.waitForIdle(HANDLER_TIMEOUT_MS)
        handler.waitForIdle(HANDLER_TIMEOUT_MS)
    }

    @Test
    fun testCallOnUserAddedForInitialUsers() {
        // Mock existing users.
        val existingUser1 = mock(UserHandle::class.java)
        val existingUser2 = mock(UserHandle::class.java)
        val existingUsers = listOf(existingUser1, existingUser2)
        doReturn(existingUsers).`when`(mockUserManager).getUserHandles(any())
        // Make sure there is no interactions.
        delegateInOrderVerifier.verifyNoMoreInteractions()

        // Verify that onUserAdded is called for each existing user.
        processOnHandlerThread { broadcastReceiveHelper.callOnUserAddedForExistingUsers() }
        delegateInOrderVerifier.verify(mockDelegate).onUserAdded(existingUser1)
        delegateInOrderVerifier.verify(mockDelegate).onUserAdded(existingUser2)
        delegateInOrderVerifier.verifyNoMoreInteractions()
    }
}
