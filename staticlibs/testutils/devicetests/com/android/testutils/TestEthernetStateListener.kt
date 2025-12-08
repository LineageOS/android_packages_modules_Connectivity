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

import android.net.EthernetManager.ETHERNET_STATE_DISABLED
import android.net.EthernetManager.ETHERNET_STATE_ENABLED
import com.android.net.module.util.TestableCallback
import com.android.testutils.TestEthernetStateListener.EthernetStateChanged
import java.util.function.IntConsumer

/**
 * A test listener for ethernet state.
 */
class TestEthernetStateListener : TestableCallback<EthernetStateChanged>(), IntConsumer {
    data class EthernetStateChanged(val state: Int) {
        override fun toString(): String {
            val stateString = when (state) {
                ETHERNET_STATE_ENABLED -> "ETHERNET_STATE_ENABLED"
                ETHERNET_STATE_DISABLED -> "ETHERNET_STATE_DISABLED"
                else -> state.toString()
            }
            return "EthernetStateChanged(state=$stateString)"
        }
    }

    override fun accept(state: Int) {
        history.add(EthernetStateChanged(state))
    }
}
