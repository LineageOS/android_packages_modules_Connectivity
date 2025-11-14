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

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * The class that contains mDNS feature flags;
 */
public class MdnsFeatureFlags {
    /**
     * A feature flag to control whether the mDNS offload is enabled or not.
     */
    public static final String NSD_FORCE_DISABLE_MDNS_OFFLOAD = "nsd_force_disable_mdns_offload";

    /**
     * A feature flag to control whether the probing question should include
     * InetAddressRecords or not.
     */
    public static final String INCLUDE_INET_ADDRESS_RECORDS_IN_PROBING =
            "include_inet_address_records_in_probing";
    /**
     * A feature flag to control whether expired services removal should be enabled.
     */
    public static final String NSD_EXPIRED_SERVICES_REMOVAL =
            "nsd_expired_services_removal";

    /**
     * A feature flag to control whether the label count limit should be enabled.
     */
    public static final String NSD_LIMIT_LABEL_COUNT = "nsd_limit_label_count";

    /**
     * A feature flag to control whether the known-answer suppression should be enabled.
     */
    public static final String NSD_KNOWN_ANSWER_SUPPRESSION = "nsd_known_answer_suppression";

    /**
     * A feature flag to control whether unicast replies should be enabled.
     *
     * <p>Enabling this feature causes replies to queries with the Query Unicast (QU) flag set to be
     * sent unicast instead of multicast, as per RFC6762 5.4.
     */
    public static final String NSD_UNICAST_REPLY_ENABLED = "nsd_unicast_reply_enabled";

    /**
     * A feature flag to control whether the aggressive query mode should be enabled.
     */
    public static final String NSD_AGGRESSIVE_QUERY_MODE = "nsd_aggressive_query_mode";

    /**
     * A feature flag to control whether the query with known-answer should be enabled.
     */
    public static final String NSD_QUERY_WITH_KNOWN_ANSWER = "nsd_query_with_known_answer";

    /**
     * A feature flag to avoid advertising empty TXT records, as per RFC 6763 6.1.
     */
    public static final String NSD_AVOID_ADVERTISING_EMPTY_TXT_RECORDS =
            "nsd_avoid_advertising_empty_txt_records";

    /**
     * A feature flag to control whether the cached services removal should be enabled.
     * The removal will be triggered if the retention time has elapsed after all listeners have been
     * unregistered from the service type client or the interface has been destroyed.
     */
    public static final String NSD_CACHED_SERVICES_REMOVAL = "nsd_cached_services_removal";

    /**
     * A feature flag to control whether to use shorter (16 characters + .local) hostnames, instead
     * of Android_[32 characters] hostnames.
     */
    public static final String NSD_USE_SHORT_HOSTNAMES = "nsd_use_short_hostnames";

    /**
     * A feature flag to control whether to advertise the temporary IPv6 addresses should be
     */
    public static final String NSD_IGNORE_TEMPORARY_IPV6_ADDRESSES =
            "nsd_ignore_temporary_ipv6_addresses";

    /**

     * A feature flag to control whether to only flush address records when another address record
     * with the same name, rrtype and rrclass is received (as per RFC6762 10.2), instead of flushing
     * all address records (A and AAAA) for the same name only.
     */
    public static final String NSD_CACHE_FLUSH_PER_ADDRESS_TYPE =
            "nsd_cache_flush_per_address_type";

    /**
     * A feature flag to control the retention time for cached services.
     *
     * <p> Making the retention time configurable allows for testing and future adjustments.
     */
    public static final String NSD_CACHED_SERVICES_RETENTION_TIME =
            "nsd_cached_services_retention_time";
    public static final int DEFAULT_CACHED_SERVICES_RETENTION_TIME_MILLISECONDS = 10000;

    /**
     * Tag indicating that socket tagging should not be done.
     *
     * <p>Corresponds to the same value TrafficStats uses to for "no tag".
     */
    public static final int MDNS_SOCKET_THREAD_STATS_TAG_NONE = -1;

    /**
     * A feature flag to control whether the accurate delay callback should be enabled.
     */
    public static final String NSD_ACCURATE_DELAY_CALLBACK = "nsd_accurate_delay_callback";

