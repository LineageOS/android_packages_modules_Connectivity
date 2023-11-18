/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server.thread;

import static android.net.MulticastRoutingConfig.CONFIG_FORWARD_NONE;
import static android.net.MulticastRoutingConfig.FORWARD_NONE;
import static android.net.MulticastRoutingConfig.FORWARD_SELECTED;
import static android.net.MulticastRoutingConfig.FORWARD_WITH_MIN_SCOPE;
import static android.net.thread.ActiveOperationalDataset.CHANNEL_PAGE_24_GHZ;
import static android.net.thread.ActiveOperationalDataset.LENGTH_EXTENDED_PAN_ID;
import static android.net.thread.ActiveOperationalDataset.LENGTH_MESH_LOCAL_PREFIX_BITS;
import static android.net.thread.ActiveOperationalDataset.LENGTH_NETWORK_KEY;
import static android.net.thread.ActiveOperationalDataset.LENGTH_PSKC;
import static android.net.thread.ActiveOperationalDataset.MESH_LOCAL_PREFIX_FIRST_BYTE;
import static android.net.thread.ActiveOperationalDataset.SecurityPolicy.DEFAULT_ROTATION_TIME_HOURS;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_DETACHED;
import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;
import static android.net.thread.ThreadNetworkException.ERROR_ABORTED;
import static android.net.thread.ThreadNetworkException.ERROR_BUSY;
import static android.net.thread.ThreadNetworkException.ERROR_FAILED_PRECONDITION;
import static android.net.thread.ThreadNetworkException.ERROR_INTERNAL_ERROR;
import static android.net.thread.ThreadNetworkException.ERROR_REJECTED_BY_PEER;
import static android.net.thread.ThreadNetworkException.ERROR_RESOURCE_EXHAUSTED;
import static android.net.thread.ThreadNetworkException.ERROR_RESPONSE_BAD_FORMAT;
import static android.net.thread.ThreadNetworkException.ERROR_TIMEOUT;
import static android.net.thread.ThreadNetworkException.ERROR_UNSUPPORTED_CHANNEL;
import static android.net.thread.ThreadNetworkException.ErrorCode;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;

import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_ABORT;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_BUSY;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_DETACHED;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_INVALID_STATE;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_NO_BUFS;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_PARSE;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_REASSEMBLY_TIMEOUT;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_REJECTED;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_RESPONSE_TIMEOUT;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_UNSUPPORTED_CHANNEL;
import static com.android.server.thread.openthread.IOtDaemon.TUN_IF_NAME;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LocalNetworkConfig;
import android.net.MulticastRoutingConfig;
import android.net.LocalNetworkInfo;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.RouteInfo;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.ActiveOperationalDataset.SecurityPolicy;
import android.net.thread.IActiveOperationalDatasetReceiver;
import android.net.thread.IOperationReceiver;
import android.net.thread.IOperationalDatasetCallback;
import android.net.thread.IStateCallback;
import android.net.thread.IThreadNetworkController;
import android.net.thread.OperationalDatasetTimestamp;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.DeviceRole;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceManagerWrapper;
import com.android.server.thread.openthread.IOtDaemon;
import com.android.server.thread.openthread.IOtDaemonCallback;
import com.android.server.thread.openthread.IOtStatusReceiver;
import com.android.server.thread.openthread.Ipv6AddressInfo;
import com.android.server.thread.openthread.OtDaemonState;
import com.android.server.thread.openthread.BorderRouterConfigurationParcel;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Implementation of the {@link ThreadNetworkController} API.
 *
 * <p>Threading model: This class is not Thread-safe and should only be accessed from the
 * ThreadNetworkService class. Additional attention should be paid to handle the threading code
 * correctly: 1. All member fields other than `mHandler` and `mContext` MUST be accessed from
 * `mHandlerThread` 2. In the @Override methods, the actual work MUST be dispatched to the
 * HandlerThread except for arguments or permissions checking
 */
final class ThreadNetworkControllerService extends IThreadNetworkController.Stub {
    private static final String TAG = "ThreadNetworkService";

    // Below member fields can be accessed from both the binder and handler threads

    private final Context mContext;
    private final Handler mHandler;

    // Below member fields can only be accessed from the handler thread (`mHandlerThread`). In
    // particular, the constructor does not run on the handler thread, so it must not touch any of
    // the non-final fields, nor must it mutate any of the non-final fields inside these objects.

    private final HandlerThread mHandlerThread;
    private final NetworkProvider mNetworkProvider;
    private final Supplier<IOtDaemon> mOtDaemonSupplier;
    private final ConnectivityManager mConnectivityManager;
    private final TunInterfaceController mTunIfController;
    private final LinkProperties mLinkProperties = new LinkProperties();
    private final OtDaemonCallbackProxy mOtDaemonCallbackProxy = new OtDaemonCallbackProxy();

