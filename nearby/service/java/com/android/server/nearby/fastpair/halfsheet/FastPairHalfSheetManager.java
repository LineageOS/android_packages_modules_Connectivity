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

package com.android.server.nearby.fastpair.halfsheet;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import com.android.server.nearby.fastpair.cache.DiscoveryItem;

/**
 * Fast Pair ux manager for half sheet.
 */
public class FastPairHalfSheetManager {

    /**
     * Shows pairing fail half sheet.
     */
    public void showPairingFailed() {
        Log.d("FastPairHalfSheetManager", "show fail half sheet");
    }

    /**
     * Get the half sheet status whether it is foreground or dismissed
     */
    public boolean getHalfSheetForegroundState() {
        return true;
    }

    /**
     * Show passkey confirmation info on half sheet
     */
    public void showPasskeyConfirmation(BluetoothDevice device, int passkey) {
    }

    /**
     * This function will handle pairing steps for half sheet.
     */
    public void showPairingHalfSheet(DiscoveryItem item) {
        Log.d("FastPairHalfSheetManager", "show pairing half sheet");
    }

    /**
     * Shows pairing success info.
     */
    public void showPairingSuccessHalfSheet(String address) {
        Log.d("FastPairHalfSheetManager", "show success half sheet");
    }

    /**
     * Removes dismiss runnable.
     */
    public void disableDismissRunnable() {
    }

    /**
     * Destroys the bluetooth pairing controller.
     */
    public void destroyBluetoothPairController() {
    }

    /**
     * Notify manager the pairing has finished.
     */
    public void notifyPairingProcessDone(boolean success, String address, DiscoveryItem item) {
    }
}
