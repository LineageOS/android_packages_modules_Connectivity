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

import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.MulticastRoutingConfig.CONFIG_FORWARD_NONE;
import static android.net.MulticastRoutingConfig.FORWARD_SELECTED;
import static android.net.MulticastRoutingConfig.FORWARD_WITH_MIN_SCOPE;
import static android.net.NetworkCapabilities.TRANSPORT_THREAD;
import static android.net.thread.ActiveOperationalDataset.CHANNEL_PAGE_24_GHZ;
import static android.net.thread.ActiveOperationalDataset.LENGTH_EXTENDED_PAN_ID;
import static android.net.thread.ActiveOperationalDataset.LENGTH_MESH_LOCAL_PREFIX_BITS;
import static android.net.thread.ActiveOperationalDataset.LENGTH_NETWORK_KEY;
import static android.net.thread.ActiveOperationalDataset.LENGTH_PSKC;
import static android.net.thread.ActiveOperationalDataset.MESH_LOCAL_PREFIX_FIRST_BYTE;
import static android.net.thread.ActiveOperationalDataset.SecurityPolicy.DEFAULT_ROTATION_TIME_HOURS;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_DETACHED;
import static android.net.thread.ThreadNetworkController.EPHEMERAL_KEY_DISABLED;
import static android.net.thread.ThreadNetworkController.STATE_DISABLED;
import static android.net.thread.ThreadNetworkController.STATE_DISABLING;
import static android.net.thread.ThreadNetworkController.STATE_ENABLED;
import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;
import static android.net.thread.ThreadNetworkException.ERROR_ABORTED;
import static android.net.thread.ThreadNetworkException.ERROR_BUSY;
import static android.net.thread.ThreadNetworkException.ERROR_FAILED_PRECONDITION;
import static android.net.thread.ThreadNetworkException.ERROR_INTERNAL_ERROR;
import static android.net.thread.ThreadNetworkException.ERROR_REJECTED_BY_PEER;
import static android.net.thread.ThreadNetworkException.ERROR_RESOURCE_EXHAUSTED;
import static android.net.thread.ThreadNetworkException.ERROR_RESPONSE_BAD_FORMAT;
import static android.net.thread.ThreadNetworkException.ERROR_THREAD_DISABLED;
import static android.net.thread.ThreadNetworkException.ERROR_TIMEOUT;
import static android.net.thread.ThreadNetworkException.ERROR_UNSUPPORTED_CHANNEL;
import static android.net.thread.ThreadNetworkException.ERROR_UNSUPPORTED_FEATURE;
import static android.net.thread.ThreadNetworkManager.DISALLOW_THREAD_NETWORK;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_TESTING;

import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_ABORT;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_BUSY;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_FAILED_PRECONDITION;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_INVALID_STATE;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_NOT_IMPLEMENTED;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_NO_BUFS;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_PARSE;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_REASSEMBLY_TIMEOUT;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_REJECTED;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_RESPONSE_TIMEOUT;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_THREAD_DISABLED;
import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_UNSUPPORTED_CHANNEL;
import static com.android.server.thread.openthread.IOtDaemon.OT_STATE_DISABLED;
import static com.android.server.thread.openthread.IOtDaemon.OT_STATE_DISABLING;
import static com.android.server.thread.openthread.IOtDaemon.OT_STATE_ENABLED;
import static com.android.server.thread.openthread.IOtDaemon.TUN_IF_NAME;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LocalNetworkConfig;
import android.net.LocalNetworkInfo;
import android.net.MulticastRoutingConfig;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.TestNetworkSpecifier;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.ActiveOperationalDataset.SecurityPolicy;
import android.net.thread.ChannelMaxPower;
import android.net.thread.IActiveOperationalDatasetReceiver;
import android.net.thread.IConfigurationReceiver;
import android.net.thread.IOperationReceiver;
import android.net.thread.IOperationalDatasetCallback;
import android.net.thread.IOutputReceiver;
import android.net.thread.IStateCallback;
import android.net.thread.IThreadNetworkController;
import android.net.thread.OperationalDatasetTimestamp;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadConfiguration;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.DeviceRole;
import android.net.thread.ThreadNetworkException;
import android.net.thread.ThreadNetworkException.ErrorCode;
import android.net.thread.ThreadNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.SparseArray;

import com.android.connectivity.resources.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CommonConnectivityJni;
import com.android.net.module.util.IIpv4PrefixRequest;
import com.android.net.module.util.RoutingCoordinatorManager;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.ConnectivityResources;
import com.android.server.connectivity.MockableSystemProperties;
import com.android.server.thread.openthread.BackboneRouterState;
import com.android.server.thread.openthread.DnsTxtAttribute;
import com.android.server.thread.openthread.IChannelMasksReceiver;
import com.android.server.thread.openthread.IOtDaemon;
import com.android.server.thread.openthread.IOtDaemonCallback;
import com.android.server.thread.openthread.IOtOutputReceiver;
import com.android.server.thread.openthread.IOtStatusReceiver;
import com.android.server.thread.openthread.InfraLinkState;
import com.android.server.thread.openthread.Ipv6AddressInfo;
import com.android.server.thread.openthread.MeshcopTxtAttributes;
import com.android.server.thread.openthread.OnMeshPrefixConfig;
import com.android.server.thread.openthread.OtDaemonConfiguration;
import com.android.server.thread.openthread.OtDaemonState;

import libcore.util.HexEncoding;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Implementation of the {@link ThreadNetworkController} API.
 *
 * <p>Threading model: This class is not Thread-safe and should only be accessed from the
 * ThreadNetworkService class. Additional attention should be paid to handle the threading code
 * correctly: 1. All member fields other than `mHandler` and `mContext` MUST be accessed from the
 * thread of `mHandler` 2. In the @Override methods, the actual work MUST be dispatched to the
 * HandlerThread except for arguments or permissions checking
 */
@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
final class ThreadNetworkControllerService extends IThreadNetworkController.Stub {
    private static final String TAG = "ControllerService";
    private static final SharedLog LOG = ThreadNetworkLogger.forSubComponent(TAG);

    // The model name length in utf-8 bytes
    private static final int MAX_MODEL_NAME_UTF8_BYTES = 24;

    // The max vendor name length in utf-8 bytes
    private static final int MAX_VENDOR_NAME_UTF8_BYTES = 24;

    // This regex pattern allows "XXXXXX", "XX:XX:XX" and "XX-XX-XX" OUI formats.
    // Note that this regex allows "XX:XX-XX" as well but we don't need to be a strict checker
    private static final String OUI_REGEX = "^([0-9A-Fa-f]{2}[:-]?){2}([0-9A-Fa-f]{2})$";

    // The channel mask that indicates all channels from channel 11 to channel 24
    private static final int CHANNEL_MASK_11_TO_24 = 0x1FFF800;

    // Below member fields can be accessed from both the binder and handler threads

    private final Context mContext;
    private final Handler mHandler;
    private final MockableSystemProperties mSystemProperties;

    // Below member fields can only be accessed from the handler thread (`mHandler`). In
    // particular, the constructor does not run on the handler thread, so it must not touch any of
    // the non-final fields, nor must it mutate any of the non-final fields inside these objects.

    private final ThreadNetworkFactory mNetworkFactory;
    private final Supplier<IOtDaemon> mOtDaemonSupplier;
    private final ConnectivityManager mConnectivityManager;
    private final RoutingCoordinatorManager mRoutingCoordinatorManager;
    private final TunInterfaceController mTunIfController;
    private final InfraInterfaceController mInfraIfController;
    private final NsdPublisher mNsdPublisher;
    private final OtDaemonCallbackProxy mOtDaemonCallbackProxy = new OtDaemonCallbackProxy();
    private final Nat64CidrController mNat64CidrController = new Nat64CidrController();
    private final ConnectivityResources mResources;
    private final Supplier<String> mCountryCodeSupplier;
    private final Map<IConfigurationReceiver, IBinder.DeathRecipient> mConfigurationReceivers =
            new HashMap<>();

    // This should not be directly used for calling IOtDaemon APIs because ot-daemon may die and
    // {@code mOtDaemon} will be set to {@code null}. Instead, use {@code getOtDaemon()}
    @Nullable private IOtDaemon mOtDaemon;
    @Nullable private NetworkAgent mNetworkAgent;
    @Nullable private NetworkAgent mTestNetworkAgent;

