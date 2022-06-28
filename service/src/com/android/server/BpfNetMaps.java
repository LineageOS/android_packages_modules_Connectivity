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

package com.android.server;

import static android.net.ConnectivityManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_2;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_RESTRICTED;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_STANDBY;
import static android.system.OsConstants.EINVAL;
import static android.system.OsConstants.EOPNOTSUPP;

import android.net.INetd;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.Struct.U32;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * BpfNetMaps is responsible for providing traffic controller relevant functionality.
 *
 * {@hide}
 */
public class BpfNetMaps {
    private static final String TAG = "BpfNetMaps";
    private final INetd mNetd;
    // Use legacy netd for releases before T.
    private static final boolean PRE_T = !SdkLevel.isAtLeastT();
    private static boolean sInitialized = false;

    // Lock for sConfigurationMap entry for UID_RULES_CONFIGURATION_KEY.
    // This entry is not accessed by others.
    // BpfNetMaps acquires this lock while sequence of read, modify, and write.
    private static final Object sUidRulesConfigBpfMapLock = new Object();

    private static final String CONFIGURATION_MAP_PATH =
            "/sys/fs/bpf/netd_shared/map_netd_configuration_map";
    private static final U32 UID_RULES_CONFIGURATION_KEY = new U32(0);
    private static BpfMap<U32, U32> sConfigurationMap = null;

    // LINT.IfChange(match_type)
    private static final long NO_MATCH = 0;
    private static final long HAPPY_BOX_MATCH = (1 << 0);
    private static final long PENALTY_BOX_MATCH = (1 << 1);
    private static final long DOZABLE_MATCH = (1 << 2);
    private static final long STANDBY_MATCH = (1 << 3);
    private static final long POWERSAVE_MATCH = (1 << 4);
    private static final long RESTRICTED_MATCH = (1 << 5);
    private static final long LOW_POWER_STANDBY_MATCH = (1 << 6);
    private static final long IIF_MATCH = (1 << 7);
    private static final long LOCKDOWN_VPN_MATCH = (1 << 8);
    private static final long OEM_DENY_1_MATCH = (1 << 9);
    private static final long OEM_DENY_2_MATCH = (1 << 10);
    private static final long OEM_DENY_3_MATCH = (1 << 11);
    // LINT.ThenChange(packages/modules/Connectivity/bpf_progs/bpf_shared.h)

    // TODO: Use Java BpfMap instead of JNI code (TrafficController) for map update.
    // Currently, BpfNetMaps uses TrafficController for map update and TrafficController
    // (changeUidOwnerRule and toggleUidOwnerMap) also does conversion from "firewall chain" to
    // "match". Migrating map update from JNI to Java BpfMap will solve this duplication.
    private static final SparseLongArray FIREWALL_CHAIN_TO_MATCH = new SparseLongArray();
    static {
        FIREWALL_CHAIN_TO_MATCH.put(FIREWALL_CHAIN_DOZABLE, DOZABLE_MATCH);
        FIREWALL_CHAIN_TO_MATCH.put(FIREWALL_CHAIN_STANDBY, STANDBY_MATCH);
        FIREWALL_CHAIN_TO_MATCH.put(FIREWALL_CHAIN_POWERSAVE, POWERSAVE_MATCH);
        FIREWALL_CHAIN_TO_MATCH.put(FIREWALL_CHAIN_RESTRICTED, RESTRICTED_MATCH);
        FIREWALL_CHAIN_TO_MATCH.put(FIREWALL_CHAIN_LOW_POWER_STANDBY, LOW_POWER_STANDBY_MATCH);
        FIREWALL_CHAIN_TO_MATCH.put(FIREWALL_CHAIN_OEM_DENY_1, OEM_DENY_1_MATCH);
        FIREWALL_CHAIN_TO_MATCH.put(FIREWALL_CHAIN_OEM_DENY_2, OEM_DENY_2_MATCH);
        FIREWALL_CHAIN_TO_MATCH.put(FIREWALL_CHAIN_OEM_DENY_3, OEM_DENY_3_MATCH);
    }

    /**
     * Only tests or BpfNetMaps#ensureInitialized can call this function.
     */
    @VisibleForTesting
    public static void initialize(final Dependencies deps) {
        sConfigurationMap = deps.getConfigurationMap();
    }

    /**
     * Initializes the class if it is not already initialized. This method will open maps but not
     * cause any other effects. This method may be called multiple times on any thread.
     */
    private static synchronized void ensureInitialized() {
        if (sInitialized) return;
        if (!PRE_T) {
            System.loadLibrary("service-connectivity");
            native_init();
            initialize(new Dependencies());
        }
        sInitialized = true;
    }

