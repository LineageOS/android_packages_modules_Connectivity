/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.connectivity.mdns;

/**
 * The class that contains mDNS feature flags;
 */
public class MdnsFeatureFlags {
    /**
     * The feature flag for control whether the  mDNS offload is enabled or not.
     */
    public static final String NSD_FORCE_DISABLE_MDNS_OFFLOAD = "nsd_force_disable_mdns_offload";

    /**
     * The feature flag for controlling whether the probing question should include
     * InetAddressRecords or not.
     */
    public static final String INCLUDE_INET_ADDRESS_RECORDS_IN_PROBING =
            "include_inet_address_records_in_probing";

    // Flag for offload feature
    public final boolean mIsMdnsOffloadFeatureEnabled;

    // Flag for including InetAddressRecords in probing questions.
    public final boolean mIncludeInetAddressRecordsInProbing;

    /**
     * The constructor for {@link MdnsFeatureFlags}.
     */
    public MdnsFeatureFlags(boolean isOffloadFeatureEnabled,
            boolean includeInetAddressRecordsInProbing) {
        mIsMdnsOffloadFeatureEnabled = isOffloadFeatureEnabled;
        mIncludeInetAddressRecordsInProbing = includeInetAddressRecordsInProbing;
    }


    /** Returns a {@link Builder} for {@link MdnsFeatureFlags}. */
    public static Builder newBuilder() {
        return new Builder();
    }

    /** A builder to create {@link MdnsFeatureFlags}. */
    public static final class Builder {

        private boolean mIsMdnsOffloadFeatureEnabled;
        private boolean mIncludeInetAddressRecordsInProbing;

        /**
         * The constructor for {@link Builder}.
         */
        public Builder() {
            mIsMdnsOffloadFeatureEnabled = false;
            mIncludeInetAddressRecordsInProbing = false;
        }

        /**
         * Set if the mDNS offload  feature is enabled.
         */
        public Builder setIsMdnsOffloadFeatureEnabled(boolean isMdnsOffloadFeatureEnabled) {
            mIsMdnsOffloadFeatureEnabled = isMdnsOffloadFeatureEnabled;
            return this;
        }

        /**
         * Set if the probing question should include InetAddressRecords.
         */
        public Builder setIncludeInetAddressRecordsInProbing(
                boolean includeInetAddressRecordsInProbing) {
            mIncludeInetAddressRecordsInProbing = includeInetAddressRecordsInProbing;
            return this;
        }

        /**
         * Builds a {@link MdnsFeatureFlags} with the arguments supplied to this builder.
         */
        public MdnsFeatureFlags build() {
            return new MdnsFeatureFlags(
                    mIsMdnsOffloadFeatureEnabled, mIncludeInetAddressRecordsInProbing);
        }

    }
}
