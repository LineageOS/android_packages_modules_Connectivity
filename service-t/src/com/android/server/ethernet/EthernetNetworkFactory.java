/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_USB;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.EthernetManager;
import android.net.EthernetNetworkSpecifier;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkProperties;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.ip.IIpClient;
import android.net.ip.IpClientCallbacks;
import android.net.ip.IpClientManager;
import android.net.ip.IpClientUtil;
import android.net.shared.ProvisioningConfiguration;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.connectivity.resources.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.net.module.util.InterfaceParams;
import com.android.server.connectivity.ConnectivityResources;
import com.android.server.ethernet.EthernetTracker.TrackingReason;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that manages NetworkOffers for Ethernet networks.
 *
 * TODO: this class should be merged into EthernetTracker.
 */
public class EthernetNetworkFactory {
    private final static String TAG = EthernetNetworkFactory.class.getSimpleName();
    final static boolean DBG = true;

    private static final String NETWORK_TYPE = "Ethernet";

    private final ConcurrentHashMap<String, NetworkInterfaceState> mTrackingInterfaces =
            new ConcurrentHashMap<>();
    private final Handler mHandler;
    private final Context mContext;
    private final NetworkProvider mProvider;
    final Dependencies mDeps;

    public static class Dependencies {
        public void makeIpClient(Context context, String iface, IpClientCallbacks callbacks) {
            IpClientUtil.makeIpClient(context, iface, callbacks);
        }

        public IpClientManager makeIpClientManager(@NonNull final IIpClient ipClient) {
            return new IpClientManager(ipClient, TAG);
        }

        public EthernetNetworkAgent makeEthernetNetworkAgent(Context context, Looper looper,
                NetworkCapabilities nc, LinkProperties lp, NetworkAgentConfig config,
                NetworkProvider provider, EthernetNetworkAgent.Callbacks cb) {
            return new EthernetNetworkAgent(context, looper, nc, lp, config, provider, cb);
        }

        public InterfaceParams getNetworkInterfaceByName(String name) {
            return InterfaceParams.getByName(name);
        }

        public String getTcpBufferSizesFromResource(Context context) {
            final ConnectivityResources resources = new ConnectivityResources(context);
            return resources.get().getString(R.string.config_ethernet_tcp_buffers);
        }
    }

    public static class ConfigurationException extends AndroidRuntimeException {
        public ConfigurationException(String msg) {
            super(msg);
        }
    }

    public EthernetNetworkFactory(Handler handler, Context context) {
        this(handler, context, new NetworkProvider(context, handler.getLooper(), TAG),
            new Dependencies());
    }

    @VisibleForTesting
    EthernetNetworkFactory(Handler handler, Context context, NetworkProvider provider,
            Dependencies deps) {
        mHandler = handler;
        mContext = context;
        mProvider = provider;
        mDeps = deps;
    }

    /**
     * Registers the network provider with the system.
     */
    public void register() {
        mContext.getSystemService(ConnectivityManager.class).registerNetworkProvider(mProvider);
    }

    /** Returns an unordered(!) list of EthernetPort objects tracked by this factory. */
    public List<EthernetPort> getEthernetPorts() {
        // Note that while mTrackingInterfaces is a ConcurrentHashMap, it is only ever modified on
        // the handler thread.
        final List<EthernetPort> ports = new ArrayList<>(mTrackingInterfaces.size());
        for (NetworkInterfaceState iface : mTrackingInterfaces.values()) {
            ports.add(iface.getPort());
        }
        return ports;
    }

    /**
     * Returns an array of available interface names. The array is sorted: unrestricted interfaces
     * goes first, then sorted by name.
     */
    public String[] getInterfacesSorted(boolean includeRestricted) {
        return mTrackingInterfaces.values()
                .stream()
                .filter(iface -> !iface.isRestricted() || includeRestricted)
                .sorted((iface1, iface2) -> {
                    final int r = Boolean.compare(iface1.isRestricted(), iface2.isRestricted());
                    final String ifname1 = iface1.getPort().getInterfaceName();
                    final String ifname2 = iface2.getPort().getInterfaceName();
                    return r == 0 ? ifname1.compareTo(ifname2) : r;
                })
                .map(iface -> iface.getPort().getInterfaceName())
                .toArray(String[]::new);
    }

