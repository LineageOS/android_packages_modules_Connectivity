/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.net.module.util.netlink.xfrm;

import androidx.annotation.NonNull;

import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.net.module.util.netlink.StructNlMsgHdr;

/** Base calss for XFRM netlink messages */
// Developer notes: The Linux kernel includes a number of XFRM structs that are not standard netlink
// attributes (e.g., xfrm_usersa_id). These structs are unlikely to change size, so this XFRM
// netlink message implementation assumes their sizes will remain stable. If any non-attribute
// struct size changes, it should be caught by CTS and then developers should add
// kernel-version-based behvaiours.
public abstract class XfrmNetlinkMessage extends NetlinkMessage {
    // TODO: STOPSHIP: b/308011229 Remove it when OsConstants.IPPROTO_ESP is exposed
    public static final int IPPROTO_ESP = 50;

    public XfrmNetlinkMessage(@NonNull final StructNlMsgHdr header) {
        super(header);
    }

    // TODO: Add the support for parsing messages
}
