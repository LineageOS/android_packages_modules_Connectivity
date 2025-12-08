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

package com.android.testutils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.android.net.module.util.Expectable
import com.android.net.module.util.TestableCallback
import com.android.net.module.util.eventuallyExpect
import com.android.testutils.TestableNetworkCallback.Event.Available
import com.android.testutils.TestableNetworkCallback.Event.Lost
import kotlin.test.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Utility class for Bluetooth operations within CTS tests.
 */
@SuppressLint("MissingPermission")
class AutoReleaseBluetoothRule(private val context: Context) : TestRule {
    companion object {
        private const val TIMEOUT_MS = 10000L
        private const val NO_CALLBACK_TIMEOUT_MS = 500L
    }

    private val bluetoothAdapter by lazy {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }
    private val cm by lazy { context.getSystemService(ConnectivityManager::class.java)!! }

    /**
     * A BroadcastReceiver specifically for listening to Bluetooth state changes.
     */
    private class TestableBluetoothStateChangeReceiver(
        private val callback: TestableCallback<Int> =
                TestableCallback(TIMEOUT_MS, NO_CALLBACK_TIMEOUT_MS)
    ) : BroadcastReceiver(), Expectable<Int> by callback {

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                history.add(state)
            }
        }

        // ---- Bridge to TestableCallback, do not modify (implements standard behavior) ----
        // Proxy methods: These make it simpler to call expect/assertNo, avoiding 'expect<_, T>'
        // They simply forward the call to the embedded internalEventTracker.
        inline fun <reified T : Int> eventuallyExpect(
                timeoutMs: Long = defaultTimeoutMs,
                errorMsg: String? = null,
                noinline predicate: (T) -> Boolean = { true }
        ) = eventuallyExpect<_, T>(timeoutMs, errorMsg, predicate)
        // ---- End of bridge section ----
    }

    /**
     * Checks if Bluetooth is supported on the current device.
     *
     * @return True if Bluetooth is supported, false otherwise.
     */
    fun isBluetoothSupported() = bluetoothAdapter != null

    /**
     * Check if Bluetooth is enabled on the current device.
     *
     * @return True if Bluetooth is enabled, false otherwise.
     * @throws NullPointerException if Bluetooth is not supported.
     */
    fun isBluetoothEnabled() = bluetoothAdapter!!.isEnabled

    /**
     * Sets the enabled state of the Bluetooth adapter on the device and waits for
     * the state to change accordingly.
     *
     * This method requires the `android.permission.BLUETOOTH_ADMIN` permission.
     * In a CTS test environment, ensure your test package has declared this permission
     * in its `AndroidManifest.xml` and that the test runner has the necessary permissions.
     *
     * @param enabled True to enable Bluetooth, false to disable.
     */
    fun setBluetoothEnabled(enabled: Boolean) {
        assertTrue(isBluetoothSupported())

        // Check if Bluetooth is already in the desired state.
        val currentEnabledState = isBluetoothEnabled()
        if (currentEnabledState == enabled) return

        val receiver = TestableBluetoothStateChangeReceiver()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)

        val cb = TestableNetworkCallback()
        val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        cm.registerNetworkCallback(request, cb)
        // Only wait for a short amount of time to check current status.
        val wasConnected = if (currentEnabledState) {
            cb.poll(timeoutMs = NO_CALLBACK_TIMEOUT_MS) { it is Available } != null
        } else {
            false
        }

        tryTest {
            context.registerReceiver(receiver, filter)
            runCommandInShell("svc bluetooth ${if (enabled) "enable" else "disable"}")
            receiver.eventuallyExpect<Int> {
                it == if (enabled) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF
            }

            val success = pollingCheck(TIMEOUT_MS) {
                enabled == bluetoothAdapter!!.isEnabled
            }
            assertTrue(success, "Could not ${if (enabled) "enable" else "disable"} Bluetooth")

            // If connected, wait for disconnection before returning to prevent flakiness.
            // Note that simply enabling Bluetooth does not necessarily result in a
            // connected network.
            if (wasConnected && !enabled) {
                cb.eventuallyExpect<Lost>()
            }
        } cleanup {
            context.unregisterReceiver(receiver)
            cm.unregisterNetworkCallback(cb)
        }
    }

    private inner class AutoReleaseBluetoothRuleStatement(
            private val base: Statement,
    ) : Statement() {
        override fun evaluate() {
            val oldEnabled = if (isBluetoothSupported()) isBluetoothEnabled() else null
            tryTest {
                base.evaluate()
            } cleanup {
                // Only restore if oldEnabled was not null (i.e., Bluetooth was supported).
                oldEnabled?.let { setBluetoothEnabled(it) }
            }
        }
    }

    override fun apply(base: Statement, description: Description): Statement {
        return AutoReleaseBluetoothRuleStatement(base)
    }
}
