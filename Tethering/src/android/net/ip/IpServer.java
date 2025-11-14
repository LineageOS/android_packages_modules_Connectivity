/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.ip;

import static android.net.INetd.LOCAL_NET_ID;
import static android.net.RouteInfo.RTN_UNICAST;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_NCM;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.TetheringManager.TETHERING_WIGIG;
import static android.net.TetheringManager.TETHER_ERROR_DHCPSERVER_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_ENABLE_FORWARDING_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_IFACE_CFG_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_INTERNAL_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_SERVICE_UNAVAIL;
import static android.net.TetheringManager.TETHER_ERROR_TETHER_IFACE_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_UNTETHER_IFACE_ERROR;
import static android.net.TetheringManager.TetheringRequest.checkStaticAddressConfiguration;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;
import static android.net.util.NetworkConstants.asByte;
import static android.system.OsConstants.RT_SCOPE_UNIVERSE;

import static com.android.net.module.util.Inet4AddressUtils.intToInet4AddressHTH;
import static com.android.net.module.util.NetworkStackConstants.RFC7421_PREFIX_LENGTH;
import static com.android.networkstack.tethering.TetheringConfiguration.USE_SYNC_SM;
import static com.android.networkstack.tethering.TetheringFeatureFlags.TETHERING_LOCAL_NETWORK_AGENT;
import static com.android.networkstack.tethering.TetheringFeatureFlags.WIFIP2PGO_LOCAL_NETWORK_AGENT;
import static com.android.networkstack.tethering.util.PrefixUtils.asIpPrefix;
import static com.android.networkstack.tethering.util.TetheringMessageBase.BASE_IPSERVER;
import static com.android.networkstack.tethering.util.TetheringUtils.getTransportTypeForTetherableType;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.INetd;
import android.net.INetworkStackStatusCallback;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.NetworkAgent;
import android.net.RouteInfo;
import android.net.TetheredClient;
import android.net.TetheringManager.TetheringRequest;
import android.net.connectivity.ConnectivityInternalApiUtil;
import android.net.dhcp.DhcpLeaseParcelable;
import android.net.dhcp.DhcpServerCallbacks;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.DhcpServingParamsParcelExt;
import android.net.dhcp.IDhcpEventCallbacks;
import android.net.dhcp.IDhcpServer;
import android.net.ip.RouterAdvertisementDaemon.RaParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.State;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.IIpv4PrefixRequest;
import com.android.net.module.util.InterfaceParams;
import com.android.net.module.util.NetdUtils;
import com.android.net.module.util.RoutingCoordinatorManager;
import com.android.net.module.util.SharedLog;
import com.android.net.module.util.SyncStateMachine.StateInfo;
import com.android.net.module.util.ip.InterfaceController;
import com.android.networkstack.tethering.BpfCoordinator;
import com.android.networkstack.tethering.TetheringConfiguration;
import com.android.networkstack.tethering.metrics.TetheringMetrics;
import com.android.networkstack.tethering.util.InterfaceSet;
import com.android.networkstack.tethering.util.PrefixUtils;
import com.android.networkstack.tethering.util.StateMachineShim;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Provides the interface to IP-layer serving functionality for a given network
 * interface, e.g. for tethering or "local-only hotspot" mode.
 *
 * @hide
 */
public class IpServer extends StateMachineShim {
    public static final int STATE_UNAVAILABLE = 0;
    public static final int STATE_AVAILABLE   = 1;
    public static final int STATE_TETHERED    = 2;
    public static final int STATE_LOCAL_ONLY  = 3;

    /** Get string name of |state|.*/
    public static String getStateString(int state) {
        switch (state) {
            case STATE_UNAVAILABLE: return "UNAVAILABLE";
            case STATE_AVAILABLE:   return "AVAILABLE";
            case STATE_TETHERED:    return "TETHERED";
            case STATE_LOCAL_ONLY:  return "LOCAL_ONLY";
        }
        return "UNKNOWN: " + state;
    }

    private static final byte DOUG_ADAMS = (byte) 42;

    // TODO: have PanService use some visible version of this constant
    private static final String BLUETOOTH_IFACE_ADDR = "192.168.44.1/24";

    private static final String LEGACY_WIFI_P2P_IFACE_ADDRESS = "192.168.49.1/24";

    // TODO: have this configurable
    private static final int DHCP_LEASE_TIME_SECS = 3600;

    private static final int NO_UPSTREAM = 0;
    private static final MacAddress NULL_MAC_ADDRESS = MacAddress.fromString("00:00:00:00:00:00");

    private static final String TAG = "IpServer";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VDBG = Log.isLoggable(TAG, Log.VERBOSE);
    private static final Class[] sMessageClasses = {
            IpServer.class
    };
    private static final SparseArray<String> sMagicDecoderRing =
            MessageUtils.findMessageNames(sMessageClasses);

    /** IpServer callback. */
    public static class Callback {
        /**
         * Notify that |who| has changed its tethering state.
         *
         * @param who the calling instance of IpServer
         * @param state one of STATE_*
         * @param lastError one of TetheringManager.TETHER_ERROR_*
         */
        public void updateInterfaceState(IpServer who, int state, int lastError) { }

        /**
         * Notify that |who| has new LinkProperties.
         *
         * @param who the calling instance of IpServer
         * @param newLp the new LinkProperties to report
         */
        public void updateLinkProperties(IpServer who, LinkProperties newLp) { }

        /**
         * Notify that the DHCP leases changed in one of the IpServers.
         */
        public void dhcpLeasesChanged() { }

        /**
         * Request Tethering change.
         *
         * @param tetheringType the downstream type of this IpServer.
         * @param enabled enable or disable tethering.
         */
        public void requestEnableTethering(int tetheringType, boolean enabled) { }
    }

    /** Capture IpServer dependencies, for injection. */
    public abstract static class Dependencies {
        /**
         * Create a DadProxy instance to be used by IpServer.
         * To support multiple tethered interfaces concurrently DAD Proxy
         * needs to be supported per IpServer instead of per upstream.
         */
        public DadProxy getDadProxy(Handler handler, InterfaceParams ifParams) {
            return new DadProxy(handler, ifParams);
        }

        /** Create a RouterAdvertisementDaemon instance to be used by IpServer.*/
        public RouterAdvertisementDaemon getRouterAdvertisementDaemon(InterfaceParams ifParams) {
            return new RouterAdvertisementDaemon(ifParams);
        }

        /** Get |ifName|'s interface information.*/
        public InterfaceParams getInterfaceParams(String ifName) {
            return InterfaceParams.getByName(ifName);
        }

        /** Create a DhcpServer instance to be used by IpServer. */
        public abstract void makeDhcpServer(String ifName, DhcpServingParamsParcel params,
                DhcpServerCallbacks cb);

        /**
         * @see DeviceConfigUtils#isTetheringFeatureEnabled
         */
        public boolean isFeatureEnabled(Context context, String name) {
            return DeviceConfigUtils.isTetheringFeatureEnabled(context, name);
        }

        /** Create a NetworkAgent instance to be used by IpServer. */
        @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        @SuppressLint("NewApi")
        public NetworkAgent makeNetworkAgent(
                @NonNull Context context, @NonNull Looper looper, @NonNull String logTag,
                int interfaceType, @NonNull LinkProperties lp) {
            return ConnectivityInternalApiUtil.buildTetheringNetworkAgent(
                    context, looper, logTag, getTransportTypeForTetherableType(interfaceType), lp);
        }
    }

