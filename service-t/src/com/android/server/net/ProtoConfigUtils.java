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
package com.android.server.net;

import static java.util.Objects.requireNonNull;

import android.net.EthernetConfiguration;
import android.net.EthernetConfiguration.MeteredOverride;
import android.net.EthernetPortSelector;
import android.net.InetAddresses;
import android.net.LinkAddress;
import android.net.MacAddress;
import android.net.StaticIpConfiguration;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.network.configstore.proto.NetworkConfigStoreProto.EthernetPortSelectorProto;
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.LinkAddressProto;
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.MeteredOverrideProto;
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.StaticIpv4ConfigurationProto;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Static util methods for {@link ProtoConfig}.
 * Note that conversion methods are responsible of doing null check on arguments.
 */
public class ProtoConfigUtils {
    private static final String TAG = "ProtoConfigUtils";

    /**
     * Converts an {@link EthernetConfiguration.MeteredOverride} value to a corresponding value of
     * {@link MeteredOverrideProto} type.
     */
    public static MeteredOverrideProto convertMeteredOverrideToProto(
            @MeteredOverride int override) {
        return switch (override) {
            case EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED ->
                    MeteredOverrideProto.METERED_OVERRIDE_FORCE_METERED;
            case EthernetConfiguration.METERED_OVERRIDE_FORCE_UNMETERED ->
                    MeteredOverrideProto.METERED_OVERRIDE_FORCE_UNMETERED;
            case EthernetConfiguration.METERED_OVERRIDE_NONE ->
                    MeteredOverrideProto.METERED_OVERRIDE_NONE;
            default -> {
                Log.e(TAG, "Ignore invalid metered override: " + override);
                yield MeteredOverrideProto.METERED_OVERRIDE_NONE;
            }
        };
    }

    /**
     * Converts a {@link MeteredOverrideProto} value to a corresponding value of
     * {@link EthernetConfiguration.MeteredOverride} type.
     */
    public static @MeteredOverride int convertMeteredOverrideFromProto(
            MeteredOverrideProto protoOverride) {
        requireNonNull(protoOverride, "MeteredOverrideProto must not be null");
        return switch (protoOverride) {
            case METERED_OVERRIDE_FORCE_METERED ->
                    EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED;
            case METERED_OVERRIDE_FORCE_UNMETERED ->
                    EthernetConfiguration.METERED_OVERRIDE_FORCE_UNMETERED;
            case METERED_OVERRIDE_NONE ->
                    EthernetConfiguration.METERED_OVERRIDE_NONE;
            default -> {
                Log.e(TAG, "Ignore invalid metered override: " + protoOverride);
                yield EthernetConfiguration.METERED_OVERRIDE_NONE;
            }
        };
    }

    /**
     * Converts an {@link LinkAddress} object to a corresponding {@link LinkAddressProto} object.
     */
    public static LinkAddressProto convertLinkAddressToProto(LinkAddress linkAddress) {
        requireNonNull(linkAddress, "LinkAddress must not be null");
        return LinkAddressProto.newBuilder()
                .setAddress(linkAddress.getAddress().getHostAddress())
                .setPrefixLength(linkAddress.getPrefixLength())
                .build();
    }

    /**
     * Converts an {@link LinkAddressProto} object to a corresponding value of {@link LinkAddress}
     * type.
     *
     * @throws IllegalArgumentException if the address string in the proto is not a valid IP
     * address.
     */
    public static LinkAddress convertLinkAddressFromProto(LinkAddressProto linkAddress) {
        requireNonNull(linkAddress, "LinkAddressProto must not be null");
        return new LinkAddress(InetAddresses.parseNumericAddress(linkAddress.getAddress()),
                linkAddress.getPrefixLength());
    }