    /**
     * Dependencies of BpfNetMaps, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         *  Get configuration BPF map.
         */
        public BpfMap<U32, U32> getConfigurationMap() {
            try {
                return new BpfMap<>(
                        CONFIGURATION_MAP_PATH, BpfMap.BPF_F_RDWR, U32.class, U32.class);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot open netd configuration map: " + e);
                return null;
            }
        }
    }

    /** Constructor used after T that doesn't need to use netd anymore. */
    public BpfNetMaps() {
        this(null);

        if (PRE_T) throw new IllegalArgumentException("BpfNetMaps need to use netd before T");
    }

    public BpfNetMaps(final INetd netd) {
        ensureInitialized();
        mNetd = netd;
    }

    /**
     * Get corresponding match from firewall chain.
     */
    @VisibleForTesting
    public long getMatchByFirewallChain(final int chain) {
        final long match = FIREWALL_CHAIN_TO_MATCH.get(chain, NO_MATCH);
        if (match == NO_MATCH) {
            throw new ServiceSpecificException(EINVAL, "Invalid firewall chain: " + chain);
        }
        return match;
    }

    private void maybeThrow(final int err, final String msg) {
        if (err != 0) {
            throw new ServiceSpecificException(err, msg + ": " + Os.strerror(err));
        }
    }

    private void throwIfPreT(final String msg) {
        if (PRE_T) {
            throw new UnsupportedOperationException(msg);
        }
    }

    /**
     * Add naughty app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addNaughtyApp(final int uid) {
        final int err = native_addNaughtyApp(uid);
        maybeThrow(err, "Unable to add naughty app");
    }

    /**
     * Remove naughty app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeNaughtyApp(final int uid) {
        final int err = native_removeNaughtyApp(uid);
        maybeThrow(err, "Unable to remove naughty app");
    }

    /**
     * Add nice app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addNiceApp(final int uid) {
        final int err = native_addNiceApp(uid);
        maybeThrow(err, "Unable to add nice app");
    }

    /**
     * Remove nice app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeNiceApp(final int uid) {
        final int err = native_removeNiceApp(uid);
        maybeThrow(err, "Unable to remove nice app");
    }

    /**
     * Set target firewall child chain
     *
     * @param childChain target chain to enable
     * @param enable     whether to enable or disable child chain.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void setChildChain(final int childChain, final boolean enable) {
        throwIfPreT("setChildChain is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        try {
            synchronized (sUidRulesConfigBpfMapLock) {
                final U32 config = sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY);
                final long newConfig = enable ? (config.val | match) : (config.val & ~match);
                sConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(newConfig));
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to set child chain: " + Os.strerror(e.errno));
        }
    }

    /**
     * Get the specified firewall chain's status.
     *
     * @param childChain target chain
     * @return {@code true} if chain is enabled, {@code false} if chain is not enabled.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public boolean isChainEnabled(final int childChain) {
        throwIfPreT("isChainEnabled is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        try {
            final U32 config = sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY);
            return (config.val & match) != 0;
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to get firewall chain status: " + Os.strerror(e.errno));
        }
    }

    /**
     * Replaces the contents of the specified UID-based firewall chain.
     *
     * The chain may be an allowlist chain or a denylist chain. A denylist chain contains DROP
     * rules for the specified UIDs and a RETURN rule at the end. An allowlist chain contains RETURN
     * rules for the system UID range (0 to {@code UID_APP} - 1), RETURN rules for the specified
     * UIDs, and a DROP rule at the end. The chain will be created if it does not exist.
     *
     * @param chainName   The name of the chain to replace.
     * @param isAllowlist Whether this is an allowlist or denylist chain.
     * @param uids        The list of UIDs to allow/deny.
     * @return 0 if the chain was successfully replaced, errno otherwise.
     */
    public int replaceUidChain(final String chainName, final boolean isAllowlist,
            final int[] uids) {
        final int err = native_replaceUidChain(chainName, isAllowlist, uids);
        if (err != 0) {
            Log.e(TAG, "replaceUidChain failed: " + Os.strerror(-err));
        }
        return -err;
    }

    /**
     * Set firewall rule for uid
     *
     * @param childChain   target chain
     * @param uid          uid to allow/deny
     * @param firewallRule either FIREWALL_RULE_ALLOW or FIREWALL_RULE_DENY
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void setUidRule(final int childChain, final int uid, final int firewallRule) {
        final int err = native_setUidRule(childChain, uid, firewallRule);
        maybeThrow(err, "Unable to set uid rule");
    }

    /**
     * Add ingress interface filtering rules to a list of UIDs
     *
     * For a given uid, once a filtering rule is added, the kernel will only allow packets from the
     * allowed interface and loopback to be sent to the list of UIDs.
     *
     * Calling this method on one or more UIDs with an existing filtering rule but a different
     * interface name will result in the filtering rule being updated to allow the new interface
     * instead. Otherwise calling this method will not affect existing rules set on other UIDs.
     *
     * @param ifName the name of the interface on which the filtering rules will allow packets to
     *               be received.
     * @param uids   an array of UIDs which the filtering rules will be set
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addUidInterfaceRules(final String ifName, final int[] uids) throws RemoteException {
        if (PRE_T) {
            mNetd.firewallAddUidInterfaceRules(ifName, uids);
            return;
        }
        final int err = native_addUidInterfaceRules(ifName, uids);
        maybeThrow(err, "Unable to add uid interface rules");
    }

    /**
     * Remove ingress interface filtering rules from a list of UIDs
     *
     * Clear the ingress interface filtering rules from the list of UIDs which were previously set
     * by addUidInterfaceRules(). Ignore any uid which does not have filtering rule.
     *
     * @param uids an array of UIDs from which the filtering rules will be removed
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeUidInterfaceRules(final int[] uids) throws RemoteException {
        if (PRE_T) {
            mNetd.firewallRemoveUidInterfaceRules(uids);
            return;
        }
        final int err = native_removeUidInterfaceRules(uids);
        maybeThrow(err, "Unable to remove uid interface rules");
    }

    /**
     * Update lockdown rule for uid
     *
     * @param  uid          target uid to add/remove the rule
     * @param  add          {@code true} to add the rule, {@code false} to remove the rule.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void updateUidLockdownRule(final int uid, final boolean add) {
        final int err = native_updateUidLockdownRule(uid, add);
        maybeThrow(err, "Unable to update lockdown rule");
    }

    /**
     * Request netd to change the current active network stats map.
     *
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void swapActiveStatsMap() {
        final int err = native_swapActiveStatsMap();
        maybeThrow(err, "Unable to swap active stats map");
    }

    /**
     * Assigns android.permission.INTERNET and/or android.permission.UPDATE_DEVICE_STATS to the uids
     * specified. Or remove all permissions from the uids.
     *
     * @param permissions The permission to grant, it could be either PERMISSION_INTERNET and/or
     *                    PERMISSION_UPDATE_DEVICE_STATS. If the permission is NO_PERMISSIONS, then
     *                    revoke all permissions for the uids.
     * @param uids        uid of users to grant permission
     * @throws RemoteException when netd has crashed.
     */
    public void setNetPermForUids(final int permissions, final int[] uids) throws RemoteException {
        if (PRE_T) {
            mNetd.trafficSetNetPermForUids(permissions, uids);
            return;
        }
        native_setPermissionForUids(permissions, uids);
    }

    /**
     * Dump BPF maps
     *
     * @param fd file descriptor to output
     * @throws IOException when file descriptor is invalid.
     * @throws ServiceSpecificException when the method is called on an unsupported device.
     */
    public void dump(final FileDescriptor fd, boolean verbose)
            throws IOException, ServiceSpecificException {
        if (PRE_T) {
            throw new ServiceSpecificException(
                    EOPNOTSUPP, "dumpsys connectivity trafficcontroller dump not available on pre-T"
                    + " devices, use dumpsys netd trafficcontroller instead.");
        }
        native_dump(fd, verbose);
    }

    private static native void native_init();
    private native int native_addNaughtyApp(int uid);
    private native int native_removeNaughtyApp(int uid);
    private native int native_addNiceApp(int uid);
    private native int native_removeNiceApp(int uid);
    private native int native_replaceUidChain(String name, boolean isAllowlist, int[] uids);
    private native int native_setUidRule(int childChain, int uid, int firewallRule);
    private native int native_addUidInterfaceRules(String ifName, int[] uids);
    private native int native_removeUidInterfaceRules(int[] uids);
    private native int native_updateUidLockdownRule(int uid, boolean add);
    private native int native_swapActiveStatsMap();
    private native void native_setPermissionForUids(int permissions, int[] uids);
    private native void native_dump(FileDescriptor fd, boolean verbose);
}
