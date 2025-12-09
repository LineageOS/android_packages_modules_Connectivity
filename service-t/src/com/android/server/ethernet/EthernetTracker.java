/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.net.EthernetManager.ETHERNET_STATE_DISABLED;
import static android.net.EthernetManager.ETHERNET_STATE_ENABLED;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_LOWPAN;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE;
import static android.net.TestNetworkManager.TEST_TAP_PREFIX;
import static android.net.EthernetManager.TEST_INTERFACE_MODE_NONE;
import static android.system.OsConstants.ENOTSUP;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;
import static com.android.net.module.util.netlink.NetlinkConstants.IFF_UP;
import static com.android.net.module.util.netlink.NetlinkConstants.RTM_GETLINK;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.EthernetManager;
import android.net.EthernetManager.TestInterfaceMode;
import android.net.INetd;
import android.net.ITetheredInterfaceCallback;
import android.net.InterfaceConfigurationParcel;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.MacAddress;
import android.net.NetworkCapabilities;
import android.net.StaticIpConfiguration;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.HandlerUtils;
import com.android.net.module.util.NetdUtils;
import com.android.net.module.util.ServiceConnectivityJni;
import com.android.net.module.util.SharedLog;
import com.android.net.module.util.ip.NetlinkMonitor;
import com.android.net.module.util.netlink.NetlinkConstants;
import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.net.module.util.netlink.NetlinkUtils;
import com.android.net.module.util.netlink.RtNetlinkLinkMessage;
import com.android.net.module.util.netlink.StructIfinfoMsg;
import com.android.net.module.util.netlink.StructNlMsgHdr;
import com.android.server.connectivity.ConnectivityResources;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tracks Ethernet interfaces and manages interface configurations.
 *
 * <p>Interfaces may have different {@link android.net.NetworkCapabilities}. This mapping is defined
 * in {@code config_ethernet_interfaces}. Notably, some interfaces could be marked as restricted by
 * not specifying {@link android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED} flag.
 * Interfaces could have associated {@link android.net.IpConfiguration}.
 * Ethernet Interfaces may be present at boot time or appear after boot (e.g., for Ethernet adapters
 * connected over USB). This class supports multiple interfaces. When an interface appears on the
 * system (or is present at boot time) this class will start tracking it and bring it up. Only
 * interfaces whose names match the {@code config_ethernet_iface_regex} regular expression are
 * tracked.
 *
 * <p>All public or package private methods must be thread-safe unless stated otherwise.
 */
@VisibleForTesting(visibility = PACKAGE)
public class EthernetTracker {
    private static final int INTERFACE_MODE_CLIENT = 1;
    private static final int INTERFACE_MODE_SERVER = 2;

    private static final String TAG = EthernetTracker.class.getSimpleName();
    private static final boolean DBG = EthernetNetworkFactory.DBG;

    private static final String NCM_ENABLED_FLAG = "ethernet_local_ncm_tracking_enabled_flag";

    private static final Pattern TEST_IFACE_REGEXP = Pattern.compile(TEST_TAP_PREFIX + "\\d+");

    /** Pattern that identifies possible NCM host interfaces. */
    private static final Pattern NCM_HOST_REGEXP = Pattern.compile("(usb|eth)\\d+");

    /** The driver name used to identify NCM host interfaces; see drivers/net/usb/cdc_ncm.c */
    private static final String NCM_HOST_DRIVER_NAME = "cdc_ncm";

    // TODO: consider using SharedLog consistently across ethernet service.
    private static final SharedLog sLog = new SharedLog(TAG);

