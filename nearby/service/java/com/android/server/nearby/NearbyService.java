/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.nearby;

import android.content.Context;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.FastPairManager;
import com.android.server.nearby.provider.FastPairDataProvider;

/**
 * Service implementing nearby functionality. The actual implementation is delegated to
 * {@link NearbyServiceImpl}.
 */
// TODO(189954300): Implement nearby service.
public class NearbyService extends SystemService {

    public static final String TAG = "NearbyService";
    private static final boolean DBG = true;
    private final NearbyServiceImpl mImpl;
    private final Context mContext;
    private final FastPairManager mFastPairManager;

    private LocatorContextWrapper mLocatorContextWrapper;

    public NearbyService(Context contextBase) {
        super(contextBase);
        mImpl = new NearbyServiceImpl(contextBase);
        mContext = contextBase;
        mLocatorContextWrapper = new LocatorContextWrapper(contextBase, null);
        mFastPairManager = new FastPairManager(mLocatorContextWrapper);
    }

    @Override
    public void onStart() {
        if (DBG) {
            Log.d(TAG, "Publishing NearbyService");
        }
        publishBinderService(Context.NEARBY_SERVICE, mImpl);
        mFastPairManager.initiate();
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            onSystemThirdPartyAppsCanStart();
        }
    }

    private void onSystemThirdPartyAppsCanStart() {
        // Ensures that a fast pair data provider exists which will work in direct boot.
        FastPairDataProvider.init(mContext);
    }
}