    // TODO(b/308310823): read supported channel from Thread dameon
    private final int mSupportedChannelMask = 0x07FFF800; // from channel 11 to 26

    private IOtDaemon mOtDaemon;
    private NetworkAgent mNetworkAgent;
    private MulticastRoutingConfig mUpstreamMulticastRoutingConfig = CONFIG_FORWARD_NONE;
    private MulticastRoutingConfig mDownstreamMulticastRoutingConfig = CONFIG_FORWARD_NONE;
    private Network mUpstreamNetwork;
    private final NetworkRequest mUpstreamNetworkRequest;
    private final HashMap<Network, String> mNetworkToInterface;
    private final LocalNetworkConfig mLocalNetworkConfig;

    private BorderRouterConfigurationParcel mBorderRouterConfig;

    @VisibleForTesting
    ThreadNetworkControllerService(
            Context context,
            HandlerThread handlerThread,
            NetworkProvider networkProvider,
            Supplier<IOtDaemon> otDaemonSupplier,
            ConnectivityManager connectivityManager,
            TunInterfaceController tunIfController) {
        mContext = context;
        mHandlerThread = handlerThread;
        mHandler = new Handler(handlerThread.getLooper());
        mNetworkProvider = networkProvider;
        mOtDaemonSupplier = otDaemonSupplier;
        mConnectivityManager = connectivityManager;
        mTunIfController = tunIfController;
        mUpstreamNetworkRequest =
                new NetworkRequest.Builder()
                        .clearCapabilities()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .build();
        mLocalNetworkConfig =
                new LocalNetworkConfig.Builder()
                        .setUpstreamSelector(mUpstreamNetworkRequest)
                        .build();
        mNetworkToInterface = new HashMap<Network, String>();
        mBorderRouterConfig = new BorderRouterConfigurationParcel();
    }

    public static ThreadNetworkControllerService newInstance(Context context) {
        HandlerThread handlerThread = new HandlerThread("ThreadHandlerThread");
        handlerThread.start();
        NetworkProvider networkProvider =
                new NetworkProvider(context, handlerThread.getLooper(), "ThreadNetworkProvider");

        return new ThreadNetworkControllerService(
                context,
                handlerThread,
                networkProvider,
                () -> IOtDaemon.Stub.asInterface(ServiceManagerWrapper.waitForService("ot_daemon")),
                context.getSystemService(ConnectivityManager.class),
                new TunInterfaceController(TUN_IF_NAME));
    }

    private static NetworkCapabilities newNetworkCapabilities() {
        return new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .build();
    }

    private static Inet6Address bytesToInet6Address(byte[] addressBytes) {
        try {
            return (Inet6Address) Inet6Address.getByAddress(addressBytes);
        } catch (UnknownHostException e) {
            // This is unlikely to happen unless the Thread daemon is critically broken
            return null;
        }
    }

    private static InetAddress addressInfoToInetAddress(Ipv6AddressInfo addressInfo) {
        return bytesToInet6Address(addressInfo.address);
    }

    private static LinkAddress newLinkAddress(Ipv6AddressInfo addressInfo) {
        long deprecationTimeMillis =
                addressInfo.isPreferred
                        ? LinkAddress.LIFETIME_PERMANENT
                        : SystemClock.elapsedRealtime();

        InetAddress address = addressInfoToInetAddress(addressInfo);

        // flags and scope will be adjusted automatically depending on the address and
        // its lifetimes.
        return new LinkAddress(
                address,
                addressInfo.prefixLength,
                0 /* flags */,
                0 /* scope */,
                deprecationTimeMillis,
                LinkAddress.LIFETIME_PERMANENT /* expirationTime */);
    }

    private void initializeOtDaemon() {
        try {
            getOtDaemon();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to initialize ot-daemon");
        }
    }

    private IOtDaemon getOtDaemon() throws RemoteException {
        if (mOtDaemon != null) {
            return mOtDaemon;
        }

        IOtDaemon otDaemon = mOtDaemonSupplier.get();
        if (otDaemon == null) {
            throw new RemoteException("Internal error: failed to start OT daemon");
        }
        otDaemon.asBinder().linkToDeath(() -> mHandler.post(this::onOtDaemonDied), 0);
        otDaemon.initialize(mTunIfController.getTunFd());
        otDaemon.registerStateCallback(mOtDaemonCallbackProxy, -1);
        mOtDaemon = otDaemon;
        return mOtDaemon;
    }

    // TODO(b/309792480): restarts the OT daemon service
    private void onOtDaemonDied() {
        Log.w(TAG, "OT daemon became dead, clean up...");
        OperationReceiverWrapper.onOtDaemonDied();
        mOtDaemonCallbackProxy.onOtDaemonDied();
        mOtDaemon = null;
    }

