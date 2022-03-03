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

package android.nearby.multidevices.fastpair.provider

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE
import android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
import android.bluetooth.BluetoothAdapter.SCAN_MODE_NONE
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.annotation.VisibleForTesting

/** Processes the state of the local Bluetooth adapter. */
class BluetoothStateChangeReceiver(private val context: Context) : BroadcastReceiver() {
    @VisibleForTesting
    var listener: EventListener? = null

    /**
     * Registers this Bluetooth state change receiver.
     *
     * @param listener the listener for Bluetooth state events.
     */
    fun register(listener: EventListener) {
        this.listener = listener
        val bondStateFilter =
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            }
        context.registerReceiver(
            this,
            bondStateFilter,
            /* broadcastPermission= */ null,
            /* scheduler= */ null
        )
    }

    /** Unregisters this Bluetooth state change receiver. */
    fun unregister() {
        context.unregisterReceiver(this)
        this.listener = null
    }

    /**
     * Callback method for receiving Intent broadcast for Bluetooth state.
     *
     * See [android.content.BroadcastReceiver#onReceive].
     *
     * @param context the Context in which the receiver is running.
     * @param intent the Intent being received.
     */
    @RequiresPermission(allOf = [BLUETOOTH, BLUETOOTH_CONNECT])
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "BluetoothStateChangeReceiver received intent, action=${intent.action}")

        when (intent.action) {
            BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
                val scanMode =
                    intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, SCAN_MODE_NONE)
                val scanModeStr = scanModeToString(scanMode)
                Log.i(TAG, "ACTION_SCAN_MODE_CHANGED, the new scanMode: $scanModeStr")
                listener?.onScanModeChange(scanModeStr)
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val remoteDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val remoteDeviceString =
                    if (remoteDevice != null) "${remoteDevice.name}-${remoteDevice.address}" else "none"
                var boundStateString = "ERROR"
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_NONE -> {
                        boundStateString = "BOND_NONE"
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        boundStateString = "BOND_BONDING"
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        boundStateString = "BOND_BONDED"
                    }
                }
                Log.i(
                    TAG,
                    "The bound state of the remote device ($remoteDeviceString) change to $boundStateString."
                )
            }
            else -> {}
        }
    }

    private fun scanModeToString(scanMode: Int): String {
        return when (scanMode) {
            SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "DISCOVERABLE"
            SCAN_MODE_CONNECTABLE -> "CONNECTABLE"
            SCAN_MODE_NONE -> "NOT CONNECTABLE"
            else -> "UNKNOWN($scanMode)"
        }
    }

    /** Interface for listening the events from Bluetooth adapter. */
    interface EventListener {
        /**
         * Reports the current scan mode of the local Adapter.
         *
         * @param mode the current scan mode in string.
         */
        fun onScanModeChange(mode: String)
    }

    companion object {
        private const val TAG = "BluetoothStateReceiver"
    }
}
