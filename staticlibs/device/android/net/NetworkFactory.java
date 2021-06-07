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

package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.NetworkProvider.NetworkOfferCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * A NetworkFactory is an entity that creates NetworkAgent objects.
 * The bearers register with ConnectivityService using {@link #register} and
 * their factory will start receiving scored NetworkRequests.  NetworkRequests
 * can be filtered 3 ways: by NetworkCapabilities, by score and more complexly by
 * overridden function.  All of these can be dynamic - changing NetworkCapabilities
 * or score forces re-evaluation of all current requests.
 *
 * If any requests pass the filter some overrideable functions will be called.
 * If the bearer only cares about very simple start/stopNetwork callbacks, those
 * functions can be overridden.  If the bearer needs more interaction, it can
 * override addNetworkRequest and removeNetworkRequest which will give it each
 * request that passes their current filters.
 * @hide
 **/
public class NetworkFactory extends Handler {
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    // A score that will win against everything, so that score filtering will let all requests
    // through
    // TODO : remove this and replace with an API to listen to all requests.
    @NonNull
    private static final NetworkScore INVINCIBLE_SCORE =
            new NetworkScore.Builder().setLegacyInt(1000).build();

    /**
     * Pass a network request to the bearer.  If the bearer believes it can
     * satisfy the request it should connect to the network and create a
     * NetworkAgent.  Once the NetworkAgent is fully functional it will
     * register itself with ConnectivityService using registerNetworkAgent.
     * If the bearer cannot immediately satisfy the request (no network,
     * user disabled the radio, lower-scored network) it should remember
     * any NetworkRequests it may be able to satisfy in the future.  It may
     * disregard any that it will never be able to service, for example
     * those requiring a different bearer.
     * msg.obj = NetworkRequest
     */
    // TODO : this and CANCEL_REQUEST are only used by telephony tests. Replace it in the tests
    // and remove them and the associated code.
    public static final int CMD_REQUEST_NETWORK = 1;

    /**
     * Cancel a network request
     * msg.obj = NetworkRequest
     */
    public static final int CMD_CANCEL_REQUEST = 2;

    /**
     * Internally used to set our best-guess score.
     * msg.obj = new score
     */
    private static final int CMD_SET_SCORE = 3;

    /**
     * Internally used to set our current filter for coarse bandwidth changes with
     * technology changes.
     * msg.obj = new filter
     */
    private static final int CMD_SET_FILTER = 4;

    /**
     * Internally used to send the network offer associated with this factory.
     * No arguments, will read from members
     */
    private static final int CMD_OFFER_NETWORK = 5;

    /**
     * Internally used to send the request to listen to all requests.
     * No arguments, will read from members
     */
    private static final int CMD_LISTEN_TO_ALL_REQUESTS = 6;

    private final Context mContext;
    private final String LOG_TAG;

    private final Map<NetworkRequest, NetworkRequestInfo> mNetworkRequests =
            new LinkedHashMap<>();

    @NonNull private NetworkScore mScore = new NetworkScore.Builder().setLegacyInt(0).build();
    @NonNull private NetworkCapabilities mCapabilityFilter;

    private int mRefCount = 0;
    private NetworkProvider mProvider = null;

    private final NetworkOfferCallback mRequestCallback = new NetworkOfferCallback() {
        @Override
        public void onNetworkNeeded(@NonNull final NetworkRequest request) {
            handleAddRequest(request);
        }

        @Override
        public void onNetworkUnneeded(@NonNull final NetworkRequest request) {
            handleRemoveRequest(request);
        }
    };
    @NonNull private final Executor mExecutor = command -> post(command);

    // Ideally the filter argument would be non-null, but null has historically meant to see
    // no requests and telephony passes null.
    public NetworkFactory(Looper looper, Context context, String logTag,
            @Nullable final NetworkCapabilities filter) {
        super(looper);
        LOG_TAG = logTag;
        mContext = context;
        if (null != filter) {
            mCapabilityFilter = filter;
        } else {
            mCapabilityFilter = new NetworkCapabilities.Builder()
                    .withoutDefaultCapabilities().build();
        }
    }

    /* Registers this NetworkFactory with the system. May only be called once per factory. */
    public void register() {
        register(false);
    }

    /**
     * Registers this NetworkFactory with the system ignoring the score filter. This will let
     * the factory always see all network requests matching its capabilities filter.
     * May only be called once per factory.
     */
    public void registerIgnoringScore() {
        register(true);
    }

    private void register(final boolean listenToAllRequests) {
        if (mProvider != null) {
            throw new IllegalStateException("A NetworkFactory must only be registered once");
        }
        if (DBG) log("Registering NetworkFactory");

        mProvider = new NetworkProvider(mContext, NetworkFactory.this.getLooper(), LOG_TAG) {
            @Override
            public void onNetworkRequested(@NonNull NetworkRequest request, int score,
                    int servingProviderId) {
                handleAddRequest(request);
            }

            @Override
            public void onNetworkRequestWithdrawn(@NonNull NetworkRequest request) {
                handleRemoveRequest(request);
            }
        };

        ((ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE)).registerNetworkProvider(mProvider);

        // The mScore and mCapabilityFilter members can only be accessed on the handler thread.
        // TODO : offer a separate API to listen to all requests instead
        if (listenToAllRequests) {
            sendMessage(obtainMessage(CMD_LISTEN_TO_ALL_REQUESTS));
        } else {
            sendMessage(obtainMessage(CMD_OFFER_NETWORK));
        }
    }

    private void handleOfferNetwork(@NonNull final NetworkScore score) {
        mProvider.registerNetworkOffer(score, mCapabilityFilter, mExecutor, mRequestCallback);
    }

    /** Unregisters this NetworkFactory. After this call, the object can no longer be used. */
    public void terminate() {
        if (mProvider == null) {
            throw new IllegalStateException("This NetworkFactory was never registered");
        }
        if (DBG) log("Unregistering NetworkFactory");

        ((ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE))
            .unregisterNetworkProvider(mProvider);

        // Remove all pending messages, since this object cannot be reused. Any message currently
        // being processed will continue to run.
        removeCallbacksAndMessages(null);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case CMD_REQUEST_NETWORK: {
                handleAddRequest((NetworkRequest) msg.obj);
                break;
            }
            case CMD_CANCEL_REQUEST: {
                handleRemoveRequest((NetworkRequest) msg.obj);
                break;
            }
            case CMD_SET_SCORE: {
                handleSetScore((NetworkScore) msg.obj);
                break;
            }
            case CMD_SET_FILTER: {
                handleSetFilter((NetworkCapabilities) msg.obj);
                break;
            }
            case CMD_OFFER_NETWORK: {
                handleOfferNetwork(mScore);
                break;
            }
            case CMD_LISTEN_TO_ALL_REQUESTS: {
                handleOfferNetwork(INVINCIBLE_SCORE);
                break;
            }
        }
    }

    private static class NetworkRequestInfo {
        @NonNull public final NetworkRequest request;
        public boolean requested; // do we have a request outstanding, limited by score

        NetworkRequestInfo(@NonNull final NetworkRequest request) {
            this.request = request;
            this.requested = false;
        }

        @Override
        public String toString() {
            return "{" + request + ", requested=" + requested + "}";
        }
    }

    /**
     * Add a NetworkRequest that the bearer may want to attempt to satisfy.
     * @see #CMD_REQUEST_NETWORK
     *
     * @param request the request to handle.
     */
    private void handleAddRequest(@NonNull final NetworkRequest request) {
        NetworkRequestInfo n = mNetworkRequests.get(request);
        if (n == null) {
            if (DBG) log("got request " + request);
            n = new NetworkRequestInfo(request);
            mNetworkRequests.put(n.request, n);
        } else {
            if (VDBG) {
                log("handle existing request " + request);
            }
        }
        if (VDBG) log("  my score=" + mScore + ", my filter=" + mCapabilityFilter);

        if (acceptRequest(request)) {
            n.requested = true;
            needNetworkFor(request);
        }
    }

    private void handleRemoveRequest(NetworkRequest request) {
        NetworkRequestInfo n = mNetworkRequests.get(request);
        if (n != null) {
            mNetworkRequests.remove(request);
            if (n.requested) releaseNetworkFor(n.request);
        }
    }

    private void handleSetScore(@NonNull final NetworkScore score) {
        if (mScore.equals(score)) return;
        mScore = score;
        reevaluateAllRequests();
    }

    private void handleSetFilter(@NonNull final NetworkCapabilities netCap) {
        if (netCap.equals(mCapabilityFilter)) return;
        mCapabilityFilter = netCap;
        reevaluateAllRequests();
    }

    protected final void reevaluateAllRequests() {
        if (mProvider == null) return;
        mProvider.registerNetworkOffer(mScore, mCapabilityFilter, mExecutor, mRequestCallback);
    }

    /**
     * Overridable function to provide complex filtering.
     * Called for every request every time a new NetworkRequest is seen
     * and whenever the filterScore or filterNetworkCapabilities change.
     *
     * acceptRequest can be overridden to provide complex filter behavior
     * for the incoming requests
     *
     * For output, this class will call {@link #needNetworkFor} and
     * {@link #releaseNetworkFor} for every request that passes the filters.
     * If you don't need to see every request, you can leave the base
     * implementations of those two functions and instead override
     * {@link #startNetwork} and {@link #stopNetwork}.
     *
     * If you want to see every score fluctuation on every request, set
     * your score filter to a very high number and watch {@link #needNetworkFor}.
     *
     * @return {@code true} to accept the request.
     */
    public boolean acceptRequest(@NonNull final NetworkRequest request) {
        return true;
    }
    /**
     * Can be called by a factory to release a request as unfulfillable: the request will be
     * removed, and the caller will get a
     * {@link ConnectivityManager.NetworkCallback#onUnavailable()} callback after this function
     * returns.
     *
     * Note: this should only be called by factory which KNOWS that it is the ONLY factory which
     * is able to fulfill this request!
     */
    protected void releaseRequestAsUnfulfillableByAnyFactory(NetworkRequest r) {
        post(() -> {
            if (DBG) log("releaseRequestAsUnfulfillableByAnyFactory: " + r);
            final NetworkProvider provider = mProvider;
            if (provider == null) {
                Log.e(LOG_TAG, "Ignoring attempt to release unregistered request as unfulfillable");
                return;
            }
            provider.declareNetworkRequestUnfulfillable(r);
        });
    }

    // override to do simple mode (request independent)
    protected void startNetwork() { }
    protected void stopNetwork() { }

    // override to do fancier stuff
    protected void needNetworkFor(@NonNull final NetworkRequest networkRequest) {
        if (++mRefCount == 1) startNetwork();
    }

    protected void releaseNetworkFor(@NonNull final NetworkRequest networkRequest) {
        if (--mRefCount == 0) stopNetwork();
    }

    /**
     * @deprecated this method was never part of the API (system or public) and is only added
     *   for migration of existing clients. It will be removed before S ships.
     */
    @Deprecated
    public void setScoreFilter(final int score) {
        setScoreFilter(new NetworkScore.Builder().setLegacyInt(score).build());
    }

    /**
     * Set a score filter for this factory.
     *
     * This should include the transports the factory knows its networks will have, and
     * an optimistic view of the attributes it may have. This does not commit the factory
     * to being able to bring up such a network ; it only lets it avoid hearing about
     * requests that it has no chance of fulfilling.
     *
     * @param score the filter
     */
    public void setScoreFilter(@NonNull final NetworkScore score) {
        sendMessage(obtainMessage(CMD_SET_SCORE, score));
    }

    public void setCapabilityFilter(NetworkCapabilities netCap) {
        sendMessage(obtainMessage(CMD_SET_FILTER, new NetworkCapabilities(netCap)));
    }

    @VisibleForTesting
    protected int getRequestCount() {
        return mNetworkRequests.size();
    }

    /* TODO: delete when all callers have migrated to NetworkProvider IDs. */
    public int getSerialNumber() {
        return mProvider.getProviderId();
    }

    public NetworkProvider getProvider() {
        return mProvider;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, s);
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println(toString());
        for (NetworkRequestInfo n : mNetworkRequests.values()) {
            writer.println("  " + n);
        }
    }

    @Override
    public String toString() {
        return "{" + LOG_TAG + " - providerId="
                + mProvider.getProviderId() + ", ScoreFilter="
                + mScore + ", Filter=" + mCapabilityFilter + ", requests="
                + mNetworkRequests.size() + ", refCount=" + mRefCount
                + "}";
    }
}
