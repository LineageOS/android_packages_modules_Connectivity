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

package com.android.networkstack.tethering;

import com.android.net.module.util.DeviceConfigUtils;

/**
 * The class that contains Tethering feature flags;
 *
 * @hide
 */
public class TetheringFeatureFlags {
    /**
     * A feature flag to control whether the automatic forced choosing upstreams is enabled.
     *
     * This setting is intended to help force-enable the feature on OEM devices that disabled it
     * via resource overlays, and later noticed issues. To that end, it overrides
     * config_tether_upstream_automatic when set to true.
     *
     * This flag is enabled if !=0 and less than the module APEX version: see
     * {@link DeviceConfigUtils#isTetheringFeatureEnabled}. It is also ignored after R, as later
     * devices should just set config_tether_upstream_automatic to true instead.
     */
    public static final String TETHER_FORCE_UPSTREAM_AUTOMATIC_VERSION =
            "tether_force_upstream_automatic_version";

    /**
     * A feature flag to control whether the TETHERING_WEAR is enabled.
     */
    public static final String TETHER_ENABLE_WEAR_TETHERING = "tether_enable_wear_tethering";

    /**
     * A feature flag to control whether the force random prefix base selection is enabled.
     */
    public static final String TETHER_FORCE_RANDOM_PREFIX_BASE_SELECTION =
            "tether_force_random_prefix_base_selection";

    /**
     * A feature flag to control whether the synchronize state machine is enabled.
     */
    public static final String TETHER_ENABLE_SYNC_SM = "tether_enable_sync_sm";

    /**
     * A feature flag to control whether the active sessions metrics should be enabled.
     * Disabled by default.
     */
    public static final String TETHER_ACTIVE_SESSIONS_METRICS = "tether_active_sessions_metrics";

    /**
     * A feature flag to control whether the tethering local network agent should be enabled.
     * Disabled by default.
     */
    public static final String TETHERING_LOCAL_NETWORK_AGENT = "tethering_local_network_agent";

    /**
     * A feature flag to control whether the Wifi P2P Group Owner local network agent
     * should be enabled. Disabled by default. This depends on TETHERING_LOCAL_AGENT
     * and will not be enabled if that flag is off.
     */
    public static final String WIFIP2PGO_LOCAL_NETWORK_AGENT = "wifip2pgo_local_network_agent";
}
