/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.net.vcn.cts;

import static android.content.pm.PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION;

import static com.android.compatibility.common.util.PropertyUtil.getVsrApiLevel;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.vcn.VcnManager;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VcnSystemRequirementsTest {
    private static final int VENDOR_API_FOR_ANDROID_V = 202404;
    private final Context mContext = InstrumentationRegistry.getContext();
    private final VcnManager mVcnManager = mContext.getSystemService(VcnManager.class);

    private static void assertNotNullVcnDependencies(Context context) {
        assertNotNull(
                "CarrierConfigManager must be present on this device",
                context.getSystemService(CarrierConfigManager.class));
        assertNotNull(
                "SubscriptionManager must be present on this device",
                context.getSystemService(SubscriptionManager.class));
        assertNotNull(
                "ConnectivityManager must be present on this device",
                context.getSystemService(ConnectivityManager.class));
        assertNotNull(
                "TelephonyManager must be present on this device",
                context.getSystemService(TelephonyManager.class));
    }

    /**
     * This test verifies that devices with vendor API greater than or equal to V, and which support
     * FEATURE_TELEPHONY_SUBSCRIPTION, must also implement VCN and its dependent system services.
     */
    @Test
    public void testVcnAndDependenciesOnVOrNewerDevices() throws Exception {
        final boolean hasFeatureTelSubscription =
                mContext.getPackageManager().hasSystemFeature(FEATURE_TELEPHONY_SUBSCRIPTION);
        assumeTrue(getVsrApiLevel() >= VENDOR_API_FOR_ANDROID_V);
        assumeTrue(hasFeatureTelSubscription);

        assertNotNullVcnDependencies(mContext);
        assertNotNull("VcnManager must be present on this device", mVcnManager);
    }
}
