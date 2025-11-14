package com.android.server.connectivity

import android.content.res.Resources
import android.os.Handler
import android.provider.DeviceConfig
import android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY
import android.provider.DeviceConfig.OnPropertiesChangedListener
import android.telephony.CarrierConfigManager
import com.android.internal.annotations.GuardedBy
import com.android.internal.os.BackgroundThread
import com.android.server.connectivity.MultinetworkPolicyTracker.CONFIG_ACTIVELY_PREFER_BAD_WIFI
import java.util.concurrent.Executor

class MultinetworkPolicyTrackerTestDependencies(
    private val resources: Resources
) : MultinetworkPolicyTracker.Dependencies() {
    @GuardedBy("listeners")
    private var configActivelyPreferBadWifi = 0

    // TODO : move this to an actual fake device config object
    @GuardedBy("listeners")
    private val listeners = mutableListOf<Pair<Executor, OnPropertiesChangedListener>>()

    private var avoidBadWifiFromCarrierConfigFeature = false
    private val avoidBadWifiCarrierConfigForSubIdMap = HashMap<Int, Boolean>()
    private var backgroundThreadHandler = BackgroundThread.getHandler()

    fun putConfigActivelyPreferBadWifi(value: Int) {
        synchronized(listeners) {
            if (value == configActivelyPreferBadWifi) return
            configActivelyPreferBadWifi = value
            val p = DeviceConfig.Properties(
                NAMESPACE_CONNECTIVITY,
                mapOf(CONFIG_ACTIVELY_PREFER_BAD_WIFI to value.toString())
            )
            listeners.forEach { (executor, listener) ->
                executor.execute { listener.onPropertiesChanged(p) }
            }
        }
    }

    fun setAvoidBadWifiFromCarrierConfigFeature(value: Boolean) {
        avoidBadWifiFromCarrierConfigFeature = value
    }

    fun setAvoidBadWifiCarrierConfigForSubId(subId: Int, value: Boolean) {
        avoidBadWifiCarrierConfigForSubIdMap[subId] = value
    }

    fun resetAvoidBadWifiCarrierConfigForSubIdMap() {
        avoidBadWifiCarrierConfigForSubIdMap.clear()
    }

    fun setBackgroundThreadHandler(handler: Handler) {
        backgroundThreadHandler = handler
    }

    override fun getConfigActivelyPreferBadWifi(): Int {
        return synchronized(listeners) { configActivelyPreferBadWifi }
    }

    override fun addOnDevicePropertiesChangedListener(
        e: Executor,
        listener: OnPropertiesChangedListener
    ) {
        synchronized(listeners) {
            listeners.add(e to listener)
        }
    }

    override fun getResourcesForActiveSubId(res: ConnectivityResources, id: Int): Resources =
        resources

    override fun readAvoidBadWifiFromCarrierConfig(
        ccm: CarrierConfigManager,
        subId: Int
    ): Boolean =
        avoidBadWifiCarrierConfigForSubIdMap.getOrDefault(subId, true)

    public override fun getAvoidBadWifiFromCarrierConfigFeature(): Boolean =
        avoidBadWifiFromCarrierConfigFeature

    public override fun getBackgroundThreadHandler(): Handler =
        backgroundThreadHandler
}
