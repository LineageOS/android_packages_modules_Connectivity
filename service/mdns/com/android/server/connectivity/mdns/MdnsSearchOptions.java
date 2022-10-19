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
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArraySet;

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
                    return new MdnsSearchOptions(source.createStringArrayList(),
                            source.readBoolean(), source.readBoolean());
                }

                @Override
                public MdnsSearchOptions[] newArray(int size) {
                    return new MdnsSearchOptions[size];
                }
            };
    private static MdnsSearchOptions defaultOptions;
    private final List<String> subtypes;

    private final boolean isPassiveMode;
    private final boolean removeExpiredService;

    /** Parcelable constructs for a {@link MdnsServiceInfo}. */
    MdnsSearchOptions(List<String> subtypes, boolean isPassiveMode, boolean removeExpiredService) {
        this.subtypes = new ArrayList<>();
        if (subtypes != null) {
            this.subtypes.addAll(subtypes);
        }
        this.isPassiveMode = isPassiveMode;
        this.removeExpiredService = removeExpiredService;
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

    /** Returns {@code true} if service will be removed after its TTL expires. */
    public boolean removeExpiredService() {
        return removeExpiredService;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeStringList(subtypes);
        out.writeBoolean(isPassiveMode);
        out.writeBoolean(removeExpiredService);
    }

    /** A builder to create {@link MdnsSearchOptions}. */
    public static final class Builder {
        private final Set<String> subtypes;
        private boolean isPassiveMode = true;
        private boolean removeExpiredService;

        private Builder() {
            subtypes = new ArraySet<>();
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
         * Sets if the service should be removed after TTL.
         *
         * @param removeExpiredService If set to {@code true}, the service will be removed after TTL
         */
        public Builder setRemoveExpiredService(boolean removeExpiredService) {
            this.removeExpiredService = removeExpiredService;
            return this;
        }

        /** Builds a {@link MdnsSearchOptions} with the arguments supplied to this builder. */
        public MdnsSearchOptions build() {
            return new MdnsSearchOptions(
                    new ArrayList<>(subtypes), isPassiveMode, removeExpiredService);
        }
    }
}