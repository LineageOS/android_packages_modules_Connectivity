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

package com.android.metrics

import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY
import android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_SATELLITE
import android.net.UidRange
import android.os.Build
import android.stats.connectivity.MeteredState.METERED_NO
import android.stats.connectivity.MeteredState.METERED_TEMPORARILY_UNMETERED
import android.stats.connectivity.MeteredState.METERED_YES
import android.stats.connectivity.ValidatedState.VS_INVALID
import android.stats.connectivity.ValidatedState.VS_PARTIAL
import android.stats.connectivity.ValidatedState.VS_PORTAL
import android.stats.connectivity.ValidatedState.VS_VALID
import com.android.metrics.DefaultNetworkRematchMetrics.Dependencies
import com.android.server.ConnectivityService
import com.android.server.ConnectivityService.PREFERENCE_ORDER_NONE
import com.android.server.ConnectivityService.PREFERENCE_ORDER_SATELLITE_FALLBACK
import com.android.server.connectivity.FullScore
import com.android.server.connectivity.NetworkAgentInfo
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class DefaultNetworkRematchMetricsTest {

    private val deps = mock(Dependencies::class.java)

    private val nc = mock(NetworkCapabilities::class.java).also {
        doReturn(1L shl TRANSPORT_SATELLITE).`when`(it).transportTypesInternal
        doReturn(0L).`when`(it).capabilitiesInternal
        doReturn(true).`when`(it).hasTransport(TRANSPORT_SATELLITE)
    }

    private val score = mock(FullScore::class.java).also {
        doReturn(0L).`when`(it).policiesInternal
    }

    private val oldNai = mock(NetworkAgentInfo::class.java).also {
        doReturn(nc).`when`(it).capsNoCopy
        doReturn(score).`when`(it).score
    }

    private val newNai = mock(NetworkAgentInfo::class.java).also {
        doReturn(nc).`when`(it).capsNoCopy
        doReturn(score).`when`(it).score
    }

    private val nri = mock(ConnectivityService.NetworkRequestInfo::class.java).also {
                doReturn(PREFERENCE_ORDER_SATELLITE_FALLBACK).`when`(it).preferenceOrderForNetd
                doReturn(emptySet<UidRange>()).`when`(it).uids
            }

    private val infoListCaptor = ArgumentCaptor.forClass(DefaultNetworkRematchInfoList::class.java)

    private val metrics = DefaultNetworkRematchMetrics(deps)

    @Test
    fun testAddEvent_wrongPreference_isIgnored() {
        doReturn(PREFERENCE_ORDER_NONE).`when`(nri).preferenceOrderForNetd

        metrics.addEvent(nri, oldNai, newNai, 0L)
        metrics.writeStatsAndClear()

        verify(deps, never()).writeStats(anyLong(), anyInt(), any())
    }

    @Test
    fun testAddEvent_notFromSatellite_isIgnored() {
        // Setup: The old network is not a satellite network. The mock for its capabilities
        // will return false when checked for the SATELLITE transport type.
        val nonSatelliteCaps = mock(NetworkCapabilities::class.java)
        doReturn(false).`when`(nonSatelliteCaps).hasTransport(TRANSPORT_SATELLITE)
        doReturn(nonSatelliteCaps).`when`(oldNai).capsNoCopy

        // Action: Attempt to add the event and write stats.
        metrics.addEvent(nri, oldNai, newNai, 0L)
        metrics.writeStatsAndClear()

        // Verification: The event should be ignored, so writeStats should never be called.
        verify(deps, never()).writeStats(anyLong(), anyInt(), any())
    }

    @Test
    fun testAddEvent_validRequest_isAdded() {
        metrics.addEvent(nri, oldNai, newNai, 0L)
        metrics.writeStatsAndClear()

        verify(deps, times(1)).writeStats(anyLong(), anyInt(), any())
    }

    @Test
    fun testWriteStats_noEvents_doesNothing() {
        metrics.writeStatsAndClear()
        verify(deps, never()).writeStats(anyLong(), anyInt(), any())
    }

    @Test
    fun testWriteStats_withEvents_writesAndClears() {
        // Setup: Define the time the network satisfied time and the current time.
        val satisfiedDurationMs = 120_000L

        // First call: Add event and write
        metrics.addEvent(nri, oldNai, newNai, satisfiedDurationMs)
        metrics.writeStatsAndClear()

        verify(deps, times(1)).writeStats(anyLong(), anyInt(), infoListCaptor.capture())
        val capturedList = infoListCaptor.value
        assertEquals(1, capturedList.defaultNetworkRematchInfoList.size)
        val info = capturedList.defaultNetworkRematchInfoList[0]
        assertEquals((satisfiedDurationMs / 1000).toInt(), info.timeDurationOnOldNetworkSec)

        // Second call: Should do nothing as events are cleared
        clearInvocations(deps)
        metrics.writeStatsAndClear()
        verify(deps, never()).writeStats(anyLong(), anyInt(), any())
    }

    @Test
    fun testGetMeteredState() {
        // Temporarily Unmetered should be checked first
        val tempUnmeteredCaps = mock(NetworkCapabilities::class.java)
        doReturn(true).`when`(tempUnmeteredCaps)
                .hasCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        doReturn(true).`when`(tempUnmeteredCaps)
                .hasCapability(NET_CAPABILITY_NOT_METERED)
        assertEquals(
            METERED_TEMPORARILY_UNMETERED,
            DefaultNetworkRematchMetrics.getMeteredState(tempUnmeteredCaps)
        )

        // Not Metered
        val notMeteredCaps = mock(NetworkCapabilities::class.java)
        doReturn(true).`when`(notMeteredCaps).hasCapability(NET_CAPABILITY_NOT_METERED)
        assertEquals(METERED_NO, DefaultNetworkRematchMetrics.getMeteredState(notMeteredCaps))

        // Metered (default)
        val meteredCaps = mock(NetworkCapabilities::class.java)
        assertEquals(METERED_YES, DefaultNetworkRematchMetrics.getMeteredState(meteredCaps))
    }

    @Test
    fun testGetValidatedState() {
        // Valid
        val validatedCaps = mock(NetworkCapabilities::class.java)
        doReturn(true).`when`(validatedCaps).hasCapability(NET_CAPABILITY_VALIDATED)
        assertEquals(VS_VALID, DefaultNetworkRematchMetrics.getValidatedState(validatedCaps))

        // Captive Portal
        val portalCaps = mock(NetworkCapabilities::class.java)
        doReturn(true).`when`(portalCaps).hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL)
        assertEquals(VS_PORTAL, DefaultNetworkRematchMetrics.getValidatedState(portalCaps))

        // Partial Connectivity
        val partialCaps = mock(NetworkCapabilities::class.java)
        doReturn(true).`when`(partialCaps).hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY)
        assertEquals(VS_PARTIAL, DefaultNetworkRematchMetrics.getValidatedState(partialCaps))

        // Invalid
        val invalidCaps = mock(NetworkCapabilities::class.java).also {
            doReturn(false).`when`(it).hasCapability(NET_CAPABILITY_VALIDATED)
            doReturn(false).`when`(it).hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL)
            doReturn(false).`when`(it).hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY)
        }
        assertEquals(VS_INVALID, DefaultNetworkRematchMetrics.getValidatedState(invalidCaps))
    }

    @Test
    fun testGetUidRangesProto() {
        val uids = setOf(UidRange(1000, 1000), UidRange(2000, 3000))
        doReturn(uids).`when`(nri).uids

        metrics.addEvent(nri, oldNai, null, 0L)
        metrics.writeStatsAndClear()

        verify(deps).writeStats(anyLong(), anyInt(), infoListCaptor.capture())
        val capturedInfo = infoListCaptor.value.defaultNetworkRematchInfoList[0]

        val capturedRanges = capturedInfo.uidRanges.uidRangeList
        assertEquals(2, capturedRanges.size)
        assertTrue(capturedRanges.any { it.begin == 1000 && it.end == 1000 })
        assertTrue(capturedRanges.any { it.begin == 2000 && it.end == 3000 })
    }

    @Test
    fun testGetNetworkDescription() {
        val transports = 0x67890ABL
        val capsInternal = 12345L
        val scorePoliciesInternal = 0xDEADBEEFL
        val enterpriseIds = 0x03

        val caps = mock(NetworkCapabilities::class.java).also {
            doReturn(transports).`when`(it).transportTypesInternal
            doReturn(true).`when`(it).hasCapability(NET_CAPABILITY_VALIDATED)
            doReturn(true).`when`(it).hasCapability(NET_CAPABILITY_NOT_METERED)
            doReturn(capsInternal).`when`(it).capabilitiesInternal
            doReturn(enterpriseIds).`when`(it).enterpriseIdsInternal
        }
        val score = mock(FullScore::class.java).also {
            doReturn(scorePoliciesInternal).`when`(it).policiesInternal
        }

        val nai = mock(NetworkAgentInfo::class.java).also {
            doReturn(caps).`when`(it).capsNoCopy
            doReturn(score).`when`(it).score
        }

        val description = DefaultNetworkRematchMetrics.getNetworkDescription(nai)

        assertEquals(transports.toInt(), description.transportTypes)
        assertEquals(VS_VALID, description.validatedState)
        assertEquals(METERED_NO, description.meteredState)
        assertEquals(capsInternal, description.capabilities)
        assertEquals(scorePoliciesInternal, description.scorePolicies)
        assertEquals(enterpriseIds, description.enterpriseId)
    }
}
