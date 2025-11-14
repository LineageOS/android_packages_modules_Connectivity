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
@file:JvmName("VersionUtil")

package com.android.testutils.com.android.testutils

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Value for [getTargetModuleVersion] that indicates that no manifest is available in the test.
 */
const val MODULE_VERSION_NO_MANIFEST = -1

/**
 * Get the version of the module targeted by the current test.
 *
 * <p>This reads the value of the target_module_version resource from the resources of the test
 * package. That resource must be configured in build rules (via "resources" or "resource_zips") for
 * the test.
 */
fun getTargetModuleVersion(): Int {
    val ctx = InstrumentationRegistry.getInstrumentation().context
    val res = ctx.resources
    val resId = res.getIdentifier("target_module_version", "raw", ctx.packageName)
    if (resId == 0) {
        return MODULE_VERSION_NO_MANIFEST
    }
    return res.openRawResource(resId).reader().use {
        it.readText().trim(' ', '\n').toInt()
    }
}
