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

import android.annotation.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * API configuration parameters for advertising the mDNS service.
 *
 * <p>Use {@link MdnsAdvertisingOptions.Builder} to create {@link MdnsAdvertisingOptions}.
 *
 * @hide
 */
public class MdnsAdvertisingOptions {

    private static MdnsAdvertisingOptions sDefaultOptions;
    private final boolean mIsOnlyUpdate;
    @Nullable
    private final Duration mTtl;
    private final boolean mSkipProbing;
    private final boolean mSkipSubtypeAnnouncements;
    private final boolean mIsOffloadOnly;

    /**
     * Parcelable constructs for a {@link MdnsAdvertisingOptions}.
     */
    MdnsAdvertisingOptions(boolean isOnlyUpdate, @Nullable Duration ttl, boolean skipProbing,
            boolean skipSubtypeAnnouncements, boolean isOffloadOnly) {
        this.mIsOnlyUpdate = isOnlyUpdate;
        this.mTtl = ttl;
        this.mSkipProbing = skipProbing;
        this.mSkipSubtypeAnnouncements = skipSubtypeAnnouncements;
        this.mIsOffloadOnly = isOffloadOnly;
    }

    /**
     * Returns a {@link Builder} for {@link MdnsAdvertisingOptions}.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Returns a default search options.
     */
    public static synchronized MdnsAdvertisingOptions getDefaultOptions() {
        if (sDefaultOptions == null) {
            sDefaultOptions = newBuilder().build();
        }
        return sDefaultOptions;
    }

    /**
     * @return {@code true} if the advertising request is an update request.
     */
    public boolean isOnlyUpdate() {
        return mIsOnlyUpdate;
    }

    /**
     * @return {@code true} if the probing step should be skipped.
     */
    public boolean skipProbing() {
        return mSkipProbing;
    }

    /**
     * @return {@code true} if this request is an offload only request.
     */
    public boolean isOffloadOnly() {
        return mIsOffloadOnly;
    }

    /**
     * Returns the TTL for all records in a service.
     */
    @Nullable
    public Duration getTtl() {
        return mTtl;
    }

    /**
     * Returns {@code true} if subtype announcements should be skipped.
     */
    public boolean skipSubtypeAnnouncements() {
        return mSkipSubtypeAnnouncements;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof MdnsAdvertisingOptions)) {
            return false;
        } else {
            final MdnsAdvertisingOptions otherOptions = (MdnsAdvertisingOptions) other;
            return mIsOnlyUpdate == otherOptions.mIsOnlyUpdate
                    && mSkipProbing == otherOptions.mSkipProbing
                    && mSkipSubtypeAnnouncements == otherOptions.mSkipSubtypeAnnouncements
                    && mIsOffloadOnly == otherOptions.mIsOffloadOnly
                    && Objects.equals(mTtl, otherOptions.mTtl);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsOnlyUpdate, mTtl, mSkipProbing, mSkipSubtypeAnnouncements,
                mIsOffloadOnly);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("MdnsAdvertisingOptions{");
        if (mIsOnlyUpdate) {
            sb.append(" isOnlyUpdate");
        }
        if (mSkipProbing) {
            sb.append(" skipProbing");
        }
        if (mSkipSubtypeAnnouncements) {
            sb.append(" skipSubtypeAnnouncements");
        }
        if (mTtl != null) {
            sb.append(" ttl=");
            sb.append(mTtl);
        }
        if (mIsOffloadOnly) {
            sb.append(" isOffloadOnly");
        }
        sb.append(" }");
        return sb.toString();
    }

    /**
     * A builder to create {@link MdnsAdvertisingOptions}.
     */
    public static final class Builder {
        private boolean mIsOnlyUpdate = false;
        private boolean mSkipProbing = false;
        private boolean mSkipSubtypeAnnouncements = false;
        private boolean mIsOffloadOnly = false;
        @Nullable
        private Duration mTtl;

        private Builder() {
        }

        /**
         * Sets if the advertising request is an update request.
         */
        public Builder setIsOnlyUpdate(boolean isOnlyUpdate) {
            this.mIsOnlyUpdate = isOnlyUpdate;
            return this;
        }

        /**
         * Sets the TTL duration for all records of the service.
         */
        public Builder setTtl(@Nullable Duration ttl) {
            this.mTtl = ttl;
            return this;
        }

        /**
         * Sets whether to skip the probing step.
         */
        public Builder setSkipProbing(boolean skipProbing) {
            this.mSkipProbing = skipProbing;
            return this;
        }

        /**
         * Sets whether to skip subtype announcements.
         */
        public Builder setSkipSubtypeAnnouncements(boolean skipSubtypeAnnouncements) {
            this.mSkipSubtypeAnnouncements = skipSubtypeAnnouncements;
            return this;
        }

        /**
         * Sets whether the request is offload only request or not.
         */
        public Builder setOffloadOnly(boolean isOffloadOnly) {
            this.mIsOffloadOnly = isOffloadOnly;
            return this;
        }

        /**
         * Builds a {@link MdnsAdvertisingOptions} with the arguments supplied to this builder.
         */
        public MdnsAdvertisingOptions build() {
            return new MdnsAdvertisingOptions(mIsOnlyUpdate, mTtl, mSkipProbing,
                    mSkipSubtypeAnnouncements, mIsOffloadOnly);
        }
    }
}