    /**
     * A feature flag to control whether the optimized expired service removal should be enabled.
     */
    public static final String NSD_OPTIMIZED_EXPIRED_SERVICE_REMOVAL =
            "nsd_optimized_expired_service_removal";

    // Flag for offload feature
    public final boolean mIsMdnsOffloadFeatureEnabled;

    // Flag for including InetAddressRecords in probing questions.
    public final boolean mIncludeInetAddressRecordsInProbing;

    // Flag for expired services removal
    public final boolean mIsExpiredServicesRemovalEnabled;

    // Flag for label count limit
    public final boolean mIsLabelCountLimitEnabled;

    // Flag for known-answer suppression
    public final boolean mIsKnownAnswerSuppressionEnabled;

    // Flag to enable replying unicast to queries requesting unicast replies
    public final boolean mIsUnicastReplyEnabled;

    // Flag for aggressive query mode
    public final boolean mIsAggressiveQueryModeEnabled;

    // Flag for query with known-answer
    public final boolean mIsQueryWithKnownAnswerEnabled;

    // Flag for avoiding advertising empty TXT records
    public final boolean mAvoidAdvertisingEmptyTxtRecords;

    // Flag for cached services removal
    public final boolean mIsCachedServicesRemovalEnabled;

    // Retention Time for cached services
    public final long mCachedServicesRetentionTime;

    // Flag for accurate delay callback
    public final boolean mIsAccurateDelayCallbackEnabled;

    // Flag to use shorter (16 characters + .local) hostnames
    public final boolean mIsShortHostnamesEnabled;

    // Flag to enable guessing the Network of received packets in the legacy MdnsSocketClient.
    public final boolean mIsSocketClientNetworkGuessingEnabled;

    // Flag to only flush address records when another address record with the same name, rrtype and
    // rrclass is received
    public final boolean mIsCacheFlushPerAddressTypeEnabled;

    // Flag for optimized expired service removal
    public final boolean mIsOptimizedExpiredServiceRemovalEnabled;

    // Flag for ignoring temporary IPv6 addresses in advertising
    public final boolean mIsIgnoreTemporaryIPv6AddressesEnabled;

    // Thread stats tag for MdnsSocketClient
    public final int mMdnsSocketThreadStatsTag;

    @Nullable
    private final FlagOverrideProvider mOverrideProvider;

    /**
     * A provider that can indicate whether a flag should be force-enabled for testing purposes.
     */
    public interface FlagOverrideProvider {
        /**
         * Indicates whether the flag should be force-enabled for testing purposes.
         */
        boolean isForceEnabledForTest(@NonNull String flag);


        /**
         * Get the int value of the flag for testing purposes.
         */
        int getIntValueForTest(@NonNull String flag, int defaultValue);
    }

    /**
     * Indicates whether the flag should be force-enabled for testing purposes.
     */
    private boolean isForceEnabledForTest(@NonNull String flag) {
        return mOverrideProvider != null && mOverrideProvider.isForceEnabledForTest(flag);
    }

    /**
     * Get the int value of the flag for testing purposes.
     *
     * @return the test int value, or given default value if it is unset or the OverrideProvider
     * doesn't exist.
     */
    private int getIntValueForTest(@NonNull String flag, int defaultValue) {
        if (mOverrideProvider == null) {
            return defaultValue;
        }
        return mOverrideProvider.getIntValueForTest(flag, defaultValue);
    }

    /**
     * Indicates whether {@link #NSD_UNICAST_REPLY_ENABLED} is enabled, including for testing.
     */
    public boolean isUnicastReplyEnabled() {
        return mIsUnicastReplyEnabled || isForceEnabledForTest(NSD_UNICAST_REPLY_ENABLED);
    }

    /**
     * Indicates whether {@link #NSD_AGGRESSIVE_QUERY_MODE} is enabled, including for testing.
     */
    public boolean isAggressiveQueryModeEnabled() {
        return mIsAggressiveQueryModeEnabled || isForceEnabledForTest(NSD_AGGRESSIVE_QUERY_MODE);
    }

    /**
     * Indicates whether {@link #NSD_KNOWN_ANSWER_SUPPRESSION} is enabled, including for testing.
     */
    public boolean isKnownAnswerSuppressionEnabled() {
        return mIsKnownAnswerSuppressionEnabled
                || isForceEnabledForTest(NSD_KNOWN_ANSWER_SUPPRESSION);
    }

