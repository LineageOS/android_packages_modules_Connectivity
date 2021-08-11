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

import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdManager.ResolveListener
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.platform.test.annotations.AppModeFull
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
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
import kotlin.test.fail

private const val TAG = "NsdManagerTest"
private const val SERVICE_TYPE = "_nmt._tcp"
private const val TIMEOUT = 2000
private const val DBG = false

@AppModeFull(reason = "Socket cannot bind in instant app mode")
@RunWith(AndroidJUnit4::class)
class NsdManagerTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val nsdManager by lazy { context.getSystemService(NsdManager::class.java) }
    private val serviceName = "NsdTest%04d".format(Random().nextInt(1000))

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            setEvent("onRegistrationFailed", errorCode)
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            setEvent("onUnregistrationFailed", errorCode)
        }

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            setEvent("onServiceRegistered", serviceInfo)
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            setEvent("onServiceUnregistered", serviceInfo)
        }
    }

    private val discoveryListener = object : DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            setEvent("onStartDiscoveryFailed", errorCode)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            setEvent("onStopDiscoveryFailed", errorCode)
        }

        override fun onDiscoveryStarted(serviceType: String) {
            val info = NsdServiceInfo()
            info.serviceType = serviceType
            setEvent("onDiscoveryStarted", info)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            val info = NsdServiceInfo()
            info.serviceType = serviceType
            setEvent("onDiscoveryStopped", info)
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            setEvent("onServiceFound", serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            setEvent("onServiceLost", serviceInfo)
        }
    }

    private inner class TestResolveListener : ResolveListener {
        var resolvedService: NsdServiceInfo? = null
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            setEvent("onResolveFailed", errorCode)
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            resolvedService = serviceInfo
            setEvent("onServiceResolved", serviceInfo)
        }
    }

    private class EventData {
        constructor(callbackName: String, info: NsdServiceInfo?) {
            this.callbackName = callbackName
            succeeded = true
            errorCode = 0
            this.info = info
        }

        constructor(callbackName: String, errorCode: Int) {
            this.callbackName = callbackName
            succeeded = false
            this.errorCode = errorCode
            info = null
        }

        val callbackName: String
        val succeeded: Boolean
        private val errorCode: Int
        val info: NsdServiceInfo?
    }

    private val eventCache = ArrayList<EventData>()
    private fun setEvent(callbackName: String, errorCode: Int) {
        if (DBG) Log.d(TAG, "$callbackName failed with $errorCode")
        val eventData = EventData(callbackName, errorCode)
        synchronized(eventCache) {
            eventCache.add(eventData)
            eventCache.notify()
        }
    }

    private fun setEvent(callbackName: String, info: NsdServiceInfo) {
        if (DBG) Log.d(TAG, "Received event " + callbackName + " for " + info.serviceName)
        val eventData = EventData(callbackName, info)
        synchronized(eventCache) {
            eventCache.add(eventData)
            eventCache.notify()
        }
    }

    fun clearEventCache() {
        synchronized(eventCache) { eventCache.clear() }
    }

    fun eventCacheSize(): Int {
        synchronized(eventCache) { return eventCache.size }
    }

    private var waitId = 0
    private fun waitForCallback(callbackName: String): EventData? {
        synchronized(eventCache) {
            waitId++
            if (DBG) Log.d(TAG, "Waiting for $callbackName, id=$waitId")
            val startTime = SystemClock.uptimeMillis()
            var elapsedTime = 0L
            while (elapsedTime < TIMEOUT) {
                // first check if we've received that event
                eventCache.find { it.callbackName == callbackName }?.let {
                    if (DBG) Log.d(TAG, "exiting wait id=$waitId")
                    return it
                }

                // Not yet received, just wait
                try {
                    eventCache.wait(TIMEOUT - elapsedTime)
                } catch (e: InterruptedException) {
                    return null
                }
                elapsedTime = SystemClock.uptimeMillis() - startTime
            }
            // we exited the loop because of TIMEOUT; fail the call
            if (DBG) Log.d(TAG, "timed out waiting id=$waitId")
            return null
        }
    }

    private fun waitForNewEvents(): EventData? {
        if (DBG) Log.d(TAG, "Waiting for a bit, id=$waitId")
        val startTime = SystemClock.uptimeMillis()
        var elapsedTime = 0L
        synchronized(eventCache) {
            val index = eventCache.size
            while (elapsedTime < TIMEOUT) {
                // first check if we've received that event
                if (index < eventCache.size) {
                    return eventCache[index]
                }

                // Not yet received, just wait
                eventCache.wait(TIMEOUT - elapsedTime)
                elapsedTime = SystemClock.uptimeMillis() - startTime
            }
        }
        return null
    }

    @Test
    fun testNsdManager() {
        val si = NsdServiceInfo()
        si.serviceType = SERVICE_TYPE
        si.serviceName = serviceName
        val testByteArray = byteArrayOf(-128, 127, 2, 1, 0, 1, 2)
        val string256 = "1_________2_________3_________4_________5_________6_________" +
                "7_________8_________9_________10________11________12________13________" +
                "14________15________16________17________18________19________20________" +
                "21________22________23________24________25________123456"

        // Illegal attributes
        assertFailsWith<IllegalArgumentException>("Could set null key") {
            si.setAttribute(null, null as String?)
        }
        assertFailsWith<IllegalArgumentException>("Could set empty key") {
            si.setAttribute("", null as String?)
        }
        assertFailsWith<IllegalArgumentException>("Could set key with 255 characters") {
            si.setAttribute(string256, null as String?)
        }
        assertFailsWith<IllegalArgumentException>(
                "Could set key+value combination with more than 255 characters") {
            si.setAttribute("key", string256.substring(3))
        }
        assertFailsWith<IllegalArgumentException>(
                "Could set key+value combination with 255 characters") {
            si.setAttribute("key", string256.substring(4))
        }
        assertFailsWith<IllegalArgumentException>("Could set key with invalid character") {
            si.setAttribute("\u0019", null as String?)
        }
        assertFailsWith<IllegalArgumentException>("Could set key with invalid character") {
            si.setAttribute("=", null as String?)
        }
        assertFailsWith<IllegalArgumentException>("Could set key with invalid character") {
            si.setAttribute("\u007f", null as String?)
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
        clearEventCache()

        val registeredName = registerService(si)

        assertEquals(1, eventCacheSize())
        clearEventCache()
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        // Expect discovery started
        var lastEvent = waitForCallback("onDiscoveryStarted")
        assertNotNull(lastEvent)
        assertTrue(lastEvent.succeeded)

        // Remove this event, so accounting becomes easier later
        synchronized(eventCache) { eventCache.remove(lastEvent) }

        // Expect a service record to be discovered
        val foundInfo = waitForServiceDiscovered(registeredName)

        // We've removed all serviceFound events, and we've removed the discoveryStarted
        // event as well, so now the event cache should be empty!
        assertEquals(0, eventCacheSize())

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
        if (DBG) Log.d(TAG, "id = $waitId: Port = ${lastEvent.info?.port}")
        assertEquals(localPort, resolvedService.port)
        assertEquals(1, eventCacheSize())
        checkForAdditionalEvents()
        clearEventCache()

        // Unregister the service
        nsdManager.unregisterService(registrationListener)
        lastEvent = waitForCallback("onServiceUnregistered")
        assertNotNull(lastEvent)
        assertTrue(lastEvent.succeeded)

        // Expect a callback for service lost
        lastEvent = waitForCallback("onServiceLost")
        assertNotNull(lastEvent)
        assertEquals(registeredName, lastEvent.info?.serviceName)

        // Register service again to see if we discover it
        checkForAdditionalEvents()
        clearEventCache()
        val si2 = NsdServiceInfo()
        si2.serviceType = SERVICE_TYPE
        si2.serviceName = serviceName
        si2.port = localPort
        val registeredName2 = registerService(si2)

        // Expect a record to be discovered
        // Expect a service record to be discovered (and filter the ones
        // that are unrelated to this test)
        val foundInfo2 = waitForServiceDiscovered(registeredName2)

        // Resolve the service
        clearEventCache()
        val resolvedService2 = resolveService(foundInfo2)

        // Check that we don't have any TXT records
        assertEquals(0, resolvedService2.attributes.size)
        checkForAdditionalEvents()
        clearEventCache()
        nsdManager.stopServiceDiscovery(discoveryListener)
        lastEvent = waitForCallback("onDiscoveryStopped")
        assertNotNull(lastEvent)
        assertTrue(lastEvent.succeeded)
        checkCacheSize(1)
        checkForAdditionalEvents()
        clearEventCache()
        nsdManager.unregisterService(registrationListener)
        lastEvent = waitForCallback("onServiceUnregistered")
        assertNotNull(lastEvent)
        assertTrue(lastEvent.succeeded)
        checkCacheSize(1)
    }

    /**
     * Register a service and return its registered name.
     */
    private fun registerService(si: NsdServiceInfo): String {
        nsdManager.registerService(si, NsdManager.PROTOCOL_DNS_SD, registrationListener)

        // We may not always get the name that we tried to register;
        // This events tells us the name that was registered.
        val cb = waitForCallback("onServiceRegistered")
        assertNotNull(cb)
        assertTrue(cb.succeeded)
        return cb.info?.serviceName ?: fail("Missing event info")
    }

    private fun waitForServiceDiscovered(serviceName: String): NsdServiceInfo {
        var foundInfo: NsdServiceInfo? = null
        repeat(32) {
            val event = waitForCallback("onServiceFound") ?: return@repeat
            assertTrue(event.succeeded)
            if (DBG) Log.d(TAG, "id = $waitId: ServiceName = ${event.info?.serviceName}")
            if (event.info?.serviceName == serviceName) {
                // Save it, as it will get overwritten with new serviceFound events
                foundInfo = event.info
            }

            // Remove this event from the event cache, so it won't be found by subsequent
            // calls to waitForCallback
            synchronized(eventCache) { eventCache.remove(event) }
        }
        return foundInfo ?: fail("Service not discovered")
    }

    private fun resolveService(discoveredInfo: NsdServiceInfo): NsdServiceInfo {
        val resolveListener = TestResolveListener()
        nsdManager.resolveService(discoveredInfo, resolveListener)
        val resolvedCb = waitForCallback("onServiceResolved")
        assertNotNull(resolvedCb)
        assertTrue(resolvedCb.succeeded)
        if (DBG) Log.d(TAG, "id = $waitId: ServiceName = ${resolvedCb.info?.serviceName}")
        assertEquals(discoveredInfo.serviceName, resolvedCb.info?.serviceName)

        return resolveListener.resolvedService ?: fail("Missing resolved service")
    }

    fun checkCacheSize(size: Int) {
        synchronized(eventCache) {
            if (size != eventCache.size) {
                fail("Expected size $size, found event list [${
                    eventCache.joinToString(", ") {
                        "eventName: ${it.callbackName}, serviceName ${it.info?.serviceName}"
                    }
                }]")
            }
        }
    }

    fun checkForAdditionalEvents(): Boolean {
        val e = waitForNewEvents() ?: return true
        Log.d(TAG, "ignoring unexpected event ${e.callbackName} (${e.info?.serviceName})")
        return false
    }
}

private fun ByteArray?.utf8ToString(): String {
    if (this == null) return ""
    return String(this, StandardCharsets.UTF_8)
}

// TODO: migrate legacy java-style implementation to newer utils like RecorderCallback
private fun Any.wait(timeout: Long) = (this as Object).wait(timeout)
private fun Any.notify() = (this as Object).notify()