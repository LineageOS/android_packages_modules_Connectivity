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

package android.net.cts.util;

import static android.net.ipsec.ike.SaProposal.KEY_LEN_AES_128;
import static android.net.ipsec.ike.SaProposal.KEY_LEN_UNUSED;

import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.IkeFqdnIdentification;
import android.net.ipsec.ike.IkeSaProposal;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.SaProposal;
import android.net.ipsec.ike.TunnelModeChildSessionParams;

/** Shared testing parameters and util methods for testing IKE */
public class IkeSessionTestUtils {
    private static final String TEST_CLIENT_ADDR = "test.client.com";
    private static final String TEST_SERVER_ADDR = "test.server.com";
    private static final String TEST_SERVER = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";

    public static final IkeSaProposal SA_PROPOSAL = new IkeSaProposal.Builder()
            .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_3DES, KEY_LEN_UNUSED)
            .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96)
            .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_AES128_XCBC)
            .addDhGroup(SaProposal.DH_GROUP_1024_BIT_MODP)
            .build();
    public static final ChildSaProposal CHILD_PROPOSAL = new ChildSaProposal.Builder()
            .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_128)
            .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_NONE)
            .addDhGroup(SaProposal.DH_GROUP_1024_BIT_MODP)
            .build();

    public static final IkeSessionParams IKE_PARAMS =
            new IkeSessionParams.Builder()
                    .setServerHostname(TEST_SERVER)
                    .addSaProposal(SA_PROPOSAL)
                    .setLocalIdentification(new IkeFqdnIdentification(TEST_CLIENT_ADDR))
                    .setRemoteIdentification(new IkeFqdnIdentification(TEST_SERVER_ADDR))
                    .setAuthPsk("psk".getBytes())
                    .build();
    public static final TunnelModeChildSessionParams CHILD_PARAMS =
            new TunnelModeChildSessionParams.Builder()
                    .addSaProposal(CHILD_PROPOSAL)
                    .build();
}
