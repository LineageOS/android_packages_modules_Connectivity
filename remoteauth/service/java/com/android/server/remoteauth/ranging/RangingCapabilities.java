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
package com.android.server.remoteauth.ranging;

import androidx.annotation.IntDef;

import com.google.common.collect.ImmutableList;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/** The ranging capabilities of the device. */
public class RangingCapabilities {

    /** Possible ranging methods */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                RANGING_METHOD_UNKNOWN,
                RANGING_METHOD_UWB,
            })
    public @interface RangingMethod {}

    /** Unknown ranging method. */
    public static final int RANGING_METHOD_UNKNOWN = 0x0;

    /** Ultra-wideband ranging. */
    public static final int RANGING_METHOD_UWB = 0x1;

    private final ImmutableList<Integer> mSupportedRangingMethods;

    /**
     * Gets the list of supported ranging methods of the device.
     *
     * @return list of {@link RangingMethod}
     */
    public ImmutableList<Integer> getSupportedRangingMethods() {
        return mSupportedRangingMethods;
    }

    private RangingCapabilities(List<Integer> supportedRangingMethods) {
        mSupportedRangingMethods = ImmutableList.copyOf(supportedRangingMethods);
    }

    /** Builder class for {@link RangingCapabilities}. */
    public static final class Builder {
        private List<Integer> mSupportedRangingMethods = new ArrayList<>();

        /** Adds a supported {@link RangingMethod} */
        public Builder addSupportedRangingMethods(@RangingMethod int rangingMethod) {
            mSupportedRangingMethods.add(rangingMethod);
            return this;
        }

        /** Builds {@link RangingCapabilities}. */
        public RangingCapabilities build() {
            return new RangingCapabilities(mSupportedRangingMethods);
        }
    }
}
