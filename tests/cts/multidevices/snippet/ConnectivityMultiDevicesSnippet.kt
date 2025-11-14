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

package com.google.snippet.connectivity

import android.Manifest.permission.NETWORK_SETTINGS
import android.Manifest.permission.OVERRIDE_WIFI_CONFIG
import android.content.pm.PackageManager.FEATURE_AUTOMOTIVE
import android.content.pm.PackageManager.FEATURE_TELEPHONY
import android.content.pm.PackageManager.FEATURE_WIFI
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.cts.util.CtsNetUtils
import android.net.cts.util.CtsTetheringUtils
import android.net.wifi.SoftApConfiguration
import android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.Build.VERSION.CODENAME
import android.os.Build.VERSION.SDK_INT
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.PropertyUtil
import com.android.modules.utils.build.SdkLevel
import com.android.testutils.AutoReleaseNetworkCallbackRule
import com.android.testutils.ConnectUtil
import com.android.testutils.NetworkCallbackHelper
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.CapabilitiesChanged
import com.android.testutils.runAsShell
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import org.junit.Rule

class ConnectivityMultiDevicesSnippet : Snippet {
    @get:Rule
    val networkCallbackRule = AutoReleaseNetworkCallbackRule()
    private val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
    private val wifiManager = context.getSystemService(WifiManager::class.java)!!
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val pm = context.packageManager
    private val ctsNetUtils = CtsNetUtils(context)
    private val cbHelper = NetworkCallbackHelper()
    private val ctsTetheringUtils = CtsTetheringUtils(context)
    private var oldSoftApConfig: SoftApConfiguration? = null
    private var multicastSocket: MulticastSocket? = null

    override fun shutdown() {
        cbHelper.unregisterAll()
    }

    private fun isAtLeastPreReleaseCodename(codeName: String): Boolean {
        // Special case "REL", which means the build is not a pre-release build.
        if ("REL".equals(CODENAME)) {
            return false
        }

        // Otherwise lexically compare them. Return true if the build codename is equal to or
        // greater than the requested codename.
        return CODENAME.compareTo(codeName) >= 0
    }

    @Rpc(description = "Check whether the device has wifi feature.")
    fun hasWifiFeature() = pm.hasSystemFeature(FEATURE_WIFI)

    @Rpc(description = "Check whether the device has telephony feature.")
    fun hasTelephonyFeature() = pm.hasSystemFeature(FEATURE_TELEPHONY)

    @Rpc(description = "Check whether the device has automotive feature.")
    fun hasAutomotiveFeature() = pm.hasSystemFeature(FEATURE_AUTOMOTIVE)

    @Rpc(description = "Check whether the device supporters AP + STA concurrency.")
    fun isStaApConcurrencySupported() = wifiManager.isStaApConcurrencySupported()

    @Rpc(description = "Check whether the device SDK is as least T")
    fun isAtLeastT() = SdkLevel.isAtLeastT()

    @Rpc(description = "Return whether the Sdk level is at least V.")
    fun isAtLeastV() = SdkLevel.isAtLeastV()

    @Rpc(description = "Check whether the device is at least B.")
    fun isAtLeastB(): Boolean {
        return SDK_INT >= 36 || (SDK_INT == 35 && isAtLeastPreReleaseCodename("Baklava"))
    }

    @Rpc(description = "Return the API level that the VSR requirement must be fulfilled.")
    fun getVsrApiLevel() = PropertyUtil.getVsrApiLevel()

    @Rpc(description = "Request cellular connection and ensure it is the default network.")
    fun requestCellularAndEnsureDefault() {
        ctsNetUtils.disableWifi()
        val network = cbHelper.requestCell()
        ctsNetUtils.expectNetworkIsSystemDefault(network)
    }

    @Rpc(description = "Reconnect to wifi if supported.")
    fun reconnectWifiIfSupported() {
        ctsNetUtils.reconnectWifiIfSupported()
    }

    @Rpc(description = "Unregister all connections.")
    fun unregisterAll() {
        cbHelper.unregisterAll()
    }

    @Rpc(description = "Ensure any wifi is connected and is the default network.")
    fun ensureWifiIsDefault() {
        val network = ctsNetUtils.ensureWifiConnected()
        ctsNetUtils.expectNetworkIsSystemDefault(network)
    }

    @Rpc(description = "Connect to specified wifi network.")
    // Suppress warning because WifiManager methods to connect to a config are
    // documented not to be deprecated for privileged users.
    @Suppress("DEPRECATION")
    fun connectToWifi(ssid: String, passphrase: String, requireValidation: Boolean): Long {
        val wifiConfig = WifiConfiguration()
        wifiConfig.SSID = "\"" + ssid + "\""
        wifiConfig.preSharedKey = "\"" + passphrase + "\""
        wifiConfig.hiddenSSID = true
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
        wifiConfig.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
        wifiConfig.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)

