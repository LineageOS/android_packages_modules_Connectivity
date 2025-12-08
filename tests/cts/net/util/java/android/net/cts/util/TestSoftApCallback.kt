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

package android.net.cts.util

import android.net.cts.util.TestSoftApCallback.SoftApEvent
import android.net.cts.util.TestSoftApCallback.SoftApEvent.ConnectedClientsChanged
import android.net.cts.util.TestSoftApCallback.SoftApEvent.InfoChanged
import android.net.cts.util.TestSoftApCallback.SoftApEvent.StateChanged
import android.net.wifi.SoftApInfo
import android.net.wifi.WifiClient
import android.net.wifi.WifiManager.SoftApCallback
import com.android.net.module.util.TestableCallback

private const val DEFAULT_TIMEOUT_MS = 60_000L

class TestSoftApCallback : SoftApCallback, TestableCallback<SoftApEvent>() {

    sealed class SoftApEvent {
        data class StateChanged(val state: Int, val failureReason: Int) : SoftApEvent()
        data class ConnectedClientsChanged(val clients: List<WifiClient>) : SoftApEvent()
        data class InfoChanged(val softApInfoList: List<SoftApInfo>) : SoftApEvent()

        // Convenience constants for expecting a type
        companion object {
            @JvmField
            val STATE_CHANGED = StateChanged::class
            @JvmField
            val CONNECTED_CLIENTS_CHANGED = ConnectedClientsChanged::class
            @JvmField
            val INFO_CHANGED = InfoChanged::class
        }
    }

    override fun onStateChanged(state: Int, failureReason: Int) {
        history.add(SoftApEvent.StateChanged(state, failureReason))
    }

    override fun onConnectedClientsChanged(clients: List<WifiClient>) {
        history.add(SoftApEvent.ConnectedClientsChanged(clients))
    }

    override fun onInfoChanged(softApInfoList: List<SoftApInfo>) {
        history.add(SoftApEvent.InfoChanged(softApInfoList))
    }
}
