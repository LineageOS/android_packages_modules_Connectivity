/**
 * Copyright (c) 2022, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "NetdUpdatable"

#include "BpfHandler.h"

#include <linux/bpf.h>
#include <inttypes.h>

#include <android-base/result.h>
#include <android-base/unique_fd.h>
#include <bpf/WaitForProgsLoaded.h>
#include <log/log.h>
#include <private/android_filesystem_config.h>

#include "BpfSyscallWrappers.h"
#include "bpf/BpfUtils.h"
#include "internal_net_api.h"

namespace android {
namespace net {

using base::Error;
using base::Result;
using base::unique_fd;
using base::WaitForProperty;
using bpf::getSocketCookie;
using bpf::isAtLeastKernelVersion;
using bpf::isAtLeastT;
using bpf::isAtLeastU;
using bpf::isAtLeastV;
using bpf::isAtLeast25Q2;
using bpf::isAtLeast26Q2;
using bpf::isKernel64Bit;
using bpf::isUser;
using bpf::isUserspace64bit;
using bpf::queryProgram;
using bpf::retrieveProgram;

constexpr int PER_UID_STATS_ENTRIES_LIMIT = 500;
// At most 90% of the stats map may be used by tagged traffic entries. This ensures
// that 10% of the map is always available to count untagged traffic, one entry per UID.
// Otherwise, apps would be able to avoid data usage accounting entirely by filling up the
// map with tagged traffic entries.
constexpr int TOTAL_UID_STATS_ENTRIES_LIMIT = STATS_MAP_SIZE * 0.9;

static_assert(STATS_MAP_SIZE - TOTAL_UID_STATS_ENTRIES_LIMIT > 100,
              "The limit for stats map is to high, stats data may be lost due to overflow");

#define RETURN_IF_NOT_OK(expr) \
    if (auto _res = (expr); !_res.ok()) return _res.error();

static Result<void> attachProgramToCgroup(const char* programPath, const unique_fd& cgroupFd,
                                          bpf_attach_type type) {
    unique_fd cgroupProg(retrieveProgram(programPath));
    if (!cgroupProg.ok()) {
        return Error(errno) << "Failed to get program from " << programPath;
    }
    // Multi-attach lets the firewall attach alongside; detaching first lets netd re-attach.
    bpf::detachSingleProgram(type, cgroupProg, cgroupFd);
    if (bpf::attachProgram(type, cgroupProg, cgroupFd, BPF_F_ALLOW_MULTI)) {
        return Error(errno) << "Program " << programPath << " attach failed";
    }
    return {};
}

// Counts every program on the hook. queryProgram() fails once another module's program shares it.
static int attachedPrograms(const unique_fd& cgroupFd, bpf_attach_type type) {
    bpf_attr attr = {.query = {.target_fd = static_cast<__u32>(cgroupFd.get()),
                               .attach_type = type}};
    return bpf::bpf(BPF_PROG_QUERY, attr) ? -errno : attr.query.prog_cnt;
}

static Result<void> checkProgramAccessible(const char* programPath) {
    unique_fd prog(retrieveProgram(programPath));
    if (!prog.ok()) {
        return Error(errno) << "Failed to get program from " << programPath;
    }
    return {};
}

static void getsockoptTest() {
    // getsockopt bpf hook is not called if the device is running in compat mode.
    if (isKernel64Bit() != isUserspace64bit() && !isAtLeastKernelVersion(6, 13)) return;

    // SO_ANDROID_DROP_REASON option is only supported on 5.10+.
    if (!isAtLeastKernelVersion(5, 10)) return;

    base::unique_fd sock(socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0));
    if (!sock.ok()) abort();

    uint64_t reason = 0xDEADC0DEDEADBEEFuLL; // should be overwritten by getsockopt()
    socklen_t len = sizeof(reason);
    if (getsockopt(sock, SOL_SOCKET, SO_ANDROID_DROP_REASON, &reason, &len) != 0) abort();
    if (reason != DROP_REASON_NONE) abort();

    // This option expects an 8 byte buffer. Passing a smaller buffer (7 bytes)
    // should trigger a size check failure in the eBPF program (netd.c).
    len = sizeof(reason) - 1;
    int expected = isAtLeastKernelVersion(6, 1) ? EINVAL : EPERM;
    errno = 0;
    if (getsockopt(sock, SOL_SOCKET, SO_ANDROID_DROP_REASON, &reason, &len) == 0) abort();
    if (errno != expected) abort();
}

static Result<void> initPrograms(const char* cg2_path) {
    if (!cg2_path) return Error() << "cg2_path is NULL";

    // This code was mainlined in T, so this should be trivially satisfied.
    if (!isAtLeastT) return Error() << "S- platform is unsupported";

    // S requires eBPF support which was only added in 4.9, so this should be satisfied.
    if (!isAtLeastKernelVersion(4, 9)) {
        return Error() << "kernel version < 4.9.0 is unsupported";
    }

    // U bumps the kernel requirement up to 4.14
    if (isAtLeastU && !isAtLeastKernelVersion(4, 14)) {
        return Error() << "U+ platform with kernel version < 4.14.0 is unsupported";
    }

    // U mandates this mount point (though it should also be the case on T)
    if (isAtLeastU && !!strcmp(cg2_path, "/sys/fs/cgroup")) {
        return Error() << "U+ platform with cg2_path != /sys/fs/cgroup is unsupported";
    }

    // V bumps the kernel requirement up to 4.19
    if (isAtLeastV && !isAtLeastKernelVersion(4, 19)) {
        return Error() << "V+ platform with kernel version < 4.19.0 is unsupported";
    }

    // 25Q2 bumps the kernel requirement up to 5.4
    if (isAtLeast25Q2 && !isAtLeastKernelVersion(5, 4)) {
        return Error() << "25Q2+ platform with kernel version < 5.4.0 is unsupported";
    }

    unique_fd cg_fd(open(cg2_path, O_DIRECTORY | O_RDONLY | O_CLOEXEC));
    if (!cg_fd.ok()) return Error(errno) << "Opening cgroup dir failed";

    RETURN_IF_NOT_OK(checkProgramAccessible(XT_BPF_ALLOWLIST_PROG_PATH));
    RETURN_IF_NOT_OK(checkProgramAccessible(XT_BPF_DENYLIST_PROG_PATH));
    RETURN_IF_NOT_OK(checkProgramAccessible(XT_BPF_EGRESS_PROG_PATH));
    RETURN_IF_NOT_OK(checkProgramAccessible(XT_BPF_INGRESS_PROG_PATH));
    RETURN_IF_NOT_OK(attachProgramToCgroup(BPF_EGRESS_PROG_PATH, cg_fd, BPF_CGROUP_INET_EGRESS));
    RETURN_IF_NOT_OK(attachProgramToCgroup(BPF_INGRESS_PROG_PATH, cg_fd, BPF_CGROUP_INET_INGRESS));

    // For the devices that support cgroup socket filter, the socket filter
    // should be loaded successfully by bpfloader. So we attach the filter to
    // cgroup if the program is pinned properly.
    // TODO: delete the if statement once all devices should support cgroup
    // socket filter (ie. the minimum kernel version required is 4.14).
    if (isAtLeastKernelVersion(4, 14)) {
        RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_INET_CREATE_PROG_PATH,
                                    cg_fd, BPF_CGROUP_INET_SOCK_CREATE));
    }

    if (isAtLeastKernelVersion(5, 10)) {
        RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_INET_RELEASE_PROG_PATH,
                                    cg_fd, BPF_CGROUP_INET_SOCK_RELEASE));
    }

    if (isAtLeastV) {
        // V requires 4.19+, so technically this 2nd 'if' is not required, but it
        // doesn't hurt us to try to support AOSP forks that try to support older kernels.
        if (isAtLeastKernelVersion(4, 19)) {
            RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_CONNECT4_PROG_PATH,
                                        cg_fd, BPF_CGROUP_INET4_CONNECT));
            RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_CONNECT6_PROG_PATH,
                                        cg_fd, BPF_CGROUP_INET6_CONNECT));
            RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_UDP4_RECVMSG_PROG_PATH,
                                        cg_fd, BPF_CGROUP_UDP4_RECVMSG));
            RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_UDP6_RECVMSG_PROG_PATH,
                                        cg_fd, BPF_CGROUP_UDP6_RECVMSG));
            RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_UDP4_SENDMSG_PROG_PATH,
                                        cg_fd, BPF_CGROUP_UDP4_SENDMSG));
            RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_UDP6_SENDMSG_PROG_PATH,
                                        cg_fd, BPF_CGROUP_UDP6_SENDMSG));
        }

        if (isAtLeastKernelVersion(5, 4)) {
            RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_GETSOCKOPT_PROG_PATH,
                                        cg_fd, BPF_CGROUP_GETSOCKOPT));
            RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_SETSOCKOPT_PROG_PATH,
                                        cg_fd, BPF_CGROUP_SETSOCKOPT));
            getsockoptTest();
        }
    }

    if (isAtLeast26Q2) {
        if (isAtLeastKernelVersion(6, 1) && !isAtLeastKernelVersion(6, 18)) {
            RETURN_IF_NOT_OK(attachProgramToCgroup(L4S_OPTIONS_SOCKOPS_PROG_PATH,
                                        cg_fd, BPF_CGROUP_SOCK_OPS));
        }
    }

    if (isAtLeastKernelVersion(4, 19)) {
        RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_BIND4_PROG_PATH,
                cg_fd, BPF_CGROUP_INET4_BIND));
        RETURN_IF_NOT_OK(attachProgramToCgroup(CGROUP_BIND6_PROG_PATH,
                cg_fd, BPF_CGROUP_INET6_BIND));

        // This should trivially pass, since we just attached up above,
        // but BPF_PROG_QUERY is only implemented on 4.19+ kernels.
        if (attachedPrograms(cg_fd, BPF_CGROUP_INET_EGRESS) <= 0) abort();
        if (attachedPrograms(cg_fd, BPF_CGROUP_INET_INGRESS) <= 0) abort();
        if (queryProgram(cg_fd, BPF_CGROUP_INET_SOCK_CREATE) <= 0) abort();
        if (queryProgram(cg_fd, BPF_CGROUP_INET4_BIND) <= 0) abort();
        if (queryProgram(cg_fd, BPF_CGROUP_INET6_BIND) <= 0) abort();
    }

    if (isAtLeastKernelVersion(5, 10)) {
        if (queryProgram(cg_fd, BPF_CGROUP_INET_SOCK_RELEASE) <= 0) abort();
    }

    if (isAtLeastV) {
        // V requires 4.19+, so technically this 2nd 'if' is not required, but it
        // doesn't hurt us to try to support AOSP forks that try to support older kernels.
        if (isAtLeastKernelVersion(4, 19)) {
            if (attachedPrograms(cg_fd, BPF_CGROUP_INET4_CONNECT) <= 0) abort();
            if (attachedPrograms(cg_fd, BPF_CGROUP_INET6_CONNECT) <= 0) abort();
            if (queryProgram(cg_fd, BPF_CGROUP_UDP4_RECVMSG) <= 0) abort();
            if (queryProgram(cg_fd, BPF_CGROUP_UDP6_RECVMSG) <= 0) abort();
            if (queryProgram(cg_fd, BPF_CGROUP_UDP4_SENDMSG) <= 0) abort();
            if (queryProgram(cg_fd, BPF_CGROUP_UDP6_SENDMSG) <= 0) abort();
        }

        if (isAtLeastKernelVersion(5, 4)) {
            if (queryProgram(cg_fd, BPF_CGROUP_GETSOCKOPT) <= 0) abort();
            if (queryProgram(cg_fd, BPF_CGROUP_SETSOCKOPT) <= 0) abort();
        }
    }

    if (isAtLeast26Q2) {
        if (isAtLeastKernelVersion(6, 1) && !isAtLeastKernelVersion(6, 18)) {
            if (queryProgram(cg_fd, BPF_CGROUP_SOCK_OPS) <= 0) abort();
        }
    }

    return {};
}

BpfHandler::BpfHandler()
    : mPerUidStatsEntriesLimit(PER_UID_STATS_ENTRIES_LIMIT),
      mTotalUidStatsEntriesLimit(TOTAL_UID_STATS_ENTRIES_LIMIT) {}

BpfHandler::BpfHandler(uint32_t perUidLimit, uint32_t totalLimit)
    : mPerUidStatsEntriesLimit(perUidLimit), mTotalUidStatsEntriesLimit(totalLimit) {}

static bool mainlineNetBpfLoadDone() {
    return !access("/sys/fs/bpf/netd_shared/mainline_done", F_OK);
}

// copied with minor changes from waitForProgsLoaded()
// p/m/C's staticlibs/native/bpf_headers/include/bpf/WaitForProgsLoaded.h
static inline void waitForNetProgsLoaded() {
    // infinite loop until success with 5/10/20/40/60/60/60... delay
    for (int delay = 5;; delay *= 2) {
        if (delay > 60) delay = 60;
        if (WaitForProperty("init.svc.mdnsd_netbpfload", "stopped", std::chrono::seconds(delay)))
            break;
        ALOGW("Waited %ds for init.svc.mdnsd_netbpfload=stopped, still waiting...", delay);
    }
    if (!mainlineNetBpfLoadDone()) {
        ALOGE("FATAL: init.svc.mdnsd_netbpfload=stopped, yet !mainlineNetBpfLoadDone");
        // mdnsd_netbpfload is marked 'reboot_on_failure', init should start a reboot very soon now,
        // spamming logs with an abort is pointless
        for (;;);
    }
    return;
}

static inline void waitForBpf() {
    // Note: netd *can* be restarted, so this might get called a second time after boot is complete
    // at which point we don't need to (and shouldn't) wait for (more importantly start) loading bpf

    if (base::GetProperty("bpf.progs_loaded", "") != "1") {
        // AOSP platform netd & mainline don't need this (at least prior to U QPR3),
        // but there could be platform provided (xt_)bpf programs that oem/vendor
        // modified netd (which calls us during init) depends on...
        ALOGI("Waiting for platform BPF programs");
        bpf::waitForProgsLoaded();
    }

    if (!mainlineNetBpfLoadDone()) {
        // We're on < U QPR3 & it's the first time netd is starting up (unless crashlooping)
        //
        // On U QPR3+ netbpfload is guaranteed to run before the platform bpfloader,
        // so waitForProgsLoaded() implies mainlineNetBpfLoadDone().
        if (!base::SetProperty("ctl.start", "mdnsd_netbpfload")) {
            ALOGE("Failed to set property ctl.start=mdnsd_netbpfload, see dmesg for reason.");
            abort();
        }

        ALOGI("Waiting for Networking BPF programs");
        waitForNetProgsLoaded();
        ALOGI("Networking BPF programs are loaded");
    }

    ALOGI("BPF programs are loaded");
}

Result<void> BpfHandler::init(const char* cg2_path) {
    // This wait is effectively a no-op on U QPR3+ devices (as netd starts
    // *after* the synchronous 'exec_start bpfloader' which calls NetBpfLoad)
    // but checking for U QPR3 is hard.
    //
    // Waiting should not be required on U QPR3+ devices,
    // ...
    //
    // ...unless someone changed 'exec_start bpfloader' to 'start bpfloader'
    // in the rc file.
    //
    if (!isAtLeast25Q2) waitForBpf();

    RETURN_IF_NOT_OK(initPrograms(cg2_path));
    RETURN_IF_NOT_OK(initMaps());

    if (isAtLeast25Q2) {
        struct rlimit limit = {
            .rlim_cur = 1u << 30,  // 1 GiB
            .rlim_max = 1u << 30,  // 1 GiB
        };
        // 25Q2 netd.rc includes "rlimit memlock 1073741824 1073741824"
        // so this should be a no-op, and thus just succeed.
        // make sure it isn't lowered in platform netd.rc...
        if (setrlimit(RLIMIT_MEMLOCK, &limit))
            return Error(errno) << "Failed to set 1GiB RLIMIT_MEMLOCK";

        // Make sure netd can create & write maps.  sepolicy is V+, but enough to enforce on 25Q2+
        int key = 1;
        int value = 123;
        unique_fd map(bpf::createMap(BPF_MAP_TYPE_ARRAY, sizeof(key), sizeof(value), 2, 0));
        if (!map.ok()) return Error(errno) << "map create failed";
        int rv = bpf::writeToMapEntry(map, &key, &value, BPF_ANY);
        if (rv) return Error(errno) << "map write failed (rv=" << rv << ")";
    }

    if (isAtLeast26Q2) {
        // Bring up loopback interface first
        unique_fd sock(socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0));
        if (!sock.ok()) return Error(errno) << "socket6udp";

        struct ifreq ifr = {
            .ifr_name = "lo",
        };
        // due to sepolicy this cannot be a unix/dgram nor ipv6/tcp socket
        if (ioctl(sock.get(), SIOCGIFFLAGS, &ifr)) return Error(errno) << "SIOCGIFFLAGS";

        if (ifr.ifr_flags & IFF_UP) {
            // Nothing to be done, already up.
        } else {
            ifr.ifr_flags |= IFF_UP;
            if (ioctl(sock.get(), SIOCSIFFLAGS, &ifr)) return Error(errno) << "SIOCSIFFLAGS";
        }
    }

    if (isAtLeast26Q2) {
        // Simple boot time self test that verifies that loopback ip/tcp works
        // (this triggers at least bpf cgroup inet_create/ingress/egress/inet_release hooks)

        unique_fd server(socket(AF_INET6, SOCK_STREAM | SOCK_CLOEXEC, 0));
        if (!server.ok()) return Error(errno) << "socket6tcp";

        struct sockaddr_in6 addr6 = {
            .sin6_family = AF_INET6,
        };
        if (bind(server.get(), (struct sockaddr*)&addr6, sizeof(addr6)))
            return Error(errno) << "bind";

        if (listen(server.get(), 1)) return Error(errno) << "listen";

        socklen_t addr6_len = sizeof(addr6);
        if (getsockname(server.get(), (struct sockaddr*)&addr6, &addr6_len))
            return Error(errno) << "getsockname";
        if (addr6_len != sizeof(addr6)) return Error() << "addr6_len";

        unique_fd client(socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0));
        if (!client.ok()) return Error(errno) << "socket4tcp";

        if (isKernel64Bit() == isUserspace64bit()
            && isAtLeastKernelVersion(6, 1) && !isAtLeastKernelVersion(6, 18)) {
            {
                bool b = false;
                socklen_t len = sizeof(b);
                if (getsockopt(server.get(), SOL_TCP, TCP_ANDROID_L4S, &b, &len)) return Error(errno) << "gs-s1";
                if (len != sizeof(b)) return Error(errno) << "gs-s-len1";
                if (!b) return Error(errno) << "gs-s-off1";
            }
            {
                bool b = false;
                socklen_t len = sizeof(b);
                if (getsockopt(client.get(), SOL_TCP, TCP_ANDROID_L4S, &b, &len)) return Error(errno) << "gs-c1";
                if (len != sizeof(b)) return Error(errno) << "gs-c-len1";
                if (!b) return Error(errno) << "gs-c-off1";
            }
            {
                const bool off = false;
                if (setsockopt(server.get(), SOL_TCP, TCP_ANDROID_L4S, &off, sizeof(off))) return Error(errno) << "ss-s2";
                if (setsockopt(client.get(), SOL_TCP, TCP_ANDROID_L4S, &off, sizeof(off))) return Error(errno) << "ss-c2";
            }
            {
                bool b = true;
                socklen_t len = sizeof(b);
                if (getsockopt(server.get(), SOL_TCP, TCP_ANDROID_L4S, &b, &len)) return Error(errno) << "gs-s3";
                if (len != sizeof(b)) return Error(errno) << "gs-s-len3";
                if (b) return Error(errno) << "gs-s-on3";
            }
            {
                bool b = true;
                socklen_t len = sizeof(b);
                if (getsockopt(client.get(), SOL_TCP, TCP_ANDROID_L4S, &b, &len)) return Error(errno) << "gs-c3";
                if (len != sizeof(b)) return Error(errno) << "gs-c-len3";
                if (b) return Error(errno) << "gs-c-on3";
            }
            {
                const bool on = true;
                if (setsockopt(server.get(), SOL_TCP, TCP_ANDROID_L4S, &on, sizeof(on))) return Error(errno) << "ss-s4";
                if (setsockopt(client.get(), SOL_TCP, TCP_ANDROID_L4S, &on, sizeof(on))) return Error(errno) << "ss-c4";
            }
            {
                bool b = false;
                socklen_t len = sizeof(b);
                if (getsockopt(server.get(), SOL_TCP, TCP_ANDROID_L4S, &b, &len)) return Error(errno) << "gs-s5";
                if (len != sizeof(b)) return Error(errno) << "gs-s-len5";
                if (!b) return Error(errno) << "gs-s-off5";
            }
            {
                bool b = false;
                socklen_t len = sizeof(b);
                if (getsockopt(client.get(), SOL_TCP, TCP_ANDROID_L4S, &b, &len)) return Error(errno) << "gs-c5";
                if (len != sizeof(b)) return Error(errno) << "gs-c-len5";
                if (!b) return Error(errno) << "gs-c-off5";
            }
        }

        struct sockaddr_in addr4 = {
            .sin_family = AF_INET,
            .sin_port = addr6.sin6_port,
        };
        if (connect(client.get(), (struct sockaddr*)&addr4, sizeof(addr4)))
            return Error(errno) << "connect";  // fails with ENETUNREACH if 'lo' is down

        unique_fd peer(accept4(server.get(), NULL, NULL, SOCK_CLOEXEC));
        if (!peer.ok()) return Error(errno) << "accept";

        char snd = '@';
        if (write(client.get(), &snd, sizeof(snd)) != 1) return Error(errno) << "write";

        char rcv = 0;
        if (read(peer.get(), &rcv, sizeof(rcv)) != 1) return Error(errno) << "read";

        if (rcv != snd) return Error() << "wrong";

        // all 3 sockets are closed automatically
    }

    return {};
}

static void mapLockTest(void) {
    // The maps must be R/W, and as yet unopened (or more specifically not yet lock'ed).
    const char * const m1 = BPF_NETD_PATH "map_netd_lock_array_test_map";
    const char * const m2 = BPF_NETD_PATH "map_netd_lock_hash_test_map";

    unique_fd fd0(bpf::mapRetrieveExclusiveRW(m1)); if (!fd0.ok()) abort();  // grabs exclusive lock

    unique_fd fd1(bpf::mapRetrieveExclusiveRW(m2)); if (!fd1.ok()) abort();  // no conflict with fd0
    unique_fd fd2(bpf::mapRetrieveExclusiveRW(m2)); if ( fd2.ok()) abort();  // busy due to fd1
    unique_fd fd3(bpf::mapRetrieveRO(m2));          if (!fd3.ok()) abort();  // no lock taken
    unique_fd fd4(bpf::mapRetrieveRW(m2));          if ( fd4.ok()) abort();  // busy due to fd1
    fd1.reset();  // releases exclusive lock
    unique_fd fd5(bpf::mapRetrieveRO(m2));          if (!fd5.ok()) abort();  // no lock taken
    unique_fd fd6(bpf::mapRetrieveRW(m2));          if (!fd6.ok()) abort();  // now ok
    unique_fd fd7(bpf::mapRetrieveRO(m2));          if (!fd7.ok()) abort();  // no lock taken
    unique_fd fd8(bpf::mapRetrieveExclusiveRW(m2)); if ( fd8.ok()) abort();  // busy due to fd6

    fd0.reset();  // releases exclusive lock
    unique_fd fd9(bpf::mapRetrieveWO(m1));          if (!fd9.ok()) abort();  // grabs exclusive lock
}

Result<void> BpfHandler::initMaps() {
    // bpfLock() requires bpfGetFdMapId which is only available on 4.14+ kernels.
    if (bpf::lockingEnabled && isAtLeastKernelVersion(4, 14)) mapLockTest();

    RETURN_IF_NOT_OK(mStatsMapA.init(STATS_MAP_A_PATH));
    RETURN_IF_NOT_OK(mStatsMapB.init(STATS_MAP_B_PATH));
    RETURN_IF_NOT_OK(mConfigurationMap.init(CONFIGURATION_MAP_PATH));
    RETURN_IF_NOT_OK(mUidMigrationEnabledMap.init(UID_MIGRATION_ENABLED_MAP_PATH));
    RETURN_IF_NOT_OK(mUidPermissionChunkMap.init(UID_PERMISSION_CHUNK_MAP_PATH));
    RETURN_IF_NOT_OK(mUidPermissionMap.init(UID_PERMISSION_MAP_PATH));
    RETURN_IF_NOT_OK(mNetdPidMap.init(NETD_PID_MAP_PATH));
    // initialized last so mCookieTagMap.isValid() implies everything else is valid too
    RETURN_IF_NOT_OK(mCookieTagMap.init(COOKIE_TAG_MAP_PATH));

    const uint32_t zero = 0;
    const uint32_t my_pid = getpid();
    RETURN_IF_NOT_OK(mNetdPidMap.writeValue(zero, my_pid, BPF_ANY));

    return {};
}

bool BpfHandler::hasUpdateDeviceStatsPermission(uid_t uid) {
    // This implementation is the same logic as method ActivityManager#checkComponentPermission.
    // It implies that real uid can never be same as AID_USER_OFFSET == PER_USER_RANGE == 100000
    uint32_t appId = uid % AID_USER_OFFSET;
    uint32_t mapKey = 0;
    auto isUidMigrationEnabled = mUidMigrationEnabledMap.readValue(mapKey);
    if (isUidMigrationEnabled.ok() && isUidMigrationEnabled.value()) {
        uint32_t chunkId = uid / CHUNK_UID_COUNT;
        uint32_t index = uid / UIDS_PER_INT64 % CHUNK_INT64_COUNT;
        int shift = (uid % UIDS_PER_INT64 * PERMISSION_COUNT) & 63;
        auto chunk = mUidPermissionChunkMap.readValue(chunkId);
        if (chunk.ok() && (chunk.value().block[index] >> shift) &
                              PERMISSION_BIT_UPDATE_DEVICE_STATS) {
            return true;
        }
    } else {
        auto permission = mUidPermissionMap.readValue(appId);
        if (permission.ok() &&
            (permission.value() & BPF_PERMISSION_UPDATE_DEVICE_STATS)) {
            return true;
        }
    }
    return ((appId == AID_ROOT) || (appId == AID_SYSTEM) || (appId == AID_DNS));
}

int BpfHandler::tagSocket(int sockFd, uint32_t tag, uid_t chargeUid, uid_t realUid) {
    if (!mCookieTagMap.isValid()) return -EPERM;

    if (chargeUid != realUid && !hasUpdateDeviceStatsPermission(realUid)) return -EPERM;

    // Note that tagging the socket to AID_CLAT is only implemented in JNI ClatCoordinator.
    // The process is not allowed to tag socket to AID_CLAT via tagSocket() which would cause
    // process data usage accounting to be bypassed. Tagging AID_CLAT is used for avoiding counting
    // CLAT traffic data usage twice. See packages/modules/Connectivity/service/jni/
    // com_android_server_connectivity_ClatCoordinator.cpp
    if (chargeUid == AID_CLAT) return -EPERM;

    // The socket destroy listener only monitors on the group {INET_TCP, INET_UDP, INET6_TCP,
    // INET6_UDP}. Tagging listener unsupported sockets (on <5.10) means the tag cannot be
    // removed from tag map automatically. Eventually, it may run out of space due to dead tag
    // entries. Note that although tagSocket() of net client has already denied the family which
    // is neither AF_INET nor AF_INET6, the family validation is still added here just in case.
    // See tagSocket in system/netd/client/NetdClient.cpp and
    // TrafficController::makeSkDestroyListener in
    // packages/modules/Connectivity/service/native/TrafficController.cpp
    // TODO: remove this once the socket destroy listener can detect more types of socket destroy.
    int socketFamily;
    socklen_t familyLen = sizeof(socketFamily);
    if (getsockopt(sockFd, SOL_SOCKET, SO_DOMAIN, &socketFamily, &familyLen)) {
        ALOGE("Failed to getsockopt SO_DOMAIN: %s, fd: %d", strerror(errno), sockFd);
        return -errno;
    }
    if (socketFamily != AF_INET && socketFamily != AF_INET6) {
        ALOGV("Unsupported family: %d", socketFamily);
        return -EAFNOSUPPORT;
    }

    // On 5.10+ the BPF_CGROUP_INET_SOCK_RELEASE hook takes care of cookie tag map cleanup
    // during socket destruction. As such the socket destroy listener is superfluous.
    if (!isAtLeastKernelVersion(5, 10)) {
        int socketProto;
        socklen_t protoLen = sizeof(socketProto);
        if (getsockopt(sockFd, SOL_SOCKET, SO_PROTOCOL, &socketProto, &protoLen)) {
            ALOGE("Failed to getsockopt SO_PROTOCOL: %s, fd: %d", strerror(errno), sockFd);
            return -errno;
        }
        if (socketProto != IPPROTO_UDP && socketProto != IPPROTO_TCP) {
            ALOGV("Unsupported protocol: %d", socketProto);
            return -EPROTONOSUPPORT;
        }
    }

    uint64_t sock_cookie = getSocketCookie(sockFd);
    if (!sock_cookie) return -errno;

    auto configuration = mConfigurationMap.readValue(CURRENT_STATS_MAP_CONFIGURATION_KEY);
    if (!configuration.ok()) {
        ALOGE("Failed to get current configuration: %s",
              configuration.error().message().c_str());
        return -configuration.error().code();
    }
    if (configuration.value() != SELECT_MAP_A && configuration.value() != SELECT_MAP_B) {
        ALOGE("unknown configuration value: %d", configuration.value());
        return -EINVAL;
    }

    BpfMapRO<StatsKey, StatsValue>& currentMap =
            (configuration.value() == SELECT_MAP_A) ? mStatsMapA : mStatsMapB;

    uint32_t totalEntryCount = 0;
    uint32_t perUidEntryCount = 0;
    // Now we go through the stats map and count how many entries are associated
    // with chargeUid. If the uid entry hit the limit for each chargeUid, we block
    // the request to prevent the map from overflow. Note though that it isn't really
    // safe here to iterate over the map since it might be modified by the system server,
    // which might toggle the live stats map and clean it.
    Result<void> res = currentMap.forAll(
        [chargeUid, &totalEntryCount, &perUidEntryCount](const StatsKey& key) {
            if (key.uid == chargeUid) perUidEntryCount++;
            totalEntryCount++;
        }
    );
    if (!res.ok()) {
        ALOGE("Failed to count the stats entry in map: %s",
              res.error().message().c_str());
        return -res.error().code();
    }

    if (totalEntryCount > mTotalUidStatsEntriesLimit ||
        perUidEntryCount > mPerUidStatsEntriesLimit) {
        ALOGE("Too many stats entries in the map, total count: %u, chargeUid(%u) count: %u,"
              " blocking tag request to prevent map overflow",
              totalEntryCount, chargeUid, perUidEntryCount);
        return -EMFILE;
    }
    // Update the tag information of a socket to the cookieUidMap. Use BPF_ANY
    // flag so it will insert a new entry to the map if that value doesn't exist
    // yet and update the tag if there is already a tag stored. Since the eBPF
    // program in kernel only read this map, and is protected by rcu read lock. It
    // should be fine to concurrently update the map while eBPF program is running.
    UidTagValue newKey = {.uid = (uint32_t)chargeUid, .tag = tag};
    res = mCookieTagMap.writeValue(sock_cookie, newKey, BPF_ANY);
    if (!res.ok()) {
        ALOGE("Failed to tag the socket: %s", res.error().message().c_str());
        return -res.error().code();
    }
    ALOGV("Socket with cookie %" PRIu64 " tagged successfully with tag %" PRIu32 " uid %u "
          "and real uid %u", sock_cookie, tag, chargeUid, realUid);
    return 0;
}

int BpfHandler::untagSocket(int sockFd) {
    uint64_t sock_cookie = getSocketCookie(sockFd);
    if (!sock_cookie) return -errno;

    if (!mCookieTagMap.isValid()) return -EPERM;
    Result<void> res = mCookieTagMap.deleteValue(sock_cookie);
    if (!res.ok()) {
        const int err = res.error().code();
        if (err != ENOENT) ALOGE("Failed to untag socket: %s", res.error().message().c_str());
        return -err;
    }
    ALOGV("Socket with cookie %" PRIu64 " untagged successfully.", sock_cookie);
    return 0;
}

}  // namespace net
}  // namespace android