        // Add the test configuration and connect to it.
        val connectUtil = ConnectUtil(context)
        connectUtil.connectToWifiConfig(wifiConfig)

        // Implement manual SSID matching. Specifying the SSID in
        // NetworkSpecifier is ineffective
        // (see WifiNetworkAgentSpecifier#canBeSatisfiedBy for details).
        // Note that holding permission is necessary when waiting for
        // the callbacks. The handler thread checks permission; if
        // it's not present, the SSID will be redacted.
        val networkCallback = TestableNetworkCallback()
        val wifiRequest = NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build()
        return runAsShell(NETWORK_SETTINGS) {
            // Register the network callback is needed here.
            // This is to avoid the race condition where callback is fired before
            // acquiring permission.
            networkCallbackRule.registerNetworkCallback(wifiRequest, networkCallback)
            return@runAsShell networkCallback.eventuallyExpect<CapabilitiesChanged> {
                // Remove double quotes.
                val ssidFromCaps = (WifiInfo::sanitizeSsid)(it.caps.ssid)
                ssidFromCaps == ssid && (!requireValidation ||
                        it.caps.hasCapability(NET_CAPABILITY_VALIDATED))
            }.network.networkHandle
        }
    }

    @Rpc(description = "Get interface name from NetworkHandle")
    fun getInterfaceNameFromNetworkHandle(networkHandle: Long): String {
        val network = Network.fromNetworkHandle(networkHandle)
        return cm.getLinkProperties(network)!!.getInterfaceName()!!
    }

    @Rpc(description = "Check whether the device supports hotspot feature.")
    fun hasHotspotFeature(): Boolean {
        val tetheringCallback = ctsTetheringUtils.registerTetheringEventCallback()
        try {
            return tetheringCallback.isWifiTetheringSupported(context)
        } finally {
            ctsTetheringUtils.unregisterTetheringEventCallback(tetheringCallback)
        }
    }

    @Rpc(description = "Start a hotspot with given SSID and passphrase.")
    fun startHotspot(ssid: String, passphrase: String): String {
        // Store old config.
        runAsShell(OVERRIDE_WIFI_CONFIG) {
            oldSoftApConfig = wifiManager.getSoftApConfiguration()
        }

        val softApConfig = SoftApConfiguration.Builder()
            .setWifiSsid(WifiSsid.fromBytes(ssid.toByteArray()))
            .setPassphrase(passphrase, SECURITY_TYPE_WPA2_PSK)
            .setBand(SoftApConfiguration.BAND_2GHZ)
            .build()
        runAsShell(OVERRIDE_WIFI_CONFIG) {
            wifiManager.setSoftApConfiguration(softApConfig)
        }
        val tetheringCallback = ctsTetheringUtils.registerTetheringEventCallback()
        try {
            tetheringCallback.expectNoTetheringActive()
            return ctsTetheringUtils.startWifiTethering(tetheringCallback).getInterface()
        } finally {
            ctsTetheringUtils.unregisterTetheringEventCallback(tetheringCallback)
        }
    }

    @Rpc(description = "Stop all tethering.")
    fun stopAllTethering() {
        ctsTetheringUtils.stopAllTethering()

        // Restore old config.
        oldSoftApConfig?.let {
            runAsShell(OVERRIDE_WIFI_CONFIG) {
                wifiManager.setSoftApConfiguration(it)
            }
        }
    }

    @Rpc(description = "Create multicast socket")
    fun createMulticastSocket(ifname: String) {
        if (multicastSocket != null) {
            throw IllegalStateException("multicast socket is not null")
        }

        multicastSocket = MulticastSocket()
        multicastSocket?.networkInterface = NetworkInterface.getByName(ifname)
    }

    @Rpc(description = "Destroy multicast socket")
    fun destroyMulticastSocket() {
        if (multicastSocket == null) {
            throw IllegalStateException("multicast socket is null")
        }

        multicastSocket?.close()
        multicastSocket = null
    }

    @Rpc(description = "Join multicast group")
    fun joinMulticastGroup(multicastGroup: String) {
        if (multicastSocket == null) {
            throw IllegalStateException("multicast socket is null")
        }

        val groupToJoin = InetAddress.getByName(multicastGroup)
        multicastSocket?.joinGroup(
            InetSocketAddress(groupToJoin, 0),
            multicastSocket?.networkInterface
        )
    }

    @Rpc(description = "Leave multicast group")
    fun leaveMulticastGroup(multicastGroup: String) {
        if (multicastSocket == null) {
            throw IllegalStateException("multicast socket is null")
        }

        val groupToLeave = InetAddress.getByName(multicastGroup)
        multicastSocket?.leaveGroup(
            InetSocketAddress(groupToLeave, 0),
            multicastSocket?.networkInterface
        )
    }
}
