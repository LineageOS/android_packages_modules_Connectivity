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

@file:Suppress("UNCHECKED_CAST") // cast is checked by type.isInstance throughout the file

package com.android.net.module.util

import com.android.testutils.failWithErrorReason
import kotlin.reflect.KClass

private const val DEFAULT_TIMEOUT = 30_000L // ms
private const val DEFAULT_NO_EVENT_TIMEOUT = 200L // ms

/**
 * These classes, [Expectable] and [TestableCallback], are tools designed to simplify the testing
 * of asynchronous callbacks in your code.
 *
 * A **callback** is a piece of code that gets executed when a specific event happens (e.g., data
 * received, network disconnected). These utilities help you define, record, and then assert the
 * occurrence or non-occurrence of these events in your tests.
 *
 * To use these tools, your test class typically needs to:
 * 1. Define a **sealed class** for all possible events that your callback can produce. Each
 * specific event should be a `data class` subclass of this sealed event type. This ensures type
 * safety and clear event definitions.
 *   Note : if there is only one event you can skip the sealed class and only have one data class
 *   representing that event.
 * 2. Subclass [TestableCallback] and have your subclass implement the callback interface. If you
 * cannot subclass [TestableCallback] because the callback is not an interface, your subclass
 * will instead compose a [TestableCallback] which will allow for implementing [Expectable]
 * with no additional work.
 *
 *
 * ## How to Use: Two Main Scenarios
 *
 * ### Scenario 1: Testing an Interface callback (Recommended)
 *
 * If the callback you are testing is an **interface** (e.g., `NetworkOfferCallback`), then
 * your test callback class should **inherit directly from [TestableCallback]**. This gives you
 * all the `expect` and `assertNo` family of methods for free.
 *
 * **Example:** Testing a `TestedServiceCallback` interface with `onFirstAction` and
 * `onSecondAction` methods.
 * ```kotlin
 * // The interface you want to test
 * interface TestedServiceCallback {
 *   fun onFirstAction(data: String)
 *   fun onSecondAction(value: Int)
 * }
 *
 * class MyTestServiceCallback : TestableCallback<Event>(), TestedServiceCallback {
 *   // Define all the possible events that can occur from this callback
 *   sealed class Event {
 *     data class FirstAction(val data: String) : ServiceEvent()
 *     data class SecondAction(val value: Int) : ServiceEvent()
 *   }
 *
 *   // Implement the interface methods and record the events into the history
 *   override fun onFirstAction(data: String) = history.add(FirstAction(data))
 *   override fun onSecondAction(value: Int) = history.add(SecondAction(value))
 *
 *   // You can add custom helper methods here if you want more functionality than the default
 *   // expect/assertNo methods offer.
 * }
 * ```
 *
 * **In your test code, you can then:**
 * // Wait for a `FirstAction` event where the argument starts with "foo" to happen within the
 * // default timeout.
 * `myTestServiceCallback.expect<FirstAction>() { it.data.startsWith("foo") }`
 * // Wait for any `SecondAction` event for up to 500 milliseconds.
 * `myTestServiceCallback.expect<SecondAction>(timeoutMs = 500)`
 * // Make sure no `SecondAction` event has fired so far.
 * `myTestServiceCallback.assertNo<SecondAction>()
 * etc.
 *
 * ### Scenario 2: Testing a class callback (Only do this when inheritance isn't possible)
 *
 * If the callback you need to test is a **class** (not an interface), direct inheritance from
 * [TestableCallback] isn't possible. In this case, you will:
 * 1. **Embed** an instance of [TestableCallback] inside your test class.
 * 2. Declare that your test class **implements [Expectable] by delegating** to this embedded
 * [TestableCallback] instance. This means your class will use the embedded `TestableCallback`'s
 * functionality for event tracking.
 * ```kotlin
 * MyTestServiceCallback(internalEventTracker: TestableCallback<Event>)
 *   : TestedServiceCallback, Expectable<Event> by internalEventTracker { ...
 * ```
 * 3. **Proxy** the `expect`, `eventuallyExpect`, and `assertNo` methods. This makes your test code
 * simpler by allowing you to write `expect<MySpecificEvent>()` instead of
 * `expect<_, MySpecificEvent>()`.
 *
 * **Example:** Testing a `TestedServiceCallback` class with `onFirstAction` and `onSecondAction`
 * methods.
 * ```kotlin
 * import com.android.net.module.util.assertNo
 * import com.android.net.module.util.expect
 * import com.android.net.module.util.eventuallyExpect
 *
 * // The class you want to test
 * open class TestedServiceCallback {
 *   open fun onFirstAction(data: String)
 *   open fun onSecondAction(value: Int)
 * }
 *
 * class MyTestServiceCallback(
 * // Embed a TestableCallback to handle the actual event tracking
 *   private val internalEventTracker: TestableCallback<Event> = TestableCallback()
 * ) : TestedServiceCallback(), Expectable<Event> by internalEventTracker {
 *
 *   // Define all the possible events that can occur from this callback
 *   sealed class Event {
 *     data class FirstAction(val data: String) : ServiceEvent()
 *     data class SecondAction(val value: Int) : ServiceEvent()
 *   }
 *
 *   // Implement the interface methods and record the events into the history
 *   override fun onFirstAction(data: String) = history.add(FirstAction(data))
 *   override fun onSecondAction(value: Int) = history.add(SecondAction(value))
 *
 *   // ---- Bridge to TestableCallback, do not modify (implements standard behavior) ----
 *   // Proxy methods: These make it simpler to call expect/assertNo, avoiding 'expect<_, T>'
 *   // They simply forward the call to the embedded internalEventTracker.
 *   inline fun <reified T : Event> expect(
 *     timeoutMs: Long = defaultTimeoutMs,
 *     errorMsg: String? = null,
 *     noinline predicate: (T) -> Boolean = { true }
 *   ) = expect<_, T>(timeoutMs, errorMsg, predicate)
 *
 *   inline fun <reified T : Event> eventuallyExpect(
 *     timeoutMs: Long = defaultTimeoutMs,
 *     errorMsg: String? = null,
 *     noinline predicate: (T) -> Boolean = { true }
 *   ) = eventuallyExpect<_, T>(timeoutMs, errorMsg, predicate)
 *
 *   inline fun <reified T : Event> assertNo(
 *     timeoutMs: Long = defaultNoEventTimeoutMs,
 *     errorMsg: String? = null,
 *     noinline predicate: (T) -> Boolean = { true }
 *   ) = assertNo<_, T>(timeoutMs, errorMsg, predicate)
 *   // ---- End of bridge section ----
 * }
 * ```
 * HINT : if the proxy methods above give an error ("Type checking has run into a recursive
 * problem" or "None of the following functions can be called with the arguments supplied"),
 * you need to add the three imports (see at the top of the example above).
 *
 * ### Explanation of proxy methods (for the curious)
 *
 * The `expect`, `eventuallyExpect`, and `assertNo` methods typically reside on
 * `TestableCallback<Event>`. When you inherit from `TestableCallback`, the type `Event` is
 * directly known, making calls like `expect<MySpecificEvent>()` straightforward.
 *
 * However, `Expectable` is an interface, and as such it cannot have member methods. The
 * `Expectable.expect*` family of methods are therefore extension methods with two type arguments :
 * one for the type argument of the receiver (`Event`), one for the concrete event type
 * (`FirstAction`) ; the former is required to provide the upper bound for the latter (otherwise
 * you could call `expect<String>()`).
 * For syntactic reasons, Kotlin's compiler will refuse to call a 2-argument method with only one
 * argument, even if it can infer the first type argument. The proxy methods (like the
 * `inline fun <reified T : Event> expect(...)`) solve this by providing a single-argument version
 * that calls the 2-type argument version instructing the compiler to infer the first argument
 * (`expect<_, T>` infers `_`). This lets you write `expect<MySpecificEvent>()`.
 */
