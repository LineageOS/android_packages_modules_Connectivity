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

package com.android.server.nearby.common.ble.util;


/**
 * Ranging utilities embody the physics of converting RF path loss to distance. The free space path
 * loss is proportional to the square of the distance from transmitter to receiver, and to the
 * square of the frequency of the propagation signal.
 */
public final class RangingUtils {
    /*
     * Key to variable names used in this class (viz. Physics):
     *
     * c   = speed of light (2.9979 x 10^8 m/s);
     * f   = frequency (Bluetooth center frequency is 2.44175GHz = 2.44175x10^9 Hz);
     * l   = wavelength (meters);
     * d   = distance (from transmitter to receiver in meters);
     * dB  = decibels
     * dBm = decibel milliwatts
     *
     *
     * Free-space path loss (FSPL) is proportional to the square of the distance between the
     * transmitter and the receiver, and also proportional to the square of the frequency of the
     * radio signal.
     *
     * FSPL      = (4 * pi * d / l)^2 = (4 * pi * d * f / c)^2
     *
     * FSPL (dB) = 10*log10((4 * pi * d * f / c)^2)
     *           = 20*log10(4 * pi * d * f / c)
     *           = 20*log10(d) + 20*log10(f) + 20*log10(4*pi/c)
     *
     * Calculating constants:
     *
     * FSPL_FREQ        = 20*log10(f)
     *                  = 20*log10(2.44175 * 10^9)
     *                  = 187.75
     *
     * FSPL_LIGHT       = 20*log10(4*pi/c)
     *                  = 20*log10(4*pi/(2.9979*10^8))
     *                  = -147.55
     *
     * FSPL_DISTANCE_1M = 20*log10(1m)
     *                  = 0
     *
     * PATH_LOSS_AT_1M  = FSPL_DISTANCE_1M + FSPL_FREQ + FSPL_LIGHT
     *                  = 0                + 187.75    + (-147.55)
     *                  = 40.20 [round to 41]
     *
     * Note that PATH_LOSS_AT_1M is rounded to 41 instead to the more natural 40. The first version
     * of this file had a typo that caused the value to be close to 41; when this was discovered,
     * the value 41 was already used in many places, and it was more important to be consistent
     * rather than exact.
     *
     * Given this we can work out a formula for distance from a given RSSI (received signal strength
     * indicator) and a given value for the expected strength at one meter from the beacon (aka
     * calibrated transmission power). Both values are in dBm.
     *
     * FSPL = 20*log10(d) + PATH_LOSS_AT_1M = full_power - RSSI
     *        20*log10(d) + PATH_LOSS_AT_1M = power_at_1m + PATH_LOSS_AT_1M - RSSI
     *        20*log10(d)                   = power_at_1m - RSSI
     *           log10(d)                   = (power_at_1m - RSSI) / 20
     *                 d                    = 10 ^ ((power_at_1m - RSSI) / 20)
     *
     * Note: because of how logarithms work, units get a bit funny. If you take a two values x and y
     * whose units are dBm, the value of x - y has units of dB, not dBm. Similarly, if x is dBm and
     * y is in dB, then x - y will be in dBm.
     */

    /* (dBm) PATH_LOSS at 1m for isotropic antenna transmitting BLE */
    public static final int PATH_LOSS_AT_1M = 41;

    /** Different region categories, based on distance range. */
    public static final class Region {

        public static final int UNKNOWN = -1;
        public static final int NEAR = 0;
        public static final int MID = 1;
        public static final int FAR = 2;

        private Region() {}
    }

    // Cutoff distances between different regions.
    public static final double NEAR_TO_MID_METERS = 0.5;
    public static final double MID_TO_FAR_METERS = 2.0;

    public static final int DEFAULT_CALIBRATED_TX_POWER = -77;

    private RangingUtils() {}

    /**
     * Convert RSSI to path loss using the free space path loss equation. See <a
     * href="http://en.wikipedia.org/wiki/Free-space_path_loss">Free-space_path_loss</a>
     *
     * @param rssi Received Signal Strength Indication (RSSI) in dBm
     * @param calibratedTxPower the calibrated power of the transmitter (dBm) at 1 meter
     * @return The calculated path loss.
     */
    public static int pathLossFromRssi(int rssi, int calibratedTxPower) {
        return calibratedTxPower + PATH_LOSS_AT_1M - rssi;
    }

    /**
     * Convert RSSI to distance using the free space path loss equation. See <a
     * href="http://en.wikipedia.org/wiki/Free-space_path_loss">Free-space_path_loss</a>
     *
     * @param rssi Received Signal Strength Indication (RSSI) in dBm
     * @param calibratedTxPower the calibrated power of the transmitter (dBm) at 1 meter
     * @return the distance at which that rssi value would occur in meters
     */
    public static double distanceFromRssi(int rssi, int calibratedTxPower) {
        return Math.pow(10, (calibratedTxPower - rssi) / 20.0);
    }

    /**
     * Determine the region of a beacon given its perceived distance.
     *
     * @param distance The measured distance in meters.
     * @return the region as one of the constants in {@link Region}.
     */
    public static int regionFromDistance(double distance) {
        if (distance < 0) {
            return Region.UNKNOWN;
        }
        if (distance <= NEAR_TO_MID_METERS) {
            return Region.NEAR;
        }
        if (distance <= MID_TO_FAR_METERS) {
            return Region.MID;
        }
        return Region.FAR;
    }

    /**
     * Convert distance to RSSI using the free space path loss equation. See <a
     * href="http://en.wikipedia.org/wiki/Free-space_path_loss">Free-space_path_loss</a>
     *
     * @param distanceInMeters distance in meters (m)
     * @param calibratedTxPower transmitted power (dBm) calibrated to 1 meter
     * @return the rssi (dBm) that would be measured at that distance
     */
    public static int rssiFromDistance(double distanceInMeters, int calibratedTxPower) {
        return distanceInMeters == 0
                ? calibratedTxPower + PATH_LOSS_AT_1M
                : (int) (calibratedTxPower - (20 * Math.log10(distanceInMeters)));
    }
}

