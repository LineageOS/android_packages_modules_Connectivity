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
package android.net.cts

import android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS
import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.Manifest.permission.NETWORK_SETTINGS
import android.content.Context
import android.net.ConnectivityManager
import android.net.EthernetManager
import android.net.EthernetManager.ETHERNET_STATE_DISABLED
import android.net.EthernetManager.ETHERNET_STATE_ENABLED
import android.net.EthernetManager.InterfaceStateListener
import android.net.EthernetManager.ROLE_CLIENT
import android.net.EthernetManager.ROLE_NONE
import android.net.EthernetManager.ROLE_SERVER
import android.net.EthernetManager.STATE_ABSENT
import android.net.EthernetManager.STATE_LINK_DOWN
import android.net.EthernetManager.STATE_LINK_UP
import android.net.EthernetManager.TetheredInterfaceCallback
import android.net.EthernetManager.TetheredInterfaceRequest
import android.net.EthernetNetworkManagementException
import android.net.EthernetNetworkSpecifier
import android.net.EthernetNetworkUpdateRequest
import android.net.InetAddresses
import android.net.IpConfiguration
import android.net.LinkAddress
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkRequest
import android.net.StaticIpConfiguration
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.cts.EthernetManagerTest.EthernetStateListener.CallbackEntry.EthernetStateChanged
import android.net.cts.EthernetManagerTest.EthernetStateListener.CallbackEntry.InterfaceStateChanged
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.os.SystemProperties
import android.platform.test.annotations.AppModeFull
import android.util.ArraySet
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.ArrayTrackRecord
import com.android.net.module.util.TrackRecord
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.DeviceInfoUtils.isKernelVersionAtLeast
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import com.android.testutils.RouterAdvertisementResponder
import com.android.testutils.SkipPresubmit
import com.android.testutils.TapPacketReader
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.anyNetwork
import com.android.testutils.runAsShell
import com.android.testutils.waitForIdle
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet6Address
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.IntConsumer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val TAG = "EthernetManagerTest"
private const val TIMEOUT_MS = 1000L
// Timeout used to confirm no callbacks matching given criteria are received. Must be long enough to
// process all callbacks including ip provisioning when using the updateConfiguration API.
// Note that increasing this timeout increases the test duration.
private const val NO_CALLBACK_TIMEOUT_MS = 200L

private val DEFAULT_IP_CONFIGURATION = IpConfiguration(IpConfiguration.IpAssignment.DHCP,
        IpConfiguration.ProxySettings.NONE, null, null)
private val ETH_REQUEST: NetworkRequest = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_TEST)
        .addTransportType(TRANSPORT_ETHERNET)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .build()
private val STATIC_IP_CONFIGURATION = IpConfiguration.Builder()
        .setStaticIpConfiguration(StaticIpConfiguration.Builder()
                .setIpAddress(LinkAddress("192.0.2.1/30")).build())
        .build()

