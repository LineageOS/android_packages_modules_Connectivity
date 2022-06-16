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

package com.android.server.nearby.common.bloomfilter;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import junit.framework.TestCase;

import org.junit.Test;

/**
 * Unit-tests for the {@link BloomFilter} class.
 */
public abstract class BloomFilterTest extends TestCase {
    private static final int BYTE_ARRAY_LENGTH = 100;

    private final BloomFilter mBloomFilter =
            new BloomFilter(new byte[BYTE_ARRAY_LENGTH], newHasher());

    public abstract BloomFilter.Hasher newHasher();

    @Test
    public void emptyFilter_returnsEmptyArray() throws Exception {
        assertThat(mBloomFilter.asBytes()).isEqualTo(new byte[BYTE_ARRAY_LENGTH]);
    }

    @Test
    public void emptyFilter_neverContains() throws Exception {
        assertThat(mBloomFilter.possiblyContains(element(1))).isFalse();
        assertThat(mBloomFilter.possiblyContains(element(2))).isFalse();
        assertThat(mBloomFilter.possiblyContains(element(3))).isFalse();
    }


    @Test
    public void add() throws Exception {
        assertThat(mBloomFilter.possiblyContains(element(1))).isFalse();
        mBloomFilter.add(element(1));
        assertThat(mBloomFilter.possiblyContains(element(1))).isTrue();
    }

    @Test
    public void add_onlyGivenArgAdded() throws Exception {
        mBloomFilter.add(element(1));
        assertThat(mBloomFilter.possiblyContains(element(1))).isTrue();
        assertThat(mBloomFilter.possiblyContains(element(2))).isFalse();
        assertThat(mBloomFilter.possiblyContains(element(3))).isFalse();
    }

    @Test
    public void add_multipleArgs() throws Exception {
        mBloomFilter.add(element(1));
        mBloomFilter.add(element(2));
        assertThat(mBloomFilter.possiblyContains(element(1))).isTrue();
        assertThat(mBloomFilter.possiblyContains(element(2))).isTrue();
        assertThat(mBloomFilter.possiblyContains(element(3))).isFalse();
    }

    /**
     * This test was added because of a bug where the BloomFilter doesn't utilize all bits given.
     * Functionally, the filter still works, but we just have a much higher false positive rate. The
     * bug was caused by confusing bit length and byte length, which made our BloomFilter only set
     * bits on the first byteLength (bitLength / 8) bits rather than the whole bitLength bits.
     *
     * <p>Here, we're verifying that the bits set are somewhat scattered. So instead of something
     * like [ 0, 1, 1, 0, 0, 0, 0, ..., 0 ], we should be getting something like
     * [ 0, 1, 0, 0, 1, 1, 0, 0,0, 1, ..., 1, 0].
     */
    @Test
    public void randomness_noEndBias() throws Exception {
        // Add one element to our BloomFilter.
        mBloomFilter.add(element(1));

        // Record the amount of non-zero bytes and the longest streak of zero bytes in the resulting
        // BloomFilter. This is an approximation of reasonable distribution since we're recording by
        // bytes instead of bits.
        int nonZeroCount = 0;
        int longestZeroStreak = 0;
        int currentZeroStreak = 0;
        for (byte b : mBloomFilter.asBytes()) {
            if (b == 0) {
                currentZeroStreak++;
            } else {
                // Increment the number of non-zero bytes we've seen, update the longest zero
                // streak, and then reset the current zero streak.
                nonZeroCount++;
                longestZeroStreak = Math.max(longestZeroStreak, currentZeroStreak);
                currentZeroStreak = 0;
            }
        }
        // Update the longest zero streak again for the tail case.
        longestZeroStreak = Math.max(longestZeroStreak, currentZeroStreak);

        // Since randomness is hard to measure within one unit test, we instead do a valid check.
        // All non-zero bytes should not be packed into one end of the array.
        //
        // In this case, the size of one end is approximated to be:
        //   BYTE_ARRAY_LENGTH / nonZeroCount.
        // Therefore, the longest zero streak should be less than:
        //   BYTE_ARRAY_LENGTH - one end of the array.
        int longestAcceptableZeroStreak = BYTE_ARRAY_LENGTH - (BYTE_ARRAY_LENGTH / nonZeroCount);
        assertThat(longestZeroStreak).isAtMost(longestAcceptableZeroStreak);
    }

    @Test
    public void randomness_falsePositiveRate() throws Exception {
        // Create a new BloomFilter with a length of only 10 bytes.
        BloomFilter bloomFilter = new BloomFilter(new byte[10], newHasher());

        // Add 5 distinct elements to the BloomFilter.
        for (int i = 0; i < 5; i++) {
            bloomFilter.add(element(i));
        }

        // Now test 100 other elements and record the number of false positives.
        int falsePositives = 0;
        for (int i = 5; i < 105; i++) {
            falsePositives += bloomFilter.possiblyContains(element(i)) ? 1 : 0;
        }

        // We expect the false positive rate to be 3% with 5 elements in a 10 byte filter. Thus,
        // we give a little leeway and verify that the false positive rate is no more than 5%.
        assertWithMessage(
                String.format(
                        "False positive rate too large. Expected <= 5%%, but got %d%%.",
                        falsePositives))
                .that(falsePositives <= 5)
                .isTrue();
        System.out.printf("False positive rate: %d%%%n", falsePositives);
    }


    private String element(int index) {
        return "ELEMENT_" + index;
    }

}
