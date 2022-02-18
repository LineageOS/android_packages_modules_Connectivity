/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.nearby.multidevices.common

import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

/**
 * Helper methods to wrap common Mockito functions that don't do quite what you would expect in
 * Kotlin. The returned null values need to be recast to their original type in Kotlin otherwise it
 * breaks.
 */
object Mockotlin {

    /**
     * Delegates to [Mockito.any].
     * @return null as T
     */
    fun <T> any() = Mockito.any<T>() as T

    /**
     * Delegates to [Mockito.eq].
     * @return null as T
     */
    fun <T> eq(match: T) = Mockito.eq(match) as T

    /**
     * Delegates to [Mockito.isA].
     * @return null as T
     */
    fun <T> isA(match: Class<T>): T = Mockito.isA(match) as T

    /** Delegates to [Mockito.when ], uses the same API as the mockitokotlin2 library. */
    fun <T> whenever(methodCall: T) = Mockito.`when`(methodCall)!!

    /**
     * Delegates to [Mockito.any] and calls it with Class<T>.
     * @return Class<T>
     */
    inline fun <reified T> anyClass(): Class<T> {
        Mockito.any(T::class.java)
        return T::class.java
    }

    /**
     * Delegates to [Mockito.anyListOf] and calls it with Class<T>.
     * @return List<T>
     */
    fun <T> anyListOf(): List<T> = Mockito.anyList<T>()

    /**
     * Delegates to [Mockito.mock].
     * @return T
     */
    inline fun <reified T> mock() = Mockito.mock(T::class.java)!!

    /** This is the same as calling `MockitoAnnotations.initMocks(this)` */
    fun Any.initMocks() {
        MockitoAnnotations.initMocks(this)
    }

    /**
     * Returns ArgumentCaptor.capture() as nullable type to avoid java.lang.IllegalStateException
     * when null is returned.
     */
    fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
}
