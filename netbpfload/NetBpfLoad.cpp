/*
 * Copyright (C) 2017-2023 The Android Open Source Project
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

#ifndef LOG_TAG
#define LOG_TAG "NetBpfLoad"
#endif

#include <arpa/inet.h>
#include <dirent.h>
#include <elf.h>
#include <error.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/bpf.h>
#include <linux/unistd.h>
#include <net/if.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <android/api-level.h>
#include <android-base/logging.h>
#include <android-base/macros.h>
#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <log/log.h>

#include "BpfSyscallWrappers.h"
#include "bpf/BpfUtils.h"
#include "loader.h"

namespace android {
namespace bpf {

using base::EndsWith;
using std::string;

static bool exists(const char* const path) {
    int v = access(path, F_OK);
    if (!v) return true;
    if (errno == ENOENT) return false;
    ALOGE("FATAL: access(%s, F_OK) -> %d [%d:%s]", path, v, errno, strerror(errno));
    abort();  // can only hit this if permissions (likely selinux) are screwed up
}


const Location locations[] = {
        // S+ Tethering mainline module (network_stack): tether offload
        {
                .dir = "/apex/com.android.tethering/etc/bpf/",
                .prefix = "tethering/",
        },
        // T+ Tethering mainline module (shared with netd & system server)
        // netutils_wrapper (for iptables xt_bpf) has access to programs
        {
                .dir = "/apex/com.android.tethering/etc/bpf/netd_shared/",
                .prefix = "netd_shared/",
        },
        // T+ Tethering mainline module (shared with netd & system server)
        // netutils_wrapper has no access, netd has read only access
        {
                .dir = "/apex/com.android.tethering/etc/bpf/netd_readonly/",
                .prefix = "netd_readonly/",
        },
        // T+ Tethering mainline module (shared with system server)
        {
                .dir = "/apex/com.android.tethering/etc/bpf/net_shared/",
                .prefix = "net_shared/",
        },
        // T+ Tethering mainline module (not shared, just network_stack)
        {
                .dir = "/apex/com.android.tethering/etc/bpf/net_private/",
                .prefix = "net_private/",
        },
};

static int loadAllElfObjects(const unsigned int bpfloader_ver, const Location& location) {
    int retVal = 0;
    DIR* dir;
    struct dirent* ent;

    if ((dir = opendir(location.dir)) != NULL) {
        while ((ent = readdir(dir)) != NULL) {
            string s = ent->d_name;
            if (!EndsWith(s, ".o")) continue;

            string progPath(location.dir);
            progPath += s;

            bool critical;
            int ret = loadProg(progPath.c_str(), &critical, bpfloader_ver, location);
            if (ret) {
                if (critical) retVal = ret;
                ALOGE("Failed to load object: %s, ret: %s", progPath.c_str(), std::strerror(-ret));
            } else {
                ALOGD("Loaded object: %s", progPath.c_str());
            }
        }
        closedir(dir);
    }
    return retVal;
}

static int createSysFsBpfSubDir(const char* const prefix) {
    if (*prefix) {
        mode_t prevUmask = umask(0);

        string s = "/sys/fs/bpf/";
        s += prefix;

        errno = 0;
        int ret = mkdir(s.c_str(), S_ISVTX | S_IRWXU | S_IRWXG | S_IRWXO);
        if (ret && errno != EEXIST) {
            const int err = errno;
            ALOGE("Failed to create directory: %s, ret: %s", s.c_str(), std::strerror(err));
            return -err;
        }

        umask(prevUmask);
    }
    return 0;
}

// Technically 'value' doesn't need to be newline terminated, but it's best
// to include a newline to match 'echo "value" > /proc/sys/...foo' behaviour,
// which is usually how kernel devs test the actual sysctl interfaces.
static int writeProcSysFile(const char *filename, const char *value) {
    base::unique_fd fd(open(filename, O_WRONLY | O_CLOEXEC));
    if (fd < 0) {
        const int err = errno;
        ALOGE("open('%s', O_WRONLY | O_CLOEXEC) -> %s", filename, strerror(err));
        return -err;
    }
    int len = strlen(value);
    int v = write(fd, value, len);
    if (v < 0) {
        const int err = errno;
        ALOGE("write('%s', '%s', %d) -> %s", filename, value, len, strerror(err));
        return -err;
    }
    if (v != len) {
        // In practice, due to us only using this for /proc/sys/... files, this can't happen.
        ALOGE("write('%s', '%s', %d) -> short write [%d]", filename, value, len, v);
        return -EINVAL;
    }
    return 0;
}

#define APEX_MOUNT_POINT "/apex/com.android.tethering"
const char * const platformBpfLoader = "/system/bin/bpfloader";

static int logTetheringApexVersion(void) {
    char * found_blockdev = NULL;
    FILE * f = NULL;
    char buf[4096];

    f = fopen("/proc/mounts", "re");
    if (!f) return 1;

    // /proc/mounts format: block_device [space] mount_point [space] other stuff... newline
    while (fgets(buf, sizeof(buf), f)) {
        char * blockdev = buf;
        char * space = strchr(blockdev, ' ');
        if (!space) continue;
        *space = '\0';
        char * mntpath = space + 1;
        space = strchr(mntpath, ' ');
        if (!space) continue;
        *space = '\0';
        if (strcmp(mntpath, APEX_MOUNT_POINT)) continue;
        found_blockdev = strdup(blockdev);
        break;
    }
    fclose(f);
    f = NULL;

    if (!found_blockdev) return 2;
    ALOGV("Found Tethering Apex mounted from blockdev %s", found_blockdev);

    f = fopen("/proc/mounts", "re");
    if (!f) { free(found_blockdev); return 3; }

    while (fgets(buf, sizeof(buf), f)) {
        char * blockdev = buf;
        char * space = strchr(blockdev, ' ');
        if (!space) continue;
        *space = '\0';
        char * mntpath = space + 1;
        space = strchr(mntpath, ' ');
        if (!space) continue;
        *space = '\0';
        if (strcmp(blockdev, found_blockdev)) continue;
        if (strncmp(mntpath, APEX_MOUNT_POINT "@", strlen(APEX_MOUNT_POINT "@"))) continue;
        char * at = strchr(mntpath, '@');
        if (!at) continue;
        char * ver = at + 1;
        ALOGI("Tethering APEX version %s", ver);
    }
    fclose(f);
    free(found_blockdev);
    return 0;
}

static bool isGSI() {
    // From //system/gsid/libgsi.cpp IsGsiRunning()
    return !access("/metadata/gsi/dsu/booted", F_OK);
}

static int doLoad(char** argv, char * const envp[]) {
    const int device_api_level = android_get_device_api_level();
    const bool isAtLeastT = (device_api_level >= __ANDROID_API_T__);
    const bool isAtLeastU = (device_api_level >= __ANDROID_API_U__);
    const bool isAtLeastV = (device_api_level >= __ANDROID_API_V__);

    // last in U QPR2 beta1
    const bool has_platform_bpfloader_rc = exists("/system/etc/init/bpfloader.rc");
    // first in U QPR2 beta~2
    const bool has_platform_netbpfload_rc = exists("/system/etc/init/netbpfload.rc");

    ALOGI("NetBpfLoad (%s) api:%d/%d kver:%07x (%s) rc:%d%d",
          argv[0], android_get_application_target_sdk_version(), device_api_level,
          kernelVersion(), describeArch(),
          has_platform_bpfloader_rc, has_platform_netbpfload_rc);

    if (!has_platform_bpfloader_rc && !has_platform_netbpfload_rc) {
        ALOGE("Unable to find platform's bpfloader & netbpfload init scripts.");
        return 1;
    }

    if (has_platform_bpfloader_rc && has_platform_netbpfload_rc) {
        ALOGE("Platform has *both* bpfloader & netbpfload init scripts.");
        return 1;
    }

    logTetheringApexVersion();

    if (!isAtLeastT) {
        ALOGE("Impossible - not reachable on Android <T.");
        return 1;
    }

    if (isAtLeastT && !isAtLeastKernelVersion(4, 9, 0)) {
        ALOGE("Android T requires kernel 4.9.");
        return 1;
    }

    if (isAtLeastU && !isAtLeastKernelVersion(4, 14, 0)) {
        ALOGE("Android U requires kernel 4.14.");
        return 1;
    }

    if (isAtLeastV && !isAtLeastKernelVersion(4, 19, 0)) {
        ALOGE("Android V requires kernel 4.19.");
        return 1;
    }

    if (isAtLeastV && isX86() && !isKernel64Bit()) {
        ALOGE("Android V requires X86 kernel to be 64-bit.");
        return 1;
    }

    if (isAtLeastV) {
        bool bad = false;

        if (!isLtsKernel()) {
            ALOGW("Android V only supports LTS kernels.");
            bad = true;
        }

#define REQUIRE(maj, min, sub) \
        if (isKernelVersion(maj, min) && !isAtLeastKernelVersion(maj, min, sub)) { \
            ALOGW("Android V requires %d.%d kernel to be %d.%d.%d+.", maj, min, maj, min, sub); \
            bad = true; \
        }

        REQUIRE(4, 19, 236)
        REQUIRE(5, 4, 186)
        REQUIRE(5, 10, 199)
        REQUIRE(5, 15, 136)
        REQUIRE(6, 1, 57)
        REQUIRE(6, 6, 0)

#undef REQUIRE

        if (bad && !isGSI()) {
            ALOGE("Unsupported kernel version (%07x).", kernelVersion());
            sleep(60);
        }
    }

    if (isUserspace32bit() && isAtLeastKernelVersion(6, 2, 0)) {
        /* Android 14/U should only launch on 64-bit kernels
         *   T launches on 5.10/5.15
         *   U launches on 5.15/6.1
         * So >=5.16 implies isKernel64Bit()
         *
         * We thus added a test to V VTS which requires 5.16+ devices to use 64-bit kernels.
         *
         * Starting with Android V, which is the first to support a post 6.1 Linux Kernel,
         * we also require 64-bit userspace.
         *
         * There are various known issues with 32-bit userspace talking to various
         * kernel interfaces (especially CAP_NET_ADMIN ones) on a 64-bit kernel.
         * Some of these have userspace or kernel workarounds/hacks.
         * Some of them don't...
         * We're going to be removing the hacks.
         *
         * Additionally the 32-bit kernel jit support is poor,
         * and 32-bit userspace on 64-bit kernel bpf ringbuffer compatibility is broken.
         */
        ALOGE("64-bit userspace required on 6.2+ kernels.");
        return 1;
    }

    // Ensure we can determine the Android build type.
    if (!isEng() && !isUser() && !isUserdebug()) {
        ALOGE("Failed to determine the build type: got %s, want 'eng', 'user', or 'userdebug'",
              getBuildType().c_str());
        return 1;
    }

    if (isAtLeastV) {
        // Linux 5.16-rc1 changed the default to 2 (disabled but changeable),
        // but we need 0 (enabled)
        // (this writeFile is known to fail on at least 4.19, but always defaults to 0 on
        // pre-5.13, on 5.13+ it depends on CONFIG_BPF_UNPRIV_DEFAULT_OFF)
        if (writeProcSysFile("/proc/sys/kernel/unprivileged_bpf_disabled", "0\n") &&
            isAtLeastKernelVersion(5, 13, 0)) return 1;
    }

    if (isAtLeastU) {
        // Enable the eBPF JIT -- but do note that on 64-bit kernels it is likely
        // already force enabled by the kernel config option BPF_JIT_ALWAYS_ON.
        // (Note: this (open) will fail with ENOENT 'No such file or directory' if
        //  kernel does not have CONFIG_BPF_JIT=y)
        // BPF_JIT is required by R VINTF (which means 4.14/4.19/5.4 kernels),
        // but 4.14/4.19 were released with P & Q, and only 5.4 is new in R+.
        if (writeProcSysFile("/proc/sys/net/core/bpf_jit_enable", "1\n")) return 1;

        // Enable JIT kallsyms export for privileged users only
        // (Note: this (open) will fail with ENOENT 'No such file or directory' if
        //  kernel does not have CONFIG_HAVE_EBPF_JIT=y)
        if (writeProcSysFile("/proc/sys/net/core/bpf_jit_kallsyms", "1\n")) return 1;
    }

    // Create all the pin subdirectories
    // (this must be done first to allow selinux_context and pin_subdir functionality,
    //  which could otherwise fail with ENOENT during object pinning or renaming,
    //  due to ordering issues)
    for (const auto& location : locations) {
        if (createSysFsBpfSubDir(location.prefix)) return 1;
    }

    // Note: there's no actual src dir for fs_bpf_loader .o's,
    // so it is not listed in 'locations[].prefix'.
    // This is because this is primarily meant for triggering genfscon rules,
    // and as such this will likely always be the case.
    // Thus we need to manually create the /sys/fs/bpf/loader subdirectory.
    if (createSysFsBpfSubDir("loader")) return 1;

    // Version of Network BpfLoader depends on the Android OS version
    unsigned int bpfloader_ver = 42u;  // [42] BPFLOADER_MAINLINE_VERSION
    if (isAtLeastT) ++bpfloader_ver;   // [43] BPFLOADER_MAINLINE_T_VERSION
    if (isAtLeastU) ++bpfloader_ver;   // [44] BPFLOADER_MAINLINE_U_VERSION
    if (isAtLeastV) ++bpfloader_ver;   // [45] BPFLOADER_MAINLINE_V_VERSION

    // Load all ELF objects, create programs and maps, and pin them
    for (const auto& location : locations) {
        if (loadAllElfObjects(bpfloader_ver, location) != 0) {
            ALOGE("=== CRITICAL FAILURE LOADING BPF PROGRAMS FROM %s ===", location.dir);
            ALOGE("If this triggers reliably, you're probably missing kernel options or patches.");
            ALOGE("If this triggers randomly, you might be hitting some memory allocation "
                  "problems or startup script race.");
            ALOGE("--- DO NOT EXPECT SYSTEM TO BOOT SUCCESSFULLY ---");
            sleep(20);
            return 2;
        }
    }

    int key = 1;
    int value = 123;
    base::unique_fd map(
            createMap(BPF_MAP_TYPE_ARRAY, sizeof(key), sizeof(value), 2, 0));
    if (writeToMapEntry(map, &key, &value, BPF_ANY)) {
        ALOGE("Critical kernel bug - failure to write into index 1 of 2 element bpf map array.");
        return 1;
    }

    if (isAtLeastV) {
        ALOGI("done, transferring control to platform bpfloader.");

        const char * args[] = { platformBpfLoader, NULL, };
        execve(args[0], (char**)args, envp);
        ALOGE("FATAL: execve('%s'): %d[%s]", platformBpfLoader, errno, strerror(errno));
        return 1;
    }

    ALOGI("mainline done!");
    return 0;
}

}  // namespace bpf
}  // namespace android

int main(int argc, char** argv, char * const envp[]) {
    android::base::InitLogging(argv, &android::base::KernelLogger);

    if (argc == 2 && !strcmp(argv[1], "done")) {
        // we're being re-exec'ed from platform bpfloader to 'finalize' things
        if (!android::base::SetProperty("bpf.progs_loaded", "1")) {
            ALOGE("Failed to set bpf.progs_loaded property to 1.");
            return 125;
        }
        ALOGI("success.");
        return 0;
    }

    return android::bpf::doLoad(argv, envp);
}