    /**
     * Indicates whether {@link #NSD_QUERY_WITH_KNOWN_ANSWER} is enabled, including for testing.
     */
    public boolean isQueryWithKnownAnswerEnabled() {
        return mIsQueryWithKnownAnswerEnabled
                || isForceEnabledForTest(NSD_QUERY_WITH_KNOWN_ANSWER);
    }

    /**
     * Indicates whether {@link #NSD_AVOID_ADVERTISING_EMPTY_TXT_RECORDS} is enabled, including for
     * testing.
     */
    public boolean avoidAdvertisingEmptyTxtRecords() {
        return mAvoidAdvertisingEmptyTxtRecords
                || isForceEnabledForTest(NSD_AVOID_ADVERTISING_EMPTY_TXT_RECORDS);
    }

    /**
     * Indicates whether {@link #NSD_CACHED_SERVICES_REMOVAL} is enabled, including for testing.
     */
    public boolean isCachedServicesRemovalEnabled() {
        return mIsCachedServicesRemovalEnabled
                || isForceEnabledForTest(NSD_CACHED_SERVICES_REMOVAL);
    }

    /**
     * Get the value which is set to {@link #NSD_CACHED_SERVICES_RETENTION_TIME}, including for
     * testing.
     */
    public long getCachedServicesRetentionTime() {
        return getIntValueForTest(
                NSD_CACHED_SERVICES_RETENTION_TIME, (int) mCachedServicesRetentionTime);
    }

    public boolean isShortHostnamesEnabled() {
        return mIsShortHostnamesEnabled || isForceEnabledForTest(NSD_USE_SHORT_HOSTNAMES);
    }

    /**
     * Indicates whether {@link #NSD_ACCURATE_DELAY_CALLBACK} is enabled, including for testing.
     */
    public boolean isAccurateDelayCallbackEnabled() {
        return mIsAccurateDelayCallbackEnabled
                || isForceEnabledForTest(NSD_ACCURATE_DELAY_CALLBACK);
    }

    /**
     * Indicates whether {@link #NSD_OPTIMIZED_EXPIRED_SERVICE_REMOVAL} is enabled, including for
     * testing.
     */
    public boolean isOptimizedExpiredServiceRemovalEnabled() {
        return mIsOptimizedExpiredServiceRemovalEnabled
                || isForceEnabledForTest(NSD_OPTIMIZED_EXPIRED_SERVICE_REMOVAL);
    }

    /**
     * The constructor for {@link MdnsFeatureFlags}.
     */
    public MdnsFeatureFlags(boolean isOffloadFeatureEnabled,
            boolean includeInetAddressRecordsInProbing,
            boolean isExpiredServicesRemovalEnabled,
            boolean isLabelCountLimitEnabled,
            boolean isKnownAnswerSuppressionEnabled,
            boolean isUnicastReplyEnabled,
            boolean isAggressiveQueryModeEnabled,
            boolean isQueryWithKnownAnswerEnabled,
            boolean avoidAdvertisingEmptyTxtRecords,
            boolean isCachedServicesRemovalEnabled,
            long cachedServicesRetentionTime,
            boolean isAccurateDelayCallbackEnabled,
            boolean isShortHostnamesEnabled,
            boolean isSocketClientNetworkGuessingEnabled,
            boolean isCacheFlushPerAddressTypeEnabled,
            boolean isOptimizedExpiredServiceRemovalEnabled,
            boolean isIgnoreTemporaryIPv6AddressesEnabled,
            int mdnsSocketThreadStatsTag,
            @Nullable FlagOverrideProvider overrideProvider) {
        mIsMdnsOffloadFeatureEnabled = isOffloadFeatureEnabled;
        mIncludeInetAddressRecordsInProbing = includeInetAddressRecordsInProbing;
        mIsExpiredServicesRemovalEnabled = isExpiredServicesRemovalEnabled;
        mIsLabelCountLimitEnabled = isLabelCountLimitEnabled;
        mIsKnownAnswerSuppressionEnabled = isKnownAnswerSuppressionEnabled;
        mIsUnicastReplyEnabled = isUnicastReplyEnabled;
        mIsAggressiveQueryModeEnabled = isAggressiveQueryModeEnabled;
        mIsQueryWithKnownAnswerEnabled = isQueryWithKnownAnswerEnabled;
        mAvoidAdvertisingEmptyTxtRecords = avoidAdvertisingEmptyTxtRecords;
        mIsCachedServicesRemovalEnabled = isCachedServicesRemovalEnabled;
        mCachedServicesRetentionTime = cachedServicesRetentionTime;
        mIsAccurateDelayCallbackEnabled = isAccurateDelayCallbackEnabled;
        mIsShortHostnamesEnabled = isShortHostnamesEnabled;
        mIsSocketClientNetworkGuessingEnabled = isSocketClientNetworkGuessingEnabled;
        mIsCacheFlushPerAddressTypeEnabled = isCacheFlushPerAddressTypeEnabled;
        mIsOptimizedExpiredServiceRemovalEnabled = isOptimizedExpiredServiceRemovalEnabled;
        mMdnsSocketThreadStatsTag = mdnsSocketThreadStatsTag;
        mIsIgnoreTemporaryIPv6AddressesEnabled = isIgnoreTemporaryIPv6AddressesEnabled;
        mOverrideProvider = overrideProvider;
    }


