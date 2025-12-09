/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <errno.h>
#include <linux/if_ether.h>
#include <linux/pfkeyv2.h>
#include <net/if.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <sys/utsname.h>

#include <android-base/properties.h>
#include <log/log.h>

#include "KernelUtils.h"

namespace android {
namespace bpf {

static inline int get_api_level_full() {
    // This fetches/parses 'ro.build.version.sdk' system property.
    const int api_level = android_get_device_api_level();
    // Before Baklava/25Q2 there is no 'sdk_full'
    if (api_level < 36) return api_level * 100;  // 3x -> 3x00

    // Fetch and parse the 'sdk_full' system property.
    char value[92] = {};
    if (__system_property_get("ro.build.version.sdk_full", value) < 1) abort();
    int major, minor;
    if (sscanf(value, "%d.%d", &major, &minor) != 2) abort();
    if (major < 36 || minor < 0 || minor > 9) abort();
    const int api_level_full = major * 100 + minor * 10;  // 3x.y -> 3xy0

    // Fetch and parse our platform provided .rc file - this provides quarterly info as well
    FILE * f = fopen("/system/etc/init/netbpfload.rc", "re");
    if (!f) abort();
    int y, q, a, b, c;
    if (fscanf(f, "# %d %d %d %d %d #", &y, &q, &a, &b, &c) != 5) abort();
    if (a < 36 || b < 0 || b > 9 || c < 0 || c > 4) abort();
    fclose(f);
    const int api_level_fuller = a * 100 + b * 10 + c * 2;  // 3x.y.z -> 3xy[2z]

    const bool unreleased = (base::GetProperty("ro.build.version.codename", "REL") != "REL");
    return std::max(api_level_fuller, api_level_full) + unreleased;
}

const int api_level_full = get_api_level_full();

const bool isAtLeastS    = (api_level_full >= 3100);  // 31
                                                      // 32 is Sv2
const bool isAtLeastT    = (api_level_full >= 3300);  // 33
const bool isAtLeastU    = (api_level_full >= 3400);  // 34
const bool isAtLeastV    = (api_level_full >= 3500);  // 35
const bool isAtLeast25Q2 = (api_level_full >= 3600);  // 36.0
const bool isAtLeast25Q4 = (api_level_full >= 3610);  // 36.1
const bool isAtLeast26Q1 = (api_level_full >= 3612);  // 36.1+
const bool isAtLeast26Q2 = (api_level_full >= 3700);  // 37.0

// See kernel's net/core/sock_diag.c __sock_gen_cookie()
// the implementation of which guarantees 0 will never be returned,
// primarily because 0 is used to mean not yet initialized,
// and socket cookies are only assigned on first fetch.
constexpr const uint64_t NONEXISTENT_COOKIE = 0;

static inline uint64_t getSocketCookie(int sockFd) {
    uint64_t sock_cookie;
    socklen_t cookie_len = sizeof(sock_cookie);
    if (getsockopt(sockFd, SOL_SOCKET, SO_COOKIE, &sock_cookie, &cookie_len)) {
        // Failure is almost certainly either EBADF or ENOTSOCK
        const int err = errno;
        ALOGE("Failed to get socket cookie: %s\n", strerror(err));
        errno = err;
        return NONEXISTENT_COOKIE;
    }
    if (cookie_len != sizeof(sock_cookie)) {
        // This probably cannot actually happen, but...
        ALOGE("Failed to get socket cookie: len %d != 8\n", cookie_len);
        errno = 523; // EBADCOOKIE: kernel internal, seems reasonable enough...
        return NONEXISTENT_COOKIE;
    }
    return sock_cookie;
}

static inline int synchronizeKernelRCU() {
    // This is a temporary hack for network stats map swap on devices running
    // 4.9 kernels. The kernel code of socket release on pf_key socket will
    // explicitly call synchronize_rcu() which is exactly what we need.
    //
    // Linux 4.14/4.19/5.4/5.10/5.15/6.1/6.6/6.12 (& 6.13) have this behaviour.
    // see net/key/af_key.c: pfkey_release() -> synchronize_rcu()
    // https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/net/key/af_key.c?h=v6.13#n185
    const int pfSocket = socket(AF_KEY, SOCK_RAW | SOCK_CLOEXEC, PF_KEY_V2);

    if (pfSocket < 0) {
        const int err = errno;
        ALOGE("create PF_KEY socket failed: %s", strerror(err));
        return -err;
    }

    // When closing socket, synchronize_rcu() gets called in sock_release().
    if (close(pfSocket)) {
        const int err = errno;
        ALOGE("failed to close the PF_KEY socket: %s", strerror(err));
        return -err;
    }
    return 0;
}

static inline int setrlimitForTest() {
    // Set the memory rlimit for the test process if the default MEMLOCK rlimit is not enough.
    struct rlimit limit = {
            .rlim_cur = 1073741824,  // 1 GiB
            .rlim_max = 1073741824,  // 1 GiB
    };
    const int res = setrlimit(RLIMIT_MEMLOCK, &limit);
    if (res) ALOGE("Failed to set the default MEMLOCK rlimit: %s", strerror(errno));
    return res;
}

}  // namespace bpf
}  // namespace android
