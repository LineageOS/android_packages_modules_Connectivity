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
import android.net.Network;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * API configuration parameters for searching the mDNS service.
 *
 * <p>Use {@link MdnsSearchOptions.Builder} to create {@link MdnsSearchOptions}.
 *
 * @hide
 */
public class MdnsSearchOptions implements Parcelable {

    /** @hide */
    public static final Parcelable.Creator<MdnsSearchOptions> CREATOR =
            new Parcelable.Creator<MdnsSearchOptions>() {
                @Override
                public MdnsSearchOptions createFromParcel(Parcel source) {
                    return new MdnsSearchOptions(
                            source.createStringArrayList(),
                            source.readInt() == 1,
                            source.readInt() == 1,
                            source.readParcelable(null),
                            source.readString(),
                            source.readInt() == 1,
                            source.readInt());
                }

                @Override
                public MdnsSearchOptions[] newArray(int size) {
                    return new MdnsSearchOptions[size];
                }
            };
    private static MdnsSearchOptions defaultOptions;
    private final List<String> subtypes;
    @Nullable
    private final String resolveInstanceName;
    private final boolean isPassiveMode;
    private final boolean onlyUseIpv6OnIpv6OnlyNetworks;
    private final int numOfQueriesBeforeBackoff;
    private final boolean removeExpiredService;
    // The target network for searching. Null network means search on all possible interfaces.
    @Nullable private final Network mNetwork;

    /** Parcelable constructs for a {@link MdnsSearchOptions}. */
    MdnsSearchOptions(
            List<String> subtypes,
            boolean isPassiveMode,
            boolean removeExpiredService,
            @Nullable Network network,
            @Nullable String resolveInstanceName,
            boolean onlyUseIpv6OnIpv6OnlyNetworks,
            int numOfQueriesBeforeBackoff) {
        this.subtypes = new ArrayList<>();
        if (subtypes != null) {
            this.subtypes.addAll(subtypes);
        }
        this.isPassiveMode = isPassiveMode;
        this.onlyUseIpv6OnIpv6OnlyNetworks = onlyUseIpv6OnIpv6OnlyNetworks;
        this.numOfQueriesBeforeBackoff = numOfQueriesBeforeBackoff;
        this.removeExpiredService = removeExpiredService;
        mNetwork = network;
        this.resolveInstanceName = resolveInstanceName;
    }

    /** Returns a {@link Builder} for {@link MdnsSearchOptions}. */
    public static Builder newBuilder() {
        return new Builder();
    }

    /** Returns a default search options. */
    public static synchronized MdnsSearchOptions getDefaultOptions() {
        if (defaultOptions == null) {
            defaultOptions = newBuilder().build();
        }
        return defaultOptions;
    }

    /** @return the list of subtypes to search. */
    public List<String> getSubtypes() {
        return subtypes;
    }

    /**
     * @return {@code true} if the passive mode is used. The passive mode scans less frequently in
     * order to conserve battery and produce less network traffic.
     */
    public boolean isPassiveMode() {
        return isPassiveMode;
    }

    /**
     * @return {@code true} if only the IPv4 mDNS host should be queried on network that supports
     * both IPv6 as well as IPv4. On an IPv6-only network, this is ignored.
     */
    public boolean onlyUseIpv6OnIpv6OnlyNetworks() {
        return onlyUseIpv6OnIpv6OnlyNetworks;
    }

    /**
     *  Returns number of queries should be executed before backoff mode is enabled.
     *  The default number is 3 if it is not set.
     */
    public int numOfQueriesBeforeBackoff() {
        return numOfQueriesBeforeBackoff;
    }

    /** Returns {@code true} if service will be removed after its TTL expires. */
    public boolean removeExpiredService() {
        return removeExpiredService;
    }

    /**
     * Returns the network which the mdns query should target on.
     *
     * @return the target network or null if search on all possible interfaces.
     */
    @Nullable
    public Network getNetwork() {
        return mNetwork;
    }

