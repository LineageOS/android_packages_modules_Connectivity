/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net

import android.net.BpfNetMapsConstants.UID_RULES_CONFIGURATION_KEY
import android.net.BpfNetMapsUtils.getMatchByFirewallChain
import android.os.Build
import com.android.net.module.util.IBpfMap
import com.android.net.module.util.Struct.S32
import com.android.net.module.util.Struct.U32
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestBpfMap
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// pre-T devices does not support Bpf.
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
class BpfNetMapsReaderTest {
    private val testConfigurationMap: IBpfMap<S32, U32> = TestBpfMap()
    private val testUidOwnerMap: IBpfMap<S32, UidOwnerValue> = TestBpfMap()
    private val bpfNetMapsReader = BpfNetMapsReader(
        TestDependencies(testConfigurationMap, testUidOwnerMap))

    class TestDependencies(
        private val configMap: IBpfMap<S32, U32>,
        private val uidOwnerMap: IBpfMap<S32, UidOwnerValue>
    ) : BpfNetMapsReader.Dependencies() {
        override fun getConfigurationMap() = configMap
        override fun getUidOwnerMap() = uidOwnerMap
    }

    private fun doTestIsChainEnabled(chain: Int) {
        testConfigurationMap.updateEntry(
            UID_RULES_CONFIGURATION_KEY,
            U32(getMatchByFirewallChain(chain))
        )
        assertTrue(bpfNetMapsReader.isChainEnabled(chain))
        testConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, U32(0))
        assertFalse(bpfNetMapsReader.isChainEnabled(chain))
    }

    @Test
    @Throws(Exception::class)
    fun testIsChainEnabled() {
        doTestIsChainEnabled(ConnectivityManager.FIREWALL_CHAIN_DOZABLE)
        doTestIsChainEnabled(ConnectivityManager.FIREWALL_CHAIN_STANDBY)
        doTestIsChainEnabled(ConnectivityManager.FIREWALL_CHAIN_POWERSAVE)
        doTestIsChainEnabled(ConnectivityManager.FIREWALL_CHAIN_RESTRICTED)
        doTestIsChainEnabled(ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY)
    }
}
