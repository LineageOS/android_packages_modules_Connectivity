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

package com.android.nearby.multidevices.fastpair.seeker

import android.nearby.multidevices.fastpair.seeker.generateCompanionAppLaunchIntentUri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric tests for CompanionAppUtils.kt. */
@RunWith(RobolectricTestRunner::class)
class CompanionAppUtilsTest {

    @Test
    fun testGenerateCompanionAppLaunchIntentUri_defaultNullPackage_returnsEmptyString() {
        assertThat(generateCompanionAppLaunchIntentUri()).isEmpty()
    }

    @Test
    fun testGenerateCompanionAppLaunchIntentUri_emptyPackageName_returnsEmptyString() {
        assertThat(generateCompanionAppLaunchIntentUri(companionAppPackageName = "")).isEmpty()
    }

    @Test
    fun testGenerateCompanionAppLaunchIntentUri_emptyActivityName_returnsEmptyString() {
        val uriString = generateCompanionAppLaunchIntentUri(
                companionAppPackageName = COMPANION_APP_PACKAGE_TEST_CONSTANT, activityName = "")

        assertThat(uriString).isEmpty()
    }

    @Test
    fun testGenerateCompanionAppLaunchIntentUri_emptyAction_returnsNoActionUriString() {
        val uriString = generateCompanionAppLaunchIntentUri(
                companionAppPackageName = COMPANION_APP_PACKAGE_TEST_CONSTANT,
                activityName = COMPANION_APP_ACTIVITY_TEST_CONSTANT,
                action = "")

        assertThat(uriString).doesNotContain("action=")
        assertThat(uriString).contains("package=$COMPANION_APP_PACKAGE_TEST_CONSTANT")
        assertThat(uriString).contains(COMPANION_APP_ACTIVITY_TEST_CONSTANT)
    }

    @Test
    fun testGenerateCompanionAppLaunchIntentUri_nonNullArgs_returnsUriString() {
        val uriString = generateCompanionAppLaunchIntentUri(
                companionAppPackageName = COMPANION_APP_PACKAGE_TEST_CONSTANT,
                activityName = COMPANION_APP_ACTIVITY_TEST_CONSTANT,
                action = COMPANION_APP_ACTION_TEST_CONSTANT)

        assertThat(uriString).isEqualTo("intent:#Intent;" +
                "action=android.nearby.SHOW_WELCOME;" +
                "package=android.nearby.companion;" +
                "component=android.nearby.companion/android.nearby.companion.MainActivity;" +
                "end")
    }

    companion object {
        private const val COMPANION_APP_PACKAGE_TEST_CONSTANT = "android.nearby.companion"
        private const val COMPANION_APP_ACTIVITY_TEST_CONSTANT =
                "android.nearby.companion.MainActivity"
        private const val COMPANION_APP_ACTION_TEST_CONSTANT = "android.nearby.SHOW_WELCOME"
    }
}
