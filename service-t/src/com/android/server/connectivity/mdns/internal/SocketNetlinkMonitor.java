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

package com.android.server.connectivity.mdns.internal;

import android.annotation.NonNull;
import android.os.Handler;
import android.system.OsConstants;

import com.android.net.module.util.SharedLog;
import com.android.net.module.util.ip.NetlinkMonitor;
import com.android.net.module.util.netlink.NetlinkConstants;
import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.server.connectivity.mdns.ISocketNetLinkMonitor;

/**
 * The netlink monitor for MdnsSocketProvider.
 */
public class SocketNetlinkMonitor extends NetlinkMonitor implements ISocketNetLinkMonitor {

    public SocketNetlinkMonitor(@NonNull final Handler handler, @NonNull SharedLog log) {
        super(handler, log, SocketNetlinkMonitor.class.getSimpleName(), OsConstants.NETLINK_ROUTE,
                NetlinkConstants.RTMGRP_IPV4_IFADDR | NetlinkConstants.RTMGRP_IPV6_IFADDR);
    }

    @Override
    public void processNetlinkMessage(NetlinkMessage nlMsg, long whenMs) {

    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public void startMonitoring() {
        this.start();
    }

    @Override
    public void stopMonitoring() {
        this.stop();
    }
}
