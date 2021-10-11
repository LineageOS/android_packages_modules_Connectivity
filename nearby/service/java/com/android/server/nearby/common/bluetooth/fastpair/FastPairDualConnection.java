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

package com.android.server.nearby.common.bluetooth.fastpair;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.android.server.nearby.proto.NearbyEventCodes.NearbyEvent.EventCode;

import com.google.common.base.Ascii;

/**
 * Supports Fast Pair pairing with certain Bluetooth headphones, Auto, etc.
 *
 * <p>Based on https://developers.google.com/nearby/fast-pair/spec, the pairing is constructed by
 * both BLE and BREDR connections. Example state transitions for Fast Pair 2, ie a pairing key is
 * included in the request (note: timeouts and retries are governed by flags, may change):
 *
 * <pre>
 * {@code
 *   Connect GATT
 *     A) Success -> Handshake
 *     B) Failure (3s timeout) -> Retry 2x -> end
 *
 *   Handshake
 *     A) Generate a shared secret with the headset (either using anti-spoofing key or account key)
 *       1) Account key is used directly as the key
 *       2) Anti-spoofing key is used by combining out private key with the headset's public and
 *          sending our public to the headset to combine with their private to generate a shared
 *          key. Sending our public key to headset takes ~3s.
 *     B) Write an encrypted packet to the headset containing their BLE address for verification
 *        that both sides have the same key (headset decodes this packet and checks it against their
 *        own address) (~250ms).
 *     C) Receive a response from the headset containing their public address (~250ms).
 *
 *   Discovery (for devices < Oreo)
 *     A) Success -> Create Bond
 *     B) Failure (10s timeout) -> Sleep 1s, Retry 3x -> end
 *
 *   Connect to device
 *     A) If already bonded
 *       1) Attempt directly connecting to supported profiles (A2DP, etc)
 *         a) Success -> Write Account Key
 *         b) Failure (15s timeout, usually fails within a ~2s) -> Remove bond (~1s) -> Create bond
 *     B) If not already bonded
 *       1) Create bond
 *         a) Success -> Connect profile
 *         b) Failure (15s timeout) -> Retry 2x -> end
 *       2) Connect profile
 *         a) Success -> Write account key
 *         b) Failure -> Retry -> end
 *
 *   Write account key
 *     A) Callback that pairing succeeded
 *     B) Disconnect GATT
 *     C) Reconnect GATT for secure connection
 *     D) Write account key (~3s)
 * }
 * </pre>
 *
 * The performance profiling result by {@link TimingLogger}:
 *
 * <pre>
 *   FastPairDualConnection [Exclusive time] / [Total time] ([Timestamp])
 *     Connect GATT #1 3054ms (0)
 *     Handshake 32ms / 740ms (3054)
 *       Generate key via ECDH 10ms (3054)
 *       Add salt 1ms (3067)
 *       Encrypt request 3ms (3068)
 *       Write data to GATT 692ms (3097)
 *       Wait response from GATT 0ms (3789)
 *       Decrypt response 2ms (3789)
 *     Get BR/EDR handover information via SDP 1ms (3795)
 *     Pair device #1 6ms / 4887ms (3805)
 *       Create bond 3965ms / 4881ms (3809)
 *         Exchange passkey 587ms / 915ms (7124)
 *           Encrypt passkey 6ms (7694)
 *           Send passkey to remote 290ms (7700)
 *           Wait for remote passkey 0ms (7993)
 *           Decrypt passkey 18ms (7994)
 *           Confirm the pairing: true 14ms (8025)
 *         Close BondedReceiver 1ms (8688)
 *     Connect: A2DP 19ms / 370ms (8701)
 *       Wait connection 348ms / 349ms (8720)
 *         Close ConnectedReceiver 1ms (9068)
 *       Close profile: A2DP 2ms (9069)
 *     Write account key 2ms / 789ms (9163)
 *       Encrypt key 0ms (9164)
 *       Write key via GATT #1 777ms / 783ms (9164)
 *         Close GATT 6ms (9941)
 *       Start CloudSyncing 2ms (9947)
 *       Broadcast Validator 2ms (9949)
 *   FastPairDualConnection end, 9952ms
 * </pre>
 */
public abstract class FastPairDualConnection extends FastPairConnection {

    static void logRetrySuccessEvent(
            EventCode eventCode,
            @Nullable Exception recoverFromException,
            EventLoggerWrapper eventLogger) {
        if (recoverFromException == null) {
            return;
        }
        eventLogger.setCurrentEvent(eventCode);
        eventLogger.logCurrentEventFailed(recoverFromException);
    }

    static void checkFastPairSignal(
            FastPairSignalChecker fastPairSignalChecker,
            String currentAddress,
            Exception originalException)
            throws SignalLostException, SignalRotatedException {
        String newAddress = fastPairSignalChecker.getValidAddressForModelId(currentAddress);
        if (TextUtils.isEmpty(newAddress)) {
            throw new SignalLostException("Signal lost", originalException);
        } else if (!Ascii.equalsIgnoreCase(currentAddress, newAddress)) {
            throw new SignalRotatedException("Address rotated", newAddress, originalException);
        }
    }
}
