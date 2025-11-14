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
import static org.junit.Assert.assertThrows;

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
public class EthernetPortInfoTest {
    private final String mTestInterfaceName1 = "eth0";
    private final MacAddress mTestMacAddress1 = MacAddress.fromString("0A:1B:2C:3D:4E:5F");
    private final int mTestInterfaceIndex1 = 1;

    private final String mTestInterfaceName2 = "eth1";
    private final MacAddress mTestMacAddress2 = MacAddress.fromString("DE:AD:BE:EF:00:01");
    private final int mTestInterfaceIndex2 = 2;

    private EthernetConfiguration mConfig1;
    private EthernetConfiguration mConfig2;
    private NetworkCapabilities mNetworkCapabilities;
    private EthernetPortInfo mEthernetPortInfo1;
    private EthernetPortInfo mEthernetPortInfo2;

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

        IpConfiguration ipConfig1 = new IpConfiguration.Builder()
                .setStaticIpConfiguration(staticIpConfig)
                .setHttpProxy(proxy1)
                .build();
        IpConfiguration ipConfig2 = new IpConfiguration.Builder()
                .setStaticIpConfiguration(staticIpConfig)
                .setHttpProxy(proxy2)
                .build();

        mNetworkCapabilities = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
        mConfig1 = new EthernetConfiguration(ipConfig1, mNetworkCapabilities);
        mConfig2 = new EthernetConfiguration(ipConfig2, mNetworkCapabilities);

