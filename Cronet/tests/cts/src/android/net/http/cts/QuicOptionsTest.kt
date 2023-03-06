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

import android.net.http.QuicOptions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuicOptionsTest {
    @Test
    fun testQuicOptions_defaultValues() {
        val quicOptions = QuicOptions.Builder().build()
        assertThat(quicOptions.quicHostAllowlist).isEmpty()
        assertThat(quicOptions.handshakeUserAgent).isNull()
        // TODO(danstahr): idleConnectionTimeout getter should be public
        // assertThat(quicOptions.idleConnectionTimeout).isNull()
        assertThat(quicOptions.inMemoryServerConfigsCacheSize).isNull()
    }

    @Test
    fun testQuicOptions_quicHostAllowlist_returnsAddedValues() {
        val quicOptions = QuicOptions.Builder()
                .addAllowedQuicHost("foo")
                .addAllowedQuicHost("bar")
                .addAllowedQuicHost("foo")
                .addAllowedQuicHost("baz")
                .build()
        assertThat(quicOptions.quicHostAllowlist)
                .containsExactly("foo", "bar", "baz")
                .inOrder()
    }

    // TODO(danstahr): idleConnectionTimeout getter should be public
    /*
    @Test
    fun testQuicOptions_idleConnectionTimeout_returnsSetValue() {
        val timeout = Duration.ofMinutes(10)
        val quicOptions = QuicOptions.Builder()
                .setIdleConnectionTimeout(timeout)
                .build()
        assertThat(quicOptions.idleConnectionTimeout)
                .isEqualTo(timeout)
    }
    */

    @Test
    fun testQuicOptions_inMemoryServerConfigsCacheSize_returnsSetValue() {
        val quicOptions = QuicOptions.Builder()
                .setInMemoryServerConfigsCacheSize(42)
                .build()
        assertThat(quicOptions.inMemoryServerConfigsCacheSize)
                .isEqualTo(42)
    }
}
