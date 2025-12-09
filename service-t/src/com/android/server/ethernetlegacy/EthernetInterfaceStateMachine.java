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

package com.android.server.ethernetlegacy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkProvider.NetworkOfferCallback;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.ip.IIpClient;
import android.net.ip.IpClientCallbacks;
import android.net.ip.IpClientManager;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Message;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.util.State;
import com.android.net.module.util.SyncStateMachine;
import com.android.net.module.util.SyncStateMachine.StateInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * EthernetInterfaceStateMachine manages the lifecycle of an ethernet-like network interface which
 * includes managing a NetworkOffer, IpClient, and NetworkAgent as well as making the interface
 * available as a tethering downstream.
 *
 * All methods exposed by this class *must* be called on the Handler thread provided in the
 * constructor.
 */
class EthernetInterfaceStateMachine extends SyncStateMachine {
    private static final String TAG = EthernetInterfaceStateMachine.class.getSimpleName();

    private static final int CMD_ON_LINK_UP          = 1;
    private static final int CMD_ON_LINK_DOWN        = 2;
    private static final int CMD_ON_NETWORK_NEEDED   = 3;
    private static final int CMD_ON_NETWORK_UNNEEDED = 4;
    private static final int CMD_ON_IPCLIENT_CREATED = 5;

    private class EthernetNetworkOfferCallback implements NetworkOfferCallback {
        private final Set<Integer> mRequestIds = new ArraySet<>();

        @Override
        public void onNetworkNeeded(@NonNull NetworkRequest request) {
            if (this != mNetworkOfferCallback) {
                return;
            }

            mRequestIds.add(request.requestId);
            if (mRequestIds.size() == 1) {
                processMessage(CMD_ON_NETWORK_NEEDED);
            }
        }

        @Override
        public void onNetworkUnneeded(@NonNull NetworkRequest request) {
            if (this != mNetworkOfferCallback) {
                return;
            }

            if (!mRequestIds.remove(request.requestId)) {
                // This can only happen if onNetworkNeeded was not called for a request or if
                // the requestId changed. Both should *never* happen.
                Log.wtf(TAG, "onNetworkUnneeded called for unknown request");
            }
            if (mRequestIds.isEmpty()) {
                processMessage(CMD_ON_NETWORK_UNNEEDED);
            }
        }
    }

    private class EthernetIpClientCallback extends IpClientCallbacks {
        private final ConditionVariable mOnQuitCv = new ConditionVariable(false);

        private void safelyPostOnHandler(Runnable r) {
            mHandler.post(() -> {
                if (this != mIpClientCallback) {
                    return;
                }
                r.run();
            });
        }

        @Override
        public void onIpClientCreated(IIpClient ipClient) {
            safelyPostOnHandler(() -> {
                // TODO: add a SyncStateMachine#processMessage(cmd, obj) overload.
                processMessage(CMD_ON_IPCLIENT_CREATED, 0, 0,
                        mDependencies.makeIpClientManager(ipClient));
            });
        }

        public void waitOnQuit() {
            if (!mOnQuitCv.block(5_000 /* timeoutMs */)) {
                Log.wtf(TAG, "Timed out waiting on IpClient to shutdown.");
            }
        }

        @Override
        public void onQuit() {
            mOnQuitCv.open();
        }
    }

    private @Nullable EthernetNetworkOfferCallback mNetworkOfferCallback;
    private @Nullable EthernetIpClientCallback mIpClientCallback;
    private @Nullable IpClientManager mIpClient;
    private final String mIface;
    private final Handler mHandler;
    private final Context mContext;
    private final NetworkCapabilities mCapabilities;
    private final NetworkProvider mNetworkProvider;
    private final EthernetNetworkFactory.Dependencies mDependencies;
    private boolean mLinkUp = false;

