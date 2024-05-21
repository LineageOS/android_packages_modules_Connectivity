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

package com.android.testutils.com.android.testutils

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A JUnit Rule that sets feature flags based on `@FeatureFlag` annotations.
 *
 * This rule enables dynamic control of feature flag states during testing.
 * And restores the original values after performing tests.
 *
 * **Usage:**
 * ```kotlin
 * class MyTestClass {
 *   @get:Rule
 *   val setFeatureFlagsRule = SetFeatureFlagsRule(setFlagsMethod = (name, enabled) -> {
 *     // Custom handling code.
 *   }, (name) -> {
 *     // Custom getter code to retrieve the original values.
 *   })
 *
 *   // ... test methods with @FeatureFlag annotations
 *   @FeatureFlag("FooBar1", true)
 *   @FeatureFlag("FooBar2", false)
 *   @Test
 *   fun testFooBar() {}
 * }
 * ```
 */
class SetFeatureFlagsRule(
    val setFlagsMethod: (name: String, enabled: Boolean?) -> Unit,
                          val getFlagsMethod: (name: String) -> Boolean?
) : TestRule {
    /**
     * This annotation marks a test method as requiring a specific feature flag to be configured.
     *
     * Use this on test methods to dynamically control feature flag states during testing.
     *
     * @param name The name of the feature flag.
     * @param enabled The desired state (true for enabled, false for disabled) of the feature flag.
     */
    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class FeatureFlag(val name: String, val enabled: Boolean = true)

    /**
     * This method is the core of the rule, executed by the JUnit framework before each test method.
     *
     * It retrieves the test method's metadata.
     * If any `@FeatureFlag` annotation is found, it passes every feature flag's name
     * and enabled state into the user-specified lambda to apply custom actions.
     */
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val testMethod = description.testClass.getMethod(description.methodName)
                val featureFlagAnnotations = testMethod.getAnnotationsByType(
                    FeatureFlag::class.java
                )

                val valuesToBeRestored = mutableMapOf<String, Boolean?>()
                for (featureFlagAnnotation in featureFlagAnnotations) {
                    valuesToBeRestored[featureFlagAnnotation.name] =
                            getFlagsMethod(featureFlagAnnotation.name)
                    setFlagsMethod(featureFlagAnnotation.name, featureFlagAnnotation.enabled)
                }

                // Execute the test method, which includes methods annotated with
                // @Before, @Test and @After.
                base.evaluate()

                valuesToBeRestored.forEach {
                    setFlagsMethod(it.key, it.value)
                }
            }
        }
    }
}
