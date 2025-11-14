/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package com.android.server.connectivity.mdns

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.android.net.module.util.ArrayTrackRecord
import com.android.net.module.util.CollectionUtils
import com.android.server.connectivity.mdns.MdnsResponse.EXPIRATION_NEVER
import com.android.server.connectivity.mdns.MdnsServiceCache.CacheKey
import com.android.server.connectivity.mdns.MdnsServiceCache.CachedService
import com.android.server.connectivity.mdns.MdnsServiceCache.NEVER_SENT_QUERY
import com.android.server.connectivity.mdns.MdnsServiceCacheTest.ExpiredRecord.ExpiredEvent.ServiceRecordExpired
import com.android.server.connectivity.mdns.util.MdnsUtils
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

private const val SERVICE_NAME_1 = "service-instance-1"
private const val SERVICE_NAME_2 = "service-instance-2"
private const val SERVICE_NAME_3 = "service-instance-3"
private const val SERVICE_TYPE_1 = "_test1._tcp.local"
private const val SERVICE_TYPE_2 = "_test2._tcp.local"
private const val INTERFACE_INDEX = 999
private const val DEFAULT_TIMEOUT_MS = 2000L
private const val NO_CALLBACK_TIMEOUT_MS = 200L
private const val TEST_ELAPSED_REALTIME_MS = 123L
private const val DEFAULT_TTL_TIME_MS = 120000L

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsServiceCacheTest {
    private val socketKey = SocketKey(null /* network */, INTERFACE_INDEX)
    private val cacheKey1 = CacheKey(SERVICE_TYPE_1, socketKey)
    private val cacheKey2 = CacheKey(SERVICE_TYPE_2, socketKey)
    private val thread = HandlerThread(MdnsServiceCacheTest::class.simpleName)
    private val clock = mock(MdnsUtils.Clock::class.java)
    private val handler by lazy {
        Handler(thread.looper)
    }

    private class ExpiredRecord : MdnsServiceCache.ServiceExpiredCallback {
        val history = ArrayTrackRecord<ExpiredEvent>().newReadHead()

        sealed class ExpiredEvent {
            abstract val previousResponse: MdnsResponse
            abstract val newResponse: MdnsResponse?
            data class ServiceRecordExpired(
                    override val previousResponse: MdnsResponse,
                    override val newResponse: MdnsResponse?
            ) : ExpiredEvent()
        }

        override fun onServiceRecordExpired(
                previousResponse: MdnsResponse,
                newResponse: MdnsResponse?
        ) {
            history.add(ServiceRecordExpired(previousResponse, newResponse))
        }

        fun expectedServiceRecordExpired(
                serviceName: String,
                timeoutMs: Long = DEFAULT_TIMEOUT_MS
        ) {
            val event = history.poll(timeoutMs)
            assertNotNull(event)
            assertTrue(event is ServiceRecordExpired)
            assertEquals(serviceName, event.previousResponse.serviceInstanceName)
        }

        fun assertNoCallback() {
            val cb = history.poll(NO_CALLBACK_TIMEOUT_MS)
            assertNull("Expected no callback but got $cb", cb)
        }
    }

    @Before
    fun setUp() {
        thread.start()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    private fun makeFlags(
            isExpiredServicesRemovalEnabled: Boolean = false,
            isOptimizedExpiredServiceRemovalEnabled: Boolean = false
    ) = MdnsFeatureFlags.Builder()
            .setIsExpiredServicesRemovalEnabled(isExpiredServicesRemovalEnabled)
            .setIsOptimizedExpiredServiceRemovalEnabled(isOptimizedExpiredServiceRemovalEnabled)
            .build()

    private fun <T> runningOnHandlerAndReturn(functor: (() -> T)): T {
        val future = CompletableFuture<T>()
        handler.post {
            future.complete(functor())
        }
        return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun addOrUpdateService(
            serviceCache: MdnsServiceCache,
            cacheKey: CacheKey,
            service: MdnsResponse
    ): Unit = runningOnHandlerAndReturn { serviceCache.addOrUpdateService(cacheKey, service) }

    private fun removeService(
            serviceCache: MdnsServiceCache,
            serviceName: String,
            cacheKey: CacheKey
    ): Unit = runningOnHandlerAndReturn { serviceCache.removeService(serviceName, cacheKey) }

    private fun getService(
            serviceCache: MdnsServiceCache,
            serviceName: String,
            cacheKey: CacheKey,
            excludeExpiredService: Boolean = true,
    ): MdnsResponse? = runningOnHandlerAndReturn {
        serviceCache.getCachedService(serviceName, cacheKey, excludeExpiredService)
    }

    private fun getServices(
            serviceCache: MdnsServiceCache,
            cacheKey: CacheKey,
            excludeExpiredService: Boolean = true,
    ): List<MdnsResponse> = runningOnHandlerAndReturn {
        serviceCache.getCachedServices(cacheKey, excludeExpiredService)
    }

    private fun registerServiceExpiredCallback(
            serviceCache: MdnsServiceCache,
            cacheKey: CacheKey,
            callback: MdnsServiceCache.ServiceExpiredCallback
    ) = runningOnHandlerAndReturn {
        serviceCache.registerServiceExpiredCallback(cacheKey, callback)
    }

    private fun removeServices(
            serviceCache: MdnsServiceCache,
            cacheKey: CacheKey
    ): Unit = runningOnHandlerAndReturn { serviceCache.removeServices(cacheKey) }

    private fun getFirstQueryTimeAfterLastUpdate(
            serviceCache: MdnsServiceCache,
            serviceName: String,
            cacheKey: CacheKey
    ): Long = runningOnHandlerAndReturn {
        serviceCache.getFirstQueryTimeAfterLastUpdate(serviceName, cacheKey)
    }

    private fun updateFirstQueryTimeForCachedServices(
            serviceCache: MdnsServiceCache,
            serviceName: String?,
            subtypes: List<String>,
            cacheKey: CacheKey,
            currentTime: Long
    ): Unit = runningOnHandlerAndReturn {
        serviceCache.updateFirstQueryTimeForCachedServices(
                serviceName,
                subtypes,
                cacheKey,
                currentTime
        )
    }

    private fun removeExpiredServicesAndNotifyListeners(
            serviceCache: MdnsServiceCache,
            cacheKey: CacheKey,
            currentTime: Long
    ): Unit = runningOnHandlerAndReturn {
        serviceCache.removeExpiredServicesAndNotifyListeners(cacheKey, currentTime)
    }

    @Test
    fun testAddAndRemoveService() {
        val serviceCache = MdnsServiceCache(thread.looper, makeFlags(), clock)
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        var response = getService(serviceCache, SERVICE_NAME_1, cacheKey1)
        assertNotNull(response)
        assertEquals(SERVICE_NAME_1, response.serviceInstanceName)
        removeService(serviceCache, SERVICE_NAME_1, cacheKey1)
        response = getService(serviceCache, SERVICE_NAME_1, cacheKey1)
        assertNull(response)
    }

    @Test
    fun testGetCachedServices_multipleServiceTypes() {
        val serviceCache = MdnsServiceCache(thread.looper, makeFlags(), clock)
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_2, SERVICE_TYPE_1))
        addOrUpdateService(serviceCache, cacheKey2, createResponse(SERVICE_NAME_2, SERVICE_TYPE_2))

        val responses1 = getServices(serviceCache, cacheKey1)
        assertEquals(2, responses1.size)
        assertTrue(responses1.stream().anyMatch { response ->
            response.serviceInstanceName == SERVICE_NAME_1
        })
        assertTrue(responses1.any { response ->
            response.serviceInstanceName == SERVICE_NAME_2
        })
        val responses2 = getServices(serviceCache, cacheKey2)
        assertEquals(1, responses2.size)
        assertTrue(responses2.any { response ->
            response.serviceInstanceName == SERVICE_NAME_2
        })

        removeService(serviceCache, SERVICE_NAME_2, cacheKey1)
        val responses3 = getServices(serviceCache, cacheKey1)
        assertEquals(1, responses3.size)
        assertTrue(responses3.any { response ->
            response.serviceInstanceName == SERVICE_NAME_1
        })
        val responses4 = getServices(serviceCache, cacheKey2)
        assertEquals(1, responses4.size)
        assertTrue(responses4.any { response ->
            response.serviceInstanceName == SERVICE_NAME_2
        })
    }

    @Test
    fun testServiceExpiredAndSendCallbacks() {
        val serviceCache = MdnsServiceCache(
                thread.looper,
            makeFlags(isExpiredServicesRemovalEnabled = true),
            clock
        )
        // Register service expired callbacks
        val callback1 = ExpiredRecord()
        val callback2 = ExpiredRecord()
        registerServiceExpiredCallback(serviceCache, cacheKey1, callback1)
        registerServiceExpiredCallback(serviceCache, cacheKey2, callback2)

        doReturn(TEST_ELAPSED_REALTIME_MS).`when`(clock).elapsedRealtime()

        // Add multiple services with different ttl time.
        addOrUpdateService(serviceCache, cacheKey1, createResponse(
            SERVICE_NAME_1,
            SERVICE_TYPE_1,
                DEFAULT_TTL_TIME_MS
        ))
        addOrUpdateService(serviceCache, cacheKey1, createResponse(
            SERVICE_NAME_2,
            SERVICE_TYPE_1,
                DEFAULT_TTL_TIME_MS + 20L
        ))
        addOrUpdateService(serviceCache, cacheKey2, createResponse(
            SERVICE_NAME_3,
            SERVICE_TYPE_2,
                DEFAULT_TTL_TIME_MS + 10L
        ))

        // Check the service expiration immediately. Should be no callback.
        assertEquals(2, getServices(serviceCache, cacheKey1).size)
        assertEquals(1, getServices(serviceCache, cacheKey2).size)
        callback1.assertNoCallback()
        callback2.assertNoCallback()

        // Simulate the case where the response is after TTL then check expired services.
        // Expect SERVICE_NAME_1 expired.
        doReturn(TEST_ELAPSED_REALTIME_MS + DEFAULT_TTL_TIME_MS).`when`(clock).elapsedRealtime()
        assertEquals(1, getServices(serviceCache, cacheKey1).size)
        assertEquals(1, getServices(serviceCache, cacheKey2).size)
        callback1.expectedServiceRecordExpired(SERVICE_NAME_1)
        callback2.assertNoCallback()

        // Simulate the case where the response is after TTL then check expired services.
        // Expect SERVICE_NAME_3 expired.
        doReturn(TEST_ELAPSED_REALTIME_MS + DEFAULT_TTL_TIME_MS + 11L)
                .`when`(clock).elapsedRealtime()
        assertEquals(1, getServices(serviceCache, cacheKey1).size)
        assertEquals(0, getServices(serviceCache, cacheKey2).size)
        callback1.assertNoCallback()
        callback2.expectedServiceRecordExpired(SERVICE_NAME_3)
    }

    @Test
    fun testRemoveExpiredServiceWhenGetting() {
        val serviceCache = MdnsServiceCache(
                thread.looper,
            makeFlags(isExpiredServicesRemovalEnabled = true),
            clock
        )

        doReturn(TEST_ELAPSED_REALTIME_MS).`when`(clock).elapsedRealtime()
        addOrUpdateService(
            serviceCache,
            cacheKey1,
                createResponse(SERVICE_NAME_1, SERVICE_TYPE_1, 1L /* ttlTime */)
        )
        doReturn(TEST_ELAPSED_REALTIME_MS + 2L).`when`(clock).elapsedRealtime()
        assertNull(getService(serviceCache, SERVICE_NAME_1, cacheKey1))

        addOrUpdateService(
            serviceCache,
            cacheKey2,
                createResponse(SERVICE_NAME_2, SERVICE_TYPE_2, 3L /* ttlTime */)
        )
        doReturn(TEST_ELAPSED_REALTIME_MS + 4L).`when`(clock).elapsedRealtime()
        assertEquals(0, getServices(serviceCache, cacheKey2).size)
    }

    @Test
    fun testInsertResponseAndSortList() {
        val services = ArrayList<CachedService>()
        val service1 = CachedService(
                createResponse(SERVICE_NAME_1, SERVICE_TYPE_1, 100L /* ttlTime */)
        )
        MdnsServiceCache.insertServiceAndSortList(services, service1, TEST_ELAPSED_REALTIME_MS)
        assertEquals(1, services.size)
        assertEquals(service1, services[0])

        val service2 = CachedService(
                createResponse(SERVICE_NAME_2, SERVICE_TYPE_1, 50L /* ttlTime */)
        )
        MdnsServiceCache.insertServiceAndSortList(services, service2, TEST_ELAPSED_REALTIME_MS)
        assertEquals(2, services.size)
        assertEquals(service2, services[0])
        assertEquals(service1, services[1])

        val service3 = CachedService(
                createResponse(SERVICE_NAME_3, SERVICE_TYPE_1, 75L /* ttlTime */)
        )
        MdnsServiceCache.insertServiceAndSortList(services, service3, TEST_ELAPSED_REALTIME_MS)
        assertEquals(3, services.size)
        assertEquals(service2, services[0])
        assertEquals(service3, services[1])
        assertEquals(service1, services[2])

        val service4 = CachedService(
                createResponse("service-instance-4", SERVICE_TYPE_1, 125L /* ttlTime */)
        )
        MdnsServiceCache.insertServiceAndSortList(services, service4, TEST_ELAPSED_REALTIME_MS)
        assertEquals(4, services.size)
        assertEquals(service2, services[0])
        assertEquals(service3, services[1])
        assertEquals(service1, services[2])
        assertEquals(service4, services[3])
    }

    private fun verifyGetServices(
            serviceCache: MdnsServiceCache,
            cacheKey: CacheKey,
            excludeExpiredService: Boolean,
            vararg serviceInstanceNames: String
    ) {
        val responses = getServices(serviceCache, cacheKey, excludeExpiredService)
        assertEquals(serviceInstanceNames.size, responses.size)
        for (serviceInstanceName: String in serviceInstanceNames) {
            assertTrue(responses.stream().anyMatch { response ->
                response.serviceInstanceName == serviceInstanceName
            })
        }
    }

    @Test
    fun testRemoveServices() {
        val serviceCache = MdnsServiceCache(thread.looper, makeFlags(), clock)
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_2, SERVICE_TYPE_1))
        addOrUpdateService(serviceCache, cacheKey2, createResponse(SERVICE_NAME_1, SERVICE_TYPE_2))
        verifyGetServices(
                serviceCache,
                cacheKey1,
                excludeExpiredService = true,
                SERVICE_NAME_1,
                SERVICE_NAME_2
        )
        verifyGetServices(serviceCache, cacheKey2, excludeExpiredService = true, SERVICE_NAME_1)

        removeServices(serviceCache, cacheKey1)
        verifyGetServices(serviceCache, cacheKey1, excludeExpiredService = true)
        verifyGetServices(serviceCache, cacheKey2, excludeExpiredService = true, SERVICE_NAME_1)

        removeServices(serviceCache, cacheKey2)
        verifyGetServices(serviceCache, cacheKey2, excludeExpiredService = true)
    }

    @Test
    fun testFirstQueryTime_AddService() {
        val serviceCache = MdnsServiceCache(thread.looper, makeFlags(), clock)
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_2, SERVICE_TYPE_1))

        // Verify the first query time
        assertEquals(
                NEVER_SENT_QUERY,
                getFirstQueryTimeAfterLastUpdate(serviceCache, SERVICE_NAME_1, cacheKey1)
        )
        assertEquals(
                NEVER_SENT_QUERY,
                getFirstQueryTimeAfterLastUpdate(serviceCache, SERVICE_NAME_2, cacheKey1)
        )
    }

    @Test
    fun testFirstQueryTime_UpdateServices() {
        val serviceCache = MdnsServiceCache(thread.looper, makeFlags(), clock)
        var currentTime = 12345L
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_2, SERVICE_TYPE_1))

        // Update the first query time for all services and verify the update.
        updateFirstQueryTimeForCachedServices(
                serviceCache,
                serviceName = null,
                subtypes = emptyList(),
                cacheKey1,
                currentTime
        )
        assertEquals(
                currentTime,
                getFirstQueryTimeAfterLastUpdate(serviceCache, SERVICE_NAME_1, cacheKey1)
        )
        assertEquals(
                currentTime,
                getFirstQueryTimeAfterLastUpdate(serviceCache, SERVICE_NAME_2, cacheKey1)
        )
    }

    @Test
    fun testFirstQueryTime_UpdateServices_WithSubtypes() {
        val serviceCache = MdnsServiceCache(thread.looper, makeFlags(), clock)
        var currentTime = 12345L
        val subType = "subtype"
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        addOrUpdateService(
                serviceCache,
                cacheKey1,
                createResponse(SERVICE_NAME_2, SERVICE_TYPE_1, subType = subType)
        )

        // Update the first query time for the service with a subtype and verify the update.
        updateFirstQueryTimeForCachedServices(
                serviceCache,
                serviceName = null,
                subtypes = listOf(subType),
                cacheKey1,
                currentTime
        )

        assertEquals(
                NEVER_SENT_QUERY,
                getFirstQueryTimeAfterLastUpdate(serviceCache, SERVICE_NAME_1, cacheKey1)
        )
        assertEquals(
                currentTime,
                getFirstQueryTimeAfterLastUpdate(serviceCache, SERVICE_NAME_2, cacheKey1)
        )
    }

    @Test
    fun testFirstQueryTime_UpdateSpecificService() {
        val serviceCache = MdnsServiceCache(thread.looper, makeFlags(), clock)
        var currentTime = 12345L
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_2, SERVICE_TYPE_1))

        // Update the first query time for service 1 only and verify the update.
        updateFirstQueryTimeForCachedServices(
                serviceCache,
                SERVICE_NAME_1,
                subtypes = emptyList(),
                cacheKey1,
                currentTime
        )
        assertEquals(
                currentTime,
                getFirstQueryTimeAfterLastUpdate(serviceCache, SERVICE_NAME_1, cacheKey1)
        )
        assertEquals(
                NEVER_SENT_QUERY,
                getFirstQueryTimeAfterLastUpdate(serviceCache, SERVICE_NAME_2, cacheKey1)
        )
    }

    @Test
    fun testRemoveExpiredServiceAfterQuerySent() {
        val serviceCache = MdnsServiceCache(
                thread.looper,
                makeFlags(
                        isExpiredServicesRemovalEnabled = true,
                        isOptimizedExpiredServiceRemovalEnabled = true
                ),
                clock
        )
        var currentTime = TEST_ELAPSED_REALTIME_MS

        // Add a service, then advance the time to expire the service.
        doReturn(currentTime).`when`(clock).elapsedRealtime()
        addOrUpdateService(serviceCache, cacheKey1, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        currentTime += DEFAULT_TTL_TIME_MS
        doReturn(currentTime).`when`(clock).elapsedRealtime()
        // No service because the service has been marked as expired
        assertNull(getService(serviceCache, SERVICE_NAME_1, cacheKey1))
        // Can still get the service if expired services are included.
        assertNotNull(getService(
                serviceCache,
                SERVICE_NAME_1,
                cacheKey1,
                excludeExpiredService = false
        ))

        // Attempt to remove the expired service, but it should not be removed due to no query being
        // sent.
        removeExpiredServicesAndNotifyListeners(serviceCache, cacheKey1, currentTime)
        assertNotNull(getService(
                serviceCache,
                SERVICE_NAME_1,
                cacheKey1,
                excludeExpiredService = false
        ))

        // Update the first query time and attempt to remove the expired service again after 2 sec
        // later. It should now be removed.
        updateFirstQueryTimeForCachedServices(
                serviceCache,
                SERVICE_NAME_1,
                subtypes = emptyList(),
                cacheKey1,
                currentTime
        )
        currentTime += MdnsServiceTypeClient.REMOVE_SERVICE_AFTER_QUERY_SENT_TIME
        doReturn(currentTime).`when`(clock).elapsedRealtime()
        removeExpiredServicesAndNotifyListeners(serviceCache, cacheKey1, currentTime)
        assertNull(getService(
                serviceCache,
                SERVICE_NAME_1,
                cacheKey1,
                excludeExpiredService = false
        ))
    }

    @Test
    fun testGetNextExpirationTime() {
        val serviceCache = MdnsServiceCache(
                thread.looper,
                makeFlags(
                        isExpiredServicesRemovalEnabled = true,
                        isOptimizedExpiredServiceRemovalEnabled = true
                ),
                clock
        )
        var currentTime = TEST_ELAPSED_REALTIME_MS
        assertEquals(EXPIRATION_NEVER, serviceCache.currentExpiredTime)

        doReturn(currentTime).`when`(clock).elapsedRealtime()
        addOrUpdateService(
                serviceCache,
                cacheKey1,
                createResponse(SERVICE_NAME_1, SERVICE_TYPE_1, ttlTime = 30000L)
        )
        addOrUpdateService(
                serviceCache,
                cacheKey1,
                createResponse(SERVICE_NAME_2, SERVICE_TYPE_1, ttlTime = 60000L)
        )
        assertEquals(TEST_ELAPSED_REALTIME_MS + 30000L, serviceCache.currentExpiredTime)

        currentTime += 45000L
        doReturn(currentTime).`when`(clock).elapsedRealtime()
        removeExpiredServicesAndNotifyListeners(serviceCache, cacheKey1, currentTime)
        assertEquals(TEST_ELAPSED_REALTIME_MS + 60000L, serviceCache.currentExpiredTime)

        currentTime += 100000L
        doReturn(currentTime).`when`(clock).elapsedRealtime()
        removeExpiredServicesAndNotifyListeners(serviceCache, cacheKey1, currentTime)
        assertEquals(EXPIRATION_NEVER, serviceCache.currentExpiredTime)
    }

    private fun createResponse(
            serviceInstanceName: String,
            serviceType: String,
            ttlTime: Long = DEFAULT_TTL_TIME_MS,
            subType: String? = null
    ): MdnsResponse {
        val serviceTypeArray = if (subType != null) {
            MdnsUtils.constructFullSubtype(
                    serviceType.split(".").toTypedArray(),
                    "_$subType"
            )
        } else {
            serviceType.split(".").toTypedArray()
        }
        val serviceNameArray = CollectionUtils.prependArray(
                String::class.java,
                serviceTypeArray,
                serviceInstanceName
        )
        val response = MdnsResponse(
                0 /* now */,
                serviceNameArray,
                socketKey.interfaceIndex,
                socketKey.network
        )

        // Set PTR record
        val pointerRecord = MdnsPointerRecord(
                serviceTypeArray,
                TEST_ELAPSED_REALTIME_MS /* receiptTimeMillis */,
                false /* cacheFlush */,
                ttlTime /* ttlMillis */,
                serviceNameArray
        )
        response.addPointerRecord(pointerRecord)

        // Set SRV record.
        val serviceRecord = MdnsServiceRecord(
                serviceNameArray,
                TEST_ELAPSED_REALTIME_MS /* receiptTimeMillis */,
                false /* cacheFlush */,
                ttlTime /* ttlMillis */,
                0 /* servicePriority */,
                0 /* serviceWeight */,
                12345 /* port */,
                arrayOf("hostname")
        )
        response.serviceRecord = serviceRecord
        return response
    }
}
