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

import android.net.INetd.PERMISSION_INTERNET
import android.net.INetd.PERMISSION_NONE
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.connectivity.ConnectivityCompatChanges.NETWORK_BLOCKED_WITHOUT_INTERNET_PERMISSION
import android.os.Build
import android.os.Process
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkOfferCallback
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.doReturn

private const val TIMEOUT_MS = 5_000L
private const val NO_CB_TIMEOUT_MS = 200L

private fun cellNc() = nc(
    TRANSPORT_CELLULAR,
    NET_CAPABILITY_NOT_SUSPENDED,
    NET_CAPABILITY_NOT_VCN_MANAGED
)

private fun cellRequest() = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_CELLULAR)
        .build()

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSEnforceMeteredApnPolicy : CSTest() {
    @Parameterized.Parameter(0) lateinit var params: TestParams

    data class TestParams(
            val changeEnabled: Boolean,
            val hasInternetPermission: Boolean,
            val expectRequestMeteredNetwork: Boolean
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun arguments() = listOf(
                // For backwards compatibility, if the change is disabled and the uid does not have
                // the internet permission, the uid can request metered network even if the uid is
                // restricted from using metered networks.
                TestParams(
                        changeEnabled = false,
                        hasInternetPermission = false,
                        expectRequestMeteredNetwork = true
                ),
                // If the uid has the internet permission and the uid is restricted from using
                // metered network, the uid cannot request metered network even if the change is
                // disabled.
                TestParams(
                        changeEnabled = false,
                        hasInternetPermission = true,
                        expectRequestMeteredNetwork = false
                ),
                // If the change is enabled and the uid is restricted from using metered network,
                // the uid cannot request metered network regardless of the internet permission.
                TestParams(
                        changeEnabled = true,
                        hasInternetPermission = false,
                        expectRequestMeteredNetwork = false
                ),
                TestParams(
                        changeEnabled = true,
                        hasInternetPermission = true,
                        expectRequestMeteredNetwork = false
                )
        )
    }

    @Test
    fun testEnforceMeteredApnPolicy() {
        deps.setBuildSdk(VERSION_V)
        deps.setChangeIdEnabled(params.changeEnabled, NETWORK_BLOCKED_WITHOUT_INTERNET_PERMISSION)
        doReturn(true).`when`(bpfNetMaps).isUidRestrictedOnMeteredNetworks(Process.myUid())
        doReturn(if (params.hasInternetPermission) PERMISSION_INTERNET else PERMISSION_NONE )
                .`when`(bpfNetMaps).getNetPermForUid(Process.myUid())

        val provider = NetworkProvider(context, csHandlerThread.looper, "Cell provider")
        cm.registerNetworkProvider(provider)
        val offerCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(
                NetworkScore.Builder().build(),
                cellNc(),
                Runnable::run,
                offerCb
        )
        val cb = TestableNetworkCallback()
        cm.requestNetwork(cellRequest(), cb)

        if (params.expectRequestMeteredNetwork) {
            offerCb.expectOnNetworkNeeded(cellNc())
        } else {
            offerCb.assertNoCallback()
        }

        cm.unregisterNetworkCallback(cb)
        provider.unregisterNetworkOffer(offerCb)
    }
}