    private MulticastRoutingConfig mUpstreamMulticastRoutingConfig = CONFIG_FORWARD_NONE;
    private MulticastRoutingConfig mDownstreamMulticastRoutingConfig = CONFIG_FORWARD_NONE;
    private Network mUpstreamNetwork;
    private NetworkRequest mUpstreamNetworkRequest;
    private UpstreamNetworkCallback mUpstreamNetworkCallback;
    private TestNetworkSpecifier mUpstreamTestNetworkSpecifier;
    private ThreadNetworkCallback mThreadNetworkCallback;
    private final Map<Network, LinkProperties> mNetworkToLinkProperties;
    private final ThreadPersistentSettings mPersistentSettings;
    private final UserManager mUserManager;
    private boolean mUserRestricted;
    private boolean mForceStopOtDaemonEnabled;

    private InfraLinkState mInfraLinkState;

    @Nullable private ThreadNetworkSpecifier mJoinNetworkSpecifier;

    @VisibleForTesting
    ThreadNetworkControllerService(
            Context context,
            Handler handler,
            MockableSystemProperties systemProperties,
            ThreadNetworkFactory networkFactory,
            Supplier<IOtDaemon> otDaemonSupplier,
            ConnectivityManager connectivityManager,
            RoutingCoordinatorManager routingCoordinatorManager,
            TunInterfaceController tunIfController,
            InfraInterfaceController infraIfController,
            ThreadPersistentSettings persistentSettings,
            NsdPublisher nsdPublisher,
            UserManager userManager,
            ConnectivityResources resources,
            Supplier<String> countryCodeSupplier,
            Map<Network, LinkProperties> networkToLinkProperties) {
        mContext = context;
        mHandler = handler;
        mSystemProperties = systemProperties;
        mNetworkFactory = networkFactory;
        mOtDaemonSupplier = otDaemonSupplier;
        mConnectivityManager = connectivityManager;
        mRoutingCoordinatorManager = routingCoordinatorManager;
        mTunIfController = tunIfController;
        mInfraIfController = infraIfController;
        mUpstreamNetworkRequest = newUpstreamNetworkRequest();
        // TODO: networkToLinkProperties should be shared with NsdPublisher, add a test/assert to
        // verify they are the same.
        mNetworkToLinkProperties = networkToLinkProperties;
        mInfraLinkState = new InfraLinkState.Builder().build();
        mPersistentSettings = persistentSettings;
        mNsdPublisher = nsdPublisher;
        mUserManager = userManager;
        mResources = resources;
        mCountryCodeSupplier = countryCodeSupplier;
    }

    public static ThreadNetworkControllerService newInstance(
            Context context,
            ThreadPersistentSettings persistentSettings,
            Supplier<String> countryCodeSupplier) {
        HandlerThread handlerThread = new HandlerThread("ThreadHandlerThread");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());
        Map<Network, LinkProperties> networkToLinkProperties = new HashMap<>();
        final ConnectivityManager connectivityManager =
                context.getSystemService(ConnectivityManager.class);
        final RoutingCoordinatorManager routingCoordinatorManager =
                new RoutingCoordinatorManager(
                        context, connectivityManager.getRoutingCoordinatorService());

