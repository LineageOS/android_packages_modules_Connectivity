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

package com.android.server.connectivity;

import static android.net.DscpPolicy.STATUS_DELETED;
import static android.net.DscpPolicy.STATUS_INSUFFICIENT_PROCESSING_RESOURCES;
import static android.net.DscpPolicy.STATUS_POLICY_NOT_FOUND;
import static android.net.DscpPolicy.STATUS_SUCCESS;
import static android.system.OsConstants.ETH_P_ALL;

import android.annotation.NonNull;
import android.net.DscpPolicy;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.net.module.util.BpfMap;
import com.android.net.module.util.Struct;
import com.android.net.module.util.TcUtils;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.util.HashSet;
import java.util.Set;

/**
 * DscpPolicyTracker has a single entry point from ConnectivityService handler.
 * This guarantees that all code runs on the same thread and no locking is needed.
 */
public class DscpPolicyTracker {
    static {
        System.loadLibrary("com_android_connectivity_com_android_net_module_util_jni");
    }

    // After tethering and clat priorities.
    static final short PRIO_DSCP = 5;

    private static final String TAG = DscpPolicyTracker.class.getSimpleName();
    private static final String PROG_PATH =
            "/sys/fs/bpf/prog_dscp_policy_schedcls_set_dscp";
    // Name is "map + *.o + map_name + map". Can probably shorten this
    private static final String IPV4_POLICY_MAP_PATH = makeMapPath(
            "dscp_policy_ipv4_dscp_policies");
    private static final String IPV6_POLICY_MAP_PATH = makeMapPath(
            "dscp_policy_ipv6_dscp_policies");
    private static final int MAX_POLICIES = 16;

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/map_" + which + "_map";
    }

    private Set<String> mAttachedIfaces;

    private final BpfMap<Struct.U32, DscpPolicyValue> mBpfDscpIpv4Policies;
    private final BpfMap<Struct.U32, DscpPolicyValue> mBpfDscpIpv6Policies;
    private final SparseIntArray mPolicyIdToBpfMapIndex;

    public DscpPolicyTracker() throws ErrnoException {
        mAttachedIfaces = new HashSet<String>();

        mPolicyIdToBpfMapIndex = new SparseIntArray(MAX_POLICIES);
        mBpfDscpIpv4Policies = new BpfMap<Struct.U32, DscpPolicyValue>(IPV4_POLICY_MAP_PATH,
                BpfMap.BPF_F_RDWR, Struct.U32.class, DscpPolicyValue.class);
        mBpfDscpIpv6Policies = new BpfMap<Struct.U32, DscpPolicyValue>(IPV6_POLICY_MAP_PATH,
                BpfMap.BPF_F_RDWR, Struct.U32.class, DscpPolicyValue.class);
    }

    private int getFirstFreeIndex() {
        for (int i = 0; i < MAX_POLICIES; i++) {
            if (mPolicyIdToBpfMapIndex.indexOfValue(i) < 0) return i;
        }
        return MAX_POLICIES;
    }

    private void sendStatus(NetworkAgentInfo nai, int policyId, int status) {
        try {
            nai.networkAgent.onDscpPolicyStatusUpdated(policyId, status);
        } catch (RemoteException e) {
            Log.d(TAG, "Failed update policy status: ", e);
        }
    }

    private boolean matchesIpv4(DscpPolicy policy) {
        return ((policy.getDestinationAddress() == null
                       || policy.getDestinationAddress() instanceof Inet4Address)
            && (policy.getSourceAddress() == null
                        || policy.getSourceAddress() instanceof Inet4Address));
    }

    private boolean matchesIpv6(DscpPolicy policy) {
        return ((policy.getDestinationAddress() == null
                       || policy.getDestinationAddress() instanceof Inet6Address)
            && (policy.getSourceAddress() == null
                        || policy.getSourceAddress() instanceof Inet6Address));
    }

    private int addDscpPolicyInternal(DscpPolicy policy) {
        // If there is no existing policy with a matching ID, and we are already at
        // the maximum number of policies then return INSUFFICIENT_PROCESSING_RESOURCES.
        final int existingIndex = mPolicyIdToBpfMapIndex.get(policy.getPolicyId(), -1);
        if (existingIndex == -1 && mPolicyIdToBpfMapIndex.size() >= MAX_POLICIES) {
            return STATUS_INSUFFICIENT_PROCESSING_RESOURCES;
        }

        // Currently all classifiers are supported, if any are removed return
        // STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED,
        // and for any other generic error STATUS_REQUEST_DECLINED

        int addIndex = 0;
        // If a policy with a matching ID exists, replace it, otherwise use the next free
        // index for the policy.
        if (existingIndex != -1) {
            addIndex = mPolicyIdToBpfMapIndex.get(policy.getPolicyId());
        } else {
            addIndex = getFirstFreeIndex();
        }

        try {
            mPolicyIdToBpfMapIndex.put(policy.getPolicyId(), addIndex);

            // Add v4 policy to mBpfDscpIpv4Policies if source and destination address
            // are both null or if they are both instances of Inet6Address.
            if (matchesIpv4(policy)) {
                mBpfDscpIpv4Policies.insertOrReplaceEntry(
                        new Struct.U32(addIndex),
                        new DscpPolicyValue(policy.getSourceAddress(),
                            policy.getDestinationAddress(),
                            policy.getSourcePort(), policy.getDestinationPortRange(),
                            (short) policy.getProtocol(), (short) policy.getDscpValue()));
            }

            // Add v6 policy to mBpfDscpIpv6Policies if source and destination address
            // are both null or if they are both instances of Inet6Address.
            if (matchesIpv6(policy)) {
                mBpfDscpIpv6Policies.insertOrReplaceEntry(
                        new Struct.U32(addIndex),
                        new DscpPolicyValue(policy.getSourceAddress(),
                                policy.getDestinationAddress(),
                                policy.getSourcePort(), policy.getDestinationPortRange(),
                                (short) policy.getProtocol(), (short) policy.getDscpValue()));
            }
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to insert policy into map: ", e);
            return STATUS_INSUFFICIENT_PROCESSING_RESOURCES;
        }

        return STATUS_SUCCESS;
    }

    /**
     * Add the provided DSCP policy to the bpf map. Attach bpf program dscp_policy to iface
     * if not already attached. Response will be sent back to nai with status.
     *
     * STATUS_SUCCESS - if policy was added successfully
     * STATUS_INSUFFICIENT_PROCESSING_RESOURCES - if max policies were already set
     */
    public void addDscpPolicy(NetworkAgentInfo nai, DscpPolicy policy) {
        if (!mAttachedIfaces.contains(nai.linkProperties.getInterfaceName())) {
            if (!attachProgram(nai.linkProperties.getInterfaceName())) {
                Log.e(TAG, "Unable to attach program");
                sendStatus(nai, policy.getPolicyId(), STATUS_INSUFFICIENT_PROCESSING_RESOURCES);
                return;
            }
        }

        int status = addDscpPolicyInternal(policy);
        sendStatus(nai, policy.getPolicyId(), status);
    }

    private void removePolicyFromMap(NetworkAgentInfo nai, int policyId, int index) {
        int status = STATUS_POLICY_NOT_FOUND;
        try {
            mBpfDscpIpv4Policies.replaceEntry(new Struct.U32(index), DscpPolicyValue.NONE);
            mBpfDscpIpv6Policies.replaceEntry(new Struct.U32(index), DscpPolicyValue.NONE);
            status = STATUS_DELETED;
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to delete policy from map: ", e);
        }

        sendStatus(nai, policyId, status);
    }

    /**
     * Remove specified DSCP policy and detach program if no other policies are active.
     */
    public void removeDscpPolicy(NetworkAgentInfo nai, int policyId) {
        if (!mAttachedIfaces.contains(nai.linkProperties.getInterfaceName())) {
            // Nothing to remove since program is not attached. Send update back for policy id.
            sendStatus(nai, policyId, STATUS_POLICY_NOT_FOUND);
            return;
        }

        if (mPolicyIdToBpfMapIndex.get(policyId, -1) != -1) {
            removePolicyFromMap(nai, policyId, mPolicyIdToBpfMapIndex.get(policyId));
            mPolicyIdToBpfMapIndex.delete(policyId);
        }

        // TODO: detach should only occur if no more policies are present on the nai's iface.
        if (mPolicyIdToBpfMapIndex.size() == 0) {
            detachProgram(nai.linkProperties.getInterfaceName());
        }
    }

    /**
     * Remove all DSCP policies and detach program.
     */
    // TODO: Remove all should only remove policies from corresponding nai iface.
    public void removeAllDscpPolicies(NetworkAgentInfo nai) {
        if (!mAttachedIfaces.contains(nai.linkProperties.getInterfaceName())) {
            // Nothing to remove since program is not attached. Send update for policy
            // id 0. The status update must contain a policy ID, and 0 is an invalid id.
            sendStatus(nai, 0, STATUS_SUCCESS);
            return;
        }

        for (int i = 0; i < mPolicyIdToBpfMapIndex.size(); i++) {
            removePolicyFromMap(nai, mPolicyIdToBpfMapIndex.keyAt(i),
                    mPolicyIdToBpfMapIndex.valueAt(i));
        }
        mPolicyIdToBpfMapIndex.clear();

        // Can detach program since no policies are active.
        detachProgram(nai.linkProperties.getInterfaceName());
    }

    /**
     * Attach BPF program
     */
    private boolean attachProgram(@NonNull String iface) {
        // TODO: attach needs to be per iface not program.

        try {
            NetworkInterface netIface = NetworkInterface.getByName(iface);
            TcUtils.tcFilterAddDevBpf(netIface.getIndex(), false, PRIO_DSCP, (short) ETH_P_ALL,
                    PROG_PATH);
        } catch (IOException e) {
            Log.e(TAG, "Unable to attach to TC on " + iface + ": " + e);
            return false;
        }
        mAttachedIfaces.add(iface);
        return true;
    }

    /**
     * Detach BPF program
     */
    public void detachProgram(@NonNull String iface) {
        try {
            NetworkInterface netIface = NetworkInterface.getByName(iface);
            if (netIface != null) {
                TcUtils.tcFilterDelDev(netIface.getIndex(), false, PRIO_DSCP, (short) ETH_P_ALL);
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to detach to TC on " + iface + ": " + e);
        }
        mAttachedIfaces.remove(iface);
    }
}