    @VisibleForTesting
    public static final NetworkCapabilities DEFAULT_CAPABILITIES = new NetworkCapabilities.Builder()
                        .addTransportType(TRANSPORT_ETHERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                        // TODO: do not hardcode link bandwidth.
                        .setLinkUpstreamBandwidthKbps(100 * 1000 /* 100 Mbps */)
                        .setLinkDownstreamBandwidthKbps(100 * 1000 /* 100 Mbps */)
                        .build();


    /**
     * Interface names we track. This is a product-dependent regular expression.
     * Use inferTrackingReason() to check if a interface name is a valid ethernet interface (this
     * includes test interfaces if setIncludeTestInterfaces is set to true) and should be tracked.
     */
    private final Pattern mIfaceMatch;

    /**
     * Track test interfaces if true, don't track otherwise.
     * Volatile is needed as getEthernetInterfaceList() does not run on the handler thread.
     */
    private volatile int mTestInterfaceMode = TEST_INTERFACE_MODE_NONE;

    /** Mapping between {iface name | mac address} -> {NetworkCapabilities} */
    private final ConcurrentHashMap<String, NetworkCapabilities> mNetworkCapabilities =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IpConfiguration> mIpConfigurations =
            new ConcurrentHashMap<>();

    private final Context mContext;
    private final INetd mNetd;
    private final Handler mHandler;
    private final EthernetNetworkFactory mFactory;
    private final EthernetConfigStore mConfigStore;
    private final EthernetNetlinkMonitor mNetlinkMonitor;
    private final Dependencies mDeps;

    private final RemoteCallbackList<EthernetListener> mListeners = new RemoteCallbackList<>();
    private final TetheredInterfaceRequestList mTetheredInterfaceRequests =
            new TetheredInterfaceRequestList();

    // The first interface discovered is set as the mTetheringInterface. It is the interface that is
    // returned when a tethered interface is requested; until then, it remains in client mode. Its
    // current mode is reflected in mTetheringInterfaceMode.
    @Nullable private EthernetPort mTetheringInterface;
    @Nullable private EnumSet<TrackingReason> mTetheringTrackingReason;
    private int mTetheringInterfaceMode = INTERFACE_MODE_CLIENT;
    // Tracks whether clients were notified that the tethered interface is available
    private boolean mTetheredInterfaceWasAvailable = false;
    // Tracks the current state of ethernet as configured by EthernetManager#setEthernetEnabled.
    private boolean mIsEthernetEnabled = true;

    private class TetheredInterfaceRequestList extends
            RemoteCallbackList<ITetheredInterfaceCallback> {
        @Override
        public void onCallbackDied(ITetheredInterfaceCallback cb, Object cookie) {
            mHandler.post(EthernetTracker.this::maybeUntetherInterface);
        }
    }

    public static class Dependencies {
        public String getInterfaceRegexFromResource(Context context) {
            final ConnectivityResources resources = new ConnectivityResources(context);
            return resources.get().getString(
                    com.android.connectivity.resources.R.string.config_ethernet_iface_regex);
        }

        public String[] getInterfaceConfigFromResource(Context context) {
            final ConnectivityResources resources = new ConnectivityResources(context);
            return resources.get().getStringArray(
                    com.android.connectivity.resources.R.array.config_ethernet_interfaces);
        }

        public boolean isAtLeastB() {
            return SdkLevel.isAtLeastB();
        }
    }

    /** Enum used to convey the reason an interface is tracked. */
    public enum TrackingReason {
        /** The interface is tracked because it is part of the regex. */
        REGEX,
        /** The interface is tracked because it is an NCM interface. */
        NCM,
    }

    private class EthernetNetlinkMonitor extends NetlinkMonitor {
        EthernetNetlinkMonitor(Handler handler) {
            super(handler, sLog, EthernetNetlinkMonitor.class.getSimpleName(),
                    OsConstants.NETLINK_ROUTE, NetlinkConstants.RTMGRP_LINK);
        }

        /** Request RTM_GETLINK dump. Resulting callbacks are handled by #processNetlinkMessage. */
        public void requestLinkDump() {
            // TODO(b/422484024): Clean up NetlinkMessage classes and add support for building a
            // generic GETLINK message.
            // Allocate enough space for struct nlmsghdr + struct rtgenmsg
            final ByteBuffer buf = ByteBuffer.allocate(StructNlMsgHdr.STRUCT_SIZE + 1);
            buf.order(ByteOrder.nativeOrder());

            final StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
            nlmsghdr.nlmsg_len = buf.capacity();
            nlmsghdr.nlmsg_type = RTM_GETLINK;
            nlmsghdr.nlmsg_flags = StructNlMsgHdr.NLM_F_DUMP | StructNlMsgHdr.NLM_F_REQUEST;
            nlmsghdr.pack(buf);

            // struct rtgenmsg {
            //   unsigned char rtgen_family;
            // }
            buf.put((byte) OsConstants.AF_UNSPEC);
            buf.flip();

            sendNetlinkMessage(buf);
        }

        private boolean isInterfaceTracked(EthernetPort port) {
            final String ifname = port.getInterfaceName();
            if (mFactory.hasInterface(ifname)) return true;
            if (mTetheringInterface != null && mTetheringInterface.matches(ifname)) return true;
            return false;
        }

        private void onNewLink(EthernetPort port, boolean linkUp) {
            if (!isInterfaceTracked(port)) {
                final String ifname = port.getInterfaceName();
                final EnumSet<TrackingReason> trackingReason = inferTrackingReason(ifname);
                if (trackingReason.isEmpty()) return;

                Log.i(TAG, "onInterfaceAdded: " + port + " for reason: " + trackingReason);
                trackInterface(port, trackingReason);
            }
            Log.i(TAG, "interfaceLinkStateChanged: " + port + ", up: " + linkUp);
            updateInterfaceState(port, linkUp);
        }

        private void onDelLink(EthernetPort port) {
            if (!isInterfaceTracked(port)) return;
            Log.i(TAG, "onInterfaceRemoved: " + port);
            stopTrackingInterface(port);
        }

        private void processRtNetlinkLinkMessage(RtNetlinkLinkMessage msg) {
            final StructIfinfoMsg ifinfomsg = msg.getIfinfoHeader();
            // check if the message is valid
            if (ifinfomsg.family != OsConstants.AF_UNSPEC) return;

            // ignore messages for the loopback interface
            if ((ifinfomsg.flags & OsConstants.IFF_LOOPBACK) != 0) return;

            // rtnl_fill_ifinfo sets IFLA_ADDRESS when there is one. This should always be true for
            // all ethernet interfaces.
            final MacAddress mac = msg.getHardwareAddress();
            if (mac == null) return;

            // Note that #onNewLink() and #onDelLink() filter out non-ethernet interfaces by calling
            // either #inferTrackingReason (for newly tracked interfaces) or #isInterfaceTracked
            // (for existing interfaces). Up until then, this code runs for every network interface
            // on the system.
            final String ifname = msg.getInterfaceName();
            final EthernetPort port = new EthernetPort(ifname, mac, ifinfomsg.index);

            switch (msg.getHeader().nlmsg_type) {
                case NetlinkConstants.RTM_NEWLINK:
                    final boolean linkUp = (ifinfomsg.flags & NetlinkConstants.IFF_LOWER_UP) != 0;
                    onNewLink(port, linkUp);
                    break;
                case NetlinkConstants.RTM_DELLINK:
                    onDelLink(port);
                    break;
                case NetlinkConstants.NLMSG_DONE:
                    // do nothing.
                    break;
                default:
                    Log.e(TAG, "Unknown rtnetlink link msg type: " + msg);
                    break;
            }
        }

        // Note: processNetlinkMessage is called on the handler thread.
        @Override
        protected void processNetlinkMessage(NetlinkMessage nlMsg, long whenMs) {
            if (nlMsg instanceof RtNetlinkLinkMessage) {
                processRtNetlinkLinkMessage((RtNetlinkLinkMessage) nlMsg);
            } else {
                Log.e(TAG, "Unknown netlink message: " + nlMsg);
            }
        }
    }


    EthernetTracker(@NonNull final Context context, @NonNull final Handler handler,
            @NonNull final EthernetNetworkFactory factory, @NonNull final INetd netd) {
        this(context, handler, factory, netd, new Dependencies());
    }

    @VisibleForTesting
    EthernetTracker(@NonNull final Context context, @NonNull final Handler handler,
            @NonNull final EthernetNetworkFactory factory, @NonNull final INetd netd,
            @NonNull final Dependencies deps) {
        mContext = context;
        mHandler = handler;
        mFactory = factory;
        mNetd = netd;
        mDeps = deps;

        // Interface match regex.
        String ifaceMatchRegex = mDeps.getInterfaceRegexFromResource(mContext);
        // "*" is a magic string to indicate "pick the default".
        if (ifaceMatchRegex.equals("*")) {
            if (SdkLevel.isAtLeastV()) {
                // On V+, include both usb%d and eth%d interfaces.
                ifaceMatchRegex = "(usb|eth)\\d+";
            } else {
                // On T and U, include only eth%d interfaces.
                ifaceMatchRegex = "eth\\d+";
            }
        }
        mIfaceMatch = Pattern.compile(ifaceMatchRegex);

        // Read default Ethernet interface configuration from resources
        final String[] interfaceConfigs = mDeps.getInterfaceConfigFromResource(context);
        for (String strConfig : interfaceConfigs) {
            parseEthernetConfig(strConfig);
        }

        mConfigStore = new EthernetConfigStore();
        mNetlinkMonitor = new EthernetNetlinkMonitor(mHandler);
    }

    void start() {
        mFactory.register();
        mConfigStore.read();

        final ArrayMap<String, IpConfiguration> configs = mConfigStore.getIpConfigurations();
        for (int i = 0; i < configs.size(); i++) {
            mIpConfigurations.put(configs.keyAt(i), configs.valueAt(i));
        }

        mHandler.post(() -> {
            mNetlinkMonitor.start();
            mNetlinkMonitor.requestLinkDump();
        });
    }

    // TODO: is this dead code?
    void updateIpConfiguration(String iface, IpConfiguration ipConfiguration) {
        if (DBG) {
            Log.i(TAG, "updateIpConfiguration, iface: " + iface + ", cfg: " + ipConfiguration);
        }
        writeIpConfiguration(iface, ipConfiguration);
        mHandler.post(() -> {
            mFactory.updateInterface(iface, ipConfiguration, null);
            // This code always sends an InterfaceStateChange callback even if the interface is not
            // being tracked. For the sake of the onInterfaceStateChanged callback, pretend that the
            // interface is in the regex.
            // TODO: find a better solution for this. Consider adding callback flags instead of
            // passing the tracking reason. Also consider getting rid of this behavior altogether.
            broadcastInterfaceStateChange(iface, EnumSet.of(TrackingReason.REGEX));
        });
    }

    private void writeIpConfiguration(@NonNull final String iface,
            @NonNull final IpConfiguration ipConfig) {
        mConfigStore.write(iface, ipConfig);
        mIpConfigurations.put(iface, ipConfig);
    }

    private IpConfiguration getIpConfigurationForCallback(String iface, int state) {
        return (state == EthernetManager.STATE_ABSENT) ? null : getOrCreateIpConfiguration(iface);
    }

    private void ensureRunningOnEthernetServiceThread() {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);
    }

