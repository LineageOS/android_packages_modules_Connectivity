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

import android.Manifest.permission.LOCAL_MAC_ADDRESS
import android.Manifest.permission.THREAD_NETWORK_PRIVILEGED
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.MacAddress
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.REDACT_FOR_LOCAL_MAC_ADDRESS
import android.net.NetworkCapabilities.REDACT_FOR_THREAD_NETWORK_PRIVILEGED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.os.Build.VERSION_CODES
import android.os.Parcel
import android.os.Parcelable
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.CapabilitiesChanged
import com.android.testutils.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for {@link NetworkSpecifier} functionalities in the ConnectivityService class. */
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(VERSION_CODES.R)
@DevSdkIgnoreRunner.MonitorThreadLeak
class CSNetworkSpecifierTest : CSTest() {
    @get:Rule(order = 0) val mIgnoreRule = DevSdkIgnoreRule()

    /**
     * A NetworkSpecifier subclass which overrides {@link
     * NetworkSpecifier#getApplicableRedactions()} and {@link NetworkSpecifier#redact(long)}.
     */
    data class RedactableNetworkSpecifier(
            val macAddress: MacAddress?, val threadPrivilegedKey: String?) :
            NetworkSpecifier(), Parcelable {
        constructor(
                p: Parcel
        ) : this(p.readParcelable(MacAddress::class.java.classLoader, MacAddress::class.java),
                p.readString())

        override fun canBeSatisfiedBy(other: NetworkSpecifier?) = equals(other)

        override fun redact(redactions: Long): NetworkSpecifier? =
                RedactableNetworkSpecifier(
                        if ((redactions and REDACT_FOR_LOCAL_MAC_ADDRESS) != 0L) null
                        else macAddress,
                        if ((redactions and REDACT_FOR_THREAD_NETWORK_PRIVILEGED) != 0L) null
                        else threadPrivilegedKey,
                )

        override fun getApplicableRedactions() =
                REDACT_FOR_LOCAL_MAC_ADDRESS or REDACT_FOR_THREAD_NETWORK_PRIVILEGED

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeParcelable(macAddress, 0 /* parcelableFlags */)
            dest.writeString(threadPrivilegedKey)
        }

