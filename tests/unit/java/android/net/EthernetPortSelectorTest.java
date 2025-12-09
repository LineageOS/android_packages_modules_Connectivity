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

import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
@RunWith(DevSdkIgnoreRunner.class)
public class EthernetPortSelectorTest {
    private final MacAddress mTestMacAddr = MacAddress.fromString("aa:bb:cc:dd:ee:ff");
    private final String mTestIfaceName1 = "testIface";
    private final String mTestIfaceName2 = "testIfaceTwo";

    @Test
    public void testConstructorWithIface() {
        final EthernetPortSelector ns = new EthernetPortSelector(mTestIfaceName1);
        assertEquals(mTestIfaceName1, ns.getInterfaceName());
        assertNull(ns.getMacAddress());
    }

    @Test
    public void testConstructorWithMacAddress() {
        final EthernetPortSelector ns = new EthernetPortSelector(mTestMacAddr);
        assertEquals(mTestMacAddr, ns.getMacAddress());
        assertNull(ns.getInterfaceName());
    }

    @Test
    public void testConstructorWithNullIface() {
        assertThrows("Interface name cannot be empty.",
                IllegalArgumentException.class,
                () -> new EthernetPortSelector((String) null));
    }

    @Test
    public void testConstructorWithNullMacAddr() {
        assertThrows("MAC address cannot be null.",
                IllegalArgumentException.class,
                () -> new EthernetPortSelector((MacAddress) null));
    }

    @Test
    public void testEquals() {
        final EthernetPortSelector nsOne =
                new EthernetPortSelector(mTestIfaceName1);
        final EthernetPortSelector nsTwo =
                new EthernetPortSelector(mTestIfaceName1);
        assertEquals(nsOne, nsTwo);
    }

    @Test
    public void testNotEquals() {
        final EthernetPortSelector nsOne =
                new EthernetPortSelector(mTestIfaceName1);
        final EthernetPortSelector nsTwo =
                new EthernetPortSelector(mTestIfaceName2);
        assertNotEquals(nsOne, nsTwo);
    }

    @Test
    public void testHashCodeOfDifferentSpecifier() {
        final EthernetPortSelector nsOne =
                new EthernetPortSelector(mTestIfaceName1);
        final EthernetPortSelector nsTwo =
                new EthernetPortSelector(mTestIfaceName2);
        int hash1 = nsOne.hashCode();
        int hash2 = nsTwo.hashCode();
        assertNotEquals(hash1, hash2);
    }

    @Test
    public void testHashCodeOfTheSameSpecifier() {
        final EthernetPortSelector nsOne =
                new EthernetPortSelector(mTestIfaceName1);
        final EthernetPortSelector nsTwo =
                new EthernetPortSelector(mTestIfaceName1);
        int hash1 = nsOne.hashCode();
        int hash2 = nsTwo.hashCode();
        assertEquals(hash1, hash2);
    }

    @Test
    public void testToString() {
        final EthernetPortSelector nsOne =
                new EthernetPortSelector(mTestIfaceName1);
        String expectOne = "EthernetPortSelector (testIface)";
        assertEquals(nsOne.toString(), expectOne);

        final EthernetPortSelector nsTwo =
                new EthernetPortSelector(mTestMacAddr);
        String expectTwo = "EthernetPortSelector (aa:bb:cc:dd:ee:ff)";
        assertEquals(nsTwo.toString(), expectTwo);
    }

    @Test
    public void testParcel() {
        final EthernetPortSelector nsOne =
                new EthernetPortSelector(mTestIfaceName1);
        assertParcelingIsLossless(nsOne);

        final EthernetPortSelector nsTwo =
                new EthernetPortSelector(mTestMacAddr);
        assertParcelingIsLossless(nsTwo);
    }
}
