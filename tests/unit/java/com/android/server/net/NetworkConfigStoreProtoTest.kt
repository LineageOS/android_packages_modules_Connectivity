/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.net

import androidx.test.filters.SmallTest
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.EthernetConfigHolderProto
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.EthernetConfigurationProto
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.EthernetPortSelectorProto
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.IpConfigurationProto
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.LinkAddressProto
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.ManualProxyConfigProto
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.MeteredOverrideProto
import com.android.server.network.configstore.proto.NetworkConfigStoreProto.StaticIpv4ConfigurationProto
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.MockitoAnnotations

/**
 * Unit tests for [com.android.server.ethernet.proto.NetworkConfigStoreProto].
 */
@SmallTest
class NetworkConfigStoreProtoTest {
    companion object {
        private const val IFACE_NAME = "eth0"
        private const val ADDR = "192.0.2.1"
        private const val PREFIX_LEN = 25
        private const val GATEWAY = "192.0.2.129"
        private const val DNS1 = "8.8.8.8"
        private const val DNS2 = "8.8.8.4"
        private const val EXCLUSION_LIST1 = "com.example.app"
        private const val EXCLUSION_LIST2 = "localhost"
        private const val PROXY_HOST = "proxy.example.com"
        private const val PROXY_PORT = 8080
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testBuilder() {
        val selector = EthernetPortSelectorProto.newBuilder().apply {
            ifaceName = IFACE_NAME
        }.build()
        assertEquals(IFACE_NAME, selector.ifaceName)

        val linkAddr = LinkAddressProto.newBuilder().apply {
            address = ADDR
            prefixLength = PREFIX_LEN
        }.build()
        assertEquals(ADDR, linkAddr.address)
        assertEquals(PREFIX_LEN, linkAddr.prefixLength)

        val staticIpv4Configuration =
            StaticIpv4ConfigurationProto.newBuilder().apply {
                setAddress(linkAddr)
                gateway = GATEWAY
                addDnsServers(DNS1)
                addDnsServers(DNS2)
            }.build()
        assertEquals(linkAddr, staticIpv4Configuration.address)
        assertEquals(GATEWAY, staticIpv4Configuration.gateway)
        assertEquals(DNS1, staticIpv4Configuration.getDnsServers(0))
        assertEquals(DNS2, staticIpv4Configuration.getDnsServers(1))

        val manualProxy = ManualProxyConfigProto.newBuilder().apply {
            host = PROXY_HOST
            port = PROXY_PORT
            addExclusionHosts(EXCLUSION_LIST1)
            addExclusionHosts(EXCLUSION_LIST2)
        }.build()
        assertEquals(PROXY_HOST, manualProxy.host)
        assertEquals(PROXY_PORT, manualProxy.port)
        assertEquals(EXCLUSION_LIST1, manualProxy.getExclusionHosts(0))
        assertEquals(EXCLUSION_LIST2, manualProxy.getExclusionHosts(1))

        val ipConfig = IpConfigurationProto.newBuilder().apply {
            staticIpv4Config = staticIpv4Configuration
            manualProxyConfig = manualProxy
        }.build()
        assertEquals(staticIpv4Configuration, ipConfig.staticIpv4Config)
        assertEquals(manualProxy, ipConfig.manualProxyConfig)

        val ethConfig = EthernetConfigurationProto.newBuilder().apply {
            setSelector(selector)
            setIpConfig(ipConfig)
            meteredOverride = MeteredOverrideProto.METERED_OVERRIDE_FORCE_METERED
        }.build()
        assertEquals(selector, ethConfig.selector)
        assertEquals(ipConfig, ethConfig.ipConfig)
        assertEquals(MeteredOverrideProto.METERED_OVERRIDE_FORCE_METERED, ethConfig.meteredOverride)
        assertEquals(staticIpv4Configuration, ethConfig.ipConfig.staticIpv4Config)

        val ethState = EthernetConfigHolderProto.newBuilder().apply {
            addConfigs(ethConfig)
        }.build()
        assertEquals(ethConfig, ethState.getConfigs(0))
    }
}
