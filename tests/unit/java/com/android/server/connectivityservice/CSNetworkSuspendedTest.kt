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

package com.android.server

import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkRequest
import android.os.Build
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Lost
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

private const val NETWORK_SUSPENDED_TIMEOUT_SHORT = 150

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S)
class CSNetworkSuspendedTest : CSTest() {
    private val cb = TestableNetworkCallback(timeoutMs = 30 * 1000)
    private val cellNc = defaultNc().addTransportType(TRANSPORT_CELLULAR).addCapability(
        NET_CAPABILITY_INTERNET
    )
    private val cellNcSuspended = defaultNc().removeCapability(
        NET_CAPABILITY_NOT_SUSPENDED
    ).addCapability(
        NET_CAPABILITY_INTERNET
    ).addTransportType(TRANSPORT_CELLULAR)

    @ConnectivityModuleTest
    @Test
    fun testNetworkSuspendedTimeoutDisconnected() {
        // Set a short timeout to make the test faster.
        deps.networkSuspendedTimeoutForTestMs = NETWORK_SUSPENDED_TIMEOUT_SHORT
        val agent = Agent(cellNc)
        agent.connect()
        val networkInfo = cm.activeNetworkInfo
        assertNotNull(networkInfo)
        cm.registerNetworkCallback(
            NetworkRequest.Builder().setCapabilities(
                cellNc
            ).removeCapability(NET_CAPABILITY_NOT_SUSPENDED).build(),
            cb
        )
        // Update the network to suspended.
        agent.sendNetworkCapabilities(cellNcSuspended)

        // Verify that the suspended network eventually disconnects.
        cb.assertNoCallback(
            NETWORK_SUSPENDED_TIMEOUT_SHORT.toLong()
        ) { it is Lost && agent.network == it.network }
        cb.eventuallyExpect<Lost> { it.network == agent.network }
        cm.unregisterNetworkCallback(cb)
    }

    @ConnectivityModuleTest
    @Test
    fun testNetworkExitsSuspendedRemainsConnected() {
        // Set a slightly longer(0.5s) timeout to test (SUSPENDED -> NOT_SUSPENDED)
        deps.networkSuspendedTimeoutForTestMs = 500
        val agent = Agent(cellNc)
        agent.connect()
        val networkInfo = cm.activeNetworkInfo
        assertNotNull(networkInfo)
        cm.registerNetworkCallback(
            NetworkRequest.Builder().setCapabilities(
                cellNc
            ).removeCapability(NET_CAPABILITY_NOT_SUSPENDED).build(),
            cb
        )
        // Update the network to suspended.
        agent.sendNetworkCapabilities(cellNcSuspended)
        // Update the network back to not suspended.
        agent.sendNetworkCapabilities(cellNc)

        // Verify that the network is not disconnected after the suspended state timeout,
        // since the network returned to NOT_SUSPENDED state before the timeout.
        cb.assertNoCallback(750) { it is Lost && agent.network == it.network }
        cm.unregisterNetworkCallback(cb)
    }
}
