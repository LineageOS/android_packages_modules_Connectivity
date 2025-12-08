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

import static com.android.testutils.ParcelUtils.assertParcelingIsLossless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.build.SdkLevel;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.ArrayList;

@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
@RunWith(DevSdkIgnoreRunner.class)
public class EthernetConfigurationTest {
    // Setup 2 different IP configurations for building different ethernet configurations.
    private IpConfiguration mIpConfiguration1;
    private IpConfiguration mIpConfiguration2;
    private NetworkCapabilities mNetworkCapabilities;

    @Before
    public void setUp() {
        LinkAddress linkAddr = new LinkAddress("192.168.1.100/25");
        InetAddress gateway = InetAddresses.parseNumericAddress("192.168.1.1");
        InetAddress dns1 = InetAddresses.parseNumericAddress("8.8.8.8");
        InetAddress dns2 = InetAddresses.parseNumericAddress("8.8.4.4");
        ArrayList<InetAddress> dnsServers = new ArrayList<>();
        dnsServers.add(dns1);
        dnsServers.add(dns2);
        StaticIpConfiguration staticIpConfig = new StaticIpConfiguration.Builder()
                .setIpAddress(linkAddr)
                .setGateway(gateway)
                .setDnsServers(dnsServers)
                .build();
        ProxyInfo proxy1 = ProxyInfo.buildDirectProxy("test1", 8888);
        ProxyInfo proxy2 = ProxyInfo.buildDirectProxy("test2", 8888);

        mIpConfiguration1 = new IpConfiguration.Builder()
                .setStaticIpConfiguration(staticIpConfig)
                .setHttpProxy(proxy1)
                .build();
        mIpConfiguration2 = new IpConfiguration.Builder()
                .setStaticIpConfiguration(staticIpConfig)
                .setHttpProxy(proxy2)
                .build();

        mNetworkCapabilities = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
    }

    private void assertIpConfigurationEqual(IpConfiguration source, IpConfiguration target) {
        assertEquals(source.getIpAssignment(), target.getIpAssignment());
        assertEquals(source.getProxySettings(), target.getProxySettings());
        assertEquals(source.getHttpProxy(), target.getHttpProxy());
        assertEquals(source.getStaticIpConfiguration(), target.getStaticIpConfiguration());
    }

    private void assertNetworkCapabilitiesEqual(
            NetworkCapabilities source, NetworkCapabilities target) {
        if (source == null || target == null) {
            assertNull(target);
            assertNull(source);
        } else {
            assertTrue(source.equalsNetCapabilities(target));
        }
    }

    @Test
    public void testBuilderNotPersisted() {
        EthernetConfiguration config = new EthernetConfiguration.Builder()
                        .setIpConfiguration(mIpConfiguration1)
                        .setNetworkCapabilities(mNetworkCapabilities)
                        .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                        .setPersistence(EthernetConfiguration.PERSISTENCE_NOT_PERSISTED)
                        .build();
        assertIpConfigurationEqual(config.getIpConfiguration(), mIpConfiguration1);
        assertNetworkCapabilitiesEqual(config.getNetworkCapabilities(), mNetworkCapabilities);
        assertEquals(config.getMeteredOverride(),
                EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED);
        assertEquals(config.getPersistence(), EthernetConfiguration.PERSISTENCE_NOT_PERSISTED);
    }
    @Test
    public void testBuilderPersisted() {
        EthernetConfiguration config = new EthernetConfiguration.Builder()
                .setIpConfiguration(mIpConfiguration1)
                .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                .setPersistence(EthernetConfiguration.PERSISTENCE_IS_PERSISTED)
                .build();
        assertIpConfigurationEqual(config.getIpConfiguration(), mIpConfiguration1);
        assertNetworkCapabilitiesEqual(config.getNetworkCapabilities(), null);
        assertEquals(config.getMeteredOverride(),
                EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED);
        assertEquals(config.getPersistence(), EthernetConfiguration.PERSISTENCE_IS_PERSISTED);
    }

    @Test
    public void testConstructorMeteredOverrideNotSet() {
        EthernetConfiguration config = new EthernetConfiguration.Builder()
                .setIpConfiguration(mIpConfiguration1)
                .setNetworkCapabilities(mNetworkCapabilities)
                .setPersistence(EthernetConfiguration.PERSISTENCE_NOT_PERSISTED)
                .build();
        assertEquals(config.getMeteredOverride(),
                EthernetConfiguration.METERED_OVERRIDE_NONE);
    }

    @Test
    public void testConstructorIsPersistedNotSet() {
        EthernetConfiguration config = new EthernetConfiguration.Builder()
                        .setIpConfiguration(mIpConfiguration1)
                        .setNetworkCapabilities(mNetworkCapabilities)
                        .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                        .build();
        assertEquals(config.getPersistence(), EthernetConfiguration.PERSISTENCE_NOT_PERSISTED);
    }

    @Test
    public void testConstructorWithIpConfigNotSet() {
        EthernetConfiguration config = new EthernetConfiguration.Builder()
                .setIpConfiguration(null)
                .setNetworkCapabilities(mNetworkCapabilities)
                .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                .setPersistence(EthernetConfiguration.PERSISTENCE_NOT_PERSISTED)
                .build();
        assertNull(config.getIpConfiguration());
    }

