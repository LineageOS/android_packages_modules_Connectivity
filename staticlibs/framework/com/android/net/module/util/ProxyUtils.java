/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.net.module.util;

import android.text.TextUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Collection of network common utilities.
 *
 * @hide
 */
public final class ProxyUtils {

    /** Converts exclusion list from String to List. */
    public static List<String> exclusionStringAsList(String exclusionList) {
        if (exclusionList == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(exclusionList.toLowerCase(Locale.ROOT).split(","));
    }

    /** Converts exclusion list from List to string */
    public static String exclusionListAsString(String[] exclusionList) {
        if (exclusionList == null) {
            return "";
        }
        return TextUtils.join(",", exclusionList);
    }
}
