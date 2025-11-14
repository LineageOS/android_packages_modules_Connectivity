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

import android.Manifest.permission.NETWORK_SETTINGS
import android.content.Context
import android.net.EthernetManager
import android.net.EthernetManager.InterfaceStateListener
import android.net.EthernetManager.STATE_ABSENT
import android.net.EthernetManager.STATE_LINK_UP
import android.net.IpConfiguration
import android.net.TestNetworkInterface
import android.os.Handler
import android.util.Log
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.EthernetTestInterface.EthernetStateListener.Event.InterfaceStateChanged
import java.net.NetworkInterface
import kotlin.concurrent.Volatile
import kotlin.test.assertNotNull

private const val TAG = "EthernetTestInterface"
private const val TIMEOUT_MS = 5_000L

/**
 * A class to manage the lifecycle of an ethernet interface.
 *
 * This class encapsulates creating new tun/tap interfaces and registering them with ethernet
 * service.
 */
class EthernetTestInterface(
    private val context: Context,
    private val handler: Handler,
    val testIface: TestNetworkInterface
) {
    private class EthernetStateListener(private val trackedIface: String) : InterfaceStateListener {
        val events = ArrayTrackRecord<Event>().newReadHead()

        sealed class Event {
            data class InterfaceStateChanged(
                val iface: String,
                val state: Int,
                val role: Int,
                val cfg: IpConfiguration?
            ) : Event()
        }

        override fun onInterfaceStateChanged(
            iface: String,
            state: Int,
            role: Int,
            cfg: IpConfiguration?
        ) {
            // filter out callbacks for other interfaces
            if (iface != trackedIface) return
            events.add(InterfaceStateChanged(iface, state, role, cfg))
        }

        fun eventuallyExpect(state: Int) {
            val cb = events.poll(TIMEOUT_MS) { it is InterfaceStateChanged && it.state == state }
            assertNotNull(cb, "Never received state $state. Got: ${events.backtrace()}")
        }
    }

    val name get() = testIface.interfaceName
    val mtu: Int
        get() {
            val nif = NetworkInterface.getByName(name)
            assertNotNull(nif)
            return nif.mtu
        }
    val packetReader = PollPacketReader(handler, testIface.fileDescriptor.fileDescriptor, mtu)
    private val listener = EthernetStateListener(name)
    private val em = context.getSystemService(EthernetManager::class.java)!!
    @Volatile private var cleanedUp = false

    init{
        em.addInterfaceStateListener(handler::post, listener)
        runAsShell(NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(true)
        }
        // Wait for link up to be processed in EthernetManager before returning.
        listener.eventuallyExpect(STATE_LINK_UP)
        handler.post { packetReader.start() }
        handler.waitForIdle(TIMEOUT_MS)
    }

    fun destroy() {
        // packetReader.stop() closes the test interface.
        handler.post { packetReader.stop() }
        handler.waitForIdle(TIMEOUT_MS)
        listener.eventuallyExpect(STATE_ABSENT)

        // setIncludeTestInterfaces() posts on the handler and does not run synchronously. However,
        // there should be no need for a synchronization mechanism here. If the next test is
        // bringing up its interface, a RTM_NEWLINK will be put on the back of the handler and is
        // guaranteed to be in order with (i.e. after) this call, so there is no race here.
        runAsShell(NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(false)
        }
        em.removeInterfaceStateListener(listener)

        cleanedUp = true
    }

    protected fun finalize() {
        if (!cleanedUp) {
            Log.wtf(TAG, "destroy() was not called for interface $name.")
            destroy()
        }
    }
}