    /**
     * If non-null, queries should try to resolve all records of this specific service, rather than
     * discovering all services.
     */
    @Nullable
    public String getResolveInstanceName() {
        return resolveInstanceName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeStringList(subtypes);
        out.writeInt(isPassiveMode ? 1 : 0);
        out.writeInt(removeExpiredService ? 1 : 0);
        out.writeParcelable(mNetwork, 0);
        out.writeString(resolveInstanceName);
        out.writeInt(onlyUseIpv6OnIpv6OnlyNetworks ? 1 : 0);
        out.writeInt(numOfQueriesBeforeBackoff);
    }

    /** A builder to create {@link MdnsSearchOptions}. */
    public static final class Builder {
        private final Set<String> subtypes;
        private boolean isPassiveMode = true;
        private boolean onlyUseIpv6OnIpv6OnlyNetworks = false;
        private int numOfQueriesBeforeBackoff = 3;
        private boolean removeExpiredService;
        private Network mNetwork;
        private String resolveInstanceName;

        private Builder() {
            subtypes = MdnsUtils.newSet();
        }

        /**
         * Adds a subtype to search.
         *
         * @param subtype the subtype to add.
         */
        public Builder addSubtype(@NonNull String subtype) {
            if (TextUtils.isEmpty(subtype)) {
                throw new IllegalArgumentException("Empty subtype");
            }
            subtypes.add(subtype);
            return this;
        }

        /**
         * Adds a set of subtypes to search.
         *
         * @param subtypes The list of subtypes to add.
         */
        public Builder addSubtypes(@NonNull Collection<String> subtypes) {
            this.subtypes.addAll(Objects.requireNonNull(subtypes));
            return this;
        }

        /**
         * Sets if the passive mode scan should be used. The passive mode scans less frequently in
         * order to conserve battery and produce less network traffic.
         *
         * @param isPassiveMode If set to {@code true}, passive mode will be used. If set to {@code
         *                      false}, active mode will be used.
         */
        public Builder setIsPassiveMode(boolean isPassiveMode) {
            this.isPassiveMode = isPassiveMode;
            return this;
        }

        /**
         * Sets if only the IPv4 mDNS host should be queried on a network that is both IPv4 & IPv6.
         * On an IPv6-only network, this is ignored.
         */
        public Builder setOnlyUseIpv6OnIpv6OnlyNetworks(boolean onlyUseIpv6OnIpv6OnlyNetworks) {
            this.onlyUseIpv6OnIpv6OnlyNetworks = onlyUseIpv6OnIpv6OnlyNetworks;
            return this;
        }

        /**
         * Sets if the query backoff mode should be turned on.
         */
        public Builder setNumOfQueriesBeforeBackoff(int numOfQueriesBeforeBackoff) {
            this.numOfQueriesBeforeBackoff = numOfQueriesBeforeBackoff;
            return this;
        }

        /**
         * Sets if the service should be removed after TTL.
         *
         * @param removeExpiredService If set to {@code true}, the service will be removed after TTL
         */
        public Builder setRemoveExpiredService(boolean removeExpiredService) {
            this.removeExpiredService = removeExpiredService;
            return this;
        }

        /**
         * Sets if the mdns query should target on specific network.
         *
         * @param network the mdns query will target on given network.
         */
        public Builder setNetwork(Network network) {
            mNetwork = network;
            return this;
        }

        /**
         * Set the instance name to resolve.
         *
         * If non-null, queries should try to resolve all records of this specific service,
         * rather than discovering all services.
         * @param name The instance name.
         */
        public Builder setResolveInstanceName(String name) {
            resolveInstanceName = name;
            return this;
        }

        /** Builds a {@link MdnsSearchOptions} with the arguments supplied to this builder. */
        public MdnsSearchOptions build() {
            return new MdnsSearchOptions(
                    new ArrayList<>(subtypes),
                    isPassiveMode,
                    removeExpiredService,
                    mNetwork,
                    resolveInstanceName,
                    onlyUseIpv6OnIpv6OnlyNetworks,
                    numOfQueriesBeforeBackoff);
        }
    }
}