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

package android.tethering.cts

import android.Manifest.permission.LOG_COMPAT_CHANGE
import android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG
import android.Manifest.permission.TETHER_PRIVILEGED
import android.app.compat.CompatChanges
import android.content.pm.PackageManager
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.TetheringManager
import android.net.connectivity.ConnectivityCompatChanges
import android.os.Build
import android.provider.DeviceConfig
import android.text.TextUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.AutoReleaseNetworkCallbackRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.DeviceConfigRule
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Available
import com.android.testutils.TestableNetworkCallback.Event.CapabilitiesChanged
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.runAsShell
import com.google.snippet.connectivity.Wifip2pMultiDevicesSnippet
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@RunWith(DevSdkIgnoreRunner::class)
class TetheringWifiP2pTest {
    // For manipulating feature flag before and after testing.
    @get:Rule
    val deviceConfigRule = DeviceConfigRule()

    @get:Rule
    val networkCallbackRule = AutoReleaseNetworkCallbackRule()

    companion object {
        // Shamelessly copied from TetheringConfiguration.
        private const val TETHERING_LOCAL_NETWORK_AGENT = "tethering_local_network_agent"
        private const val WIFIP2PGO_LOCAL_NETWORK_AGENT = "wifip2pgo_local_network_agent"
    }

    private val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
    private val pm = context.packageManager!!
    private val tm = context.getSystemService(TetheringManager::class.java)

    private fun assumeMatchNonThreadLocalNetworksEnabled() {
        assumeTrue(runAsShell<Boolean>(READ_COMPAT_CHANGE_CONFIG, LOG_COMPAT_CHANGE) {
            CompatChanges.isChangeEnabled(
                    ConnectivityCompatChanges.ENABLE_MATCH_NON_THREAD_LOCAL_NETWORKS
            )
        })
    }

    private fun getRandomString(): String {
        return "%09d".format(Random().nextInt(1_000_000_000))
    }

    @Test
    fun testWifiP2pGoNetworkAgent_networkCallbacks() {
        // Check preconditions.
        assumeMatchNonThreadLocalNetworksEnabled()
        assumeTrue(pm.hasSystemFeature(PackageManager.FEATURE_WIFI))
        assumeTrue(pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))
        // Wifi P2p Group Owner mode still need tethering support in order to get
        // onLocalOnlyInterfacesChanged callbacks.
        assumeTrue(runAsShell(TETHER_PRIVILEGED) { tm.isTetheringSupported() })
        deviceConfigRule.setConfig(
                DeviceConfig.NAMESPACE_TETHERING,
                TETHERING_LOCAL_NETWORK_AGENT,
                "1"
        )
        deviceConfigRule.setConfig(
                DeviceConfig.NAMESPACE_TETHERING,
                WIFIP2PGO_LOCAL_NETWORK_AGENT,
                "1"
        )

        val cb = TestableNetworkCallback()
        val request = NetworkRequest.Builder().addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .addTransportType(TRANSPORT_WIFI).build()
        networkCallbackRule.registerNetworkCallback(request, cb)

        val wifiP2pSnippet = Wifip2pMultiDevicesSnippet()
        try {
            wifiP2pSnippet.startWifiP2p()
            wifiP2pSnippet.createGroup("DIRECT-" + getRandomString(), getRandomString())

            val availableEvent = cb.expect<Available>()
            // Verify Capabilities.
            val capEvent = cb.eventuallyExpect<CapabilitiesChanged>()
            assertEquals(availableEvent.network, capEvent.network)
            assertTrue(capEvent.caps.hasCapability(NET_CAPABILITY_LOCAL_NETWORK))
            assertTrue(capEvent.caps.hasTransport(TRANSPORT_WIFI))

            // Verify LinkProperties.
            val lpEvent = cb.eventuallyExpect<LinkPropertiesChanged>()
            assertEquals(capEvent.network, lpEvent.network)
            assertFalse(TextUtils.isEmpty(lpEvent.lp.interfaceName))
            // At least 1 Ipv4 address and route.
            assertTrue(lpEvent.lp.hasIPv4Address())
            assertTrue(lpEvent.lp.routes.size >= 1)
        } finally {
            wifiP2pSnippet.stopWifiP2p()
        }
    }
}