    /**
     * Converts an {@link EthernetPortSelector} object to a corresponding
     * {@link EthernetPortSelectorProto} object.
     *
     * @throws IllegalArgumentException if both MAC address and interface name in
     * {@code portSelector} are invalid.
     */
    public static EthernetPortSelectorProto convertPortSelectorToProto(
            EthernetPortSelector portSelector) {
        requireNonNull(portSelector, "EthernetPortSelector must not be null");
        EthernetPortSelectorProto.Builder selectorBuilder =
                EthernetPortSelectorProto.newBuilder();
        if (portSelector.getMacAddress() != null) {
            selectorBuilder.setMacAddr(portSelector.getMacAddress().toString());
        } else if (!TextUtils.isEmpty(portSelector.getInterfaceName())) {
            selectorBuilder.setIfaceName(portSelector.getInterfaceName());
        } else {
            // This branch should not be reached theoretically. Added for safety reason.
            throw new IllegalArgumentException("Both MAC address and interface name are invalid in "
                    + "port selector: " + portSelector);
        }
        return selectorBuilder.build();
    }

    /**
     * Converts an {@link EthernetPortSelectorProto} object to a corresponding value of
     * {@link EthernetPortSelector} type.
     *
     * @throws IllegalArgumentException if both MAC address and interface in {@code portSelector}
     * are invalid.
     */
    public static EthernetPortSelector convertPortSelectorFromProto(
            EthernetPortSelectorProto portSelector) {
        requireNonNull(portSelector, "EthernetPortSelectorProto must not be null");
        if (portSelector.hasMacAddr()) {
            return new EthernetPortSelector(MacAddress.fromString(portSelector.getMacAddr()));
        } else if (portSelector.hasIfaceName()) {
            return new EthernetPortSelector(portSelector.getIfaceName());
        } else {
            throw new IllegalArgumentException("No MAC address or interface is found.");
        }
    }

    /**
     * Converts an {@link StaticIpConfiguration} object to a corresponding.
     * {@link StaticIpv4ConfigurationProto} object.
     */
    public static StaticIpv4ConfigurationProto convertStaticIpConfigurationToProto(
            StaticIpConfiguration config) {
        requireNonNull(config, "Static IP configuration cannot be null.");
        final StaticIpv4ConfigurationProto.Builder builder =
                StaticIpv4ConfigurationProto.newBuilder();
        builder.setAddress(convertLinkAddressToProto(config.getIpAddress()));
        if (config.getGateway() != null) {
            builder.setGateway(config.getGateway().getHostAddress());
        }
        for (InetAddress inetAddr : config.getDnsServers()) {
            builder.addDnsServers(inetAddr.getHostAddress());
        }
        if (config.getDomains() != null) {
            for (String domain : config.getDomains().split(",")) {
                if (domain.isEmpty()) continue;
                builder.addSearchDomains(domain);
            }
        }
        return builder.build();
    }

    /**
     * Extracts static IP configuration from a {@link StaticIpv4ConfigurationProto} proto object and
     * converts it to a corresponding {@link StaticIpConfiguration} framework object.
     *
     * @throws IllegalArgumentException if the {@code proto} does not contain a valid IPv4 address,
     * or any gateway/DNS server address is invalid.
     */
    public static StaticIpConfiguration convertStaticIpConfigurationFromProto(
            StaticIpv4ConfigurationProto proto) {
        requireNonNull(proto, "Static IP configuration proto object cannot be null.");

        final StaticIpConfiguration.Builder builder = new StaticIpConfiguration.Builder();

        final LinkAddress linkAddr = convertLinkAddressFromProto(proto.getAddress());
        if (linkAddr.getAddress() instanceof Inet4Address) {
            builder.setIpAddress(linkAddr);
        } else {
            throw new IllegalArgumentException("Non-IPv4 link address, conversion failed.");
        }

        if (proto.hasGateway()) {
            builder.setGateway(InetAddresses.parseNumericAddress(proto.getGateway()));
        }

        final List<InetAddress> dnsServers = new ArrayList<>();
        for (String dnsServer : proto.getDnsServersList()) {
            dnsServers.add(InetAddresses.parseNumericAddress(dnsServer));
        }
        builder.setDnsServers(dnsServers);

        // Filter out empty search domains.
        final String domains = proto.getSearchDomainsList().stream()
                .filter(domain -> !domain.isEmpty())
                .collect(Collectors.joining(","));
        if (!domains.isEmpty()) {
            builder.setDomains(domains);
        }

        return builder.build();
    }
}