@AppModeFull(reason = "Instant apps can't access EthernetManager")
// EthernetManager is not updatable before T, so tests do not need to be backwards compatible.
@RunWith(DevSdkIgnoreRunner::class)
// This test depends on behavior introduced post-T as part of connectivity module updates
@ConnectivityModuleTest
@SkipPresubmit(reason = "Flaky: b/240323229; remove annotation after fixing")
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class EthernetManagerTest {

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val em by lazy { context.getSystemService(EthernetManager::class.java) }
    private val cm by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val ifaceListener = EthernetStateListener()
    private val createdIfaces = ArrayList<EthernetTestInterface>()
    private val addedListeners = ArrayList<EthernetStateListener>()
    private val registeredCallbacks = ArrayList<TestableNetworkCallback>()

    private var tetheredInterfaceRequest: TetheredInterfaceRequest? = null

    private class EthernetTestInterface(
        context: Context,
        private val handler: Handler,
        hasCarrier: Boolean
    ) {
        private val tapInterface: TestNetworkInterface
        private val packetReader: TapPacketReader
        private val raResponder: RouterAdvertisementResponder
        private val tnm: TestNetworkManager
        val name get() = tapInterface.interfaceName

        init {
            tnm = runAsShell(MANAGE_TEST_NETWORKS) {
                context.getSystemService(TestNetworkManager::class.java)
            }
            tapInterface = runAsShell(MANAGE_TEST_NETWORKS) {
                // setting RS delay to 0 and disabling DAD speeds up tests.
                tnm.createTapInterface(hasCarrier, false /* bringUp */,
                        true /* disableIpv6ProvisioningDelay */)
            }
            val mtu = tapInterface.mtu
            packetReader = TapPacketReader(handler, tapInterface.fileDescriptor.fileDescriptor, mtu)
            raResponder = RouterAdvertisementResponder(packetReader)
            val iidString = "fe80::${Integer.toHexString(Random().nextInt(65536))}"
            val linklocal = InetAddresses.parseNumericAddress(iidString) as Inet6Address
            raResponder.addRouterEntry(MacAddress.fromString("01:23:45:67:89:ab"), linklocal)

            packetReader.startAsyncForTest()
            raResponder.start()
        }

        // WARNING: this function requires kernel support. Call assumeChangingCarrierSupported() at
        // the top of your test.
        fun setCarrierEnabled(enabled: Boolean) {
            runAsShell(MANAGE_TEST_NETWORKS) {
                tnm.setCarrierEnabled(tapInterface, enabled)
            }
        }

        fun destroy() {
            raResponder.stop()
            handler.post({ packetReader.stop() })
            handler.waitForIdle(TIMEOUT_MS)
        }
    }

    private open class EthernetStateListener private constructor(
        private val history: ArrayTrackRecord<CallbackEntry>
    ) : InterfaceStateListener, IntConsumer,
                TrackRecord<EthernetStateListener.CallbackEntry> by history {
        constructor() : this(ArrayTrackRecord())

        val events = history.newReadHead()

        sealed class CallbackEntry {
            data class InterfaceStateChanged(
                val iface: String,
                val state: Int,
                val role: Int,
                val configuration: IpConfiguration?
            ) : CallbackEntry() {
                override fun toString(): String {
                    val stateString = when (state) {
                        STATE_ABSENT -> "STATE_ABSENT"
                        STATE_LINK_UP -> "STATE_LINK_UP"
                        STATE_LINK_DOWN -> "STATE_LINK_DOWN"
                        else -> state.toString()
                    }
                    val roleString = when (role) {
                        ROLE_NONE -> "ROLE_NONE"
                        ROLE_CLIENT -> "ROLE_CLIENT"
                        ROLE_SERVER -> "ROLE_SERVER"
                        else -> role.toString()
                    }
                    return ("InterfaceStateChanged(iface=$iface, state=$stateString, " +
                            "role=$roleString, ipConfig=$configuration)")
                }
            }

            data class EthernetStateChanged(val state: Int) : CallbackEntry() {
                override fun toString(): String {
                    val stateString = when (state) {
                        ETHERNET_STATE_ENABLED -> "ETHERNET_STATE_ENABLED"
                        ETHERNET_STATE_DISABLED -> "ETHERNET_STATE_DISABLED"
                        else -> state.toString()
                    }
                    return "EthernetStateChanged(state=$stateString)"
                }
            }
        }

        override fun onInterfaceStateChanged(
            iface: String,
            state: Int,
            role: Int,
            cfg: IpConfiguration?
        ) {
            add(InterfaceStateChanged(iface, state, role, cfg))
        }

        override fun accept(state: Int) {
            add(EthernetStateChanged(state))
        }

        fun <T : CallbackEntry> expectCallback(expected: T): T {
            val event = pollForNextCallback()
            assertEquals(expected, event)
            return event as T
        }

        fun expectCallback(iface: EthernetTestInterface, state: Int, role: Int) {
            expectCallback(createChangeEvent(iface.name, state, role))
        }

        fun expectCallback(state: Int) {
            expectCallback(EthernetStateChanged(state))
        }

        fun createChangeEvent(iface: String, state: Int, role: Int) =
                InterfaceStateChanged(iface, state, role,
                        if (state != STATE_ABSENT) DEFAULT_IP_CONFIGURATION else null)

        fun pollForNextCallback(): CallbackEntry {
            return events.poll(TIMEOUT_MS) ?: fail("Did not receive callback after ${TIMEOUT_MS}ms")
        }

        fun eventuallyExpect(expected: CallbackEntry) = events.poll(TIMEOUT_MS) { it == expected }

        fun eventuallyExpect(iface: EthernetTestInterface, state: Int, role: Int) {
            val event = createChangeEvent(iface.name, state, role)
            assertNotNull(eventuallyExpect(event), "Never received expected $event")
        }

        fun eventuallyExpect(state: Int) {
            val event = EthernetStateChanged(state)
            assertNotNull(eventuallyExpect(event), "Never received expected $event")
        }

        fun assertNoCallback() {
            val cb = events.poll(NO_CALLBACK_TIMEOUT_MS)
            assertNull(cb, "Expected no callback but got $cb")
        }
    }

    private class TetheredInterfaceListener : TetheredInterfaceCallback {
        private val available = CompletableFuture<String>()

        override fun onAvailable(iface: String) {
            available.complete(iface)
        }

        override fun onUnavailable() {
            available.completeExceptionally(IllegalStateException("onUnavailable was called"))
        }

        fun expectOnAvailable(): String {
            return available.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        fun expectOnUnavailable() {
            // Assert that the future fails with the IllegalStateException from the
            // completeExceptionally() call inside onUnavailable.
            assertFailsWith(IllegalStateException::class) {
                try {
                    available.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: ExecutionException) {
                    throw e.cause!!
                }
            }
        }
    }

    private class EthernetOutcomeReceiver :
        OutcomeReceiver<String, EthernetNetworkManagementException> {
        private val result = CompletableFuture<String>()

        override fun onResult(iface: String) {
            assertFalse(result.isDone())
            result.complete(iface)
        }

        override fun onError(e: EthernetNetworkManagementException) {
            assertFalse(result.isDone())
            result.completeExceptionally(e)
        }

        fun expectResult(expected: String) {
            assertEquals(expected, result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        }

        fun expectError() {
            // Assert that the future fails with EthernetNetworkManagementException from the
            // completeExceptionally() call inside onUnavailable.
            assertFailsWith(EthernetNetworkManagementException::class) {
                try {
                    result.get()
                } catch (e: ExecutionException) {
                    throw e.cause!!
                }
            }
        }
    }

    private fun isEthernetSupported() = em != null

    @Before
    fun setUp() {
        assumeTrue(isEthernetSupported())
        setIncludeTestInterfaces(true)
        addInterfaceStateListener(ifaceListener)
        // Handler.post() events may get processed after native fd events, so it is possible that
        // RTM_NEWLINK (from a subsequent createInterface() call) arrives before the interface state
        // listener is registered. This affects the callbacks and breaks the tests.
        // setEthernetEnabled() will always wait on a callback, so it is used as a barrier to ensure
        // proper listener registration before proceeding.
        setEthernetEnabled(true)
    }

    @After
    fun tearDown() {
        if (!isEthernetSupported()) return
        // Reenable ethernet, so ABSENT callbacks are received.
        setEthernetEnabled(true)

        for (iface in createdIfaces) {
            iface.destroy()
            ifaceListener.eventuallyExpect(iface, STATE_ABSENT, ROLE_NONE)
        }

        // After test interfaces are removed, disable tracking.
        setIncludeTestInterfaces(false)

        for (listener in addedListeners) {
            em.removeInterfaceStateListener(listener)
        }
        registeredCallbacks.forEach { cm.unregisterNetworkCallback(it) }
        releaseTetheredInterface()
    }

    // Setting the carrier up / down relies on TUNSETCARRIER which was added in kernel version 5.0.
    private fun assumeChangingCarrierSupported() = assumeTrue(isKernelVersionAtLeast("5.0.0"))

    private fun isAdbOverEthernet(): Boolean {
        // If no ethernet interface is available, adb is not connected over ethernet.
        if (em.getInterfaceList().isEmpty()) return false

        // cuttlefish is special and does not connect adb over ethernet.
        if (SystemProperties.get("ro.product.board", "") == "cutf") return false

        // Check if adb is connected over the network.
        return (SystemProperties.getInt("persist.adb.tcp.port", -1) > -1 ||
                SystemProperties.getInt("service.adb.tcp.port", -1) > -1)
    }

    private fun addInterfaceStateListener(listener: EthernetStateListener) {
        runAsShell(CONNECTIVITY_USE_RESTRICTED_NETWORKS) {
            em.addInterfaceStateListener(handler::post, listener)
        }
        addedListeners.add(listener)
    }

    // WARNING: setting hasCarrier to false requires kernel support. Call
    // assumeChangingCarrierSupported() at the top of your test.
    private fun createInterface(hasCarrier: Boolean = true): EthernetTestInterface {
        val iface = EthernetTestInterface(
            context,
            handler,
            hasCarrier
        ).also { createdIfaces.add(it) }

        // when an interface comes up, we should always see a down cb before an up cb.
        ifaceListener.eventuallyExpect(iface, STATE_LINK_DOWN, ROLE_CLIENT)
        if (hasCarrier) {
            ifaceListener.expectCallback(iface, STATE_LINK_UP, ROLE_CLIENT)
        }
        return iface
    }

    private fun setIncludeTestInterfaces(value: Boolean) {
        runAsShell(NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(value)
        }
    }

    private fun removeInterface(iface: EthernetTestInterface) {
        iface.destroy()
        createdIfaces.remove(iface)
        ifaceListener.eventuallyExpect(iface, STATE_ABSENT, ROLE_NONE)
    }

    private fun requestNetwork(request: NetworkRequest): TestableNetworkCallback {
        return TestableNetworkCallback().also {
            cm.requestNetwork(request, it)
            registeredCallbacks.add(it)
        }
    }

    private fun registerNetworkListener(request: NetworkRequest): TestableNetworkCallback {
        return TestableNetworkCallback().also {
            cm.registerNetworkCallback(request, it)
            registeredCallbacks.add(it)
        }
    }

    private fun requestTetheredInterface() = TetheredInterfaceListener().also {
        tetheredInterfaceRequest = runAsShell(NETWORK_SETTINGS) {
            em.requestTetheredInterface(handler::post, it)
        }
    }

    private fun releaseTetheredInterface() {
        runAsShell(NETWORK_SETTINGS) {
            tetheredInterfaceRequest?.release()
            tetheredInterfaceRequest = null
        }
    }

    private fun releaseRequest(cb: TestableNetworkCallback) {
        cm.unregisterNetworkCallback(cb)
        registeredCallbacks.remove(cb)
    }

    private fun disableInterface(iface: EthernetTestInterface) = EthernetOutcomeReceiver().also {
        runAsShell(MANAGE_TEST_NETWORKS) {
            em.disableInterface(iface.name, handler::post, it)
        }
    }

    private fun enableInterface(iface: EthernetTestInterface) = EthernetOutcomeReceiver().also {
        runAsShell(MANAGE_TEST_NETWORKS) {
            em.enableInterface(iface.name, handler::post, it)
        }
    }

    private fun updateConfiguration(
        iface: EthernetTestInterface,
        ipConfig: IpConfiguration? = null,
        capabilities: NetworkCapabilities? = null
    ) = EthernetOutcomeReceiver().also {
        runAsShell(MANAGE_TEST_NETWORKS) {
            em.updateConfiguration(
                iface.name,
                EthernetNetworkUpdateRequest.Builder()
                    .setIpConfiguration(ipConfig)
                    .setNetworkCapabilities(capabilities).build(),
                handler::post,
                it)
        }
    }

    // WARNING: check that isAdbOverEthernet() is false before calling setEthernetEnabled(false).
    private fun setEthernetEnabled(enabled: Boolean) {
        runAsShell(NETWORK_SETTINGS) { em.setEthernetEnabled(enabled) }

        val listener = EthernetStateListener()
        em.addEthernetStateListener(handler::post, listener)
        try {
            listener.eventuallyExpect(
                    if (enabled) ETHERNET_STATE_ENABLED else ETHERNET_STATE_DISABLED)
        } finally {
            em.removeEthernetStateListener(listener)
        }
    }

    // NetworkRequest.Builder does not create a copy of the passed NetworkRequest, so in order to
    // keep ETH_REQUEST as it is, a defensive copy is created here.
    private fun NetworkRequest.createCopyWithEthernetSpecifier(ifaceName: String) =
        NetworkRequest.Builder(NetworkRequest(ETH_REQUEST))
            .setNetworkSpecifier(EthernetNetworkSpecifier(ifaceName)).build()

    // It can take multiple seconds for the network to become available.
    private fun TestableNetworkCallback.expectAvailable() =
            expectCallback<Available>(anyNetwork(), 5000 /* ms timeout */).network

    private fun TestableNetworkCallback.expectLost(n: Network = anyNetwork()) =
            expectCallback<Lost>(n, 5000 /* ms timeout */)

    // b/233534110: eventuallyExpect<Lost>() does not advance ReadHead, use
    // eventuallyExpect(Lost::class) instead.
    private fun TestableNetworkCallback.eventuallyExpectLost(n: Network? = null) =
        eventuallyExpect(Lost::class, TIMEOUT_MS) { n?.equals(it.network) ?: true }

    private fun TestableNetworkCallback.assertNeverLost(n: Network? = null) =
        assertNoCallbackThat(NO_CALLBACK_TIMEOUT_MS) {
            it is Lost && (n?.equals(it.network) ?: true)
        }

    private fun TestableNetworkCallback.assertNeverAvailable(n: Network? = null) =
        assertNoCallbackThat() { it is Available && (n?.equals(it.network) ?: true) }

    private fun TestableNetworkCallback.expectCapabilitiesWithInterfaceName(name: String) =
        expectCapabilitiesThat(anyNetwork()) {
            it.networkSpecifier == EthernetNetworkSpecifier(name)
        }

    private fun TestableNetworkCallback.expectCapabilitiesWithCapability(cap: Int) =
        expectCapabilitiesThat(anyNetwork(), TIMEOUT_MS) {
            it.hasCapability(cap)
        }

    private fun TestableNetworkCallback.expectLinkPropertiesWithLinkAddress(addr: LinkAddress) =
        expectLinkPropertiesThat(anyNetwork(), TIMEOUT_MS) {
            // LinkAddress.equals isn't possible as the system changes the LinkAddress.flags value.
            // any() must be used since the interface may also have a link-local address.
            it.linkAddresses.any { x -> x.isSameAddressAs(addr) }
        }

    @Test
    fun testCallbacks() {
        // If an interface exists when the callback is registered, it is reported on registration.
        val iface = createInterface()
        val listener1 = EthernetStateListener()
        addInterfaceStateListener(listener1)
        validateListenerOnRegistration(listener1)

        // If an interface appears, existing callbacks see it.
        val iface2 = createInterface()
        listener1.expectCallback(iface2, STATE_LINK_DOWN, ROLE_CLIENT)
        listener1.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)

        // Register a new listener, it should see state of all existing interfaces immediately.
        val listener2 = EthernetStateListener()
        addInterfaceStateListener(listener2)
        validateListenerOnRegistration(listener2)

        // Removing interfaces first sends link down, then STATE_ABSENT/ROLE_NONE.
        removeInterface(iface)
        for (listener in listOf(listener1, listener2)) {
            listener.expectCallback(iface, STATE_LINK_DOWN, ROLE_CLIENT)
            listener.expectCallback(iface, STATE_ABSENT, ROLE_NONE)
        }

        removeInterface(iface2)
        for (listener in listOf(listener1, listener2)) {
            listener.expectCallback(iface2, STATE_LINK_DOWN, ROLE_CLIENT)
            listener.expectCallback(iface2, STATE_ABSENT, ROLE_NONE)
            listener.assertNoCallback()
        }
    }

    @Test
    fun testCallbacks_withRunningInterface() {
        // This test disables ethernet, so check that adb is not connected over ethernet.
        assumeFalse(isAdbOverEthernet())
        assumeTrue(em.getInterfaceList().isEmpty())
        val iface = createInterface()
        val listener = EthernetStateListener()
        addInterfaceStateListener(listener)
        listener.eventuallyExpect(iface, STATE_LINK_UP, ROLE_CLIENT)

        // Remove running interface. The interface stays running but is no longer tracked.
        setEthernetEnabled(false)
        listener.expectCallback(iface, STATE_ABSENT, ROLE_NONE)

        setEthernetEnabled(true)
        listener.expectCallback(iface, STATE_LINK_UP, ROLE_CLIENT)
        listener.assertNoCallback()
    }

    private fun assumeNoInterfaceForTetheringAvailable() {
        // Interfaces that have configured NetworkCapabilities will never be used for tethering,
        // see aosp/2123900.
        try {
            // assumeException does not exist.
            requestTetheredInterface().expectOnAvailable()
            // interface used for tethering is available, throw an assumption error.
            assumeTrue(false)
        } catch (e: TimeoutException) {
            // do nothing -- the TimeoutException indicates that no interface is available for
            // tethering.
            releaseTetheredInterface()
        }
    }

    @Test
    fun testCallbacks_forServerModeInterfaces() {
        // do not run this test if an interface that can be used for tethering already exists.
        assumeNoInterfaceForTetheringAvailable()

        val iface = createInterface()
        requestTetheredInterface().expectOnAvailable()

        val listener = EthernetStateListener()
        addInterfaceStateListener(listener)
        // TODO(b/236895792): THIS IS A BUG! Existing server mode interfaces are not reported when
        // an InterfaceStateListener is registered.
        // Note: using eventuallyExpect as there may be other interfaces present.
        // listener.eventuallyExpect(iface, STATE_LINK_UP, ROLE_SERVER)

        releaseTetheredInterface()
        listener.eventuallyExpect(iface, STATE_LINK_UP, ROLE_CLIENT)

        requestTetheredInterface().expectOnAvailable()
        // This should be changed to expectCallback, once b/236895792 is fixed.
        listener.eventuallyExpect(iface, STATE_LINK_UP, ROLE_SERVER)

        releaseTetheredInterface()
        listener.expectCallback(iface, STATE_LINK_UP, ROLE_CLIENT)
    }

    /**
     * Validate all interfaces are returned for an EthernetStateListener upon registration.
     */
    private fun validateListenerOnRegistration(listener: EthernetStateListener) {
        // Get all tracked interfaces to validate on listener registration. Ordering and interface
        // state (up/down) can't be validated for interfaces not created as part of testing.
        val ifaces = em.getInterfaceList()
        val polledIfaces = ArraySet<String>()
        for (i in ifaces) {
            val event = (listener.pollForNextCallback() as InterfaceStateChanged)
            val iface = event.iface
            assertTrue(polledIfaces.add(iface), "Duplicate interface $iface returned")
            assertTrue(ifaces.contains(iface), "Untracked interface $iface returned")
            // If the event's iface was created in the test, additional criteria can be validated.
            createdIfaces.find { it.name.equals(iface) }?.let {
                assertEquals(event, listener.createChangeEvent(it.name, STATE_LINK_UP, ROLE_CLIENT))
            }
        }
        // Assert all callbacks are accounted for.
        listener.assertNoCallback()
    }

    @Test
    fun testGetInterfaceList() {
        // Create two test interfaces and check the return list contains the interface names.
        val iface1 = createInterface()
        val iface2 = createInterface()
        var ifaces = em.getInterfaceList()
        assertTrue(ifaces.size > 0)
        assertTrue(ifaces.contains(iface1.name))
        assertTrue(ifaces.contains(iface2.name))

        // Remove one existing test interface and check the return list doesn't contain the
        // removed interface name.
        removeInterface(iface1)
        ifaces = em.getInterfaceList()
        assertFalse(ifaces.contains(iface1.name))
        assertTrue(ifaces.contains(iface2.name))

        removeInterface(iface2)
    }

    @Test
    fun testNetworkRequest_withSingleExistingInterface() {
        createInterface()

        // install a listener which will later be used to verify the Lost callback
        val listenerCb = registerNetworkListener(ETH_REQUEST)

        val cb = requestNetwork(ETH_REQUEST)
        val network = cb.expectAvailable()

        cb.assertNeverLost()
        releaseRequest(cb)
        listenerCb.eventuallyExpectLost(network)
    }

    @Test
    fun testNetworkRequest_beforeSingleInterfaceIsUp() {
        val cb = requestNetwork(ETH_REQUEST)

        // bring up interface after network has been requested.
        // Note: there is no guarantee that the NetworkRequest has been processed before the
        // interface is actually created. That being said, it takes a few seconds between calling
        // createInterface and the interface actually being properly registered with the ethernet
        // module, so it is extremely unlikely that the CS handler thread has not run until then.
        val iface = createInterface()
        val network = cb.expectAvailable()

        // remove interface before network request has been removed
        cb.assertNeverLost()
        removeInterface(iface)
        cb.eventuallyExpectLost()
    }

    @Test
    fun testNetworkRequest_withMultipleInterfaces() {
        val iface1 = createInterface()
        val iface2 = createInterface()

        val cb = requestNetwork(ETH_REQUEST.createCopyWithEthernetSpecifier(iface2.name))

        val network = cb.expectAvailable()
        cb.expectCapabilitiesWithInterfaceName(iface2.name)

        removeInterface(iface1)
        cb.assertNeverLost()
        removeInterface(iface2)
        cb.eventuallyExpectLost()
    }

    @Test
    fun testNetworkRequest_withInterfaceBeingReplaced() {
        val iface1 = createInterface()

        val cb = requestNetwork(ETH_REQUEST)
        val network = cb.expectAvailable()

        // create another network and verify the request sticks to the current network
        val iface2 = createInterface()
        cb.assertNeverLost()

        // remove iface1 and verify the request brings up iface2
        removeInterface(iface1)
        cb.eventuallyExpectLost(network)
        val network2 = cb.expectAvailable()
    }

    @Test
    fun testNetworkRequest_withMultipleInterfacesAndRequests() {
        val iface1 = createInterface()
        val iface2 = createInterface()

        val cb1 = requestNetwork(ETH_REQUEST.createCopyWithEthernetSpecifier(iface1.name))
        val cb2 = requestNetwork(ETH_REQUEST.createCopyWithEthernetSpecifier(iface2.name))
        val cb3 = requestNetwork(ETH_REQUEST)

        cb1.expectAvailable()
        cb1.expectCapabilitiesWithInterfaceName(iface1.name)
        cb2.expectAvailable()
        cb2.expectCapabilitiesWithInterfaceName(iface2.name)
        // this request can be matched by either network.
        cb3.expectAvailable()

        cb1.assertNeverLost()
        cb2.assertNeverLost()
        cb3.assertNeverLost()
    }

    @Test
    fun testNetworkRequest_ensureProperRefcounting() {
        // create first request before interface is up / exists; create another request after it has
        // been created; release one of them and check that the network stays up.
        val listener = registerNetworkListener(ETH_REQUEST)
        val cb1 = requestNetwork(ETH_REQUEST)

        val iface = createInterface()
        val network = cb1.expectAvailable()

        val cb2 = requestNetwork(ETH_REQUEST)
        cb2.expectAvailable()

        // release the first request; this used to trigger b/197548738
        releaseRequest(cb1)

        cb2.assertNeverLost()
        releaseRequest(cb2)
        listener.eventuallyExpectLost(network)
    }

    @Test
    fun testNetworkRequest_forInterfaceWhileTogglingCarrier() {
        assumeChangingCarrierSupported()

        val iface = createInterface(false /* hasCarrier */)

        val cb = requestNetwork(ETH_REQUEST)
        cb.assertNeverAvailable()

        iface.setCarrierEnabled(true)
        cb.expectAvailable()

        iface.setCarrierEnabled(false)
        cb.eventuallyExpectLost()
    }

    @Test
    fun testRemoveInterface_whileInServerMode() {
        assumeNoInterfaceForTetheringAvailable()

        val listener = EthernetStateListener()
        addInterfaceStateListener(listener)

        val iface = createInterface()
        val ifaceName = requestTetheredInterface().expectOnAvailable()

        assertEquals(iface.name, ifaceName)
        listener.eventuallyExpect(iface, STATE_LINK_UP, ROLE_SERVER)

        removeInterface(iface)

        // Note: removeInterface already verifies that a STATE_ABSENT, ROLE_NONE callback is
        // received, but it can't hurt to explicitly check for it.
        listener.expectCallback(iface, STATE_ABSENT, ROLE_NONE)
        releaseTetheredInterface()
        listener.assertNoCallback()
    }

    @Test
    fun testEnableDisableInterface_withActiveRequest() {
        val iface = createInterface()
        val cb = requestNetwork(ETH_REQUEST)
        cb.expectAvailable()
        cb.assertNeverLost()

        disableInterface(iface).expectResult(iface.name)
        cb.eventuallyExpectLost()

        enableInterface(iface).expectResult(iface.name)
        cb.expectAvailable()
    }

    @Test
    fun testEnableDisableInterface_withoutStateChange() {
        val iface = createInterface()
        // Interface is already enabled, so enableInterface() should return success
        enableInterface(iface).expectResult(iface.name)

        disableInterface(iface).expectResult(iface.name)
        // Interface is already disabled, so disableInterface() should return success.
        disableInterface(iface).expectResult(iface.name)
    }

    @Test
    fun testEnableDisableInterface_withMissingInterface() {
        val iface = createInterface()
        removeInterface(iface)
        // Interface does not exist, enable/disableInterface() should both return an error.
        enableInterface(iface).expectError()
        disableInterface(iface).expectError()
    }

    @Test
    fun testUpdateConfiguration_forBothIpConfigAndCapabilities() {
        val iface = createInterface()
        val cb = requestNetwork(ETH_REQUEST.createCopyWithEthernetSpecifier(iface.name))
        val network = cb.expectAvailable()
        cb.assertNeverLost()

        val testCapability = NET_CAPABILITY_TEMPORARILY_NOT_METERED
        val nc = NetworkCapabilities
                .Builder(ETH_REQUEST.networkCapabilities)
                .addCapability(testCapability)
                .build()
        updateConfiguration(iface, STATIC_IP_CONFIGURATION, nc).expectResult(iface.name)

        // UpdateConfiguration() currently does a restarts on the ethernet interface therefore lost
        // will be expected first before available, as part of the restart.
        cb.expectLost(network)
        cb.expectAvailable()
        cb.expectCapabilitiesWithCapability(testCapability)
        cb.expectLinkPropertiesWithLinkAddress(
                STATIC_IP_CONFIGURATION.staticIpConfiguration.ipAddress!!)
    }

    @Test
    fun testUpdateConfiguration_forOnlyIpConfig() {
        val iface: EthernetTestInterface = createInterface()
        val cb = requestNetwork(ETH_REQUEST.createCopyWithEthernetSpecifier(iface.name))
        val network = cb.expectAvailable()
        cb.assertNeverLost()

        updateConfiguration(iface, STATIC_IP_CONFIGURATION).expectResult(iface.name)

        // UpdateConfiguration() currently does a restarts on the ethernet interface therefore lost
        // will be expected first before available, as part of the restart.
        cb.expectLost(network)
        cb.expectAvailable()
        cb.expectCallback<CapabilitiesChanged>()
        cb.expectLinkPropertiesWithLinkAddress(
                STATIC_IP_CONFIGURATION.staticIpConfiguration.ipAddress!!)
    }

    // TODO(b/240323229): This test is currently flaky due to a race between IpClient restarting and
    // NetworkAgent tearing down the routes. This problem is exacerbated by disabling RS delay.
    @Ignore
    @Test
    fun testUpdateConfiguration_forOnlyCapabilities() {
        val iface: EthernetTestInterface = createInterface()
        val cb = requestNetwork(ETH_REQUEST.createCopyWithEthernetSpecifier(iface.name))
        val network = cb.expectAvailable()
        cb.assertNeverLost()

        val testCapability = NET_CAPABILITY_TEMPORARILY_NOT_METERED
        val nc = NetworkCapabilities
                .Builder(ETH_REQUEST.networkCapabilities)
                .addCapability(testCapability)
                .build()
        updateConfiguration(iface, capabilities = nc).expectResult(iface.name)

        // UpdateConfiguration() currently does a restarts on the ethernet interface therefore lost
        // will be expected first before available, as part of the restart.
        cb.expectLost(network)
        cb.expectAvailable()
        cb.expectCapabilitiesWithCapability(testCapability)
    }
}
