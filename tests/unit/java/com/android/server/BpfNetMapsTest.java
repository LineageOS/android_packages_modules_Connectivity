/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server;

import static android.net.ConnectivityManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_2;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_RESTRICTED;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_STANDBY;
import static android.net.INetd.PERMISSION_INTERNET;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.mockito.Mockito.verify;

import android.net.INetd;
import android.os.Build;
import android.os.ServiceSpecificException;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.Struct.U32;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.TestBpfMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public final class BpfNetMapsTest {
    private static final String TAG = "BpfNetMapsTest";

    @Rule
    public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    private static final int TEST_UID = 10086;
    private static final int[] TEST_UIDS = {10002, 10003};
    private static final String IFNAME = "wlan0";
    private static final String CHAINNAME = "fw_dozable";
    private static final U32 UID_RULES_CONFIGURATION_KEY = new U32(0);
    private static final List<Integer> FIREWALL_CHAINS = List.of(
            FIREWALL_CHAIN_DOZABLE,
            FIREWALL_CHAIN_STANDBY,
            FIREWALL_CHAIN_POWERSAVE,
            FIREWALL_CHAIN_RESTRICTED,
            FIREWALL_CHAIN_LOW_POWER_STANDBY,
            FIREWALL_CHAIN_OEM_DENY_1,
            FIREWALL_CHAIN_OEM_DENY_2,
            FIREWALL_CHAIN_OEM_DENY_3
    );

    private BpfNetMaps mBpfNetMaps;

    @Mock INetd mNetd;
    private final BpfMap<U32, U32> mConfigurationMap = new TestBpfMap<>(U32.class, U32.class);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        BpfNetMaps.setConfigurationMapForTest(mConfigurationMap);
        mBpfNetMaps = new BpfNetMaps(mNetd);
    }

    @Test
    public void testBpfNetMapsBeforeT() throws Exception {
        assumeFalse(SdkLevel.isAtLeastT());
        mBpfNetMaps.addUidInterfaceRules(IFNAME, TEST_UIDS);
        verify(mNetd).firewallAddUidInterfaceRules(IFNAME, TEST_UIDS);
        mBpfNetMaps.removeUidInterfaceRules(TEST_UIDS);
        verify(mNetd).firewallRemoveUidInterfaceRules(TEST_UIDS);
        mBpfNetMaps.setNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
        verify(mNetd).trafficSetNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
    }

    private void doTestIsChainEnabled(final List<Integer> enableChains) throws Exception {
        long match = 0;
        for (final int chain: enableChains) {
            match |= mBpfNetMaps.getMatchByFirewallChain(chain);
        }
        mConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(match));

        for (final int chain: FIREWALL_CHAINS) {
            final String testCase = "EnabledChains: " + enableChains + " CheckedChain: " + chain;
            if (enableChains.contains(chain)) {
                assertTrue("Expected isChainEnabled returns True, " + testCase,
                        mBpfNetMaps.isChainEnabled(chain));
            } else {
                assertFalse("Expected isChainEnabled returns False, " + testCase,
                        mBpfNetMaps.isChainEnabled(chain));
            }
        }
    }

    private void doTestIsChainEnabled(final int enableChain) throws Exception {
        doTestIsChainEnabled(List.of(enableChain));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabled() throws Exception {
        doTestIsChainEnabled(FIREWALL_CHAIN_DOZABLE);
        doTestIsChainEnabled(FIREWALL_CHAIN_STANDBY);
        doTestIsChainEnabled(FIREWALL_CHAIN_POWERSAVE);
        doTestIsChainEnabled(FIREWALL_CHAIN_RESTRICTED);
        doTestIsChainEnabled(FIREWALL_CHAIN_LOW_POWER_STANDBY);
        doTestIsChainEnabled(FIREWALL_CHAIN_OEM_DENY_1);
        doTestIsChainEnabled(FIREWALL_CHAIN_OEM_DENY_2);
        doTestIsChainEnabled(FIREWALL_CHAIN_OEM_DENY_3);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabledMultipleChainEnabled() throws Exception {
        doTestIsChainEnabled(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY));
        doTestIsChainEnabled(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY,
                FIREWALL_CHAIN_POWERSAVE,
                FIREWALL_CHAIN_RESTRICTED));
        doTestIsChainEnabled(FIREWALL_CHAINS);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabledInvalidChain() {
        final Class<ServiceSpecificException> expected = ServiceSpecificException.class;
        assertThrows(expected, () -> mBpfNetMaps.isChainEnabled(-1 /* childChain */));
        assertThrows(expected, () -> mBpfNetMaps.isChainEnabled(1000 /* childChain */));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabledBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.isChainEnabled(FIREWALL_CHAIN_DOZABLE));
    }

    private void doTestSetChildChain(final List<Integer> testChains) throws Exception {
        long expectedMatch = 0;
        for (final int chain: testChains) {
            expectedMatch |= mBpfNetMaps.getMatchByFirewallChain(chain);
        }

        assertEquals(0, mConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val);

        for (final int chain: testChains) {
            mBpfNetMaps.setChildChain(chain, true /* enable */);
        }
        assertEquals(expectedMatch, mConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val);

        for (final int chain: testChains) {
            mBpfNetMaps.setChildChain(chain, false /* enable */);
        }
        assertEquals(0, mConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val);
    }

    private void doTestSetChildChain(final int testChain) throws Exception {
        doTestSetChildChain(List.of(testChain));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetChildChain() throws Exception {
        mConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(0));
        doTestSetChildChain(FIREWALL_CHAIN_DOZABLE);
        doTestSetChildChain(FIREWALL_CHAIN_STANDBY);
        doTestSetChildChain(FIREWALL_CHAIN_POWERSAVE);
        doTestSetChildChain(FIREWALL_CHAIN_RESTRICTED);
        doTestSetChildChain(FIREWALL_CHAIN_LOW_POWER_STANDBY);
        doTestSetChildChain(FIREWALL_CHAIN_OEM_DENY_1);
        doTestSetChildChain(FIREWALL_CHAIN_OEM_DENY_2);
        doTestSetChildChain(FIREWALL_CHAIN_OEM_DENY_3);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetChildChainMultipleChain() throws Exception {
        mConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(0));
        doTestSetChildChain(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY));
        doTestSetChildChain(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY,
                FIREWALL_CHAIN_POWERSAVE,
                FIREWALL_CHAIN_RESTRICTED));
        doTestSetChildChain(FIREWALL_CHAINS);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetChildChainInvalidChain() {
        final Class<ServiceSpecificException> expected = ServiceSpecificException.class;
        assertThrows(expected,
                () -> mBpfNetMaps.setChildChain(-1 /* childChain */, true /* enable */));
        assertThrows(expected,
                () -> mBpfNetMaps.setChildChain(1000 /* childChain */, true /* enable */));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testSetChildChainBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.setChildChain(FIREWALL_CHAIN_DOZABLE, true /* enable */));
    }
}
