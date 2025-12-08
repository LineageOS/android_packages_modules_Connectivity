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

import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE
import android.net.NetworkRequest
import android.net.NetworkStack
import android.os.Build
import android.os.Process
import android.util.Range
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.CapabilitiesChanged
import org.junit.Test
import org.junit.runner.RunWith

// TODO: Use android.Manifest.permission.CREATE_APP_SPECIFIC_NETWORK once it's available.
private const val PERMISSION_CREATE_APP_SPECIFIC_NETWORK =
        "android.permission.CREATE_APP_SPECIFIC_NETWORK"

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSCreateAppSpecificNetworkPermissionTest : CSTest() {
    @Test
    fun testRegisterNetworkAgent() {
        deps.setBuildSdk(VERSION_25Q4)
        context.setPermission(
                android.Manifest.permission.NETWORK_FACTORY,
                PERMISSION_DENIED
        )
        context.setPermission(
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                PERMISSION_DENIED
        )
        context.setPermission(
                PERMISSION_CREATE_APP_SPECIFIC_NETWORK,
                PERMISSION_GRANTED
        )

        val nc = nc(TRANSPORT_WIFI_AWARE)
        nc.uids = null
        nc.addCapability(NET_CAPABILITY_NOT_RESTRICTED)
        val agent = Agent(nc = nc, score = keepConnectedScore())
        agent.connect()

        // Uid is set and NOT_RESTRICTED is removed
        val myUid = Process.myUid()
        val expectedUids = setOf(Range(myUid, myUid))
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)
        cb.eventuallyExpect<CapabilitiesChanged> {
            it.network == agent.network &&
                it.caps.uids!!.equals(expectedUids) &&
                !it.caps.hasCapability(NET_CAPABILITY_NOT_RESTRICTED)
        }

        // Attempt to change uid and make network non-restricted is ignored
        nc.setSingleUid(myUid + 1)
        nc.addCapability(NET_CAPABILITY_NOT_RESTRICTED)
        agent.sendNetworkCapabilities(nc)
        cb.assertNoCallback() {
            it is CapabilitiesChanged && it.network == agent.network && (
                    !it.caps.uids!!.equals(expectedUids) ||
                    it.caps.hasCapability(NET_CAPABILITY_NOT_RESTRICTED))
        }
    }
}