        return new ThreadNetworkControllerService(
                context,
                handler,
                new MockableSystemProperties(),
                ThreadNetworkFactory.newInstance(context, handler.getLooper()),
                () -> IOtDaemon.Stub.asInterface(CommonConnectivityJni.waitForService("ot_daemon")),
                connectivityManager,
                routingCoordinatorManager,
                new TunInterfaceController(TUN_IF_NAME),
                new InfraInterfaceController(),
                persistentSettings,
                NsdPublisher.newInstance(context, handler, networkToLinkProperties),
                context.getSystemService(UserManager.class),
                new ConnectivityResources(context),
                countryCodeSupplier,
                networkToLinkProperties);
    }

    private NetworkRequest newUpstreamNetworkRequest() {
        NetworkRequest.Builder builder = new NetworkRequest.Builder();

        if (mUpstreamTestNetworkSpecifier != null) {
            // Test networks don't have NET_CAPABILITY_TRUSTED
            return builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                    .setNetworkSpecifier(mUpstreamTestNetworkSpecifier)
                    .build();
        }
        return builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
    }

    private void maybeInitializeOtDaemon() {
        if (!shouldEnableThread()) {
            return;
        }

        LOG.i("Starting OT daemon...");

        try {
            getOtDaemon();
        } catch (RemoteException e) {
            LOG.e("Failed to initialize ot-daemon", e);
        } catch (ThreadNetworkException e) {
            // no ThreadNetworkException.ERROR_THREAD_DISABLED error should be thrown
            throw new AssertionError(e);
        }
    }

    private IOtDaemon getOtDaemon() throws RemoteException, ThreadNetworkException {
        checkOnHandlerThread();

        if (mForceStopOtDaemonEnabled) {
            throw new ThreadNetworkException(
                    ERROR_THREAD_DISABLED, "ot-daemon is forcibly stopped");
        }

        if (mOtDaemon != null) {
            return mOtDaemon;
        }

        IOtDaemon otDaemon = mOtDaemonSupplier.get();
        if (otDaemon == null) {
            throw new RemoteException("Internal error: failed to start OT daemon");
        }

        otDaemon.initialize(
                shouldEnableThread(),
                newOtDaemonConfig(mPersistentSettings.getConfiguration()),
                mTunIfController.getTunFd(),
                mNsdPublisher,
                getMeshcopTxtAttributes(mResources.get(), mSystemProperties),
                mCountryCodeSupplier.get(),
                FeatureFlags.isTrelEnabled(),
                mOtDaemonCallbackProxy);
        otDaemon.asBinder().linkToDeath(() -> mHandler.post(this::onOtDaemonDied), 0);
        mOtDaemon = otDaemon;
        mHandler.post(mNat64CidrController::maybeUpdateNat64Cidr);
        return mOtDaemon;
    }

    static String getVendorName(Resources resources, MockableSystemProperties systemProperties) {
        final String PROP_MANUFACTURER = "ro.product.manufacturer";
        String vendorName = resources.getString(R.string.config_thread_vendor_name);
        if (vendorName.equalsIgnoreCase(PROP_MANUFACTURER)) {
            vendorName = systemProperties.get(PROP_MANUFACTURER);
            // Assume it's always ASCII chars in ro.product.manufacturer
            if (vendorName.length() > MAX_VENDOR_NAME_UTF8_BYTES) {
                vendorName = vendorName.substring(0, MAX_VENDOR_NAME_UTF8_BYTES);
            }
        }
        return vendorName;
    }

    static String getModelName(Resources resources, MockableSystemProperties systemProperties) {
        final String PROP_MODEL = "ro.product.model";
        String modelName = resources.getString(R.string.config_thread_model_name);
        if (modelName.equalsIgnoreCase(PROP_MODEL)) {
            modelName = systemProperties.get(PROP_MODEL);
            // Assume it's always ASCII chars in ro.product.model
            if (modelName.length() > MAX_MODEL_NAME_UTF8_BYTES) {
                modelName = modelName.substring(0, MAX_MODEL_NAME_UTF8_BYTES);
            }
        }
        return modelName;
    }

    @VisibleForTesting
    static MeshcopTxtAttributes getMeshcopTxtAttributes(
            Resources resources, MockableSystemProperties systemProperties) {
        final String vendorName = getVendorName(resources, systemProperties);
        final String modelName = getModelName(resources, systemProperties);
        final String vendorOui = resources.getString(R.string.config_thread_vendor_oui);
        final String[] vendorSpecificTxts =
                resources.getStringArray(R.array.config_thread_mdns_vendor_specific_txts);

        if (!modelName.isEmpty()) {
            if (modelName.getBytes(UTF_8).length > MAX_MODEL_NAME_UTF8_BYTES) {
                throw new IllegalStateException(
                        "Model name is longer than "
                                + MAX_MODEL_NAME_UTF8_BYTES
                                + "utf-8 bytes: "
                                + modelName);
            }
        }

        if (!vendorName.isEmpty()) {
            if (vendorName.getBytes(UTF_8).length > MAX_VENDOR_NAME_UTF8_BYTES) {
                throw new IllegalStateException(
                        "Vendor name is longer than "
                                + MAX_VENDOR_NAME_UTF8_BYTES
                                + " utf-8 bytes: "
                                + vendorName);
            }
        }

        if (!vendorOui.isEmpty() && !Pattern.compile(OUI_REGEX).matcher(vendorOui).matches()) {
            throw new IllegalStateException("Vendor OUI is invalid: " + vendorOui);
        }

        MeshcopTxtAttributes meshcopTxts = new MeshcopTxtAttributes();
        meshcopTxts.modelName = modelName;
        meshcopTxts.vendorName = vendorName;
        meshcopTxts.vendorOui = HexEncoding.decode(vendorOui.replace("-", "").replace(":", ""));
        meshcopTxts.nonStandardTxtEntries = makeVendorSpecificTxtAttrs(vendorSpecificTxts);

        return meshcopTxts;
    }

    /**
     * Parses vendor-specific TXT entries from "=" separated strings into list of {@link
     * DnsTxtAttribute}.
     *
     * @throws IllegalArgumentsException if invalid TXT entries are found in {@code vendorTxts}
     */
    @VisibleForTesting
    static List<DnsTxtAttribute> makeVendorSpecificTxtAttrs(String[] vendorTxts) {
        List<DnsTxtAttribute> txts = new ArrayList<>();
        for (String txt : vendorTxts) {
            String[] kv = txt.split("=", 2 /* limit */); // Split with only the first '='
            if (kv.length < 1) {
                throw new IllegalArgumentException(
                        "Invalid vendor-specific TXT is found in resources: " + txt);
            }

            if (kv[0].length() < 2) {
                throw new IllegalArgumentException(
                        "Invalid vendor-specific TXT key \""
                                + kv[0]
                                + "\": it must contain at least 2 characters");
            }

            if (!kv[0].startsWith("v")) {
                throw new IllegalArgumentException(
                        "Invalid vendor-specific TXT key \""
                                + kv[0]
                                + "\": it doesn't start with \"v\"");
            }

            txts.add(new DnsTxtAttribute(kv[0], (kv.length >= 2 ? kv[1] : "").getBytes(UTF_8)));
        }
        return txts;
    }

    private void onOtDaemonDied() {
        checkOnHandlerThread();
        LOG.w("OT daemon is dead, clean up...");

        OperationReceiverWrapper.onOtDaemonDied();
        OutputReceiverWrapper.onOtDaemonDied();
        mOtDaemonCallbackProxy.onOtDaemonDied();
        mTunIfController.onOtDaemonDied();
        mNsdPublisher.onOtDaemonDied();
        mOtDaemon = null;
        maybeInitializeOtDaemon();
    }

    public void initialize() {
        mHandler.post(() -> initializeInternal());
    }

    private void initializeInternal() {
        checkOnHandlerThread();

        LOG.v(
                "Initializing Thread system service: Thread is "
                        + (shouldEnableThread() ? "enabled" : "disabled"));
        try {
            mTunIfController.createTunInterface();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create Thread tunnel interface", e);
        }
        mNetworkFactory.initialize(this);
        mNetworkFactory.register();
        mUserRestricted = isThreadUserRestricted();
        registerUserRestrictionsReceiver();

        if (isBorderRouterMode()) {
            requestUpstreamNetwork();
            registerThreadNetworkCallback();
        } else {
            cancelRequestUpstreamNetwork();
            unregisterThreadNetworkCallback();
        }
        maybeInitializeOtDaemon();
    }

    /**
     * Force stops ot-daemon immediately and prevents ot-daemon from being restarted by
     * system_server again.
     *
     * <p>This is for VTS testing only.
     */
    @RequiresPermission(PERMISSION_THREAD_NETWORK_PRIVILEGED)
    void forceStopOtDaemonForTest(boolean enabled, @NonNull IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        mHandler.post(
                () ->
                        forceStopOtDaemonForTestInternal(
                                enabled,
                                new OperationReceiverWrapper(
                                        receiver, true /* expectOtDaemonDied */)));
    }

    private void forceStopOtDaemonForTestInternal(
            boolean enabled, @NonNull OperationReceiverWrapper receiver) {
        checkOnHandlerThread();
        if (enabled == mForceStopOtDaemonEnabled) {
            receiver.onSuccess();
            return;
        }

        if (!enabled) {
            mForceStopOtDaemonEnabled = false;
            maybeInitializeOtDaemon();
            receiver.onSuccess();
            return;
        }

        try {
            getOtDaemon().terminate();
            // Do not invoke the {@code receiver} callback here but wait for ot-daemon to
            // become dead, so that it's guaranteed that ot-daemon is stopped when {@code
            // receiver} is completed
        } catch (RemoteException e) {
            LOG.e("otDaemon.terminate failed", e);
            receiver.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        } catch (ThreadNetworkException e) {
            // No ThreadNetworkException.ERROR_THREAD_DISABLED error will be thrown
            throw new AssertionError(e);
        } finally {
            mForceStopOtDaemonEnabled = true;
        }
    }

    public void setEnabled(boolean isEnabled, @NonNull IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        mHandler.post(
                () ->
                        setEnabledInternal(
                                isEnabled,
                                true /* persist */,
                                new OperationReceiverWrapper(receiver)));
    }

    private void setEnabledInternal(
            boolean isEnabled, boolean persist, @NonNull OperationReceiverWrapper receiver) {
        checkOnHandlerThread();
        if (isEnabled && isThreadUserRestricted()) {
            receiver.onError(
                    ERROR_FAILED_PRECONDITION,
                    "Cannot enable Thread: forbidden by user restriction");
            return;
        }

        LOG.i("Set Thread enabled: " + isEnabled + ", persist: " + persist);

        if (persist) {
            // The persistent setting keeps the desired enabled state, thus it's set regardless
            // the otDaemon set enabled state operation succeeded or not, so that it can recover
            // to the desired value after reboot.
            mPersistentSettings.put(ThreadPersistentSettings.KEY_THREAD_ENABLED, isEnabled);
        }

        try {
            getOtDaemon().setThreadEnabled(isEnabled, newOtStatusReceiver(receiver));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.setThreadEnabled failed", e);
            receiver.onError(e);
        }
    }

    @Override
    public void setConfiguration(
            @NonNull ThreadConfiguration configuration, @NonNull IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);
        mHandler.post(
                () ->
                        setConfigurationInternal(
                                configuration, new OperationReceiverWrapper(receiver)));
    }

    private void setConfigurationInternal(
            @NonNull ThreadConfiguration configuration,
            @NonNull OperationReceiverWrapper receiver) {
        checkOnHandlerThread();

        LOG.i("Set Thread configuration: " + configuration);

        final boolean changed = mPersistentSettings.putConfiguration(configuration);

        if (changed) {
            if (isBorderRouterMode()) {
                requestUpstreamNetwork();
                registerThreadNetworkCallback();
            } else {
                cancelRequestUpstreamNetwork();
                unregisterThreadNetworkCallback();
                disableBorderRouting();
            }
        }

        receiver.onSuccess();

        if (changed) {
            for (IConfigurationReceiver configReceiver : mConfigurationReceivers.keySet()) {
                try {
                    configReceiver.onConfigurationChanged(configuration);
                } catch (RemoteException e) {
                    // do nothing if the client is dead
                }
            }
        }

        try {
            getOtDaemon()
                    .setConfiguration(
                            newOtDaemonConfig(configuration),
                            new LoggingOtStatusReceiver("setConfiguration"));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.setConfiguration failed. Config: " + configuration, e);
        }
        mNat64CidrController.maybeUpdateNat64Cidr();
    }

    private OtDaemonConfiguration newOtDaemonConfig(ThreadConfiguration threadConfig) {
        int srpServerConfig = R.bool.config_thread_srp_server_wait_for_border_routing_enabled;
        boolean srpServerWaitEnabled = mResources.get().getBoolean(srpServerConfig);
        int autoJoinConfig = R.bool.config_thread_border_router_auto_join_enabled;
        boolean autoJoinEnabled = mResources.get().getBoolean(autoJoinConfig);
        boolean countryCodeEnabled =
                mResources.get().getBoolean(R.bool.config_thread_country_code_enabled);
        return new OtDaemonConfiguration.Builder()
                .setBorderRouterEnabled(threadConfig.isBorderRouterEnabled())
                .setNat64Enabled(threadConfig.isNat64Enabled())
                .setDhcpv6PdEnabled(threadConfig.isDhcpv6PdEnabled())
                .setSrpServerWaitForBorderRoutingEnabled(srpServerWaitEnabled)
                .setBorderRouterAutoJoinEnabled(autoJoinEnabled)
                .setCountryCodeEnabled(countryCodeEnabled)
                .setVendorName(getVendorName(mResources.get(), mSystemProperties))
                .setModelName(getModelName(mResources.get(), mSystemProperties))
                .build();
    }

    /** Returns {@code true} if this device is operating as a border router. */
    private boolean isBorderRouterMode() {
        return mPersistentSettings.getConfiguration().isBorderRouterEnabled();
    }

    @Override
    public void registerConfigurationCallback(@NonNull IConfigurationReceiver callback) {
        enforceAllPermissionsGranted(permission.THREAD_NETWORK_PRIVILEGED);
        mHandler.post(() -> registerConfigurationCallbackInternal(callback));
    }

    private void registerConfigurationCallbackInternal(@NonNull IConfigurationReceiver callback) {
        checkOnHandlerThread();
        if (mConfigurationReceivers.containsKey(callback)) {
            throw new IllegalStateException("Registering the same IConfigurationReceiver twice");
        }
        IBinder.DeathRecipient deathRecipient =
                () -> mHandler.post(() -> unregisterConfigurationCallbackInternal(callback));
        try {
            callback.asBinder().linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            return;
        }
        mConfigurationReceivers.put(callback, deathRecipient);
        try {
            callback.onConfigurationChanged(mPersistentSettings.getConfiguration());
        } catch (RemoteException e) {
            // do nothing if the client is dead
        }
    }

    @Override
    public void unregisterConfigurationCallback(@NonNull IConfigurationReceiver callback) {
        enforceAllPermissionsGranted(permission.THREAD_NETWORK_PRIVILEGED);
        mHandler.post(() -> unregisterConfigurationCallbackInternal(callback));
    }

    private void unregisterConfigurationCallbackInternal(@NonNull IConfigurationReceiver callback) {
        checkOnHandlerThread();
        if (!mConfigurationReceivers.containsKey(callback)) {
            return;
        }
        callback.asBinder().unlinkToDeath(mConfigurationReceivers.remove(callback), 0);
    }

    private void registerUserRestrictionsReceiver() {
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        onUserRestrictionsChanged(isThreadUserRestricted());
                    }
                },
                new IntentFilter(UserManager.ACTION_USER_RESTRICTIONS_CHANGED),
                null /* broadcastPermission */,
                mHandler);
    }

    private void onUserRestrictionsChanged(boolean newUserRestrictedState) {
        checkOnHandlerThread();
        if (mUserRestricted == newUserRestrictedState) {
            return;
        }
        LOG.i(
                "Thread user restriction changed: "
                        + mUserRestricted
                        + " -> "
                        + newUserRestrictedState);
        mUserRestricted = newUserRestrictedState;

        final boolean shouldEnableThread = shouldEnableThread();
        final IOperationReceiver receiver =
                new IOperationReceiver.Stub() {
                    @Override
                    public void onSuccess() {
                        LOG.v(
                                (shouldEnableThread ? "Enabled" : "Disabled")
                                        + " Thread due to user restriction change");
                    }

                    @Override
                    public void onError(int errorCode, String errorMessage) {
                        LOG.e(
                                "Failed to "
                                        + (shouldEnableThread ? "enable" : "disable")
                                        + " Thread for user restriction change");
                    }
                };
        // Do not save the user restriction state to persistent settings so that the user
        // configuration won't be overwritten
        setEnabledInternal(
                shouldEnableThread, false /* persist */, new OperationReceiverWrapper(receiver));
    }

    /** Returns {@code true} if Thread has been restricted for the user. */
    private boolean isThreadUserRestricted() {
        return mUserManager.hasUserRestriction(DISALLOW_THREAD_NETWORK);
    }

    /**
     * Returns {@code true} if Thread should be enabled based on current settings, runtime user
     * restriction state.
     */
    private boolean shouldEnableThread() {
        return !mForceStopOtDaemonEnabled
                && !mUserRestricted
                && mPersistentSettings.get(ThreadPersistentSettings.KEY_THREAD_ENABLED);
    }

    private void requestUpstreamNetwork() {
        if (mUpstreamNetworkCallback != null) {
            return;
        }
        mUpstreamNetworkCallback = new UpstreamNetworkCallback();
        mConnectivityManager.registerNetworkCallback(
                mUpstreamNetworkRequest, mUpstreamNetworkCallback, mHandler);
    }

    private void cancelRequestUpstreamNetwork() {
        if (mUpstreamNetworkCallback == null) {
            return;
        }
        mNetworkToLinkProperties.clear();
        mConnectivityManager.unregisterNetworkCallback(mUpstreamNetworkCallback);
        mUpstreamNetworkCallback = null;
    }

    private final class UpstreamNetworkCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(@NonNull Network network) {
            checkOnHandlerThread();
            LOG.i("Upstream network available: " + network);
        }

        @Override
        public void onLost(@NonNull Network network) {
            checkOnHandlerThread();
            LOG.i("Upstream network lost: " + network);

            // TODO: disable border routing when upsteam network disconnected
        }

        @Override
        public void onLinkPropertiesChanged(
                @NonNull Network network, @NonNull LinkProperties newLinkProperties) {
            checkOnHandlerThread();

            LinkProperties oldLinkProperties = mNetworkToLinkProperties.get(network);
            if (Objects.equals(oldLinkProperties, newLinkProperties)) {
                return;
            }
            LOG.i("Upstream network changed: " + oldLinkProperties + " -> " + newLinkProperties);
            mNetworkToLinkProperties.put(network, newLinkProperties);

            // TODO: disable border routing if netIfName is null
            if (network.equals(mUpstreamNetwork)) {
                setInfraLinkState(newInfraLinkStateBuilder(newLinkProperties).build());
            }
        }
    }

    private final class ThreadNetworkCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(@NonNull Network network) {
            checkOnHandlerThread();
            LOG.i("Thread network is available: " + network);
        }

        @Override
        public void onLost(@NonNull Network network) {
            checkOnHandlerThread();
            LOG.i("Thread network is lost: " + network);
            setInfraLinkState(newInfraLinkStateBuilder().build());
        }

        @Override
        public void onLocalNetworkInfoChanged(
                @NonNull Network network, @NonNull LocalNetworkInfo localNetworkInfo) {
            checkOnHandlerThread();
            LOG.i(
                    "LocalNetworkInfo of Thread network changed: {threadNetwork: "
                            + network
                            + ", localNetworkInfo: "
                            + localNetworkInfo
                            + "}");
            mUpstreamNetwork = localNetworkInfo.getUpstreamNetwork();
            if (mUpstreamNetwork == null) {
                setInfraLinkState(newInfraLinkStateBuilder().build());
                return;
            }
            if (mNetworkToLinkProperties.containsKey(mUpstreamNetwork)) {
                setInfraLinkState(
                        newInfraLinkStateBuilder(mNetworkToLinkProperties.get(mUpstreamNetwork))
                                .build());
            }
            mNsdPublisher.setNetworkForHostResolution(mUpstreamNetwork);
        }
    }

    private void registerThreadNetworkCallback() {
        if (mThreadNetworkCallback != null) {
            return;
        }

        mThreadNetworkCallback = new ThreadNetworkCallback();
        NetworkRequest request =
                new NetworkRequest.Builder()
                        // clearCapabilities() is needed to remove forbidden capabilities and UID
                        // requirement.
                        .clearCapabilities()
                        .addTransportType(TRANSPORT_THREAD)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                        .build();
        mConnectivityManager.registerNetworkCallback(request, mThreadNetworkCallback, mHandler);
    }

    private void unregisterThreadNetworkCallback() {
        if (mThreadNetworkCallback == null) {
            return;
        }
        mConnectivityManager.unregisterNetworkCallback(mThreadNetworkCallback);
        mThreadNetworkCallback = null;
    }

    /** Injects a {@link NetworkAgent} for testing. */
    @VisibleForTesting
    void setTestNetworkAgent(@Nullable NetworkAgent testNetworkAgent) {
        mTestNetworkAgent = testNetworkAgent;
    }

    private NetworkCapabilities computeNetworkCapabilities(
            @Nullable ActiveOperationalDataset activeDataset) {
        final var netCapsBuilder =
                new NetworkCapabilities.Builder()
                        .addTransportType(TRANSPORT_THREAD)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK);
        if (activeDataset != null) {
            boolean createPartition =
                    (mJoinNetworkSpecifier != null)
                            ? mJoinNetworkSpecifier.shouldCreatePartitionIfNotFound()
                            : false;
            final var actualSpecifier =
                    new ThreadNetworkSpecifier.Builder()
                            .setActiveOperationalDataset(activeDataset)
                            .setShouldCreatePartitionIfNotFound(createPartition)
                            .build();
            netCapsBuilder.setNetworkSpecifier(actualSpecifier);
        }
        return netCapsBuilder.build();
    }

    private NetworkAgent newNetworkAgent() {
        if (mTestNetworkAgent != null) {
            return mTestNetworkAgent;
        }

        final var scoreBuilder = new NetworkScore.Builder();
        scoreBuilder.setKeepConnectedReason(NetworkScore.KEEP_CONNECTED_LOCAL_NETWORK);

        return new NetworkAgent(
                mContext,
                mHandler.getLooper(),
                LOG.getTag(),
                computeNetworkCapabilities(mOtDaemonCallbackProxy.mActiveDataset),
                getTunIfLinkProperties(),
                newLocalNetworkConfig(),
                scoreBuilder.build(),
                new NetworkAgentConfig.Builder().build(),
                mNetworkFactory.getProvider()) {

            // TODO(b/374037595): use NetworkFactory to handle dynamic network requests
            @Override
            public void onNetworkUnwanted() {
                LOG.i("Thread network is unwanted by ConnectivityService");
                // TODO(b/374037595): leave() the current network when the new APIs for mobile
                // is available
            }
        };
    }

    private LocalNetworkConfig newLocalNetworkConfig() {
        return new LocalNetworkConfig.Builder()
                .setUpstreamMulticastRoutingConfig(mUpstreamMulticastRoutingConfig)
                .setDownstreamMulticastRoutingConfig(mDownstreamMulticastRoutingConfig)
                .setUpstreamSelector(mUpstreamNetworkRequest)
                .build();
    }

    private void registerThreadNetwork() {
        if (mNetworkAgent != null) {
            return;
        }

        mNetworkAgent = newNetworkAgent();
        mNetworkAgent.register();
        mNetworkAgent.markConnected();
        LOG.i("Registered Thread network");
    }

    private void unregisterThreadNetwork() {
        if (mNetworkAgent == null) {
            // unregisterThreadNetwork can be called every time this device becomes detached or
            // disabled and the mNetworkAgent may not be created in this cases
            return;
        }

        LOG.v("Unregistering Thread network agent");

        mNetworkAgent.unregister();
        mNetworkAgent = null;
    }

    @Override
    public int getThreadVersion() {
        return THREAD_VERSION_1_3;
    }

    @Override
    public void activateEphemeralKeyMode(long lifetimeMillis, IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        mHandler.post(
                () ->
                        activateEphemeralKeyModeInternal(
                                lifetimeMillis, new OperationReceiverWrapper(receiver)));
    }

    private void activateEphemeralKeyModeInternal(
            long lifetimeMillis, OperationReceiverWrapper receiver) {
        checkOnHandlerThread();

        if (!isBorderRouterMode()) {
            receiver.onError(
                    ERROR_FAILED_PRECONDITION, "This device is not configured a Border Router");
            return;
        }

        try {
            getOtDaemon().activateEphemeralKeyMode(lifetimeMillis, newOtStatusReceiver(receiver));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.activateEphemeralKeyMode failed", e);
            receiver.onError(e);
        }
    }

    @Override
    public void deactivateEphemeralKeyMode(IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        mHandler.post(
                () -> deactivateEphemeralKeyModeInternal(new OperationReceiverWrapper(receiver)));
    }

    private void deactivateEphemeralKeyModeInternal(OperationReceiverWrapper receiver) {
        checkOnHandlerThread();

        if (!isBorderRouterMode()) {
            receiver.onError(
                    ERROR_FAILED_PRECONDITION, "This device is not configured a Border Router");
            return;
        }

        try {
            getOtDaemon().deactivateEphemeralKeyMode(newOtStatusReceiver(receiver));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.deactivateEphemeralKeyMode failed", e);
            receiver.onError(e);
        }
    }

    @Override
    public void createRandomizedDataset(
            String networkName, IActiveOperationalDatasetReceiver receiver) {
        ActiveOperationalDatasetReceiverWrapper receiverWrapper =
                new ActiveOperationalDatasetReceiverWrapper(receiver);
        mHandler.post(() -> createRandomizedDatasetInternal(networkName, receiverWrapper));
    }

    private void createRandomizedDatasetInternal(
            String networkName, @NonNull ActiveOperationalDatasetReceiverWrapper receiver) {
        checkOnHandlerThread();

        try {
            getOtDaemon().getChannelMasks(newChannelMasksReceiver(networkName, receiver));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.getChannelMasks failed", e);
            receiver.onError(e);
        }
    }

    private IChannelMasksReceiver newChannelMasksReceiver(
            String networkName, ActiveOperationalDatasetReceiverWrapper receiver) {
        return new IChannelMasksReceiver.Stub() {
            @Override
            public void onSuccess(int supportedChannelMask, int preferredChannelMask) {
                ActiveOperationalDataset dataset =
                        createRandomizedDataset(
                                networkName,
                                supportedChannelMask,
                                preferredChannelMask,
                                new Random(),
                                new SecureRandom());

                receiver.onSuccess(dataset);
            }

            @Override
            public void onError(int errorCode, String errorMessage) {
                receiver.onError(otErrorToAndroidError(errorCode), errorMessage);
            }
        };
    }

    private static ActiveOperationalDataset createRandomizedDataset(
            String networkName,
            int supportedChannelMask,
            int preferredChannelMask,
            Random random,
            SecureRandom secureRandom) {
        boolean authoritative = false;
        Instant now = Instant.now();
        try {
            Clock clock = SystemClock.currentNetworkTimeClock();
            now = clock.instant();
            authoritative = true;
        } catch (DateTimeException e) {
            LOG.w("Failed to get authoritative time: " + e.getMessage());
        }

        int panId = random.nextInt(/* bound= */ 0xffff);
        final byte[] meshLocalPrefix = newRandomBytes(random, LENGTH_MESH_LOCAL_PREFIX_BITS / 8);
        meshLocalPrefix[0] = MESH_LOCAL_PREFIX_FIRST_BYTE;

        final SparseArray<byte[]> channelMask = new SparseArray<>(1);
        channelMask.put(CHANNEL_PAGE_24_GHZ, channelMaskToByteArray(supportedChannelMask));
        final int channel = selectChannel(supportedChannelMask, preferredChannelMask, random);

        final byte[] securityFlags = new byte[] {(byte) 0xff, (byte) 0xf8};

        return new ActiveOperationalDataset.Builder()
                .setActiveTimestamp(OperationalDatasetTimestamp.fromInstant(now, authoritative))
                .setExtendedPanId(newRandomBytes(random, LENGTH_EXTENDED_PAN_ID))
                .setPanId(panId)
                .setNetworkName(networkName)
                .setChannel(CHANNEL_PAGE_24_GHZ, channel)
                .setChannelMask(channelMask)
                .setPskc(newRandomBytes(secureRandom, LENGTH_PSKC))
                .setNetworkKey(newRandomBytes(secureRandom, LENGTH_NETWORK_KEY))
                .setMeshLocalPrefix(meshLocalPrefix)
                .setSecurityPolicy(new SecurityPolicy(DEFAULT_ROTATION_TIME_HOURS, securityFlags))
                .build();
    }

    private static int selectChannel(
            int supportedChannelMask, int preferredChannelMask, Random random) {
        // Due to radio hardware performance reasons, many Thread radio chips need to reduce their
        // transmit power on edge channels to pass regulatory RF certification. Thread edge channel
        // 25 and 26 are not preferred here.
        //
        // If users want to use channel 25 or 26, they can change the channel via the method
        // ActiveOperationalDataset.Builder(activeOperationalDataset).setChannel(channel).build().
        preferredChannelMask = preferredChannelMask & CHANNEL_MASK_11_TO_24;

        // If the preferred channel mask is not empty, select a random channel from it, otherwise
        // choose one from the supported channel mask.
        preferredChannelMask = preferredChannelMask & supportedChannelMask;
        if (preferredChannelMask == 0) {
            preferredChannelMask = supportedChannelMask;
        }

        return selectRandomChannel(preferredChannelMask, random);
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

    private void enforceAllPermissionsGranted(String... permissions) {
        for (String permission : permissions) {
            mContext.enforceCallingOrSelfPermission(
                    permission, "Permission " + permission + " is missing");
        }
    }

    @Override
    public void registerStateCallback(IStateCallback stateCallback) throws RemoteException {
        enforceAllPermissionsGranted(permission.ACCESS_NETWORK_STATE);
        boolean hasThreadPrivilegedPermission =
                (mContext.checkCallingOrSelfPermission(PERMISSION_THREAD_NETWORK_PRIVILEGED)
                        == PERMISSION_GRANTED);

        mHandler.post(
                () ->
                        mOtDaemonCallbackProxy.registerStateCallback(
                                stateCallback, hasThreadPrivilegedPermission));
    }

    @Override
    public void unregisterStateCallback(IStateCallback stateCallback) throws RemoteException {
        enforceAllPermissionsGranted(permission.ACCESS_NETWORK_STATE);
        mHandler.post(() -> mOtDaemonCallbackProxy.unregisterStateCallback(stateCallback));
    }

    @Override
    public void registerOperationalDatasetCallback(IOperationalDatasetCallback callback)
            throws RemoteException {
        enforceAllPermissionsGranted(
                permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        mHandler.post(() -> mOtDaemonCallbackProxy.registerDatasetCallback(callback));
    }

    @Override
    public void unregisterOperationalDatasetCallback(IOperationalDatasetCallback callback)
            throws RemoteException {
        enforceAllPermissionsGranted(
                permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        mHandler.post(() -> mOtDaemonCallbackProxy.unregisterDatasetCallback(callback));
    }

    private void checkOnHandlerThread() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            throw new IllegalStateException(
                    "Not running on ThreadNetworkControllerService thread ("
                            + mHandler.getLooper()
                            + ") : "
                            + Looper.myLooper());
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

    private IOtStatusReceiver newOtStatusReceiver(
            OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        return new IOtStatusReceiver.Stub() {
            @Override
            public void onSuccess() {
                receiver.onResult(null);
            }

            @Override
            public void onError(int otError, String message) {
                receiver.onError(
                        new ThreadNetworkException(otErrorToAndroidError(otError), message));
            }
        };
    }

    private IOtOutputReceiver newOtOutputReceiver(OutputReceiverWrapper receiver) {
        return new IOtOutputReceiver.Stub() {
            @Override
            public void onOutput(String output) {
                receiver.onOutput(output);
            }

            @Override
            public void onComplete() {
                receiver.onComplete();
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
            case OT_ERROR_NOT_IMPLEMENTED:
                return ERROR_UNSUPPORTED_FEATURE;
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
            case OT_ERROR_THREAD_DISABLED:
                return ERROR_THREAD_DISABLED;
            case OT_ERROR_FAILED_PRECONDITION:
                return ERROR_FAILED_PRECONDITION;
            case OT_ERROR_INVALID_STATE:
            default:
                return ERROR_INTERNAL_ERROR;
        }
    }

    @Override
    public void join(
            @NonNull ActiveOperationalDataset activeDataset, @NonNull IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        OperationReceiverWrapper receiverWrapper = new OperationReceiverWrapper(receiver);
        mHandler.post(() -> joinInternal(activeDataset, receiverWrapper));
    }

    private void joinInternal(
            @NonNull ActiveOperationalDataset activeDataset,
            @NonNull OperationReceiverWrapper receiver) {
        checkOnHandlerThread();

        mJoinNetworkSpecifier = null;
        try {
            // The otDaemon.join() will leave first if this device is currently attached
            getOtDaemon()
                    .join(
                            activeDataset.toThreadTlvs(),
                            true /* createPartitionIfNotFound */,
                            newOtStatusReceiver(receiver));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.join failed", e);
            receiver.onError(e);
        }
    }

    void joinWithSpecifier(
            ThreadNetworkSpecifier specifier,
            OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        mHandler.post(() -> joinWithSpecifierInternal(specifier, receiver));
    }

    private void joinWithSpecifierInternal(
            ThreadNetworkSpecifier specifier,
            OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        checkOnHandlerThread();

        mJoinNetworkSpecifier = specifier;
        byte[] dataset = requireNonNull(specifier.getActiveOperationalDataset()).toThreadTlvs();
        boolean createPartitionIfNotFound = specifier.shouldCreatePartitionIfNotFound();
        try {
            getOtDaemon().join(dataset, createPartitionIfNotFound, newOtStatusReceiver(receiver));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.joinWithSpecifier failed", e);
            if (e instanceof RemoteException re) {
                receiver.onError(new ThreadNetworkException(ERROR_INTERNAL_ERROR, re.getMessage()));
            } else {
                receiver.onError((ThreadNetworkException) e);
            }
        }
    }

    @Override
    public void scheduleMigration(
            @NonNull PendingOperationalDataset pendingDataset,
            @NonNull IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

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
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.scheduleMigration failed", e);
            receiver.onError(e);
        }
    }

    @Override
    public void leave(@NonNull IOperationReceiver receiver) {
        leave(true /* eraseDataset */, receiver);
    }

    private void leave(boolean eraseDataset, @NonNull IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        mHandler.post(() -> leaveInternal(eraseDataset, new OperationReceiverWrapper(receiver)));
    }

    private void leaveInternal(boolean eraseDataset, @NonNull OperationReceiverWrapper receiver) {
        checkOnHandlerThread();

        try {
            getOtDaemon().leave(eraseDataset, newOtStatusReceiver(receiver));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.leave failed", e);
            receiver.onError(e);
        }
    }

    /**
     * Sets the country code.
     *
     * @param countryCode 2 characters string country code (as defined in ISO 3166) to set.
     * @param receiver the receiver to receive result of this operation
     */
    @RequiresPermission(PERMISSION_THREAD_NETWORK_PRIVILEGED)
    public void setCountryCode(@NonNull String countryCode, @NonNull IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        OperationReceiverWrapper receiverWrapper = new OperationReceiverWrapper(receiver);
        mHandler.post(() -> setCountryCodeInternal(countryCode, receiverWrapper));
    }

    private void setCountryCodeInternal(
            String countryCode, @NonNull OperationReceiverWrapper receiver) {
        checkOnHandlerThread();

        // Fails early to avoid waking up ot-daemon by the ThreadNetworkCountryCode class
        if (!shouldEnableThread()) {
            receiver.onError(
                    ERROR_THREAD_DISABLED, "Can't set country code when Thread is disabled");
            return;
        }

        try {
            getOtDaemon().setCountryCode(countryCode, newOtStatusReceiver(receiver));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.setCountryCode failed", e);
            receiver.onError(e);
        }
    }

    @Override
    public void setTestNetworkAsUpstream(
            @Nullable String testNetworkInterfaceName, @NonNull IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED, NETWORK_SETTINGS);

        LOG.i("setTestNetworkAsUpstream: " + testNetworkInterfaceName);
        mHandler.post(() -> setTestNetworkAsUpstreamInternal(testNetworkInterfaceName, receiver));
    }

    private void setTestNetworkAsUpstreamInternal(
            @Nullable String testNetworkInterfaceName, @NonNull IOperationReceiver receiver) {
        checkOnHandlerThread();

        TestNetworkSpecifier testNetworkSpecifier = null;
        if (testNetworkInterfaceName != null) {
            testNetworkSpecifier = new TestNetworkSpecifier(testNetworkInterfaceName);
        }

        if (!Objects.equals(mUpstreamTestNetworkSpecifier, testNetworkSpecifier)) {
            cancelRequestUpstreamNetwork();
            mUpstreamTestNetworkSpecifier = testNetworkSpecifier;
            mUpstreamNetworkRequest = newUpstreamNetworkRequest();
            requestUpstreamNetwork();
            sendLocalNetworkConfig();
        }
        try {
            receiver.onSuccess();
        } catch (RemoteException ignored) {
            // do nothing if the client is dead
        }
    }

    @RequiresPermission(PERMISSION_THREAD_NETWORK_PRIVILEGED)
    public void setChannelMaxPowers(
            @NonNull ChannelMaxPower[] channelMaxPowers, @NonNull IOperationReceiver receiver) {
        enforceAllPermissionsGranted(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        mHandler.post(
                () ->
                        setChannelMaxPowersInternal(
                                channelMaxPowers, new OperationReceiverWrapper(receiver)));
    }

    private void setChannelMaxPowersInternal(
            @NonNull ChannelMaxPower[] channelMaxPowers,
            @NonNull OperationReceiverWrapper receiver) {
        checkOnHandlerThread();

        try {
            getOtDaemon().setChannelMaxPowers(channelMaxPowers, newOtStatusReceiver(receiver));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.setChannelMaxPowers failed", e);
            receiver.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
    }

    private void setInfraLinkState(InfraLinkState newInfraLinkState) {
        if (Objects.equals(mInfraLinkState, newInfraLinkState)) {
            return;
        }
        LOG.i("Infra link state changed: " + mInfraLinkState + " -> " + newInfraLinkState);
        setInfraLinkInterfaceName(newInfraLinkState.interfaceName);
        setInfraLinkNat64Prefix(newInfraLinkState.nat64Prefix);
        setInfraLinkDnsServers(newInfraLinkState.dnsServers);
        mInfraLinkState = newInfraLinkState;
    }

    private void setInfraLinkInterfaceName(String newInfraLinkInterfaceName) {
        if (Objects.equals(mInfraLinkState.interfaceName, newInfraLinkInterfaceName)) {
            return;
        }
        ParcelFileDescriptor infraIcmp6Socket = null;
        if (newInfraLinkInterfaceName != null) {
            try {
                infraIcmp6Socket = mInfraIfController.createIcmp6Socket(newInfraLinkInterfaceName);
            } catch (IOException e) {
                LOG.e("Failed to create ICMPv6 socket on infra network interface", e);
            }
        }
        try {
            getOtDaemon()
                    .setInfraLinkInterfaceName(
                            newInfraLinkInterfaceName,
                            infraIcmp6Socket,
                            new LoggingOtStatusReceiver("setInfraLinkInterfaceName"));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("Failed to set infra link interface name " + newInfraLinkInterfaceName, e);
        }
    }

    private void setInfraLinkNat64Prefix(@Nullable String newNat64Prefix) {
        if (Objects.equals(newNat64Prefix, mInfraLinkState.nat64Prefix)) {
            return;
        }
        try {
            getOtDaemon()
                    .setInfraLinkNat64Prefix(
                            newNat64Prefix, new LoggingOtStatusReceiver("setInfraLinkNat64Prefix"));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("Failed to set infra link NAT64 prefix " + newNat64Prefix, e);
        }
    }

    private void setInfraLinkDnsServers(List<String> newDnsServers) {
        if (Objects.equals(newDnsServers, mInfraLinkState.dnsServers)) {
            return;
        }
        try {
            getOtDaemon()
                    .setInfraLinkDnsServers(
                            newDnsServers, new LoggingOtStatusReceiver("setInfraLinkDnsServers"));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("Failed to set infra link DNS servers " + newDnsServers, e);
        }
    }

    private void disableBorderRouting() {
        LOG.i("Disabling border routing");
        setInfraLinkState(newInfraLinkStateBuilder().build());
    }

    private void handleActiveDatasetChanged(@Nullable ActiveOperationalDataset newActiveDataset) {
        if (mNetworkAgent == null) {
            return;
        }
        mNetworkAgent.sendNetworkCapabilities(computeNetworkCapabilities(newActiveDataset));
    }

    private void handleThreadInterfaceStateChanged(boolean isUp) {
        try {
            mTunIfController.setInterfaceUp(isUp);
            LOG.i("Thread TUN interface becomes " + (isUp ? "up" : "down"));
        } catch (IOException e) {
            LOG.e("Failed to handle Thread interface state changes", e);
        }
    }

    private void handleDeviceRoleChanged(@DeviceRole int deviceRole) {
        if (ThreadNetworkController.isAttached(deviceRole)) {
            LOG.i("Attached to the Thread network");

            // This is an idempotent method which can be called for multiple times when the device
            // is already attached (e.g. going from Child to Router)
            registerThreadNetwork();
        } else {
            LOG.i("Detached from the Thread network");

            // This is an idempotent method which can be called for multiple times when the device
            // is already detached or stopped
            unregisterThreadNetwork();
        }
    }

    private void handleAddressChanged(List<Ipv6AddressInfo> addressInfoList) {
        checkOnHandlerThread();

        mTunIfController.updateAddresses(addressInfoList);

        // The OT daemon can send link property updates before the networkAgent is
        // registered
        maybeSendLinkProperties();
    }

    private void handlePrefixChanged(List<OnMeshPrefixConfig> onMeshPrefixConfigList) {
        checkOnHandlerThread();

        mTunIfController.updatePrefixes(onMeshPrefixConfigList);

        // The OT daemon can send link property updates before the networkAgent is
        // registered
        maybeSendLinkProperties();
    }

    private void maybeSendLinkProperties() {
        if (mNetworkAgent == null) {
            return;
        }
        mNetworkAgent.sendLinkProperties(getTunIfLinkProperties());
    }

    private LinkProperties getTunIfLinkProperties() {
        return mTunIfController.getLinkPropertiesWithNat64Cidr(mNat64CidrController.mNat64Cidr);
    }

    @RequiresPermission(
            allOf = {PERMISSION_THREAD_NETWORK_PRIVILEGED, PERMISSION_THREAD_NETWORK_TESTING})
    public void runOtCtlCommand(
            @NonNull String command, boolean isInteractive, @NonNull IOutputReceiver receiver) {
        enforceAllPermissionsGranted(
                PERMISSION_THREAD_NETWORK_PRIVILEGED, PERMISSION_THREAD_NETWORK_TESTING);

        mHandler.post(
                () ->
                        runOtCtlCommandInternal(
                                command, isInteractive, new OutputReceiverWrapper(receiver)));
    }

    private void runOtCtlCommandInternal(
            String command, boolean isInteractive, @NonNull OutputReceiverWrapper receiver) {
        checkOnHandlerThread();

        try {
            getOtDaemon().runOtCtlCommand(command, isInteractive, newOtOutputReceiver(receiver));
        } catch (RemoteException | ThreadNetworkException e) {
            LOG.e("otDaemon.runOtCtlCommand failed", e);
            receiver.onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        }
    }

    private void sendLocalNetworkConfig() {
        if (mNetworkAgent == null) {
            return;
        }
        final LocalNetworkConfig localNetworkConfig = newLocalNetworkConfig();
        mNetworkAgent.sendLocalNetworkConfig(localNetworkConfig);
        LOG.v("Sent localNetworkConfig: " + localNetworkConfig);
    }

    private void handleMulticastForwardingChanged(BackboneRouterState state) {
        MulticastRoutingConfig upstreamMulticastRoutingConfig;
        MulticastRoutingConfig downstreamMulticastRoutingConfig;

        if (state.multicastForwardingEnabled) {
            // When multicast forwarding is enabled, setup upstream forwarding to any address
            // with minimal scope 4
            // setup downstream forwarding with addresses subscribed from Thread network
            upstreamMulticastRoutingConfig =
                    new MulticastRoutingConfig.Builder(FORWARD_WITH_MIN_SCOPE, 4).build();
            downstreamMulticastRoutingConfig =
                    buildDownstreamMulticastRoutingConfigSelected(state.listeningAddresses);
        } else {
            // When multicast forwarding is disabled, set both upstream and downstream
            // forwarding config to FORWARD_NONE.
            upstreamMulticastRoutingConfig = CONFIG_FORWARD_NONE;
            downstreamMulticastRoutingConfig = CONFIG_FORWARD_NONE;
        }

        if (upstreamMulticastRoutingConfig.equals(mUpstreamMulticastRoutingConfig)
                && downstreamMulticastRoutingConfig.equals(mDownstreamMulticastRoutingConfig)) {
            return;
        }

        mUpstreamMulticastRoutingConfig = upstreamMulticastRoutingConfig;
        mDownstreamMulticastRoutingConfig = downstreamMulticastRoutingConfig;
        sendLocalNetworkConfig();
    }

    private MulticastRoutingConfig buildDownstreamMulticastRoutingConfigSelected(
            List<String> listeningAddresses) {
        MulticastRoutingConfig.Builder builder =
                new MulticastRoutingConfig.Builder(FORWARD_SELECTED);
        for (String addressStr : listeningAddresses) {
            Inet6Address address = (Inet6Address) InetAddresses.parseNumericAddress(addressStr);
            builder.addListeningAddress(address);
        }
        return builder.build();
    }

    private static InfraLinkState.Builder newInfraLinkStateBuilder() {
        return new InfraLinkState.Builder().setInterfaceName("");
    }

    private static InfraLinkState.Builder newInfraLinkStateBuilder(
            @Nullable LinkProperties linkProperties) {
        if (linkProperties == null) {
            return newInfraLinkStateBuilder();
        }
        String nat64Prefix = null;
        if (linkProperties.getNat64Prefix() != null) {
            nat64Prefix = linkProperties.getNat64Prefix().toString();
        }
        return new InfraLinkState.Builder()
                .setInterfaceName(linkProperties.getInterfaceName())
                .setNat64Prefix(nat64Prefix)
                .setDnsServers(addressesToStrings(linkProperties.getDnsServers()));
    }

    private static List<String> addressesToStrings(List<InetAddress> addresses) {
        List<String> strings = new ArrayList<>();

        for (InetAddress address : addresses) {
            strings.add(address.getHostAddress());
        }
        return strings;
    }

    private static final class CallbackMetadata {
        private static long gId = 0;

        // The unique ID
        final long id;

        final IBinder.DeathRecipient deathRecipient;

        final boolean hasThreadPrivilegedPermission;

        CallbackMetadata(
                IBinder.DeathRecipient deathRecipient, boolean hasThreadPrivilegedPermission) {
            this.id = allocId();
            this.deathRecipient = deathRecipient;
            this.hasThreadPrivilegedPermission = hasThreadPrivilegedPermission;
        }

        private static long allocId() {
            if (gId == Long.MAX_VALUE) {
                gId = 0;
            }
            return gId++;
        }
    }

    /** An implementation of {@link IOperationReceiver} that simply logs the operation result. */
    private static class LoggingOperationReceiver extends IOperationReceiver.Stub {
        private final String mOperation;

        LoggingOperationReceiver(String operation) {
            mOperation = operation;
        }

        @Override
        public void onSuccess() {
            LOG.i("The operation " + mOperation + " succeeded");
        }

        @Override
        public void onError(int errorCode, String errorMessage) {
            LOG.w("The operation " + mOperation + " failed: " + errorCode + " " + errorMessage);
        }
    }

    private static class LoggingOtStatusReceiver extends IOtStatusReceiver.Stub {
        private final String mAction;

        LoggingOtStatusReceiver(String action) {
            mAction = action;
        }

        @Override
        public void onSuccess() {
            LOG.i("The action " + mAction + " succeeded");
        }

        @Override
        public void onError(int i, String s) {
            LOG.w("The action " + mAction + " failed: " + i + " " + s);
        }
    }

    /**
     * Handles and forwards Thread daemon callbacks. This class must be accessed from the thread of
     * {@code mHandler}.
     */
    private final class OtDaemonCallbackProxy extends IOtDaemonCallback.Stub {
        private final Map<IStateCallback, CallbackMetadata> mStateCallbacks = new HashMap<>();
        private final Map<IOperationalDatasetCallback, CallbackMetadata> mOpDatasetCallbacks =
                new HashMap<>();

        private OtDaemonState mState;
        private ActiveOperationalDataset mActiveDataset;
        private PendingOperationalDataset mPendingDataset;

        public void registerStateCallback(
                IStateCallback callback, boolean hasThreadPrivilegedPermission) {
            checkOnHandlerThread();
            if (mStateCallbacks.containsKey(callback)) {
                throw new IllegalStateException("Registering the same IStateCallback twice");
            }

            IBinder.DeathRecipient deathRecipient =
                    () -> mHandler.post(() -> unregisterStateCallback(callback));
            CallbackMetadata callbackMetadata =
                    new CallbackMetadata(deathRecipient, hasThreadPrivilegedPermission);
            mStateCallbacks.put(callback, callbackMetadata);
            try {
                callback.asBinder().linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                mStateCallbacks.remove(callback);
                // This is thrown when the client is dead, do nothing
            }

            try {
                getOtDaemon().registerStateCallback(this, callbackMetadata.id);
            } catch (RemoteException | ThreadNetworkException e) {
                LOG.e("otDaemon.registerStateCallback failed", e);
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
            CallbackMetadata callbackMetadata =
                    new CallbackMetadata(deathRecipient, true /* hasThreadPrivilegedPermission */);
            mOpDatasetCallbacks.put(callback, callbackMetadata);
            try {
                callback.asBinder().linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                mOpDatasetCallbacks.remove(callback);
            }

            try {
                getOtDaemon().registerStateCallback(this, callbackMetadata.id);
            } catch (RemoteException | ThreadNetworkException e) {
                LOG.e("otDaemon.registerStateCallback failed", e);
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

            final int deviceRole = mState.deviceRole;
            mState = null;

            // If this device is already STOPPED or DETACHED, do nothing
            if (!ThreadNetworkController.isAttached(deviceRole)) {
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
            mInfraLinkState = newInfraLinkStateBuilder().build();
        }

        private void onThreadEnabledChanged(int state, long listenerId) {
            checkOnHandlerThread();
            boolean stateChanged = (mState == null || mState.threadEnabled != state);

            for (var callbackEntry : mStateCallbacks.entrySet()) {
                if (!stateChanged && callbackEntry.getValue().id != listenerId) {
                    continue;
                }
                try {
                    callbackEntry.getKey().onThreadEnableStateChanged(otStateToAndroidState(state));
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
        }

        private static int otStateToAndroidState(int state) {
            switch (state) {
                case OT_STATE_ENABLED:
                    return STATE_ENABLED;
                case OT_STATE_DISABLED:
                    return STATE_DISABLED;
                case OT_STATE_DISABLING:
                    return STATE_DISABLING;
                default:
                    throw new IllegalArgumentException("Unknown ot state " + state);
            }
        }

        @Override
        public void onStateChanged(@NonNull OtDaemonState newState, long listenerId) {
            mHandler.post(() -> onStateChangedInternal(newState, listenerId));
        }

        private void onStateChangedInternal(OtDaemonState newState, long listenerId) {
            checkOnHandlerThread();

            onInterfaceStateChanged(newState.isInterfaceUp);
            onDeviceRoleChanged(newState.deviceRole, listenerId);
            onPartitionIdChanged(newState.partitionId, listenerId);
            onThreadEnabledChanged(newState.threadEnabled, listenerId);
            onEphemeralKeyStateChanged(newState, listenerId);
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
                LOG.wtf("Invalid Active Operational Dataset from OpenThread", e);
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
                LOG.wtf("Invalid Pending Operational Dataset from OpenThread", e);
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

        private void onEphemeralKeyStateChanged(OtDaemonState newState, long listenerId) {
            checkOnHandlerThread();
            boolean hasChange = isEphemeralKeyStateChanged(mState, newState);

            for (var callbackEntry : mStateCallbacks.entrySet()) {
                if (!hasChange && callbackEntry.getValue().id != listenerId) {
                    continue;
                }
                String passcode =
                        callbackEntry.getValue().hasThreadPrivilegedPermission
                                ? newState.ephemeralKeyPasscode
                                : null;
                if (newState.ephemeralKeyState == EPHEMERAL_KEY_DISABLED) {
                    passcode = null;
                }
                try {
                    callbackEntry
                            .getKey()
                            .onEphemeralKeyStateChanged(
                                    newState.ephemeralKeyState,
                                    passcode,
                                    newState.ephemeralKeyLifetimeMillis);
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
        }

        private static boolean isEphemeralKeyStateChanged(
                OtDaemonState oldState, @NonNull OtDaemonState newState) {
            if (oldState == null) return true;
            if (oldState.ephemeralKeyState != newState.ephemeralKeyState) return true;
            if (oldState.ephemeralKeyState == EPHEMERAL_KEY_DISABLED) return false;
            return (!Objects.equals(oldState.ephemeralKeyPasscode, newState.ephemeralKeyPasscode)
                    || oldState.ephemeralKeyLifetimeMillis != newState.ephemeralKeyLifetimeMillis);
        }

        private void onActiveOperationalDatasetChanged(
                ActiveOperationalDataset activeDataset, long listenerId) {
            checkOnHandlerThread();
            boolean hasChange = !Objects.equals(mActiveDataset, activeDataset);
            if (hasChange) {
                LOG.i(
                        "Current Active Operational Dataset changed: "
                                + mActiveDataset
                                + " -> "
                                + activeDataset);
                handleActiveDatasetChanged(activeDataset);
            }

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

        @Override
        public void onAddressChanged(List<Ipv6AddressInfo> addressInfoList) {
            mHandler.post(() -> handleAddressChanged(addressInfoList));
        }

        @Override
        public void onBackboneRouterStateChanged(BackboneRouterState state) {
            mHandler.post(() -> handleMulticastForwardingChanged(state));
        }

        @Override
        public void onPrefixChanged(List<OnMeshPrefixConfig> onMeshPrefixConfigList) {
            mHandler.post(() -> handlePrefixChanged(onMeshPrefixConfigList));
        }
    }

    private final class Nat64CidrController extends IIpv4PrefixRequest.Stub {
        private static final int RETRY_DELAY_ON_FAILURE_MILLIS = 600_000; // 10 minutes

        @Nullable private LinkAddress mNat64Cidr;

        @Override
        public void onIpv4PrefixConflict(IpPrefix prefix) {
            mHandler.post(() -> onIpv4PrefixConflictInternal(prefix));
        }

        private void onIpv4PrefixConflictInternal(IpPrefix prefix) {
            checkOnHandlerThread();

            LOG.i("Conflict on NAT64 CIDR: " + prefix);
            maybeReleaseNat64Cidr();
            maybeUpdateNat64Cidr();
        }

        public void maybeUpdateNat64Cidr() {
            checkOnHandlerThread();

            if (mPersistentSettings.getConfiguration().isNat64Enabled()) {
                maybeRequestNat64Cidr();
            } else {
                maybeReleaseNat64Cidr();
            }
            try {
                getOtDaemon()
                        .setNat64Cidr(
                                mNat64Cidr == null ? null : mNat64Cidr.toString(),
                                new LoggingOtStatusReceiver("setNat64Cidr"));
            } catch (RemoteException | ThreadNetworkException e) {
                LOG.e("Failed to set NAT64 CIDR at otd-daemon", e);
            }
            maybeSendLinkProperties();
        }

        private void maybeRequestNat64Cidr() {
            if (mNat64Cidr != null) {
                return;
            }
            final LinkAddress downstreamAddress =
                    mRoutingCoordinatorManager.requestDownstreamAddress(this);
            if (downstreamAddress == null) {
                mHandler.postDelayed(() -> maybeUpdateNat64Cidr(), RETRY_DELAY_ON_FAILURE_MILLIS);
            }
            mNat64Cidr = downstreamAddress;
            LOG.i("Allocated NAT64 CIDR: " + mNat64Cidr);
        }

        private void maybeReleaseNat64Cidr() {
            if (mNat64Cidr == null) {
                return;
            }
            LOG.i("Released NAT64 CIDR: " + mNat64Cidr);
            mNat64Cidr = null;
            mRoutingCoordinatorManager.releaseDownstream(this);
        }
    }
}
