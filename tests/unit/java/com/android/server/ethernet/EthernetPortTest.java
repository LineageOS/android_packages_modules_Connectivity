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

package com.android.server.ethernet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import android.net.MacAddress;
import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class EthernetPortTest {
    private final String mTestInterfaceName = "eth0";
    private final MacAddress mTestMacAddress = MacAddress.fromString("0A:1B:2C:3D:4E:5F");
    private final int mTestInterfaceIndex = 1;

    @Test
    public void testMakeEthernetPort() {
        EthernetPort port = new EthernetPort(
                mTestInterfaceName, mTestMacAddress, mTestInterfaceIndex);
        assertEquals(mTestInterfaceName, port.getInterfaceName());
        assertEquals(mTestMacAddress, port.getMacAddress());
        assertEquals(mTestInterfaceIndex, port.getInterfaceIndex());
    }

    @Test
    public void testMakeEthernetPortWithEmptyInterfaceName() {
        assertThrows("Ethernet port should have valid interface name, MAC address and "
                + "interface index.",
                IllegalArgumentException.class,
                () -> new EthernetPort("", mTestMacAddress, mTestInterfaceIndex));
    }

    @Test
    public void testMakeEthernetPortWithNullInterfaceName() {
        assertThrows("Ethernet port should have valid interface name, MAC address and "
                + "interface index.",
                IllegalArgumentException.class,
                () -> new EthernetPort(null, mTestMacAddress, mTestInterfaceIndex));
    }

    @Test
    public void testMakeEthernetPortWithNullMacAddress() {
        assertThrows("Ethernet port should have valid interface name, MAC address and "
                + "interface index.",
                IllegalArgumentException.class,
                () -> new EthernetPort(mTestInterfaceName, null, mTestInterfaceIndex));
    }

    @Test
    public void testMakeEthernetPortWithInvalidInterfaceIndex() {
        assertThrows("Ethernet port should have valid interface name, MAC address and "
                + "interface index.",
                IllegalArgumentException.class,
                () -> new EthernetPort(mTestInterfaceName, mTestMacAddress, 0));
    }

    @Test
    public void testHashCodeConsistency() {
        EthernetPort port = new EthernetPort(
                mTestInterfaceName, mTestMacAddress, mTestInterfaceIndex);
        int hash1 = port.hashCode();
        int hash2 = port.hashCode();
        assertEquals(hash1, hash2);
    }

    @Test
    public void testHashCodeOfDifferentConfig() {
        EthernetPort port1 = new EthernetPort(
                mTestInterfaceName, mTestMacAddress, mTestInterfaceIndex);
        EthernetPort port2 = new EthernetPort(
                "eth1", MacAddress.fromString("DE:AD:BE:EF:00:01"), 2);
        int hash1 = port1.hashCode();
        int hash2 = port2.hashCode();
        assertNotEquals(hash1, hash2);
    }

    @Test
    public void testHashCodeOfTheSameConfig() {
        EthernetPort port1 = new EthernetPort(
                mTestInterfaceName, mTestMacAddress, mTestInterfaceIndex);
        EthernetPort port2 = new EthernetPort(
                mTestInterfaceName, mTestMacAddress, mTestInterfaceIndex);
        int hash1 = port1.hashCode();
        int hash2 = port2.hashCode();
        assertEquals(hash1, hash2);
    }

    @Test
    public void testToString() {
        EthernetPort port = new EthernetPort(
                mTestInterfaceName, mTestMacAddress, mTestInterfaceIndex);
        String expect = "EthernetPort(eth0, ifindex=1, MAC=0a:1b:2c:3d:4e:5f)";
        assertEquals(expect, port.toString());
    }
}
