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

package android.net.thread;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link OperationalDatasetTimestamp}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class OperationalDatasetTimestampTest {
    @Test
    public void fromTlvValue_invalidTimestamp_throwsIllegalArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OperationalDatasetTimestamp.fromTlvValue(new byte[7]));
    }

    @Test
    public void fromTlvValue_goodValue_success() {
        OperationalDatasetTimestamp timestamp =
                OperationalDatasetTimestamp.fromTlvValue(base16().decode("FFEEDDCCBBAA9989"));

        assertThat(timestamp.getSeconds()).isEqualTo(0xFFEEDDCCBBAAL);
        // 0x9989 is 0x4CC4 << 1 + 1
        assertThat(timestamp.getTicks()).isEqualTo(0x4CC4);
        assertThat(timestamp.isAuthoritativeSource()).isTrue();
    }

    @Test
    public void toTlvValue_conversionIsLossLess() {
        OperationalDatasetTimestamp timestamp1 = new OperationalDatasetTimestamp(100L, 10, true);

        OperationalDatasetTimestamp timestamp2 =
                OperationalDatasetTimestamp.fromTlvValue(timestamp1.toTlvValue());

        assertThat(timestamp2).isEqualTo(timestamp1);
    }
}
