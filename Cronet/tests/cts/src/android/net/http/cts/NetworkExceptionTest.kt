/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.http.cts

import android.net.http.HttpEngine
import android.net.http.NetworkException
import android.net.http.cts.util.TestUrlRequestCallback
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkExceptionTest {

    @Test
    fun testNetworkException_returnsInputParameters() {
        val message = "failed"
        val cause = Throwable("thrown")
        val networkException =
            object : NetworkException(message, cause) {
                override fun getErrorCode() = 0
                override fun isImmediatelyRetryable() = false
            }

        assertEquals(message, networkException.message)
        assertSame(cause, networkException.cause)
    }

    @Test
    fun testNetworkException_thrownFromUrlRequest() {
        val httpEngine = HttpEngine.Builder(ApplicationProvider.getApplicationContext()).build()
        val callback = TestUrlRequestCallback()
        val request =
            httpEngine.newUrlRequestBuilder("http://localhost", callback.executor, callback).build()

        request.start()
        callback.blockForDone()

        assertTrue(request.isDone)
        assertIs<NetworkException>(callback.mError)
        httpEngine.shutdown()
    }
}
