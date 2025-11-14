/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.testutils.connectivitypreparer

import android.Manifest.permission.NETWORK_SETTINGS
import android.content.pm.PackageManager.FEATURE_TELEPHONY
import android.content.pm.PackageManager.FEATURE_WIFI
import android.net.InetAddresses.parseNumericAddress
import android.net.Network
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.HexDump
import com.android.net.module.util.NetworkStackConstants.IPV4_ADDR_ANY
import com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_ANY
import com.android.testutils.AutoReleaseNetworkCallbackRule
import com.android.testutils.ConnectUtil
import com.android.testutils.TestableNetworkCallback.Event.CapabilitiesChanged
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.runAsShell
import com.android.testutils.tryTest
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.Random
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val QUIC_SOCKET_TIMEOUT_MS = 5_000
private const val QUIC_RETRY_COUNT = 5

@RunWith(AndroidJUnit4::class)
class ConnectivityCheckTest {
    @get:Rule
    val networkCallbackRule = AutoReleaseNetworkCallbackRule()

    private val logTag = ConnectivityCheckTest::class.simpleName
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val pm by lazy { context.packageManager }
    private val connectUtil by lazy { ConnectUtil(context) }

    // Skip IPv6 checks on virtual devices which do not support it. Tests that require IPv6 will
    // still fail even if the preparer does not.
    private fun ipv6Unsupported(wifiSsid: String?) = ConnectUtil.VIRTUAL_SSIDS.contains(
        WifiInfo.sanitizeSsid(wifiSsid)
    )

    @Test
    fun testCheckWifiSetup() {
        if (!pm.hasSystemFeature(FEATURE_WIFI)) return
        connectUtil.ensureWifiValidated()

        val (wifiNetwork, wifiSsid) = runAsShell(NETWORK_SETTINGS) {
            val cb = networkCallbackRule.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(TRANSPORT_WIFI)
                    .addCapability(NET_CAPABILITY_INTERNET)
                    .build()
            )
            val capChanged = cb.eventuallyExpect<CapabilitiesChanged>(from = 0)
            val network = capChanged.network
            val ssid = capChanged.caps.ssid
            assertFalse(ssid.isNullOrEmpty(), "No SSID for wifi network $network")
            // Expect a global IPv6 address, and native or stacked IPv4
            val lpChange = cb.history.poll(
                pos = 0,
                timeoutMs = 30_000L
            ) {
                if (it !is LinkPropertiesChanged || it.network != network) {
                    false
                } else {
                    // Same check as used by DnsResolver for AI_ADDRCONFIG (have_ipv4)
                    val ipv4Reachable = it.lp.isReachable(parseNumericAddress("8.8.8.8"))
                    // Same check as used by DnsResolver for AI_ADDRCONFIG (have_ipv6)
                    val ipv6Reachable = it.lp.isReachable(parseNumericAddress("2000::"))
                    ipv4Reachable && (ipv6Unsupported(ssid) || ipv6Reachable)
                }
            }
            assertNotNull(
                lpChange,
                "Wifi network $network needs an IPv4 address and default route" +
                        if (ipv6Unsupported(ssid)) {
                            ""
                        } else {
                            " and a global IPv6 address and default route"
                        }
            )

            Pair(network, ssid)
        }

