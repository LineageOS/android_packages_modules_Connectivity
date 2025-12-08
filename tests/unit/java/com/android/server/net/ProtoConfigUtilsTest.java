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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.net.EthernetConfiguration;
import android.net.EthernetPortSelector;
import android.net.InetAddresses;
import android.net.LinkAddress;
import android.net.MacAddress;
import android.net.StaticIpConfiguration;
import android.os.Build;

import com.android.server.network.configstore.proto.NetworkConfigStoreProto.EthernetPortSelectorProto;
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.LinkAddressProto;
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.MeteredOverrideProto;
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.StaticIpv4ConfigurationProto;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link ProtoConfigUtils}
 */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class ProtoConfigUtilsTest {
    private static final String LINK_ADDRESS_STRING = "192.168.1.10/24";
    private static final String IP_ADDRESS_STRING = "192.168.1.10";
    private static final int PREFIX_LENGTH = 24;
    private static final String IFACE_NAME = "eth0";
    private static final MacAddress MAC_ADDR = MacAddress.fromString("aa:bb:cc:dd:ee:11");
    private static final String GATEWAY_ADDRESS = "192.168.1.1";
    private static final String DOMAIN1 = "aexample.com";
    private static final String DOMAIN2 = "test.net";
    private static final String DNS_IP_ADDR_1 = "1.2.3.4";
    private static final String DNS_IP_ADDR_2 = "5.6.7.8";

    private static final ArrayList<InetAddress> DNS_SERVERS = new ArrayList<>(List.of(
            InetAddresses.parseNumericAddress(DNS_IP_ADDR_1),
            InetAddresses.parseNumericAddress(DNS_IP_ADDR_2)));

    @Test
    public void testconvertMeteredOverrideToProto() {
        assertEquals(MeteredOverrideProto.METERED_OVERRIDE_FORCE_METERED,
                ProtoConfigUtils.convertMeteredOverrideToProto(
                        EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED));
        assertEquals(MeteredOverrideProto.METERED_OVERRIDE_FORCE_UNMETERED,
                ProtoConfigUtils.convertMeteredOverrideToProto(
                        EthernetConfiguration.METERED_OVERRIDE_FORCE_UNMETERED));
        assertEquals(MeteredOverrideProto.METERED_OVERRIDE_NONE,
                ProtoConfigUtils.convertMeteredOverrideToProto(
                        EthernetConfiguration.METERED_OVERRIDE_NONE));
    }

    @Test
    public void testConvertMeteredOverrideFromProto() {
        assertEquals(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED,
                ProtoConfigUtils.convertMeteredOverrideFromProto(
                        MeteredOverrideProto.METERED_OVERRIDE_FORCE_METERED));
        assertEquals(EthernetConfiguration.METERED_OVERRIDE_FORCE_UNMETERED,
                ProtoConfigUtils.convertMeteredOverrideFromProto(
                        MeteredOverrideProto.METERED_OVERRIDE_FORCE_UNMETERED));
        assertEquals(EthernetConfiguration.METERED_OVERRIDE_NONE,
                ProtoConfigUtils.convertMeteredOverrideFromProto(
                        MeteredOverrideProto.METERED_OVERRIDE_NONE));
    }

    @Test
    public void testConvertLinkAddressToProto() {
        LinkAddress linkAddr = new LinkAddress(LINK_ADDRESS_STRING);
        LinkAddressProto proto = ProtoConfigUtils.convertLinkAddressToProto(linkAddr);

        assertEquals(IP_ADDRESS_STRING, proto.getAddress());
        assertEquals(PREFIX_LENGTH, proto.getPrefixLength());
    }

    @Test
    public void testConvertLinkAddressFromProto() {
        LinkAddressProto proto = LinkAddressProto.newBuilder()
                .setAddress(IP_ADDRESS_STRING)
                .setPrefixLength(PREFIX_LENGTH)
                .build();

        LinkAddress actual = ProtoConfigUtils.convertLinkAddressFromProto(proto);
        LinkAddress target = new LinkAddress(LINK_ADDRESS_STRING);
        assertEquals(actual, target);
    }

    @Test
    public void testConvertPortSelectorToProto_withMacAddress_setsMacAddrField() {
        final EthernetPortSelector portSelector = new EthernetPortSelector(MAC_ADDR);

        final EthernetPortSelectorProto proto =
                ProtoConfigUtils.convertPortSelectorToProto(portSelector);

        assertNotNull(proto);
        assertTrue(proto.hasMacAddr());
        assertFalse(proto.hasIfaceName());
        assertEquals(MAC_ADDR.toString(), proto.getMacAddr());
    }

    @Test
    public void testConvertPortSelectorToProto_withInterfaceName_setsIfaceNameField() {
        final EthernetPortSelector portSelector = new EthernetPortSelector(IFACE_NAME);

        final EthernetPortSelectorProto proto =
                ProtoConfigUtils.convertPortSelectorToProto(portSelector);

        assertNotNull(proto);
        assertTrue(proto.hasIfaceName());
        assertFalse(proto.hasMacAddr());
        assertEquals(IFACE_NAME, proto.getIfaceName());
    }


    @Test
    public void testConvertPortSelectorFromProto_withMacAddress() {
        EthernetPortSelectorProto proto = EthernetPortSelectorProto.newBuilder()
                .setMacAddr(MAC_ADDR.toString())
                .build();

        EthernetPortSelector selector = ProtoConfigUtils.convertPortSelectorFromProto(proto);
        assertEquals(MAC_ADDR, selector.getMacAddress());
    }

    @Test
    public void testConvertPortSelectorFromProto_withInterfaceName() {
        EthernetPortSelectorProto proto = EthernetPortSelectorProto.newBuilder()
                .setIfaceName(IFACE_NAME)
                .build();

        EthernetPortSelector selector = ProtoConfigUtils.convertPortSelectorFromProto(proto);
        assertEquals(IFACE_NAME, selector.getInterfaceName());
    }

    @Test
    public void testConvertPortSelectorFromProto_emptyProto_throwsException() {
        EthernetPortSelectorProto emptyProto = EthernetPortSelectorProto.newBuilder().build();

        assertThrows(IllegalArgumentException.class, () ->
                ProtoConfigUtils.convertPortSelectorFromProto(emptyProto));
    }

    @Test
    public void testConvertStaticIpConfigurationToProto() {
        final StaticIpConfiguration staticIpConfig = new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress(LINK_ADDRESS_STRING))
                .setGateway(InetAddresses.parseNumericAddress(GATEWAY_ADDRESS))
                .setDnsServers(DNS_SERVERS)
                .setDomains(DOMAIN1 + "," + DOMAIN2)
                .build();

        final StaticIpv4ConfigurationProto proto =
                ProtoConfigUtils.convertStaticIpConfigurationToProto(staticIpConfig);

        assertNotNull(proto);
        assertTrue(proto.hasAddress());
        Assert.assertEquals(IP_ADDRESS_STRING, proto.getAddress().getAddress());
        Assert.assertEquals(PREFIX_LENGTH, proto.getAddress().getPrefixLength());

        assertTrue(proto.hasGateway());
        Assert.assertEquals(GATEWAY_ADDRESS, proto.getGateway());

        Assert.assertEquals(2, proto.getDnsServersCount());
        Assert.assertEquals(Arrays.asList(DNS_IP_ADDR_1, DNS_IP_ADDR_2),
                proto.getDnsServersList());

        Assert.assertEquals(2, proto.getSearchDomainsCount());
        Assert.assertEquals(Arrays.asList(DOMAIN1, DOMAIN2),
                proto.getSearchDomainsList());
    }

    @Test
    public void testConvertStaticIpConfigurationToProto_nullInput() {
        assertThrows("Static IP configuration cannot be null.",
                NullPointerException.class, () -> {
                    ProtoConfigUtils.convertStaticIpConfigurationToProto(null);
                });
    }

    @Test
    public void testConvertProtoToStaticIpConfiguration() {
        LinkAddressProto linkAddrProto =
                LinkAddressProto.newBuilder()
                        .setAddress(IP_ADDRESS_STRING)
                        .setPrefixLength(PREFIX_LENGTH)
                        .build();
        StaticIpv4ConfigurationProto staticIpConfigProto =
                StaticIpv4ConfigurationProto.newBuilder()
                        .setAddress(linkAddrProto)
                        .addDnsServers(DNS_IP_ADDR_1)
                        .addDnsServers(DNS_IP_ADDR_2)
                        .addSearchDomains(DOMAIN1)
                        .addSearchDomains(DOMAIN2)
                        .build();

        StaticIpConfiguration actualConfig =
                ProtoConfigUtils.convertStaticIpConfigurationFromProto(staticIpConfigProto);

        StaticIpConfiguration expectedConfig = new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress(LINK_ADDRESS_STRING))
                .setDnsServers(DNS_SERVERS)
                .setDomains(DOMAIN1 + "," + DOMAIN2)
                .build();

        assertEquals(expectedConfig, actualConfig);
    }

    @Test
    public void testConvertProtoToStaticIpConfiguration_nullInput() {
        assertThrows("Static IP configuration proto object cannot be null.",
                NullPointerException.class, () -> {
                    ProtoConfigUtils.convertStaticIpConfigurationFromProto(null);
                });
    }
}
