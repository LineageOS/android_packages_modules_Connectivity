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

package com.android.net.module.util;

/**
 * Class to centralize feature version control that requires a specific module or a specific
 * module version.
 * @hide
 */
public class FeatureVersions {
    public static final long MODULE_MASK = 0xFF00_000000000L;
    public static final long VERSION_MASK = 0x0000_FFFFFFFFFL;
    public static final long CONNECTIVITY_MODULE_ID = 0x0100_000000000L;
    public static final long NETWORK_STACK_MODULE_ID = 0x0200_000000000L;
    // CLAT_ADDRESS_TRANSLATE is a feature of the network stack, which doesn't throw when system
    // try to add a NAT-T keepalive packet filter with v6 address, introduced in version
    // M-2023-Sept on July 3rd, 2023.
    public static final long FEATURE_CLAT_ADDRESS_TRANSLATE =
            NETWORK_STACK_MODULE_ID + 340900000L;
}
