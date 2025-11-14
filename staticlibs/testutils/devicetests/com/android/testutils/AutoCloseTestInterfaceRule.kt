/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.content.Context
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.TestNetworkManager.TestInterfaceRequest
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class AutoCloseTestInterfaceRule(
        private val context: Context,
    ) : TestRule {
    private val tnm = runAsShell(MANAGE_TEST_NETWORKS) {
        context.getSystemService(TestNetworkManager::class.java)!!
    }
    private val ifaces = ArrayList<TestNetworkInterface>()

    fun createTapInterface(): TestNetworkInterface {
        return runAsShell(MANAGE_TEST_NETWORKS) {
            tnm.createTapInterface()
        }.also {
            ifaces.add(it)
        }
    }

    fun createTestInterface(req: TestInterfaceRequest): TestNetworkInterface {
        return runAsShell(MANAGE_TEST_NETWORKS) {
            tnm.createTestInterface(req)
        }.also {
            ifaces.add(it)
        }
    }

    private fun closeAllInterfaces() {
        // TODO: wait on RTM_DELLINK before proceeding.
        for (iface in ifaces) {
            // ParcelFileDescriptor prevents the fd from being double closed.
            iface.getFileDescriptor().close()
        }
    }

    private inner class AutoCloseTestInterfaceRuleStatement(
        private val base: Statement,
        private val description: Description
    ) : Statement() {
        override fun evaluate() {
            tryTest {
                base.evaluate()
            } cleanup {
                closeAllInterfaces()
            }
        }
    }

    override fun apply(base: Statement, description: Description): Statement {
        return AutoCloseTestInterfaceRuleStatement(base, description)
    }
}