    /** Interface is in tethering mode. */
    private class TetheringState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_ON_LINK_UP:
                case CMD_ON_LINK_DOWN:
                    // TODO: think about what to do here.
                    return HANDLED;
            }
            return NOT_HANDLED;
        }
    }

    /** Link is down */
    private class LinkDownState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_ON_LINK_UP:
                    transitionTo(mStoppedState);
                    return HANDLED;
                case CMD_ON_LINK_DOWN:
                    // do nothing, already in the correct state.
                    return HANDLED;
            }
            return NOT_HANDLED;
        }
    }

    /** Parent states of all states that do not cause a NetworkOffer to be extended. */
    private class NetworkOfferExtendedState extends State {
        @Override
        public void enter() {
            if (mNetworkOfferCallback != null) {
                // This should never happen. If it happens anyway, log and move on.
                Log.wtf(TAG, "Previous NetworkOffer was never retracted");
            }

            mNetworkOfferCallback = new EthernetNetworkOfferCallback();
            final NetworkScore defaultScore = new NetworkScore.Builder().build();
            mNetworkProvider.registerNetworkOffer(defaultScore,
                    new NetworkCapabilities(mCapabilities), cmd -> mHandler.post(cmd),
                    mNetworkOfferCallback);
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_ON_LINK_UP:
                    // do nothing, already in the correct state.
                    return HANDLED;
                case CMD_ON_LINK_DOWN:
                    transitionTo(mLinkDownState);
                    return HANDLED;
            }
            return NOT_HANDLED;
        }

        @Override
        public void exit() {
            mNetworkProvider.unregisterNetworkOffer(mNetworkOfferCallback);
            mNetworkOfferCallback = null;
        }
    }

    /**
     * Offer is extended but has not been requested.
     *
     * StoppedState's sole purpose is to react to a CMD_ON_NETWORK_NEEDED and transition to
     * StartedState when that happens. Note that StoppedState could be rolled into
     * NetworkOfferExtendedState. However, keeping the states separate provides some additional
     * protection by logging a Log.wtf if a CMD_ON_NETWORK_NEEDED is received in an unexpected state
     * (i.e. StartedState or RunningState). StoppedState is a child of NetworkOfferExtendedState.
     */
    private class StoppedState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_ON_NETWORK_NEEDED:
                    transitionTo(mStartedState);
                    return HANDLED;
            }
            return NOT_HANDLED;
        }
    }

    /** Network is needed, starts IpClient and manages its lifecycle */
    private class StartedState extends State {
        @Override
        public void enter() {
            mIpClientCallback = new EthernetIpClientCallback();
            mDependencies.makeIpClient(mContext, mIface, mIpClientCallback);
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_ON_NETWORK_UNNEEDED:
                    transitionTo(mStoppedState);
                    return HANDLED;
                case CMD_ON_IPCLIENT_CREATED:
                    mIpClient = (IpClientManager) msg.obj;
                    transitionTo(mRunningState);
                    return HANDLED;
            }
            return NOT_HANDLED;
        }

        @Override
        public void exit() {
            if (mIpClient != null) {
                mIpClient.shutdown();
                // TODO: consider adding a StoppingState and making the shutdown operation
                // asynchronous.
                mIpClientCallback.waitOnQuit();
            }
            mIpClientCallback = null;
        }
    }

    /** IpClient is running, starts provisioning and registers NetworkAgent */
    private class RunningState extends State {

    }

    private final TetheringState mTetheringState = new TetheringState();
    private final LinkDownState mLinkDownState = new LinkDownState();
    private final NetworkOfferExtendedState mOfferExtendedState = new NetworkOfferExtendedState();
    private final StoppedState mStoppedState = new StoppedState();
    private final StartedState mStartedState = new StartedState();
    private final RunningState mRunningState = new RunningState();

    public EthernetInterfaceStateMachine(String iface, Handler handler, Context context,
            NetworkCapabilities capabilities, NetworkProvider provider,
            EthernetNetworkFactory.Dependencies deps) {
        super(TAG + "." + iface, handler.getLooper().getThread());

        mIface = iface;
        mHandler = handler;
        mContext = context;
        mCapabilities = capabilities;
        mNetworkProvider = provider;
        mDependencies = deps;

        // Interface lifecycle:
        //           [ LinkDownState ]
        //                   |
        //                   v
        //             *link comes up*
        //                   |
        //                   v
        //            [ StoppedState ]
        //                   |
        //                   v
        //           *network is needed*
        //                   |
        //                   v
        //            [ StartedState ]
        //                   |
        //                   v
        //           *IpClient is created*
        //                   |
        //                   v
        //            [ RunningState ]
        //                   |
        //                   v
        //  *interface is requested for tethering*
        //                   |
        //                   v
        //            [TetheringState]
        //
        // Tethering mode is special as the interface is configured by Tethering, rather than the
        // ethernet module.
        final List<StateInfo> states = new ArrayList<>();
        states.add(new StateInfo(mTetheringState, null));

        // CHECKSTYLE:OFF IndentationCheck
        // Initial state
        states.add(new StateInfo(mLinkDownState, null));
        states.add(new StateInfo(mOfferExtendedState, null));
            states.add(new StateInfo(mStoppedState, mOfferExtendedState));
            states.add(new StateInfo(mStartedState, mOfferExtendedState));
                states.add(new StateInfo(mRunningState, mStartedState));
        // CHECKSTYLE:ON IndentationCheck
        addAllStates(states);

        // TODO: set initial state to TetheringState if a tethering interface has been requested and
        // this is the first interface to be added.
        start(mLinkDownState);
    }

    public boolean updateLinkState(boolean up) {
        if (mLinkUp == up) {
            return false;
        }

        // TODO: consider setting mLinkUp as part of processMessage().
        mLinkUp = up;
        if (!up) { // was up, goes down
            processMessage(CMD_ON_LINK_DOWN);
        } else { // was down, comes up
            processMessage(CMD_ON_LINK_UP);
        }

        return true;
    }
}
