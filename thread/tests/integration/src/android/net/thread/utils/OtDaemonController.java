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

package android.net.thread.utils;

import android.annotation.Nullable;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.os.SystemClock;

import com.android.compatibility.common.util.SystemUtil;

import java.net.Inet6Address;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Wrapper of the "/system/bin/ot-ctl" which can be used to send CLI commands to ot-daemon to
 * control its behavior.
 *
 * <p>Note that this class takes root privileged to run.
 */
public final class OtDaemonController {
    private static final String OT_CTL = "/system/bin/ot-ctl";

    /**
     * Factory resets ot-daemon.
     *
     * <p>This will erase all persistent data written into apexdata/com.android.apex/ot-daemon and
     * restart the ot-daemon service.
     */
    public void factoryReset() {
        executeCommand("factoryreset");

        // TODO(b/323164524): ot-ctl is a separate process so that the tests can't depend on the
        // time sequence. Here needs to wait for system server to receive the ot-daemon death
        // signal and take actions.
        // A proper fix is to replace "ot-ctl" with "cmd thread_network ot-ctl" which is
        // synchronized with the system server
        SystemClock.sleep(500);
    }

    /** Returns the list of IPv6 addresses on ot-daemon. */
    public List<Inet6Address> getAddresses() {
        return executeCommandAndParse("ipaddr").stream()
                .map(addr -> InetAddresses.parseNumericAddress(addr))
                .map(inetAddr -> (Inet6Address) inetAddr)
                .toList();
    }

    /** Returns {@code true} if the Thread interface is up. */
    public boolean isInterfaceUp() {
        String output = executeCommand("ifconfig");
        return output.contains("up");
    }

    /** Returns the ML-EID of the device. */
    public Inet6Address getMlEid() {
        String addressStr = executeCommandAndParse("ipaddr mleid").get(0);
        return (Inet6Address) InetAddresses.parseNumericAddress(addressStr);
    }

    /** Returns the country code on ot-daemon. */
    public String getCountryCode() {
        return executeCommandAndParse("region").get(0);
    }

    /**
     * Returns the list of IPv6 Mesh-Local addresses on ot-daemon.
     *
     * <p>The return List can be empty if no Mesh-Local prefix exists.
     */
    public List<Inet6Address> getMeshLocalAddresses() {
        IpPrefix meshLocalPrefix = getMeshLocalPrefix();
        if (meshLocalPrefix == null) {
            return Collections.emptyList();
        }
        return getAddresses().stream().filter(addr -> meshLocalPrefix.contains(addr)).toList();
    }

    /**
     * Returns the Mesh-Local prefix or {@code null} if none exists (e.g. the Active Dataset is not
     * set).
     */
    @Nullable
    public IpPrefix getMeshLocalPrefix() {
        List<IpPrefix> prefixes =
                executeCommandAndParse("prefix meshlocal").stream()
                        .map(prefix -> new IpPrefix(prefix))
                        .toList();
        return prefixes.isEmpty() ? null : prefixes.get(0);
    }

    public String executeCommand(String cmd) {
        return SystemUtil.runShellCommand(OT_CTL + " " + cmd);
    }

    /**
     * Executes a ot-ctl command and parse the output to a list of strings.
     *
     * <p>The trailing "Done" in the command output will be dropped.
     */
    public List<String> executeCommandAndParse(String cmd) {
        return Arrays.asList(executeCommand(cmd).split("\n")).stream()
                .map(String::trim)
                .filter(str -> !str.equals("Done"))
                .toList();
    }
}
