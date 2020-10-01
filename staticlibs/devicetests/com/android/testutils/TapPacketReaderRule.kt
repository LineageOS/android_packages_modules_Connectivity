/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.test.assertFalse
import kotlin.test.fail

private const val HANDLER_TIMEOUT_MS = 10_000L

/**
 * A [TestRule] that sets up a [TapPacketReader] on a [TestNetworkInterface] for use in the test.
 */
class TapPacketReaderRule @JvmOverloads constructor(
    private val maxPacketSize: Int = 1500
) : TestRule {
    // Use lateinit as the below members can't be initialized in the rule constructor (the
    // InstrumentationRegistry may not be ready), but from the point of view of test cases using
    // this rule, the members are always initialized (in setup/test/teardown): tests cases should be
    // able use them directly.
    // lateinit also allows getting good exceptions detailing what went wrong in the unlikely event
    // that the members are referenced before they could be initialized.
    lateinit var iface: TestNetworkInterface
    lateinit var reader: TapPacketReader

    // The reader runs on its own handlerThread created locally, but this is not an actual
    // requirement: any handler could be used for this rule. If using a specific handler is needed,
    // a method could be added to start the TapPacketReader manually on a given handler.
    private val handlerThread = HandlerThread(TapPacketReaderRule::class.java.simpleName)

    override fun apply(base: Statement, description: Description): Statement {
        return TapReaderStatement(base)
    }

    private inner class TapReaderStatement(private val base: Statement) : Statement() {
        override fun evaluate() {
            val ctx: android.content.Context = InstrumentationRegistry.getInstrumentation().context
            iface = runAsShell(MANAGE_TEST_NETWORKS) {
                val tnm = ctx.getSystemService(TestNetworkManager::class.java)
                        ?: fail("Could not obtain the TestNetworkManager")
                tnm.createTapInterface()
            }

            handlerThread.start()
            reader = TapPacketReader(handlerThread.threadHandler,
                    iface.fileDescriptor.fileDescriptor, maxPacketSize)
            reader.startAsyncForTest()

            try {
                base.evaluate()
            } finally {
                handlerThread.threadHandler.post(reader::stop)
                handlerThread.quitSafely()
                handlerThread.join(HANDLER_TIMEOUT_MS)
                assertFalse(handlerThread.isAlive,
                        "HandlerThread did not exit within $HANDLER_TIMEOUT_MS ms")
                iface.fileDescriptor.close()
            }
        }
    }
}