    /** Returns a {@link Builder} for {@link MdnsFeatureFlags}. */
    public static Builder newBuilder() {
        return new Builder();
    }

    /** A builder to create {@link MdnsFeatureFlags}. */
    public static final class Builder {

        private boolean mIsMdnsOffloadFeatureEnabled;
        private boolean mIncludeInetAddressRecordsInProbing;
        private boolean mIsExpiredServicesRemovalEnabled;
        private boolean mIsLabelCountLimitEnabled;
        private boolean mIsKnownAnswerSuppressionEnabled;
        private boolean mIsUnicastReplyEnabled;
        private boolean mIsAggressiveQueryModeEnabled;
        private boolean mIsQueryWithKnownAnswerEnabled;
        private boolean mAvoidAdvertisingEmptyTxtRecords;
        private boolean mIsCachedServicesRemovalEnabled;
        private long mCachedServicesRetentionTime;
        private boolean mIsAccurateDelayCallbackEnabled;
        private boolean mIsShortHostnamesEnabled;
        private boolean mIsSocketClientNetworkGuessingEnabled;
        private boolean mIsCacheFlushPerAddressTypeEnabled;
        private boolean mIsOptimizedExpiredServiceRemovalEnabled;
        private boolean mIsIgnoreTemporaryIPv6AddressesEnabled;
        private int mMdnsSocketThreadStatsTag;
        private FlagOverrideProvider mOverrideProvider;

        /**
         * The constructor for {@link Builder}.
         */
        public Builder() {
            mIsMdnsOffloadFeatureEnabled = false;
            mIncludeInetAddressRecordsInProbing = false;
            mIsExpiredServicesRemovalEnabled = true; // Default enabled.
            mIsLabelCountLimitEnabled = true; // Default enabled.
            mIsKnownAnswerSuppressionEnabled = true; // Default enabled.
            mIsUnicastReplyEnabled = true; // Default enabled.
            mIsAggressiveQueryModeEnabled = false;
            mIsQueryWithKnownAnswerEnabled = false;
            mAvoidAdvertisingEmptyTxtRecords = true; // Default enabled.
            mIsCachedServicesRemovalEnabled = true; // Default enabled.
            mCachedServicesRetentionTime = DEFAULT_CACHED_SERVICES_RETENTION_TIME_MILLISECONDS;
            mIsAccurateDelayCallbackEnabled = false;
            mIsShortHostnamesEnabled = true; // Default enabled.
            mIsSocketClientNetworkGuessingEnabled = false;
            mIsCacheFlushPerAddressTypeEnabled = true; // Default enabled.
            mIsOptimizedExpiredServiceRemovalEnabled = false;
            mIsIgnoreTemporaryIPv6AddressesEnabled = true; // Default enabled.
            mMdnsSocketThreadStatsTag = MDNS_SOCKET_THREAD_STATS_TAG_NONE;
            mOverrideProvider = null;
        }