    // request from the user that it wants to tether
    public static final int CMD_TETHER_REQUESTED            = BASE_IPSERVER + 1;
    // request from the user that it wants to untether
    public static final int CMD_TETHER_UNREQUESTED          = BASE_IPSERVER + 2;
    // notification that this interface is down
    public static final int CMD_INTERFACE_DOWN              = BASE_IPSERVER + 3;
    // notification from the {@link Tethering.TetherMainSM} that it had trouble enabling IP
    // Forwarding
    public static final int CMD_IP_FORWARDING_ENABLE_ERROR  = BASE_IPSERVER + 4;
    // notification from the {@link Tethering.TetherMainSM} SM that it had trouble disabling IP
    // Forwarding
    public static final int CMD_IP_FORWARDING_DISABLE_ERROR = BASE_IPSERVER + 5;
    // notification from the {@link Tethering.TetherMainSM} SM that it had trouble starting
    // tethering
    public static final int CMD_START_TETHERING_ERROR       = BASE_IPSERVER + 6;
    // notification from the {@link Tethering.TetherMainSM} that it had trouble stopping tethering
    public static final int CMD_STOP_TETHERING_ERROR        = BASE_IPSERVER + 7;
    // notification from the {@link Tethering.TetherMainSM} that it had trouble setting the DNS
    // forwarders
    public static final int CMD_SET_DNS_FORWARDERS_ERROR    = BASE_IPSERVER + 8;
    // the upstream connection has changed
    public static final int CMD_TETHER_CONNECTION_CHANGED   = BASE_IPSERVER + 9;
    // new IPv6 tethering parameters need to be processed
    public static final int CMD_IPV6_TETHER_UPDATE          = BASE_IPSERVER + 10;
    // request from DHCP server that it wants to have a new prefix
    public static final int CMD_NEW_PREFIX_REQUEST          = BASE_IPSERVER + 11;
    // request from PrivateAddressCoordinator to restart tethering.
    public static final int CMD_NOTIFY_PREFIX_CONFLICT      = BASE_IPSERVER + 12;
    public static final int CMD_SERVICE_FAILED_TO_START     = BASE_IPSERVER + 13;

    private final State mInitialState;
    private final State mLocalHotspotState;
    private final State mTetheredState;
    private final State mUnavailableState;
    private final State mWaitingForRestartState;

    private final SharedLog mLog;
    private final INetd mNetd;
    @NonNull
    private final BpfCoordinator mBpfCoordinator;
    @NonNull
    private final RoutingCoordinatorManager mRoutingCoordinator;
    @NonNull
    private final IIpv4PrefixRequest mIpv4PrefixRequest;
    private final Callback mCallback;
    private final InterfaceController mInterfaceCtrl;

    private final String mIfaceName;
    private final int mInterfaceType;
    private final LinkProperties mLinkProperties;
    private final boolean mUsingLegacyDhcp;
    private final int mP2pLeasesSubnetPrefixLength;
    private final boolean mIsWifiP2pDedicatedIpEnabled;

    private final Dependencies mDeps;

    private int mLastError;
    private int mServingMode;
    private InterfaceSet mUpstreamIfaceSet;  // may change over time
    // mInterfaceParams can't be final now because IpServer will be created when receives
    // WIFI_AP_STATE_CHANGED broadcasts or when it detects that the wifi interface has come up.
    // In the latter case, the interface is not fully initialized and the MAC address might not
    // be correct (it will be set with a randomized MAC address later).
    // TODO: Consider create the IpServer only when tethering want to enable it, then we can
    //       make mInterfaceParams final.
    private InterfaceParams mInterfaceParams;
    // TODO: De-duplicate this with mLinkProperties above. Currently, these link
    // properties are those selected by the IPv6TetheringCoordinator and relayed
    // to us. By comparison, mLinkProperties contains the addresses and directly
    // connected routes that have been formed from these properties iff. we have
    // succeeded in configuring them and are able to announce them within Router
    // Advertisements (otherwise, we do not add them to mLinkProperties at all).
    private LinkProperties mLastIPv6LinkProperties;
    private RouterAdvertisementDaemon mRaDaemon;
    private DadProxy mDadProxy;

    // To be accessed only on the handler thread
    private int mDhcpServerStartIndex = 0;
    private IDhcpServer mDhcpServer;
    private RaParams mLastRaParams;

    private LinkAddress mStaticIpv4ServerAddr;
    private LinkAddress mStaticIpv4ClientAddr;

    @NonNull
    private List<TetheredClient> mDhcpLeases = Collections.emptyList();

    private int mLastIPv6UpstreamIfindex = 0;
    @NonNull
    private Set<IpPrefix> mLastIPv6UpstreamPrefixes = Collections.emptySet();

    private LinkAddress mIpv4Address;

    @Nullable
    private TetheringRequest mTetheringRequest;

    private final TetheringMetrics mTetheringMetrics;
    private final Handler mHandler;
    private final Context mContext;

    private final boolean mSupportTetheringLocalAgent;
    private final boolean mSupportWifiP2pGroupOwnerLocalAgent;

    // This will be null if the TetheredState is not entered or feature not supported.
    // This will be only accessed from the IpServer handler thread.
    private NetworkAgent mTetheringAgent;

    private static boolean everRegistered(@NonNull NetworkAgent agent) {
        return agent.getNetwork() != null;
    }

    // TODO: Add a dependency object to pass the data members or variables from the tethering
    // object. It helps to reduce the arguments of the constructor.
    public IpServer(
            String ifaceName, @NonNull Context context, Handler handler, int interfaceType,
            SharedLog log, INetd netd, @NonNull BpfCoordinator bpfCoordinator,
            RoutingCoordinatorManager routingCoordinatorManager, Callback callback,
            TetheringConfiguration config,
            TetheringMetrics tetheringMetrics, Dependencies deps) {
        super(ifaceName, USE_SYNC_SM ? null : handler.getLooper());
        mContext = Objects.requireNonNull(context);
        mHandler = handler;
        mLog = log.forSubComponent(ifaceName);
        mNetd = netd;
        mBpfCoordinator = bpfCoordinator;
        mRoutingCoordinator = routingCoordinatorManager;
        mIpv4PrefixRequest = new IIpv4PrefixRequest.Stub() {
            @Override
            public void onIpv4PrefixConflict(IpPrefix ipPrefix) throws RemoteException {
                sendMessage(CMD_NOTIFY_PREFIX_CONFLICT);
            }
        };
        mCallback = callback;
        mInterfaceCtrl = new InterfaceController(ifaceName, mNetd, mLog);
        mIfaceName = ifaceName;
        mInterfaceType = interfaceType;
        mLinkProperties = new LinkProperties();
        mUsingLegacyDhcp = config.useLegacyDhcpServer();
        mP2pLeasesSubnetPrefixLength = config.getP2pLeasesSubnetPrefixLength();
        mIsWifiP2pDedicatedIpEnabled = config.shouldEnableWifiP2pDedicatedIp();
        mDeps = deps;
        mTetheringMetrics = tetheringMetrics;
        resetLinkProperties();
        mLastError = TETHER_ERROR_NO_ERROR;
        mServingMode = STATE_AVAILABLE;

        // Tethering network agent is supported on V+, and will be rolled out gradually.
        mSupportTetheringLocalAgent = SdkLevel.isAtLeastV()
                && mDeps.isFeatureEnabled(mContext, TETHERING_LOCAL_NETWORK_AGENT);
        mSupportWifiP2pGroupOwnerLocalAgent = mSupportTetheringLocalAgent
                && mDeps.isFeatureEnabled(mContext, WIFIP2PGO_LOCAL_NETWORK_AGENT);

        mInitialState = new InitialState();
        mLocalHotspotState = new LocalHotspotState();
        mTetheredState = new TetheredState();
        mUnavailableState = new UnavailableState();
        mWaitingForRestartState = new WaitingForRestartState();
        final ArrayList allStates = new ArrayList<StateInfo>();
        allStates.add(new StateInfo(mInitialState, null));
        allStates.add(new StateInfo(mLocalHotspotState, null));
        allStates.add(new StateInfo(mTetheredState, null));
        allStates.add(new StateInfo(mWaitingForRestartState, mTetheredState));
        allStates.add(new StateInfo(mUnavailableState, null));
        addAllStates(allStates);
    }

    private Handler getHandler() {
        return mHandler;
    }

    /** Start IpServer state machine. */
    public void start() {
        start(mInitialState);
    }

