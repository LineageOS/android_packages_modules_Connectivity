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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.EthernetNetworkSpecifier;
import android.net.IInternalNetworkManagementListener;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkProperties;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.ip.IIpClient;
import android.net.ip.IpClientCallbacks;
import android.net.ip.IpClientManager;
import android.net.ip.IpClientUtil;
import android.net.shared.ProvisioningConfiguration;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.net.module.util.InterfaceParams;

import java.io.FileDescriptor;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link NetworkFactory} that represents Ethernet networks.
 *
 * This class reports a static network score of 70 when it is tracking an interface and that
 * interface's link is up, and a score of 0 otherwise.
 */
public class EthernetNetworkFactory extends NetworkFactory {
    private final static String TAG = EthernetNetworkFactory.class.getSimpleName();
    final static boolean DBG = true;

    private final static int NETWORK_SCORE = 70;
    private static final String NETWORK_TYPE = "Ethernet";

    private final ConcurrentHashMap<String, NetworkInterfaceState> mTrackingInterfaces =
            new ConcurrentHashMap<>();
    private final Handler mHandler;
    private final Context mContext;
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
    }

    public static class ConfigurationException extends AndroidRuntimeException {
        public ConfigurationException(String msg) {
            super(msg);
        }
    }

    public EthernetNetworkFactory(Handler handler, Context context) {
        this(handler, context, new Dependencies());
    }

    @VisibleForTesting
    EthernetNetworkFactory(Handler handler, Context context, Dependencies deps) {
        super(handler.getLooper(), context, NETWORK_TYPE, createDefaultNetworkCapabilities());

        mHandler = handler;
        mContext = context;
        mDeps = deps;

        setScoreFilter(NETWORK_SCORE);
    }

    @Override
    public boolean acceptRequest(NetworkRequest request) {
        if (DBG) {
            Log.d(TAG, "acceptRequest, request: " + request);
        }

        return networkForRequest(request) != null;
    }

    @Override
    protected void needNetworkFor(NetworkRequest networkRequest) {
        NetworkInterfaceState network = networkForRequest(networkRequest);

        if (network == null) {
            Log.e(TAG, "needNetworkFor, failed to get a network for " + networkRequest);
            return;
        }

        if (++network.refCount == 1) {
            network.start();
        }
    }

    @Override
    protected void releaseNetworkFor(NetworkRequest networkRequest) {
        NetworkInterfaceState network = networkForRequest(networkRequest);
        if (network == null) {
            Log.e(TAG, "releaseNetworkFor, failed to get a network for " + networkRequest);
            return;
        }

        if (--network.refCount == 0) {
            network.stop();
        }
    }

    /**
     * Returns an array of available interface names. The array is sorted: unrestricted interfaces
     * goes first, then sorted by name.
     */
    String[] getAvailableInterfaces(boolean includeRestricted) {
        return mTrackingInterfaces.values()
                .stream()
                .filter(iface -> !iface.isRestricted() || includeRestricted)
                .sorted((iface1, iface2) -> {
                    int r = Boolean.compare(iface1.isRestricted(), iface2.isRestricted());
                    return r == 0 ? iface1.name.compareTo(iface2.name) : r;
                })
                .map(iface -> iface.name)
                .toArray(String[]::new);
    }

    void addInterface(@NonNull final String ifaceName, @NonNull final String hwAddress,
            @NonNull final IpConfiguration ipConfig,
            @NonNull final NetworkCapabilities capabilities) {
        if (mTrackingInterfaces.containsKey(ifaceName)) {
            Log.e(TAG, "Interface with name " + ifaceName + " already exists.");
            return;
        }

        if (DBG) {
            Log.d(TAG, "addInterface, iface: " + ifaceName + ", capabilities: " + capabilities);
        }

        final NetworkInterfaceState iface = new NetworkInterfaceState(
                ifaceName, hwAddress, mHandler, mContext, ipConfig, capabilities, this, mDeps);
        mTrackingInterfaces.put(ifaceName, iface);
        updateCapabilityFilter();
    }

    /**
     * Update a network's configuration and restart it if necessary.
     *
     * @param ifaceName the interface name of the network to be updated.
     * @param ipConfig the desired {@link IpConfiguration} for the given network.
     * @param capabilities the desired {@link NetworkCapabilities} for the given network. If
     *                     {@code null} is passed, then the network's current
     *                     {@link NetworkCapabilities} will be used in support of existing APIs as
     *                     the public API does not allow this.
     * @param listener an optional {@link IInternalNetworkManagementListener} to notify callers of
     *                 completion.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected void updateInterface(@NonNull final String ifaceName,
            @NonNull final IpConfiguration ipConfig,
            @Nullable final NetworkCapabilities capabilities,
            @Nullable final IInternalNetworkManagementListener listener) {
        enforceInterfaceIsTracked(ifaceName);
        final NetworkInterfaceState iface = mTrackingInterfaces.get(ifaceName);
        // TODO: The listener will have issues if called in quick succession for the same interface
        //  before the IP layer restarts. Update the listener logic to address multiple successive
        //  calls for a particular interface.
        iface.mNetworkManagementListener = listener;
        if (iface.updateInterface(ipConfig, capabilities)) {
            mTrackingInterfaces.put(ifaceName, iface);
            updateCapabilityFilter();
        }
    }

    private void enforceInterfaceIsTracked(@NonNull final String ifaceName) {
        if (!hasInterface(ifaceName)) {
            throw new UnsupportedOperationException(
                    "Interface with name " + ifaceName + " is not being tracked.");
        }
    }

    private static NetworkCapabilities mixInCapabilities(NetworkCapabilities nc,
            NetworkCapabilities addedNc) {
       final NetworkCapabilities.Builder builder = new NetworkCapabilities.Builder(nc);
       for (int transport : addedNc.getTransportTypes()) builder.addTransportType(transport);
       for (int capability : addedNc.getCapabilities()) builder.addCapability(capability);
       return builder.build();
    }

    private void updateCapabilityFilter() {
        NetworkCapabilities capabilitiesFilter = createDefaultNetworkCapabilities();
        for (NetworkInterfaceState iface:  mTrackingInterfaces.values()) {
            capabilitiesFilter = mixInCapabilities(capabilitiesFilter, iface.mCapabilities);
        }

        if (DBG) Log.d(TAG, "updateCapabilityFilter: " + capabilitiesFilter);
        setCapabilityFilter(capabilitiesFilter);
    }

    private static NetworkCapabilities createDefaultNetworkCapabilities() {
        return NetworkCapabilities.Builder
                .withoutDefaultCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build();
    }

    void removeInterface(String interfaceName) {
        NetworkInterfaceState iface = mTrackingInterfaces.remove(interfaceName);
        if (iface != null) {
            iface.stop();
        }

        updateCapabilityFilter();
    }

    /** Returns true if state has been modified */
    boolean updateInterfaceLinkState(String ifaceName, boolean up) {
        if (!mTrackingInterfaces.containsKey(ifaceName)) {
            return false;
        }

        if (DBG) {
            Log.d(TAG, "updateInterfaceLinkState, iface: " + ifaceName + ", up: " + up);
        }

        NetworkInterfaceState iface = mTrackingInterfaces.get(ifaceName);
        return iface.updateLinkState(up);
    }

    boolean hasInterface(String ifaceName) {
        return mTrackingInterfaces.containsKey(ifaceName);
    }

    private NetworkInterfaceState networkForRequest(NetworkRequest request) {
        String requestedIface = null;

        NetworkSpecifier specifier = request.getNetworkSpecifier();
        if (specifier instanceof EthernetNetworkSpecifier) {
            requestedIface = ((EthernetNetworkSpecifier) specifier)
                .getInterfaceName();
        }

        NetworkInterfaceState network = null;
        if (!TextUtils.isEmpty(requestedIface)) {
            NetworkInterfaceState n = mTrackingInterfaces.get(requestedIface);
            if (n != null && request.canBeSatisfiedBy(n.mCapabilities)) {
                network = n;
            }
        } else {
            for (NetworkInterfaceState n : mTrackingInterfaces.values()) {
                if (request.canBeSatisfiedBy(n.mCapabilities) && n.mLinkUp) {
                    network = n;
                    break;
                }
            }
        }

        if (DBG) {
            Log.i(TAG, "networkForRequest, request: " + request + ", network: " + network);
        }

        return network;
    }

    @VisibleForTesting
    static class NetworkInterfaceState {
        final String name;

        private final String mHwAddress;
        private final Handler mHandler;
        private final Context mContext;
        private final NetworkFactory mNetworkFactory;
        private final Dependencies mDeps;

        private static String sTcpBufferSizes = null;  // Lazy initialized.

        private boolean mLinkUp;
        private int mLegacyType;
        private LinkProperties mLinkProperties = new LinkProperties();

        private volatile @Nullable IpClientManager mIpClient;
        private @NonNull NetworkCapabilities mCapabilities;
        private @Nullable IpClientCallbacksImpl mIpClientCallback;
        private @Nullable IInternalNetworkManagementListener mNetworkManagementListener;
        private @Nullable EthernetNetworkAgent mNetworkAgent;
        private @Nullable IpConfiguration mIpConfig;

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

        long refCount = 0;

        private class IpClientCallbacksImpl extends IpClientCallbacks {
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

            @Override
            public void onProvisioningSuccess(LinkProperties newLp) {
                mHandler.post(() -> onIpLayerStarted(newLp));
            }

            @Override
            public void onProvisioningFailure(LinkProperties newLp) {
                mHandler.post(() -> onIpLayerStopped(newLp));
            }

            @Override
            public void onLinkPropertiesChange(LinkProperties newLp) {
                mHandler.post(() -> updateLinkProperties(newLp));
            }

            @Override
            public void onReachabilityLost(String logMsg) {
                mHandler.post(() -> updateNeighborLostEvent(logMsg));
            }

            @Override
            public void onQuit() {
                mIpClient = null;
                mIpClientShutdownCv.open();
            }
        }

        NetworkInterfaceState(String ifaceName, String hwAddress, Handler handler, Context context,
                @NonNull IpConfiguration ipConfig, @NonNull NetworkCapabilities capabilities,
                NetworkFactory networkFactory, Dependencies deps) {
            name = ifaceName;
            mIpConfig = Objects.requireNonNull(ipConfig);
            mCapabilities = Objects.requireNonNull(capabilities);
            mLegacyType = getLegacyType(mCapabilities);
            mHandler = handler;
            mContext = context;
            mNetworkFactory = networkFactory;
            mDeps = deps;
            mHwAddress = hwAddress;
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
        }

        boolean updateInterface(@NonNull final IpConfiguration ipConfig,
                @Nullable final NetworkCapabilities capabilities) {
            final boolean shouldUpdateIpConfig = !Objects.equals(mIpConfig, ipConfig);
            final boolean shouldUpdateCapabilities = null != capabilities
                    && !Objects.equals(mCapabilities, capabilities);
            if (DBG) {
                Log.d(TAG, "updateInterface, iface: " + name
                        + ", shouldUpdateIpConfig: " + shouldUpdateIpConfig
                        + ", shouldUpdateCapabilities: " + shouldUpdateCapabilities
                        + ", ipConfig: " + ipConfig + ", old ipConfig: " + mIpConfig
                        + ", capabilities: " + capabilities + ", old capabilities: " + mCapabilities
                );
            }

            if (shouldUpdateIpConfig) { mIpConfig = ipConfig; };
            if (shouldUpdateCapabilities) { setCapabilities(capabilities); };
            if (shouldUpdateIpConfig || shouldUpdateCapabilities) {
                // TODO: Update this logic to only do a restart if required. Although a restart may
                //  be required due to the capabilities or ipConfiguration values, not all
                //  capabilities changes require a restart.
                restart();
                return true;
            }
            return false;
        }

        boolean isRestricted() {
            return !mCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        }

        private void start() {
            if (mIpClient != null) {
                if (DBG) Log.d(TAG, "IpClient already started");
                return;
            }
            if (DBG) {
                Log.d(TAG, String.format("Starting Ethernet IpClient(%s)", name));
            }

            mIpClientCallback = new IpClientCallbacksImpl();
            mDeps.makeIpClient(mContext, name, mIpClientCallback);
            mIpClientCallback.awaitIpClientStart();
            if (sTcpBufferSizes == null) {
                sTcpBufferSizes = mContext.getResources().getString(
                        com.android.internal.R.string.config_ethernet_tcp_buffers);
            }
            provisionIpClient(mIpClient, mIpConfig, sTcpBufferSizes);
        }

        void onIpLayerStarted(LinkProperties linkProperties) {
            if (mNetworkAgent != null) {
                Log.e(TAG, "Already have a NetworkAgent - aborting new request");
                stop();
                return;
            }
            mLinkProperties = linkProperties;

            // Create our NetworkAgent.
            final NetworkAgentConfig config = new NetworkAgentConfig.Builder()
                    .setLegacyType(mLegacyType)
                    .setLegacyTypeName(NETWORK_TYPE)
                    .setLegacyExtraInfo(mHwAddress)
                    .build();
            mNetworkAgent = mDeps.makeEthernetNetworkAgent(mContext, mHandler.getLooper(),
                    mCapabilities, mLinkProperties, config, mNetworkFactory.getProvider(),
                    new EthernetNetworkAgent.Callbacks() {
                        @Override
                        public void onNetworkUnwanted() {
                            // if mNetworkAgent is null, we have already called stop.
                            if (mNetworkAgent == null) return;

                            if (this == mNetworkAgent.getCallbacks()) {
                                stop();
                            } else {
                                Log.d(TAG, "Ignoring unwanted as we have a more modern " +
                                        "instance");
                            }
                        }
                    });
            mNetworkAgent.register();
            mNetworkAgent.markConnected();
            sendNetworkManagementCallback();
        }

        private void sendNetworkManagementCallback() {
            if (null != mNetworkManagementListener) {
                try {
                    mNetworkManagementListener.onComplete(mNetworkAgent.getNetwork(), null);
                } catch (RemoteException e) {
                    Log.e(TAG, "Can't send onComplete for network management callback", e);
                } finally {
                    mNetworkManagementListener = null;
                }
            }
        }

        void onIpLayerStopped(LinkProperties linkProperties) {
            // This cannot happen due to provisioning timeout, because our timeout is 0. It can only
            // happen if we're provisioned and we lose provisioning.
            stop();
            // If the interface has disappeared provisioning will fail over and over again, so
            // there is no point in starting again
            if (null != mDeps.getNetworkInterfaceByName(name)) {
                start();
            }
        }

        void updateLinkProperties(LinkProperties linkProperties) {
            mLinkProperties = linkProperties;
            if (mNetworkAgent != null) {
                mNetworkAgent.sendLinkPropertiesImpl(linkProperties);
            }
        }

        void updateNeighborLostEvent(String logMsg) {
            Log.i(TAG, "updateNeighborLostEvent " + logMsg);
            // Reachability lost will be seen only if the gateway is not reachable.
            // Since ethernet FW doesn't have the mechanism to scan for new networks
            // like WiFi, simply restart.
            // If there is a better network, that will become default and apps
            // will be able to use internet. If ethernet gets connected again,
            // and has backhaul connectivity, it will become default.
            restart();
        }

        /** Returns true if state has been modified */
        boolean updateLinkState(boolean up) {
            if (mLinkUp == up) return false;
            mLinkUp = up;

            stop();
            if (up) {
                start();
            }

            return true;
        }

        void stop() {
            // Invalidate all previous start requests
            if (mIpClient != null) {
                mIpClient.shutdown();
                mIpClientCallback.awaitIpClientShutdown();
                mIpClient = null;
            }
            mIpClientCallback = null;

            if (mNetworkAgent != null) {
                mNetworkAgent.unregister();
                mNetworkAgent = null;
            }
            mLinkProperties.clear();
        }

        private static void provisionIpClient(@NonNull final IpClientManager ipClient,
                @NonNull final IpConfiguration config, @NonNull final String tcpBufferSizes) {
            if (config.getProxySettings() == ProxySettings.STATIC ||
                    config.getProxySettings() == ProxySettings.PAC) {
                ipClient.setHttpProxy(config.getHttpProxy());
            }

            if (!TextUtils.isEmpty(tcpBufferSizes)) {
                ipClient.setTcpBufferSizes(tcpBufferSizes);
            }

            ipClient.startProvisioning(createProvisioningConfiguration(config));
        }

        private static ProvisioningConfiguration createProvisioningConfiguration(
                @NonNull final IpConfiguration config) {
            if (config.getIpAssignment() == IpAssignment.STATIC) {
                return new ProvisioningConfiguration.Builder()
                        .withStaticConfiguration(config.getStaticIpConfiguration())
                        .build();
            }
            return new ProvisioningConfiguration.Builder()
                        .withProvisioningTimeoutMs(0)
                        .build();
        }

        void restart(){
            if (DBG) Log.d(TAG, "reconnecting Ethernet");
            stop();
            start();
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{ "
                    + "refCount: " + refCount + ", "
                    + "iface: " + name + ", "
                    + "up: " + mLinkUp + ", "
                    + "hwAddress: " + mHwAddress + ", "
                    + "networkCapabilities: " + mCapabilities + ", "
                    + "networkAgent: " + mNetworkAgent + ", "
                    + "ipClient: " + mIpClient + ","
                    + "linkProperties: " + mLinkProperties
                    + "}";
        }
    }

    void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
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