    /**
     * Broadcast the link state or IpConfiguration change of existing Ethernet interfaces to all
     * listeners.
     */
    protected void broadcastInterfaceStateChange(@NonNull String iface,
            EnumSet<TrackingReason> trackingReason) {
        ensureRunningOnEthernetServiceThread();
        final int state = getInterfaceState(iface);
        final int role = getInterfaceRole(iface);
        final IpConfiguration config = getIpConfigurationForCallback(iface, state);
        final boolean isRestricted = isRestrictedInterface(iface);
        final int n = mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            final EthernetListener listener = mListeners.getBroadcastItem(i);
            if (isRestricted && !listener.hasUseRestrictedNetworksPermission()) continue;
            listener.onInterfaceStateChanged(iface, trackingReason, state, role, config);
        }
        mListeners.finishBroadcast();
    }

    /**
     * Unicast the interface state or IpConfiguration change of existing Ethernet interfaces to a
     * specific listener.
     */
    protected void unicastInterfaceStateChange(EthernetListener listener, String iface) {
        ensureRunningOnEthernetServiceThread();
        final EnumSet<TrackingReason> trackingReason = getInterfaceTrackingReason(iface);
        final int state = getInterfaceState(iface);
        final int role = getInterfaceRole(iface);
        final IpConfiguration config = getIpConfigurationForCallback(iface, state);
        listener.onInterfaceStateChanged(iface, trackingReason, state, role, config);
    }

    @VisibleForTesting(visibility = PACKAGE)
    protected void updateConfiguration(@NonNull final String iface,
            @Nullable final IpConfiguration ipConfig,
            @Nullable final NetworkCapabilities capabilities,
            @Nullable final EthernetCallback cb) {
        if (DBG) {
            Log.i(TAG, "updateConfiguration, iface: " + iface + ", capabilities: " + capabilities
                    + ", ipConfig: " + ipConfig);
        }

        // TODO: do the right thing if the interface was in server mode: either fail this operation,
        // or take the interface out of server mode.
        final IpConfiguration localIpConfig = ipConfig == null
                ? null : new IpConfiguration(ipConfig);
        if (ipConfig != null) {
            writeIpConfiguration(iface, localIpConfig);
        }

        if (null != capabilities) {
            mNetworkCapabilities.put(iface, capabilities);
        }
        mHandler.post(() -> {
            mFactory.updateInterface(iface, localIpConfig, capabilities);

            // only broadcast state change when the ip configuration is updated.
            if (ipConfig != null) {
                // This code always sends an InterfaceStateChange callback even if the interface is
                // not being tracked. For the sake of the onInterfaceStateChanged callback, pretend
                // that the interface is in the regex.
                // TODO: consider only sending callbacks if the interface is currently tracked.
                broadcastInterfaceStateChange(iface, EnumSet.of(TrackingReason.REGEX));
            }
            // Always return success. Even if the interface does not currently exist, the
            // IpConfiguration and NetworkCapabilities were saved and will be applied if an
            // interface with the given name is ever added.
            cb.onResult(iface);
        });
    }

    /** Configure the administrative state of ethernet interface by toggling IFF_UP. */
    public void setInterfaceEnabled(String iface, boolean enabled, EthernetCallback cb) {
        mHandler.post(() -> setInterfaceAdministrativeState(iface, enabled, cb));
    }

    IpConfiguration getIpConfiguration(String iface) {
        return mIpConfigurations.get(iface);
    }

    /** Returns true if this interface is being tracked by the regex. */
    public boolean isTrackingInterfaceByRegex(String iface) {
        return mFactory.getTrackingReason(iface).contains(TrackingReason.REGEX);
    }

    /** Returns an unordered(!) list of tracked EthernetPort objects. */
    private List<EthernetPort> getAllEthernetPorts() {
        final List<EthernetPort> interfaces = new ArrayList<>(mFactory.getEthernetPorts());
        if (mTetheringInterfaceMode == INTERFACE_MODE_SERVER && mTetheringInterface != null) {
            interfaces.add(mTetheringInterface);
        }
        return interfaces;
    }

    String[] getClientModeInterfacesSorted(boolean includeRestricted) {
        return mFactory.getInterfacesSorted(includeRestricted);
    }

    List<String> getEthernetInterfaceList() {
        final List<String> interfaceList = new ArrayList<String>();
        final Enumeration<NetworkInterface> ifaces;
        try {
            ifaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            Log.e(TAG, "Failed to get ethernet interfaces: ", e);
            return interfaceList;
        }

        // There is a possible race with setIncludeTestInterfaces() which affects
        // inferTrackingReason().
        // setIncludeTestInterfaces() is only used in tests, and since getEthernetInterfaceList()
        // does not run on the handler thread, the behavior around setIncludeTestInterfaces() is
        // indeterminate either way. This can easily be circumvented by waiting on a callback from
        // a test interface after calling setIncludeTestInterfaces() before calling this function.
        // In production code, this has no effect.
        while (ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();
            if (inferTrackingReason(iface.getName()).contains(TrackingReason.REGEX)) {
                interfaceList.add(iface.getName());
            }
        }
        return interfaceList;
    }

    /**
     * Returns true if given interface was configured as restricted (doesn't have
     * NET_CAPABILITY_NOT_RESTRICTED) capability. Otherwise, returns false.
     */
    boolean isRestrictedInterface(String iface) {
        final NetworkCapabilities nc = mNetworkCapabilities.get(iface);
        return nc != null && !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
    }

    void addListener(EthernetListener listener) {
        mHandler.post(() -> {
            if (!mListeners.register(listener)) {
                // Remote process has already died
                return;
            }
            final boolean canUseRestrictedNetworks = listener.hasUseRestrictedNetworksPermission();
            for (String iface : getClientModeInterfacesSorted(canUseRestrictedNetworks)) {
                unicastInterfaceStateChange(listener, iface);
            }
            if (mTetheringInterface != null && mTetheringInterfaceMode == INTERFACE_MODE_SERVER) {
                unicastInterfaceStateChange(listener, mTetheringInterface.getInterfaceName());
            }

            unicastEthernetStateChange(listener, mIsEthernetEnabled);
        });
    }

    void removeListener(EthernetListener listener) {
        mHandler.post(() -> mListeners.unregister(listener));
    }

    /** Include test interfaces as defined by the mode. */
    public void setIncludeTestInterfaces(@TestInterfaceMode int mode) {
        mHandler.post(() -> {
            mTestInterfaceMode = mode;

            // A mode change requires re-evaluating all test interfaces. The simplest way is to
            // remove them all and let the netlink dump re-discover the ones that should be tracked
            // under the new mode.
            for (EthernetPort port : getAllEthernetPorts()) {
                if (isValidTestInterface(port.getInterfaceName())) {
                    stopTrackingInterface(port);
                }
            }

            if (mode != TEST_INTERFACE_MODE_NONE) {
                mNetlinkMonitor.requestLinkDump();
            } else {
                // If mode is NONE, no need for a link dump, just clean up persisted data.
                removeTestData();
            }
        });
    }

    private void removeTestData() {
        removeTestIpData();
        removeTestCapabilityData();
    }

    private void removeTestIpData() {
        final Iterator<String> iterator = mIpConfigurations.keySet().iterator();
        while (iterator.hasNext()) {
            final String iface = iterator.next();
            if (TEST_IFACE_REGEXP.matcher(iface).matches()) {
                mConfigStore.write(iface, null);
                iterator.remove();
            }
        }
    }

    private void removeTestCapabilityData() {
        mNetworkCapabilities.keySet().removeIf(iface -> TEST_IFACE_REGEXP.matcher(iface).matches());
    }

    public void requestTetheredInterface(ITetheredInterfaceCallback cb) {
        mHandler.post(() -> {
            if (!mTetheredInterfaceRequests.register(cb)) {
                // Remote process has already died
                return;
            }
            if (mTetheringInterfaceMode == INTERFACE_MODE_SERVER && mTetheringInterface != null) {
                notifyTetheredInterfaceAvailable(cb, mTetheringInterface.getInterfaceName());
                return;
            }

            setTetheringInterfaceMode(INTERFACE_MODE_SERVER);
        });
    }

    public void releaseTetheredInterface(ITetheredInterfaceCallback callback) {
        mHandler.post(() -> {
            mTetheredInterfaceRequests.unregister(callback);
            maybeUntetherInterface();
        });
    }

    private void notifyTetheredInterfaceAvailable(ITetheredInterfaceCallback cb, String iface) {
        try {
            cb.onAvailable(iface);
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending tethered interface available callback", e);
        }
    }

    private void notifyTetheredInterfaceUnavailable(ITetheredInterfaceCallback cb) {
        try {
            cb.onUnavailable();
        } catch (RemoteException e) {
            Log.e(TAG, "Error sending tethered interface available callback", e);
        }
    }

    private void maybeUntetherInterface() {
        if (mTetheredInterfaceRequests.getRegisteredCallbackCount() > 0) return;
        if (mTetheringInterfaceMode == INTERFACE_MODE_CLIENT) return;
        setTetheringInterfaceMode(INTERFACE_MODE_CLIENT);
    }

    private void setTetheringInterfaceMode(int mode) {
        Log.d(TAG, "Setting tethering interface mode to " + mode);
        mTetheringInterfaceMode = mode;
        if (mTetheringInterface != null) {
            removeInterface(mTetheringInterface);
            addInterface(mTetheringInterface, mTetheringTrackingReason);
            // when this broadcast is sent, any calls to notifyTetheredInterfaceAvailable or
            // notifyTetheredInterfaceUnavailable have already happened
            final String ifname = mTetheringInterface.getInterfaceName();
            broadcastInterfaceStateChange(ifname, getInterfaceTrackingReason(ifname));
        }
    }

    private int getInterfaceState(final String iface) {
        if (mFactory.hasInterface(iface)) {
            return mFactory.getInterfaceState(iface);
        }
        if (getInterfaceMode(iface) == INTERFACE_MODE_SERVER) {
            // server mode interfaces are not tracked by the factory.
            // TODO(b/234743836): interface state for server mode interfaces is not tracked
            // properly; just return link up.
            return EthernetManager.STATE_LINK_UP;
        }
        return EthernetManager.STATE_ABSENT;
    }

    private int getInterfaceRole(final String iface) {
        if (mFactory.hasInterface(iface)) {
            // only client mode interfaces are tracked by the factory.
            return EthernetManager.ROLE_CLIENT;
        }
        if (getInterfaceMode(iface) == INTERFACE_MODE_SERVER) {
            return EthernetManager.ROLE_SERVER;
        }
        return EthernetManager.ROLE_NONE;
    }

    private int getInterfaceMode(final String iface) {
        if (mTetheringInterface != null && iface.equals(mTetheringInterface.getInterfaceName())) {
            return mTetheringInterfaceMode;
        }
        return INTERFACE_MODE_CLIENT;
    }

    /** Returns the TrackingReason(s) for any tracked interface; an empty EnumSet otherwise. */
    private EnumSet<TrackingReason> getInterfaceTrackingReason(String iface) {
        if (mFactory.hasInterface(iface)) {
            return mFactory.getTrackingReason(iface);
        } else if (mTetheringInterface != null) {
            return mTetheringTrackingReason;
        }
        return EnumSet.noneOf(TrackingReason.class);
    }

    private void removeInterface(EthernetPort port) {
        mFactory.removeInterface(port);
        maybeUpdateServerModeInterfaceState(port.getInterfaceName(), false);
    }

    private void stopTrackingInterface(EthernetPort port) {
        // getInterfaceTrackingReason before the interface is removed.
        final String iface = port.getInterfaceName();
        final EnumSet<TrackingReason> trackingReason = getInterfaceTrackingReason(iface);
        removeInterface(port);
        if (mTetheringInterface != null && iface.equals(mTetheringInterface.getInterfaceName())) {
            mTetheringInterface = null;
            mTetheringTrackingReason = null;
        }
        broadcastInterfaceStateChange(iface, trackingReason);
    }

    private void addInterface(EthernetPort port, EnumSet<TrackingReason> trackingReason) {
        final String iface = port.getInterfaceName();
        final InterfaceConfigurationParcel config;
        // Bring up the interface so we get link status indications.
        try {
            // Read the flags before attempting to bring up the interface. If the interface is
            // already running an UP event is created after adding the interface.
            config = NetdUtils.getInterfaceConfigParcel(mNetd, iface);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to addInterface(" + iface + "). getInterfaceConfigParcel failed", e);
            return;
        }
        if (config == null) {
            Log.e(TAG, "Null interface config parcelable for " + iface + ". Bailing out.");
            return;
        }

        // Only bring the interface up when ethernet is enabled, otherwise set interface down.
        setInterfaceUpState(iface, mIsEthernetEnabled);

        if (getInterfaceMode(iface) == INTERFACE_MODE_SERVER) {
            maybeUpdateServerModeInterfaceState(iface, true);
            return;
        }

        NetworkCapabilities nc = mNetworkCapabilities.get(iface);
        if (nc == null) {
            // Try to resolve using mac address
            nc = mNetworkCapabilities.get(port.getMacAddress().toString());
            if (nc == null) {
                final boolean isTestIface = TEST_IFACE_REGEXP.matcher(iface).matches();
                nc = createDefaultNetworkCapabilities(isTestIface);
            }
        }

        IpConfiguration ipConfiguration = getOrCreateIpConfiguration(iface);
        Log.d(TAG, "Tracking interface in client mode: " + iface);
        mFactory.addInterface(port, ipConfiguration, nc, trackingReason);

        // Note: if the interface already has link (e.g., if we crashed and got
        // restarted while it was running), we need to fake a link up notification so we
        // start configuring it.
        if (NetdUtils.hasFlag(config, INetd.IF_FLAG_RUNNING)) {
            // no need to send an interface state change as this is not a true "state change". The
            // callers (trackInterface() and setTetheringInterfaceMode()) already broadcast the
            // state change.
            mFactory.updateInterfaceLinkState(port, true);
        }
    }

    private void setInterfaceAdministrativeState(String iface, boolean up, EthernetCallback cb) {
        if (!mIsEthernetEnabled) {
            cb.onError("Cannot enable/disable interface when ethernet is disabled");
            return;
        }
        if (getInterfaceState(iface) == EthernetManager.STATE_ABSENT) {
            cb.onError("Failed to enable/disable absent interface: " + iface);
            return;
        }
        if (getInterfaceRole(iface) == EthernetManager.ROLE_SERVER) {
            // TODO: support setEthernetState for server mode interfaces.
            cb.onError("Failed to enable/disable interface in server mode: " + iface);
            return;
        }

        setInterfaceUpState(iface, up);
        cb.onResult(iface);
    }

    private void updateInterfaceState(EthernetPort port, boolean up) {
        final String iface = port.getInterfaceName();
        final int mode = getInterfaceMode(iface);
        if (mode == INTERFACE_MODE_SERVER) {
            // TODO: support tracking link state for interfaces in server mode.
            return;
        }

        // If updateInterfaceLinkState returns false, the interface is already in the correct state.
        if (mFactory.updateInterfaceLinkState(port, up)) {
            broadcastInterfaceStateChange(iface, getInterfaceTrackingReason(iface));
        }
    }

    private void maybeUpdateServerModeInterfaceState(String iface, boolean available) {
        if (mTetheringInterface == null) return;
        if (!iface.equals(mTetheringInterface.getInterfaceName())) return;
        if (available == mTetheredInterfaceWasAvailable) return;

        Log.d(TAG, (available ? "Tracking" : "No longer tracking")
                + " interface in server mode: " + iface);

        final int pendingCbs = mTetheredInterfaceRequests.beginBroadcast();
        for (int i = 0; i < pendingCbs; i++) {
            ITetheredInterfaceCallback item = mTetheredInterfaceRequests.getBroadcastItem(i);
            if (available) {
                notifyTetheredInterfaceAvailable(item, iface);
            } else {
                notifyTetheredInterfaceUnavailable(item);
            }
        }
        mTetheredInterfaceRequests.finishBroadcast();
        mTetheredInterfaceWasAvailable = available;
    }

    private void trackInterface(EthernetPort port, EnumSet<TrackingReason> trackingReason) {
        final String iface = port.getInterfaceName();
        if (DBG) Log.i(TAG, "trackInterface: " + port);

        // Do not use an interface for tethering if it has configured NetworkCapabilities, or if it
        // was not included in the regex.
        if (mTetheringInterface == null && !mNetworkCapabilities.containsKey(iface)
                && trackingReason.contains(TrackingReason.REGEX)) {
            mTetheringInterface = port;
            mTetheringTrackingReason = trackingReason;
        }

        addInterface(port, trackingReason);

        broadcastInterfaceStateChange(iface, trackingReason);
    }

    /**
     * Parses an Ethernet interface configuration
     *
     * @param configString represents an Ethernet configuration in the following format: {@code
     * <interface name|mac address>;[Network Capabilities];[IP config];[Override Transport]}
     */
    private void parseEthernetConfig(String configString) {
        final EthernetConfigParser config =
                new EthernetConfigParser(configString, mDeps.isAtLeastB());
        mNetworkCapabilities.put(config.mIface, config.mCaps);

        if (null != config.mIpConfig) {
            mIpConfigurations.put(config.mIface, config.mIpConfig);
        }
    }

    private static NetworkCapabilities createDefaultNetworkCapabilities(boolean isTestIface) {
        final NetworkCapabilities.Builder builder =
                new NetworkCapabilities.Builder(DEFAULT_CAPABILITIES);
        if (isTestIface) {
            builder.addTransportType(NetworkCapabilities.TRANSPORT_TEST);
        }

        return builder.build();
    }

    private IpConfiguration getOrCreateIpConfiguration(String iface) {
        IpConfiguration ret = mIpConfigurations.get(iface);
        if (ret != null) return ret;
        ret = new IpConfiguration();
        ret.setIpAssignment(IpAssignment.DHCP);
        ret.setProxySettings(ProxySettings.NONE);
        return ret;
    }

    /**
     * Returns the reason for tracking an interface.
     *
     * If the returned EnumSet is empty, the interface is not tracked.
     */
    private EnumSet<TrackingReason> inferTrackingReason(String iface) {
        final EnumSet<TrackingReason> reasons = EnumSet.noneOf(TrackingReason.class);
        if (mIfaceMatch.matcher(iface).matches()) {
            reasons.add(TrackingReason.REGEX);
        }

        if (isValidTestInterface(iface)) {
            if ((mTestInterfaceMode & EthernetManager.TEST_INTERFACE_MODE_ETHERNET) != 0) {
                reasons.add(TrackingReason.REGEX);
            }
            if ((mTestInterfaceMode & EthernetManager.TEST_INTERFACE_MODE_NCM) != 0) {
                reasons.add(TrackingReason.NCM);
            }
        }

        // TODO: remove this flag after M-2025-09 release.
        if (!DeviceConfigUtils.isTetheringFeatureNotChickenedOut(mContext, NCM_ENABLED_FLAG)) {
            return reasons;
        }

        // Host-side NCM interfaces are guaranteed to be named either usb%d or eth%d.
        // Since this function is run against every interface that appears on the system, skip
        // invoking the ethtool API if the name does match this pattern.
        if (!NCM_HOST_REGEXP.matcher(iface).matches()) return reasons;

        String driverName = null;
        try {
            // Note that this operation is race-y because it relies on the interface name.
            // TODO: figure out whether udev/ueventd can fix this.
            driverName = ServiceConnectivityJni.getDriverNameForInterface(iface);
        } catch (ErrnoException e) {
            if (e.errno != ENOTSUP) Log.w(TAG, "Failed to get driver name for " + iface, e);
        }

        if (NCM_HOST_DRIVER_NAME.equals(driverName)) {
            reasons.add(TrackingReason.NCM);
        }
        return reasons;
    }

    /**
     * Validate if a given interface is valid for testing.
     *
     * @param iface the name of the interface to validate.
     * @return {@code true} if test interfaces are enabled and the given {@code iface} has a test
     * interface prefix, {@code false} otherwise.
     */
    public boolean isValidTestInterface(@NonNull final String iface) {
        return TEST_IFACE_REGEXP.matcher(iface).matches();
    }

    private void postAndWaitForRunnable(Runnable r) {
        final ConditionVariable cv = new ConditionVariable();
        if (mHandler.post(() -> {
            r.run();
            cv.open();
        })) {
            cv.block(2000L);
        }
    }

    @VisibleForTesting(visibility = PACKAGE)
    protected void setEthernetEnabled(boolean enabled) {
        mHandler.post(() -> {
            if (mIsEthernetEnabled == enabled) return;

            mIsEthernetEnabled = enabled;
            for (EthernetPort port : getAllEthernetPorts()) {
                setInterfaceUpState(port.getInterfaceName(), enabled);
            }
            broadcastEthernetStateChange(mIsEthernetEnabled);
        });
    }

    private int isEthernetEnabledAsInt(boolean state) {
        return state ? ETHERNET_STATE_ENABLED : ETHERNET_STATE_DISABLED;
    }

    private void unicastEthernetStateChange(EthernetListener listener, boolean enabled) {
        ensureRunningOnEthernetServiceThread();
        listener.onEthernetStateChanged(isEthernetEnabledAsInt(enabled));
    }

    private void broadcastEthernetStateChange(boolean enabled) {
        ensureRunningOnEthernetServiceThread();
        final int n = mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            mListeners.getBroadcastItem(i).onEthernetStateChanged(isEthernetEnabledAsInt(enabled));
        }
        mListeners.finishBroadcast();
    }

    private void setInterfaceUpState(@NonNull String interfaceName, boolean up) {
        if (!NetlinkUtils.setInterfaceFlags(Os.if_nametoindex(interfaceName),
                up ? IFF_UP : ~IFF_UP)) {
            Log.e(TAG, "Failed to set interface " + interfaceName + (up ? " up" : " down"));
        }
    }

    void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args) {
        postAndWaitForRunnable(() -> {
            pw.println(getClass().getSimpleName());
            pw.println("Ethernet State: "
                    + (mIsEthernetEnabled ? "enabled" : "disabled"));
            pw.println("Ethernet interface name filter: " + mIfaceMatch);
            pw.println("Interface used for tethering: " + mTetheringInterface);
            pw.println("Tethering interface mode: " + mTetheringInterfaceMode);
            pw.println("Tethered interface requests: "
                    + mTetheredInterfaceRequests.getRegisteredCallbackCount());
            pw.println("Listeners: " + mListeners.getRegisteredCallbackCount());
            pw.println("IP Configurations:");
            pw.increaseIndent();
            for (String iface : mIpConfigurations.keySet()) {
                pw.println(iface + ": " + mIpConfigurations.get(iface));
            }
            pw.decreaseIndent();
            pw.println();

            pw.println("Network Capabilities:");
            pw.increaseIndent();
            for (String iface : mNetworkCapabilities.keySet()) {
                pw.println(iface + ": " + mNetworkCapabilities.get(iface));
            }
            pw.decreaseIndent();
            pw.println();

            mFactory.dump(fd, pw, args);
        });
    }

    @VisibleForTesting
    static class EthernetConfigParser {
        final String mIface;
        final NetworkCapabilities mCaps;
        @Nullable final IpConfiguration mIpConfig;

        private static NetworkCapabilities parseCapabilities(@Nullable String capabilitiesString,
                boolean isAtLeastB) {
            final NetworkCapabilities.Builder builder =
                    NetworkCapabilities.Builder.withoutDefaultCapabilities();
            builder.setLinkUpstreamBandwidthKbps(100 * 1000 /* 100 Mbps */);
            builder.setLinkDownstreamBandwidthKbps(100 * 1000 /* 100 Mbps */);
            // Ethernet networks have no way to update the following capabilities, so they always
            // have them.
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);

            if (capabilitiesString == null) {
                return builder.build();
            }

            if (isAtLeastB && capabilitiesString.equals("*")) {
                // On Android B+, a "*" string defaults to the same set of default
                // capabilities assigned to unconfigured interfaces.
                // Note that the transport type is populated later with the result of
                // parseTransportType().
                return new NetworkCapabilities.Builder(DEFAULT_CAPABILITIES)
                        .removeTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .build();
            }

            for (String strNetworkCapability : capabilitiesString.split(",")) {
                if (TextUtils.isEmpty(strNetworkCapability)) {
                    continue;
                }
                final Integer capability;
                try {
                    builder.addCapability(Integer.valueOf(strNetworkCapability));
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Failed to parse capability: " + strNetworkCapability, e);
                    continue;
                }
            }
            return builder.build();
        }

        private static int parseTransportType(@Nullable String transportString) {
            if (TextUtils.isEmpty(transportString)) {
                return TRANSPORT_ETHERNET;
            }

            final int parsedTransport;
            try {
                parsedTransport = Integer.valueOf(transportString);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Failed to parse transport type", e);
                return TRANSPORT_ETHERNET;
            }

            if (!NetworkCapabilities.isValidTransport(parsedTransport)) {
                return TRANSPORT_ETHERNET;
            }

            switch (parsedTransport) {
                case TRANSPORT_VPN:
                case TRANSPORT_WIFI_AWARE:
                case TRANSPORT_LOWPAN:
                    Log.e(TAG, "Unsupported transport type '" + parsedTransport + "'");
                    return TRANSPORT_ETHERNET;
                default:
                    return parsedTransport;
            }
        }

        @Nullable
        private static IpConfiguration parseStaticIpConfiguration(String staticIpConfig) {
            if (TextUtils.isEmpty(staticIpConfig)) return null;

            final StaticIpConfiguration.Builder staticIpConfigBuilder =
                    new StaticIpConfiguration.Builder();

            for (String keyValueAsString : staticIpConfig.trim().split(" ")) {
                if (TextUtils.isEmpty(keyValueAsString)) continue;

                final String[] pair = keyValueAsString.split("=");
                if (pair.length != 2) {
                    throw new IllegalArgumentException("Unexpected token: " + keyValueAsString
                            + " in " + staticIpConfig);
                }

                final String key = pair[0];
                final String value = pair[1];
                switch (key) {
                    case "ip":
                        staticIpConfigBuilder.setIpAddress(new LinkAddress(value));
                        break;
                    case "domains":
                        staticIpConfigBuilder.setDomains(value);
                        break;
                    case "gateway":
                        staticIpConfigBuilder.setGateway(InetAddress.parseNumericAddress(value));
                        break;
                    case "dns": {
                        ArrayList<InetAddress> dnsAddresses = new ArrayList<>();
                        for (String address: value.split(",")) {
                            dnsAddresses.add(InetAddress.parseNumericAddress(address));
                        }
                        staticIpConfigBuilder.setDnsServers(dnsAddresses);
                        break;
                    }
                    default: {
                        throw new IllegalArgumentException("Unexpected key: " + key
                                + " in " + staticIpConfig);
                    }
                }
            }
            return new IpConfiguration.Builder()
                    .setStaticIpConfiguration(staticIpConfigBuilder.build())
                    .build();
        }

        EthernetConfigParser(String configString, boolean bplus) {
            Objects.requireNonNull(configString, "EthernetConfigParser requires non-null config");
            final String[] t = configString.split(";", /* limit of tokens */ 4);
            mIface = t[0];

            final NetworkCapabilities nc = parseCapabilities(t.length > 1 ? t[1] : null, bplus);
            final int transportType = parseTransportType(t.length > 3 ? t[3] : null);
            nc.addTransportType(transportType);
            mCaps = nc;

            mIpConfig = parseStaticIpConfiguration(t.length > 2 ? t[2] : null);
        }
    }
}