        // Checking QUIC is more important on Wi-Fi than cellular, as it finds firewall
        // configuration problems on Wi-Fi, but cellular is not actionable by the test lab.
        checkQuic(wifiNetwork, wifiSsid, ipv6 = false)
        if (!ipv6Unsupported(wifiSsid)) {
            checkQuic(wifiNetwork, wifiSsid, ipv6 = true)
        }
    }

    /**
     * Check that QUIC is working on the specified network.
     *
     * Some tests require QUIC (UDP), and some lab networks have been observed to not let it
     * through due to firewalling. Ensure that devices are setup on a network that has the proper
     * allowlists before trying to run the tests.
     */
    private fun checkQuic(network: Network, ssid: String, ipv6: Boolean) {
        // Same endpoint as used in MultinetworkApiTest in CTS
        val hostname = "connectivitycheck.android.com"
        val targetAddrs = network.getAllByName(hostname)
        val bindAddr = if (ipv6) IPV6_ADDR_ANY else IPV4_ADDR_ANY
        if (targetAddrs.isEmpty()) {
            Log.d(logTag, "No addresses found for $hostname")
            return
        }

        val socket = DatagramSocket(0, bindAddr)
        tryTest {
            socket.soTimeout = QUIC_SOCKET_TIMEOUT_MS
            network.bindSocket(socket)

            // For reference see Version-Independent Properties of QUIC:
            // https://datatracker.ietf.org/doc/html/rfc8999
            // This packet just contains a long header with an unsupported version number, to force
            // a version-negotiation packet in response.
            val connectionId = ByteArray(8).apply { Random().nextBytes(this) }
            val quicData = byteArrayOf(
                // long header
                0xc0.toByte(),
                // version number (should be an unknown version for the server)
                0xaa.toByte(), 0xda.toByte(), 0xca.toByte(), 0xca.toByte(),
                // destination connection ID length
                0x08,
            ) + connectionId + byteArrayOf(
                // source connection ID length
                0x00,
            ) + ByteArray(1185) // Ensure the packet is 1200 bytes long
            val targetAddr = targetAddrs.firstOrNull { it.javaClass == bindAddr.javaClass }
                ?: fail("No ${bindAddr.javaClass} found for $hostname " +
                        "(got ${targetAddrs.joinToString()})")
            repeat(QUIC_RETRY_COUNT) { i ->
                socket.send(DatagramPacket(quicData, quicData.size, targetAddr, 443))

                val receivedPacket = DatagramPacket(ByteArray(1500), 1500)
                try {
                    socket.receive(receivedPacket)
                } catch (e: IOException) {
                    Log.d(logTag, "No response from $hostname ($targetAddr) on QUIC try $i", e)
                    return@repeat
                }

                val receivedConnectionId = receivedPacket.data.copyOfRange(7, 7 + 8)
                if (connectionId.contentEquals(receivedConnectionId)) {
                    return@tryTest
                } else {
                    val headerBytes = receivedPacket.data.copyOfRange(
                        0, receivedPacket.length.coerceAtMost(15))
                    Log.d(logTag, "Received invalid connection ID on QUIC try $i: " +
                            HexDump.toHexString(headerBytes))
                }
            }
            fail("QUIC is not working on SSID $ssid connecting to $targetAddr " +
                    "with local source port ${socket.localPort}: check the firewall for UDP port " +
                    "443 access."
            )
        } cleanup {
            socket.close()
        }
    }

    @Test
    fun testCheckTelephonySetup() {
        if (!pm.hasSystemFeature(FEATURE_TELEPHONY)) return
        val tm = context.getSystemService(TelephonyManager::class.java)
                ?: fail("Could not get telephony service")

        val commonError = "Check the test bench. To run the tests anyway for quick & dirty local " +
                "testing, you can use atest X -- " +
                "--test-arg com.android.testutils.ConnectivityTestTargetPreparer" +
                ":ignore-mobile-data-check:true"
        // Do not use assertEquals: it outputs "expected X, was Y", which looks like a test failure
        if (tm.simState == TelephonyManager.SIM_STATE_ABSENT) {
            fail("The device has no SIM card inserted. $commonError")
        } else if (tm.simState != TelephonyManager.SIM_STATE_READY) {
            fail(
                "The device is not setup with a usable SIM card. Sim state was ${tm.simState}. " +
                    commonError
            )
        }
        assertTrue(
            tm.isDataConnectivityPossible,
            "The device has a SIM card, but it does not supports data connectivity. " +
            "Check the data plan, and verify that mobile data is working. " + commonError
        )
        connectUtil.ensureCellularValidated()
    }
}
