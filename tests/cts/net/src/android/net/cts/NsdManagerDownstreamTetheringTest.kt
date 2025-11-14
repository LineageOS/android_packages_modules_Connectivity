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
package android.net.cts

import android.net.EthernetTetheringTestBase
import android.net.LinkAddress
import android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL
import android.net.TetheringManager.TETHERING_ETHERNET
import android.net.TetheringManager.TetheringRequest
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.platform.test.annotations.AppModeFull
import androidx.test.filters.SmallTest
import com.android.testutils.AutoCloseTestInterfaceRule
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.EthernetTestInterface
import com.android.testutils.NsdDiscoveryRecord
import com.android.testutils.pollForQuery
import com.android.testutils.tryTest
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "NsdManagerDownstreamTetheringTest"

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@ConnectivityModuleTest
@AppModeFull(reason = "WifiManager cannot be obtained in instant mode")
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class NsdManagerDownstreamTetheringTest : EthernetTetheringTestBase() {
    private val nsdManager by lazy { context.getSystemService(NsdManager::class.java)!! }
    private val serviceType = "_nmt%09d._tcp".format(Random().nextInt(1_000_000_000))

    private val handlerThread = HandlerThread("$TAG thread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private lateinit var downstreamIface: EthernetTestInterface
    private var tetheringEventCallback: MyTetheringEventCallback? = null

    @get:Rule
    val testInterfaceRule = AutoCloseTestInterfaceRule(context)

    @Before
    override fun setUp() {
        super.setUp()
        val iface = testInterfaceRule.createTapInterface()
        downstreamIface = EthernetTestInterface(context, handler, iface)
    }

    @After
    override fun tearDown() {
        if (::downstreamIface.isInitialized) {
            downstreamIface.destroy()
        }
        handlerThread.quitSafely()
        handlerThread.join()
        maybeUnregisterTetheringEventCallback(tetheringEventCallback)
        super.tearDown()
    }

    @Test
    fun testMdnsDiscoveryCanSendPacketOnLocalOnlyDownstreamTetheringInterface() {
        assumeFalse(isInterfaceForTetheringAvailable())

        val discoveryRecord = NsdDiscoveryRecord()

        tryTest {
            val iface = mTetheredInterfaceRequester.getInterface()
            assertEquals(downstreamIface.name, iface)
            val request = TetheringRequest.Builder(TETHERING_ETHERNET)
                .setConnectivityScope(CONNECTIVITY_SCOPE_LOCAL).build()
            tetheringEventCallback = enableTethering(
                iface, request,
                null /* any upstream */
            ).apply {
                awaitInterfaceLocalOnly()
            }
            val downstreamReader = downstreamIface.packetReader
            waitForRouterAdvertisement(downstreamReader, iface, WAIT_RA_TIMEOUT_MS)

            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)
            discoveryRecord.expectCallback<NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStarted>()
            assertNotNull(downstreamReader.pollForQuery("$serviceType.local", 12 /* type PTR */))
        } cleanup {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStopped>()
        }
    }

    @Test
    fun testMdnsDiscoveryWorkOnTetheringInterface() {
        assumeFalse(isInterfaceForTetheringAvailable())

        val discoveryRecord = NsdDiscoveryRecord()

        tryTest {
            val iface = mTetheredInterfaceRequester.getInterface()
            assertEquals(downstreamIface.name, iface)

            val localAddr = LinkAddress("192.0.2.3/28")
            val clientAddr = LinkAddress("192.0.2.2/28")
            val request = TetheringRequest.Builder(TETHERING_ETHERNET)
                .setStaticIpv4Addresses(localAddr, clientAddr)
                .setShouldShowEntitlementUi(false).build()
            tetheringEventCallback = enableTethering(
                iface, request,
                null /* any upstream */
            ).apply {
                awaitInterfaceTethered()
            }

            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)
            discoveryRecord.expectCallback<NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStarted>()
            val downstreamReader = downstreamIface.packetReader
            assertNotNull(downstreamReader.pollForQuery("$serviceType.local", 12 /* type PTR */))
            // TODO: Add another test to check packet reply can trigger serviceFound.
        } cleanup {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStopped>()
        }
    }
}
