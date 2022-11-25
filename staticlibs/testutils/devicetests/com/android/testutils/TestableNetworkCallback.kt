/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.util.Log
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.BlockedStatus
import com.android.testutils.RecorderCallback.CallbackEntry.BlockedStatusInt
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.Losing
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import com.android.testutils.RecorderCallback.CallbackEntry.Resumed
import com.android.testutils.RecorderCallback.CallbackEntry.Suspended
import com.android.testutils.RecorderCallback.CallbackEntry.Unavailable
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

object NULL_NETWORK : Network(-1)
object ANY_NETWORK : Network(-2)
fun anyNetwork() = ANY_NETWORK

open class RecorderCallback private constructor(
    private val backingRecord: ArrayTrackRecord<CallbackEntry>
) : NetworkCallback() {
    public constructor() : this(ArrayTrackRecord())
    protected constructor(src: RecorderCallback?): this(src?.backingRecord ?: ArrayTrackRecord())

    private val TAG = this::class.simpleName

    sealed class CallbackEntry {
        // To get equals(), hashcode(), componentN() etc for free, the child classes of
        // this class are data classes. But while data classes can inherit from other classes,
        // they may only have visible members in the constructors, so they couldn't declare
        // a constructor with a non-val arg to pass to CallbackEntry. Instead, force all
        // subclasses to implement a `network' property, which can be done in a data class
        // constructor by specifying override.
        abstract val network: Network

        data class Available(override val network: Network) : CallbackEntry()
        data class CapabilitiesChanged(
            override val network: Network,
            val caps: NetworkCapabilities
        ) : CallbackEntry()
        data class LinkPropertiesChanged(
            override val network: Network,
            val lp: LinkProperties
        ) : CallbackEntry()
        data class Suspended(override val network: Network) : CallbackEntry()
        data class Resumed(override val network: Network) : CallbackEntry()
        data class Losing(override val network: Network, val maxMsToLive: Int) : CallbackEntry()
        data class Lost(override val network: Network) : CallbackEntry()
        data class Unavailable private constructor(
            override val network: Network
        ) : CallbackEntry() {
            constructor() : this(NULL_NETWORK)
        }
        data class BlockedStatus(
            override val network: Network,
            val blocked: Boolean
        ) : CallbackEntry()
        data class BlockedStatusInt(
            override val network: Network,
            val blocked: Int
        ) : CallbackEntry()
        // Convenience constants for expecting a type
        companion object {
            @JvmField
            val AVAILABLE = Available::class
            @JvmField
            val NETWORK_CAPS_UPDATED = CapabilitiesChanged::class
            @JvmField
            val LINK_PROPERTIES_CHANGED = LinkPropertiesChanged::class
            @JvmField
            val SUSPENDED = Suspended::class
            @JvmField
            val RESUMED = Resumed::class
            @JvmField
            val LOSING = Losing::class
            @JvmField
            val LOST = Lost::class
            @JvmField
            val UNAVAILABLE = Unavailable::class
            @JvmField
            val BLOCKED_STATUS = BlockedStatus::class
            @JvmField
            val BLOCKED_STATUS_INT = BlockedStatusInt::class
        }
    }

    val history = backingRecord.newReadHead()
    val mark get() = history.mark

    override fun onAvailable(network: Network) {
        Log.d(TAG, "onAvailable $network")
        history.add(Available(network))
    }

    // PreCheck is not used in the tests today. For backward compatibility with existing tests that
    // expect the callbacks not to record this, do not listen to PreCheck here.

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        Log.d(TAG, "onCapabilitiesChanged $network $caps")
        history.add(CapabilitiesChanged(network, caps))
    }

    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
        Log.d(TAG, "onLinkPropertiesChanged $network $lp")
        history.add(LinkPropertiesChanged(network, lp))
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
        Log.d(TAG, "onBlockedStatusChanged $network $blocked")
        history.add(BlockedStatus(network, blocked))
    }

    // Cannot do:
    // fun onBlockedStatusChanged(network: Network, blocked: Int) {
    // because on S, that needs to be "override fun", and on R, that cannot be "override fun".
    override fun onNetworkSuspended(network: Network) {
        Log.d(TAG, "onNetworkSuspended $network $network")
        history.add(Suspended(network))
    }

    override fun onNetworkResumed(network: Network) {
        Log.d(TAG, "$network onNetworkResumed $network")
        history.add(Resumed(network))
    }

    override fun onLosing(network: Network, maxMsToLive: Int) {
        Log.d(TAG, "onLosing $network $maxMsToLive")
        history.add(Losing(network, maxMsToLive))
    }

    override fun onLost(network: Network) {
        Log.d(TAG, "onLost $network")
        history.add(Lost(network))
    }

    override fun onUnavailable() {
        Log.d(TAG, "onUnavailable")
        history.add(Unavailable())
    }
}

