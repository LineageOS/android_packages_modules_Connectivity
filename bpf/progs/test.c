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

// The resulting .o needs to load on Android S+
#define BPFLOADER_MIN_VER BPFLOADER_MAINLINE_S_VERSION

// This is non production code, only used for testing
// Needed because the bitmap array definition is non-kosher for pre-T OS devices.
#define THIS_BPF_PROGRAM_IS_FOR_TEST_PURPOSES_ONLY

#include "bpf_net_helpers.h"
#include "offload.h"

// Used only by TetheringPrivilegedTests, not by production code.
DEFINE_BPF_MAP_GRW(tether_downstream6_map, HASH, TetherDownstream6Key, Tether6Value, 16,
                   AID_NETWORK_STACK)
DEFINE_BPF_MAP_GRW(tether2_downstream6_map, HASH, TetherDownstream6Key, Tether6Value, 16,
                   AID_NETWORK_STACK)
DEFINE_BPF_MAP_GRW(tether3_downstream6_map, HASH, TetherDownstream6Key, Tether6Value, 16,
                   AID_NETWORK_STACK)
// Used only by BpfBitmapTest, not by production code.
DEFINE_BPF_MAP_GRW(bitmap, ARRAY, int, uint64_t, 2, AID_NETWORK_STACK)

// we need at least 1 bpf program in the final .o for Android S bpfloader compatibility
// this program is trivial, and has a 'infinite' minimum kernel version number,
// so will always be skipped
DEFINE_BPF_PROG_KVER("skfilter/match", AID_ROOT, AID_ROOT, match, KVER_INF)
(__unused struct __sk_buff* skb) {
    return XTBPF_MATCH;
}

LICENSE("Apache 2.0");
