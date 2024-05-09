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

import android.net.INetd.PERMISSION_INTERNET
import android.net.INetd.PERMISSION_NONE
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkInfo.DetailedState.BLOCKED
import android.net.NetworkInfo.DetailedState.CONNECTED
import android.net.connectivity.ConnectivityCompatChanges.NETWORKINFO_WITHOUT_INTERNET_BLOCKED
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doReturn

private fun nc() = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_WIFI)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSActiveNetworkInfoTest : CSTest() {

    fun doTestGetActiveNetworkInfo(
            changeEnabled: Boolean,
            permissions: Int,
            expectBlocked: Boolean
    ) {
        deps.setChangeIdEnabled(changeEnabled, NETWORKINFO_WITHOUT_INTERNET_BLOCKED)
        doReturn(permissions).`when`(bpfNetMaps).getNetPermForUid(anyInt())

        val agent = Agent(nc = nc())
        agent.connect()

        val networkInfo = cm.activeNetworkInfo
        assertNotNull(networkInfo)
        if (expectBlocked) {
            assertEquals(BLOCKED, networkInfo.detailedState)
        } else {
            assertEquals(CONNECTED, networkInfo.detailedState)
        }
        agent.disconnect()
    }

    @Test
    fun testGetActiveNetworkInfo() {
        doReturn(true).`when`(bpfNetMaps).isUidNetworkingBlocked(anyInt(), anyBoolean())
        doTestGetActiveNetworkInfo(
                changeEnabled = true,
                permissions = PERMISSION_NONE,
                expectBlocked = true
        )
        doTestGetActiveNetworkInfo(
                changeEnabled = false,
                permissions = PERMISSION_INTERNET,
                expectBlocked = true
        )
        // getActiveNetworkInfo does not return NetworkInfo with blocked state if the compat change
        // is disabled and the app does not have PERMISSION_INTERNET
        doTestGetActiveNetworkInfo(
                changeEnabled = false,
                permissions = PERMISSION_NONE,
                expectBlocked = false
        )
    }
}
