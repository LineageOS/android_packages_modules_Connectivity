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

import android.net.INetd;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.Os;
import android.util.Log;

import com.android.modules.utils.build.SdkLevel;

/**
 * BpfNetMaps is responsible for providing traffic controller relevant functionality.
 *
 * {@hide}
 */
public class BpfNetMaps {
    private static final String TAG = "BpfNetMaps";
    private final INetd mNetd;
    // Use legacy netd for releases before T.
    private static final boolean USE_NETD = !SdkLevel.isAtLeastT();
    private static boolean sInitialized = false;

    /**
     * Initializes the class if it is not already initialized. This method will open maps but not
     * cause any other effects. This method may be called multiple times on any thread.
     */
    private static synchronized void ensureInitialized() {
        if (sInitialized) return;
        if (!USE_NETD) {
            System.loadLibrary("service-connectivity");
            native_init();
        }
        sInitialized = true;
    }

    public BpfNetMaps(INetd netd) {
        ensureInitialized();
        mNetd = netd;
    }

    private void maybeThrow(final int err, final String msg) {
        if (err != 0) {
            throw new ServiceSpecificException(err, msg + ": " + Os.strerror(err));
        }
    }

    /**
     * Add naughty app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addNaughtyApp(final int uid) throws RemoteException {
        if (USE_NETD) {
            mNetd.bandwidthAddNaughtyApp(uid);
            return;
        }
        final int err = native_addNaughtyApp(uid);
        maybeThrow(err, "Unable to add naughty app");
    }

    /**
     * Remove naughty app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeNaughtyApp(final int uid) throws RemoteException {
        if (USE_NETD) {
            mNetd.bandwidthRemoveNaughtyApp(uid);
            return;
        }
        final int err = native_removeNaughtyApp(uid);
        maybeThrow(err, "Unable to remove naughty app");
    }

    /**
     * Add nice app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addNiceApp(final int uid) throws RemoteException {
        if (USE_NETD) {
            mNetd.bandwidthAddNiceApp(uid);
            return;
        }
        final int err = native_addNiceApp(uid);
        maybeThrow(err, "Unable to add nice app");
    }

    /**
     * Remove nice app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeNiceApp(final int uid) throws RemoteException {
        if (USE_NETD) {
            mNetd.bandwidthRemoveNiceApp(uid);
            return;
        }
        final int err = native_removeNiceApp(uid);
        maybeThrow(err, "Unable to remove nice app");
    }

    /**
     * Set target firewall child chain
     *
     * @param childChain target chain to enable
     * @param enable     whether to enable or disable child chain.
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void setChildChain(final int childChain, final boolean enable) throws RemoteException {
        if (USE_NETD) {
            mNetd.firewallEnableChildChain(childChain, enable);
            return;
        }
        final int err = native_setChildChain(childChain, enable);
        maybeThrow(err, "Unable to set child chain");
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
     * @throws RemoteException when netd has crashed.
     */
    public int replaceUidChain(final String chainName, final boolean isAllowlist,
            final int[] uids) throws RemoteException {
        if (USE_NETD) {
            mNetd.firewallReplaceUidChain(chainName, isAllowlist, uids);
            return 0;
        }
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
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void setUidRule(final int childChain, final int uid, final int firewallRule)
            throws RemoteException {
        if (USE_NETD) {
            mNetd.firewallSetUidRule(childChain, uid, firewallRule);
            return;
        }
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
        if (USE_NETD) {
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
        if (USE_NETD) {
            mNetd.firewallRemoveUidInterfaceRules(uids);
            return;
        }
        final int err = native_removeUidInterfaceRules(uids);
        maybeThrow(err, "Unable to remove uid interface rules");
    }

    /**
     * Request netd to change the current active network stats map.
     *
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void swapActiveStatsMap() throws RemoteException {
        if (USE_NETD) {
            mNetd.trafficSwapActiveStatsMap();
            return;
        }
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
        if (USE_NETD) {
            mNetd.trafficSetNetPermForUids(permissions, uids);
            return;
        }
        native_setPermissionForUids(permissions, uids);
    }

    /**
     * Set counter set for uid
     *
     * @param counterSet either SET_DEFAULT or SET_FOREGROUND
     * @param uid        uid to foreground/background
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void setCounterSet(final int counterSet, final int uid) {
        final int err = native_setCounterSet(counterSet, uid);
        maybeThrow(err, "setCounterSet failed");
    }

    /**
     * Reset Uid stats
     *
     * @param tag default 0
     * @param uid given uid to be clear
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void deleteTagData(final int tag, final int uid) {
        final int err = native_deleteTagData(tag, uid);
        maybeThrow(err, "deleteTagData failed");
    }

    private static native void native_init();
    private native int native_addNaughtyApp(int uid);
    private native int native_removeNaughtyApp(int uid);
    private native int native_addNiceApp(int uid);
    private native int native_removeNiceApp(int uid);
    private native int native_setChildChain(int childChain, boolean enable);
    private native int native_replaceUidChain(String name, boolean isAllowlist, int[] uids);
    private native int native_setUidRule(int childChain, int uid, int firewallRule);
    private native int native_addUidInterfaceRules(String ifName, int[] uids);
    private native int native_removeUidInterfaceRules(int[] uids);
    private native int native_swapActiveStatsMap();
    private native void native_setPermissionForUids(int permissions, int[] uids);
    private native int native_setCounterSet(int counterSet, int uid);
    private native int native_deleteTagData(int tag, int uid);
}
