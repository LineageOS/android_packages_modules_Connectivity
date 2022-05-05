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

package com.android.testutils;

import android.text.TextUtils;
import android.util.Pair;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for device information.
 */
public class DeviceInfoUtils {
    private static Pair<Integer, Integer> getVersionFromString(String version) {
        // Only gets major and minor number of the version string.
        final Pattern versionPattern = Pattern.compile("^(\\d+)(\\.(\\d+))?.*");
        final Matcher m = versionPattern.matcher(version);
        if (m.matches()) {
            final int major = Integer.parseInt(m.group(1));
            final int minor = TextUtils.isEmpty(m.group(3)) ? 0 : Integer.parseInt(m.group(3));
            return new Pair<>(major, minor);
        } else {
            return new Pair<>(0, 0);
        }
    }

    /**
     * Compares two version strings numerically. Compare only major and minor number of the
     * version string. The version comparison uses #Integer.compare. Possible version
     * 5, 5.10, 5-beta1, 4.8-RC1, 4.7.10.10 and so on.
     *
     * @param  s1 the first version string to compare
     * @param  s2 the second version string to compare
     * @return the value 0 if s1 == s2;
     *         a value less than 0 if s1 < s2 and
     *         a value greater than 0 if s1 > s2.
     */
    public static int compareMajorMinorVersion(final String s1, final String s2) {
        final Pair<Integer, Integer> v1 = getVersionFromString(s1);
        final Pair<Integer, Integer> v2 = getVersionFromString(s2);

        if (v1.first == v2.first) {
            return Integer.compare(v1.second, v2.second);
        } else {
            return Integer.compare(v1.first, v2.first);
        }
    }
}