    public void initialize() {
        mHandler.post(
                () -> {
                    Log.d(TAG, "Initializing Thread system service...");
                    try {
                        mTunIfController.createTunInterface();
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "Failed to create Thread tunnel interface", e);
                    }
                    mLinkProperties.setInterfaceName(TUN_IF_NAME);
                    mLinkProperties.setMtu(TunInterfaceController.MTU);
                    mConnectivityManager.registerNetworkProvider(mNetworkProvider);
                    requestUpstreamNetwork();

                    initializeOtDaemon();
                });
    }

    private void requestUpstreamNetwork() {
        mConnectivityManager.registerNetworkCallback(
                mUpstreamNetworkRequest,
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        Log.i(TAG, "onAvailable: " + network);
                    }

                    @Override
                    public void onLost(@NonNull Network network) {
                        Log.i(TAG, "onLost: " + network);
                    }

                    @Override
                    public void onLinkPropertiesChanged(
                            @NonNull Network network, @NonNull LinkProperties linkProperties) {
                        Log.i(
                                TAG,
                                String.format(
                                        "onLinkPropertiesChanged: {network: %s, interface: %s}",
                                        network, linkProperties.getInterfaceName()));
                        mNetworkToInterface.put(network, linkProperties.getInterfaceName());
                        if (network.equals(mUpstreamNetwork)) {
                            enableBorderRouting(mNetworkToInterface.get(mUpstreamNetwork));
                        }
                    }
                },
                mHandler);
    }

    private final class ThreadNetworkCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(@NonNull Network network) {
            Log.i(TAG, "onAvailable: Thread network Available");
        }

        @Override
        public void onLocalNetworkInfoChanged(
                @NonNull Network network, @NonNull LocalNetworkInfo localNetworkInfo) {
            Log.i(TAG, "onLocalNetworkInfoChanged: " + localNetworkInfo);
            if (localNetworkInfo.getUpstreamNetwork() == null) {
                mUpstreamNetwork = null;
                return;
            }
            if (!localNetworkInfo.getUpstreamNetwork().equals(mUpstreamNetwork)) {
                mUpstreamNetwork = localNetworkInfo.getUpstreamNetwork();
                if (mNetworkToInterface.containsKey(mUpstreamNetwork)) {
                    enableBorderRouting(mNetworkToInterface.get(mUpstreamNetwork));
                }
            }
        }
    }

    private void requestThreadNetwork() {
        mConnectivityManager.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .clearCapabilities()
                        .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                        .removeForbiddenCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                        .build(),
                new ThreadNetworkCallback(),
                mHandler);
    }

    private void registerThreadNetwork() {
        if (mNetworkAgent != null) {
            return;
        }
        NetworkCapabilities netCaps = newNetworkCapabilities();
        NetworkScore score =
                new NetworkScore.Builder()
                        .setKeepConnectedReason(NetworkScore.KEEP_CONNECTED_LOCAL_NETWORK)
                        .build();
        requestThreadNetwork();
        mNetworkAgent =
                new NetworkAgent(
                        mContext,
                        mHandlerThread.getLooper(),
                        TAG,
                        netCaps,
                        mLinkProperties,
                        mLocalNetworkConfig,
                        score,
                        new NetworkAgentConfig.Builder().build(),
                        mNetworkProvider) {};
        mNetworkAgent.register();
        mNetworkAgent.markConnected();
        Log.i(TAG, "Registered Thread network");
    }

    private void unregisterThreadNetwork() {
        if (mNetworkAgent == null) {
            // unregisterThreadNetwork can be called every time this device becomes detached or
            // disabled and the mNetworkAgent may not be created in this cases
            return;
        }

        Log.d(TAG, "Unregistering Thread network agent");

        mNetworkAgent.unregister();
        mNetworkAgent = null;
    }

    private void updateTunInterfaceAddress(LinkAddress linkAddress, boolean isAdded) {
        try {
            if (isAdded) {
                mTunIfController.addAddress(linkAddress);
            } else {
                mTunIfController.removeAddress(linkAddress);
            }
        } catch (IOException e) {
            Log.e(
                    TAG,
                    String.format(
                            "Failed to %s Thread tun interface address %s",
                            (isAdded ? "add" : "remove"), linkAddress),
                    e);
        }
    }

    private void updateNetworkLinkProperties(LinkAddress linkAddress, boolean isAdded) {
        RouteInfo routeInfo =
                new RouteInfo(
                        new IpPrefix(linkAddress.getAddress(), 64),
                        null,
                        TUN_IF_NAME,
                        RouteInfo.RTN_UNICAST,
                        TunInterfaceController.MTU);
        if (isAdded) {
            mLinkProperties.addLinkAddress(linkAddress);
            mLinkProperties.addRoute(routeInfo);
        } else {
            mLinkProperties.removeLinkAddress(linkAddress);
            mLinkProperties.removeRoute(routeInfo);
        }

        // The Thread daemon can send link property updates before the networkAgent is
        // registered
        if (mNetworkAgent != null) {
            mNetworkAgent.sendLinkProperties(mLinkProperties);
        }
    }

    @Override
    public int getThreadVersion() {
        return THREAD_VERSION_1_3;
    }

    @Override
    public void createRandomizedDataset(
            String networkName, IActiveOperationalDatasetReceiver receiver) {
        mHandler.post(
                () -> {
                    ActiveOperationalDataset dataset =
                            createRandomizedDatasetInternal(
                                    networkName,
                                    mSupportedChannelMask,
                                    Instant.now(),
                                    new Random(),
                                    new SecureRandom());
                    try {
                        receiver.onSuccess(dataset);
                    } catch (RemoteException e) {
                        // The client is dead, do nothing
                    }
                });
    }

    private static ActiveOperationalDataset createRandomizedDatasetInternal(
            String networkName,
            int supportedChannelMask,
            Instant now,
            Random random,
            SecureRandom secureRandom) {
        int panId = random.nextInt(/* bound= */ 0xffff);
        final byte[] meshLocalPrefix = newRandomBytes(random, LENGTH_MESH_LOCAL_PREFIX_BITS / 8);
        meshLocalPrefix[0] = MESH_LOCAL_PREFIX_FIRST_BYTE;

        final SparseArray<byte[]> channelMask = new SparseArray<>(1);
        channelMask.put(CHANNEL_PAGE_24_GHZ, channelMaskToByteArray(supportedChannelMask));

        final byte[] securityFlags = new byte[] {(byte) 0xff, (byte) 0xf8};

        return new ActiveOperationalDataset.Builder()
                .setActiveTimestamp(
                        new OperationalDatasetTimestamp(
                                now.getEpochSecond() & 0xffffffffffffL, 0, false))
                .setExtendedPanId(newRandomBytes(random, LENGTH_EXTENDED_PAN_ID))
                .setPanId(panId)
                .setNetworkName(networkName)
                .setChannel(CHANNEL_PAGE_24_GHZ, selectRandomChannel(supportedChannelMask, random))
                .setChannelMask(channelMask)
                .setPskc(newRandomBytes(secureRandom, LENGTH_PSKC))
                .setNetworkKey(newRandomBytes(secureRandom, LENGTH_NETWORK_KEY))
                .setMeshLocalPrefix(meshLocalPrefix)
                .setSecurityPolicy(new SecurityPolicy(DEFAULT_ROTATION_TIME_HOURS, securityFlags))
                .build();
    }

    private static byte[] newRandomBytes(Random random, int length) {
        byte[] result = new byte[length];
        random.nextBytes(result);
        return result;
    }

    private static byte[] channelMaskToByteArray(int channelMask) {
        // Per Thread spec, a Channel Mask is:
        // A variable-length bit mask that identifies the channels within the channel page
        // (1 = selected, 0 = unselected). The channels are represented in most significant bit
        // order. For example, the most significant bit of the left-most byte indicates channel 0.
        // If channel 0 and channel 10 are selected, the mask would be: 80 20 00 00. For IEEE
        // 802.15.4-2006 2.4 GHz PHY, the ChannelMask is 27 bits and MaskLength is 4.
        //
        // The pass-in channelMask represents a channel K by (channelMask & (1 << K)), so here
        // needs to do bit-wise reverse to convert it to the Thread spec format in bytes.
        channelMask = Integer.reverse(channelMask);
        return new byte[] {
            (byte) (channelMask >>> 24),
            (byte) (channelMask >>> 16),
            (byte) (channelMask >>> 8),
            (byte) channelMask
        };
    }

    private static int selectRandomChannel(int supportedChannelMask, Random random) {
        int num = random.nextInt(Integer.bitCount(supportedChannelMask));
        for (int i = 0; i < 32; i++) {
            if ((supportedChannelMask & 1) == 1 && (num--) == 0) {
                return i;
            }
            supportedChannelMask >>>= 1;
        }
        return -1;
    }

    private void enforceAllCallingPermissionsGranted(String... permissions) {
        for (String permission : permissions) {
            mContext.enforceCallingPermission(
                    permission, "Permission " + permission + " is missing");
        }
    }

    @Override
    public void registerStateCallback(IStateCallback stateCallback) throws RemoteException {
        enforceAllCallingPermissionsGranted(permission.ACCESS_NETWORK_STATE);
        mHandler.post(() -> mOtDaemonCallbackProxy.registerStateCallback(stateCallback));
    }

    @Override
    public void unregisterStateCallback(IStateCallback stateCallback) throws RemoteException {
        enforceAllCallingPermissionsGranted(permission.ACCESS_NETWORK_STATE);
        mHandler.post(() -> mOtDaemonCallbackProxy.unregisterStateCallback(stateCallback));
    }

    @Override
    public void registerOperationalDatasetCallback(IOperationalDatasetCallback callback)
            throws RemoteException {
        enforceAllCallingPermissionsGranted(
                permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        mHandler.post(() -> mOtDaemonCallbackProxy.registerDatasetCallback(callback));
    }

    @Override
    public void unregisterOperationalDatasetCallback(IOperationalDatasetCallback callback)
            throws RemoteException {
        enforceAllCallingPermissionsGranted(
                permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        mHandler.post(() -> mOtDaemonCallbackProxy.unregisterDatasetCallback(callback));
    }

    private void checkOnHandlerThread() {
        if (Looper.myLooper() != mHandlerThread.getLooper()) {
            Log.wtf(TAG, "Must be on the handler thread!");
        }
    }

    private IOtStatusReceiver newOtStatusReceiver(OperationReceiverWrapper receiver) {
        return new IOtStatusReceiver.Stub() {
            @Override
            public void onSuccess() {
                receiver.onSuccess();
            }

            @Override
            public void onError(int otError, String message) {
                receiver.onError(otErrorToAndroidError(otError), message);
            }
        };
    }

    @ErrorCode
    private static int otErrorToAndroidError(int otError) {
        // See external/openthread/include/openthread/error.h for OT error definition
        switch (otError) {
            case OT_ERROR_ABORT:
                return ERROR_ABORTED;
            case OT_ERROR_BUSY:
                return ERROR_BUSY;
            case OT_ERROR_DETACHED:
            case OT_ERROR_INVALID_STATE:
                return ERROR_FAILED_PRECONDITION;
            case OT_ERROR_NO_BUFS:
                return ERROR_RESOURCE_EXHAUSTED;
            case OT_ERROR_PARSE:
                return ERROR_RESPONSE_BAD_FORMAT;
            case OT_ERROR_REASSEMBLY_TIMEOUT:
            case OT_ERROR_RESPONSE_TIMEOUT:
                return ERROR_TIMEOUT;
            case OT_ERROR_REJECTED:
                return ERROR_REJECTED_BY_PEER;
            case OT_ERROR_UNSUPPORTED_CHANNEL:
                return ERROR_UNSUPPORTED_CHANNEL;
            default:
                return ERROR_INTERNAL_ERROR;
        }
    }

    @Override
    public void join(
            @NonNull ActiveOperationalDataset activeDataset, @NonNull IOperationReceiver receiver) {
        enforceAllCallingPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        OperationReceiverWrapper receiverWrapper = new OperationReceiverWrapper(receiver);
        mHandler.post(() -> joinInternal(activeDataset, receiverWrapper));
    }

    private void joinInternal(
            @NonNull ActiveOperationalDataset activeDataset,
            @NonNull OperationReceiverWrapper receiver) {
        checkOnHandlerThread();

        try {
            // The otDaemon.join() will leave first if this device is currently attached
            getOtDaemon().join(activeDataset.toThreadTlvs(), newOtStatusReceiver(receiver));
        } catch (RemoteException e) {
            Log.e(TAG, "otDaemon.join failed", e);
            receiver.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
    }

    @Override
    public void scheduleMigration(
            @NonNull PendingOperationalDataset pendingDataset,
            @NonNull IOperationReceiver receiver) {
        enforceAllCallingPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        OperationReceiverWrapper receiverWrapper = new OperationReceiverWrapper(receiver);
        mHandler.post(() -> scheduleMigrationInternal(pendingDataset, receiverWrapper));
    }

    public void scheduleMigrationInternal(
            @NonNull PendingOperationalDataset pendingDataset,
            @NonNull OperationReceiverWrapper receiver) {
        checkOnHandlerThread();

        try {
            getOtDaemon()
                    .scheduleMigration(
                            pendingDataset.toThreadTlvs(), newOtStatusReceiver(receiver));
        } catch (RemoteException e) {
            Log.e(TAG, "otDaemon.scheduleMigration failed", e);
            receiver.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
    }

    @Override
    public void leave(@NonNull IOperationReceiver receiver) throws RemoteException {
        enforceAllCallingPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        mHandler.post(() -> leaveInternal(new OperationReceiverWrapper(receiver)));
    }

    private void leaveInternal(@NonNull OperationReceiverWrapper receiver) {
        checkOnHandlerThread();

        try {
            getOtDaemon().leave(newOtStatusReceiver(receiver));
        } catch (RemoteException e) {
            // Oneway AIDL API should never throw?
            receiver.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
    }

    private void enableBorderRouting(String infraIfName) {
        if (mBorderRouterConfig.isBorderRoutingEnabled
                && infraIfName.equals(mBorderRouterConfig.infraInterfaceName)) {
            return;
        }
        Log.i(TAG, "enableBorderRouting on AIL: " + infraIfName);
        try {
            mBorderRouterConfig.infraInterfaceName = infraIfName;
            mBorderRouterConfig.infraInterfaceIcmp6Socket =
                    InfraInterfaceController.createIcmp6Socket(infraIfName);
            mBorderRouterConfig.isBorderRoutingEnabled = true;

            mOtDaemon.configureBorderRouter(
                    mBorderRouterConfig,
                    new IOtStatusReceiver.Stub() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "configure border router successfully");
                        }

                        @Override
                        public void onError(int i, String s) {
                            Log.w(
                                    TAG,
                                    String.format(
                                            "failed to configure border router: %d %s", i, s));
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "enableBorderRouting failed: " + e);
        }
    }

    private void handleThreadInterfaceStateChanged(boolean isUp) {
        try {
            mTunIfController.setInterfaceUp(isUp);
            Log.d(TAG, "Thread network interface becomes " + (isUp ? "up" : "down"));
        } catch (IOException e) {
            Log.e(TAG, "Failed to handle Thread interface state changes", e);
        }
    }

    private void handleDeviceRoleChanged(@DeviceRole int deviceRole) {
        if (ThreadNetworkController.isAttached(deviceRole)) {
            Log.d(TAG, "Attached to the Thread network");

            // This is an idempotent method which can be called for multiple times when the device
            // is already attached (e.g. going from Child to Router)
            registerThreadNetwork();
        } else {
            Log.d(TAG, "Detached from the Thread network");

            // This is an idempotent method which can be called for multiple times when the device
            // is already detached or stopped
            unregisterThreadNetwork();
        }
    }

    private void handleAddressChanged(Ipv6AddressInfo addressInfo, boolean isAdded) {
        checkOnHandlerThread();
        InetAddress address = addressInfoToInetAddress(addressInfo);
        if (address.isMulticastAddress()) {
            Log.i(TAG, "Ignoring multicast address " + address.getHostAddress());
            return;
        }

        LinkAddress linkAddress = newLinkAddress(addressInfo);
        Log.d(TAG, (isAdded ? "Adding" : "Removing") + " address " + linkAddress);

        updateTunInterfaceAddress(linkAddress, isAdded);
        updateNetworkLinkProperties(linkAddress, isAdded);
    }

    private boolean isMulticastForwardingEnabled() {
        return !(mUpstreamMulticastRoutingConfig.getForwardingMode() == FORWARD_NONE
                && mDownstreamMulticastRoutingConfig.getForwardingMode() == FORWARD_NONE);
    }

    private void sendLocalNetworkConfig() {
        if (mNetworkAgent == null) {
            return;
        }
        final LocalNetworkConfig.Builder configBuilder = new LocalNetworkConfig.Builder();
        LocalNetworkConfig localNetworkConfig =
                configBuilder
                        .setUpstreamMulticastRoutingConfig(mUpstreamMulticastRoutingConfig)
                        .setDownstreamMulticastRoutingConfig(mDownstreamMulticastRoutingConfig)
                        .setUpstreamSelector(mUpstreamNetworkRequest)
                        .build();
        mNetworkAgent.sendLocalNetworkConfig(localNetworkConfig);
        Log.d(
                TAG,
                "Sent localNetworkConfig with upstreamConfig "
                        + mUpstreamMulticastRoutingConfig
                        + " downstreamConfig"
                        + mDownstreamMulticastRoutingConfig);
    }

    private void handleMulticastForwardingStateChanged(boolean isEnabled) {
        if (isMulticastForwardingEnabled() == isEnabled) {
            return;
        }
        if (isEnabled) {
            // When multicast forwarding is enabled, setup upstream forwarding to any address
            // with minimal scope 4
            // setup downstream forwarding with addresses subscribed from Thread network
            mUpstreamMulticastRoutingConfig =
                    new MulticastRoutingConfig.Builder(FORWARD_WITH_MIN_SCOPE, 4).build();
            mDownstreamMulticastRoutingConfig =
                    new MulticastRoutingConfig.Builder(FORWARD_SELECTED).build();
        } else {
            // When multicast forwarding is disabled, set both upstream and downstream
            // forwarding config to FORWARD_NONE.
            mUpstreamMulticastRoutingConfig = CONFIG_FORWARD_NONE;
            mDownstreamMulticastRoutingConfig = CONFIG_FORWARD_NONE;
        }
        sendLocalNetworkConfig();
        Log.d(
                TAG,
                "Sent updated localNetworkConfig with multicast forwarding "
                        + (isEnabled ? "enabled" : "disabled"));
    }

    private void handleMulticastForwardingAddressChanged(byte[] addressBytes, boolean isAdded) {
        Inet6Address address = bytesToInet6Address(addressBytes);
        MulticastRoutingConfig newDownstreamConfig;
        MulticastRoutingConfig.Builder builder;

        if (mDownstreamMulticastRoutingConfig.getForwardingMode() !=
                MulticastRoutingConfig.FORWARD_SELECTED) {
            Log.e(
                    TAG,
                    "Ignore multicast listening address updates when downstream multicast "
                            + "forwarding mode is not FORWARD_SELECTED");
            // Don't update the address set if downstream multicast forwarding is disabled.
            return;
        }
        if (isAdded ==
                mDownstreamMulticastRoutingConfig.getListeningAddresses().contains(address)) {
            return;
        }

        builder = new MulticastRoutingConfig.Builder(FORWARD_SELECTED);
        for (Inet6Address listeningAddress :
                mDownstreamMulticastRoutingConfig.getListeningAddresses()) {
            builder.addListeningAddress(listeningAddress);
        }

        if (isAdded) {
            builder.addListeningAddress(address);
        } else {
            builder.clearListeningAddress(address);
        }

        newDownstreamConfig = builder.build();
        if (!newDownstreamConfig.equals(mDownstreamMulticastRoutingConfig)) {
            Log.d(
                    TAG,
                    "Multicast listening address "
                            + address.getHostAddress()
                            + " is "
                            + (isAdded ? "added" : "removed"));
            mDownstreamMulticastRoutingConfig = newDownstreamConfig;
            sendLocalNetworkConfig();
        }
    }

    private static final class CallbackMetadata {
        private static long gId = 0;

        // The unique ID
        final long id;

        final IBinder.DeathRecipient deathRecipient;

        CallbackMetadata(IBinder.DeathRecipient deathRecipient) {
            this.id = allocId();
            this.deathRecipient = deathRecipient;
        }

        private static long allocId() {
            if (gId == Long.MAX_VALUE) {
                gId = 0;
            }
            return gId++;
        }
    }

    /**
     * Handles and forwards Thread daemon callbacks. This class must be accessed from the {@code
     * mHandlerThread}.
     */
    private final class OtDaemonCallbackProxy extends IOtDaemonCallback.Stub {
        private final Map<IStateCallback, CallbackMetadata> mStateCallbacks = new HashMap<>();
        private final Map<IOperationalDatasetCallback, CallbackMetadata> mOpDatasetCallbacks =
                new HashMap<>();

        private OtDaemonState mState;
        private ActiveOperationalDataset mActiveDataset;
        private PendingOperationalDataset mPendingDataset;

        public void registerStateCallback(IStateCallback callback) {
            checkOnHandlerThread();
            if (mStateCallbacks.containsKey(callback)) {
                throw new IllegalStateException("Registering the same IStateCallback twice");
            }

            IBinder.DeathRecipient deathRecipient =
                    () -> mHandler.post(() -> unregisterStateCallback(callback));
            CallbackMetadata callbackMetadata = new CallbackMetadata(deathRecipient);
            mStateCallbacks.put(callback, callbackMetadata);
            try {
                callback.asBinder().linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                mStateCallbacks.remove(callback);
                // This is thrown when the client is dead, do nothing
            }

            try {
                getOtDaemon().registerStateCallback(this, callbackMetadata.id);
            } catch (RemoteException e) {
                // oneway operation should never fail
            }
        }

        public void unregisterStateCallback(IStateCallback callback) {
            checkOnHandlerThread();
            if (!mStateCallbacks.containsKey(callback)) {
                return;
            }
            callback.asBinder().unlinkToDeath(mStateCallbacks.remove(callback).deathRecipient, 0);
        }

        public void registerDatasetCallback(IOperationalDatasetCallback callback) {
            checkOnHandlerThread();
            if (mOpDatasetCallbacks.containsKey(callback)) {
                throw new IllegalStateException(
                        "Registering the same IOperationalDatasetCallback twice");
            }

            IBinder.DeathRecipient deathRecipient =
                    () -> mHandler.post(() -> unregisterDatasetCallback(callback));
            CallbackMetadata callbackMetadata = new CallbackMetadata(deathRecipient);
            mOpDatasetCallbacks.put(callback, callbackMetadata);
            try {
                callback.asBinder().linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                mOpDatasetCallbacks.remove(callback);
            }

            try {
                getOtDaemon().registerStateCallback(this, callbackMetadata.id);
            } catch (RemoteException e) {
                // oneway operation should never fail
            }
        }

        public void unregisterDatasetCallback(IOperationalDatasetCallback callback) {
            checkOnHandlerThread();
            if (!mOpDatasetCallbacks.containsKey(callback)) {
                return;
            }
            callback.asBinder()
                    .unlinkToDeath(mOpDatasetCallbacks.remove(callback).deathRecipient, 0);
        }

        public void onOtDaemonDied() {
            checkOnHandlerThread();
            if (mState == null) {
                return;
            }

            // If this device is already STOPPED or DETACHED, do nothing
            if (!ThreadNetworkController.isAttached(mState.deviceRole)) {
                return;
            }

            // The Thread device role is considered DETACHED when the OT daemon process is dead
            handleDeviceRoleChanged(DEVICE_ROLE_DETACHED);
            for (IStateCallback callback : mStateCallbacks.keySet()) {
                try {
                    callback.onDeviceRoleChanged(DEVICE_ROLE_DETACHED);
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
        }

        @Override
        public void onStateChanged(OtDaemonState newState, long listenerId) {
            mHandler.post(() -> onStateChangedInternal(newState, listenerId));
        }

        private void onStateChangedInternal(OtDaemonState newState, long listenerId) {
            checkOnHandlerThread();
            onInterfaceStateChanged(newState.isInterfaceUp);
            onDeviceRoleChanged(newState.deviceRole, listenerId);
            onPartitionIdChanged(newState.partitionId, listenerId);
            onMulticastForwardingStateChanged(newState.multicastForwardingEnabled);
            mState = newState;

            ActiveOperationalDataset newActiveDataset;
            try {
                if (newState.activeDatasetTlvs.length != 0) {
                    newActiveDataset =
                            ActiveOperationalDataset.fromThreadTlvs(newState.activeDatasetTlvs);
                } else {
                    newActiveDataset = null;
                }
                onActiveOperationalDatasetChanged(newActiveDataset, listenerId);
                mActiveDataset = newActiveDataset;
            } catch (IllegalArgumentException e) {
                // Is unlikely that OT will generate invalid Operational Dataset
                Log.wtf(TAG, "Invalid Active Operational Dataset from OpenThread", e);
            }

            PendingOperationalDataset newPendingDataset;
            try {
                if (newState.pendingDatasetTlvs.length != 0) {
                    newPendingDataset =
                            PendingOperationalDataset.fromThreadTlvs(newState.pendingDatasetTlvs);
                } else {
                    newPendingDataset = null;
                }
                onPendingOperationalDatasetChanged(newPendingDataset, listenerId);
                mPendingDataset = newPendingDataset;
            } catch (IllegalArgumentException e) {
                // Is unlikely that OT will generate invalid Operational Dataset
                Log.wtf(TAG, "Invalid Pending Operational Dataset from OpenThread", e);
            }
        }

        private void onInterfaceStateChanged(boolean isUp) {
            checkOnHandlerThread();
            if (mState == null || mState.isInterfaceUp != isUp) {
                handleThreadInterfaceStateChanged(isUp);
            }
        }

        private void onDeviceRoleChanged(@DeviceRole int deviceRole, long listenerId) {
            checkOnHandlerThread();
            boolean hasChange = (mState == null || mState.deviceRole != deviceRole);
            if (hasChange) {
                handleDeviceRoleChanged(deviceRole);
            }

            for (var callbackEntry : mStateCallbacks.entrySet()) {
                if (!hasChange && callbackEntry.getValue().id != listenerId) {
                    continue;
                }
                try {
                    callbackEntry.getKey().onDeviceRoleChanged(deviceRole);
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
        }

        private void onPartitionIdChanged(long partitionId, long listenerId) {
            checkOnHandlerThread();
            boolean hasChange = (mState == null || mState.partitionId != partitionId);

            for (var callbackEntry : mStateCallbacks.entrySet()) {
                if (!hasChange && callbackEntry.getValue().id != listenerId) {
                    continue;
                }
                try {
                    callbackEntry.getKey().onPartitionIdChanged(partitionId);
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
        }

        private void onActiveOperationalDatasetChanged(
                ActiveOperationalDataset activeDataset, long listenerId) {
            checkOnHandlerThread();
            boolean hasChange = !Objects.equals(mActiveDataset, activeDataset);

            for (var callbackEntry : mOpDatasetCallbacks.entrySet()) {
                if (!hasChange && callbackEntry.getValue().id != listenerId) {
                    continue;
                }
                try {
                    callbackEntry.getKey().onActiveOperationalDatasetChanged(activeDataset);
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
        }

        private void onPendingOperationalDatasetChanged(
                PendingOperationalDataset pendingDataset, long listenerId) {
            checkOnHandlerThread();
            boolean hasChange = !Objects.equals(mPendingDataset, pendingDataset);
            for (var callbackEntry : mOpDatasetCallbacks.entrySet()) {
                if (!hasChange && callbackEntry.getValue().id != listenerId) {
                    continue;
                }
                try {
                    callbackEntry.getKey().onPendingOperationalDatasetChanged(pendingDataset);
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
        }

        private void onMulticastForwardingStateChanged(boolean isEnabled) {
            checkOnHandlerThread();
            handleMulticastForwardingStateChanged(isEnabled);
        }

        @Override
        public void onAddressChanged(Ipv6AddressInfo addressInfo, boolean isAdded) {
            mHandler.post(() -> handleAddressChanged(addressInfo, isAdded));
        }

        @Override
        public void onMulticastForwardingAddressChanged(byte[] address, boolean isAdded) {
            mHandler.post(() -> handleMulticastForwardingAddressChanged(address, isAdded));
        }
    }
}
