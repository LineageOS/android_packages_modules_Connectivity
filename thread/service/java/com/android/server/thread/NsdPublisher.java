/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.thread;

import static android.net.nsd.NsdManager.PROTOCOL_DNS_SD;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.DnsResolver;
import android.net.InetAddresses;
import android.net.Network;
import android.net.nsd.DiscoveryRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.thread.openthread.DnsTxtAttribute;
import com.android.server.thread.openthread.INsdDiscoverServiceCallback;
import com.android.server.thread.openthread.INsdPublisher;
import com.android.server.thread.openthread.INsdResolveHostCallback;
import com.android.server.thread.openthread.INsdResolveServiceCallback;
import com.android.server.thread.openthread.INsdStatusReceiver;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Implementation of {@link INsdPublisher}.
 *
 * <p>This class provides API for service registration and discovery over mDNS. This class is a
 * proxy between ot-daemon and NsdManager.
 *
 * <p>All the data members of this class MUST be accessed in the {@code mHandler}'s Thread except
 * {@code mHandler} itself.
 */
public final class NsdPublisher extends INsdPublisher.Stub {
    private static final String TAG = NsdPublisher.class.getSimpleName();

    // TODO: b/321883491 - specify network for mDNS operations
    @Nullable private Network mNetwork;
    private final NsdManager mNsdManager;
    private final DnsResolver mDnsResolver;
    private final Handler mHandler;
    private final Executor mExecutor;
    private final SparseArray<RegistrationListener> mRegistrationListeners = new SparseArray<>(0);
    private final SparseArray<DiscoveryListener> mDiscoveryListeners = new SparseArray<>(0);
    private final SparseArray<ServiceInfoListener> mServiceInfoListeners = new SparseArray<>(0);
    private final SparseArray<HostInfoListener> mHostInfoListeners = new SparseArray<>(0);

    @VisibleForTesting
    public NsdPublisher(NsdManager nsdManager, DnsResolver dnsResolver, Handler handler) {
        mNetwork = null;
        mNsdManager = nsdManager;
        mDnsResolver = dnsResolver;
        mHandler = handler;
        mExecutor = runnable -> mHandler.post(runnable);
    }

    public static NsdPublisher newInstance(Context context, Handler handler) {
        return new NsdPublisher(
                context.getSystemService(NsdManager.class), DnsResolver.getInstance(), handler);
    }

    // TODO: b/321883491 - NsdPublisher should be disabled when mNetwork is null
    public void setNetworkForHostResolution(@Nullable Network network) {
        mNetwork = network;
    }

    @Override
    public void registerService(
            String hostname,
            String name,
            String type,
            List<String> subTypeList,
            int port,
            List<DnsTxtAttribute> txt,
            INsdStatusReceiver receiver,
            int listenerId) {
        NsdServiceInfo serviceInfo =
                buildServiceInfoForService(hostname, name, type, subTypeList, port, txt);
        mHandler.post(() -> registerInternal(serviceInfo, receiver, listenerId, "service"));
    }

    private static NsdServiceInfo buildServiceInfoForService(
            String hostname,
            String name,
            String type,
            List<String> subTypeList,
            int port,
            List<DnsTxtAttribute> txt) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();

        serviceInfo.setServiceName(name);
        if (!TextUtils.isEmpty(hostname)) {
            serviceInfo.setHostname(hostname);
        }
        serviceInfo.setServiceType(type);
        serviceInfo.setPort(port);
        serviceInfo.setSubtypes(new HashSet<>(subTypeList));
        for (DnsTxtAttribute attribute : txt) {
            serviceInfo.setAttribute(attribute.name, attribute.value);
        }

