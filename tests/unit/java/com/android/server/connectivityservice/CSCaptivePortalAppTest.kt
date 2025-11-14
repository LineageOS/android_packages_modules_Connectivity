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

package com.android.server

import android.Manifest.permission.NETWORK_STACK
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.CaptivePortal
import android.net.ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN
import android.net.ConnectivityManager.EXTRA_CAPTIVE_PORTAL
import android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.NetworkStack
import android.os.Build
import android.os.Bundle
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSCaptivePortalAppTest : CSTest() {
    private val WIFI_IFACE = "wifi0"
    private val TEST_REDIRECT_URL = "http://example.com/firstPath"
    private val TIMEOUT_MS = 2_000L

    @Test
    fun testCaptivePortalApp_Reevaluate_Nopermission() {
        val captivePortalCallback = TestableNetworkCallback()
        val captivePortalRequest = NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build()
        cm.registerNetworkCallback(captivePortalRequest, captivePortalCallback)
        val wifiAgent = Agent(WIFI_IFACE, TRANSPORT_WIFI, NET_CAPABILITY_INTERNET)
        wifiAgent.connectWithCaptivePortal(TEST_REDIRECT_URL)
        captivePortalCallback.expectAvailableCallbacksUnvalidated(wifiAgent)
        val signInIntent = startCaptivePortalApp(wifiAgent)
        // Remove the granted permissions
        context.setPermission(
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                PERMISSION_DENIED
        )
        context.setPermission(NETWORK_STACK, PERMISSION_DENIED)
        val captivePortal: CaptivePortal? = signInIntent.getParcelableExtra(EXTRA_CAPTIVE_PORTAL)
        captivePortal?.reevaluateNetwork()
        verify(wifiAgent.networkMonitor, never()).forceReevaluation(anyInt())
    }

    private fun startCaptivePortalApp(networkAgent: CSAgentWrapper): Intent {
        val network = networkAgent.network
        cm.startCaptivePortalApp(network)
        waitForIdle()
        verify(networkAgent.networkMonitor).launchCaptivePortalApp()

        val testBundle = Bundle()
        val testKey = "testkey"
        val testValue = "testvalue"
        testBundle.putString(testKey, testValue)
        context.setPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, PERMISSION_GRANTED)
        cm.startCaptivePortalApp(network, testBundle)
        val signInIntent: Intent = context.expectStartActivityIntent(TIMEOUT_MS)
        assertEquals(ACTION_CAPTIVE_PORTAL_SIGN_IN, signInIntent.getAction())
        assertEquals(testValue, signInIntent.getStringExtra(testKey))
        return signInIntent
    }
}
