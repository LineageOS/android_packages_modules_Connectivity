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

import android.Manifest.permission.ACCESS_NETWORK_STATE
import android.Manifest.permission.NETWORK_SETTINGS
import android.annotation.SuppressLint
import android.content.Context
import android.net.EthernetManager
import android.net.EthernetManager.ETHERNET_STATE_DISABLED
import android.net.EthernetManager.ETHERNET_STATE_ENABLED
import android.os.SystemProperties
import com.android.testutils.TestEthernetStateListener.EthernetStateChanged
import java.util.function.IntConsumer
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A JUnit rule to save and restore ethernet state during tests.
 *
 * This rule ensures that the ethernet state (enabled/disabled) is returned to its original
 * value after each test. It also provides a utility method to change the state and wait for
 * the change to be applied.
 *
 * This rule requires shell permissions to function correctly.
 */
open class RestoreEthernetRule(private val context: Context) : TestRule {
    // Callers should ensure isEthernetSupported() before invoking anything needs
    // the EthernetManager instance. Otherwise, this would throw.
    private val em by lazy { context.getSystemService(EthernetManager::class.java)!! }

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val wasEnabled = isEthernetEnabled()
                try {
                    base.evaluate()
                } finally {
                    setEthernetEnabled(wasEnabled)
                }
            }
        }
    }

    private fun addEthernetStateListener(listener: IntConsumer) {
        runAsShell(ACCESS_NETWORK_STATE) {
            em.addEthernetStateListener({ r -> r.run() }, listener)
        }
    }

    private fun removeEthernetStateListener(listener: IntConsumer) {
        runAsShell(ACCESS_NETWORK_STATE) {
            em.removeEthernetStateListener(listener)
        }
    }

    /**
     * Sets the ethernet enabled state and waits for the change to be confirmed.
     *
     * @param enabled The desired ethernet state.
     */
    @SuppressLint("MissingPermission")
    open fun setEthernetEnabled(enabled: Boolean) {
        if (!enabled) require(!isAdbOverEthernet())

        runAsShell(NETWORK_SETTINGS) { setEthernetEnabled(enabled) }
        val listener = TestEthernetStateListener()
        addEthernetStateListener(listener)
        try {
            listener.eventuallyExpect<EthernetStateChanged> {
                it.state == if (enabled) ETHERNET_STATE_ENABLED else ETHERNET_STATE_DISABLED
            }
        } finally {
            removeEthernetStateListener(listener)
        }
    }

    @SuppressLint("MissingPermission")
    open fun isAdbOverEthernet(): Boolean {
        // If no ethernet interface is available, adb is not connected over ethernet.
        val ifaces = runAsShell(NETWORK_SETTINGS) { em.interfaceList }
        if (ifaces.isEmpty()) return false

        // cuttlefish is special and does not connect adb over ethernet.
        if (SystemProperties.get("ro.product.board", "") == "cutf") return false

        // Check if adb is connected over the network.
        return (SystemProperties.getInt("persist.adb.tcp.port", -1) > -1 ||
                SystemProperties.getInt("service.adb.tcp.port", -1) > -1)
    }

    open fun isEthernetSupported() = context.getSystemService(EthernetManager::class.java) != null

    @SuppressLint("MissingPermission")
    open fun isEthernetEnabled(): Boolean {
        val listener = TestEthernetStateListener()
        addEthernetStateListener(listener)
        try {
            val event = listener.expect<EthernetStateChanged>()
            return event.state == ETHERNET_STATE_ENABLED
        } finally {
            removeEthernetStateListener(listener)
        }
    }
}