/**
 * An interface for classes on which the expect* and assertNo* family of methods can be called.
 */
// TODO : why do we have to specify `: Any` here ? Is that not the default ? If you remove
// it then `KClass<T>` complains that T is not within its `Any` bounds,
interface Expectable<E : Any> {
    val history: ArrayTrackRecord<E>.ReadHead
    val defaultTimeoutMs: Long
    val defaultNoEventTimeoutMs: Long

    /*****
     * Expect an event immediately
     *
     * This method fetches the next callback and assert it matches the conditions : type,
     * passed predicate. If no callback is received within the timeout, this method fails.
     */
    fun <T : E> expect(
        type: KClass<T>,
        timeoutMs: Long = defaultTimeoutMs,
        errorMsg: String? = null,
        predicate: (T) -> Boolean = { true }
    ): T

    /*****
     * Expect that some event is received eventually.
     *
     * This method makes sure a callback that matches the type/predicate is received eventually.
     * Any callback of the wrong type, or doesn't match the optional predicate, is ignored.
     * It fails if no callback matching the predicate is received within the timeout.
     */
    fun <T : E> eventuallyExpect(
        type: KClass<T>,
        timeoutMs: Long = defaultTimeoutMs,
        errorMsg: String? = null,
        predicate: (T) -> Boolean = { true }
    ): T

    /*****
     * Assert no event matching a predicate was received.
     *
     * This method makes sure that no callback that matches the predicate was received.
     * If no predicate is given, it makes sure that no callback at all was received.
     */
    fun <T : E> assertNo(
        type: KClass<T>,
        timeoutMs: Long = defaultNoEventTimeoutMs,
        errorMsg: String? = null,
        predicate: (T) -> Boolean = { true }
    )

    fun assertNoCallback(
        timeoutMs: Long = defaultNoEventTimeoutMs,
        errorMsg: String? = null,
        predicate: (E) -> Boolean = { true }
    )
}