        /**
         * Set whether the temporary IPv6 addresses should be ignored.
         *
         * @see #NSD_IGNORE_TEMPORARY_IPV6_ADDRESSES
         */
        public Builder setIsIgnoreTemporaryIPv6AddressesEnabled(
                boolean isIgnoreTemporaryIPv6AddressesEnabled) {
            mIsIgnoreTemporaryIPv6AddressesEnabled = isIgnoreTemporaryIPv6AddressesEnabled;
            return this;
        }

        /**
         * Set whether the mDNS offload feature is enabled.
         *
         * @see #NSD_FORCE_DISABLE_MDNS_OFFLOAD
         */
        public Builder setIsMdnsOffloadFeatureEnabled(boolean isMdnsOffloadFeatureEnabled) {
            mIsMdnsOffloadFeatureEnabled = isMdnsOffloadFeatureEnabled;
            return this;
        }

        /**
         * Set whether the probing question should include InetAddressRecords.
         *
         * @see #INCLUDE_INET_ADDRESS_RECORDS_IN_PROBING
         */
        public Builder setIncludeInetAddressRecordsInProbing(
                boolean includeInetAddressRecordsInProbing) {
            mIncludeInetAddressRecordsInProbing = includeInetAddressRecordsInProbing;
            return this;
        }

        /**
         * Set whether the expired services removal is enabled.
         *
         * @see #NSD_EXPIRED_SERVICES_REMOVAL
         */
        public Builder setIsExpiredServicesRemovalEnabled(boolean isExpiredServicesRemovalEnabled) {
            mIsExpiredServicesRemovalEnabled = isExpiredServicesRemovalEnabled;
            return this;
        }

        /**
         * Set whether the label count limit is enabled.
         *
         * @see #NSD_LIMIT_LABEL_COUNT
         */
        public Builder setIsLabelCountLimitEnabled(boolean isLabelCountLimitEnabled) {
            mIsLabelCountLimitEnabled = isLabelCountLimitEnabled;
            return this;
        }

        /**
         * Set whether the known-answer suppression is enabled.
         *
         * @see #NSD_KNOWN_ANSWER_SUPPRESSION
         */
        public Builder setIsKnownAnswerSuppressionEnabled(boolean isKnownAnswerSuppressionEnabled) {
            mIsKnownAnswerSuppressionEnabled = isKnownAnswerSuppressionEnabled;
            return this;
        }

        /**
         * Set whether the unicast reply feature is enabled.
         *
         * @see #NSD_UNICAST_REPLY_ENABLED
         */
        public Builder setIsUnicastReplyEnabled(boolean isUnicastReplyEnabled) {
            mIsUnicastReplyEnabled = isUnicastReplyEnabled;
            return this;
        }

        /**
         * Set a {@link FlagOverrideProvider} to be used by {@link #isForceEnabledForTest(String)}.
         *
         * If non-null, features that use {@link #isForceEnabledForTest(String)} will use that
         * provider to query whether the flag should be force-enabled.
         */
        public Builder setOverrideProvider(@Nullable FlagOverrideProvider overrideProvider) {
            mOverrideProvider = overrideProvider;
            return this;
        }

        /**
         * Set whether the aggressive query mode is enabled.
         *
         * @see #NSD_AGGRESSIVE_QUERY_MODE
         */
        public Builder setIsAggressiveQueryModeEnabled(boolean isAggressiveQueryModeEnabled) {
            mIsAggressiveQueryModeEnabled = isAggressiveQueryModeEnabled;
            return this;
        }

        /**
         * Set whether the query with known-answer is enabled.
         *
         * @see #NSD_QUERY_WITH_KNOWN_ANSWER
         */
        public Builder setIsQueryWithKnownAnswerEnabled(boolean isQueryWithKnownAnswerEnabled) {
            mIsQueryWithKnownAnswerEnabled = isQueryWithKnownAnswerEnabled;
            return this;
        }

        /**
         * Set whether to avoid advertising empty TXT records.
         *
         * @see #NSD_AVOID_ADVERTISING_EMPTY_TXT_RECORDS
         */
        public Builder setAvoidAdvertisingEmptyTxtRecords(boolean avoidAdvertisingEmptyTxtRecords) {
            mAvoidAdvertisingEmptyTxtRecords = avoidAdvertisingEmptyTxtRecords;
            return this;
        }