        mEthernetPortInfo1 = new EthernetPortInfo(
                mTestInterfaceName1, mTestMacAddress1, mTestInterfaceIndex1, mConfig1,
                EthernetManager.ROLE_CLIENT, EthernetManager.STATE_LINK_UP);
        mEthernetPortInfo2 = new EthernetPortInfo(
                mTestInterfaceName2, mTestMacAddress2, mTestInterfaceIndex2, mConfig2,
                EthernetManager.ROLE_CLIENT, EthernetManager.STATE_LINK_UP);
    }

    @Test
    public void testConstructor() {
        EthernetPortInfo portInfo = new EthernetPortInfo(
                mTestInterfaceName1, mTestMacAddress1, mTestInterfaceIndex1, mConfig1,
                EthernetManager.ROLE_CLIENT, EthernetManager.STATE_LINK_UP);
        assertEquals(portInfo.getInterfaceName(), mTestInterfaceName1);
        assertEquals(portInfo.getMacAddress(), mTestMacAddress1);
        assertEquals(portInfo.getInterfaceIndex(), mTestInterfaceIndex1);
        assertEquals(portInfo.getConfiguration(), mConfig1);
        assertEquals(portInfo.getMeteredConfiguration(),
                mNetworkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        assertEquals(portInfo.getRole(), EthernetManager.ROLE_CLIENT);
        assertEquals(portInfo.getState(), EthernetManager.STATE_LINK_UP);
    }

    @Test
    public void testConstructorWithNullIfname() {
        assertThrows("All of interface name, MAC address and interface"
                        + " index need to be valid.",
                IllegalArgumentException.class,
                () -> new EthernetPortInfo(
                        null, mTestMacAddress1, mTestInterfaceIndex1, mConfig1,
                        EthernetManager.ROLE_CLIENT, EthernetManager.STATE_LINK_UP));
    }

    @Test
    public void testConstructorWithEmptyIfname() {
        assertThrows("All of interface name, MAC address and interface"
                        + " index need to be valid.",
                IllegalArgumentException.class,
                () -> new EthernetPortInfo(
                        "", mTestMacAddress1, mTestInterfaceIndex1, mConfig1,
                        EthernetManager.ROLE_CLIENT, EthernetManager.STATE_LINK_UP));
    }

    @Test
    public void testConstructorWithNullAddress() {
        assertThrows("All of interface name, MAC address and interface"
                        + " index need to be valid.",
                IllegalArgumentException.class,
                () -> new EthernetPortInfo(
                        mTestInterfaceName1, null, mTestInterfaceIndex1, mConfig1,
                        EthernetManager.ROLE_CLIENT, EthernetManager.STATE_LINK_UP));
    }

    @Test
    public void testConstructorWithInvalidIndex() {
        assertThrows("All of interface name, MAC address and interface"
                        + " index need to be valid.",
                IllegalArgumentException.class,
                () -> new EthernetPortInfo(
                        mTestInterfaceName1, mTestMacAddress1, 0, mConfig1,
                        EthernetManager.ROLE_CLIENT, EthernetManager.STATE_LINK_UP));
    }

    @Test
    public void testConstructorWithNullConfig() {
        assertThrows(NullPointerException.class,
                () -> new EthernetPortInfo(
                        mTestInterfaceName1, mTestMacAddress1, mTestInterfaceIndex1, null,
                        EthernetManager.ROLE_CLIENT, EthernetManager.STATE_LINK_UP));
    }

    @Test
    public void testConstructorWithInvalidRole() {
        assertThrows("Interface role is not valid, should be one of "
                        + "{EthernetManager.ROLE_SERVER, EthernetManager.ROLE_CLIENT, "
                        + "EthernetManager.ROLE_NONE}.",
                IllegalArgumentException.class,
                () -> new EthernetPortInfo(
                        mTestInterfaceName1, mTestMacAddress1, mTestInterfaceIndex1, mConfig1,
                        -1, EthernetManager.STATE_LINK_UP));
    }

    @Test
    public void testConstructorWithInvalidInterfaceState() {
        assertThrows("Interface state is not valid, should be one of "
                        + "{EthernetManager.STATE_ABSENT, EthernetManager.STATE_LINK_DOWN, "
                        + "EthernetManager.STATE_LINK_UP}.",
                IllegalArgumentException.class,
                () -> new EthernetPortInfo(
                mTestInterfaceName1, mTestMacAddress1, mTestInterfaceIndex1, mConfig1,
                EthernetManager.ROLE_CLIENT, -1));
    }

    @Test
    public void testHashCodeConsistency() {
        int hash1 = mEthernetPortInfo1.hashCode();
        int hash2 = mEthernetPortInfo1.hashCode();
        assertEquals(hash1, hash2);
    }

    @Test
    public void testHashCodeOfDifferentConfig() {
        int hash1 = mEthernetPortInfo1.hashCode();
        int hash2 = mEthernetPortInfo2.hashCode();
        assertNotEquals(hash1, hash2);
    }

    @Test
    public void testHashCodeOfTheSameConfig() {
        EthernetPortInfo portInfo1 = new EthernetPortInfo(
                mTestInterfaceName1, mTestMacAddress1, mTestInterfaceIndex1, mConfig1,
                EthernetManager.ROLE_CLIENT, EthernetManager.STATE_LINK_UP);
        EthernetPortInfo portInfo2 = new EthernetPortInfo(
                mTestInterfaceName1, mTestMacAddress1, mTestInterfaceIndex1, mConfig1,
                EthernetManager.ROLE_CLIENT, EthernetManager.STATE_LINK_UP);
        int hash1 = portInfo1.hashCode();
        int hash2 = portInfo2.hashCode();
        assertEquals(hash1, hash2);
    }

    @Test
    public void testToString() {
        String expect;
        // For devices with SDK level at least V, NOT_BANDWIDTH_CONSTRAINED is included as a
        // default capability.
        if (SdkLevel.isAtLeastV()) {
            expect = "Role:1, State:2, Configurations: IP configurations: "
                    + "IP assignment: STATIC\n"
                    + "Static configuration: IP address 192.168.1.100/25 Gateway 192.168.1.1 "
                    + " DNS servers: [ 8.8.8.8 8.8.4.4 ] Domains \n"
                    + "Proxy settings: PAC\n"
                    + "HTTP proxy: [test1] 8888\n"
                    + "Network capabilities: [ Transports: ETHERNET Capabilities: "
                    + "NOT_RESTRICTED&TRUSTED&NOT_VPN&NOT_BANDWIDTH_CONSTRAINED "
                    + "UnderlyingNetworks: Null], Interface name:eth0, "
                    + "MAC address:0a:1b:2c:3d:4e:5f, Interface index:1";
        } else {
            expect = "Role:1, State:2, Configurations: IP configurations: "
                    + "IP assignment: STATIC\n"
                    + "Static configuration: IP address 192.168.1.100/25 Gateway 192.168.1.1 "
                    + " DNS servers: [ 8.8.8.8 8.8.4.4 ] Domains \n"
                    + "Proxy settings: PAC\n"
                    + "HTTP proxy: [test1] 8888\n"
                    + "Network capabilities: [ Transports: ETHERNET Capabilities: "
                    + "NOT_RESTRICTED&TRUSTED&NOT_VPN UnderlyingNetworks: Nu"
                    + "ll], Interface name:eth0, MAC address:0a:1b:2c:3d:4e:5f, Interface index:1";

        }
        assertEquals(mEthernetPortInfo1.toString(), expect);
    }

    @Test
    public void testParcel() {
        assertParcelingIsLossless(mEthernetPortInfo1);
    }
}
