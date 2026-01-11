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

#pragma once

#include <android-base/unique_fd.h>
#include <stdlib.h>
#include <unistd.h>
#include <linux/bpf.h>
#include <linux/unistd.h>
#include <sys/file.h>


namespace android {
namespace bpf {

using ::android::base::borrowed_fd;
using ::android::base::unique_fd;

inline uint64_t ptr_to_u64(const void * const x) {
    return (uint64_t)(uintptr_t)x;
}

/* Note: bpf_attr is a union which might have a much larger size then the anonymous struct portion
 * of it that we are using.  The kernel's bpf() system call will perform a strict check to ensure
 * all unused portions are zero.  It will fail with E2BIG if we don't fully zero bpf_attr.
 */

inline int bpf(enum bpf_cmd cmd, const bpf_attr& attr) {
    return syscall(__NR_bpf, cmd, &attr, sizeof(attr));
}

// this version is meant for use with cmd's which mutate the argument
inline int bpf(enum bpf_cmd cmd, bpf_attr *attr) {
    return syscall(__NR_bpf, cmd, attr, sizeof(*attr));
}

inline int createMap(bpf_map_type map_type, uint32_t key_size, uint32_t value_size,
                     uint32_t max_entries, uint32_t map_flags) {
    return bpf(BPF_MAP_CREATE, {
                                       .map_type = map_type,
                                       .key_size = key_size,
                                       .value_size = value_size,
                                       .max_entries = max_entries,
                                       .map_flags = map_flags,
                               });
}

// Note:
//   'map_type' must be one of BPF_MAP_TYPE_{ARRAY,HASH}_OF_MAPS
//   'value_size' must be sizeof(u32), ie. 4
//   'inner_map_fd' is basically a template specifying {map_type, key_size, value_size, max_entries, map_flags}
//   of the inner map type (and possibly only key_size/value_size actually matter?).
inline int createOuterMap(bpf_map_type map_type, uint32_t key_size, uint32_t value_size,
                          uint32_t max_entries, uint32_t map_flags,
                          const borrowed_fd& inner_map_fd) {
    return bpf(BPF_MAP_CREATE, {
                                       .map_type = map_type,
                                       .key_size = key_size,
                                       .value_size = value_size,
                                       .max_entries = max_entries,
                                       .map_flags = map_flags,
                                       .inner_map_fd = static_cast<__u32>(inner_map_fd.get()),
                               });
}

inline int writeToMapEntry(const borrowed_fd& map_fd, const void* key, const void* value,
                           uint64_t flags) {
    return bpf(BPF_MAP_UPDATE_ELEM, {
                                            .map_fd = static_cast<__u32>(map_fd.get()),
                                            .key = ptr_to_u64(key),
                                            .value = ptr_to_u64(value),
                                            .flags = flags,
                                    });
}

inline int findMapEntry(const borrowed_fd& map_fd, const void* key, void* value) {
    return bpf(BPF_MAP_LOOKUP_ELEM, {
                                            .map_fd = static_cast<__u32>(map_fd.get()),
                                            .key = ptr_to_u64(key),
                                            .value = ptr_to_u64(value),
                                    });
}

inline int deleteMapEntry(const borrowed_fd& map_fd, const void* key) {
    return bpf(BPF_MAP_DELETE_ELEM, {
                                            .map_fd = static_cast<__u32>(map_fd.get()),
                                            .key = ptr_to_u64(key),
                                    });
}

inline int getNextMapKey(const borrowed_fd& map_fd, const void* key, void* next_key) {
    return bpf(BPF_MAP_GET_NEXT_KEY, {
                                             .map_fd = static_cast<__u32>(map_fd.get()),
                                             .key = ptr_to_u64(key),
                                             .next_key = ptr_to_u64(next_key),
                                     });
}

inline int getFirstMapKey(const borrowed_fd& map_fd, void* firstKey) {
    return getNextMapKey(map_fd, NULL, firstKey);
}

inline int bpfFdPin(const borrowed_fd& map_fd, const char* pathname) {
    return bpf(BPF_OBJ_PIN, {
                                    .pathname = ptr_to_u64(pathname),
                                    .bpf_fd = static_cast<__u32>(map_fd.get()),
                            });
}

inline int bpfFdGet(const char* pathname, uint32_t flag) {
    return bpf(BPF_OBJ_GET, {
                                    .pathname = ptr_to_u64(pathname),
                                    .file_flags = flag,
                            });
}

int bpfGetFdMapId(const borrowed_fd& map_fd);

inline int bpfLock(int fd, short type) {
    if (fd < 0) return fd;  // pass any errors straight through
#ifdef BPF_MAP_LOCKLESS_FOR_TEST
    return fd;
#endif
    int mapId = bpfGetFdMapId(fd);
    int saved_errno = errno;
    // 4.14+ required to fetch map id, but we don't want to call isAtLeastKernelVersion
    if (mapId == -1 && saved_errno == EINVAL) return fd;
    if (mapId <= 0) abort();  // should not be possible

    // on __LP64__ (aka. 64-bit userspace) 'struct flock64' is the same as 'struct flock'
    struct flock64 fl = {
        .l_type = type,        // short: F_{RD,WR,UN}LCK
        .l_whence = SEEK_SET,  // short: SEEK_{SET,CUR,END}
        .l_start = mapId,      // off_t: start offset
        .l_len = 1,            // off_t: number of bytes
    };

    // see: bionic/libc/bionic/fcntl.cpp: iff !__LP64__ this uses fcntl64
    int ret = fcntl(fd, F_OFD_SETLK, &fl);
    if (!ret) return fd;  // success
    close(fd);
    return ret;  // most likely -1 with errno == EAGAIN, due to already held lock
}

inline int mapRetrieveLocklessRW(const char* pathname) {
    return bpfFdGet(pathname, 0);
}

inline int mapRetrieveExclusiveRW(const char* pathname) {
    return bpfLock(mapRetrieveLocklessRW(pathname), F_WRLCK);
}

inline int mapRetrieveRW(const char* pathname) {
    return bpfLock(mapRetrieveLocklessRW(pathname), F_RDLCK);
}

inline int mapRetrieveRO(const char* pathname) {
    return bpfFdGet(pathname, BPF_F_RDONLY);
}

// WARNING: it's impossible to grab a shared (ie. read) lock on a write-only fd,
// so we instead choose to grab an exclusive (ie. write) lock.
inline int mapRetrieveWO(const char* pathname) {
    return bpfLock(bpfFdGet(pathname, BPF_F_WRONLY), F_WRLCK);
}

inline int retrieveProgram(const char* pathname) {
    return bpfFdGet(pathname, BPF_F_RDONLY);
}

inline bool usableProgram(const char* pathname) {
    unique_fd fd(retrieveProgram(pathname));
    return fd.ok();
}

inline int attachProgram(bpf_attach_type type, const borrowed_fd& prog_fd,
                         const borrowed_fd& cg_fd, uint32_t flags = 0) {
    return bpf(BPF_PROG_ATTACH, {
                                        .target_fd = static_cast<__u32>(cg_fd.get()),
                                        .attach_bpf_fd = static_cast<__u32>(prog_fd.get()),
                                        .attach_type = type,
                                        .attach_flags = flags,
                                });
}

inline int detachProgram(bpf_attach_type type, const borrowed_fd& cg_fd) {
    return bpf(BPF_PROG_DETACH, {
                                        .target_fd = static_cast<__u32>(cg_fd.get()),
                                        .attach_type = type,
                                });
}

inline int queryProgram(const borrowed_fd& cg_fd,
                        enum bpf_attach_type attach_type,
                        __u32 query_flags = 0,
                        __u32 attach_flags = 0) {
    int prog_id = -1;  // equivalent to an array of one integer.
    bpf_attr arg = {
            .query = {
                    .target_fd = static_cast<__u32>(cg_fd.get()),
                    .attach_type = attach_type,
                    .query_flags = query_flags,
                    .attach_flags = attach_flags,
                    .prog_ids = ptr_to_u64(&prog_id),  // pointer to output array
                    .prog_cnt = 1,  // in: space - nr of ints in the array, out: used
            }
    };
    int v = bpf(BPF_PROG_QUERY, &arg);
    if (v) return v;  // error case
    if (!arg.query.prog_cnt) return 0;  // no program, kernel never returns zero id
    return prog_id;  // return actual id
}

inline int detachSingleProgram(bpf_attach_type type, const borrowed_fd& prog_fd,
                               const borrowed_fd& cg_fd) {
    return bpf(BPF_PROG_DETACH, {
                                        .target_fd = static_cast<__u32>(cg_fd.get()),
                                        .attach_bpf_fd = static_cast<__u32>(prog_fd.get()),
                                        .attach_type = type,
                                });
}

// Available in 4.12 and later kernels.
inline int runProgram(const borrowed_fd &prog_fd, const void *data,
                      const uint32_t data_size, const void *ctx = nullptr,
                      const uint32_t ctx_size = 0) {
    return bpf(BPF_PROG_RUN,
               {
                   .test =
                       {
                           .prog_fd = static_cast<__u32>(prog_fd.get()),
                           .data_size_in = data_size,
                           .data_in = ptr_to_u64(data),
                           .ctx_size_in = ctx_size,
                           .ctx_in = ptr_to_u64(ctx),
                       },
               });
}

// BPF_OBJ_GET_INFO_BY_FD requires 4.14+ kernel
//
// Note: some fields are only defined in newer kernels (ie. the map_info struct grows
// over time), so we need to check that the field we're interested in is actually
// supported/returned by the running kernel.  We do this by checking it is fully
// within the bounds of the struct size as reported by the kernel.
#define DEFINE_BPF_GET_FD(TYPE, NAME, FIELD) \
inline int bpfGetFd ## NAME(const borrowed_fd& fd) { \
    struct bpf_ ## TYPE ## _info info = {}; \
    union bpf_attr attr = { .info = { \
        .bpf_fd = static_cast<__u32>(fd.get()), \
        .info_len = sizeof(info), \
        .info = ptr_to_u64(&info), \
    }}; \
    int rv = bpf(BPF_OBJ_GET_INFO_BY_FD, &attr); \
    if (rv) return rv; \
    if (attr.info.info_len < offsetof(bpf_ ## TYPE ## _info, FIELD) + sizeof(info.FIELD)) { \
        errno = EOPNOTSUPP; \
        return -1; \
    }; \
    return info.FIELD; \
}

// All 9 of these fields are already present in Linux v4.14 (even ACK 4.14-P)
// while BPF_OBJ_GET_INFO_BY_FD is not implemented at all in v4.9 (even ACK 4.9-Q)
DEFINE_BPF_GET_FD(map, MapType, type)            // int bpfGetFdMapType(const borrowed_fd& map_fd)
DEFINE_BPF_GET_FD(map, MapId, id)                // int bpfGetFdMapId(const borrowed_fd& map_fd)
DEFINE_BPF_GET_FD(map, KeySize, key_size)        // int bpfGetFdKeySize(const borrowed_fd& map_fd)
DEFINE_BPF_GET_FD(map, ValueSize, value_size)    // int bpfGetFdValueSize(const borrowed_fd& map_fd)
DEFINE_BPF_GET_FD(map, MaxEntries, max_entries)  // int bpfGetFdMaxEntries(const borrowed_fd& map_fd)
DEFINE_BPF_GET_FD(map, MapFlags, map_flags)      // int bpfGetFdMapFlags(const borrowed_fd& map_fd)
DEFINE_BPF_GET_FD(prog, ProgId, id)              // int bpfGetFdProgId(const borrowed_fd& prog_fd)
DEFINE_BPF_GET_FD(prog, JitProgLen, jited_prog_len)   // int bpfGetFdJitProgLen(...)
DEFINE_BPF_GET_FD(prog, XlatProgLen, xlated_prog_len) // int bpfGetFdXlatProgLen(...)

#undef DEFINE_BPF_GET_FD

}  // namespace bpf
}  // namespace android