    /** Interface name which IpServer served.*/
    public String interfaceName() {
        return mIfaceName;
    }

    /**
     * Tethering downstream type. It would be one of TetheringManager#TETHERING_*.
     */
    public int interfaceType() {
        return mInterfaceType;
    }

    /** Last error from this IpServer. */
    public int lastError() {
        return mLastError;
    }

    /** Serving mode is the current state of IpServer state machine. */
    public int servingMode() {
        return mServingMode;
    }

    /** The properties of the network link which IpServer is serving. */
    public LinkProperties linkProperties() {
        return new LinkProperties(mLinkProperties);
    }

    /** The address which IpServer is using. */
    public LinkAddress getAddress() {
        return mIpv4Address;
    }

    /** The IPv6 upstream interface index */
    public int getIpv6UpstreamIfindex() {
        return mLastIPv6UpstreamIfindex;
    }

    /** The IPv6 upstream interface prefixes */
    @NonNull
    public Set<IpPrefix> getIpv6UpstreamPrefixes() {
        return Collections.unmodifiableSet(mLastIPv6UpstreamPrefixes);
    }

    /** The interface parameters which IpServer is using */
    public InterfaceParams getInterfaceParams() {
        return mInterfaceParams;
    }

    @VisibleForTesting
    public IIpv4PrefixRequest getIpv4PrefixRequest() {
        return mIpv4PrefixRequest;
    }

    /**
     * Get the latest list of DHCP leases that was reported. Must be called on the IpServer looper
     * thread.
     */
    public List<TetheredClient> getAllLeases() {
        return Collections.unmodifiableList(mDhcpLeases);
    }

    /**
     * Enable this IpServer. IpServer state machine will be tethered or localHotspot state based on
     * the connectivity scope of the TetheringRequest. */
    public void enable(@NonNull final TetheringRequest request) {
        sendMessage(CMD_TETHER_REQUESTED, 0, 0, request);
    }

    /** Stop this IpServer. After this is called this IpServer should not be used any more. */
    public void stop() {
        sendMessage(CMD_INTERFACE_DOWN);
    }

    /**
     * Tethering is canceled. IpServer state machine will be available and wait for
     * next tethering request.
     */
    public void unwanted() {
        sendMessage(CMD_TETHER_UNREQUESTED);
    }

    /** Internals. */

    private boolean startIPv4(int scope) {
        return configureIPv4(true, scope);
    }

    /**
     * Convenience wrapper around INetworkStackStatusCallback to run callbacks on the IpServer
     * handler.
     *
     * <p>Different instances of this class can be created for each call to IDhcpServer methods,
     * with different implementations of the callback, to differentiate handling of success/error in
     * each call.
     */
    private abstract class OnHandlerStatusCallback extends INetworkStackStatusCallback.Stub {
        @Override
        public void onStatusAvailable(int statusCode) {
            getHandler().post(() -> callback(statusCode));
        }

