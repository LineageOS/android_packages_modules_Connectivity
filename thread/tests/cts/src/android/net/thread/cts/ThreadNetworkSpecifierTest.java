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

package android.net.thread.cts;

import static com.android.net.thread.flags.Flags.FLAG_THREAD_MOBILE_ENABLED;
import static com.android.testutils.ParcelUtils.assertParcelingIsLossless;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;

import android.net.thread.ActiveOperationalDataset;
import android.net.thread.ThreadNetworkSpecifier;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Rule;
import org.junit.Test;

/** Tests for {@link ThreadNetworkSpecifier}. */
@SmallTest
@RequiresFlagsEnabled(FLAG_THREAD_MOBILE_ENABLED)
public final class ThreadNetworkSpecifierTest {
    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset new":
    // Active Timestamp: 1
    // Channel: 13
    // Wake-up Channel: 18
    // Channel Mask: 0x07fff800
    // Ext PAN ID: 492cc112e634e51b
    // Mesh Local Prefix: fd2f:357c:dd90:2120::/64
    // Network Key: 9f55333f52eb25dbfdbe62121b39bee4
    // Network Name: OpenThread-145a
    // PAN ID: 0x145a
    // PSKc: 6d81fe700160ab9f63f50f78ccf0733c
    // Security Policy: 672 onrc 0
    private static final byte[] DATASET_TLVS_1 =
            base16().ignoreCase()
                    .decode(
                            "0e080000000000010000000300000d4a0300001235060004001fff"
                                + "e00208492cc112e634e51b0708fd2f357cdd90212005109f55333f"
                                + "52eb25dbfdbe62121b39bee4030f4f70656e5468726561642d3134"
                                + "35610102145a04106d81fe700160ab9f63f50f78ccf0733c0c0402a0f7f8");
    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset new":
    // Active Timestamp: 1
    // Channel: 24
    // Wake-up Channel: 19
    // Channel Mask: 0x07fff800
    // Ext PAN ID: 779732128edb4591
    // Mesh Local Prefix: fdbd:3671:821d:18c1::/64
    // Network Key: 6a643ec02d38ef55b21b26cec46f622d
    // Network Name: OpenThread-64be
    // PAN ID: 0x64be
    // PSKc: 37968aab079fac83fa67b14c9bb7f598
    // Security Policy: 672 onrc 0
    private static final byte[] DATASET_TLVS_2 =
            base16().ignoreCase()
                    .decode(
                            "0e08000000000001000000030000184a0300001335060004001fff"
                                + "e00208779732128edb45910708fdbd3671821d18c105106a643ec0"
                                + "2d38ef55b21b26cec46f622d030f4f70656e5468726561642d3634"
                                + "6265010264be041037968aab079fac83fa67b14c9bb7f5980c0402a0f7f8");

    private static final ActiveOperationalDataset TEST_DATASET_1 =
            ActiveOperationalDataset.fromThreadTlvs(DATASET_TLVS_1);
    private static final ActiveOperationalDataset TEST_DATASET_2 =
            ActiveOperationalDataset.fromThreadTlvs(DATASET_TLVS_2);

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    public void parcelable_parcelingIsLossLess() {
        ThreadNetworkSpecifier specifier =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(TEST_DATASET_1)
                        .setShouldCreatePartitionIfNotFound(true)
                        .build();
        assertParcelingIsLossless(specifier);
    }

    @Test
    public void builder_correctValuesAreSet() {
        ThreadNetworkSpecifier specifier =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(TEST_DATASET_1)
                        .setShouldCreatePartitionIfNotFound(true)
                        .build();

        assertThat(specifier.getActiveOperationalDataset()).isEqualTo(TEST_DATASET_1);
        assertThat(specifier.shouldCreatePartitionIfNotFound()).isEqualTo(true);
    }

    @Test
    public void builderConstructor_specifiersAreEqual() {
        ThreadNetworkSpecifier specifier1 =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(TEST_DATASET_1)
                        .setShouldCreatePartitionIfNotFound(true)
                        .build();

        ThreadNetworkSpecifier specifier2 = new ThreadNetworkSpecifier.Builder(specifier1).build();

        assertThat(specifier1).isEqualTo(specifier2);
    }

    @Test
    public void equalsTester() {
        new EqualsTester()
                .addEqualityGroup(
                        new ThreadNetworkSpecifier.Builder()
                                .setActiveOperationalDataset(TEST_DATASET_1)
                                .setShouldCreatePartitionIfNotFound(true)
                                .build(),
                        new ThreadNetworkSpecifier.Builder()
                                .setActiveOperationalDataset(TEST_DATASET_1)
                                .setShouldCreatePartitionIfNotFound(true)
                                .build())
                .addEqualityGroup(
                        new ThreadNetworkSpecifier.Builder()
                                .setActiveOperationalDataset(TEST_DATASET_2)
                                .setShouldCreatePartitionIfNotFound(true)
                                .build(),
                        new ThreadNetworkSpecifier.Builder()
                                .setActiveOperationalDataset(TEST_DATASET_2)
                                .setShouldCreatePartitionIfNotFound(true)
                                .build())
                .addEqualityGroup(
                        new ThreadNetworkSpecifier.Builder()
                                .setActiveOperationalDataset(TEST_DATASET_2)
                                .setShouldCreatePartitionIfNotFound(false)
                                .build(),
                        new ThreadNetworkSpecifier.Builder()
                                .setActiveOperationalDataset(TEST_DATASET_2)
                                .setShouldCreatePartitionIfNotFound(false)
                                .build())
                .addEqualityGroup(
                        new ThreadNetworkSpecifier.Builder()
                                .setActiveOperationalDataset(null)
                                .setShouldCreatePartitionIfNotFound(false)
                                .build(),
                        new ThreadNetworkSpecifier.Builder()
                                .setActiveOperationalDataset(null)
                                .setShouldCreatePartitionIfNotFound(false)
                                .build())
                .testEquals();
    }

