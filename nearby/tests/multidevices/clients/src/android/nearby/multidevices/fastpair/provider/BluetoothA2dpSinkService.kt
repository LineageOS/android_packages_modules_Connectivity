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

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.annotation.TargetApi
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.annotation.VisibleForTesting

/** Maintains an environment for Bluetooth A2DP sink profile. */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class BluetoothA2dpSinkService(private val context: Context) {
    private val bluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter!!
    private var a2dpSinkProxy: BluetoothProfile? = null

    /**
     * Starts the Bluetooth A2DP sink profile proxy.
     *
     * @param onServiceConnected the callback for the first time onServiceConnected.
     */
    fun start(onServiceConnected: () -> Unit) {
        // Get the A2DP proxy before continuing with initialization.
        bluetoothAdapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    // When Bluetooth turns off and then on again, this is called again. But we only care
                    // the first time. There doesn't seem to be a way to unregister our listener.
                    if (a2dpSinkProxy == null) {
                        a2dpSinkProxy = proxy
                        onServiceConnected()
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            },
            BLUETOOTH_PROFILE_A2DP_SINK
        )
    }

    /**
     * Checks the device is paired or not.
     *
     * @param remoteBluetoothDevice the device to check is paired or not.
     */
    @RequiresPermission(BLUETOOTH_CONNECT)
    fun isPaired(remoteBluetoothDevice: BluetoothDevice?): Boolean =
        bluetoothAdapter.bondedDevices.contains(remoteBluetoothDevice)

    /**
     * Gets the current Bluetooth scan mode of the local Bluetooth adapter.
     */
    @RequiresPermission(BLUETOOTH_SCAN)
    fun getScanMode(): Int = bluetoothAdapter.scanMode

    /**
     * Clears the bounded devices.
     *
     * @param removeBondDevice the callback to remove bounded devices.
     */
    @RequiresPermission(BLUETOOTH_CONNECT)
    fun clearBoundedDevices(removeBondDevice: (BluetoothDevice) -> Unit) {
        for (device in bluetoothAdapter.bondedDevices) {
            if (device.bluetoothClass.majorDeviceClass == BluetoothClass.Device.Major.PHONE) {
                removeBondDevice(device)
            }
        }
    }

    /**
     * Clears the connected but unbounded devices.
     *
     * Sometimes a device will still be connected even though it's not bonded. :( Clear that too.
     *
     * @param disconnectDevice the callback to clear connected but unbounded devices.
     */
    fun clearConnectedUnboundedDevices(
        disconnectDevice: (BluetoothProfile, BluetoothDevice) -> Unit,
    ) {
        for (device in a2dpSinkProxy!!.connectedDevices) {
            disconnectDevice(a2dpSinkProxy!!, device)
        }
    }

    companion object {
        /** Hidden SystemApi field in [android.bluetooth.BluetoothProfile] interface. */
        @VisibleForTesting
        const val BLUETOOTH_PROFILE_A2DP_SINK = 11
    }
}
