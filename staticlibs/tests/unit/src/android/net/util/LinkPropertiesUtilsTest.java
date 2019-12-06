/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.util.LinkPropertiesUtils.CompareResult;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;

@RunWith(AndroidJUnit4.class)
public final class LinkPropertiesUtilsTest {
    private static final IpPrefix PREFIX = new IpPrefix(toInetAddress("75.208.6.0"), 24);
    private static final InetAddress V4_ADDR = toInetAddress("75.208.6.1");
    private static final InetAddress V6_ADDR  = toInetAddress(
            "2001:0db8:85a3:0000:0000:8a2e:0370:7334");
    private static final InetAddress DNS1 = toInetAddress("75.208.7.1");
    private static final InetAddress DNS2 = toInetAddress("69.78.7.1");

    private static final InetAddress GATEWAY1 = toInetAddress("75.208.8.1");
    private static final InetAddress GATEWAY2 = toInetAddress("69.78.8.1");

    private static final String IF_NAME = "wlan0";
    private static final LinkAddress V4_LINKADDR = new LinkAddress(V4_ADDR, 32);
    private static final LinkAddress V6_LINKADDR = new LinkAddress(V6_ADDR, 128);
    private static final RouteInfo RT_INFO1 = new RouteInfo(PREFIX, GATEWAY1, IF_NAME);
    private static final RouteInfo RT_INFO2 = new RouteInfo(PREFIX, GATEWAY2, IF_NAME);
    private static final String TEST_DOMAIN = "link.properties.com";

    private static InetAddress toInetAddress(String addrString) {
        return InetAddresses.parseNumericAddress(addrString);
    }

    private LinkProperties createTestObject() {
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(IF_NAME);
        lp.addLinkAddress(V4_LINKADDR);
        lp.addLinkAddress(V6_LINKADDR);
        lp.addDnsServer(DNS1);
        lp.addDnsServer(DNS2);
        lp.setDomains(TEST_DOMAIN);
        lp.addRoute(RT_INFO1);
        lp.addRoute(RT_INFO2);
        lp.setHttpProxy(ProxyInfo.buildDirectProxy("test", 8888));
        return lp;
    }