    /** Add an interface to the factory. */
    public void addInterface(EthernetPort port, IpConfiguration ipConfig,
            NetworkCapabilities capabilities, EnumSet<TrackingReason> trackingReason) {
        final String ifaceName = port.getInterfaceName();
        final String hwAddress = port.getMacAddress().toString();

        if (mTrackingInterfaces.containsKey(ifaceName)) {
            Log.e(TAG, "Interface with name " + ifaceName + " already exists.");
            return;
        }

        final NetworkCapabilities nc = new NetworkCapabilities.Builder(capabilities)
                .setNetworkSpecifier(new EthernetNetworkSpecifier(ifaceName))
                .build();

        if (DBG) {
            Log.d(TAG, "addInterface, iface: " + ifaceName + ", capabilities: " + nc);
        }

        final NetworkInterfaceState iface = new NetworkInterfaceState(
                port, trackingReason, mHandler, mContext, ipConfig, nc, mProvider, mDeps);
        mTrackingInterfaces.put(ifaceName, iface);
    }

    @VisibleForTesting
    protected int getInterfaceState(@NonNull String iface) {
        final NetworkInterfaceState interfaceState = mTrackingInterfaces.get(iface);
        if (interfaceState == null) {
            return EthernetManager.STATE_ABSENT;
        } else if (!interfaceState.mLinkUp) {
            return EthernetManager.STATE_LINK_DOWN;
        } else {
            return EthernetManager.STATE_LINK_UP;
        }
    }

    /**
     * Update a network's configuration and restart it if necessary.
     *
     * @param ifaceName the interface name of the network to be updated.
     * @param ipConfig the desired {@link IpConfiguration} for the given network or null. If
     *                 {@code null} is passed, the existing IpConfiguration is not updated.
     * @param capabilities the desired {@link NetworkCapabilities} for the given network. If
     *                     {@code null} is passed, then the network's current
     *                     {@link NetworkCapabilities} will be used in support of existing APIs as
     *                     the public API does not allow this.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected void updateInterface(@NonNull final String ifaceName,
            @Nullable final IpConfiguration ipConfig,
            @Nullable final NetworkCapabilities capabilities) {
        if (!hasInterface(ifaceName)) {
            return;
        }

        final NetworkInterfaceState iface = mTrackingInterfaces.get(ifaceName);
        iface.updateInterface(ipConfig, capabilities);
        mTrackingInterfaces.put(ifaceName, iface);
        return;
    }

    /** Removes the interface from the factory and returns whether the interface was tracked */
    public void removeInterface(EthernetPort port) {
        final NetworkInterfaceState iface = mTrackingInterfaces.remove(port.getInterfaceName());
        if (iface == null) return; // interface is in tethering mode; nothing to do.
        iface.unregisterNetworkOfferAndStop();
    }

    /** Returns true if state has been modified */
    public boolean updateInterfaceLinkState(EthernetPort port, boolean up) {
        final String ifaceName = port.getInterfaceName();
        if (!hasInterface(ifaceName)) {
            return false;
        }

        if (DBG) {
            Log.d(TAG, "updateInterfaceLinkState, iface: " + ifaceName + ", up: " + up);
        }

        NetworkInterfaceState iface = mTrackingInterfaces.get(ifaceName);
        return iface.updateLinkState(up);
    }

    /**
     * Returns true if this interface is currently tracked by this factory.
     *
     * Use {@link #getTrackingReason(String)} to distinguish between interfaces that are tracked by
     * the regex and local-only NCM interfaces.
     */
    public boolean hasInterface(String ifaceName) {
        return mTrackingInterfaces.containsKey(ifaceName);
    }