inline fun <E : Any, reified T : E> Expectable<E>.expect(
    timeoutMs: Long = defaultTimeoutMs,
    errorMsg: String? = null,
    noinline predicate: (T) -> Boolean = { true }
) = expect(T::class, timeoutMs, errorMsg, predicate)

inline fun <E : Any, reified T : E> Expectable<E>.eventuallyExpect(
    timeoutMs: Long = defaultTimeoutMs,
    errorMsg: String? = null,
    noinline predicate: (T) -> Boolean = { true }
) = eventuallyExpect(T::class, timeoutMs, errorMsg, predicate)

inline fun <E : Any, reified T : E> Expectable<E>.assertNo(
    timeoutMs: Long = defaultNoEventTimeoutMs,
    errorMsg: String? = null,
    noinline predicate: (T) -> Boolean = { true }
) = assertNo(T::class, timeoutMs, errorMsg, predicate)

/**
 * A base class for connectivity testable callbacks.
 */
open class TestableCallback<E : Any>(
    override val defaultTimeoutMs: Long = DEFAULT_TIMEOUT,
    override val defaultNoEventTimeoutMs: Long = DEFAULT_NO_EVENT_TIMEOUT
) : Expectable<E> {
    override val history = ArrayTrackRecord<E>().newReadHead()
    val mark get() = history.mark

    /**
     * Implementation of the expect<> family of methods. All other
     * methods eventually call this. They implement the Expectable<E>
     * interface.
     */
    override fun <T : E> expect(
        type: KClass<T>,
        timeoutMs: Long,
        errorMsg: String?,
        predicate: (T) -> Boolean
    ): T {
        val event = history.poll(timeoutMs)
        if (null == event) {
            val lastReceivedCallback = history.lastOrNull()
            val lastCallbackMsg = if (null == lastReceivedCallback) {
                "No callback received since filed"
            } else {
                "Last received callback was $lastReceivedCallback"
            }
            failWithErrorReason(
                errorMsg,
                "Did not receive ${type.simpleName} after ${timeoutMs}ms. " +
                        lastCallbackMsg
            )
        }
        if (!type.isInstance(event)) {
            failWithErrorReason(
                errorMsg,
                "Expected callback ${type.simpleName}, got $event. Latest callbacks :\n" +
                        history.prettyPrintBacktrace(before = 3)
            )
        }
        event as T
        if (!predicate(event)) {
            failWithErrorReason(errorMsg, "Callback doesn't match predicate : $event")
        }
        return event
    }

    override fun <T : E> eventuallyExpect(
        type: KClass<T>,
        timeoutMs: Long,
        errorMsg: String?,
        predicate: (T) -> Boolean,
    ): T {
        val event = history.poll(timeoutMs) { type.isInstance(it) && predicate(it as T) }
        if (null == event) {
            failWithErrorReason(
                errorMsg,
                "Callback ${type.simpleName} not received within ${timeoutMs}ms. " +
                        "Got \n${history.prettyPrintBacktrace(before = 4, after = 4)}"
            )
        }
        return event as T
    }

    override fun assertNoCallback(
        timeoutMs: Long,
        errorMsg: String?,
        predicate: (E) -> Boolean,
    ) {
        val mark = history.mark
        try {
            val event = history.poll(timeoutMs) { predicate(it) }
            if (null != event) {
                failWithErrorReason(
                    errorMsg,
                    "Expected no such callback but found $event"
                )
            }
        } finally {
            // Rewind so callers can expect a failure and continue later. For example, a caller
            // might want to assert no callback { interfaceName == "wlan1" } but then go on
            // to test that some other event took place, which would not be possible without this
            // rewind because it would have been skipped by the `poll` call above.
            history.rewind(mark)
        }
    }

    override fun <T : E> assertNo(
        type: KClass<T>,
        timeoutMs: Long,
        errorMsg: String?,
        predicate: (T) -> Boolean,
    ) = assertNoCallback(timeoutMs, errorMsg) { type.isInstance(it) && predicate(it as T) }

    /**
     * Proxy methods to avoid having to write expect<_, T>, see comment at the top of this file.
     */
    inline fun <reified T : E> expect(
        timeoutMs: Long = defaultTimeoutMs,
        errorMsg: String? = null,
        noinline predicate: (T) -> Boolean = { true }
    ) = expect<_, T>(timeoutMs, errorMsg, predicate)

    inline fun <reified T : E> eventuallyExpect(
        timeoutMs: Long = defaultTimeoutMs,
        errorMsg: String? = null,
        noinline predicate: (T) -> Boolean = { true }
    ) = eventuallyExpect<_, T>(timeoutMs, errorMsg, predicate)

    inline fun <reified T : E> assertNo(
        timeoutMs: Long = defaultNoEventTimeoutMs,
        errorMsg: String? = null,
        noinline predicate: (T) -> Boolean = { true }
    ) = assertNo<_, T>(timeoutMs, errorMsg, predicate)
}