        public abstract void callback(int statusCode);

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }
    }

    private class DhcpServerCallbacksImpl extends DhcpServerCallbacks {
        private final int mStartIndex;

        private DhcpServerCallbacksImpl(int startIndex) {
            mStartIndex = startIndex;
        }

        @Override
        public void onDhcpServerCreated(int statusCode, IDhcpServer server) throws RemoteException {
            getHandler().post(() -> {
                // We are on the handler thread: mDhcpServerStartIndex can be read safely.
                if (mStartIndex != mDhcpServerStartIndex) {
                     // This start request is obsolete. Explicitly stop the DHCP server to shut
                     // down its thread. When the |server| binder token goes out of scope, the
                     // garbage collector will finalize it, which causes the network stack process
                     // garbage collector to collect the server itself.
                    try {
                        server.stop(null);
                    } catch (RemoteException e) { }
                    return;
                }

                if (statusCode != STATUS_SUCCESS) {
                    mLog.e("Error obtaining DHCP server: " + statusCode);
                    handleError();
                    return;
                }

                mDhcpServer = server;
                try {
                    mDhcpServer.startWithCallbacks(new OnHandlerStatusCallback() {
                        @Override
                        public void callback(int startStatusCode) {
                            if (startStatusCode != STATUS_SUCCESS) {
                                mLog.e("Error starting DHCP server: " + startStatusCode);
                                handleError();
                            }
                        }
                    }, new DhcpEventCallback());
                } catch (RemoteException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        private void handleError() {
            mLastError = TETHER_ERROR_DHCPSERVER_ERROR;
            if (USE_SYNC_SM) {
                sendMessage(CMD_SERVICE_FAILED_TO_START, TETHER_ERROR_DHCPSERVER_ERROR);
            } else {
                sendMessageAtFrontOfQueueToAsyncSM(CMD_SERVICE_FAILED_TO_START,
                        TETHER_ERROR_DHCPSERVER_ERROR);
            }
        }
    }

    private class DhcpEventCallback extends IDhcpEventCallbacks.Stub {
        @Override
        public void onLeasesChanged(List<DhcpLeaseParcelable> leaseParcelables) {
            final ArrayList<TetheredClient> leases = new ArrayList<>();
            for (DhcpLeaseParcelable lease : leaseParcelables) {
                final LinkAddress address = new LinkAddress(
                        intToInet4AddressHTH(lease.netAddr), lease.prefixLength,
                        0 /* flags */, RT_SCOPE_UNIVERSE /* as per RFC6724#3.2 */,
                        lease.expTime /* deprecationTime */, lease.expTime /* expirationTime */);

                final MacAddress macAddress;
                try {
                    macAddress = MacAddress.fromBytes(lease.hwAddr);
                } catch (IllegalArgumentException e) {
                    Log.wtf(TAG, "Invalid address received from DhcpServer: "
                            + Arrays.toString(lease.hwAddr));
                    return;
                }

                final TetheredClient.AddressInfo addressInfo = new TetheredClient.AddressInfo(
                        address, lease.hostname);
                leases.add(new TetheredClient(
                        macAddress,
                        Collections.singletonList(addressInfo),
                        mInterfaceType));
            }

            getHandler().post(() -> {
                mDhcpLeases = leases;
                mCallback.dhcpLeasesChanged();
            });
        }

        @Override
        public void onNewPrefixRequest(@NonNull final IpPrefix currentPrefix) {
            Objects.requireNonNull(currentPrefix);
            sendMessage(CMD_NEW_PREFIX_REQUEST, currentPrefix);
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() throws RemoteException {
            return this.HASH;
        }
    }

    private RouteInfo getDirectConnectedRoute(@NonNull final LinkAddress ipv4Address) {
        Objects.requireNonNull(ipv4Address);
        return new RouteInfo(PrefixUtils.asIpPrefix(ipv4Address), null, mIfaceName, RTN_UNICAST);
    }

    private DhcpServingParamsParcel makeServingParams(@NonNull final Inet4Address defaultRouter,
            @NonNull final Inet4Address dnsServer, @NonNull LinkAddress serverAddr,
            @Nullable Inet4Address clientAddr) {
        final boolean changePrefixOnDecline =
                (mInterfaceType == TETHERING_NCM && clientAddr == null);
        final int subnetPrefixLength = mInterfaceType == TETHERING_WIFI_P2P
                ? mP2pLeasesSubnetPrefixLength : 0 /* default value */;

        return new DhcpServingParamsParcelExt()
            .setDefaultRouters(defaultRouter)
            .setDhcpLeaseTimeSecs(DHCP_LEASE_TIME_SECS)
            .setDnsServers(dnsServer)
            .setServerAddr(serverAddr)
            .setMetered(true)
            .setSingleClientAddr(clientAddr)
            .setChangePrefixOnDecline(changePrefixOnDecline)
            .setLeasesSubnetPrefixLength(subnetPrefixLength);
            // TODO: also advertise link MTU
    }

    private boolean startDhcp(final LinkAddress serverLinkAddr, final LinkAddress clientLinkAddr) {
        if (mUsingLegacyDhcp) {
            return true;
        }

        final Inet4Address addr = (Inet4Address) serverLinkAddr.getAddress();
        final Inet4Address clientAddr = clientLinkAddr == null ? null :
                (Inet4Address) clientLinkAddr.getAddress();

        final DhcpServingParamsParcel params = makeServingParams(addr /* defaultRouter */,
                addr /* dnsServer */, serverLinkAddr, clientAddr);
        mDhcpServerStartIndex++;
        mDeps.makeDhcpServer(
                mIfaceName, params, new DhcpServerCallbacksImpl(mDhcpServerStartIndex));
        return true;
    }

    private void stopDhcp() {
        // Make all previous start requests obsolete so servers are not started later
        mDhcpServerStartIndex++;

        if (mDhcpServer != null) {
            try {
                mDhcpServer.stop(new OnHandlerStatusCallback() {
                    @Override
                    public void callback(int statusCode) {
                        if (statusCode != STATUS_SUCCESS) {
                            mLog.e("Error stopping DHCP server: " + statusCode);
                            mLastError = TETHER_ERROR_DHCPSERVER_ERROR;
                            // Not much more we can do here
                        }
                        mDhcpLeases.clear();
                        getHandler().post(mCallback::dhcpLeasesChanged);
                    }
                });
                mDhcpServer = null;
            } catch (RemoteException e) {
                mLog.e("Error stopping DHCP server", e);
                // Not much more we can do here
            }
        }
    }

    private boolean configureDhcp(boolean enable, final LinkAddress serverAddr,
            final LinkAddress clientAddr) {
        if (enable) {
            return startDhcp(serverAddr, clientAddr);
        } else {
            stopDhcp();
            return true;
        }
    }

    private void stopIPv4() {
        configureIPv4(false /* enabled */, CONNECTIVITY_SCOPE_GLOBAL /* not used */);
        // NOTE: All of configureIPv4() will be refactored out of existence
        // into calls to InterfaceController, shared with startIPv4().
        mInterfaceCtrl.clearIPv4Address();
        mRoutingCoordinator.releaseDownstream(mIpv4PrefixRequest);
        mBpfCoordinator.tetherOffloadClientClear(this);
        mIpv4Address = null;
        mStaticIpv4ServerAddr = null;
        mStaticIpv4ClientAddr = null;
    }

    private boolean configureIPv4(boolean enabled, int scope) {
        if (VDBG) Log.d(TAG, "configureIPv4(" + enabled + ")");

        if (enabled) {
            mIpv4Address = requestIpv4Address(scope, true /* useLastAddress */);
        }

        if (mIpv4Address == null) {
            mLog.e("No available ipv4 address");
            return false;
        }

        if (shouldNotConfigureBluetoothInterface()) {
            // Interface was already configured elsewhere, only start DHCP.
            return configureDhcp(enabled, mIpv4Address, null /* clientAddress */);
        }

        final IpPrefix ipv4Prefix = asIpPrefix(mIpv4Address);

        final Boolean setIfaceUp;
        if (mInterfaceType == TETHERING_WIFI
                || mInterfaceType == TETHERING_WIFI_P2P
                || mInterfaceType == TETHERING_ETHERNET
                || mInterfaceType == TETHERING_WIGIG) {
            // The WiFi and Ethernet stack has ownership of the interface up/down state.
            // It is unclear whether the Bluetooth or USB stacks will manage their own
            // state.
            setIfaceUp = null;
        } else {
            setIfaceUp = enabled;
        }
        if (!mInterfaceCtrl.setInterfaceConfiguration(mIpv4Address, setIfaceUp)) {
            mLog.e("Error configuring interface");
            if (!enabled) stopDhcp();
            return false;
        }

        if (enabled) {
            mLinkProperties.addLinkAddress(mIpv4Address);
            mLinkProperties.addRoute(getDirectConnectedRoute(mIpv4Address));
        } else {
            mLinkProperties.removeLinkAddress(mIpv4Address);
            mLinkProperties.removeRoute(getDirectConnectedRoute(mIpv4Address));
        }
        return configureDhcp(enabled, mIpv4Address, mStaticIpv4ClientAddr);
    }

    private boolean shouldNotConfigureBluetoothInterface() {
        // Before T, bluetooth tethering configures the interface elsewhere.
        return (mInterfaceType == TETHERING_BLUETOOTH) && !SdkLevel.isAtLeastT();
    }

    private boolean shouldUseWifiP2pDedicatedIp() {
        return mIsWifiP2pDedicatedIpEnabled
                && mInterfaceType == TETHERING_WIFI_P2P;
    }

    private LinkAddress requestIpv4Address(final int scope, final boolean useLastAddress) {
        if (mStaticIpv4ServerAddr != null) return mStaticIpv4ServerAddr;

        if (shouldNotConfigureBluetoothInterface()) return new LinkAddress(BLUETOOTH_IFACE_ADDR);

        if (shouldUseWifiP2pDedicatedIp()) return new LinkAddress(LEGACY_WIFI_P2P_IFACE_ADDRESS);

        if (useLastAddress) {
            return mRoutingCoordinator.requestStickyDownstreamAddress(mInterfaceType, scope,
                    mIpv4PrefixRequest);
        }

        return mRoutingCoordinator.requestDownstreamAddress(mIpv4PrefixRequest);
    }

    private boolean startIPv6() {
        mInterfaceParams = mDeps.getInterfaceParams(mIfaceName);
        if (mInterfaceParams == null) {
            mLog.e("Failed to find InterfaceParams");
            stopIPv6();
            return false;
        }

        mRaDaemon = mDeps.getRouterAdvertisementDaemon(mInterfaceParams);
        if (!mRaDaemon.start()) {
            stopIPv6();
            return false;
        }

        if (SdkLevel.isAtLeastS()) {
            // DAD Proxy starts forwarding packets after IPv6 upstream is present.
            mDadProxy = mDeps.getDadProxy(getHandler(), mInterfaceParams);
        }

        return true;
    }

    private void stopIPv6() {
        mInterfaceParams = null;
        setRaParams(null);

        if (mRaDaemon != null) {
            mRaDaemon.stop();
            mRaDaemon = null;
        }

        if (mDadProxy != null) {
            mDadProxy.stop();
            mDadProxy = null;
        }
    }

    // IPv6TetheringCoordinator sends updates with carefully curated IPv6-only
    // LinkProperties. These have extraneous data filtered out and only the
    // necessary prefixes included (per its prefix distribution policy).
    //
    // TODO: Evaluate using a data structure than is more directly suited to
    // communicating only the relevant information.
    @SuppressLint("NewApi")
    private void updateUpstreamIPv6LinkProperties(LinkProperties v6only, int ttlAdjustment) {
        if (mRaDaemon == null) return;

        // Avoid unnecessary work on spurious updates.
        if (Objects.equals(mLastIPv6LinkProperties, v6only)) {
            return;
        }

        RaParams params = null;
        String upstreamIface = null;
        InterfaceParams upstreamIfaceParams = null;
        int upstreamIfIndex = NO_UPSTREAM;

        if (v6only != null) {
            upstreamIface = v6only.getInterfaceName();
            upstreamIfaceParams = mDeps.getInterfaceParams(upstreamIface);
            if (upstreamIfaceParams != null) {
                upstreamIfIndex = upstreamIfaceParams.index;
            }
            params = new RaParams();
            params.mtu = v6only.getMtu();
            params.hasDefaultRoute = v6only.hasIpv6DefaultRoute();

            if (params.hasDefaultRoute) params.hopLimit = getHopLimit(upstreamIface, ttlAdjustment);

            params.prefixes = getTetherableIpv6Prefixes(v6only);
            for (IpPrefix prefix : params.prefixes) {
                final Inet6Address dnsServer = getLocalDnsIpFor(prefix);
                if (dnsServer != null) {
                    params.dnses.add(dnsServer);
                }
            }
        }

        // Add upstream index to name mapping. See the comment of the interface mapping update in
        // CMD_TETHER_CONNECTION_CHANGED. Adding the mapping update here to the avoid potential
        // timing issue. It prevents that the IPv6 capability is updated later than
        // CMD_TETHER_CONNECTION_CHANGED.
        mBpfCoordinator.maybeAddUpstreamToLookupTable(upstreamIfIndex, upstreamIface);

        // If v6only is null, we pass in null to setRaParams(), which handles
        // deprecation of any existing RA data.
        setRaParams(params);

        // Not support BPF on virtual upstream interface
        final Set<IpPrefix> upstreamPrefixes = params != null ? params.prefixes : Set.of();
        // mBpfCoordinator#updateIpv6UpstreamInterface must be called before updating
        // mLastIPv6UpstreamIfindex and mLastIPv6UpstreamPrefixes because BpfCoordinator will call
        // IpServer#getIpv6UpstreamIfindex and IpServer#getIpv6UpstreamPrefixes to retrieve current
        // upstream interface index and prefixes when handling upstream changes.
        mBpfCoordinator.updateIpv6UpstreamInterface(this, upstreamIfIndex, upstreamPrefixes);
        mLastIPv6LinkProperties = v6only;
        mLastIPv6UpstreamIfindex = upstreamIfIndex;
        mLastIPv6UpstreamPrefixes = upstreamPrefixes;
        if (mDadProxy != null) {
            mDadProxy.setUpstreamIface(upstreamIfaceParams);
        }
    }

    private void removeRoutesFromNetwork(int netId, @NonNull final List<RouteInfo> toBeRemoved) {
        final int removalFailures = NetdUtils.removeRoutesFromNetwork(mNetd, netId, toBeRemoved);
        if (removalFailures > 0) {
            mLog.e("Failed to remove " + removalFailures
                    + " IPv6 routes from network " + netId + ".");
        }
    }

    private void addInterfaceToNetwork(final int netId, @NonNull final String ifaceName) {
        try {
            // TODO : remove this call in favor of using the LocalNetworkConfiguration
            // correctly, which will let ConnectivityService do it automatically.
            mRoutingCoordinator.addInterfaceToNetwork(netId, ifaceName);
        } catch (ServiceSpecificException e) {
            mLog.e("Failed to add " + mIfaceName + " to local table: ", e);
        }
    }

    private void addInterfaceForward(@NonNull final String fromIface, @NonNull final String toIface)
            throws ServiceSpecificException {
        mRoutingCoordinator.addInterfaceForward(fromIface, toIface);
    }

    private void removeInterfaceForward(@NonNull final String fromIface,
            @NonNull final String toIface) {
        try {
            mRoutingCoordinator.removeInterfaceForward(fromIface, toIface);
        } catch (RuntimeException e) {
            mLog.e("Exception in removeInterfaceForward", e);
        }
    }

    private void addRoutesToNetwork(int netId,
            @NonNull final List<RouteInfo> toBeAdded) {
        // It's safe to call addInterfaceToNetwork() even if
        // the interface is already in the network.
        addInterfaceToNetwork(netId, mIfaceName);
        try {
            // Add routes from local network. Note that adding routes that
            // already exist does not cause an error (EEXIST is silently ignored).
            NetdUtils.addRoutesToNetwork(mNetd, netId, mIfaceName, toBeAdded);
        } catch (IllegalStateException e) {
            mLog.e("Failed to add IPv4/v6 routes to local table: " + e);
            return;
        }
    }

    private void configureLocalIPv6Routes(
            ArraySet<IpPrefix> deprecatedPrefixes, ArraySet<IpPrefix> newPrefixes) {
        // [1] Remove the routes that are deprecated.
        if (!deprecatedPrefixes.isEmpty()) {
            final List<RouteInfo> routesToBeRemoved =
                    getLocalRoutesFor(mIfaceName, deprecatedPrefixes);
            if (mTetheringAgent == null) {
                removeRoutesFromNetwork(LOCAL_NET_ID, routesToBeRemoved);
            }
            for (RouteInfo route : routesToBeRemoved) mLinkProperties.removeRoute(route);
        }

        // [2] Add only the routes that have not previously been added.
        if (newPrefixes != null && !newPrefixes.isEmpty()) {
            ArraySet<IpPrefix> addedPrefixes = new ArraySet<IpPrefix>(newPrefixes);
            if (mLastRaParams != null) {
                addedPrefixes.removeAll(mLastRaParams.prefixes);
            }

            if (!addedPrefixes.isEmpty()) {
                final List<RouteInfo> routesToBeAdded =
                        getLocalRoutesFor(mIfaceName, addedPrefixes);
                if (mTetheringAgent == null) {
                    addRoutesToNetwork(LOCAL_NET_ID, routesToBeAdded);
                }
                for (RouteInfo route : routesToBeAdded) mLinkProperties.addRoute(route);
            }
        }
    }

    private void configureLocalIPv6Dns(
            ArraySet<Inet6Address> deprecatedDnses, ArraySet<Inet6Address> newDnses) {
        // TODO: Is this really necessary? Can we not fail earlier if INetd cannot be located?
        if (mNetd == null) {
            if (newDnses != null) newDnses.clear();
            mLog.e("No netd service instance available; not setting local IPv6 addresses");
            return;
        }

        // [1] Remove deprecated local DNS IP addresses.
        if (!deprecatedDnses.isEmpty()) {
            for (Inet6Address dns : deprecatedDnses) {
                if (!mInterfaceCtrl.removeAddress(dns, RFC7421_PREFIX_LENGTH)) {
                    mLog.e("Failed to remove local dns IP " + dns);
                }

                mLinkProperties.removeLinkAddress(new LinkAddress(dns, RFC7421_PREFIX_LENGTH));
            }
        }

        // [2] Add only the local DNS IP addresses that have not previously been added.
        if (newDnses != null && !newDnses.isEmpty()) {
            final ArraySet<Inet6Address> addedDnses = new ArraySet<Inet6Address>(newDnses);
            if (mLastRaParams != null) {
                addedDnses.removeAll(mLastRaParams.dnses);
            }

            for (Inet6Address dns : addedDnses) {
                if (!mInterfaceCtrl.addAddress(dns, RFC7421_PREFIX_LENGTH)) {
                    mLog.e("Failed to add local dns IP " + dns);
                    newDnses.remove(dns);
                }

                mLinkProperties.addLinkAddress(new LinkAddress(dns, RFC7421_PREFIX_LENGTH));
            }
        }

        try {
            mNetd.tetherApplyDnsInterfaces();
        } catch (ServiceSpecificException | RemoteException e) {
            mLog.e("Failed to update local DNS caching server");
            if (newDnses != null) newDnses.clear();
        }
    }

    private byte getHopLimit(String upstreamIface, int adjustTTL) {
        try {
            int upstreamHopLimit = Integer.parseUnsignedInt(
                    mNetd.getProcSysNet(INetd.IPV6, INetd.CONF, upstreamIface, "hop_limit"));
            upstreamHopLimit = upstreamHopLimit + adjustTTL;
            // Cap the hop limit to 255.
            return (byte) Integer.min(upstreamHopLimit, 255);
        } catch (Exception e) {
            mLog.e("Failed to find upstream interface hop limit", e);
        }
        return RaParams.DEFAULT_HOPLIMIT;
    }

    private void setRaParams(RaParams newParams) {
        if (mRaDaemon != null) {
            final RaParams deprecatedParams =
                    RaParams.getDeprecatedRaParams(mLastRaParams, newParams);

            configureLocalIPv6Routes(deprecatedParams.prefixes,
                    (newParams != null) ? newParams.prefixes : null);

            configureLocalIPv6Dns(deprecatedParams.dnses,
                    (newParams != null) ? newParams.dnses : null);

            mRaDaemon.buildNewRa(deprecatedParams, newParams);
        }

        mLastRaParams = newParams;
    }

    private void maybeLogMessage(State state, int what) {
        switch (what) {
            // Suppress some CMD_* to avoid log flooding.
            case CMD_IPV6_TETHER_UPDATE:
                break;
            default:
                mLog.log(state.getName() + " got "
                        + sMagicDecoderRing.get(what, Integer.toString(what)));
        }
    }

    private void sendInterfaceState(int newInterfaceState) {
        mServingMode = newInterfaceState;
        mCallback.updateInterfaceState(this, newInterfaceState, mLastError);
        sendLinkProperties();
    }

    private void sendLinkProperties() {
        mCallback.updateLinkProperties(this, new LinkProperties(mLinkProperties));
    }

    private void resetLinkProperties() {
        mLinkProperties.clear();
        mLinkProperties.setInterfaceName(mIfaceName);
    }

    private void maybeConfigureStaticIp(@NonNull final TetheringRequest request) {
        // Ignore static address configuration if they are invalid or null. In theory, static
        // addresses should not be invalid here because TetheringManager do not allow caller to
        // specify invalid static address configuration.
        if (request.getLocalIpv4Address() == null
                || request.getClientStaticIpv4Address() == null || !checkStaticAddressConfiguration(
                request.getLocalIpv4Address(), request.getClientStaticIpv4Address())) {
            return;
        }

        mStaticIpv4ServerAddr = request.getLocalIpv4Address();
        mStaticIpv4ClientAddr = request.getClientStaticIpv4Address();
    }

    class InitialState extends State {
        @Override
        public void enter() {
            sendInterfaceState(STATE_AVAILABLE);
        }

        @Override
        public boolean processMessage(Message message) {
            maybeLogMessage(this, message.what);
            switch (message.what) {
                case CMD_TETHER_REQUESTED:
                    mLastError = TETHER_ERROR_NO_ERROR;
                    mTetheringRequest = (TetheringRequest) message.obj;
                    switch (mTetheringRequest.getConnectivityScope()) {
                        case CONNECTIVITY_SCOPE_LOCAL:
                            maybeConfigureStaticIp(mTetheringRequest);
                            transitionTo(mLocalHotspotState);
                            break;
                        case CONNECTIVITY_SCOPE_GLOBAL:
                            maybeConfigureStaticIp(mTetheringRequest);
                            transitionTo(mTetheredState);
                            break;
                        default:
                            mLog.e("Invalid tethering interface serving state specified.");
                    }
                    break;
                case CMD_INTERFACE_DOWN:
                    transitionTo(mUnavailableState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    abstract class BaseServingState extends State {
        private final int mDesiredInterfaceState;

        BaseServingState(int interfaceState) {
            mDesiredInterfaceState = interfaceState;
        }

        @Override
        public void enter() {
            mBpfCoordinator.addIpServer(IpServer.this);

            startServingInterface();

            if (mLastError != TETHER_ERROR_NO_ERROR) {
                // This will transition to InitialState right away, regardless of whether any
                // message is already waiting in the StateMachine queue (including maybe some
                // message to go to InitialState). InitialState will then process any pending
                // message (and generally ignores them). It is difficult to know for sure whether
                // this is correct in all cases, but this is equivalent to what IpServer was doing
                // in previous versions of the mainline module.
                // TODO : remove sendMessageAtFrontOfQueueToAsyncSM after migrating to the Sync
                // StateMachine.
                if (USE_SYNC_SM) {
                    sendSelfMessageToSyncSM(CMD_SERVICE_FAILED_TO_START, mLastError);
                } else {
                    sendMessageAtFrontOfQueueToAsyncSM(CMD_SERVICE_FAILED_TO_START, mLastError);
                }
            }

            if (DBG) Log.d(TAG, getStateString(mDesiredInterfaceState) + " serve " + mIfaceName);
            sendInterfaceState(mDesiredInterfaceState);
        }

        private int getScope() {
            if (mDesiredInterfaceState == STATE_TETHERED) {
                return CONNECTIVITY_SCOPE_GLOBAL;
            }

            return CONNECTIVITY_SCOPE_LOCAL;
        }

        @SuppressLint("NewApi")
        private void startServingInterface() {
            if (mSupportTetheringLocalAgent && (mSupportWifiP2pGroupOwnerLocalAgent
                    || getScope() != CONNECTIVITY_SCOPE_LOCAL)) {
                try {
                    mTetheringAgent = mDeps.makeNetworkAgent(mContext, Looper.myLooper(), TAG,
                            mInterfaceType, mLinkProperties);
                    // Entering CONNECTING state, the ConnectivityService will create the
                    // native network.
                    mTetheringAgent.register();
                } catch (RuntimeException e) {
                    mLog.e("Error Creating Local Network", e);
                    // If an exception occurs during the creation or registration of the
                    // NetworkAgent, it typically indicates a problem with the system services.
                    mLastError = TETHER_ERROR_SERVICE_UNAVAIL;
                    return;
                }
            }

            if (!startIPv4(getScope())) {
                mLastError = TETHER_ERROR_IFACE_CFG_ERROR;
                return;
            }

            try {
                // Enable IPv6, disable accepting RA, etc. See TetherController::tetherInterface()
                // for more detail.
                mNetd.tetherInterfaceAdd(mIfaceName);
                if (mTetheringAgent == null) {
                    NetdUtils.networkAddInterface(mNetd, LOCAL_NET_ID, mIfaceName,
                            20 /* maxAttempts */, 50 /* pollingIntervalMs */);
                    // Activate a route to dest and IPv6 link local.
                    NetdUtils.modifyRoute(mNetd, NetdUtils.ModifyOperation.ADD, LOCAL_NET_ID,
                            new RouteInfo(asIpPrefix(mIpv4Address), null, mIfaceName, RTN_UNICAST));
                    NetdUtils.modifyRoute(mNetd, NetdUtils.ModifyOperation.ADD, LOCAL_NET_ID,
                            new RouteInfo(new IpPrefix("fe80::/64"), null, mIfaceName,
                                    RTN_UNICAST));
                }
            } catch (RemoteException | ServiceSpecificException | IllegalStateException e) {
                mLog.e("Error Tethering", e);
                mLastError = TETHER_ERROR_TETHER_IFACE_ERROR;
                return;
            }

            if (!startIPv6()) {
                mLog.e("Failed to startIPv6");
                // TODO: Make this a fatal error once Bluetooth IPv6 is sorted.
                return;
            }

            if (mTetheringAgent != null && everRegistered(mTetheringAgent)) {
                mTetheringAgent.sendLinkProperties(mLinkProperties);
                // Mark it connected to notify the applications for
                // the network availability.
                mTetheringAgent.markConnected();
            }
        }

        @Override
        @SuppressLint("NewApi")
        public void exit() {
            // Note that at this point, we're leaving the tethered state.  We can fail any
            // of these operations, but it doesn't really change that we have to try them
            // all in sequence.
            stopIPv6();

            // Reset interface for tethering.
            try {
                try {
                    mNetd.tetherInterfaceRemove(mIfaceName);
                } finally {
                    if (mTetheringAgent == null) {
                        mNetd.networkRemoveInterface(LOCAL_NET_ID, mIfaceName);
                    }
                }
            } catch (RemoteException | ServiceSpecificException e) {
                mLastError = TETHER_ERROR_UNTETHER_IFACE_ERROR;
                mLog.e("Failed to untether interface: " + e);
            }

            stopIPv4();
            mBpfCoordinator.removeIpServer(IpServer.this);

            if (mTetheringAgent != null && everRegistered(mTetheringAgent)) {
                mTetheringAgent.unregister();
                mTetheringAgent = null;
            }

            resetLinkProperties();

            mTetheringMetrics.updateErrorCode(mInterfaceType, mLastError);
            mTetheringMetrics.sendReport(mInterfaceType);
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_TETHER_UNREQUESTED:
                    transitionTo(mInitialState);
                    if (DBG) Log.d(TAG, "Untethered (unrequested)" + mIfaceName);
                    break;
                case CMD_INTERFACE_DOWN:
                    transitionTo(mUnavailableState);
                    if (DBG) Log.d(TAG, "Untethered (ifdown)" + mIfaceName);
                    break;
                case CMD_IPV6_TETHER_UPDATE:
                    updateUpstreamIPv6LinkProperties((LinkProperties) message.obj, message.arg1);
                    // Sends update to the NetworkAgent.
                    // TODO: Refactor the callers of sendLinkProperties()
                    //  and move these code into sendLinkProperties().
                    if (mTetheringAgent != null && everRegistered(mTetheringAgent)) {
                        mTetheringAgent.sendLinkProperties(mLinkProperties);
                    }
                    sendLinkProperties();
                    break;
                case CMD_IP_FORWARDING_ENABLE_ERROR:
                case CMD_IP_FORWARDING_DISABLE_ERROR:
                case CMD_START_TETHERING_ERROR:
                case CMD_STOP_TETHERING_ERROR:
                case CMD_SET_DNS_FORWARDERS_ERROR:
                    mLastError = TETHER_ERROR_INTERNAL_ERROR;
                    transitionTo(mInitialState);
                    break;
                case CMD_NEW_PREFIX_REQUEST:
                    handleNewPrefixRequest((IpPrefix) message.obj);
                    break;
                case CMD_NOTIFY_PREFIX_CONFLICT:
                    mLog.i("restart tethering: " + mInterfaceType);
                    mCallback.requestEnableTethering(mInterfaceType, false /* enabled */);
                    transitionTo(mWaitingForRestartState);
                    break;
                case CMD_SERVICE_FAILED_TO_START:
                    mLog.e("start serving fail, error: " + message.arg1);
                    transitionTo(mInitialState);
                    break;
                default:
                    return false;
            }
            return true;
        }

        private void handleNewPrefixRequest(@NonNull final IpPrefix currentPrefix) {
            if (!currentPrefix.contains(mIpv4Address.getAddress())
                    || currentPrefix.getPrefixLength() != mIpv4Address.getPrefixLength()) {
                Log.e(TAG, "Invalid prefix: " + currentPrefix);
                return;
            }

            final LinkAddress deprecatedLinkAddress = mIpv4Address;
            mIpv4Address = requestIpv4Address(getScope(), false);
            if (mIpv4Address == null) {
                mLog.e("Fail to request a new downstream prefix");
                return;
            }
            final Inet4Address srvAddr = (Inet4Address) mIpv4Address.getAddress();

            // Add new IPv4 address on the interface.
            if (!mInterfaceCtrl.addAddress(srvAddr, currentPrefix.getPrefixLength())) {
                mLog.e("Failed to add new IP " + srvAddr);
                return;
            }

            // Remove deprecated routes from downstream network.
            final List<RouteInfo> routesToBeRemoved =
                    List.of(getDirectConnectedRoute(deprecatedLinkAddress));
            if (mTetheringAgent == null) {
                removeRoutesFromNetwork(LOCAL_NET_ID, routesToBeRemoved);
            }
            for (RouteInfo route : routesToBeRemoved) mLinkProperties.removeRoute(route);
            mLinkProperties.removeLinkAddress(deprecatedLinkAddress);

            // Add new routes to downstream network.
            final List<RouteInfo> routesToBeAdded = List.of(getDirectConnectedRoute(mIpv4Address));
            if (mTetheringAgent == null) {
                addRoutesToNetwork(LOCAL_NET_ID, routesToBeAdded);
            }
            for (RouteInfo route : routesToBeAdded) mLinkProperties.addRoute(route);
            mLinkProperties.addLinkAddress(mIpv4Address);

            // Update local DNS caching server with new IPv4 address, otherwise, dnsmasq doesn't
            // listen on the interface configured with new IPv4 address, that results DNS validation
            // failure of downstream client even if appropriate routes have been configured.
            try {
                mNetd.tetherApplyDnsInterfaces();
            } catch (ServiceSpecificException | RemoteException e) {
                mLog.e("Failed to update local DNS caching server");
                return;
            }
            // Sends update to the NetworkAgent.
            if (mTetheringAgent != null && everRegistered(mTetheringAgent)) {
                mTetheringAgent.sendLinkProperties(mLinkProperties);
            }
            sendLinkProperties();

            // Notify DHCP server that new prefix/route has been applied on IpServer.
            final Inet4Address clientAddr = mStaticIpv4ClientAddr == null ? null :
                    (Inet4Address) mStaticIpv4ClientAddr.getAddress();
            final DhcpServingParamsParcel params = makeServingParams(srvAddr /* defaultRouter */,
                    srvAddr /* dnsServer */, mIpv4Address /* serverLinkAddress */, clientAddr);
            try {
                mDhcpServer.updateParams(params, new OnHandlerStatusCallback() {
                        @Override
                        public void callback(int statusCode) {
                            if (statusCode != STATUS_SUCCESS) {
                                mLog.e("Error updating DHCP serving params: " + statusCode);
                            }
                        }
                });
            } catch (RemoteException e) {
                mLog.e("Error updating DHCP serving params", e);
            }
        }
    }

    // Handling errors in BaseServingState.enter() by transitioning is
    // problematic because transitioning during a multi-state jump yields
    // a Log.wtf(). Ultimately, there should be only one ServingState,
    // and forwarding and NAT rules should be handled by a coordinating
    // functional element outside of IpServer.
    class LocalHotspotState extends BaseServingState {
        LocalHotspotState() {
            super(STATE_LOCAL_ONLY);
        }

        @Override
        public boolean processMessage(Message message) {
            if (super.processMessage(message)) return true;

            maybeLogMessage(this, message.what);
            switch (message.what) {
                case CMD_TETHER_REQUESTED:
                    mLog.e("CMD_TETHER_REQUESTED while in local-only hotspot mode.");
                    break;
                case CMD_TETHER_CONNECTION_CHANGED:
                    // Ignored in local hotspot state.
                    break;
                default:
                    return false;
            }
            return true;
        }
    }

    // Handling errors in BaseServingState.enter() by transitioning is
    // problematic because transitioning during a multi-state jump yields
    // a Log.wtf(). Ultimately, there should be only one ServingState,
    // and forwarding and NAT rules should be handled by a coordinating
    // functional element outside of IpServer.
    class TetheredState extends BaseServingState {
        TetheredState() {
            super(STATE_TETHERED);
        }

        @Override
        public void exit() {
            cleanupUpstream();
            mBpfCoordinator.clearAllIpv6Rules(IpServer.this);
            super.exit();
        }

        // Note that IPv4 offload rules cleanup is implemented in BpfCoordinator while upstream
        // state is null or changed because IPv4 and IPv6 tethering have different code flow
        // and behaviour. While upstream is switching from offload supported interface to
        // offload non-supportted interface, event CMD_TETHER_CONNECTION_CHANGED calls
        // #cleanupUpstreamInterface but #cleanupUpstream because new UpstreamIfaceSet is not null.
        // This case won't happen in IPv6 tethering because IPv6 tethering upstream state is
        // reported by IPv6TetheringCoordinator. #cleanupUpstream is also called by unwirding
        // adding NAT failure. In that case, the IPv4 offload rules are removed by #stopIPv4
        // in the state machine. Once there is any case out whish is not covered by previous cases,
        // probably consider clearing rules in #cleanupUpstream as well.
        private void cleanupUpstream() {
            if (mUpstreamIfaceSet == null) return;

            for (String ifname : mUpstreamIfaceSet.ifnames) cleanupUpstreamInterface(ifname);
            mUpstreamIfaceSet = null;
            mBpfCoordinator.updateIpv6UpstreamInterface(IpServer.this, NO_UPSTREAM,
                    Collections.emptySet());
        }

        private void cleanupUpstreamInterface(String upstreamIface) {
            // Note that we don't care about errors here.
            // Sometimes interfaces are gone before we get
            // to remove their rules, which generates errors.
            // Just do the best we can.
            mBpfCoordinator.maybeDetachProgram(mIfaceName, upstreamIface);
            removeInterfaceForward(mIfaceName, upstreamIface);
        }

        @Override
        public boolean processMessage(Message message) {
            if (super.processMessage(message)) return true;

            maybeLogMessage(this, message.what);
            switch (message.what) {
                case CMD_TETHER_REQUESTED:
                    mLog.e("CMD_TETHER_REQUESTED while already tethering.");
                    break;
                case CMD_TETHER_CONNECTION_CHANGED:
                    final InterfaceSet newUpstreamIfaceSet = (InterfaceSet) message.obj;
                    if (noChangeInUpstreamIfaceSet(newUpstreamIfaceSet)) {
                        if (VDBG) Log.d(TAG, "Connection changed noop - dropping");
                        break;
                    }

                    if (newUpstreamIfaceSet == null) {
                        cleanupUpstream();
                        break;
                    }

                    for (String removed : upstreamInterfacesRemoved(newUpstreamIfaceSet)) {
                        cleanupUpstreamInterface(removed);
                    }

                    final Set<String> added = upstreamInterfacesAdd(newUpstreamIfaceSet);
                    // This makes the call to cleanupUpstream() in the error
                    // path for any interface neatly cleanup all the interfaces.
                    mUpstreamIfaceSet = newUpstreamIfaceSet;

                    for (String ifname : added) {
                        // Add upstream index to name mapping for the tether stats usage in the
                        // coordinator. Although this mapping could be added by both class
                        // Tethering and IpServer, adding mapping from IpServer guarantees that
                        // the mapping is added before adding forwarding rules. That is because
                        // there are different state machines in both classes. It is hard to
                        // guarantee the link property update order between multiple state machines.
                        // Note that both IPv4 and IPv6 interface may be added because
                        // Tethering::setUpstreamNetwork calls getTetheringInterfaces which merges
                        // IPv4 and IPv6 interface name (if any) into an InterfaceSet. The IPv6
                        // capability may be updated later. In that case, IPv6 interface mapping is
                        // updated in updateUpstreamIPv6LinkProperties.
                        if (!ifname.startsWith("v4-")) {  // ignore clat interfaces
                            final InterfaceParams upstreamIfaceParams =
                                    mDeps.getInterfaceParams(ifname);
                            if (upstreamIfaceParams != null) {
                                mBpfCoordinator.maybeAddUpstreamToLookupTable(
                                        upstreamIfaceParams.index, ifname);
                            }
                        }

                        mBpfCoordinator.maybeAttachProgram(mIfaceName, ifname);
                        try {
                            addInterfaceForward(mIfaceName, ifname);
                        } catch (RuntimeException e) {
                            mLog.e("Exception enabling iface forward", e);
                            cleanupUpstream();
                            mLastError = TETHER_ERROR_ENABLE_FORWARDING_ERROR;
                            transitionTo(mInitialState);
                            return true;
                        }
                    }
                    break;
                default:
                    return false;
            }
            return true;
        }

        private boolean noChangeInUpstreamIfaceSet(InterfaceSet newIfaces) {
            if (mUpstreamIfaceSet == null && newIfaces == null) return true;
            if (mUpstreamIfaceSet != null && newIfaces != null) {
                return mUpstreamIfaceSet.equals(newIfaces);
            }
            return false;
        }

        private Set<String> upstreamInterfacesRemoved(InterfaceSet newIfaces) {
            if (mUpstreamIfaceSet == null) return new HashSet<>();

            final HashSet<String> removed = new HashSet<>(mUpstreamIfaceSet.ifnames);
            removed.removeAll(newIfaces.ifnames);
            return removed;
        }

        private Set<String> upstreamInterfacesAdd(InterfaceSet newIfaces) {
            final HashSet<String> added = new HashSet<>(newIfaces.ifnames);
            if (mUpstreamIfaceSet != null) added.removeAll(mUpstreamIfaceSet.ifnames);
            return added;
        }
    }

    /**
     * This state is terminal for the per interface state machine.  At this
     * point, the tethering main state machine should have removed this interface
     * specific state machine from its list of possible recipients of
     * tethering requests.  The state machine itself will hang around until
     * the garbage collector finds it.
     */
    class UnavailableState extends State {
        @Override
        public void enter() {
            mLastError = TETHER_ERROR_NO_ERROR;
            // TODO: clean this up after the synchronous state machine is fully rolled out. Clean up
            // can be directly triggered after calling IpServer.stop() inside Tethering.java.
            sendInterfaceState(STATE_UNAVAILABLE);
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_IPV6_TETHER_UPDATE:
                    // sendInterfaceState(STATE_UNAVAILABLE) triggers
                    // handleInterfaceServingStateInactive which in turn cleans up IPv6 tethering
                    // (and calls into IpServer one more time). At this point, this is the only
                    // message we potentially see in this state because this IpServer has already
                    // been removed from mTetherStates before transitioning to this State; however,
                    // handleInterfaceServiceStateInactive passes a reference.
                    // TODO: This can be removed once SyncStateMachine is rolled out and the
                    // teardown path is cleaned up.
                    return true;
                default:
                    return false;
            }
        }
    }

    class WaitingForRestartState extends State {
        @Override
        public boolean processMessage(Message message) {
            maybeLogMessage(this, message.what);
            switch (message.what) {
                case CMD_TETHER_UNREQUESTED:
                    transitionTo(mInitialState);
                    mLog.i("Untethered (unrequested) and restarting " + mIfaceName);
                    mCallback.requestEnableTethering(mInterfaceType, true /* enabled */);
                    break;
                case CMD_INTERFACE_DOWN:
                    transitionTo(mUnavailableState);
                    mLog.i("Untethered (interface down) and restarting " + mIfaceName);
                    mCallback.requestEnableTethering(mInterfaceType, true /* enabled */);
                    break;
                default:
                    return false;
            }
            return true;
        }
    }

    // Accumulate routes representing "prefixes to be assigned to the local
    // interface", for subsequent modification of local_network routing.
    private static ArrayList<RouteInfo> getLocalRoutesFor(
            String ifname, ArraySet<IpPrefix> prefixes) {
        final ArrayList<RouteInfo> localRoutes = new ArrayList<RouteInfo>();
        for (IpPrefix ipp : prefixes) {
            localRoutes.add(new RouteInfo(ipp, null, ifname, RTN_UNICAST));
        }
        return localRoutes;
    }

    // Given a prefix like 2001:db8::/64 return an address like 2001:db8::1.
    private static Inet6Address getLocalDnsIpFor(IpPrefix localPrefix) {
        final byte[] dnsBytes = localPrefix.getRawAddress();
        dnsBytes[dnsBytes.length - 1] = getRandomSanitizedByte(DOUG_ADAMS, asByte(0), asByte(1));
        try {
            return Inet6Address.getByAddress(null, dnsBytes, 0);
        } catch (UnknownHostException e) {
            Log.wtf(TAG, "Failed to construct Inet6Address from: " + localPrefix);
            return null;
        }
    }

    private static byte getRandomSanitizedByte(byte dflt, byte... excluded) {
        final byte random = (byte) (new Random()).nextInt();
        for (int value : excluded) {
            if (random == value) return dflt;
        }
        return random;
    }

    /** Get IPv6 prefixes from LinkProperties */
    @NonNull
    @VisibleForTesting
    static ArraySet<IpPrefix> getTetherableIpv6Prefixes(@NonNull Collection<LinkAddress> addrs) {
        final ArraySet<IpPrefix> prefixes = new ArraySet<>();
        for (LinkAddress linkAddr : addrs) {
            if (linkAddr.getPrefixLength() != RFC7421_PREFIX_LENGTH) continue;
            prefixes.add(new IpPrefix(linkAddr.getAddress(), RFC7421_PREFIX_LENGTH));
        }
        return prefixes;
    }

    @NonNull
    private ArraySet<IpPrefix> getTetherableIpv6Prefixes(@NonNull LinkProperties lp) {
        return getTetherableIpv6Prefixes(lp.getLinkAddresses());
    }
}
