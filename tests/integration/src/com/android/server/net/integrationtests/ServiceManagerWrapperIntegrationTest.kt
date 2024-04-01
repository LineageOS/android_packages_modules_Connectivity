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
 * limitations under the License
 */

package com.android.server.net.integrationtests

import android.content.Context
import android.os.Build
import com.android.server.ServiceManagerWrapper
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Integration tests for {@link ServiceManagerWrapper}. */
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.S)
@ConnectivityModuleTest
class ServiceManagerWrapperIntegrationTest {
    @Test
    fun testWaitForService_successFullyRetrievesConnectivityServiceBinder() {
        assertNotNull(ServiceManagerWrapper.waitForService(Context.CONNECTIVITY_SERVICE))
    }
}
