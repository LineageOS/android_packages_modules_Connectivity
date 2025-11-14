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

package com.android.server.connectivityservice

import android.net.ConnectivityManager
import android.net.ConnectivityManager.CALLBACK_AVAILABLE
import android.net.ConnectivityManager.CALLBACK_BLK_CHANGED
import android.net.ConnectivityManager.CALLBACK_CAP_CHANGED
import android.net.ConnectivityManager.CALLBACK_IP_CHANGED
import android.net.ConnectivityManager.CALLBACK_LOCAL_NETWORK_INFO_CHANGED
import android.net.ConnectivityManager.CALLBACK_LOST
import android.net.ConnectivityManager.NetworkCallback.DECLARED_METHODS_ALL
import android.net.LinkAddress
import android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
import android.net.NetworkRequest
import android.os.Build
import com.android.net.module.util.BitUtils.packBits
import com.android.server.CSTest
import com.android.server.ConnectivityService
import com.android.server.defaultLp
import com.android.server.defaultNc
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event
import com.android.testutils.tryTest
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy

@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
class CSDeclaredMethodsForCallbacksTest : CSTest() {
    private val mockedCallbackFlags = AtomicInteger(DECLARED_METHODS_ALL)
    private lateinit var wrappedService: ConnectivityService

    private val instrumentedCm by lazy { ConnectivityManager(context, wrappedService) }

    @Before
    fun setUpWrappedService() {
        // Mock the callback flags set by ConnectivityManager when calling ConnectivityService, to
        // simulate methods not being overridden
        wrappedService = spy(service)
        doAnswer { inv ->
            service.requestNetwork(
                inv.getArgument(0),
                inv.getArgument(1),
                inv.getArgument(2),
                inv.getArgument(3),
                inv.getArgument(4),
                inv.getArgument(5),
                inv.getArgument(6),
                inv.getArgument(7),
                inv.getArgument(8),
                inv.getArgument(9),
                mockedCallbackFlags.get())
        }.`when`(wrappedService).requestNetwork(
            anyInt(),
            any(),
            anyInt(),
            any(),
            anyInt(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            anyInt()
        )
        doAnswer { inv ->
            service.listenForNetwork(
                inv.getArgument(0),
                inv.getArgument(1),
                inv.getArgument(2),
                inv.getArgument(3),
                inv.getArgument(4),
                inv.getArgument(5),
                mockedCallbackFlags.get()
            )
        }.`when`(wrappedService)
            .listenForNetwork(any(), any(), any(), anyInt(), any(), any(), anyInt())
    }

    @Test
    fun testCallbacksAreFiltered() {
        val requestCb = TestableNetworkCallback()
        val listenCb = TestableNetworkCallback()
        mockedCallbackFlags.withFlags(CALLBACK_IP_CHANGED, CALLBACK_LOST) {
            instrumentedCm.requestNetwork(NetworkRequest.Builder().build(), requestCb)
        }
        mockedCallbackFlags.withFlags(CALLBACK_CAP_CHANGED) {
            instrumentedCm.registerNetworkCallback(NetworkRequest.Builder().build(), listenCb)
        }

        with(Agent()) {
            connect()
            sendLinkProperties(defaultLp().apply {
                addLinkAddress(LinkAddress("fe80:db8::123/64"))
            })
            sendNetworkCapabilities(defaultNc().apply {
                addCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            })
            disconnect()
        }
        waitForIdle()

        // Only callbacks for the corresponding flags are called
        requestCb.expect<Event.LinkPropertiesChanged>()
        requestCb.expect<Event.Lost>()
        requestCb.assertNoCallback(timeoutMs = 0L)

        listenCb.expect<Event.CapabilitiesChanged>()
        listenCb.assertNoCallback(timeoutMs = 0L)
    }

    @Test
    fun testDeclaredMethodsFlagsToString() {
        assertEquals("NONE", ConnectivityService.declaredMethodsFlagsToString(0))
        assertEquals("ALL", ConnectivityService.declaredMethodsFlagsToString(0.inv()))
        assertEquals("AVAIL|NC|LP|BLK|LOCALINF", ConnectivityService.declaredMethodsFlagsToString(
            (1 shl CALLBACK_AVAILABLE) or
            (1 shl CALLBACK_CAP_CHANGED) or
            (1 shl CALLBACK_IP_CHANGED) or
            (1 shl CALLBACK_BLK_CHANGED) or
            (1 shl CALLBACK_LOCAL_NETWORK_INFO_CHANGED)
        ))

        // EXPIRE_LEGACY_REQUEST (=8) is only used in ConnectivityManager and not included.
        // CALLBACK_TRANSITIVE_CALLS_ONLY (=0) is not a callback so not included either.
        assertEquals(
            "PRECHK|AVAIL|LOSING|LOST|UNAVAIL|NC|LP|SUSP|RESUME|BLK|LOCALINF|RES|0x7fffc101",
            ConnectivityService.declaredMethodsFlagsToString(0x7fff_ffff)
        )
        // The toString method and the assertion above need to be updated if constants are added
        val constants = ConnectivityManager::class.java.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                    it.name.startsWith("CALLBACK_")
        }
        assertEquals(13, constants.size)
    }
}

private fun AtomicInteger.withFlags(vararg flags: Int, action: () -> Unit) {
    tryTest {
        set(packBits(flags).toInt())
        action()
    } cleanup {
        set(DECLARED_METHODS_ALL)
    }
}
