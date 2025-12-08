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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/personality.h>
#include <sys/system_properties.h>
#include <sys/utsname.h>

namespace android {
namespace bpf {

#define KVER(a, b, c) (((a) << 24) + ((b) << 16) + (c))

static inline unsigned uncachedKernelVersion() {
    struct utsname buf;
    if (uname(&buf)) abort();

    unsigned kver_major = 0;
    unsigned kver_minor = 0;
    unsigned kver_sub = 0;
    char kver_override[PROP_VALUE_MAX];
    int kver_override_len = __system_property_get("ro.bpf.kver_override", kver_override);
    if (sscanf(kver_override_len > 0 ? kver_override : buf.release, "%u.%u.%u",
               &kver_major, &kver_minor, &kver_sub) < 2) abort();
    return KVER(kver_major, kver_minor, kver_sub);
}

static const unsigned kernelVer = uncachedKernelVersion();

static inline unsigned __unused kernelVersion() {
    return kernelVer;
}

#ifndef __ANDROID_API__
    #error "__ANDROID_API__ is not defined"
#endif

static constexpr unsigned minSupportedKernelVer =
#ifdef __ANDROID_APEX__  // Mainline code (incl. NetBpfLoad) in APEX shouldn't (yet) assume anything
        KVER(0, 0, 0);
#elif __ANDROID_API__ >= 10000 // tip of dev tree - we'll need to bump this manually as time advances
        KVER(5, 10, 0);
#elif __ANDROID_API__ >= 41 // Android ~21 - 30Q2: G -- note: 6.12 likely min for 29Q4-ish
        KVER(6, 12, 0);
#elif __ANDROID_API__ >= 40 // Android ~20 - 29Q2: F -- note: 6.6 likely min for 28Q4-ish
        KVER(6, 6, 0);
#elif __ANDROID_API__ >= 39 // Android ~19 - 28Q2: E -- note: 6.1 likely min for 27Q4-ish
        KVER(6, 1, 0);
#elif __ANDROID_API__ >= 38 // Android ~18 - 27Q2: D -- note: 5.15 likely min for 26Q4-ish
        KVER(5, 15, 0);
#elif __ANDROID_API__ >= 37 // Android ~17 - 26Q2: C -- note: 5.10 is min for 25Q4 (or even 25Q3)
        KVER(5, 10, 0);
#elif __ANDROID_API__ >= 36 // Android 16 - 25Q2: Baklava
        KVER(5, 4, 0);
#elif __ANDROID_API__ >= 35 // Android 15 - 24Q3: Vanilla Ice Cream
        KVER(4, 19, 0);
#elif __ANDROID_API__ >= 34 // Android 14 - Upside Down Cake
        KVER(4, 14, 0);
#elif __ANDROID_API__ >= 33 // Android 13 - Tiramisu
        KVER(4, 9, 0);
#elif __ANDROID_API__ >= 32 // Android 12L - Sv2
        KVER(4, 9, 0);
#elif __ANDROID_API__ >= 31 // Android 12 - Snow Cone
        KVER(4, 9, 0);
#elif __ANDROID_API__ >= 30 // Android 11 - Red Velvet Cake
        KVER(4, 4, 0);
#else
    #error "__ANDROID_API__ is pre-R"
#endif

static inline bool isAtLeastKernelVersion(unsigned major, unsigned minor, unsigned sub = 0) {
    unsigned k = KVER(major, minor, sub);
    if (k <= minSupportedKernelVer) return true;
    return kernelVer >= k;
}

static inline bool isKernelVersion(unsigned major, unsigned minor) {
    return isAtLeastKernelVersion(major, minor) && !isAtLeastKernelVersion(major, minor + 1);
}

static inline bool __unused isLtsKernel() {
    return isKernelVersion(4,  4) ||  // minimum for Android R
           isKernelVersion(4,  9) ||  // minimum for Android S & T
           isKernelVersion(4, 14) ||  // minimum for Android U
           isKernelVersion(4, 19) ||  // minimum for Android V
           isKernelVersion(5,  4) ||  // first supported in Android R, min for 25Q2
           isKernelVersion(5, 10) ||  // first supported in Android S
           isKernelVersion(5, 15) ||  // first supported in Android T
           isKernelVersion(6,  1) ||  // first supported in Android U
           isKernelVersion(6,  6) ||  // first supported in Android V
           isKernelVersion(6, 12);    // first supported in Android 25Q2
}

// Figure out the bitness of userspace.
// Trivial and known at compile time.
static constexpr bool isUserspace32bit() {
    return sizeof(void*) == 4;
}

static constexpr bool isUserspace64bit() {
    return sizeof(void*) == 8;
}

#if defined(__LP64__)
static_assert(isUserspace64bit(), "huh? LP64 must have 64-bit userspace");
#elif defined(__ILP32__)
static_assert(isUserspace32bit(), "huh? ILP32 must have 32-bit userspace");
#else
#error "huh? must be either LP64 (64-bit userspace) or ILP32 (32-bit userspace)"
#endif

static_assert(isUserspace32bit() || isUserspace64bit(), "must be either 32 or 64 bit");

// Figure out the bitness of the kernel.
static inline bool isKernel64Bit() {
    // a 64-bit userspace requires a 64-bit kernel
    if (isUserspace64bit()) return true;

    static bool init = false;
    static bool cache = false;
    if (init) return cache;

    // Retrieve current personality - on Linux this system call *cannot* fail.
    int p = personality(0xffffffff);
    // But if it does just assume kernel and userspace (which is 32-bit) match...
    if (p == -1) return false;

    // This will effectively mask out the bottom 8 bits, and switch to 'native'
    // personality, and then return the previous personality of this thread
    // (likely PER_LINUX or PER_LINUX32) with any extra options unmodified.
    int q = personality((p & ~PER_MASK) | PER_LINUX);
    // Per man page this theoretically could error out with EINVAL,
    // but kernel code analysis suggests setting PER_LINUX cannot fail.
    // Either way, assume kernel and userspace (which is 32-bit) match...
    if (q != p) return false;

    struct utsname u;
    (void)uname(&u);  // only possible failure is EFAULT, but u is on stack.

    // Switch back to previous personality.
    // Theoretically could fail with EINVAL on arm64 with no 32-bit support,
    // but then we wouldn't have fetched 'p' from the kernel in the first place.
    // Either way there's nothing meaningful we can do in case of error.
    // Since PER_LINUX32 vs PER_LINUX only affects uname.machine it doesn't
    // really hurt us either.  We're really just switching back to be 'clean'.
    (void)personality(p);

    // Possible values of utsname.machine observed on x86_64 desktop (arm via qemu):
    //   x86_64 i686 aarch64 armv7l
    // additionally observed on arm device:
    //   armv8l
    // presumably also might just be possible:
    //   i386 i486 i586
    // and there might be other weird arm32 cases.
    // We note that the 64 is present in both 64-bit archs,
    // and in general is likely to be present in only 64-bit archs.
    cache = !!strstr(u.machine, "64");
    init = true;
    return cache;
}

static inline __unused bool isKernel32Bit() {
    return !isKernel64Bit();
}

static constexpr bool isArm() {
#if defined(__arm__)
    static_assert(isUserspace32bit(), "huh? arm must be 32 bit");
    return true;
#elif defined(__aarch64__)
    static_assert(isUserspace64bit(), "aarch64 must be LP64 - no support for ILP32");
    return true;
#else
    return false;
#endif
}

static constexpr bool isX86() {
#if defined(__i386__)
    static_assert(isUserspace32bit(), "huh? i386 must be 32 bit");
    return true;
#elif defined(__x86_64__)
    static_assert(isUserspace64bit(), "x86_64 must be LP64 - no support for ILP32 (x32)");
    return true;
#else
    return false;
#endif
}

static constexpr bool isRiscV() {
#if defined(__riscv)
    static_assert(isUserspace64bit(), "riscv must be 64 bit");
    return true;
#else
    return false;
#endif
}

static_assert(isArm() || isX86() || isRiscV(), "Unknown architecture");

static __unused const char * describeArch() {
    // ordered so as to make it easier to compile time optimize,
    // only thing not known at compile time is isKernel64Bit()
    if (isUserspace64bit()) {
        if (isArm()) return "64-on-aarch64";
        if (isX86()) return "64-on-x86-64";
        if (isRiscV()) return "64-on-riscv64";
    } else if (isKernel64Bit()) {
        if (isArm()) return "32-on-aarch64";
        if (isX86()) return "32-on-x86-64";
    } else {
        if (isArm()) return "32-on-arm32";
        if (isX86()) return "32-on-x86-32";
    }
}

}  // namespace bpf
}  // namespace android
