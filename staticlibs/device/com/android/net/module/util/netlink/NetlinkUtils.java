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

package com.android.net.module.util.netlink;

import static android.system.OsConstants.IPPROTO_TCP;

/**
 * Utilities for netlink related class.
 * @hide
 */
public class NetlinkUtils {
    /** Corresponds to enum from bionic/libc/include/netinet/tcp.h. */
    private static final int TCP_ESTABLISHED = 1;
    private static final int TCP_SYN_SENT = 2;
    private static final int TCP_SYN_RECV = 3;

    public static final int TCP_MONITOR_STATE_FILTER =
            (1 << TCP_ESTABLISHED) | (1 << TCP_SYN_SENT) | (1 << TCP_SYN_RECV);

    /**
     * Construct an inet_diag_req_v2 message for querying alive TCP sockets from kernel.
     */
    public static byte[] buildInetDiagReqForAliveTcpSockets(int family) {
        return InetDiagMessage.inetDiagReqV2(IPPROTO_TCP,
                null /* local addr */,
                null /* remote addr */,
                family,
                (short) (StructNlMsgHdr.NLM_F_REQUEST | StructNlMsgHdr.NLM_F_DUMP) /* flag */,
                0 /* pad */,
                1 << NetlinkConstants.INET_DIAG_MEMINFO /* idiagExt */,
                TCP_MONITOR_STATE_FILTER);
    }
}
