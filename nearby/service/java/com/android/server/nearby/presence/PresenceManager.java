/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.nearby.presence;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.location.NanoAppMessage;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Collections;

import service.proto.Blefilter;

/** PresenceManager is the class initiated in nearby service to handle presence related work. */
public class PresenceManager {
    /** Callback that receives filter results from CHRE Nanoapp. */
    public interface PresenceCallback {
        /** Called when {@link BleFilterResults} has been received. */
        void onFilterResults(Blefilter.BleFilterResults filterResults);
    }

    private static final String TAG = "PresenceService";
    // Nanoapp ID reserved for Nearby Presence.
    /** @hide */
    @VisibleForTesting
    public static final long NANOAPP_ID = 0x476f6f676c001031L;
    /** @hide */
    @VisibleForTesting
    public static final int NANOAPP_MESSAGE_TYPE_FILTER = 3;
    /** @hide */
    @VisibleForTesting
    public static final int NANOAPP_MESSAGE_TYPE_FILTER_RESULT = 4;
    private final Context mContext;
    private final PresenceCallback mPresenceCallback;
    private final ChreCallback mChreCallback;
    private ChreCommunication mChreCommunication;

    private Blefilter.BleFilters mFilters = null;
    private boolean mChreStarted = false;

    private final IntentFilter mIntentFilter;

    private final BroadcastReceiver mScreenBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                        // TODO(b/221082271): removed this faked data once hooked with API codes.
                        Log.d(TAG, "Update Presence CHRE filter");
                        ByteString mac_addr = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
                        Blefilter.BleFilter filter =
                                Blefilter.BleFilter.newBuilder()
                                        .setId(0)
                                        .setUuid(0xFCF1)
                                        .setIntent(1)
                                        .setMacAddress(mac_addr)
                                        .build();
                        Blefilter.BleFilters filters =
                                Blefilter.BleFilters.newBuilder().addFilter(filter).build();
                        updateFilters(filters);
                    }
                }
            };

    public PresenceManager(Context context, PresenceCallback presenceCallback) {
        mContext = context;
        mPresenceCallback = presenceCallback;
        mChreCallback = new ChreCallback();
        mIntentFilter = new IntentFilter();
    }

    /** Function called when nearby service start. */
    public void initiate(ChreCommunication chreCommunication) {
        mChreCommunication = chreCommunication;
        mChreCommunication.start(mChreCallback, Collections.singleton(NANOAPP_ID));

        mIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(mScreenBroadcastReceiver, mIntentFilter);
    }

    /** Updates the fitlers in Context Hub. */
    public synchronized void updateFilters(Blefilter.BleFilters filters) {
        mFilters = filters;
        if (mChreStarted) {
            sendFilters(mFilters);
            mFilters = null;
        }
    }

    private void sendFilters(Blefilter.BleFilters filters) {
        NanoAppMessage message =
                NanoAppMessage.createMessageToNanoApp(
                        NANOAPP_ID, NANOAPP_MESSAGE_TYPE_FILTER, filters.toByteArray());
        if (!mChreCommunication.sendMessageToNanoApp(message)) {
            Log.e(TAG, "Failed to send filters to CHRE.");
        }
    }

    private class ChreCallback implements ChreCommunication.ContextHubCommsCallback {

        @Override
        public void started(boolean success) {
            if (success) {
                synchronized (PresenceManager.this) {
                    Log.i(TAG, "CHRE communication started");
                    mChreStarted = true;
                    if (mFilters != null) {
                        sendFilters(mFilters);
                        mFilters = null;
                    }
                }
            }
        }

        @Override
        public void onHubReset() {
            // TODO(b/221082271): hooked with upper level codes.
            Log.i(TAG, "CHRE reset.");
        }

        @Override
        public void onNanoAppRestart(long nanoAppId) {
            // TODO(b/221082271): hooked with upper level codes.
            Log.i(TAG, String.format("CHRE NanoApp %d restart.", nanoAppId));
        }

        @Override
        public void onMessageFromNanoApp(NanoAppMessage message) {
            if (message.getNanoAppId() != NANOAPP_ID) {
                Log.e(TAG, "Received message from unknown nano app.");
                return;
            }
            if (message.getMessageType() == NANOAPP_MESSAGE_TYPE_FILTER_RESULT) {
                try {
                    Blefilter.BleFilterResults results =
                            Blefilter.BleFilterResults.parseFrom(message.getMessageBody());
                    mPresenceCallback.onFilterResults(results);
                } catch (InvalidProtocolBufferException e) {
                    Log.e(
                            TAG,
                            String.format("Failed to decode the filter result %s", e.toString()));
                }
            }
        }
    }
}
