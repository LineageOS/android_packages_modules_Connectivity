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

package android.net.cts

import android.Manifest.permission.READ_DEVICE_CONFIG
import android.Manifest.permission.WRITE_DEVICE_CONFIG
import android.provider.DeviceConfig
import android.util.Log
import com.android.modules.utils.build.SdkLevel
import com.android.testutils.FunctionalUtils.ThrowingRunnable
import com.android.testutils.runAsShell
import com.android.testutils.tryTest
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

private val TAG = DeviceConfigRule::class.simpleName

/**
 * A [TestRule] that helps set [DeviceConfig] for tests and clean up the test configuration
 * automatically on teardown.
 *
 * The rule can also optionally retry tests when they fail following an external change of
 * DeviceConfig before S; this typically happens because device config flags are synced while the
 * test is running, and DisableConfigSyncTargetPreparer is only usable starting from S.
 *
 * @param retryCountBeforeSIfConfigChanged if > 0, when the test fails before S, check if
 *        the configs that were set through this rule were changed, and retry the test
 *        up to the specified number of times if yes.
 */
class DeviceConfigRule @JvmOverloads constructor(
    val retryCountBeforeSIfConfigChanged: Int = 0
) : TestRule {
    // Maps (namespace, key) -> value
    private val originalConfig = mutableMapOf<Pair<String, String>, String?>()
    private val usedConfig = mutableMapOf<Pair<String, String>, String?>()

    /**
     * Actions to be run after cleanup of the config, for the current test only.
     */
    private val currentTestCleanupActions = mutableListOf<ThrowingRunnable>()

    override fun apply(base: Statement, description: Description): Statement {
        return TestValidationUrlStatement(base, description)
    }

    private inner class TestValidationUrlStatement(
        private val base: Statement,
        private val description: Description
    ) : Statement() {
        override fun evaluate() {
            var retryCount = if (SdkLevel.isAtLeastS()) 1 else retryCountBeforeSIfConfigChanged + 1
            while (retryCount > 0) {
                retryCount--
                tryTest {
                    base.evaluate()
                    // Can't use break/return out of a loop here because this is a tryTest lambda,
                    // so set retryCount to exit instead
                    retryCount = 0
                }.catch<Throwable> { e -> // junit AssertionFailedError does not extend Exception
                    if (retryCount == 0) throw e
                    usedConfig.forEach { (key, value) ->
                        val currentValue = runAsShell(READ_DEVICE_CONFIG) {
                            DeviceConfig.getProperty(key.first, key.second)
                        }
                        if (currentValue != value) {
                            Log.w(TAG, "Test failed with unexpected device config change, retrying")
                            return@catch
                        }
                    }
                    throw e
                } cleanupStep {
                    runAsShell(WRITE_DEVICE_CONFIG) {
                        originalConfig.forEach { (key, value) ->
                            DeviceConfig.setProperty(
                                    key.first, key.second, value, false /* makeDefault */)
                        }
                    }
                } cleanupStep {
                    originalConfig.clear()
                    usedConfig.clear()
                } cleanup {
                    // Fold all cleanup actions into cleanup steps of an empty tryTest, so they are
                    // all run even if exceptions are thrown, and exceptions are reported properly.
                    currentTestCleanupActions.fold(tryTest { }) {
                        tryBlock, action -> tryBlock.cleanupStep { action.run() }
                    }.cleanup {
                        currentTestCleanupActions.clear()
                    }
                }
            }
        }
    }

    /**
     * Set a configuration key/value. After the test case ends, it will be restored to the value it
     * had when this method was first called.
     */
    fun setConfig(namespace: String, key: String, value: String?) {
        runAsShell(READ_DEVICE_CONFIG, WRITE_DEVICE_CONFIG) {
            val keyPair = Pair(namespace, key)
            if (!originalConfig.containsKey(keyPair)) {
                originalConfig[keyPair] = DeviceConfig.getProperty(namespace, key)
            }
            usedConfig[keyPair] = value
            DeviceConfig.setProperty(namespace, key, value, false /* makeDefault */)
        }
    }

    /**
     * Add an action to be run after config cleanup when the current test case ends.
     */
    fun runAfterNextCleanup(action: ThrowingRunnable) {
        currentTestCleanupActions.add(action)
    }
}
