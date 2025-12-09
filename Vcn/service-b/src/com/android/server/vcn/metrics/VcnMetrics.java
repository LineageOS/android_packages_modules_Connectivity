/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.vcn.metrics;

import static com.android.server.vcn.metrics.VcnStatsLog.VCN_GATEWAY_CONNECTION_STATE_CHANGED__GW_TEARDOWN_REASON__GATEWAY_TEARDOWN_REASON_INTERNAL_ERROR;
import static com.android.server.vcn.metrics.VcnStatsLog.VCN_GATEWAY_CONNECTION_STATE_CHANGED__GW_TEARDOWN_REASON__GATEWAY_TEARDOWN_REASON_NETWORK_AGENT_UNWANTED;
import static com.android.server.vcn.metrics.VcnStatsLog.VCN_GATEWAY_CONNECTION_STATE_CHANGED__GW_TEARDOWN_REASON__GATEWAY_TEARDOWN_REASON_NONE;
import static com.android.server.vcn.metrics.VcnStatsLog.VCN_GATEWAY_CONNECTION_STATE_CHANGED__GW_TEARDOWN_REASON__GATEWAY_TEARDOWN_REASON_REQUESTED;
import static com.android.server.vcn.metrics.VcnStatsLog.VCN_GATEWAY_CONNECTION_STATE_CHANGED__GW_TEARDOWN_REASON__GATEWAY_TEARDOWN_REASON_UNSPECIFIED;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Utility class for logging VCN metrics. */
public class VcnMetrics {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"GATEWAY_TEARDOWN_REASON_"},
            value = {
                GATEWAY_TEARDOWN_REASON_NONE,
                GATEWAY_TEARDOWN_REASON_INTERNAL_ERROR,
                GATEWAY_TEARDOWN_REASON_NETWORK_AGENT_UNWANTED,
                GATEWAY_TEARDOWN_REASON_REQUESTED
            })
    public @interface GatewayTeardownReason {}

    public static final int GATEWAY_TEARDOWN_REASON_NONE =
            VCN_GATEWAY_CONNECTION_STATE_CHANGED__GW_TEARDOWN_REASON__GATEWAY_TEARDOWN_REASON_NONE;
    public static final int GATEWAY_TEARDOWN_REASON_INTERNAL_ERROR =
            VCN_GATEWAY_CONNECTION_STATE_CHANGED__GW_TEARDOWN_REASON__GATEWAY_TEARDOWN_REASON_INTERNAL_ERROR;
    public static final int GATEWAY_TEARDOWN_REASON_NETWORK_AGENT_UNWANTED =
            VCN_GATEWAY_CONNECTION_STATE_CHANGED__GW_TEARDOWN_REASON__GATEWAY_TEARDOWN_REASON_NETWORK_AGENT_UNWANTED;
    public static final int GATEWAY_TEARDOWN_REASON_REQUESTED =
            VCN_GATEWAY_CONNECTION_STATE_CHANGED__GW_TEARDOWN_REASON__GATEWAY_TEARDOWN_REASON_REQUESTED;
    public static final int GATEWAY_TEARDOWN_REASON_UNSPECIFIED =
            VCN_GATEWAY_CONNECTION_STATE_CHANGED__GW_TEARDOWN_REASON__GATEWAY_TEARDOWN_REASON_UNSPECIFIED;

    /** Log an atom when a VcnGatewayConnection has entered safe mode. */
    public void logEnterSafeMode(int gatewayConnectionId) {
        VcnStatsLog.write(
                VcnStatsLog.VCN_GATEWAY_CONNECTION_STATE_CHANGED,
                gatewayConnectionId,
                GATEWAY_TEARDOWN_REASON_NONE,
                true /* isInSafeMode */);
    }

    /** Log an atom when a VcnGatewayConnection has exited safe mode. */
    public void logExitSafeMode(int gatewayConnectionId) {
        VcnStatsLog.write(
                VcnStatsLog.VCN_GATEWAY_CONNECTION_STATE_CHANGED,
                gatewayConnectionId,
                GATEWAY_TEARDOWN_REASON_NONE,
                false /* isInSafeMode */);
    }

    /**
     * Log an atom when a VcnGatewayConnection has been torn down with reason. It will also reset
     * other VcnGatewayConnection related states i.e. safemode.
     */
    public void logVcnGatewayTeardown(int gatewayConnectionId, @GatewayTeardownReason int reason) {
        VcnStatsLog.write(
                VcnStatsLog.VCN_GATEWAY_CONNECTION_STATE_CHANGED,
                gatewayConnectionId,
                reason,
                false /* isInSafeMode */);
    }

    /** Log an atom when VCN network is connected. */
    public void logVcnNetworkConnected(int gatewayConnectionId, int networkId) {
        VcnStatsLog.write(
                VcnStatsLog.VCN_NETWORK_STATE_CHANGED,
                gatewayConnectionId,
                networkId,
                true /* isConnected */,
                false /* isValidated */);
    }

    /**
     * Log an atom when VCN network is being torn down. It will also reset other state related to
     * the network i.e. validated.
     */
    public void logVcnNetworkNotConnected(int gatewayConnectionId, int networkId) {
        VcnStatsLog.write(
                VcnStatsLog.VCN_NETWORK_STATE_CHANGED,
                gatewayConnectionId,
                networkId,
                false /* isConnected */,
                false /* isValidated */);
    }

    /** Log an atom when VCN network has been validated. */
    public void logVcnNetworkValidated(int gatewayConnectionId, int networkId) {
        VcnStatsLog.write(
                VcnStatsLog.VCN_NETWORK_STATE_CHANGED,
                gatewayConnectionId,
                networkId,
                true /* isConnected */,
                true /* isValidated */);
    }

    /** Log an atom when VCN network has been not validated. */
    public void logVcnNetworkNotValidated(int gatewayConnectionId, int networkId) {
        VcnStatsLog.write(
                VcnStatsLog.VCN_NETWORK_STATE_CHANGED,
                gatewayConnectionId,
                networkId,
                true /* isConnected */,
                false /* isValidated */);
    }

    /**
     * Log an atom about number of validated underlying network available for VCN network selection.
     */
    public void logValidatedUnderlyingNetworkCount(int gatewayConnectionId, int count) {
        VcnStatsLog.write(
                VcnStatsLog.VCN_UNDERLYING_NETWORKS_STATE_CHANGED, gatewayConnectionId, count);
    }
}
