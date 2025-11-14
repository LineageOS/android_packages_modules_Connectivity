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

package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * This class represents the static IP configuration data such as IP configuration (static IPv4
 * address, IPv4 gateway, DNS servers, etc.) and network capabilities for a configured ethernet
 * network interface.
 *
 * - Intended Usage and Scope:
 *   This class implements android.os.Parcelable and resides in the framework JAR. This is
 *   necessary for being used as an AIDL object to share code with EthernetPortInfo and
 *   EthernetNetworkUpdateRequest.
 *
 * - Future Evolution:
 *   This class is meant to progressively replace EthernetNetworkUpdateRequest in EthernetManager's
 *   {@code @SystemApi} and be exposed through {@code @SystemApi} getters as well.
 *
 * @hide
 */
public class EthernetConfiguration implements Parcelable {
    /**
     * Static IP configuration data (static IPv4 address, IPv4 gateway, DNS servers, etc.)
     */
    private final IpConfiguration mIpConfiguration;

    /**
     * This is only for the requestable bits of NetworkCapabilities that an external caller can
     * pass through a EthernetNetworkUpdateRequest.
     */
    private final NetworkCapabilities mNetworkCapabilities;

    public EthernetConfiguration(@NonNull IpConfiguration ipConfiguration,
            @NonNull NetworkCapabilities capabilities) {
        Objects.requireNonNull(ipConfiguration);
        Objects.requireNonNull(capabilities);
        mIpConfiguration = ipConfiguration;
        mNetworkCapabilities = capabilities;
    }

    /**
     * Get the IpConfiguration object associated with this EthernetConfiguration.
     */
    @NonNull
    public IpConfiguration getIpConfiguration() {
        return new IpConfiguration(mIpConfiguration);
    }

    /**
     * Get the NetworkCapabilities object associated with this EthernetConfiguration.
     */
    @NonNull
    public NetworkCapabilities getNetworkCapabilities() {
        return new NetworkCapabilities(mNetworkCapabilities);
    }

    @Override
    public String toString() {
        return "IP configurations: " + mIpConfiguration.toString()
                + "Network capabilities: " + mNetworkCapabilities.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof EthernetConfiguration)) {
            return false;
        }

        final EthernetConfiguration other = (EthernetConfiguration) o;
        return Objects.equals(this.mIpConfiguration, other.mIpConfiguration)
                && Objects.equals(this.mNetworkCapabilities, other.mNetworkCapabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIpConfiguration, mNetworkCapabilities);
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mIpConfiguration, flags);
        dest.writeParcelable(mNetworkCapabilities, flags);
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<EthernetConfiguration> CREATOR =
            new Creator<EthernetConfiguration>() {
                @Override
                public EthernetConfiguration createFromParcel(Parcel in) {
                    final IpConfiguration config = in.readParcelable(
                            IpConfiguration.class.getClassLoader());
                    final NetworkCapabilities capabilities = in.readParcelable(
                            NetworkCapabilities.class.getClassLoader());
                    return new EthernetConfiguration(config, capabilities);
                }

                @Override
                public EthernetConfiguration[] newArray(int size) {
                    return new EthernetConfiguration[size];
                }
            };

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }
}
