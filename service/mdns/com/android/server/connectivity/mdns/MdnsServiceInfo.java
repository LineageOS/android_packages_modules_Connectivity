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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A class representing a discovered mDNS service instance.
 *
 * @hide
 */
public class MdnsServiceInfo implements Parcelable {

    /** @hide */
    public static final Parcelable.Creator<MdnsServiceInfo> CREATOR =
            new Parcelable.Creator<MdnsServiceInfo>() {

                @Override
                public MdnsServiceInfo createFromParcel(Parcel source) {
                    return new MdnsServiceInfo(
                            source.readString(),
                            source.createStringArray(),
                            source.createStringArrayList(),
                            source.createStringArray(),
                            source.readInt(),
                            source.readString(),
                            source.readString(),
                            source.createStringArrayList());
                }

                @Override
                public MdnsServiceInfo[] newArray(int size) {
                    return new MdnsServiceInfo[size];
                }
            };

    private final String serviceInstanceName;
    private final String[] serviceType;
    private final List<String> subtypes;
    private final String[] hostName;
    private final int port;
    private final String ipv4Address;
    private final String ipv6Address;
    private final Map<String, String> attributes = new HashMap<>();
    List<String> textStrings;

    /**
     * Constructs a {@link MdnsServiceInfo} object with default values.
     *
     * @hide
     */
    public MdnsServiceInfo(
            String serviceInstanceName,
            String[] serviceType,
            List<String> subtypes,
            String[] hostName,
            int port,
            String ipv4Address,
            String ipv6Address,
            List<String> textStrings) {
        this.serviceInstanceName = serviceInstanceName;
        this.serviceType = serviceType;
        this.subtypes = new ArrayList<>();
        if (subtypes != null) {
            this.subtypes.addAll(subtypes);
        }
        this.hostName = hostName;
        this.port = port;
        this.ipv4Address = ipv4Address;
        this.ipv6Address = ipv6Address;
        if (textStrings != null) {
            for (String text : textStrings) {
                int pos = text.indexOf('=');
                if (pos < 1) {
                    continue;
                }
                attributes.put(text.substring(0, pos).toLowerCase(Locale.ENGLISH),
                        text.substring(++pos));
            }
        }
    }

    /** @return the name of this service instance. */
    public String getServiceInstanceName() {
        return serviceInstanceName;
    }

    /** @return the type of this service instance. */
    public String[] getServiceType() {
        return serviceType;
    }

    /** @return the list of subtypes supported by this service instance. */
    public List<String> getSubtypes() {
        return new ArrayList<>(subtypes);
    }

    /**
     * @return {@code true} if this service instance supports any subtypes.
     * @return {@code false} if this service instance does not support any subtypes.
     */
    public boolean hasSubtypes() {
        return !subtypes.isEmpty();
    }

    /** @return the host name of this service instance. */
    public String[] getHostName() {
        return hostName;
    }

    /** @return the port number of this service instance. */
    public int getPort() {
        return port;
    }

    /** @return the IPV4 address of this service instance. */
    public String getIpv4Address() {
        return ipv4Address;
    }

    /** @return the IPV6 address of this service instance. */
    public String getIpv6Address() {
        return ipv6Address;
    }

    /**
     * @return the attribute value for {@code key}.
     * @return {@code null} if no attribute value exists for {@code key}.
     */
    public String getAttributeByKey(@NonNull String key) {
        return attributes.get(key.toLowerCase(Locale.ENGLISH));
    }

    /** @return an immutable map of all attributes. */
    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (textStrings == null) {
            // Lazily initialize the parcelable field mTextStrings.
            textStrings = new ArrayList<>(attributes.size());
            for (Map.Entry<String, String> kv : attributes.entrySet()) {
                textStrings.add(String.format(Locale.ROOT, "%s=%s", kv.getKey(), kv.getValue()));
            }
        }

        out.writeString(serviceInstanceName);
        out.writeStringArray(serviceType);
        out.writeStringList(subtypes);
        out.writeStringArray(hostName);
        out.writeInt(port);
        out.writeString(ipv4Address);
        out.writeString(ipv6Address);
        out.writeStringList(textStrings);
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "Name: %s, subtypes: %s, ip: %s, port: %d",
                serviceInstanceName,
                TextUtils.join(",", subtypes),
                ipv4Address,
                port);
    }
}