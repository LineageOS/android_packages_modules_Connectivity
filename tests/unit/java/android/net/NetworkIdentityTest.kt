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

package android.net

import android.content.Context
import android.net.ConnectivityManager.TYPE_MOBILE
import android.net.NetworkIdentity.OEM_NONE
import android.net.NetworkIdentity.OEM_PAID
import android.net.NetworkIdentity.OEM_PRIVATE
import android.net.NetworkIdentity.getOemBitfield
import android.telephony.TelephonyManager
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_IMSI = "testimsi"

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class NetworkIdentityTest {
    private val mockContext = mock(Context::class.java)

    private fun buildMobileNetworkStateSnapshot(
        caps: NetworkCapabilities,
        subscriberId: String
    ): NetworkStateSnapshot {
        return NetworkStateSnapshot(mock(Network::class.java), caps,
                LinkProperties(), subscriberId, TYPE_MOBILE)
    }

    @Test
    fun testGetOemBitfield() {
        val oemNone = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID, false)
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE, false)
        }
        val oemPaid = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID, true)
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE, false)
        }
        val oemPrivate = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID, false)
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE, true)
        }
        val oemAll = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID, true)
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE, true)
        }

        assertEquals(getOemBitfield(oemNone), OEM_NONE)
        assertEquals(getOemBitfield(oemPaid), OEM_PAID)
        assertEquals(getOemBitfield(oemPrivate), OEM_PRIVATE)
        assertEquals(getOemBitfield(oemAll), OEM_PAID or OEM_PRIVATE)
    }

    @Test
    fun testGetMetered() {
        // Verify network is metered.
        val netIdent1 = NetworkIdentity.buildNetworkIdentity(mockContext,
                buildMobileNetworkStateSnapshot(NetworkCapabilities(), TEST_IMSI),
                false /* defaultNetwork */, TelephonyManager.NETWORK_TYPE_UMTS)
        assertTrue(netIdent1.getMetered())

        // Verify network is not metered because it has NET_CAPABILITY_NOT_METERED capability.
        val capsNotMetered = NetworkCapabilities.Builder().apply {
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.build()
        val netIdent2 = NetworkIdentity.buildNetworkIdentity(mockContext,
                buildMobileNetworkStateSnapshot(capsNotMetered, TEST_IMSI),
                false /* defaultNetwork */, TelephonyManager.NETWORK_TYPE_UMTS)
        assertFalse(netIdent2.getMetered())

        // Verify network is not metered because it has NET_CAPABILITY_TEMPORARILY_NOT_METERED
        // capability .
        val capsTempNotMetered = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED, true)
        }
        val netIdent3 = NetworkIdentity.buildNetworkIdentity(mockContext,
                buildMobileNetworkStateSnapshot(capsTempNotMetered, TEST_IMSI),
                false /* defaultNetwork */, TelephonyManager.NETWORK_TYPE_UMTS)
        assertFalse(netIdent3.getMetered())
    }
}
