/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.net.NetworkCapabilities
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.util.Log
import com.android.net.module.util.ArrayTrackRecord
import kotlin.test.fail

private const val DEFAULT_TIMEOUT = 30_000L // ms
private const val DEFAULT_NO_CALLBACK_TIMEOUT = 200L // ms

class TestableNetworkOfferCallback(
    val timeoutMs: Long = DEFAULT_TIMEOUT,
    private val noCallbackTimeoutMs: Long = DEFAULT_NO_CALLBACK_TIMEOUT,
) : NetworkProvider.NetworkOfferCallback {
    private val TAG = this::class.simpleName
    val history = ArrayTrackRecord<Event>().newReadHead()
    val mark get() = history.mark

    sealed class Event {
        data class Needed(val request: NetworkRequest) : Event()
        data class Unneeded(val request: NetworkRequest) : Event()
    }

    /**
     * Called by the system when a network for this offer is needed to satisfy some
     * networking request.
     */
    override fun onNetworkNeeded(request: NetworkRequest) {
        Log.d(TAG, "onNetworkNeeded $request")
        history.add(Event.Needed(request))
    }

    /**
     * Called by the system when this offer is no longer valuable for this request.
     */
    override fun onNetworkUnneeded(request: NetworkRequest) {
        Log.d(TAG, "onNetworkUnneeded $request")
        history.add(Event.Unneeded(request))
    }

    inline fun <reified T : Event> expect(
        errorMsg: String? = null,
        predicate: (T) -> Boolean = { true }
    ): T {
        val nextEntry = history.poll(timeoutMs) ?: failWithErrorReason(errorMsg,
            "Did not receive ${T::class.simpleName} after ${timeoutMs}ms")
        if (nextEntry !is T) {
            failWithErrorReason(
                errorMsg,
                "Expected callback ${T::class.simpleName}, got $nextEntry"
            )
        }
        if (!predicate(nextEntry)) {
            failWithErrorReason(errorMsg, "Callback doesn't match predicate: $nextEntry")
        }
        return nextEntry
    }

    // TODO : remove when there are no callers
    @Deprecated("Use expect instead of expectCallbackThat")
    inline fun <reified T : Event> expectCallbackThat(
        crossinline predicate: (T) -> Boolean
    ): T = expect<T>(null, predicate)

    inline fun <reified T : Event> eventuallyExpect(
        errorMsg: String? = null,
        from: Int = mark,
        crossinline predicate: (T) -> Boolean = { true }
    ) = history.poll(timeoutMs, from) { it is T && predicate(it) }.also {
        if (null == it) {
            failWithErrorReason(
                errorMsg,
                "Callback ${T::class} not received within ${timeoutMs}ms."
            )
        }
    } as T

    fun expectOnNetworkNeeded(capabilities: NetworkCapabilities) =
            expectCallbackThat<Event.Needed> {
                it.request.canBeSatisfiedBy(capabilities)
            }

    fun expectOnNetworkUnneeded(capabilities: NetworkCapabilities) =
            expectCallbackThat<Event.Unneeded> {
                it.request.canBeSatisfiedBy(capabilities)
            }

    fun assertNoCallback() {
        val cb = history.poll(noCallbackTimeoutMs)
        if (null != cb) fail("Expected no callback but got $cb")
    }
}
