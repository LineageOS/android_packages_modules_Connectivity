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

package com.android.testutils

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.mockito.Mockito
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

class AutoReleaseBluetoothRuleTest {
    private val mockBluetoothAdapter = mock(BluetoothAdapter::class.java)
    private val mockBluetoothManager = mock(BluetoothManager::class.java).also {
        doReturn(mockBluetoothAdapter).`when`(it).adapter
    }
    private val mockContext = mock(Context::class.java).also {
        doReturn(mockBluetoothManager)
                .`when`(it).getSystemService(BluetoothManager::class.java)
    }
    private val mockStatement = mock(Statement::class.java)
    private val mockDescription = mock(Description::class.java)
    private val rule = AutoReleaseBluetoothRule(mockContext)

    @Test
    fun testIsBluetoothSupported_True() {
        assertTrue(rule.isBluetoothSupported())
    }

    @Test
    fun testIsBluetoothSupported_False() {
        doReturn(null).`when`(mockBluetoothManager).adapter
        assertFalse(rule.isBluetoothSupported())
    }

    @Test
    fun testIsBluetoothEnabled() {
        doReturn(true).`when`(mockBluetoothAdapter).isEnabled
        assertTrue(rule.isBluetoothEnabled())

        doReturn(false).`when`(mockBluetoothAdapter).isEnabled
        assertFalse(rule.isBluetoothEnabled())
    }

    @Test
    fun testApply_whenBluetoothNotSupported_doesNothing() {
        doReturn(null).`when`(mockBluetoothManager).adapter
        val spyRule = spy(rule)

        spyRule.apply(mockStatement, mockDescription).evaluate()

        verify(mockStatement).evaluate()
        verify(spyRule, never()).setBluetoothEnabled(anyBoolean())
    }

    @Test
    fun testApply_restoresInitialState_wasEnabled() {
        val spyRule = spy(rule)
        // Stub the initial state check.
        doReturn(true).`when`(spyRule).isBluetoothEnabled()
        // Prevent the real setBluetoothEnabled from being called.
        doNothing().`when`(spyRule).setBluetoothEnabled(anyBoolean())

        spyRule.apply(mockStatement, mockDescription).evaluate()

        // Verify that the statement is evaluated before the cleanup action.
        val inOrder = Mockito.inOrder(mockStatement, spyRule)
        inOrder.verify(mockStatement).evaluate()
        inOrder.verify(spyRule).setBluetoothEnabled(true)
    }

    @Test
    fun testApply_restoresInitialState_wasDisabled() {
        val spyRule = spy(rule)
        // Stub the initial state check.
        doReturn(false).`when`(spyRule).isBluetoothEnabled()
        // Prevent the real setBluetoothEnabled from being called.
        doNothing().`when`(spyRule).setBluetoothEnabled(anyBoolean())

        spyRule.apply(mockStatement, mockDescription).evaluate()

        // Verify that the statement is evaluated before the cleanup action.
        val inOrder = Mockito.inOrder(mockStatement, spyRule)
        inOrder.verify(mockStatement).evaluate()
        inOrder.verify(spyRule).setBluetoothEnabled(false)
    }
}
