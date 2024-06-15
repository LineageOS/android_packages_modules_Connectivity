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

#define BPFLOADER_MIN_VER BPFLOADER_MAINLINE_T_VERSION

#include "bpf_helpers.h"
#include "bpf_net_helpers.h"

DEFINE_BPF_MAP_GRW(test, ARRAY, int, uint64_t, 1, AID_SYSTEM)

DEFINE_BPF_PROG("skfilter/accept", AID_ROOT, AID_SYSTEM, accept)
(struct __sk_buff *skb) {
    return 1;
}

LICENSE("Apache 2.0");
DISABLE_BTF_ON_USER_BUILDS();