    @Test
    public void canBeSatisfiedBy_differentDatasetsButTheSameKey_returnsTrue() {
        ActiveOperationalDataset datasetA = TEST_DATASET_1;
        ActiveOperationalDataset datasetB =
                new ActiveOperationalDataset.Builder(TEST_DATASET_2)
                        .setNetworkKey(datasetA.getNetworkKey())
                        .build();

        ThreadNetworkSpecifier specifierA =
                new ThreadNetworkSpecifier.Builder().setActiveOperationalDataset(datasetA).build();
        ThreadNetworkSpecifier specifierB =
                new ThreadNetworkSpecifier.Builder().setActiveOperationalDataset(datasetB).build();

        assertThat(specifierA.canBeSatisfiedBy(specifierB)).isTrue();
    }

    @Test
    public void canBeSatisfiedBy_theSameDatasetsWithOnlyDifferentKey_returnsFalse() {
        ActiveOperationalDataset datasetA =
                new ActiveOperationalDataset.Builder(TEST_DATASET_1)
                        .setNetworkKey(
                                new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
                        .build();
        ActiveOperationalDataset datasetB =
                new ActiveOperationalDataset.Builder(TEST_DATASET_1)
                        .setNetworkKey(
                                new byte[] {15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0})
                        .build();

        ThreadNetworkSpecifier specifierA =
                new ThreadNetworkSpecifier.Builder().setActiveOperationalDataset(datasetA).build();
        ThreadNetworkSpecifier specifierB =
                new ThreadNetworkSpecifier.Builder().setActiveOperationalDataset(datasetB).build();

        assertThat(specifierA.canBeSatisfiedBy(specifierB)).isFalse();
    }

    @Test
    public void canBeSatisfiedBy_theSameDatasetButDifferentCreatePartitionFlag_returnsTrue() {
        ThreadNetworkSpecifier specifierA =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(TEST_DATASET_1)
                        .setShouldCreatePartitionIfNotFound(true)
                        .build();
        ThreadNetworkSpecifier specifierB =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(TEST_DATASET_1)
                        .setShouldCreatePartitionIfNotFound(false)
                        .build();

        assertThat(specifierA.canBeSatisfiedBy(specifierB)).isTrue();
    }

    @Test
    public void canBeSatisfiedBy_nullDataset_matchesAnySpecifier() {
        ThreadNetworkSpecifier specifier =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(null)
                        .setShouldCreatePartitionIfNotFound(true)
                        .build();
        ThreadNetworkSpecifier defaultSpecifier = new ThreadNetworkSpecifier.Builder().build();
        ThreadNetworkSpecifier specifierNullDataset =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(null)
                        .setShouldCreatePartitionIfNotFound(false)
                        .build();
        ThreadNetworkSpecifier specifier2 =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(TEST_DATASET_2)
                        .setShouldCreatePartitionIfNotFound(false)
                        .build();

        assertThat(specifier.canBeSatisfiedBy(defaultSpecifier)).isTrue();
        assertThat(specifier.canBeSatisfiedBy(specifierNullDataset)).isTrue();
        assertThat(specifier.canBeSatisfiedBy(specifier2)).isTrue();
    }

    @Test
    public void redact_locationSensitiveInfoAreRemoved() {
        ThreadNetworkSpecifier specifier =
                new ThreadNetworkSpecifier.Builder()
                        .setActiveOperationalDataset(TEST_DATASET_1)
                        .setShouldCreatePartitionIfNotFound(true)
                        .build();

        ThreadNetworkSpecifier redactedSpecifier =
                (ThreadNetworkSpecifier) specifier.redact(specifier.getApplicableRedactions());
        ActiveOperationalDataset redactedDataset = redactedSpecifier.getActiveOperationalDataset();

        assertThat(redactedDataset.getExtendedPanId()).isEqualTo(new byte[8]);
        assertThat(redactedDataset.getNetworkName()).isEqualTo("UNKNOWN");
        assertThat(redactedDataset.getPanId()).isEqualTo(0);
        assertThat(redactedDataset.getNetworkKey()).isEqualTo(new byte[16]);
        assertThat(redactedDataset.getPskc()).isEqualTo(new byte[16]);
        assertThat(redactedDataset.getUnknownTlvs().size()).isEqualTo(0);
    }
}
