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

package android.net;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * A class representing a configuration for multicast routing.
 *
 * Internal usage to Connectivity
 * @hide
 */
// TODO : @SystemApi
public class MulticastRoutingConfig implements Parcelable {
    private static final String TAG = MulticastRoutingConfig.class.getSimpleName();

    /** Do not forward any multicast packets. */
    public static final int FORWARD_NONE = 0;
    /**
     * Forward only multicast packets with destination in the list of listening addresses.
     * Ignore the min scope.
     */
    public static final int FORWARD_SELECTED = 1;
    /**
     * Forward all multicast packets with scope greater or equal than the min scope.
     * Ignore the list of listening addresses.
     */
    public static final int FORWARD_WITH_MIN_SCOPE = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "FORWARD_" }, value = {
            FORWARD_NONE,
            FORWARD_SELECTED,
            FORWARD_WITH_MIN_SCOPE
    })
    public @interface MulticastForwardingMode {}

    /**
     * Not a multicast scope, for configurations that do not use the min scope.
     */
    public static final int MULTICAST_SCOPE_NONE = -1;

    public static final MulticastRoutingConfig CONFIG_FORWARD_NONE =
            new MulticastRoutingConfig(FORWARD_NONE, MULTICAST_SCOPE_NONE, null);

    @MulticastForwardingMode
    private final int mForwardingMode;

    private final int mMinScope;

    @NonNull
    private final Set<Inet6Address> mListeningAddresses;

    private MulticastRoutingConfig(@MulticastForwardingMode final int mode, final int scope,
            @Nullable final Set<Inet6Address> addresses) {
        mForwardingMode = mode;
        mMinScope = scope;
        if (null != addresses) {
            mListeningAddresses = Collections.unmodifiableSet(new ArraySet<>(addresses));
        } else {
            mListeningAddresses = Collections.emptySet();
        }
    }

    /**
     * Returns the forwarding mode.
     */
    @MulticastForwardingMode
    public int getForwardingMode() {
        return mForwardingMode;
    }

    /**
     * Returns the minimal group address scope that is allowed for forwarding.
     * If the forwarding mode is not FORWARD_WITH_MIN_SCOPE, will be MULTICAST_SCOPE_NONE.
     */
    public int getMinScope() {
        return mMinScope;
    }

    /**
     * Returns the list of group addresses listened by the outgoing interface.
     * The list will be empty if the forwarding mode is not FORWARD_SELECTED.
     */
    @NonNull
    public Set<Inet6Address> getMulticastListeningAddresses() {
        return mListeningAddresses;
    }

    private MulticastRoutingConfig(Parcel in) {
        mForwardingMode = in.readInt();
        mMinScope = in.readInt();
        final int count = in.readInt();
        final ArraySet<Inet6Address> listeningAddresses = new ArraySet<>(count);
        final byte[] buffer = new byte[16]; // Size of an Inet6Address
        for (int i = 0; i < count; ++i) {
            in.readByteArray(buffer);
            try {
                listeningAddresses.add((Inet6Address) Inet6Address.getByAddress(buffer));
            } catch (UnknownHostException e) {
                Log.wtf(TAG, "Can't read inet6address : " + Arrays.toString(buffer));
            }
        }
        mListeningAddresses = Collections.unmodifiableSet(listeningAddresses);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mForwardingMode);
        dest.writeInt(mMinScope);
        dest.writeInt(mListeningAddresses.size());
        for (final Inet6Address addr : mListeningAddresses) {
            dest.writeByteArray(addr.getAddress());
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<MulticastRoutingConfig> CREATOR = new Creator<>() {
        @Override
        public MulticastRoutingConfig createFromParcel(Parcel in) {
            return new MulticastRoutingConfig(in);
        }

        @Override
        public MulticastRoutingConfig[] newArray(int size) {
            return new MulticastRoutingConfig[size];
        }
    };

    public static class Builder {
        @MulticastForwardingMode
        private final int mForwardingMode;
        private int mMinScope;
        private final ArraySet<Inet6Address> mListeningAddresses;

        private Builder(@MulticastForwardingMode final int mode, int scope) {
            mForwardingMode = mode;
            mMinScope = scope;
            mListeningAddresses = new ArraySet<>();
        }

        /**
         * Create a builder that forwards nothing.
         * No properties can be set on such a builder.
         */
        public static Builder newBuilderForwardingNone() {
            return new Builder(FORWARD_NONE, MULTICAST_SCOPE_NONE);
        }

        /**
         * Create a builder that forwards packets above a certain scope
         *
         * The scope can be changed on this builder, but not the listening addresses.
         * @param scope the initial scope
         */
        public static Builder newBuilderWithMinScope(final int scope) {
            return new Builder(FORWARD_WITH_MIN_SCOPE, scope);
        }

        /**
         * Create a builder that forwards a specified list of listening addresses.
         *
         * Addresses can be added and removed from this builder, but the scope can't be set.
         */
        public static Builder newBuilderWithListeningAddresses() {
            return new Builder(FORWARD_SELECTED, MULTICAST_SCOPE_NONE);
        }

        /**
         * Sets the minimum scope for this multicast routing config.
         * This is only meaningful (indeed, allowed) for configs in FORWARD_WITH_MIN_SCOPE mode.
         * @return this builder
         */
        public Builder setMinimumScope(final int scope) {
            if (FORWARD_WITH_MIN_SCOPE != mForwardingMode) {
                throw new IllegalArgumentException("Can't set the scope on a builder in mode "
                        + modeToString(mForwardingMode));
            }
            mMinScope = scope;
            return this;
        }

        /**
         * Add an address to the set of listening addresses.
         *
         * This is only meaningful (indeed, allowed) for configs in FORWARD_SELECTED mode.
         * If this address was already added, this is a no-op.
         * @return this builder
         */
        public Builder addListeningAddress(@NonNull final Inet6Address address) {
            if (FORWARD_SELECTED != mForwardingMode) {
                throw new IllegalArgumentException("Can't add an address on a builder in mode "
                        + modeToString(mForwardingMode));
            }
            // TODO : should we check that this is a multicast address ?
            mListeningAddresses.add(address);
            return this;
        }

        /**
         * Remove an address from the set of listening addresses.
         *
         * This is only meaningful (indeed, allowed) for configs in FORWARD_SELECTED mode.
         * If this address was not added, or was already removed, this is a no-op.
         * @return this builder
         */
        public Builder removeListeningAddress(@NonNull final Inet6Address address) {
            if (FORWARD_SELECTED != mForwardingMode) {
                throw new IllegalArgumentException("Can't remove an address on a builder in mode "
                        + modeToString(mForwardingMode));
            }
            mListeningAddresses.remove(address);
            return this;
        }

        /**
         * Build the config.
         */
        public MulticastRoutingConfig build() {
            return new MulticastRoutingConfig(mForwardingMode, mMinScope, mListeningAddresses);
        }
    }

    private static String modeToString(@MulticastForwardingMode final int mode) {
        switch (mode) {
            case FORWARD_NONE: return "FORWARD_NONE";
            case FORWARD_SELECTED: return "FORWARD_SELECTED";
            case FORWARD_WITH_MIN_SCOPE: return "FORWARD_WITH_MIN_SCOPE";
            default: return "unknown multicast routing mode " + mode;
        }
    }
}