        /**
         * Set whether the cached services removal is enabled.
         *
         * @see #NSD_CACHED_SERVICES_REMOVAL
         */
        public Builder setIsCachedServicesRemovalEnabled(boolean isCachedServicesRemovalEnabled) {
            mIsCachedServicesRemovalEnabled = isCachedServicesRemovalEnabled;
            return this;
        }

        /**
         * Set cached services retention time.
         *
         * @see #NSD_CACHED_SERVICES_RETENTION_TIME
         */
        public Builder setCachedServicesRetentionTime(long cachedServicesRetentionTime) {
            mCachedServicesRetentionTime = cachedServicesRetentionTime;
            return this;
        }

        /**
         * Set whether the accurate delay callback is enabled.
         *
         * @see #NSD_ACCURATE_DELAY_CALLBACK
         */
        public Builder setIsAccurateDelayCallbackEnabled(boolean isAccurateDelayCallbackEnabled) {
            mIsAccurateDelayCallbackEnabled = isAccurateDelayCallbackEnabled;
            return this;
        }

        /**
         * Set whether the short hostnames feature is enabled.
         *
         * @see #NSD_USE_SHORT_HOSTNAMES
         */
        public Builder setIsShortHostnamesEnabled(boolean isShortHostnamesEnabled) {
            mIsShortHostnamesEnabled = isShortHostnamesEnabled;
            return this;
        }

        /**
         * Set whether MdnsSocketClient should try to guess the Network of received packets.
         */
        public Builder setIsSocketClientNetworkGuessingEnabled(
                boolean isSocketClientNetworkGuessingEnabled) {
            mIsSocketClientNetworkGuessingEnabled = isSocketClientNetworkGuessingEnabled;
            return this;
        }

        /**
         * Set whether the cache flush per address type is enabled.
         *
         * @see #NSD_CACHE_FLUSH_PER_ADDRESS_TYPE
         */
        public Builder setIsCacheFlushPerAddressTypeEnabled(
                boolean isCacheFlushPerAddressTypeEnabled) {
            mIsCacheFlushPerAddressTypeEnabled = isCacheFlushPerAddressTypeEnabled;
            return this;
        }

        /**
         * Set whether the optimized expired service removal is enabled.
         *
         * @see #NSD_OPTIMIZED_EXPIRED_SERVICE_REMOVAL
         */
        public Builder setIsOptimizedExpiredServiceRemovalEnabled(
                boolean isOptimizedExpiredServiceRemovalEnabled) {
            mIsOptimizedExpiredServiceRemovalEnabled = isOptimizedExpiredServiceRemovalEnabled;
            return this;
        }

        /**
         * Set the thread stats tag to use in {@link MdnsSocketClient}.
         */
        public Builder setMdnsSocketThreadStatsTag(int mdnsSocketThreadStatsTag) {
            mMdnsSocketThreadStatsTag = mdnsSocketThreadStatsTag;
            return this;
        }

        /**
         * Builds a {@link MdnsFeatureFlags} with the arguments supplied to this builder.
         */
        public MdnsFeatureFlags build() {
            return new MdnsFeatureFlags(mIsMdnsOffloadFeatureEnabled,
                    mIncludeInetAddressRecordsInProbing,
                    mIsExpiredServicesRemovalEnabled,
                    mIsLabelCountLimitEnabled,
                    mIsKnownAnswerSuppressionEnabled,
                    mIsUnicastReplyEnabled,
                    mIsAggressiveQueryModeEnabled,
                    mIsQueryWithKnownAnswerEnabled,
                    mAvoidAdvertisingEmptyTxtRecords,
                    mIsCachedServicesRemovalEnabled,
                    mCachedServicesRetentionTime,
                    mIsAccurateDelayCallbackEnabled,
                    mIsShortHostnamesEnabled,
                    mIsSocketClientNetworkGuessingEnabled,
                    mIsCacheFlushPerAddressTypeEnabled,
                    mIsOptimizedExpiredServiceRemovalEnabled,
                    mIsIgnoreTemporaryIPv6AddressesEnabled,
                    mMdnsSocketThreadStatsTag,
                    mOverrideProvider);
        }
    }
}