        override fun describeContents() = 0
        companion object CREATOR : Parcelable.Creator<RedactableNetworkSpecifier> {
            override fun createFromParcel(source: Parcel) = RedactableNetworkSpecifier(source)
            override fun newArray(size: Int) = arrayOfNulls<RedactableNetworkSpecifier?>(size)
        }
    }

    /** Legacy NetworkSpecifier subclass which overrides only the {@code redact()} method. */
    data class LegacyNetworkSpecifier(val macAddress: MacAddress?) :
            NetworkSpecifier(), Parcelable {
        constructor(
                p: Parcel
        ) : this(p.readParcelable(MacAddress::class.java.classLoader, MacAddress::class.java))

        override fun canBeSatisfiedBy(other: NetworkSpecifier?) = equals(other)

        override fun redact(): NetworkSpecifier? =
                LegacyNetworkSpecifier(null)

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeParcelable(macAddress, 0 /* parcelableFlags */)
        }

        override fun describeContents() = 0
        companion object CREATOR : Parcelable.Creator<LegacyNetworkSpecifier> {
            override fun createFromParcel(source: Parcel) = LegacyNetworkSpecifier(source)
            override fun newArray(size: Int) = arrayOfNulls<LegacyNetworkSpecifier?>(size)
        }
    }

    fun newWifiRequestBuilder(): NetworkRequest.Builder {
        return NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI)
    }

    fun newWifiCapabilities(specifier: NetworkSpecifier): NetworkCapabilities {
        return defaultNc().apply {
            addTransportType(TRANSPORT_WIFI)
            setNetworkSpecifier(specifier)
        }
    }

    @Test
    @IgnoreUpTo(VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testRequestNetworkPermission_permissionDenied_throwsSecurityException() {
        context.setPermission(LOCAL_MAC_ADDRESS, PERMISSION_DENIED)
        context.setPermission(THREAD_NETWORK_PRIVILEGED, PERMISSION_DENIED)
        val specifier = RedactableNetworkSpecifier(DEFAULT_MAC_ADDR, DEFAULT_THREAD_KEY)
        val request = newWifiRequestBuilder().setNetworkSpecifier(specifier).build()

        assertThrows(SecurityException::class.java) {
            cm.requestNetwork(request, TestableNetworkCallback())
        }
        assertThrows(SecurityException::class.java) {
            cm.registerNetworkCallback(request, TestableNetworkCallback())
        }
    }

    @Test
    @IgnoreUpTo(VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testRequestNetworkPermission_noNetworkSpecifier_noThrow() {
        context.setPermission(LOCAL_MAC_ADDRESS, PERMISSION_DENIED)
        context.setPermission(THREAD_NETWORK_PRIVILEGED, PERMISSION_DENIED)
        val request = newWifiRequestBuilder().build()

        cm.requestNetwork(request, TestableNetworkCallback())
        val callback = TestableNetworkCallback()
        cm.registerNetworkCallback(request, callback)
        cm.unregisterNetworkCallback(callback)
    }

    @Test
    @IgnoreUpTo(VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testRequestNetworkPermission_permissionGranted_noThrow() {
        context.setPermission(LOCAL_MAC_ADDRESS, PERMISSION_GRANTED)
        context.setPermission(THREAD_NETWORK_PRIVILEGED, PERMISSION_GRANTED)
        val specifier = RedactableNetworkSpecifier(DEFAULT_MAC_ADDR, DEFAULT_THREAD_KEY)
        val request = newWifiRequestBuilder().setNetworkSpecifier(specifier).build()

        cm.requestNetwork(request, TestableNetworkCallback())
        val callback = TestableNetworkCallback()
        cm.registerNetworkCallback(request, callback)
        cm.unregisterNetworkCallback(callback)
    }

    @Test
    @IgnoreAfter(VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testThreadPrivilegedPermission_platformOlderThanV_throwsSecurityException() {
        context.setPermission(LOCAL_MAC_ADDRESS, PERMISSION_GRANTED)
        context.setPermission(THREAD_NETWORK_PRIVILEGED, PERMISSION_GRANTED)
        val specifier = RedactableNetworkSpecifier(DEFAULT_MAC_ADDR, DEFAULT_THREAD_KEY)
        val request = newWifiRequestBuilder().setNetworkSpecifier(specifier).build()

        assertThrows(SecurityException::class.java) {
            cm.requestNetwork(request, TestableNetworkCallback())
        }
        assertThrows(SecurityException::class.java) {
            cm.registerNetworkCallback(request, TestableNetworkCallback())
        }
    }

    @Test
    @IgnoreUpTo(VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testNetworkCallback_permissionGranted_notRedacted() {
        context.setPermission(LOCAL_MAC_ADDRESS, PERMISSION_GRANTED)
        context.setPermission(THREAD_NETWORK_PRIVILEGED, PERMISSION_GRANTED)
        val specifier = RedactableNetworkSpecifier(DEFAULT_MAC_ADDR, DEFAULT_THREAD_KEY)
        val request = newWifiRequestBuilder().setNetworkSpecifier(specifier).build()

        val callback = TestableNetworkCallback()
        cm.registerNetworkCallback(request, callback)
        Agent(TRANSPORT_WIFI, baseNc = newWifiCapabilities(specifier)).connect()

        callback.eventuallyExpect<CapabilitiesChanged> {
            it.caps.networkSpecifier == specifier
        }
        cm.unregisterNetworkCallback(callback)
    }

    @Test
    @IgnoreUpTo(VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testNetworkCallback_permissionDenied_redacted() {
        context.setPermission(LOCAL_MAC_ADDRESS, PERMISSION_DENIED)
        context.setPermission(THREAD_NETWORK_PRIVILEGED, PERMISSION_DENIED)
        val specifier = RedactableNetworkSpecifier(DEFAULT_MAC_ADDR, DEFAULT_THREAD_KEY)
        val request = newWifiRequestBuilder().build()

        val callback = TestableNetworkCallback()
        cm.registerNetworkCallback(request, callback)
        Agent(TRANSPORT_WIFI, baseNc = newWifiCapabilities(specifier)).connect()

        callback.eventuallyExpect<CapabilitiesChanged> {
            (it.caps.networkSpecifier as RedactableNetworkSpecifier).macAddress == null
            (it.caps.networkSpecifier as RedactableNetworkSpecifier).threadPrivilegedKey == null
        }
        cm.unregisterNetworkCallback(callback)
    }

    @Test
    @IgnoreUpTo(VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testNetworkCallback_permissionGrantedForLegacyNetworkSpecifier_redacted() {
        context.setPermission(LOCAL_MAC_ADDRESS, PERMISSION_GRANTED)
        context.setPermission(THREAD_NETWORK_PRIVILEGED, PERMISSION_GRANTED)
        val specifier = LegacyNetworkSpecifier(DEFAULT_MAC_ADDR)
        val request = newWifiRequestBuilder().build()

        val callback = TestableNetworkCallback()
        cm.registerNetworkCallback(request, callback)
        Agent(TRANSPORT_WIFI, baseNc = newWifiCapabilities(specifier)).connect()

        callback.eventuallyExpect<CapabilitiesChanged> {
            (it.caps.networkSpecifier as LegacyNetworkSpecifier).macAddress == null
        }
        cm.unregisterNetworkCallback(callback)
    }

    companion object {
        private val DEFAULT_MAC_ADDR = MacAddress.fromString("0:1:2:3:4:5")
        private val DEFAULT_THREAD_KEY = "thread-key"
    }
}
