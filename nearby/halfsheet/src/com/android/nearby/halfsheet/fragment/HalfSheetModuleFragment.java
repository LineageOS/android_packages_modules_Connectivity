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
package com.android.nearby.halfsheet.fragment;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;


/** Base class for all of the half sheet fragment. */
// TODO(b/177675274): Resolve nullness suppression.
@SuppressWarnings("nullness")
public abstract class HalfSheetModuleFragment extends Fragment {

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    /** UI states of the half-sheet fragment. */
    public enum HalfSheetFragmentState {
        NOT_STARTED,
        SYNC_CONTACTS,
        SYNC_SMS,
        PROGRESSING,
        CONFIRM_PASSKEY,
        WRONG_PASSKEY,
        PAIRING,
        ADDITIONAL_SETUP_PROGRESS,
        ADDITIONAL_SETUP_FINAL,
        RESULT_SUCCESS,
        RESULT_FAILURE,
        FINISHED
    }

    /** Only used in {@link DevicePairingFragment} show pairing success info in half sheet. */
    public void showSuccessInfo() {
    }

    /** Only used in {@link DevicePairingFragment} show pairing fail info in half sheet. */
    public void showFailInfo() {
    }

    /**
     * Returns the {@link HalfSheetFragmentState} to the parent activity.
     *
     * <p>Overrides this method if the fragment's state needs to be preserved in the parent
     * activity.
     */
    public HalfSheetFragmentState getFragmentState() {
        return HalfSheetFragmentState.NOT_STARTED;
    }
}
