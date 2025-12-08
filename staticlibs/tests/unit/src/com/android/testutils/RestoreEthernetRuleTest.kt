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

import android.os.Build
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

// EthernetManager#addEthernetStateListener is not supported before T.
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
@RunWith(DevSdkIgnoreRunner::class)
class RestoreEthernetRuleTest {
    private val mockStatement = mock<Statement>()
    private val mockDescription = mock<Description>()

    private val rule = mock<RestoreEthernetRule> {
        on { setEthernetEnabled(any()) } doAnswer { }
        on { apply(any(), any()) }.thenCallRealMethod()
    }

    private fun doTestApply_restoresInitialState(initialState: Boolean) {
        doReturn(initialState, !initialState).`when`(rule).isEthernetEnabled()

        rule.apply(mockStatement, mockDescription).evaluate()

        val inOrder = inOrder(mockStatement, rule)
        inOrder.verify(mockStatement).evaluate()
        inOrder.verify(rule).setEthernetEnabled(initialState)
    }

    @Test
    fun testApply_restoresInitialState_wasEnabled() =
            doTestApply_restoresInitialState(initialState = true)

    @Test
    fun testApply_restoresInitialState_wasDisabled() =
            doTestApply_restoresInitialState(initialState = false)

    @Test(expected = IllegalArgumentException::class)
    fun testSetEthernetEnabled_throwsWhenDisablingWithAdbOverEthernet() {
        doReturn(true).`when`(rule).isAdbOverEthernet()
        // Override the default mock behavior for setEthernetEnabled().
        // The test needs to call the real method to verify that it throws an exception.
        doCallRealMethod().`when`(rule).setEthernetEnabled(false)

        rule.setEthernetEnabled(false)
    }
}
