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

import android.util.ArraySet;
import android.util.Range;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.net.module.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class IntegerRangeUtils {
    /**
     * Remove given Integer list from the source Integer range and return a set of ranges.
     * @param source Integer range from which Integer list will be removed
     * @param exempt Integer list to be removed from the source.
     * @return a set of Integer ranges which doesn't have exempted Integer list.
     */
    public static Set<Range<Integer>> rangeWithoutValues(
            @NonNull Range<Integer> source, @Nullable List<Integer> exempt) {
        final Set<Range<Integer>> rangeSet = new ArraySet<>();
        final List<Integer> exemptInRange = exempt != null
                ? CollectionUtils.filter(exempt, source::contains) : null;
        if (exemptInRange == null || exemptInRange.isEmpty()) {
            rangeSet.add(source);
            return rangeSet;
        }
        Collections.sort(exemptInRange);
        int start = source.getLower();
        for (int i = 0; i < exemptInRange.size(); i++) {
            int end = exemptInRange.get(i);
            if (end > start) {
                rangeSet.add(Range.create(start, end - 1));
            }
            start = end + 1;
        }
        if (start <= source.getUpper()) {
            rangeSet.add(Range.create(start, source.getUpper()));
        }
        return rangeSet;
    }
}
