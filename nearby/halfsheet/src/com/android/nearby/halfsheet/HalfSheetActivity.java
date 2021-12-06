/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.nearby.halfsheet;

import static com.android.server.nearby.fastpair.Constant.EXTRA_BINDER;
import static com.android.server.nearby.fastpair.Constant.EXTRA_BUNDLE;

import android.app.Activity;
import android.nearby.IFastPairHalfSheetCallback;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Half sheet activity to show pairing ux.
 */
public class HalfSheetActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_half_sheet);
        Bundle bundle = getIntent().getBundleExtra(EXTRA_BUNDLE);
        // Any app that has the component name can start the activity so the binder can't be
        // trusted.
        try {
            IFastPairHalfSheetCallback.Stub.asInterface(bundle.getBinder(EXTRA_BINDER))
                    .onHalfSheetConnectionConfirm();
        } catch (RemoteException e) {
            Log.d("FastPairHalfSheet", "invoke callback fall");
        }
    }
}
