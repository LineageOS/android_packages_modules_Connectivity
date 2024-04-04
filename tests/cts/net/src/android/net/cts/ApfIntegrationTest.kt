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
import com.android.compatibility.common.util.PropertyUtil.getVsrApiLevel
import com.android.compatibility.common.util.SystemUtil.runShellCommand
import com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow
import com.android.internal.util.HexDump
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.NetworkStackModuleTest
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.SkipPresubmit
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.TruthJUnit.assume
import kotlin.random.Random
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

private const val TIMEOUT_MS = 2000L
private const val APF_NEW_RA_FILTER_VERSION = "apf_new_ra_filter_version"

@AppModeFull(reason = "CHANGE_NETWORK_STATE permission can't be granted to instant apps")
@RunWith(DevSdkIgnoreRunner::class)
@NetworkStackModuleTest
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
    private lateinit var caps: ApfCapabilities

    fun getApfCapabilities(): ApfCapabilities {
        val caps = runShellCommand("cmd network_stack apf $ifname capabilities").trim()
        if (caps.isEmpty()) {
            return ApfCapabilities(0, 0, 0)
        }
        val (version, maxLen, packetFormat) = caps.split(",").map { it.toInt() }
        return ApfCapabilities(version, maxLen, packetFormat)
    }

    @Before
    fun setUp() {
        assume().that(pm.hasSystemFeature(FEATURE_WIFI)).isTrue()
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
        // It's possible the device does not support APF, in which case this command will not be
        // successful. Ignore the error as testApfCapabilities() already asserts APF support on the
        // respective VSR releases and all other tests are based on the capabilities indicated.
        runShellCommand("cmd network_stack apf $ifname pause")
        caps = getApfCapabilities()
    }

    @After
    fun tearDown() {
        if (::ifname.isInitialized) {
            runShellCommand("cmd network_stack apf $ifname resume")
        }
        if (::networkCallback.isInitialized) {
            cm.unregisterNetworkCallback(networkCallback)
        }
    }

    @Test
    fun testApfCapabilities() {
        // APF became mandatory in Android 14 VSR.
        assume().that(getVsrApiLevel()).isAtLeast(34)

        // ApfFilter does not support anything but ARPHRD_ETHER.
        assertThat(caps.apfPacketFormat).isEqualTo(OsConstants.ARPHRD_ETHER)

        // DEVICEs launching with Android 14 with CHIPSETs that set ro.board.first_api_level to 34:
        // - [GMS-VSR-5.3.12-003] MUST return 4 or higher as the APF version number from calls to
        //   the getApfPacketFilterCapabilities HAL method.
        // - [GMS-VSR-5.3.12-004] MUST indicate at least 1024 bytes of usable memory from calls to
        //   the getApfPacketFilterCapabilities HAL method.
        // TODO: check whether above text should be changed "34 or higher"
        // This should assert apfVersionSupported >= 4 as per the VSR requirements, but there are
        // currently no tests for APFv6 and there cannot be a valid implementation as the
        // interpreter has yet to be finalized.
        assertThat(caps.apfVersionSupported).isEqualTo(4)
        assertThat(caps.maximumApfProgramSize).isAtLeast(1024)

        // DEVICEs launching with Android 15 (AOSP experimental) or higher with CHIPSETs that set
        // ro.board.first_api_level or ro.board.api_level to 202404 or higher:
        // - [GMS-VSR-5.3.12-009] MUST indicate at least 2000 bytes of usable memory from calls to
        //   the getApfPacketFilterCapabilities HAL method.
        if (getVsrApiLevel() >= 202404) {
            assertThat(caps.maximumApfProgramSize).isAtLeast(2000)
        }
    }

    // APF is backwards compatible, i.e. a v6 interpreter supports both v2 and v4 functionality.
    fun assumeApfVersionSupportAtLeast(version: Int) {
        assume().that(caps.apfVersionSupported).isAtLeast(version)
    }

    fun installProgram(bytes: ByteArray) {
        val prog = HexDump.toHexString(bytes, 0 /* offset */, bytes.size, false /* upperCase */)
        val result = runShellCommandOrThrow("cmd network_stack apf $ifname install $prog").trim()
        // runShellCommandOrThrow only throws on S+.
        assertThat(result).isEqualTo("success")
    }

    fun readProgram(): ByteArray {
        val progHexString = runShellCommandOrThrow("cmd network_stack apf $ifname read").trim()
        // runShellCommandOrThrow only throws on S+.
        assertThat(progHexString).isNotEmpty()
        return HexDump.hexStringToByteArray(progHexString)
    }

    @SkipPresubmit(reason = "This test takes longer than 1 minute, do not run it on presubmit.")
    // APF integration is mostly broken before V, only run the full read / write test on V+.
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    fun testReadWriteProgram() {
        assumeApfVersionSupportAtLeast(4)

        // Only test down to 2 bytes. The first byte always stays PASS.
        val program = ByteArray(caps.maximumApfProgramSize)
        for (i in caps.maximumApfProgramSize downTo 2) {
            // Randomize bytes in range [1, i). And install first [0, i) bytes of program.
            // Note that only the very first instruction (PASS) is valid APF bytecode.
            Random.nextBytes(program, 1 /* fromIndex */, i /* toIndex */)
            installProgram(program.sliceArray(0..<i))

            // Compare entire memory region.
            val readResult = readProgram()
            assertWithMessage("read/write $i byte prog failed").that(readResult).isEqualTo(program)
        }
    }
}
