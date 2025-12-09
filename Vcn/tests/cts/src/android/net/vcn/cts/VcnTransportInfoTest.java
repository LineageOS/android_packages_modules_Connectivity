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

import static android.net.NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS;
import static android.net.vcn.VcnGatewayConnectionConfig.MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET;

import static org.junit.Assert.assertEquals;

import android.net.vcn.Flags;
import android.net.vcn.VcnTransportInfo;
import android.os.Parcel;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_MAINLINE_VCN_MODULE_API)
public class VcnTransportInfoTest extends VcnTestBase {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final int NAT_TIMEOUT_SECONDS = 600;

    @Test
    public void testBuilderAndGettersWithMinimumSet() {
        final VcnTransportInfo transportInfo = new VcnTransportInfo.Builder().build();

        assertEquals(
                MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET,
                transportInfo.getMinUdpPort4500NatTimeoutSeconds());

        assertEquals(REDACT_FOR_NETWORK_SETTINGS, transportInfo.getApplicableRedactions());
        assertEquals(transportInfo, transportInfo.makeCopy(REDACT_FOR_NETWORK_SETTINGS));
    }

    @Test
    public void testBuilderAndGettersWithNatTimeout() {
        final VcnTransportInfo transportInfo =
                new VcnTransportInfo.Builder()
                        .setMinUdpPort4500NatTimeoutSeconds(NAT_TIMEOUT_SECONDS)
                        .build();

        assertEquals(NAT_TIMEOUT_SECONDS, transportInfo.getMinUdpPort4500NatTimeoutSeconds());
    }

    @Test
    public void testParcelUnparcel() {
        final VcnTransportInfo transportInfo =
                new VcnTransportInfo.Builder()
                        .setMinUdpPort4500NatTimeoutSeconds(NAT_TIMEOUT_SECONDS)
                        .build();

        final Parcel parcel = Parcel.obtain();
        transportInfo.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        assertEquals(transportInfo, VcnTransportInfo.CREATOR.createFromParcel(parcel));
    }
}
