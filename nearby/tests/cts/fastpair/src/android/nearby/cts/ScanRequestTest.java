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

package android.nearby.cts;

import static android.nearby.ScanRequest.SCAN_MODE_BALANCED;
import static android.nearby.ScanRequest.SCAN_MODE_LOW_LATENCY;
import static android.nearby.ScanRequest.SCAN_MODE_LOW_POWER;
import static android.nearby.ScanRequest.SCAN_MODE_NO_POWER;
import static android.nearby.ScanRequest.SCAN_TYPE_EXPOSURE_NOTIFICATION;
import static android.nearby.ScanRequest.SCAN_TYPE_FAST_PAIR;
import static android.nearby.ScanRequest.SCAN_TYPE_NEARBY_PRESENCE;
import static android.nearby.ScanRequest.SCAN_TYPE_NEARBY_SHARE;

import static com.google.common.truth.Truth.assertThat;

import android.nearby.ScanRequest;
import android.os.Build;
import android.os.WorkSource;

import androidx.annotation.RequiresApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class ScanRequestTest {

    private static final int UID = 1001;
    private static final String APP_NAME = "android.nearby.tests";

    // Valid scan type must be set to one of ScanRequest#SCAN_TYPE_
    @Test(expected = IllegalStateException.class)
    public void testScanType_notSet_throwsException() {
        new ScanRequest.Builder().setScanMode(SCAN_MODE_BALANCED).build();
    }

    @Test
    public void testScanMode_defaultLowPower() {
        ScanRequest request = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_FAST_PAIR)
                .build();

        assertThat(request.getScanMode()).isEqualTo(SCAN_MODE_LOW_POWER);
    }

    /** Verify setting work source with null value in the scan request is allowed*/
    @Test
    public void testSetWorkSource_nullValue() {
        ScanRequest request = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_EXPOSURE_NOTIFICATION)
                .setWorkSource(null)
                .build();

        // Null work source is allowed.
        assertThat(request.getWorkSource().isEmpty()).isTrue();
    }

    /** Verify toString returns expected string. */
    @Test
    public void testToString() {
        WorkSource workSource = getWorkSource();
        ScanRequest request = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_SHARE)
                .setScanMode(SCAN_MODE_BALANCED)
                .setEnableBle(true)
                .setWorkSource(workSource)
                .build();

        assertThat(request.toString()).isEqualTo(
                "Request[scanType=2, scanMode=SCAN_MODE_BALANCED, "
                        + "enableBle=true, workSource=WorkSource{" + UID + " " + APP_NAME + "}]");
    }

    /** Verify toString works correctly with null WorkSource. */
    @Test
    public void testToString_nullWorkSource() {
        ScanRequest request = new ScanRequest.Builder().setScanType(
                SCAN_TYPE_FAST_PAIR).setWorkSource(null).build();

        assertThat(request.toString()).isEqualTo("Request[scanType=1, "
                + "scanMode=SCAN_MODE_LOW_POWER, enableBle=true, workSource=WorkSource{}]");
    }

    @Test
    public void testisEnableBle_defaultTrue() {
        ScanRequest request = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_FAST_PAIR)
                .build();

        assertThat(request.isEnableBle()).isTrue();
    }

    @Test
    public void test_isValidScanType() {
        assertThat(ScanRequest.isValidScanType(SCAN_TYPE_FAST_PAIR)).isTrue();
        assertThat(ScanRequest.isValidScanType(SCAN_TYPE_NEARBY_SHARE)).isTrue();
        assertThat(ScanRequest.isValidScanType(SCAN_TYPE_NEARBY_PRESENCE)).isTrue();
        assertThat(ScanRequest.isValidScanType(SCAN_TYPE_EXPOSURE_NOTIFICATION)).isTrue();

        assertThat(ScanRequest.isValidScanType(0)).isFalse();
        assertThat(ScanRequest.isValidScanType(5)).isFalse();
    }

    @Test
    public void test_isValidScanMode() {
        assertThat(ScanRequest.isValidScanMode(SCAN_MODE_LOW_LATENCY)).isTrue();
        assertThat(ScanRequest.isValidScanMode(SCAN_MODE_BALANCED)).isTrue();
        assertThat(ScanRequest.isValidScanMode(SCAN_MODE_LOW_POWER)).isTrue();
        assertThat(ScanRequest.isValidScanMode(SCAN_MODE_NO_POWER)).isTrue();

        assertThat(ScanRequest.isValidScanMode(3)).isFalse();
        assertThat(ScanRequest.isValidScanMode(-2)).isFalse();
    }

    @Test
    public void test_scanModeToString() {
        assertThat(ScanRequest.scanModeToString(2)).isEqualTo("SCAN_MODE_LOW_LATENCY");
        assertThat(ScanRequest.scanModeToString(1)).isEqualTo("SCAN_MODE_BALANCED");
        assertThat(ScanRequest.scanModeToString(0)).isEqualTo("SCAN_MODE_LOW_POWER");
        assertThat(ScanRequest.scanModeToString(-1)).isEqualTo("SCAN_MODE_NO_POWER");

        assertThat(ScanRequest.scanModeToString(3)).isEqualTo("SCAN_MODE_INVALID");
        assertThat(ScanRequest.scanModeToString(-2)).isEqualTo("SCAN_MODE_INVALID");
    }

    private static WorkSource getWorkSource() {
        return new WorkSource(UID, APP_NAME);
    }
}
