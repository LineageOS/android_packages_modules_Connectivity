/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.net.ConnectivityManager.TYPE_BLUETOOTH
import android.net.ConnectivityManager.TYPE_ETHERNET
import android.net.ConnectivityManager.TYPE_MOBILE
import android.net.ConnectivityManager.TYPE_VPN
import android.net.ConnectivityManager.TYPE_WIFI
import android.net.ConnectivityManager.TYPE_WIMAX
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkIdentitySet.writeOptionalString
import android.os.Build
import android.telephony.TelephonyManager
import com.android.net.module.util.NetworkCapabilitiesUtils.TYPE_TEST
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

private const val TEST_IMSI1 = "testimsi1"

@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
@RunWith(DevSdkIgnoreRunner::class)
class NetworkIdentitySetTest {
    private val mockContext = mock(Context::class.java)

    private fun buildMobileNetworkStateSnapshot(subscriberId: String) =
            buildNetworkStateSnapshot(NetworkCapabilities(), subscriberId, TYPE_MOBILE)

    private fun buildNetworkStateSnapshot(
            caps: NetworkCapabilities,
            subscriberId: String,
            legacyNetworkType: Int
    ): NetworkStateSnapshot {
        return NetworkStateSnapshot(
                mock(Network::class.java),
                caps,
                LinkProperties(),
                subscriberId,
                legacyNetworkType
        )
    }

    private fun buildNetworkIdentity(
            legacyNetworkType: Int,
            vararg transportTypes: Int
    ): NetworkIdentity {
        val caps = NetworkCapabilities().apply {
            transportTypes.forEach {
                addTransportType(it)
            }
        }
        return NetworkIdentity.buildNetworkIdentity(
                mockContext,
                buildNetworkStateSnapshot(caps, TEST_IMSI1, legacyNetworkType),
                false,
                0
        )
    }

    @Test
    fun testCompare() {
        val ident1 = NetworkIdentity.buildNetworkIdentity(
                mockContext,
                buildMobileNetworkStateSnapshot(TEST_IMSI1),
                false /* defaultNetwork */,
                TelephonyManager.NETWORK_TYPE_UMTS
        )
        val ident2 = NetworkIdentity.buildNetworkIdentity(
                mockContext,
                buildMobileNetworkStateSnapshot(TEST_IMSI1),
                true /* defaultNetwork */,
                TelephonyManager.NETWORK_TYPE_UMTS
        )

        // Verify that the results of comparing two empty sets are equal
        assertEquals(0, NetworkIdentitySet.compare(NetworkIdentitySet(), NetworkIdentitySet()))

        val identSet1 = NetworkIdentitySet()
        val identSet2 = NetworkIdentitySet()
        identSet1.add(ident1)
        identSet2.add(ident2)
        assertEquals(-1, NetworkIdentitySet.compare(NetworkIdentitySet(), identSet1))
        assertEquals(1, NetworkIdentitySet.compare(identSet1, NetworkIdentitySet()))
        assertEquals(0, NetworkIdentitySet.compare(identSet1, identSet1))
        assertEquals(-1, NetworkIdentitySet.compare(identSet1, identSet2))
    }

    @Test
    fun testTransportTypesSerialization() {
        val originalSet = NetworkIdentitySet()
        originalSet.add(buildNetworkIdentity(TYPE_MOBILE, TRANSPORT_CELLULAR))
        originalSet.add(buildNetworkIdentity(TYPE_WIFI, TRANSPORT_VPN, TRANSPORT_WIFI))

        val baos = ByteArrayOutputStream()
        val outDataStream = DataOutputStream(baos)
        originalSet.writeToStream(outDataStream)

        val bais = ByteArrayInputStream(baos.toByteArray())
        val inDataStream = DataInputStream(bais)
        val deserializedSet = NetworkIdentitySet(inDataStream)

        assertEquals(originalSet, deserializedSet)
    }

    /**
     * Writes a single NetworkIdentity to the stream in a specific older version format.
     * This simulates an old saved state.
     * Version: VERSION_ADD_SUB_ID (7), which does NOT include transportTypesBits explicitly.
     */
    private fun writeNetworkIdentityV7(out: DataOutputStream, ident: NetworkIdentity) {
        out.writeInt(7) // Write the old version number
        out.writeInt(1) // Number of identities in the set
        out.writeInt(ident.type)
        out.writeInt(ident.ratType)
        writeOptionalString(out, ident.subscriberId)
        writeOptionalString(out, ident.wifiNetworkKey)
        out.writeBoolean(ident.isRoaming)
        out.writeBoolean(ident.isMetered)
        out.writeBoolean(ident.isDefaultNetwork)
        out.writeInt(ident.oemManaged)
        out.writeInt(ident.subId)
        // V7 and earlier versions DO NOT write transportTypesBits
    }

    @Test
    fun testMigrateToSupportTransportTypes() {
        doTestMigrateToSupportTransportTypes(TYPE_MOBILE, TRANSPORT_CELLULAR)
        doTestMigrateToSupportTransportTypes(TYPE_WIFI, TRANSPORT_WIFI)
        doTestMigrateToSupportTransportTypes(TYPE_BLUETOOTH, TRANSPORT_BLUETOOTH)
        doTestMigrateToSupportTransportTypes(TYPE_ETHERNET, TRANSPORT_ETHERNET)
        doTestMigrateToSupportTransportTypes(TYPE_VPN, TRANSPORT_VPN)
        doTestMigrateToSupportTransportTypes(TYPE_TEST, TRANSPORT_TEST)
        doTestMigrateToSupportTransportTypes(TYPE_WIMAX)
    }

    private fun doTestMigrateToSupportTransportTypes(
            legacyNetworkType: Int,
            vararg expectedTransportTypes: Int
    ) {
        val ident = buildNetworkIdentity(legacyNetworkType)

        val baos = ByteArrayOutputStream()
        val outDataStream = DataOutputStream(baos)
        writeNetworkIdentityV7(outDataStream, ident)

        val bais = ByteArrayInputStream(baos.toByteArray())
        val inDataStream = DataInputStream(bais)
        val deserializedSet = NetworkIdentitySet(inDataStream)

        // Prepare the expected identity set, verify the transport type deduced from
        // the legacy network type.
        val expectedIdent = buildNetworkIdentity(legacyNetworkType, *expectedTransportTypes)
        val expectedSet = NetworkIdentitySet().also {
            it.add(expectedIdent)
        }

        assertEquals(expectedSet, deserializedSet)
    }
}
