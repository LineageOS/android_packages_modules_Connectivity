/*
 * Copyright (C) 2012 The Android Open Source Project
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
package android.net.cts

import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStarted
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStopped
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.ServiceFound
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.ServiceLost
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.StartDiscoveryFailed
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.StopDiscoveryFailed
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.RegistrationFailed
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.ServiceRegistered
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.ServiceUnregistered
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.UnregistrationFailed
import android.net.cts.NsdManagerTest.NsdResolveRecord.ResolveEvent.ResolveFailed
import android.net.cts.NsdManagerTest.NsdResolveRecord.ResolveEvent.ServiceResolved
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdManager.RegistrationListener
import android.net.nsd.NsdManager.ResolveListener
import android.net.nsd.NsdServiceInfo
import android.platform.test.annotations.AppModeFull
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.net.module.util.ArrayTrackRecord
import com.android.net.module.util.TrackRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val TAG = "NsdManagerTest"
private const val SERVICE_TYPE = "_nmt._tcp"
private const val TIMEOUT_MS = 2000L
private const val DBG = false

@AppModeFull(reason = "Socket cannot bind in instant app mode")
@RunWith(AndroidJUnit4::class)
class NsdManagerTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val nsdManager by lazy { context.getSystemService(NsdManager::class.java) }
    private val serviceName = "NsdTest%04d".format(Random().nextInt(1000))

    private interface NsdEvent
    private open class NsdRecord<T : NsdEvent> private constructor(
        private val history: ArrayTrackRecord<T>
    ) : TrackRecord<T> by history {
        constructor() : this(ArrayTrackRecord())

        val nextEvents = history.newReadHead()

        inline fun <reified V : NsdEvent> expectCallbackEventually(
            crossinline predicate: (V) -> Boolean = { true }
        ): V = nextEvents.poll(TIMEOUT_MS) { e -> e is V && predicate(e) } as V?
                ?: fail("Callback for ${V::class.java.simpleName} not seen after $TIMEOUT_MS ms")

        inline fun <reified V : NsdEvent> expectCallback(): V {
            val nextEvent = nextEvents.poll(TIMEOUT_MS)
            assertNotNull(nextEvent, "No callback received after $TIMEOUT_MS ms")
            assertTrue(nextEvent is V, "Expected ${V::class.java.simpleName} but got " +
                    nextEvent.javaClass.simpleName)
            return nextEvent
        }
    }

    private class NsdRegistrationRecord : RegistrationListener,
            NsdRecord<NsdRegistrationRecord.RegistrationEvent>() {
        sealed class RegistrationEvent : NsdEvent {
            abstract val serviceInfo: NsdServiceInfo

            data class RegistrationFailed(
                override val serviceInfo: NsdServiceInfo,
                val errorCode: Int
            ) : RegistrationEvent()

            data class UnregistrationFailed(
                override val serviceInfo: NsdServiceInfo,
                val errorCode: Int
            ) : RegistrationEvent()

            data class ServiceRegistered(override val serviceInfo: NsdServiceInfo)
                : RegistrationEvent()
            data class ServiceUnregistered(override val serviceInfo: NsdServiceInfo)
                : RegistrationEvent()
        }

        override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) {
            add(RegistrationFailed(si, err))
        }

        override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) {
            add(UnregistrationFailed(si, err))
        }

        override fun onServiceRegistered(si: NsdServiceInfo) {
            add(ServiceRegistered(si))
        }

        override fun onServiceUnregistered(si: NsdServiceInfo) {
            add(ServiceUnregistered(si))
        }
    }

    private class NsdDiscoveryRecord : DiscoveryListener,
            NsdRecord<NsdDiscoveryRecord.DiscoveryEvent>() {
        sealed class DiscoveryEvent : NsdEvent {
            data class StartDiscoveryFailed(val serviceType: String, val errorCode: Int)
                : DiscoveryEvent()

            data class StopDiscoveryFailed(val serviceType: String, val errorCode: Int)
                : DiscoveryEvent()

            data class DiscoveryStarted(val serviceType: String) : DiscoveryEvent()
            data class DiscoveryStopped(val serviceType: String) : DiscoveryEvent()
            data class ServiceFound(val serviceInfo: NsdServiceInfo) : DiscoveryEvent()
            data class ServiceLost(val serviceInfo: NsdServiceInfo) : DiscoveryEvent()
        }

        override fun onStartDiscoveryFailed(serviceType: String, err: Int) {
            add(StartDiscoveryFailed(serviceType, err))
        }

        override fun onStopDiscoveryFailed(serviceType: String, err: Int) {
            add(StopDiscoveryFailed(serviceType, err))
        }

        override fun onDiscoveryStarted(serviceType: String) {
            add(DiscoveryStarted(serviceType))
        }

        override fun onDiscoveryStopped(serviceType: String) {
            add(DiscoveryStopped(serviceType))
        }

        override fun onServiceFound(si: NsdServiceInfo) {
            add(ServiceFound(si))
        }

        override fun onServiceLost(si: NsdServiceInfo) {
            add(ServiceLost(si))
        }

        fun waitForServiceDiscovered(serviceName: String): NsdServiceInfo {
            return expectCallbackEventually<ServiceFound> {
                it.serviceInfo.serviceName == serviceName
            }.serviceInfo
        }
    }

    private class NsdResolveRecord : ResolveListener,
            NsdRecord<NsdResolveRecord.ResolveEvent>() {
        sealed class ResolveEvent : NsdEvent {
            data class ResolveFailed(val serviceInfo: NsdServiceInfo, val errorCode: Int)
                : ResolveEvent()

            data class ServiceResolved(val serviceInfo: NsdServiceInfo) : ResolveEvent()
        }

        override fun onResolveFailed(si: NsdServiceInfo, err: Int) {
            add(ResolveFailed(si, err))
        }

        override fun onServiceResolved(si: NsdServiceInfo) {
            add(ServiceResolved(si))
        }
    }

    @Test
    fun testNsdManager() {
        val si = NsdServiceInfo()
        si.serviceType = SERVICE_TYPE
        si.serviceName = serviceName
        // Test binary data with various bytes
        val testByteArray = byteArrayOf(-128, 127, 2, 1, 0, 1, 2)
        // Test string data with 256 characters (25 blocks of 10 characters + 6)
        val string256 = "1_________2_________3_________4_________5_________6_________" +
                "7_________8_________9_________10________11________12________13________" +
                "14________15________16________17________18________19________20________" +
                "21________22________23________24________25________123456"

        // Illegal attributes
        listOf(
                Triple(null, null, "null key"),
                Triple("", null, "empty key"),
                Triple(string256, null, "key with 256 characters"),
                Triple("key", string256.substring(3),
                        "key+value combination with more than 255 characters"),
                Triple("key", string256.substring(4), "key+value combination with 255 characters"),
                Triple("\u0019", null, "key with invalid character"),
                Triple("=", null, "key with invalid character"),
                Triple("\u007f", null, "key with invalid character")
        ).forEach {
            assertFailsWith<IllegalArgumentException>(
                    "Setting invalid ${it.third} unexpectedly succeeded") {
                si.setAttribute(it.first, it.second)
            }
        }

        // Allowed attributes
        si.setAttribute("booleanAttr", null as String?)
        si.setAttribute("keyValueAttr", "value")
        si.setAttribute("keyEqualsAttr", "=")
        si.setAttribute(" whiteSpaceKeyValueAttr ", " value ")
        si.setAttribute("binaryDataAttr", testByteArray)
        si.setAttribute("nullBinaryDataAttr", null as ByteArray?)
        si.setAttribute("emptyBinaryDataAttr", byteArrayOf())
        si.setAttribute("longkey", string256.substring(9))
        val socket = ServerSocket(0)
        val localPort = socket.localPort
        si.port = localPort
        if (DBG) Log.d(TAG, "Port = $localPort")

        val registrationRecord = NsdRegistrationRecord()
        val registeredInfo = registerService(registrationRecord, si)

        val discoveryRecord = NsdDiscoveryRecord()
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)

        // Expect discovery started
        discoveryRecord.expectCallback<DiscoveryStarted>()

        // Expect a service record to be discovered
        val foundInfo = discoveryRecord.waitForServiceDiscovered(registeredInfo.serviceName)

        val resolvedService = resolveService(foundInfo)

        // Check Txt attributes
        assertEquals(8, resolvedService.attributes.size)
        assertTrue(resolvedService.attributes.containsKey("booleanAttr"))
        assertNull(resolvedService.attributes["booleanAttr"])
        assertEquals("value", resolvedService.attributes["keyValueAttr"].utf8ToString())
        assertEquals("=", resolvedService.attributes["keyEqualsAttr"].utf8ToString())
        assertEquals(" value ",
                resolvedService.attributes[" whiteSpaceKeyValueAttr "].utf8ToString())
        assertEquals(string256.substring(9), resolvedService.attributes["longkey"].utf8ToString())
        assertArrayEquals(testByteArray, resolvedService.attributes["binaryDataAttr"])
        assertTrue(resolvedService.attributes.containsKey("nullBinaryDataAttr"))
        assertNull(resolvedService.attributes["nullBinaryDataAttr"])
        assertTrue(resolvedService.attributes.containsKey("emptyBinaryDataAttr"))
        assertNull(resolvedService.attributes["emptyBinaryDataAttr"])
        assertEquals(localPort, resolvedService.port)

        // Unregister the service
        nsdManager.unregisterService(registrationRecord)
        registrationRecord.expectCallback<ServiceUnregistered>()

        // Expect a callback for service lost
        discoveryRecord.expectCallbackEventually<ServiceLost> {
            it.serviceInfo.serviceName == serviceName
        }

        // Register service again to see if NsdManager can discover it
        val si2 = NsdServiceInfo()
        si2.serviceType = SERVICE_TYPE
        si2.serviceName = serviceName
        si2.port = localPort
        val registrationRecord2 = NsdRegistrationRecord()
        val registeredInfo2 = registerService(registrationRecord2, si2)

        // Expect a service record to be discovered (and filter the ones
        // that are unrelated to this test)
        val foundInfo2 = discoveryRecord.waitForServiceDiscovered(registeredInfo2.serviceName)

        // Resolve the service
        val resolvedService2 = resolveService(foundInfo2)

        // Check that the resolved service doesn't have any TXT records
        assertEquals(0, resolvedService2.attributes.size)

        nsdManager.stopServiceDiscovery(discoveryRecord)

        discoveryRecord.expectCallbackEventually<DiscoveryStopped>()

        nsdManager.unregisterService(registrationRecord2)
        registrationRecord2.expectCallback<ServiceUnregistered>()
    }

    /**
     * Register a service and return its registration record.
     */
    private fun registerService(record: NsdRegistrationRecord, si: NsdServiceInfo): NsdServiceInfo {
        nsdManager.registerService(si, NsdManager.PROTOCOL_DNS_SD, record)
        // We may not always get the name that we tried to register;
        // This events tells us the name that was registered.
        val cb = record.expectCallback<ServiceRegistered>()
        return cb.serviceInfo
    }

    private fun resolveService(discoveredInfo: NsdServiceInfo): NsdServiceInfo {
        val record = NsdResolveRecord()
        nsdManager.resolveService(discoveredInfo, record)
        val resolvedCb = record.expectCallback<ServiceResolved>()
        assertEquals(discoveredInfo.serviceName, resolvedCb.serviceInfo.serviceName)

        return resolvedCb.serviceInfo
    }
}

private fun ByteArray?.utf8ToString(): String {
    if (this == null) return ""
    return String(this, StandardCharsets.UTF_8)
}
