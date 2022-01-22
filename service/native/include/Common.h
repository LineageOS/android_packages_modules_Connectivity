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

#pragma once
// TODO: deduplicate with the constants in NetdConstants.h.
#include <aidl/android/net/INetd.h>

using aidl::android::net::INetd;

enum FirewallRule { ALLOW = INetd::FIREWALL_RULE_ALLOW, DENY = INetd::FIREWALL_RULE_DENY };

// ALLOWLIST means the firewall denies all by default, uids must be explicitly ALLOWed
// DENYLIST means the firewall allows all by default, uids must be explicitly DENYed

enum FirewallType { ALLOWLIST = INetd::FIREWALL_ALLOWLIST, DENYLIST = INetd::FIREWALL_DENYLIST };

enum ChildChain {
    NONE = INetd::FIREWALL_CHAIN_NONE,
    DOZABLE = INetd::FIREWALL_CHAIN_DOZABLE,
    STANDBY = INetd::FIREWALL_CHAIN_STANDBY,
    POWERSAVE = INetd::FIREWALL_CHAIN_POWERSAVE,
    RESTRICTED = INetd::FIREWALL_CHAIN_RESTRICTED,
    INVALID_CHAIN
};