    @Test
    public void testConstructorWithPersistedNonNullNetCapabilities() {
        assertThrows("Network capabilities cannot be persisted.",
                IllegalArgumentException.class,
                () -> new EthernetConfiguration.Builder()
                        .setIpConfiguration(mIpConfiguration1)
                        .setNetworkCapabilities(mNetworkCapabilities)
                        .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                        .setPersistence(EthernetConfiguration.PERSISTENCE_IS_PERSISTED)
                        .build());
    }

    @Test
    public void testConstructorWithNullIpConfigAndNullNetCapabilities() {
        assertThrows("IP configuration and network capabilities are"
                        + "both null, cannot construct an empty EthernetConfiguration.",
                IllegalArgumentException.class,
                () -> new EthernetConfiguration.Builder()
                        .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                        .setPersistence(EthernetConfiguration.PERSISTENCE_IS_PERSISTED)
                        .build());
    }

    @Test
    public void testHashCodeConsistency() {
        EthernetConfiguration config = new EthernetConfiguration.Builder()
                        .setIpConfiguration(mIpConfiguration1)
                        .setNetworkCapabilities(mNetworkCapabilities)
                        .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                        .setPersistence(EthernetConfiguration.PERSISTENCE_NOT_PERSISTED)
                        .build();
        int hash1 = config.hashCode();
        int hash2 = config.hashCode();
        assertEquals(hash1, hash2);
    }

    @Test
    public void testHashCodeOfDifferentConfig() {
        EthernetConfiguration config1 = new EthernetConfiguration.Builder()
                .setIpConfiguration(mIpConfiguration1)
                .setNetworkCapabilities(mNetworkCapabilities)
                .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                .setPersistence(EthernetConfiguration.PERSISTENCE_NOT_PERSISTED)
                .build();
        EthernetConfiguration config2 = new EthernetConfiguration.Builder()
                .setIpConfiguration(mIpConfiguration2)
                .setNetworkCapabilities(mNetworkCapabilities)
                .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                .setPersistence(EthernetConfiguration.PERSISTENCE_NOT_PERSISTED)
                .build();
        int hash1 = config1.hashCode();
        int hash2 = config2.hashCode();
        assertNotEquals(hash1, hash2);
    }

    @Test
    public void testHashCodeOfTheSameConfig() {
        EthernetConfiguration config1 = new EthernetConfiguration.Builder()
                .setIpConfiguration(mIpConfiguration1)
                .setNetworkCapabilities(mNetworkCapabilities)
                .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                .setPersistence(EthernetConfiguration.PERSISTENCE_NOT_PERSISTED)
                .build();
        EthernetConfiguration config2 = new EthernetConfiguration.Builder()
                .setIpConfiguration(mIpConfiguration1)
                .setNetworkCapabilities(mNetworkCapabilities)
                .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                .setPersistence(EthernetConfiguration.PERSISTENCE_NOT_PERSISTED)
                .build();
        int hash1 = config1.hashCode();
        int hash2 = config2.hashCode();
        assertEquals(hash1, hash2);
    }

    @Test
    public void testToString() {
        EthernetConfiguration config = new EthernetConfiguration.Builder()
                .setIpConfiguration(mIpConfiguration1)
                .setNetworkCapabilities(mNetworkCapabilities)
                .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                .setPersistence(EthernetConfiguration.PERSISTENCE_NOT_PERSISTED)
                .build();
        String expect;
        // After version V, NOT_BANDWIDTH_CONSTRAINED is added to network capabilities by default
        // while devices before V do not have this default capability.
        if (SdkLevel.isAtLeastV()) {
            expect = "IP configurations: IP assignment: STATIC\n"
                    + "Static configuration: IP address 192.168.1.100/25 Gateway 192.168.1.1 "
                    + " DNS servers: [ 8.8.8.8 8.8.4.4 ] Domains \n"
                    + "Proxy settings: PAC\n"
                    + "HTTP proxy: [test1] 8888\n"
                    + "Network capabilities: [ Transports: ETHERNET Capabilities: NOT_RESTRICTED"
                    + "&TRUSTED&NOT_VPN&NOT_BANDWIDTH_CONSTRAINED UnderlyingNetworks: Null]"
                    + ", Metered override: FORCE_METERED, Is persisted: NOT_PERSISTED";
        } else {
            expect = "IP configurations: IP assignment: STATIC\n"
                    + "Static configuration: IP address 192.168.1.100/25 Gateway 192.168.1.1 "
                    + " DNS servers: [ 8.8.8.8 8.8.4.4 ] Domains \n"
                    + "Proxy settings: PAC\n"
                    + "HTTP proxy: [test1] 8888\n"
                    + "Network capabilities: [ Transports: ETHERNET Capabilities: NOT_RESTRICTED"
                    + "&TRUSTED&NOT_VPN UnderlyingNetworks: Null], Metered override: FORCE_METERED"
                    + ", Is persisted: NOT_PERSISTED";
        }
        assertEquals(config.toString(), expect);
    }

    @Test
    public void testParcel() {
        EthernetConfiguration config = new EthernetConfiguration.Builder()
                .setIpConfiguration(mIpConfiguration1)
                .setNetworkCapabilities(mNetworkCapabilities)
                .setMeteredOverride(EthernetConfiguration.METERED_OVERRIDE_FORCE_METERED)
                .setPersistence(EthernetConfiguration.PERSISTENCE_NOT_PERSISTED)
                .build();
        assertParcelingIsLossless(config);
    }
}
