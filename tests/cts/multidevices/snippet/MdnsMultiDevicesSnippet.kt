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

package com.google.snippet.connectivity

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.NsdDiscoveryRecord
import com.android.testutils.NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStarted
import com.android.testutils.NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStopped
import com.android.testutils.NsdRegistrationRecord
import com.android.testutils.NsdRegistrationRecord.RegistrationEvent.ServiceRegistered
import com.android.testutils.NsdRegistrationRecord.RegistrationEvent.ServiceUnregistered
import com.android.testutils.NsdResolveRecord
import com.android.testutils.NsdResolveRecord.ResolveEvent.ServiceResolved
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import kotlin.test.assertEquals
import org.junit.Assert.assertArrayEquals

private const val SERVICE_NAME = "MultiDevicesTest"
private const val SERVICE_TYPE = "_multi_devices._tcp"
private const val SERVICE_ATTRIBUTES_KEY = "key"
private const val SERVICE_ATTRIBUTES_VALUE = "value"
private const val SERVICE_PORT = 12345
private const val REGISTRATION_TIMEOUT_MS = 10_000L

class MdnsMultiDevicesSnippet : Snippet {
    private val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
    private val nsdManager = context.getSystemService(NsdManager::class.java)!!
    private val registrationRecord = NsdRegistrationRecord()
    private val discoveryRecord = NsdDiscoveryRecord()
    private val resolveRecord = NsdResolveRecord()

    @Rpc(description = "Register a mDns service")
    fun registerMDnsService() {
        val info = NsdServiceInfo()
        info.setServiceName(SERVICE_NAME)
        info.setServiceType(SERVICE_TYPE)
        info.setPort(SERVICE_PORT)
        info.setAttribute(SERVICE_ATTRIBUTES_KEY, SERVICE_ATTRIBUTES_VALUE)
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationRecord)
        registrationRecord.expectCallback<ServiceRegistered>(REGISTRATION_TIMEOUT_MS)
    }

    @Rpc(description = "Unregister a mDns service")
    fun unregisterMDnsService() {
        if (!(registrationRecord.poll(timeoutMs = 0, pos = 0) is ServiceRegistered)) {
            // Ignore unregistration if the service has not registered
            return
        }
        nsdManager.unregisterService(registrationRecord)
        registrationRecord.expectCallback<ServiceUnregistered>()
    }

    @Rpc(description = "Ensure the discovery and resolution of the mDNS service")
    // Suppress the warning, as the NsdManager#resolveService() method is deprecated.
    @Suppress("DEPRECATION")
    fun ensureMDnsServiceDiscoveryAndResolution() {
        // Discover a mDns service that matches the test service
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)
        val info = discoveryRecord.waitForServiceDiscovered(SERVICE_NAME, SERVICE_TYPE)
        // Resolve the retrieved mDns service.
        nsdManager.resolveService(info, resolveRecord)
        val serviceResolved = resolveRecord.expectCallbackEventually<ServiceResolved>()
        serviceResolved.serviceInfo.let {
            assertEquals(SERVICE_NAME, it.serviceName)
            assertEquals(".$SERVICE_TYPE", it.serviceType)
            assertEquals(SERVICE_PORT, it.port)
            assertEquals(1, it.attributes.size)
            assertArrayEquals(
                    SERVICE_ATTRIBUTES_VALUE.encodeToByteArray(),
                    it.attributes[SERVICE_ATTRIBUTES_KEY]
            )
        }
    }

    @Rpc(description = "Stop discovery")
    fun stopMDnsServiceDiscovery() {
        if (!(discoveryRecord.poll(timeoutMs = 0, pos = 0) is DiscoveryStarted)) {
            // Ignore discovery stop if discovery has not started
            return
        }
        nsdManager.stopServiceDiscovery(discoveryRecord)
        discoveryRecord.expectCallbackEventually<DiscoveryStopped>()
    }
}