        return serviceInfo;
    }

    @Override
    public void registerHost(
            String name, List<String> addresses, INsdStatusReceiver receiver, int listenerId) {
        NsdServiceInfo serviceInfo = buildServiceInfoForHost(name, addresses);
        mHandler.post(() -> registerInternal(serviceInfo, receiver, listenerId, "host"));
    }

    private static NsdServiceInfo buildServiceInfoForHost(
            String name, List<String> addressStrings) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();

        serviceInfo.setHostname(name);
        ArrayList<InetAddress> addresses = new ArrayList<>(addressStrings.size());
        for (String addressString : addressStrings) {
            addresses.add(InetAddresses.parseNumericAddress(addressString));
        }
        serviceInfo.setHostAddresses(addresses);

        return serviceInfo;
    }

    private void registerInternal(
            NsdServiceInfo serviceInfo,
            INsdStatusReceiver receiver,
            int listenerId,
            String registrationType) {
        checkOnHandlerThread();
        Log.i(
                TAG,
                "Registering "
                        + registrationType
                        + ". Listener ID: "
                        + listenerId
                        + ", serviceInfo: "
                        + serviceInfo);
        RegistrationListener listener = new RegistrationListener(serviceInfo, listenerId, receiver);
        mRegistrationListeners.append(listenerId, listener);
        try {
            mNsdManager.registerService(serviceInfo, PROTOCOL_DNS_SD, mExecutor, listener);
        } catch (IllegalArgumentException e) {
            Log.i(TAG, "Failed to register service. serviceInfo: " + serviceInfo, e);
            listener.onRegistrationFailed(serviceInfo, NsdManager.FAILURE_INTERNAL_ERROR);
        }
    }

    public void unregister(INsdStatusReceiver receiver, int listenerId) {
        mHandler.post(() -> unregisterInternal(receiver, listenerId));
    }

    public void unregisterInternal(INsdStatusReceiver receiver, int listenerId) {
        checkOnHandlerThread();
        RegistrationListener registrationListener = mRegistrationListeners.get(listenerId);
        if (registrationListener == null) {
            Log.w(
                    TAG,
                    "Failed to unregister service."
                            + " Listener ID: "
                            + listenerId
                            + " The registrationListener is empty.");

            return;
        }
        Log.i(
                TAG,
                "Unregistering service."
                        + " Listener ID: "
                        + listenerId
                        + " serviceInfo: "
                        + registrationListener.mServiceInfo);
        registrationListener.addUnregistrationReceiver(receiver);
        mNsdManager.unregisterService(registrationListener);
    }

    @Override
    public void discoverService(String type, INsdDiscoverServiceCallback callback, int listenerId) {
        mHandler.post(() -> discoverServiceInternal(type, callback, listenerId));
    }

    private void discoverServiceInternal(
            String type, INsdDiscoverServiceCallback callback, int listenerId) {
        checkOnHandlerThread();
        Log.i(
                TAG,
                "Discovering services."
                        + " Listener ID: "
                        + listenerId
                        + ", service type: "
                        + type);

        DiscoveryListener listener = new DiscoveryListener(listenerId, type, callback);
        mDiscoveryListeners.append(listenerId, listener);
        DiscoveryRequest discoveryRequest =
                new DiscoveryRequest.Builder(type).setNetwork(null).build();
        mNsdManager.discoverServices(discoveryRequest, mExecutor, listener);
    }

    @Override
    public void stopServiceDiscovery(int listenerId) {
        mHandler.post(() -> stopServiceDiscoveryInternal(listenerId));
    }

    private void stopServiceDiscoveryInternal(int listenerId) {
        checkOnHandlerThread();

        DiscoveryListener listener = mDiscoveryListeners.get(listenerId);
        if (listener == null) {
            Log.w(
                    TAG,
                    "Failed to stop service discovery. Listener ID "
                            + listenerId
                            + ". The listener is null.");
            return;
        }

        Log.i(TAG, "Stopping service discovery. Listener: " + listener);
        mNsdManager.stopServiceDiscovery(listener);
    }

    @Override
    public void resolveService(
            String name, String type, INsdResolveServiceCallback callback, int listenerId) {
        mHandler.post(() -> resolveServiceInternal(name, type, callback, listenerId));
    }

    private void resolveServiceInternal(
            String name, String type, INsdResolveServiceCallback callback, int listenerId) {
        checkOnHandlerThread();

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(name);
        serviceInfo.setServiceType(type);
        serviceInfo.setNetwork(null);
        Log.i(
                TAG,
                "Resolving service."
                        + " Listener ID: "
                        + listenerId
                        + ", service name: "
                        + name
                        + ", service type: "
                        + type);

        ServiceInfoListener listener = new ServiceInfoListener(serviceInfo, listenerId, callback);
        mServiceInfoListeners.append(listenerId, listener);
        mNsdManager.registerServiceInfoCallback(serviceInfo, mExecutor, listener);
    }

    @Override
    public void stopServiceResolution(int listenerId) {
        mHandler.post(() -> stopServiceResolutionInternal(listenerId));
    }

    private void stopServiceResolutionInternal(int listenerId) {
        checkOnHandlerThread();

        ServiceInfoListener listener = mServiceInfoListeners.get(listenerId);
        if (listener == null) {
            Log.w(
                    TAG,
                    "Failed to stop service resolution. Listener ID: "
                            + listenerId
                            + ". The listener is null.");
            return;
        }

        Log.i(TAG, "Stopping service resolution. Listener: " + listener);

        try {
            mNsdManager.unregisterServiceInfoCallback(listener);
        } catch (IllegalArgumentException e) {
            Log.w(
                    TAG,
                    "Failed to stop the service resolution because it's already stopped. Listener: "
                            + listener);
        }
    }

    @Override
    public void resolveHost(String name, INsdResolveHostCallback callback, int listenerId) {
        mHandler.post(() -> resolveHostInternal(name, callback, listenerId));
    }

    private void resolveHostInternal(
            String name, INsdResolveHostCallback callback, int listenerId) {
        checkOnHandlerThread();

        String fullHostname = name + ".local";
        CancellationSignal cancellationSignal = new CancellationSignal();
        HostInfoListener listener =
                new HostInfoListener(name, callback, cancellationSignal, listenerId);
        mDnsResolver.query(
                mNetwork,
                fullHostname,
                DnsResolver.FLAG_NO_CACHE_LOOKUP,
                mExecutor,
                cancellationSignal,
                listener);
        mHostInfoListeners.append(listenerId, listener);

        Log.i(TAG, "Resolving host." + " Listener ID: " + listenerId + ", hostname: " + name);
    }

    @Override
    public void stopHostResolution(int listenerId) {
        mHandler.post(() -> stopHostResolutionInternal(listenerId));
    }

    private void stopHostResolutionInternal(int listenerId) {
        checkOnHandlerThread();

        HostInfoListener listener = mHostInfoListeners.get(listenerId);
        if (listener == null) {
            Log.w(
                    TAG,
                    "Failed to stop host resolution. Listener ID: "
                            + listenerId
                            + ". The listener is null.");
            return;
        }
        Log.i(TAG, "Stopping host resolution. Listener: " + listener);
        listener.cancel();
        mHostInfoListeners.remove(listenerId);
    }

    private void checkOnHandlerThread() {
        if (mHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on handler Thread: " + Thread.currentThread().getName());
        }
    }

    @Override
    public void reset() {
        mHandler.post(this::resetInternal);
    }

    private void resetInternal() {
        checkOnHandlerThread();
        for (int i = 0; i < mRegistrationListeners.size(); ++i) {
            try {
                mNsdManager.unregisterService(mRegistrationListeners.valueAt(i));
            } catch (IllegalArgumentException e) {
                Log.i(
                        TAG,
                        "Failed to unregister."
                                + " Listener ID: "
                                + mRegistrationListeners.keyAt(i)
                                + " serviceInfo: "
                                + mRegistrationListeners.valueAt(i).mServiceInfo,
                        e);
            }
        }
        mRegistrationListeners.clear();
    }

    /** On ot-daemon died, reset. */
    public void onOtDaemonDied() {
        reset();
    }

    private final class RegistrationListener implements NsdManager.RegistrationListener {
        private final NsdServiceInfo mServiceInfo;
        private final int mListenerId;
        private final INsdStatusReceiver mRegistrationReceiver;
        private final List<INsdStatusReceiver> mUnregistrationReceivers;

        RegistrationListener(
                @NonNull NsdServiceInfo serviceInfo,
                int listenerId,
                @NonNull INsdStatusReceiver registrationReceiver) {
            mServiceInfo = serviceInfo;
            mListenerId = listenerId;
            mRegistrationReceiver = registrationReceiver;
            mUnregistrationReceivers = new ArrayList<>();
        }

        void addUnregistrationReceiver(@NonNull INsdStatusReceiver unregistrationReceiver) {
            mUnregistrationReceivers.add(unregistrationReceiver);
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            checkOnHandlerThread();
            mRegistrationListeners.remove(mListenerId);
            Log.i(
                    TAG,
                    "Failed to register listener ID: "
                            + mListenerId
                            + " error code: "
                            + errorCode
                            + " serviceInfo: "
                            + serviceInfo);
            try {
                mRegistrationReceiver.onError(errorCode);
            } catch (RemoteException ignored) {
                // do nothing if the client is dead
            }
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            checkOnHandlerThread();
            for (INsdStatusReceiver receiver : mUnregistrationReceivers) {
                Log.i(
                        TAG,
                        "Failed to unregister."
                                + "Listener ID: "
                                + mListenerId
                                + ", error code: "
                                + errorCode
                                + ", serviceInfo: "
                                + serviceInfo);
                try {
                    receiver.onError(errorCode);
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            checkOnHandlerThread();
            Log.i(
                    TAG,
                    "Registered successfully. "
                            + "Listener ID: "
                            + mListenerId
                            + ", serviceInfo: "
                            + serviceInfo);
            try {
                mRegistrationReceiver.onSuccess();
            } catch (RemoteException ignored) {
                // do nothing if the client is dead
            }
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            checkOnHandlerThread();
            for (INsdStatusReceiver receiver : mUnregistrationReceivers) {
                Log.i(
                        TAG,
                        "Unregistered successfully. "
                                + "Listener ID: "
                                + mListenerId
                                + ", serviceInfo: "
                                + serviceInfo);
                try {
                    receiver.onSuccess();
                } catch (RemoteException ignored) {
                    // do nothing if the client is dead
                }
            }
            mRegistrationListeners.remove(mListenerId);
        }
    }

    private final class DiscoveryListener implements NsdManager.DiscoveryListener {
        private final int mListenerId;
        private final String mType;
        private final INsdDiscoverServiceCallback mDiscoverServiceCallback;

        DiscoveryListener(
                int listenerId,
                @NonNull String type,
                @NonNull INsdDiscoverServiceCallback discoverServiceCallback) {
            mListenerId = listenerId;
            mType = type;
            mDiscoverServiceCallback = discoverServiceCallback;
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            Log.e(
                    TAG,
                    "Failed to start service discovery."
                            + " Error code: "
                            + errorCode
                            + ", listener: "
                            + this);
            mDiscoveryListeners.remove(mListenerId);
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            Log.e(
                    TAG,
                    "Failed to stop service discovery."
                            + " Error code: "
                            + errorCode
                            + ", listener: "
                            + this);
            mDiscoveryListeners.remove(mListenerId);
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
            Log.i(TAG, "Started service discovery. Listener: " + this);
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            Log.i(TAG, "Stopped service discovery. Listener: " + this);
            mDiscoveryListeners.remove(mListenerId);
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            Log.i(TAG, "Found service: " + serviceInfo);
            try {
                mDiscoverServiceCallback.onServiceDiscovered(
                        serviceInfo.getServiceName(), mType, true);
            } catch (RemoteException e) {
                // do nothing if the client is dead
            }
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            Log.i(TAG, "Lost service: " + serviceInfo);
            try {
                mDiscoverServiceCallback.onServiceDiscovered(
                        serviceInfo.getServiceName(), mType, false);
            } catch (RemoteException e) {
                // do nothing if the client is dead
            }
        }

        @Override
        public String toString() {
            return "ID: " + mListenerId + ", type: " + mType;
        }
    }

    private final class ServiceInfoListener implements NsdManager.ServiceInfoCallback {
        private final String mName;
        private final String mType;
        private final INsdResolveServiceCallback mResolveServiceCallback;
        private final int mListenerId;

        ServiceInfoListener(
                @NonNull NsdServiceInfo serviceInfo,
                int listenerId,
                @NonNull INsdResolveServiceCallback resolveServiceCallback) {
            mName = serviceInfo.getServiceName();
            mType = serviceInfo.getServiceType();
            mListenerId = listenerId;
            mResolveServiceCallback = resolveServiceCallback;
        }

        @Override
        public void onServiceInfoCallbackRegistrationFailed(int errorCode) {
            Log.e(
                    TAG,
                    "Failed to register service info callback."
                            + " Listener ID: "
                            + mListenerId
                            + ", error: "
                            + errorCode
                            + ", service name: "
                            + mName
                            + ", service type: "
                            + mType);
        }

        @Override
        public void onServiceUpdated(@NonNull NsdServiceInfo serviceInfo) {
            Log.i(
                    TAG,
                    "Service is resolved. "
                            + " Listener ID: "
                            + mListenerId
                            + ", serviceInfo: "
                            + serviceInfo);
            List<String> addresses = new ArrayList<>();
            for (InetAddress address : serviceInfo.getHostAddresses()) {
                if (address instanceof Inet6Address) {
                    addresses.add(address.getHostAddress());
                }
            }
            List<DnsTxtAttribute> txtList = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : serviceInfo.getAttributes().entrySet()) {
                DnsTxtAttribute attribute =
                        new DnsTxtAttribute(entry.getKey(), entry.getValue().clone());
                txtList.add(attribute);
            }
            // TODO: b/329018320 - Use the serviceInfo.getExpirationTime to derive TTL.
            int ttlSeconds = 10;
            try {
                mResolveServiceCallback.onServiceResolved(
                        serviceInfo.getHostname(),
                        serviceInfo.getServiceName(),
                        serviceInfo.getServiceType(),
                        serviceInfo.getPort(),
                        addresses,
                        txtList,
                        ttlSeconds);

            } catch (RemoteException e) {
                // do nothing if the client is dead
            }
        }

        @Override
        public void onServiceLost() {}

        @Override
        public void onServiceInfoCallbackUnregistered() {
            Log.i(TAG, "The service info callback is unregistered. Listener: " + this);
            mServiceInfoListeners.remove(mListenerId);
        }

        @Override
        public String toString() {
            return "ID: " + mListenerId + ", service name: " + mName + ", service type: " + mType;
        }
    }

    class HostInfoListener implements DnsResolver.Callback<List<InetAddress>> {
        private final String mHostname;
        private final INsdResolveHostCallback mResolveHostCallback;
        private final CancellationSignal mCancellationSignal;
        private final int mListenerId;

        HostInfoListener(
                @NonNull String hostname,
                INsdResolveHostCallback resolveHostCallback,
                CancellationSignal cancellationSignal,
                int listenerId) {
            this.mHostname = hostname;
            this.mResolveHostCallback = resolveHostCallback;
            this.mCancellationSignal = cancellationSignal;
            this.mListenerId = listenerId;
        }

        @Override
        public void onAnswer(@NonNull List<InetAddress> answerList, int rcode) {
            checkOnHandlerThread();

            Log.i(
                    TAG,
                    "Host is resolved."
                            + " Listener ID: "
                            + mListenerId
                            + ", hostname: "
                            + mHostname
                            + ", addresses: "
                            + answerList
                            + ", return code: "
                            + rcode);
            List<String> addressStrings = new ArrayList<>();
            for (InetAddress address : answerList) {
                addressStrings.add(address.getHostAddress());
            }
            try {
                mResolveHostCallback.onHostResolved(mHostname, addressStrings);
            } catch (RemoteException e) {
                // do nothing if the client is dead
            }
            mHostInfoListeners.remove(mListenerId);
        }

        @Override
        public void onError(@NonNull DnsResolver.DnsException error) {
            checkOnHandlerThread();

            Log.i(
                    TAG,
                    "Failed to resolve host."
                            + " Listener ID: "
                            + mListenerId
                            + ", hostname: "
                            + mHostname,
                    error);
            try {
                mResolveHostCallback.onHostResolved(mHostname, Collections.emptyList());
            } catch (RemoteException e) {
                // do nothing if the client is dead
            }
            mHostInfoListeners.remove(mListenerId);
        }

        public String toString() {
            return "ID: " + mListenerId + ", hostname: " + mHostname;
        }

        void cancel() {
            mCancellationSignal.cancel();
            mHostInfoListeners.remove(mListenerId);
        }
    }
}