    @Test
    public void testLinkPropertiesIdenticalEqual() {
        final LinkProperties source = createTestObject();
        final LinkProperties target = new LinkProperties(source);

        assertTrue(LinkPropertiesUtils.isIdenticalInterfaceName(source, target));
        assertTrue(LinkPropertiesUtils.isIdenticalInterfaceName(target, source));

        assertTrue(LinkPropertiesUtils.isIdenticalAddresses(source, target));
        assertTrue(LinkPropertiesUtils.isIdenticalAddresses(target, source));

        assertTrue(LinkPropertiesUtils.isIdenticalDnses(source, target));
        assertTrue(LinkPropertiesUtils.isIdenticalDnses(target, source));

        assertTrue(LinkPropertiesUtils.isIdenticalRoutes(source, target));
        assertTrue(LinkPropertiesUtils.isIdenticalRoutes(target, source));

        assertTrue(LinkPropertiesUtils.isIdenticalHttpProxy(source, target));
        assertTrue(LinkPropertiesUtils.isIdenticalHttpProxy(target, source));

        // Test different interface name.
        target.setInterfaceName("lo");
        assertFalse(LinkPropertiesUtils.isIdenticalInterfaceName(source, target));
        assertFalse(LinkPropertiesUtils.isIdenticalInterfaceName(target, source));
        // Restore interface name
        target.setInterfaceName(IF_NAME);

        // Compare addresses.size() not equals.
        final LinkAddress testLinkAddr = new LinkAddress(toInetAddress("75.208.6.2"), 32);
        target.addLinkAddress(testLinkAddr);
        assertFalse(LinkPropertiesUtils.isIdenticalAddresses(source, target));
        assertFalse(LinkPropertiesUtils.isIdenticalAddresses(target, source));

        // Currently, target contains V4_LINKADDR, V6_LINKADDR and testLinkAddr.
        // Compare addresses.size() equals but contains different address.
        target.removeLinkAddress(V4_LINKADDR);
        assertEquals(source.getAddresses().size(), target.getAddresses().size());
        assertFalse(LinkPropertiesUtils.isIdenticalAddresses(source, target));
        assertFalse(LinkPropertiesUtils.isIdenticalAddresses(target, source));
        // Restore link address
        target.addLinkAddress(V4_LINKADDR);
        target.removeLinkAddress(testLinkAddr);

        // Compare size not equals.
        target.addDnsServer(toInetAddress("75.208.10.1"));
        assertFalse(LinkPropertiesUtils.isIdenticalDnses(source, target));
        assertFalse(LinkPropertiesUtils.isIdenticalDnses(target, source));

        // Compare the same servers but target has different domains.
        target.removeDnsServer(toInetAddress("75.208.10.1"));
        target.setDomains("test.com");
        assertFalse(LinkPropertiesUtils.isIdenticalDnses(source, target));
        assertFalse(LinkPropertiesUtils.isIdenticalDnses(target, source));

        // Test null domain.
        target.setDomains(null);
        assertFalse(LinkPropertiesUtils.isIdenticalDnses(source, target));
        assertFalse(LinkPropertiesUtils.isIdenticalDnses(target, source));
        // Restore domain
        target.setDomains(TEST_DOMAIN);

        // Compare size not equals.
        final RouteInfo testRoute = new RouteInfo(toInetAddress("75.208.7.1"));
        target.addRoute(testRoute);
        assertFalse(LinkPropertiesUtils.isIdenticalRoutes(source, target));
        assertFalse(LinkPropertiesUtils.isIdenticalRoutes(target, source));

        // Currently, target contains RT_INFO1, RT_INFO2 and testRoute.
        // Compare size equals but different routes.
        target.removeRoute(RT_INFO1);
        assertEquals(source.getRoutes().size(), target.getRoutes().size());
        assertFalse(LinkPropertiesUtils.isIdenticalRoutes(source, target));
        assertFalse(LinkPropertiesUtils.isIdenticalRoutes(target, source));
        // Restore route
        target.addRoute(RT_INFO1);
        target.removeRoute(testRoute);

        // Test different proxy.
        target.setHttpProxy(ProxyInfo.buildDirectProxy("hello", 8888));
        assertFalse(LinkPropertiesUtils.isIdenticalHttpProxy(source, target));
        assertFalse(LinkPropertiesUtils.isIdenticalHttpProxy(target, source));

        // Test null proxy.
        target.setHttpProxy(null);
        assertFalse(LinkPropertiesUtils.isIdenticalHttpProxy(source, target));
        assertFalse(LinkPropertiesUtils.isIdenticalHttpProxy(target, source));
    }

    @Test
    public void testCompareAddresses() {
        final LinkProperties source = createTestObject();
        final LinkProperties target = new LinkProperties(source);
        final InetAddress addr1 = toInetAddress("75.208.6.2");
        final LinkAddress linkAddr1 = new LinkAddress(addr1, 32);

        CompareResult<LinkAddress> results = LinkPropertiesUtils.compareAddresses(source, target);
        assertEquals(0, results.removed.size());
        assertEquals(0, results.added.size());

        source.addLinkAddress(linkAddr1);
        results = LinkPropertiesUtils.compareAddresses(source, target);
        assertEquals(1, results.removed.size());
        assertEquals(linkAddr1, results.removed.get(0));
        assertEquals(0, results.added.size());

        final InetAddress addr2 = toInetAddress("75.208.6.3");
        final LinkAddress linkAddr2 = new LinkAddress(addr2, 32);

        target.addLinkAddress(linkAddr2);
        results = LinkPropertiesUtils.compareAddresses(source, target);
        assertEquals(linkAddr1, results.removed.get(0));
        assertEquals(linkAddr2, results.added.get(0));
    }
}
