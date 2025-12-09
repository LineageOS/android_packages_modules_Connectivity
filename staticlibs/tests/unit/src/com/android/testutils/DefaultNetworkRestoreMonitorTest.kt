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

package com.android.testutils

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class DefaultNetworkRestoreMonitorTest {
    private val restoreDefaultNetworkDesc =
            Description.createSuiteDescription("RestoreDefaultNetwork")
    private val testDesc = Description.createTestDescription("testClass", "testMethod")
    private val wifiCap = NetworkCapabilities.Builder()
            .addTransportType(TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
    private val cellCap = NetworkCapabilities.Builder()
            .addTransportType(TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
    private val cm = mock(ConnectivityManager::class.java)
    private val pm = mock(PackageManager::class.java).also {
        doReturn(true).`when`(it).hasSystemFeature(anyString())
    }
    private val ctx = mock(Context::class.java).also {
        doReturn(cm).`when`(it).getSystemService(ConnectivityManager::class.java)
        doReturn(pm).`when`(it).getPackageManager()
    }
    private val notifier = mock(RunNotifier::class.java)
    private val defaultNetworkMonitor = DefaultNetworkRestoreMonitor(
        ctx,
        notifier,
        timeoutMs = 0
    )

    private fun getRunListener(): RunListener {
        val captor = ArgumentCaptor.forClass(RunListener::class.java)
        verify(notifier).addListener(captor.capture())
        return captor.value
    }

    private fun mockDefaultNetworkCapabilities(cap: NetworkCapabilities?) {
        if (cap == null) {
            doNothing().`when`(cm).registerDefaultNetworkCallback(any())
            return
        }
        doAnswer {
            val callback = it.getArgument(0) as NetworkCallback
            callback.onCapabilitiesChanged(Network(100), cap)
        }.`when`(cm).registerDefaultNetworkCallback(any())
    }

    @Test
    fun testDefaultNetworkRestoreMonitor_defaultNetworkRestored() {
        mockDefaultNetworkCapabilities(wifiCap)
        defaultNetworkMonitor.init(mock(ConnectUtil::class.java))

        val listener = getRunListener()
        listener.testFinished(testDesc)

        defaultNetworkMonitor.reportResultAndCleanUp(restoreDefaultNetworkDesc)
        val inOrder = inOrder(notifier)
        inOrder.verify(notifier).fireTestStarted(restoreDefaultNetworkDesc)
        inOrder.verify(notifier, never()).fireTestFailure(any())
        inOrder.verify(notifier).fireTestFinished(restoreDefaultNetworkDesc)
        inOrder.verify(notifier).removeListener(listener)
    }

    @Test
    fun testDefaultNetworkRestoreMonitor_testStartWithoutDefaultNetwork() {
        // There is no default network when the tests start
        mockDefaultNetworkCapabilities(null)
        defaultNetworkMonitor.init(mock(ConnectUtil::class.java))

        mockDefaultNetworkCapabilities(wifiCap)
        val listener = getRunListener()
        listener.testFinished(testDesc)

        val expectedDesc = Description.createSuiteDescription("ConnectivityTestTargetPreparer")
        defaultNetworkMonitor.reportResultAndCleanUp(restoreDefaultNetworkDesc)
        val inOrder = inOrder(notifier)
        inOrder.verify(notifier).fireTestStarted(expectedDesc)
        // fireTestFailure is called
        inOrder.verify(notifier).fireTestFailure(any())
        inOrder.verify(notifier).fireTestFinished(expectedDesc)
        inOrder.verify(notifier).removeListener(listener)
    }

    @Test
    fun testDefaultNetworkRestoreMonitor_testEndWithoutDefaultNetwork() {
        mockDefaultNetworkCapabilities(wifiCap)
        defaultNetworkMonitor.init(mock(ConnectUtil::class.java))

        // There is no default network after the test
        mockDefaultNetworkCapabilities(null)
        val listener = getRunListener()
        listener.testFinished(testDesc)

        defaultNetworkMonitor.reportResultAndCleanUp(restoreDefaultNetworkDesc)
        val inOrder = inOrder(notifier)
        inOrder.verify(notifier).fireTestStarted(testDesc)
        // fireTestFailure is called with method name
        inOrder.verify(
                notifier
        ).fireTestFailure(
                argThat{failure -> failure.exception.message?.contains("testMethod") ?: false}
        )
        inOrder.verify(notifier).fireTestFinished(testDesc)
        inOrder.verify(notifier).removeListener(listener)
    }

    @Test
    fun testDefaultNetworkRestoreMonitor_testChangeDefaultNetwork() {
        mockDefaultNetworkCapabilities(wifiCap)
        defaultNetworkMonitor.init(mock(ConnectUtil::class.java))

        // The default network transport types change after the test
        mockDefaultNetworkCapabilities(cellCap)
        val listener = getRunListener()
        listener.testFinished(testDesc)

        defaultNetworkMonitor.reportResultAndCleanUp(restoreDefaultNetworkDesc)
        val inOrder = inOrder(notifier)
        inOrder.verify(notifier).fireTestStarted(testDesc)
        // fireTestFailure is called with method name
        inOrder.verify(
                notifier
        ).fireTestFailure(
                argThat{failure -> failure.exception.message?.contains("testMethod") ?: false}
        )
        inOrder.verify(notifier).fireTestFinished(testDesc)
        inOrder.verify(notifier).removeListener(listener)
    }
}
