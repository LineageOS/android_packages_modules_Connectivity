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

package com.android.testutils

import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.RecorderCallback.CallbackEntry
import java.util.Collections
import kotlin.test.fail
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A rule to file [NetworkCallback]s to request or watch networks.
 *
 * The callbacks filed in test methods are automatically unregistered when the method completes.
 */
class AutoReleaseNetworkCallbackRule : NetworkCallbackHelper(), TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return RequestCellNetworkStatement(base, description)
    }

    private inner class RequestCellNetworkStatement(
        private val base: Statement,
        private val description: Description
    ) : Statement() {
        override fun evaluate() {
            tryTest {
                base.evaluate()
            } cleanup {
                unregisterAll()
            }
        }
    }
}

/**
 * Helps file [NetworkCallback]s to request or watch networks, keeping track of them for cleanup.
 */
open class NetworkCallbackHelper {
    private val cm by lazy {
        InstrumentationRegistry.getInstrumentation().context
            .getSystemService(ConnectivityManager::class.java)
            ?: fail("ConnectivityManager not found")
    }
    private val cbToCleanup = Collections.synchronizedSet(mutableSetOf<NetworkCallback>())
    private var cellRequestCb: TestableNetworkCallback? = null

    /**
     * Convenience method to request a cell network, similarly to [requestNetwork].
     *
     * The rule will keep tract of a single cell network request, which can be unrequested manually
     * using [unrequestCell].
     */
    fun requestCell(): Network {
        if (cellRequestCb != null) {
            fail("Cell network was already requested")
        }
        val cb = requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        )
        cellRequestCb = cb
        return cb.expect<CallbackEntry.Available>(
            errorMsg = "Cell network not available. " +
                    "Please ensure the device has working mobile data."
        ).network
    }

    /**
     * Unrequest a cell network requested through [requestCell].
     */
    fun unrequestCell() {
        val cb = cellRequestCb ?: fail("Cell network was not requested")
        unregisterNetworkCallback(cb)
        cellRequestCb = null
    }

    /**
     * File a request for a Network.
     *
     * This will fail tests (throw) if the cell network cannot be obtained, or if it was already
     * requested.
     *
     * Tests may call [unregisterNetworkCallback] once they are done using the returned [Network],
     * otherwise it will be automatically unrequested after the test.
     */
    @JvmOverloads
    fun requestNetwork(
        request: NetworkRequest,
        cb: TestableNetworkCallback = TestableNetworkCallback()
    ): TestableNetworkCallback {
        cm.requestNetwork(request, cb)
        cbToCleanup.add(cb)
        return cb
    }

    /**
     * File a callback for a NetworkRequest.
     *
     * This will fail tests (throw) if the cell network cannot be obtained, or if it was already
     * requested.
     *
     * Tests may call [unregisterNetworkCallback] once they are done using the returned [Network],
     * otherwise it will be automatically unrequested after the test.
     */
    @JvmOverloads
    fun registerNetworkCallback(
        request: NetworkRequest,
        cb: TestableNetworkCallback = TestableNetworkCallback()
    ): TestableNetworkCallback {
        cm.registerNetworkCallback(request, cb)
        cbToCleanup.add(cb)
        return cb
    }

    /**
     * Unregister a callback filed using registration methods in this class.
     */
    fun unregisterNetworkCallback(cb: NetworkCallback) {
        cm.unregisterNetworkCallback(cb)
        cbToCleanup.remove(cb)
    }

    /**
     * Unregister all callbacks that were filed using registration methods in this class.
     */
    fun unregisterAll() {
        cbToCleanup.forEach { cm.unregisterNetworkCallback(it) }
        cbToCleanup.clear()
        cellRequestCb = null
    }
}
