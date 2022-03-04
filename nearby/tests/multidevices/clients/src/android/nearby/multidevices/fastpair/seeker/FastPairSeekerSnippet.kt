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

package android.nearby.multidevices.fastpair.seeker

import android.content.Context
import android.content.Intent
import android.nearby.NearbyManager
import android.nearby.ScanCallback
import android.nearby.ScanRequest
import androidx.test.core.app.ApplicationProvider
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc
import com.google.android.mobly.snippet.util.Log

/** Expose Mobly RPC methods for Python side to test fast pair seeker role. */
class FastPairSeekerSnippet : Snippet {
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val nearbyManager = appContext.getSystemService(Context.NEARBY_SERVICE) as NearbyManager
    private lateinit var scanCallback: ScanCallback

    /**
     * Starts scanning as a Fast Pair seeker to find Fast Pair provider devices.
     *
     * @param callbackId the callback ID corresponding to the {@link FastPairSeekerSnippet#startScan}
     * call that started the scanning.
     */
    @AsyncRpc(description = "Starts scanning as Fast Pair seeker to find Fast Pair provider devices.")
    fun startScan(callbackId: String) {
        val scanRequest = ScanRequest.Builder()
            .setScanMode(ScanRequest.SCAN_MODE_LOW_LATENCY)
            .setScanType(ScanRequest.SCAN_TYPE_FAST_PAIR)
            .setEnableBle(true)
            .build()
        scanCallback = ScanCallbackEvents(callbackId)

        Log.i("Start Fast Pair scanning via BLE...")
        nearbyManager.startScan(scanRequest, /* executor */ { it.run() }, scanCallback)
    }

    /** Stops the Fast Pair seeker scanning. */
    @Rpc(description = "Stops the Fast Pair seeker scanning.")
    fun stopScan() {
        Log.i("Stop Fast Pair scanning.")
        nearbyManager.stopScan(scanCallback)
    }

    /** Starts the Fast Pair seeker pairing. */
    @Rpc(description = "Starts the Fast Pair seeker pairing.")
    fun startPairing(modelId: String, address: String) {
        Log.i("Starts the Fast Pair seeker pairing.")

        val scanIntent = Intent().apply {
            action = FAST_PAIR_MANAGER_ACTION_START_PAIRING
            putExtra(FAST_PAIR_MANAGER_EXTRA_MODEL_ID, modelId.toByteArray())
            putExtra(FAST_PAIR_MANAGER_EXTRA_ADDRESS, address)
        }
        appContext.sendBroadcast(scanIntent)
    }

    /** Puts a model id to FastPairAntiSpoofKeyDeviceMetadata pair into test data cache.
     *
     * @param modelId a string of model id to be associated with.
     * @param json a string of FastPairAntiSpoofKeyDeviceMetadata JSON object.
     */
    @Rpc(description = "Puts a model id to FastPairAntiSpoofKeyDeviceMetadata pair into test data cache.")
    fun putAntiSpoofKeyDeviceMetadata(modelId: String, json: String) {
        Log.i("Puts a model id to FastPairAntiSpoofKeyDeviceMetadata pair into test data cache.")
        FastPairTestDataCache.putAntiSpoofKeyDeviceMetadata(modelId, json)
    }

    /** Puts an array of FastPairAccountKeyDeviceMetadata into test data cache.
     *
     * @param json a string of FastPairAccountKeyDeviceMetadata JSON array.
     */
    @Rpc(description = "Puts an array of FastPairAccountKeyDeviceMetadata into test data cache.")
    fun putAccountKeyDeviceMetadata(json: String) {
        Log.i("Puts an array of FastPairAccountKeyDeviceMetadata into test data cache.")
        FastPairTestDataCache.putAccountKeyDeviceMetadata(json)
    }

    /** Dumps all FastPairAccountKeyDeviceMetadata from the test data cache. */
    @Rpc(description = "Dumps all FastPairAccountKeyDeviceMetadata from the test data cache.")
    fun dumpAccountKeyDeviceMetadata(): String {
        Log.i("Dumps all FastPairAccountKeyDeviceMetadata from the test data cache.")
        return FastPairTestDataCache.dumpAccountKeyDeviceMetadata()
    }

    /** Invokes when the snippet runner shutting down. */
    override fun shutdown() {
        super.shutdown()

        Log.i("Resets the Fast Pair test data cache.")
        FastPairTestDataCache.reset()
    }

    companion object {
        private const val FAST_PAIR_MANAGER_ACTION_START_PAIRING = "NEARBY_START_PAIRING"
        private const val FAST_PAIR_MANAGER_EXTRA_MODEL_ID = "MODELID"
        private const val FAST_PAIR_MANAGER_EXTRA_ADDRESS = "ADDRESS"
    }
}