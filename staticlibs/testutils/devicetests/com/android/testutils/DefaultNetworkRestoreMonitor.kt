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

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.android.internal.annotations.VisibleForTesting
import com.android.net.module.util.BitUtils
import com.android.testutils.TestableNetworkCallback.Event.CapabilitiesChanged
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier

@VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
class DefaultNetworkRestoreMonitor(
        ctx: Context,
        private val notifier: RunNotifier,
        private val timeoutMs: Long = 30_000
) {
    var firstFailure: Exception? = null
    var initialTransports = 0L
    val cm = ctx.getSystemService(ConnectivityManager::class.java)!!
    val pm = ctx.packageManager
    val listener = object : RunListener() {
        @SuppressLint("WrongConstant") // transportNamesOf wants @Transport annotation
        override fun testFinished(desc: Description) {
            fun ConnectivityManager.activeNetworkCaps() =
                activeNetwork?.let { getNetworkCapabilities(it) }
            // Only the first method that does not restore the default network should be blamed.
            if (firstFailure != null) {
                return
            }
            val cb = TestableNetworkCallback()
            cm.registerDefaultNetworkCallback(cb)
            try {
                cb.eventuallyExpect<CapabilitiesChanged>(timeoutMs = timeoutMs) {
                    BitUtils.packBits(it.caps.transportTypes) == initialTransports &&
                            it.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            } catch (e: AssertionError) {
                val expectedTransports = BitUtils.unpackBits(initialTransports)
                firstFailure = IllegalStateException(desc.methodName + " does not restore the " +
                        "default network. Default network has caps ${cm.activeNetworkCaps()} ; " +
                        "expected a network with transports = " +
                        NetworkCapabilities.transportNamesOf(expectedTransports))
            } finally {
                cm.unregisterNetworkCallback(cb)
            }
        }
    }

    fun init(connectUtil: ConnectUtil) {
        // Ensure Wi-Fi and cellular connection before running test to avoid starting test
        // with unexpected default network.
        // ConnectivityTestTargetPreparer does the same thing, but it's possible that previous tests
        // don't enable DefaultNetworkRestoreMonitor and the default network is not restored.
        // This can be removed if all tests enable DefaultNetworkRestoreMonitor
        if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            connectUtil.ensureWifiValidated()
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            connectUtil.ensureCellularValidated()
        }

        val capFuture = CompletableFuture<NetworkCapabilities>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, cap: NetworkCapabilities) {
                capFuture.complete(cap)
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        try {
            val cap = capFuture.get(10_000, TimeUnit.MILLISECONDS)
            initialTransports = BitUtils.packBits(cap.transportTypes)
        } catch (e: Exception) {
            firstFailure = IllegalStateException(
                    "Failed to get default network status before starting tests", e
            )
        } finally {
            cm.unregisterNetworkCallback(cb)
        }
        notifier.addListener(listener)
    }

    fun reportResultAndCleanUp(desc: Description) {
        notifier.fireTestStarted(desc)
        if (firstFailure != null) {
            notifier.fireTestFailure(Failure(desc, firstFailure))
        }
        notifier.fireTestFinished(desc)
        notifier.removeListener(listener)
    }
}
