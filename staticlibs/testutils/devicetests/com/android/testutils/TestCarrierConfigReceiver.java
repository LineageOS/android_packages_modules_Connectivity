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

package com.android.testutils;

import static android.telephony.CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertTrue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A receiver to receive carrier config and can be awaiting the result of config change. */
public class TestCarrierConfigReceiver extends BroadcastReceiver {
    // CountDownLatch used to wait for this BroadcastReceiver to be notified of a CarrierConfig
    // change. This latch will be counted down if a broadcast indicates this package has carrier
    // configs, or if an Exception occurs in #onReceive.
    private final CountDownLatch mLatch = new CountDownLatch(1);
    private final int mSubId;
    private final int mTimeoutMs;
    private final PersistableBundle mRequestedConfig;
    private final Matcher mMatcher;
    private final Context mContext;
    private final CarrierConfigManager mCarrierConfigManager;

    private volatile PersistableBundle mReceivedConfigs;
    private volatile Exception mOnReceiveException;

    /** A interface for doing the carrier config match between requested and received one.*/
    public interface Matcher {
        /**
         * Called when a broadcast is received in order to determine whether the
         * broadcast matches expectations.
         *
         *  - If this method returns true, waitForCarrierConfigChanged returns.
         *  - If this method returns false, the broadcast will be ignored and
         *  waitForCarrierConfigChanged will continue to wait. If this method throws an exception,
         *  the exception will be stored and re-thrown by waitForCarrierConfigChanged.
         */
        boolean matchesBroadcast(PersistableBundle config) throws Exception;
    }

    public TestCarrierConfigReceiver(
            Context context,
            int subId,
            int timeoutMs,
            PersistableBundle requestedConfig,
            Matcher matcher) {
        mContext = context;
        mSubId = subId;
        mTimeoutMs = timeoutMs;
        mRequestedConfig = requestedConfig;
        mMatcher = matcher;
        mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(intent.getAction())) {
            // Ignores this incorrect broadcast.
            return;
        }

        int subId = intent.getIntExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (mSubId != subId) {
            // Ignores this broadcast for the wrong subId.
            return;
        }
        mReceivedConfigs = getCarrierConfigs(subId);

        if (!CarrierConfigManager.isConfigForIdentifiedCarrier(mReceivedConfigs)) {
            // Configs are not for an identified carrier (meaning they are defaults) - ignore
            return;
        }

        try {
            if (mMatcher.matchesBroadcast(mReceivedConfigs)) {
                mLatch.countDown();
            }
        } catch (Exception e) {
            // Throw an Exception - cache it and allow waitForCarrierConfigChanged() to throw it
            mOnReceiveException = e;
            mLatch.countDown();
        }
    }

    private PersistableBundle getCarrierConfigs(int subId) {
        return runAsShell(android.Manifest.permission.READ_PHONE_STATE,
                () -> mCarrierConfigManager.getConfigForSubId(subId));
    }

    private void waitForCarrierConfigChanged() throws Exception {
        try {
            assertTrue("No matching carrier config broadcast received after " + mTimeoutMs
                            + " milliseconds.", mLatch.await(mTimeoutMs, TimeUnit.MILLISECONDS));
            // If latch is unlocked and mOnReceiveException is assigned,
            // it shall throw the exception.
            if (mOnReceiveException != null) {
                throw mOnReceiveException;
            }
        } finally {
            mContext.unregisterReceiver(this);
        }
    }

    /**
     * Override the carrier config and waiting for the carrier config change.
     * @return A PersistentBundle with received carrier configs.
     * @throws Exception Once timout happened, it may throw a timout exception.
     */
    public PersistableBundle overrideCarrierConfigForTest() throws Exception {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CARRIER_CONFIG_CHANGED);
        mContext.registerReceiver(this, filter);
        runAsShell(android.Manifest.permission.MODIFY_PHONE_STATE,
                () -> {
                    mCarrierConfigManager.overrideConfig(mSubId, mRequestedConfig);
                    mCarrierConfigManager.notifyConfigChangedForSubId(mSubId);
                });

        waitForCarrierConfigChanged();
        return mReceivedConfigs;
    }
}
