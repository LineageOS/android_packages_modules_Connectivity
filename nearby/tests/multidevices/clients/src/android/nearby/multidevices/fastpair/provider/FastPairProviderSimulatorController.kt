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

import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.nearby.fastpair.provider.FastPairSimulator
import android.nearby.fastpair.provider.bluetooth.BluetoothController
import com.google.android.mobly.snippet.util.Log
import com.google.common.io.BaseEncoding

class FastPairProviderSimulatorController(
    private val context: Context,
    private val modelId: String,
    private val antiSpoofingKeyString: String,
    private val eventListener: EventListener,
) : BluetoothController.EventListener {
    private lateinit var bluetoothController: BluetoothController
    lateinit var simulator: FastPairSimulator

    fun startProviderSimulator() {
        bluetoothController = BluetoothController(context, this)
        bluetoothController.registerBluetoothStateReceiver()
        bluetoothController.enableBluetooth()
        bluetoothController.connectA2DPSinkProfile()
    }

    fun stopProviderSimulator() {
        simulator.destroy()
        bluetoothController.unregisterBluetoothStateReceiver()
    }

    override fun onA2DPSinkProfileConnected() {
        createFastPairSimulator()
    }

    override fun onBondStateChanged(bondState: Int) {
    }

    override fun onConnectionStateChanged(connectionState: Int) {
    }

    override fun onScanModeChange(mode: Int) {
        eventListener.onScanModeChange(FastPairSimulator.scanModeToString(mode))
    }

    private fun createFastPairSimulator() {
        val antiSpoofingKey = BaseEncoding.base64().decode(antiSpoofingKeyString)
        simulator = FastPairSimulator(context, FastPairSimulator.Options.builder(modelId)
            .setAdvertisingModelId(modelId)
            .setBluetoothAddress(null)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setAdvertisingChangedCallback {
                val isAdvertising = simulator.isAdvertising
                Log.i("FastPairSimulator callback(), isAdvertising: $isAdvertising")
                eventListener.onAdvertisingChange(isAdvertising)
            }
            .setAntiSpoofingPrivateKey(antiSpoofingKey)
            .setUseRandomSaltForAccountKeyRotation(false)
            .setDataOnlyConnection(false)
            .setShowsPasskeyConfirmation(false)
            .setRemoveAllDevicesDuringPairing(true)
            .build())
    }

    /** Interface for listening the events from Fast Pair Provider Simulator. */
    interface EventListener {
        /**
         * Reports the current scan mode of the local Adapter.
         *
         * @param mode the current scan mode in string.
         */
        fun onScanModeChange(mode: String)

        /**
         * Indicates the advertising state of the Fast Pair provider simulator has changed.
         *
         * @param isAdvertising the current advertising state, true if advertising otherwise false.
         */
        fun onAdvertisingChange(isAdvertising: Boolean)
    }
}