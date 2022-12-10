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

#include <stdlib.h>
#include <string.h>
#include <sys/utsname.h>

namespace android {
namespace bpf {

#define KVER(a, b, c) (((a) << 24) + ((b) << 16) + (c))

static inline unsigned uncachedKernelVersion() {
    struct utsname buf;
    if (uname(&buf)) return 0;

    unsigned kver_major = 0;
    unsigned kver_minor = 0;
    unsigned kver_sub = 0;
    (void)sscanf(buf.release, "%u.%u.%u", &kver_major, &kver_minor, &kver_sub);
    return KVER(kver_major, kver_minor, kver_sub);
}

static inline unsigned kernelVersion() {
    static unsigned kver = uncachedKernelVersion();
    return kver;
}

static inline bool isAtLeastKernelVersion(unsigned major, unsigned minor, unsigned sub) {
    return kernelVersion() >= KVER(major, minor, sub);
}

}  // namespace bpf
}  // namespace android
