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

package com.android.server.connectivity;

import static org.junit.Assert.assertEquals;

import android.os.Build;
import android.util.Range;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Set;

/**
 * Tests for IntegerRangeUtils.
 */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public class IntegerRangeUtilsTest {
    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
    public void testRangeWithoutValues() {
        final Range<Integer> source1 = Range.create(100, 200);
        // Basic separation after excluding exempt Integer list.
        final List<Integer> exempt1 = List.of(110, 170);
        final Set<Range<Integer>> expected1 = Set.of(
                Range.create(100, 109),
                Range.create(111, 169),
                Range.create(171, 200));
        // Check continuous exempt list.
        final List<Integer> exempt2 = List.of(110, 111, 112, 114);
        final Set<Range<Integer>> expected2 = Set.of(
                Range.create(100, 109),
                Range.create(113, 113),
                Range.create(115, 200));
        // Check handling exempted Integer on the edge of range.
        final List<Integer> exempt3 = List.of(100, 199, 200);
        final Set<Range<Integer>> expected3 = Set.of(
                Range.create(101, 198)
        );
        final List<Integer> exempt4 = List.of(100, 101, 102, 200);
        final Set<Range<Integer>> expected4 = Set.of(
                Range.create(103, 199)
        );
        // Check handling exempted Integer list out of the source range.
        final List<Integer> exempt5 = List.of(99, 201);
        final Set<Range<Integer>> expected5 = Set.of(
                Range.create(100, 200)
        );
        final List<Integer> exempt6 = List.of(99, 150, 200, 201);
        final Set<Range<Integer>> expected6 = Set.of(
                Range.create(100, 149),
                Range.create(151, 199)
        );
        // Check handling unordered exempted Integer list.
        final List<Integer> exempt7 = List.of(200, 99, 201, 150, 117);
        final Set<Range<Integer>> expected7 = Set.of(
                Range.create(100, 116),
                Range.create(118, 149),
                Range.create(151, 199)
        );
        assertEquals(expected1, IntegerRangeUtils.rangeWithoutValues(source1, exempt1));
        assertEquals(expected2, IntegerRangeUtils.rangeWithoutValues(source1, exempt2));
        assertEquals(expected3, IntegerRangeUtils.rangeWithoutValues(source1, exempt3));
        assertEquals(expected4, IntegerRangeUtils.rangeWithoutValues(source1, exempt4));
        assertEquals(expected5, IntegerRangeUtils.rangeWithoutValues(source1, exempt5));
        assertEquals(expected6, IntegerRangeUtils.rangeWithoutValues(source1, exempt6));
        assertEquals(expected7, IntegerRangeUtils.rangeWithoutValues(source1, exempt7));
        // Check the case that source range is included in exempted list.
        final Range<Integer> source2 = Range.create(100, 101);
        final List<Integer> exempt8 = List.of(99, 100, 101);
        final Set<Range<Integer>> expected8 = Set.of();
        assertEquals(expected8, IntegerRangeUtils.rangeWithoutValues(source2, exempt8));
    }
}
