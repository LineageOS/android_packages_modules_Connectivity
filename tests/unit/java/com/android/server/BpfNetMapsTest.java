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

import static android.net.INetd.FIREWALL_CHAIN_DOZABLE;
import static android.net.INetd.FIREWALL_RULE_ALLOW;
import static android.net.INetd.PERMISSION_INTERNET;

import static org.junit.Assume.assumeFalse;
import static org.mockito.Mockito.verify;

import android.net.INetd;
import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.build.SdkLevel;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public final class BpfNetMapsTest {
    private static final String TAG = "BpfNetMapsTest";
    private static final int TEST_UID = 10086;
    private static final int[] TEST_UIDS = {10002, 10003};
    private static final String IFNAME = "wlan0";
    private static final String CHAINNAME = "fw_dozable";
    private BpfNetMaps mBpfNetMaps;

    @Mock INetd mNetd;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mBpfNetMaps = new BpfNetMaps(mNetd);
    }

    @Test
    public void testBpfNetMapsBeforeT() throws Exception {
        assumeFalse(SdkLevel.isAtLeastT());
        mBpfNetMaps.addNaughtyApp(TEST_UID);
        verify(mNetd).bandwidthAddNaughtyApp(TEST_UID);
        mBpfNetMaps.removeNaughtyApp(TEST_UID);
        verify(mNetd).bandwidthRemoveNaughtyApp(TEST_UID);
        mBpfNetMaps.addNiceApp(TEST_UID);
        verify(mNetd).bandwidthAddNiceApp(TEST_UID);
        mBpfNetMaps.removeNiceApp(TEST_UID);
        verify(mNetd).bandwidthRemoveNiceApp(TEST_UID);
        mBpfNetMaps.setChildChain(FIREWALL_CHAIN_DOZABLE, true);
        verify(mNetd).firewallEnableChildChain(FIREWALL_CHAIN_DOZABLE, true);
        mBpfNetMaps.replaceUidChain(CHAINNAME, true, TEST_UIDS);
        verify(mNetd).firewallReplaceUidChain(CHAINNAME, true, TEST_UIDS);
        mBpfNetMaps.setUidRule(FIREWALL_CHAIN_DOZABLE, TEST_UID, FIREWALL_RULE_ALLOW);
        verify(mNetd).firewallSetUidRule(FIREWALL_CHAIN_DOZABLE, TEST_UID, FIREWALL_RULE_ALLOW);
        mBpfNetMaps.addUidInterfaceRules(IFNAME, TEST_UIDS);
        verify(mNetd).firewallAddUidInterfaceRules(IFNAME, TEST_UIDS);
        mBpfNetMaps.removeUidInterfaceRules(TEST_UIDS);
        verify(mNetd).firewallRemoveUidInterfaceRules(TEST_UIDS);
        mBpfNetMaps.swapActiveStatsMap();
        verify(mNetd).trafficSwapActiveStatsMap();
        mBpfNetMaps.setNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
        verify(mNetd).trafficSetNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
    }
}