    /** Returns the TrackingReason or an empty EnumSet if the interface does not exist */
    public EnumSet<TrackingReason> getTrackingReason(String ifname) {
        final NetworkInterfaceState iface = mTrackingInterfaces.get(ifname);
        if (iface == null) return EnumSet.noneOf(TrackingReason.class);
        return iface.getTrackingReason();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @Nullable
    protected String getHwAddress(@NonNull final String ifaceName) {
        if (!hasInterface(ifaceName)) {
            return null;
        }

        NetworkInterfaceState iface = mTrackingInterfaces.get(ifaceName);
        return iface.getPort().getMacAddress().toString();
    }

    @VisibleForTesting
    static class NetworkInterfaceState {
        private static final NetworkScore NETWORK_SCORE = new NetworkScore.Builder().build();
        /**
         * Capabilities used for local-only connectivity on NCM interfaces.
         *
         * Note that because a request for such a network takes precedent over any "global" network
         * requests (and therefore breaks global connectivity on these interfaces), the network is
         * marked restricted. Requestors need to be careful to always release the NetworkRequest
         * after they are done using this network.
         **/
        private static final NetworkCapabilities LOCAL_NCM_CAPABILITIES =
                NetworkCapabilities.Builder.withoutDefaultCapabilities()
                        .addTransportType(TRANSPORT_USB)
                        .addCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
                        .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                        .addCapability(NET_CAPABILITY_NOT_METERED)
                        .addCapability(NET_CAPABILITY_NOT_ROAMING)
                        .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                        .addCapability(NET_CAPABILITY_NOT_VPN)
                        .build();

        private final EthernetPort mPort;
        private final EnumSet<TrackingReason> mTrackingReason;
        private final Handler mHandler;
        private final Context mContext;
        private final NetworkProvider mNetworkProvider;
        private final Dependencies mDeps;
        @Nullable private NetworkProvider.NetworkOfferCallback mNetworkOfferCallback;
        @Nullable private NetworkProvider.NetworkOfferCallback mLocalNetworkOfferCallback;

        private static String sTcpBufferSizes = null;  // Lazy initialized.

        private boolean mLinkUp;
        private int mLegacyType;
        private LinkProperties mLinkProperties = new LinkProperties();

        private volatile @Nullable IpClientManager mIpClient;
        private NetworkCapabilities mCapabilities;
        private @Nullable EthernetIpClientCallback mIpClientCallback;
        private @Nullable EthernetNetworkAgent mNetworkAgent;
        private @Nullable EthernetNetworkAgent.Callbacks mNetworkAgentCallback;
        private IpConfiguration mIpConfig;

        /**
         * A map of TRANSPORT_* types to legacy transport types available for each type an ethernet
         * interface could propagate.
         *
         * There are no legacy type equivalents to LOWPAN or WIFI_AWARE. These types are set to
         * TYPE_NONE to match the behavior of their own network factories.
         */
        private static final SparseArray<Integer> sTransports = new SparseArray();
        static {
            sTransports.put(NetworkCapabilities.TRANSPORT_ETHERNET,
                    ConnectivityManager.TYPE_ETHERNET);
            sTransports.put(NetworkCapabilities.TRANSPORT_BLUETOOTH,
                    ConnectivityManager.TYPE_BLUETOOTH);
            sTransports.put(NetworkCapabilities.TRANSPORT_WIFI, ConnectivityManager.TYPE_WIFI);
            sTransports.put(NetworkCapabilities.TRANSPORT_CELLULAR,
                    ConnectivityManager.TYPE_MOBILE);
            sTransports.put(NetworkCapabilities.TRANSPORT_LOWPAN, ConnectivityManager.TYPE_NONE);
            sTransports.put(NetworkCapabilities.TRANSPORT_WIFI_AWARE,
                    ConnectivityManager.TYPE_NONE);
        }

        // TODO: Create a state machine to simplify this logic and also support tethering mode.
        /** Tracks what type of network is currently being provided for this interface */
        private enum Mode {
            /** Indicates that no network of any type has been created for this interface */
            NONE,
            /** Indicates that a global network (i.e. with internet capability) is being provided */
            GLOBAL,
            /** Indicates that a local-only network is being provided */
            LOCAL,
        }
        private Mode mMode = Mode.NONE;

        private class EthernetIpClientCallback extends IpClientCallbacks {
            private final ConditionVariable mIpClientStartCv = new ConditionVariable(false);
            private final ConditionVariable mIpClientShutdownCv = new ConditionVariable(false);

            @Override
            public void onIpClientCreated(IIpClient ipClient) {
                mIpClient = mDeps.makeIpClientManager(ipClient);
                mIpClientStartCv.open();
            }

            private void awaitIpClientStart() {
                mIpClientStartCv.block();
            }

            private void awaitIpClientShutdown() {
                mIpClientShutdownCv.block();
            }

            private void safelyPostOnHandler(Runnable r) {
                mHandler.post(() -> {
                    if (this != mIpClientCallback) {
                        // At the time IpClient is stopped, an IpClient event may have already been
                        // posted on the handler and is awaiting execution. Once that event is
                        // executed, the associated callback object may not be valid anymore.
                        return;
                    }
                    r.run();
                });
            }

            @Override
            public void onProvisioningSuccess(LinkProperties newLp) {
                safelyPostOnHandler(() -> handleOnProvisioningSuccess(newLp));
            }

            @Override
            public void onProvisioningFailure(LinkProperties newLp) {
                // This cannot happen due to provisioning timeout, because our timeout is 0. It can
                // happen due to errors while provisioning or on provisioning loss.
                safelyPostOnHandler(() -> handleOnProvisioningFailure());
            }

            @Override
            public void onLinkPropertiesChange(LinkProperties newLp) {
                safelyPostOnHandler(() -> handleOnLinkPropertiesChange(newLp));
            }

            @Override
            public void onReachabilityLost(String logMsg) {
                safelyPostOnHandler(() -> handleOnReachabilityLost(logMsg));
            }

            @Override
            public void onQuit() {
                mIpClient = null;
                mIpClientShutdownCv.open();
            }
        }

        private class EthernetNetworkAgentCallback implements EthernetNetworkAgent.Callbacks {
            private boolean isStale() {
                return this != mNetworkAgentCallback;
            }

            @Override
            public void onNetworkUnwanted() {
                if (isStale()) return;
                stop();
            }
        }

        private final RequestTracker mRequestTracker = new RequestTracker();
        private static class RequestTracker {
            private final Set<Integer> mGlobalRequests = new ArraySet<>();
            private final Set<Integer> mLocalRequests = new ArraySet<>();

            /** Reflects whether the global or local NetworkOffer was requested. */
            public enum RequestType {
                LOCAL,
                GLOBAL,
            }

            private Set<Integer> getRequestSet(RequestType type) {
                return (type == RequestType.GLOBAL) ? mGlobalRequests : mLocalRequests;
            }

            public void addRequest(NetworkRequest request, RequestType type) {
                getRequestSet(type).add(request.requestId);
            }

            public void removeRequest(NetworkRequest request, RequestType type) {
                if (!getRequestSet(type).remove(request.requestId)) {
                    // This can only happen if onNetworkNeeded was not called for a request or if
                    // the requestId changed. Both should *never* happen.
                    Log.wtf(TAG, "removeRequest called for unknown request");
                }
            }

            public void clear() {
                mGlobalRequests.clear();
                mLocalRequests.clear();
            }

            public Mode getNetworkNeededMode() {
                // Local requests take precedence.
                if (!mLocalRequests.isEmpty()) return Mode.LOCAL;
                if (!mGlobalRequests.isEmpty()) return Mode.GLOBAL;
                return Mode.NONE;
            }
        }

        private void onRequestTrackerUpdate() {
            final Mode newMode = mRequestTracker.getNetworkNeededMode();
            if (mMode == newMode) return;

            // If the interface is already stopped, stop() is a noop.
            stop();
            if (newMode != Mode.NONE) start(newMode);
        }

        private class EthernetNetworkOfferCallback implements NetworkProvider.NetworkOfferCallback {
            private boolean isStale() {
                return this != mNetworkOfferCallback;
            }

            @Override
            public void onNetworkNeeded(@NonNull NetworkRequest request) {
                if (isStale()) return;
                if (DBG) Log.d(TAG, String.format("%s: onNetworkNeeded: %s", mPort, request));

                // When the network offer is first registered, onNetworkNeeded is called with all
                // existing requests.
                // ConnectivityService filters requests for us based on the NetworkCapabilities
                // passed in the maybeRegisterOrUpdateNetworkOffer() call.
                mRequestTracker.addRequest(request, RequestTracker.RequestType.GLOBAL);
                onRequestTrackerUpdate();
            }

            @Override
            public void onNetworkUnneeded(@NonNull NetworkRequest request) {
                if (isStale()) return;
                if (DBG) Log.d(TAG, String.format("%s: onNetworkUnneeded: %s", mPort, request));

                mRequestTracker.removeRequest(request, RequestTracker.RequestType.GLOBAL);
                onRequestTrackerUpdate();
            }
        }

        /**
         * Special NetworkOffer used to provide IPv6 link-local only network connectivity on an NCM
         * interface.
         *
         * Requests for this offer take precedence over requests for the "global" offer above (see
         * {@link EthernetNetworkOfferCallback}).
         */
        private class LocalNetworkOfferCallback implements NetworkProvider.NetworkOfferCallback {
            private boolean isStale() {
                return this != mLocalNetworkOfferCallback;
            }

            @Override
            public void onNetworkNeeded(NetworkRequest request) {
                if (isStale()) return;
                mRequestTracker.addRequest(request, RequestTracker.RequestType.LOCAL);
                onRequestTrackerUpdate();
            }

            @Override
            public void onNetworkUnneeded(NetworkRequest request) {
                if (isStale()) return;
                mRequestTracker.removeRequest(request, RequestTracker.RequestType.LOCAL);
                onRequestTrackerUpdate();
            }
        }

        NetworkInterfaceState(EthernetPort port, EnumSet<TrackingReason> trackingReason,
                Handler handler, Context context, IpConfiguration ipConfig,
                NetworkCapabilities capabilities, NetworkProvider networkProvider,
                Dependencies deps) {
            mPort = port;
            mTrackingReason = trackingReason;
            mIpConfig = Objects.requireNonNull(ipConfig);
            mCapabilities = Objects.requireNonNull(capabilities);
            mLegacyType = getLegacyType(mCapabilities);
            mHandler = handler;
            mContext = context;
            mNetworkProvider = networkProvider;
            mDeps = deps;
        }

        /** Returns the EthernetPort object */
        public EthernetPort getPort() {
            return mPort;
        }

        /** Returns the TrackingReason */
        public EnumSet<TrackingReason> getTrackingReason() {
            return mTrackingReason;
        }

        /**
         * Determines the legacy transport type from a NetworkCapabilities transport type. Defaults
         * to legacy TYPE_NONE if there is no known conversion
         */
        private static int getLegacyType(int transport) {
            return sTransports.get(transport, ConnectivityManager.TYPE_NONE);
        }

        private static int getLegacyType(@NonNull final NetworkCapabilities capabilities) {
            final int[] transportTypes = capabilities.getTransportTypes();
            if (transportTypes.length > 0) {
                return getLegacyType(transportTypes[0]);
            }

            // Should never happen as transport is always one of ETHERNET or a valid override
            throw new ConfigurationException("Network Capabilities do not have an associated "
                    + "transport type.");
        }

        private void setCapabilities(@NonNull final NetworkCapabilities capabilities) {
            mCapabilities = new NetworkCapabilities(capabilities);
            mLegacyType = getLegacyType(mCapabilities);

            if (mLinkUp) {
                // update the existing network offer with the new capabilities. Note that this only
                // affects the global network offer.
                maybeRegisterOrUpdateNetworkOffer();
            }
        }

        void updateInterface(@Nullable final IpConfiguration ipConfig,
                @Nullable final NetworkCapabilities capabilities) {
            if (DBG) {
                Log.d(TAG, "updateInterface, port: " + mPort
                        + ", ipConfig: " + ipConfig + ", old ipConfig: " + mIpConfig
                        + ", capabilities: " + capabilities + ", old capabilities: " + mCapabilities
                );
            }

            if (null != ipConfig){
                mIpConfig = ipConfig;
            }
            if (null != capabilities) {
                setCapabilities(capabilities);
            }

            // If no request is currently being served (Mode.NONE) or the interface is in local NCM
            // mode (Mode.LOCAL), do not restart the interface. updateInterface() does not affect
            // the NCM capabilities or IpConfiguration.
            if (mMode != Mode.GLOBAL) return;

            // TODO: Update this logic to only do a restart if required. Although a restart may
            //  be required due to the capabilities or ipConfiguration values, not all
            //  capabilities changes require a restart.
            maybeRestart();
        }

        public boolean isRestricted() {
            return !mCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        }

        private void start(Mode mode) {
            // Ensure stop() is called an all associated resources are cleaned up before starting in
            // a (potentially) different mode.
            if (mMode != Mode.NONE) throw new IllegalStateException("Forgot to call stop()");
            if (mode == Mode.NONE) throw new IllegalArgumentException("Can't use Mode.NONE");
            mMode = mode;

            if (mIpClient != null) {
                if (DBG) Log.d(TAG, "IpClient already started");
                return;
            }
            if (DBG) {
                Log.d(TAG, String.format("Starting Ethernet IpClient(%s)", mPort));
            }

            mIpClientCallback = new EthernetIpClientCallback();
            mDeps.makeIpClient(mContext, mPort.getInterfaceName(), mIpClientCallback);
            mIpClientCallback.awaitIpClientStart();

            // Ethernet-specific settings are only applied in global mode.
            if (mMode == Mode.GLOBAL) {
                if (mIpConfig.getProxySettings() == ProxySettings.STATIC
                        || mIpConfig.getProxySettings() == ProxySettings.PAC) {
                    mIpClient.setHttpProxy(mIpConfig.getHttpProxy());
                }

                if (sTcpBufferSizes == null) {
                    sTcpBufferSizes = mDeps.getTcpBufferSizesFromResource(mContext);
                }
                if (!TextUtils.isEmpty(sTcpBufferSizes)) {
                    mIpClient.setTcpBufferSizes(sTcpBufferSizes);
                }
            }

            final ProvisioningConfiguration.Builder config = new ProvisioningConfiguration.Builder()
                    .withProvisioningTimeoutMs(0 /* infinite */);

            if (mMode == Mode.GLOBAL && mIpConfig.getIpAssignment() == IpAssignment.STATIC) {
                // TODO: add ProvisioningConfiguration.Builder#withIpConfiguration
                config.withStaticConfiguration(mIpConfig.getStaticIpConfiguration());
            }

            // Local mode is IPv6 link-local only.
            if (mMode == Mode.LOCAL) {
                config.withoutIPv4();
                config.withIpv6LinkLocalOnly();
            }

            mIpClient.startProvisioning(config.build());
        }

        private void handleOnProvisioningSuccess(@NonNull final LinkProperties linkProperties) {
            mLinkProperties = linkProperties;

            final NetworkAgentConfig networkAgentConfig;
            final NetworkCapabilities capabilities;
            if (mMode == Mode.GLOBAL) {
                // Only configure legacy config options for global mode.
                networkAgentConfig = new NetworkAgentConfig.Builder()
                        .setLegacyType(mLegacyType)
                        .setLegacyTypeName(NETWORK_TYPE)
                        .setLegacyExtraInfo(mPort.getMacAddress().toString())
                        .build();
                capabilities = mCapabilities;
            } else {
                networkAgentConfig = new NetworkAgentConfig.Builder().build();
                capabilities = LOCAL_NCM_CAPABILITIES;
            }

            mNetworkAgentCallback = new EthernetNetworkAgentCallback();
            mNetworkAgent = mDeps.makeEthernetNetworkAgent(mContext, mHandler.getLooper(),
                    capabilities, mLinkProperties, networkAgentConfig, mNetworkProvider,
                    mNetworkAgentCallback);
            mNetworkAgent.register();
            mNetworkAgent.markConnected();
        }

        private void handleOnProvisioningFailure() {
            // There is no point in continuing if the interface is gone as stop() will be triggered
            // by removeInterface() when processed on the handler thread and start() won't
            // work for a non-existent interface.
            // TODO: consider removing this functionality entirely and maybeRestart() regardless.
            if (null == mDeps.getNetworkInterfaceByName(mPort.getInterfaceName())) {
                if (DBG) Log.d(TAG, mPort + " is no longer available.");
                // Send a callback in case a provisioning request was in progress.
                return;
            }
            maybeRestart();
        }

        private void handleOnLinkPropertiesChange(LinkProperties linkProperties) {
            mLinkProperties = linkProperties;
            if (mNetworkAgent != null) {
                mNetworkAgent.sendLinkPropertiesImpl(linkProperties);
            }
        }

        private void handleOnReachabilityLost(String logMsg) {
            Log.i(TAG, "handleOnReachabilityLost " + logMsg);
            if (mIpConfig.getIpAssignment() == IpAssignment.STATIC) {
                // Ignore NUD failures for static IP configurations, where restarting the IpClient
                // will not fix connectivity.
                // In this scenario, NetworkMonitor will not verify the network, so it will
                // eventually be torn down.
                return;
            }
            // Reachability lost will be seen only if the gateway is not reachable.
            // Since ethernet FW doesn't have the mechanism to scan for new networks
            // like WiFi, simply restart.
            // If there is a better network, that will become default and apps
            // will be able to use internet. If ethernet gets connected again,
            // and has backhaul connectivity, it will become default.
            maybeRestart();
        }

        /** Returns true if state has been modified */
        boolean updateLinkState(final boolean up) {
            if (mLinkUp == up)  {
                return false;
            }
            mLinkUp = up;

            if (!up) { // was up, goes down
                // retract network offer and stop IpClient.
                unregisterNetworkOfferAndStop();
            } else { // was down, goes up
                // register network offers
                maybeRegisterLocalNetworkOffer();
                maybeRegisterOrUpdateNetworkOffer();
            }

            return true;
        }

        /** Stops serving the network. Safe to call no matter the current state of the interface. */
        private void stop() {
            // Unregister NetworkAgent before stopping IpClient, so destroyNativeNetwork (which
            // deletes routes) hopefully happens before stop() finishes execution. Otherwise, it may
            // delete the new routes when IpClient gets restarted.
            if (mNetworkAgent != null) {
                mNetworkAgent.unregister();
                mNetworkAgent = null;
            }

            // Invalidate all previous start requests
            if (mIpClient != null) {
                mIpClient.shutdown();
                mIpClientCallback.awaitIpClientShutdown();
                mIpClient = null;
            }

            mNetworkAgentCallback = null;
            mIpClientCallback = null;
            mMode = Mode.NONE;

            mLinkProperties.clear();
        }

        /** Registers a local network offer iff this interface is an NCM interface. */
        private void maybeRegisterLocalNetworkOffer() {
            if (!mTrackingReason.contains(TrackingReason.NCM)) return;

            if (mLocalNetworkOfferCallback != null) {
                throw new IllegalStateException("Local network offer cannot be updated");
            }

            // Independent of the global offer's capabilities, NCM always uses a set of default
            // capabilities.
            // TODO: consider using the configured values if the NCM interface is also included
            // in the regex. In the future, this may make it possible to condense the two offers
            // to one. Currently, this cannot be done, because IpClient in its current state
            // needs to know whether to attempt global provisioning when it is started.
            mLocalNetworkOfferCallback = new LocalNetworkOfferCallback();
            mNetworkProvider.registerNetworkOffer(NETWORK_SCORE,
                    new NetworkCapabilities(LOCAL_NCM_CAPABILITIES), cmd -> mHandler.post(cmd),
                    mLocalNetworkOfferCallback);
        }

        /** Iff the regex includes this interface, registers or updates the global NetworkOffer. */
        private void maybeRegisterOrUpdateNetworkOffer() {
            // Only register "global" offer if the interface is in the regex.
            if (!mTrackingReason.contains(TrackingReason.REGEX)) return;

            // Calling registerNetworkOffer with a previously registered offer updates it.
            if (mNetworkOfferCallback == null) {
                mNetworkOfferCallback = new EthernetNetworkOfferCallback();
            }
            mNetworkProvider.registerNetworkOffer(NETWORK_SCORE,
                    new NetworkCapabilities(mCapabilities), cmd -> mHandler.post(cmd),
                    mNetworkOfferCallback);
        }

        private void unregisterNetworkOfferAndStop() {
            if (mNetworkOfferCallback != null) {
                mNetworkProvider.unregisterNetworkOffer(mNetworkOfferCallback);
                mNetworkOfferCallback = null;
            }

            if (mLocalNetworkOfferCallback != null) {
                mNetworkProvider.unregisterNetworkOffer(mLocalNetworkOfferCallback);
                mLocalNetworkOfferCallback = null;
            }

            stop();
            mRequestTracker.clear();
        }

        void maybeRestart() {
            // Only restart if the interface is currently running.
            if (mIpClient == null) return;
            if (DBG) Log.d(TAG, "Restart IpClient on: " + mPort);

            // Calling stop() resets the mode.
            final Mode previousMode = mMode;
            stop();
            // Do not change the current mode when restarting the interface.
            // mIpClient.startProvisioning() in start() will yield back to the handler, so even if
            // the network does not provide global connectivity, a request for local connectivity
            // will break the restart loop.
            start(previousMode);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{ "
                    + "port: " + mPort + ", "
                    + "up: " + mLinkUp + ", "
                    + "networkCapabilities: " + mCapabilities + ", "
                    + "networkAgent: " + mNetworkAgent + ", "
                    + "ipClient: " + mIpClient + ","
                    + "linkProperties: " + mLinkProperties
                    + "}";
        }
    }

    void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args) {
        pw.println(getClass().getSimpleName());
        pw.println("Tracking interfaces:");
        pw.increaseIndent();
        for (String iface: mTrackingInterfaces.keySet()) {
            NetworkInterfaceState ifaceState = mTrackingInterfaces.get(iface);
            pw.println(iface + ":" + ifaceState);
            pw.increaseIndent();
            if (null == ifaceState.mIpClient) {
                pw.println("IpClient is null");
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }
}
