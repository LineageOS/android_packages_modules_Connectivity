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
package android.net.cts

import android.Manifest.permission.WRITE_DEVICE_CONFIG
import android.content.pm.PackageManager.FEATURE_WIFI
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.apf.ApfCapabilities
import android.os.Build
import android.platform.test.annotations.AppModeFull
import android.provider.DeviceConfig
import android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.PropertyUtil.isVendorApiLevelNewerThan
import com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.NetworkStackModuleTest
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

private const val TIMEOUT_MS = 2000L
private const val APF_NEW_RA_FILTER_VERSION = "apf_new_ra_filter_version"

@AppModeFull(reason = "CHANGE_NETWORK_STATE permission can't be granted to instant apps")
@RunWith(DevSdkIgnoreRunner::class)
@NetworkStackModuleTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class ApfIntegrationTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun setupOnce() {
            // TODO: check that there is no active wifi network. Otherwise, ApfFilter has already been
            // created.
            // APF adb cmds are only implemented in ApfFilter.java. Enable experiment to prevent
            // LegacyApfFilter.java from being used.
            runAsShell(WRITE_DEVICE_CONFIG) {
                DeviceConfig.setProperty(
                        NAMESPACE_CONNECTIVITY,
                        APF_NEW_RA_FILTER_VERSION,
                        "1",  // value => force enabled
                        false // makeDefault
                )
            }
        }
    }

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val cm by lazy { context.getSystemService(ConnectivityManager::class.java)!! }
    private val pm by lazy { context.packageManager }
    private lateinit var ifname: String
    private lateinit var networkCallback: TestableNetworkCallback

    @Before
    fun setUp() {
        assumeTrue(pm.hasSystemFeature(FEATURE_WIFI))
        assumeTrue(isVendorApiLevelNewerThan(Build.VERSION_CODES.TIRAMISU))
        networkCallback = TestableNetworkCallback()
        cm.requestNetwork(
                NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                networkCallback
        )
        networkCallback.eventuallyExpect<LinkPropertiesChanged>(TIMEOUT_MS) {
            ifname = assertNotNull(it.lp.interfaceName)
            true
        }
        runShellCommandOrThrow("cmd network_stack apf $ifname pause")
    }

    @After
    fun tearDown() {
        if (::networkCallback.isInitialized) {
            cm.unregisterNetworkCallback(networkCallback)
        }
        runShellCommandOrThrow("cmd network_stack apf $ifname resume")
    }

    fun getApfCapabilities(): ApfCapabilities {
        val caps = runShellCommandOrThrow("cmd network_stack apf $ifname capabilities").trim()
        val (version, maxLen, packetFormat) = caps.split(",").map { it.toInt() }
        return ApfCapabilities(version, maxLen, packetFormat)
    }

    @Test
    fun testGetApfCapabilities() {
        val caps = getApfCapabilities()
        assertThat(caps.apfVersionSupported).isEqualTo(4)
        assertThat(caps.maximumApfProgramSize).isAtLeast(1024)
        if (isVendorApiLevelNewerThan(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            assertThat(caps.maximumApfProgramSize).isAtLeast(2000)
        }
        assertThat(caps.apfPacketFormat).isEqualTo(OsConstants.ARPHRD_ETHER)
    }
}