private const val DEFAULT_TIMEOUT = 30_000L // ms
private const val DEFAULT_NO_CALLBACK_TIMEOUT = 200L // ms

open class TestableNetworkCallback private constructor(
    src: TestableNetworkCallback?,
    val defaultTimeoutMs: Long = DEFAULT_TIMEOUT,
    val defaultNoCallbackTimeoutMs: Long = DEFAULT_NO_CALLBACK_TIMEOUT
) : RecorderCallback(src) {
    @JvmOverloads
    constructor(
        timeoutMs: Long = DEFAULT_TIMEOUT,
        noCallbackTimeoutMs: Long = DEFAULT_NO_CALLBACK_TIMEOUT
    ): this(null, timeoutMs, noCallbackTimeoutMs)

    fun createLinkedCopy() = TestableNetworkCallback(
            this, defaultTimeoutMs, defaultNoCallbackTimeoutMs)

    // The last available network, or null if any network was lost since the last call to
    // onAvailable. TODO : fix this by fixing the tests that rely on this behavior
    val lastAvailableNetwork: Network?
        get() = when (val it = history.lastOrNull { it is Available || it is Lost }) {
            is Available -> it.network
            else -> null
        }

    /**
     * Get the next callback or null if timeout.
     *
     * With no argument, this method waits out the default timeout. To wait forever, pass
     * Long.MAX_VALUE.
     */
    @JvmOverloads
    fun poll(timeoutMs: Long = defaultTimeoutMs): CallbackEntry? = history.poll(timeoutMs)

    /**
     * Get the next callback or throw if timeout.
     *
     * With no argument, this method waits out the default timeout. To wait forever, pass
     * Long.MAX_VALUE.
     */
    @JvmOverloads
    fun pollOrThrow(
        timeoutMs: Long = defaultTimeoutMs,
        errorMsg: String = "Did not receive callback after $timeoutMs"
    ): CallbackEntry = poll(timeoutMs) ?: fail(errorMsg)

    /*****
     * expect family of methods.
     * These methods fetch the next callback and assert it matches the conditions : type,
     * passed predicate. If no callback is received within the timeout, these methods fail.
     */
    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        network: Network = ANY_NETWORK,
        timeoutMs: Long = defaultTimeoutMs,
        errorMsg: String? = null,
        test: (T) -> Boolean = { true }
    ) = expect<CallbackEntry>(network, timeoutMs, errorMsg) {
        test(it as? T ?: fail("Expected callback ${type.simpleName}, got $it"))
    } as T

    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        network: HasNetwork,
        timeoutMs: Long = defaultTimeoutMs,
        errorMsg: String? = null,
        test: (T) -> Boolean = { true }
    ) = expect(type, network.network, timeoutMs, errorMsg, test)

    // Java needs an explicit overload to let it omit arguments in the middle, so define these
    // here. Note that @JvmOverloads give us the versions without the last arguments too, so
    // there is no need to explicitly define versions without the test predicate.
    // Without |network|
    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        timeoutMs: Long,
        errorMsg: String?,
        test: (T) -> Boolean = { true }
    ) = expect(type, ANY_NETWORK, timeoutMs, errorMsg, test)

    // Without |timeout|, in Network and HasNetwork versions
    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        network: Network,
        errorMsg: String?,
        test: (T) -> Boolean = { true }
    ) = expect(type, network, defaultTimeoutMs, errorMsg, test)

    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        network: HasNetwork,
        errorMsg: String?,
        test: (T) -> Boolean = { true }
    ) = expect(type, network.network, defaultTimeoutMs, errorMsg, test)

    // Without |errorMsg|, in Network and HasNetwork versions
    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        network: Network,
        timeoutMs: Long,
        test: (T) -> Boolean
    ) = expect(type, network, timeoutMs, null, test)

    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        network: HasNetwork,
        timeoutMs: Long,
        test: (T) -> Boolean
    ) = expect(type, network.network, timeoutMs, null, test)

    // Without |network| or |timeout|
    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        errorMsg: String?,
        test: (T) -> Boolean = { true }
    ) = expect(type, ANY_NETWORK, defaultTimeoutMs, errorMsg, test)

    // Without |network| or |errorMsg|
    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        timeoutMs: Long,
        test: (T) -> Boolean = { true }
    ) = expect(type, ANY_NETWORK, timeoutMs, null, test)

    // Without |timeout| or |errorMsg|, in Network and HasNetwork versions
    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        network: Network,
        test: (T) -> Boolean
    ) = expect(type, network, defaultTimeoutMs, null, test)

    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        network: HasNetwork,
        test: (T) -> Boolean
    ) = expect(type, network.network, defaultTimeoutMs, null, test)

    // Without |network| or |timeout| or |errorMsg|
    @JvmOverloads
    fun <T : CallbackEntry> expect(
        type: KClass<T>,
        test: (T) -> Boolean
    ) = expect(type, ANY_NETWORK, defaultTimeoutMs, null, test)

    // Kotlin reified versions. Don't call methods above, or the predicate would need to be noinline
    inline fun <reified T : CallbackEntry> expect(
        network: Network = ANY_NETWORK,
        timeoutMs: Long = defaultTimeoutMs,
        errorMsg: String? = null,
        test: (T) -> Boolean = { true }
    ) = pollOrThrow(timeoutMs, "Did not receive ${T::class.simpleName} after ${timeoutMs}ms").also {
        if (it !is T) fail("Expected callback ${T::class.simpleName}, got $it")
        if (ANY_NETWORK !== network && it.network != network) {
            fail("Expected network $network for callback : $it")
        }
        if (!test(it)) {
            fail("${errorMsg ?: "Callback doesn't match predicate"} : $it")
        }
    } as T

    inline fun <reified T : CallbackEntry> expect(
        network: HasNetwork,
        timeoutMs: Long = defaultTimeoutMs,
        errorMsg: String? = null,
        test: (T) -> Boolean = { true }
    ) = expect(network.network, timeoutMs, errorMsg, test)

    // Make open for use in ConnectivityServiceTest which is the only one knowing its handlers.
    // TODO : remove the necessity to overload this, remove the open qualifier, and give a
    // default argument to assertNoCallback instead, possibly with @JvmOverloads if necessary.
    open fun assertNoCallback() = assertNoCallback(defaultNoCallbackTimeoutMs)

    fun assertNoCallback(timeoutMs: Long) {
        val cb = history.poll(timeoutMs)
        if (null != cb) fail("Expected no callback but got $cb")
    }

    fun assertNoCallbackThat(
        timeoutMs: Long = defaultNoCallbackTimeoutMs,
        valid: (CallbackEntry) -> Boolean
    ) {
        val cb = history.poll(timeoutMs) { valid(it) }.let {
            if (null != it) fail("Expected no callback but got $it")
        }
    }

    // Expects a callback of the specified type matching the predicate within the timeout.
    // Any callback that doesn't match the predicate will be skipped. Fails only if
    // no matching callback is received within the timeout.
    inline fun <reified T : CallbackEntry> eventuallyExpect(
        timeoutMs: Long = defaultTimeoutMs,
        from: Int = mark,
        crossinline predicate: (T) -> Boolean = { true }
    ): T = eventuallyExpectOrNull(timeoutMs, from, predicate).also {
        assertNotNull(it, "Callback ${T::class} not received within ${timeoutMs}ms")
    } as T

    fun <T : CallbackEntry> eventuallyExpect(
        type: KClass<T>,
        timeoutMs: Long = defaultTimeoutMs,
        predicate: (cb: T) -> Boolean = { true }
    ) = history.poll(timeoutMs) { type.java.isInstance(it) && predicate(it as T) }.also {
        assertNotNull(it, "Callback ${type.java} not received within ${timeoutMs}ms")
    } as T

    fun <T : CallbackEntry> eventuallyExpect(
        type: KClass<T>,
        timeoutMs: Long = defaultTimeoutMs,
        from: Int = mark,
        predicate: (cb: T) -> Boolean = { true }
    ) = history.poll(timeoutMs, from) { type.java.isInstance(it) && predicate(it as T) }.also {
        assertNotNull(it, "Callback ${type.java} not received within ${timeoutMs}ms")
    } as T

    // TODO (b/157405399) straighten and unify the method names
    inline fun <reified T : CallbackEntry> eventuallyExpectOrNull(
        timeoutMs: Long = defaultTimeoutMs,
        from: Int = mark,
        crossinline predicate: (T) -> Boolean = { true }
    ) = history.poll(timeoutMs, from) { it is T && predicate(it) } as T?

    inline fun expectCapabilitiesThat(
        net: Network,
        tmt: Long = defaultTimeoutMs,
        valid: (NetworkCapabilities) -> Boolean
    ): CapabilitiesChanged =
            expect(net, tmt, "Capabilities don't match expectations") { valid(it.caps) }

    inline fun expectLinkPropertiesThat(
        net: Network,
        tmt: Long = defaultTimeoutMs,
        valid: (LinkProperties) -> Boolean
    ): LinkPropertiesChanged =
            expect(net, tmt, "LinkProperties don't match expectations") { valid(it.lp) }

    // Expects onAvailable and the callbacks that follow it. These are:
    // - onSuspended, iff the network was suspended when the callbacks fire.
    // - onCapabilitiesChanged.
    // - onLinkPropertiesChanged.
    // - onBlockedStatusChanged.
    //
    // @param network the network to expect the callbacks on.
    // @param suspended whether to expect a SUSPENDED callback.
    // @param validated the expected value of the VALIDATED capability in the
    //        onCapabilitiesChanged callback.
    // @param tmt how long to wait for the callbacks.
    fun expectAvailableCallbacks(
        net: Network,
        suspended: Boolean = false,
        validated: Boolean? = true,
        blocked: Boolean = false,
        tmt: Long = defaultTimeoutMs
    ) {
        expectAvailableCallbacksCommon(net, suspended, validated, tmt)
        expectBlockedStatusCallback(blocked, net, tmt)
    }

    fun expectAvailableCallbacks(
        net: Network,
        suspended: Boolean,
        validated: Boolean,
        blockedStatus: Int,
        tmt: Long
    ) {
        expectAvailableCallbacksCommon(net, suspended, validated, tmt)
        expectBlockedStatusCallback(blockedStatus, net)
    }

    private fun expectAvailableCallbacksCommon(
        net: Network,
        suspended: Boolean,
        validated: Boolean?,
        tmt: Long
    ) {
        expect<Available>(net, tmt)
        if (suspended) {
            expect<Suspended>(net, tmt)
        }
        expectCapabilitiesThat(net, tmt) {
            validated == null || validated == it.hasCapability(
                NET_CAPABILITY_VALIDATED
            )
        }
        expect<LinkPropertiesChanged>(net, tmt)
    }

    // Backward compatibility for existing Java code. Use named arguments instead and remove all
    // these when there is no user left.
    fun expectAvailableAndSuspendedCallbacks(
        net: Network,
        validated: Boolean,
        tmt: Long = defaultTimeoutMs
    ) = expectAvailableCallbacks(net, suspended = true, validated = validated, tmt = tmt)

    fun expectBlockedStatusCallback(blocked: Boolean, net: Network, tmt: Long = defaultTimeoutMs) =
            expect<BlockedStatus>(net, tmt, "Unexpected blocked status") {
                it.blocked == blocked
            }

    fun expectBlockedStatusCallback(blocked: Int, net: Network, tmt: Long = defaultTimeoutMs) =
            expect<BlockedStatusInt>(net, tmt, "Unexpected blocked status") {
                it.blocked == blocked
            }

    // Expects the available callbacks (where the onCapabilitiesChanged must contain the
    // VALIDATED capability), plus another onCapabilitiesChanged which is identical to the
    // one we just sent.
    // TODO: this is likely a bug. Fix it and remove this method.
    fun expectAvailableDoubleValidatedCallbacks(net: Network, tmt: Long = defaultTimeoutMs) {
        val mark = history.mark
        expectAvailableCallbacks(net, tmt = tmt)
        val firstCaps = history.poll(tmt, mark) { it is CapabilitiesChanged }
        assertEquals(firstCaps, expect<CapabilitiesChanged>(net, tmt))
    }

    // Expects the available callbacks where the onCapabilitiesChanged must not have validated,
    // then expects another onCapabilitiesChanged that has the validated bit set. This is used
    // when a network connects and satisfies a callback, and then immediately validates.
    fun expectAvailableThenValidatedCallbacks(net: Network, tmt: Long = defaultTimeoutMs) {
        expectAvailableCallbacks(net, validated = false, tmt = tmt)
        expectCapabilitiesThat(net, tmt) { it.hasCapability(NET_CAPABILITY_VALIDATED) }
    }

    fun expectAvailableThenValidatedCallbacks(
        net: Network,
        blockedStatus: Int,
        tmt: Long = defaultTimeoutMs
    ) {
        expectAvailableCallbacks(net, validated = false, suspended = false,
                blockedStatus = blockedStatus, tmt = tmt)
        expectCapabilitiesThat(net, tmt) { it.hasCapability(NET_CAPABILITY_VALIDATED) }
    }

    // Temporary Java compat measure : have MockNetworkAgent implement this so that all existing
    // calls with networkAgent can be routed through here without moving MockNetworkAgent.
    // TODO: clean this up, remove this method.
    interface HasNetwork {
        val network: Network
    }

    fun expectAvailableCallbacks(
        n: HasNetwork,
        suspended: Boolean,
        validated: Boolean,
        blocked: Boolean,
        timeoutMs: Long
    ) = expectAvailableCallbacks(n.network, suspended, validated, blocked, timeoutMs)

    fun expectAvailableAndSuspendedCallbacks(n: HasNetwork, expectValidated: Boolean) {
        expectAvailableAndSuspendedCallbacks(n.network, expectValidated)
    }

    fun expectAvailableCallbacksValidated(n: HasNetwork) {
        expectAvailableCallbacks(n.network)
    }

    fun expectAvailableCallbacksValidatedAndBlocked(n: HasNetwork) {
        expectAvailableCallbacks(n.network, blocked = true)
    }

    fun expectAvailableCallbacksUnvalidated(n: HasNetwork) {
        expectAvailableCallbacks(n.network, validated = false)
    }

    fun expectAvailableCallbacksUnvalidatedAndBlocked(n: HasNetwork) {
        expectAvailableCallbacks(n.network, validated = false, blocked = true)
    }

    fun expectAvailableDoubleValidatedCallbacks(n: HasNetwork) {
        expectAvailableDoubleValidatedCallbacks(n.network, defaultTimeoutMs)
    }

    fun expectAvailableThenValidatedCallbacks(n: HasNetwork) {
        expectAvailableThenValidatedCallbacks(n.network, defaultTimeoutMs)
    }

    @JvmOverloads
    fun expectLinkPropertiesThat(
        n: HasNetwork,
        tmt: Long = defaultTimeoutMs,
        valid: (LinkProperties) -> Boolean
    ) = expectLinkPropertiesThat(n.network, tmt, valid)

    @JvmOverloads
    fun expectCapabilitiesThat(
        n: HasNetwork,
        tmt: Long = defaultTimeoutMs,
        valid: (NetworkCapabilities) -> Boolean
    ) = expectCapabilitiesThat(n.network, tmt, valid)

    @JvmOverloads
    fun expectCapabilitiesWith(
        capability: Int,
        n: HasNetwork,
        timeoutMs: Long = defaultTimeoutMs
    ): NetworkCapabilities {
        return expectCapabilitiesThat(n.network, timeoutMs) { it.hasCapability(capability) }.caps
    }

    @JvmOverloads
    fun expectCapabilitiesWithout(
        capability: Int,
        n: HasNetwork,
        timeoutMs: Long = defaultTimeoutMs
    ): NetworkCapabilities {
        return expectCapabilitiesThat(n.network, timeoutMs) { !it.hasCapability(capability) }.caps
    }

    fun expectBlockedStatusCallback(expectBlocked: Boolean, n: HasNetwork) {
        expectBlockedStatusCallback(expectBlocked, n.network, defaultTimeoutMs)
    }
}
