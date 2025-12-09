/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <linux/bpf.h>

#include <android/log.h>
#include <android-base/result.h>
#include <android-base/stringprintf.h>
#include <android-base/unique_fd.h>

#include "BpfSyscallWrappers.h"
#include "bpf/BpfUtils.h"

#include <cstdio>
#include <functional>

namespace android {
namespace bpf {

using base::Result;
using base::ResultError;
using base::unique_fd;
using std::function;

#ifdef BPF_MAP_MAKE_VISIBLE_FOR_TESTING
#undef BPFMAP_VERBOSE
#define BPFMAP_VERBOSE
#undef BPFMAP_VERBOSE_ABORT
#define BPFMAP_VERBOSE_ABORT
#endif

[[noreturn]] __attribute__((__format__(__printf__, 2, 3))) static inline
void Abort(int __unused error, const char* __unused fmt, ...) {
#ifdef BPFMAP_VERBOSE_ABORT
    va_list va;
    va_start(va, fmt);

    fflush(stdout);
    vfprintf(stderr, fmt, va);
    if (error) fprintf(stderr, "; errno=%d [%s]", error, strerror(error));
    putc('\n', stderr);
    fflush(stderr);

    va_end(va);
#endif

    abort();
}

// We care about enabling SSO on 64-bit platforms
// It turns out that our string implementation can SSO optimize 22 characters + (23rd) trailing NULL
//
// 32-bit userspace is obsolete, and SSO would require shortening the errors more than is reasonable
//
// For the curious: the initial u8 is a 7+1 bitfield and stores strlen * 2 + (is_short_flag=0),
// though newer versions (_LIBCPP_ABI_VERSION >= 2), reverse the order
#ifndef __ILP32__
static_assert(sizeof(std::string) == 24);
#endif

// Our string implementation, on 64-bit platforms can fit up to 22 characters with SSO
// The common case (ENOENT) shouldn't require heap memory allocation nor string formatting
// Other errors will return (string argument passed to ErrnoErrorf) + ": " + strerror(errno)
#define ERROR_FROM_ERRNO(f) ({ \
  static_assert(__builtin_strlen(f ": ENOENT") <= 22, "ERROR_FROM_ERRNO - arg string too long"); \
  (errno == ENOENT) ? ResultError(f ": ENOENT", ENOENT) : ErrnoErrorf("BpfMap::" f "() failed"); \
})

// This is a class wrapper for eBPF maps. The eBPF map is a special in-kernel
// data structure that stores data in <Key, Value> pairs. It can be read/write
// from userspace by passing syscalls with the map file descriptor. This class
// is used to generalize the procedure of interacting with eBPF maps and hide
// the implementation detail from other process. Besides the basic syscalls
// wrapper, it also provides some useful helper functions as well as an iterator
// nested class to iterate the map more easily.
//
// NOTE: A kernel eBPF map may be accessed by both kernel and userspace
// processes at the same time. Or if the map is pinned as a virtual file, it can
// be obtained by multiple eBPF map class object and accessed concurrently.
// Though the map class object and the underlying kernel map are thread safe, it
// is not safe to iterate over a map while another thread or process is deleting
// from it. In this case the iteration can return duplicate entries.
template <class Key, class Value>
class BpfMapRO {
  public:
    BpfMapRO<Key, Value>() {};

    // explicitly force no copy constructor, since it would need to dup the fd
    // (later on, for testing, we still make available a copy assignment operator)
    BpfMapRO<Key, Value>(const BpfMapRO<Key, Value>&) = delete;

  protected:
    void abortOnMismatch(bool writable) const {
        if (!mMapFd.ok()) Abort(errno, "mMapFd %d is not valid", mMapFd.get());
        if (isAtLeastKernelVersion(4, 14)) {
            int flags = bpfGetFdMapFlags(mMapFd);
            if (flags < 0) Abort(errno, "bpfGetFdMapFlags fail: flags=%d", flags);
            if (flags & BPF_F_WRONLY) Abort(0, "map is write-only (flags=0x%X)", flags);
            if (writable && (flags & BPF_F_RDONLY))
                Abort(0, "writable map is actually read-only (flags=0x%X)", flags);
            int keySize = bpfGetFdKeySize(mMapFd);
            if (keySize != sizeof(Key))
                Abort(errno, "map key size mismatch (expected=%zu, actual=%d)",
                      sizeof(Key), keySize);
            int valueSize = bpfGetFdValueSize(mMapFd);
            if (valueSize != sizeof(Value))
                Abort(errno, "map value size mismatch (expected=%zu, actual=%d)",
                      sizeof(Value), valueSize);
        }
    }

  public:
    explicit BpfMapRO<Key, Value>(const char* pathname) {
        mMapFd.reset(mapRetrieveRO(pathname));
        abortOnMismatch(/* writable */ false);
    }

    Result<Key> getFirstKey() const {
        Key firstKey;
        if (getFirstMapKey(mMapFd, &firstKey)) return ERROR_FROM_ERRNO("getFirstKey");
        return firstKey;
    }

    Result<Key> getNextKey(const Key& key) const {
        Key nextKey;
        if (getNextMapKey(mMapFd, &key, &nextKey)) return ERROR_FROM_ERRNO("getNextKey");
        return nextKey;
    }

    Result<Value> readValue(const Key& key) const {
        Value value;
        if (findMapEntry(mMapFd, &key, &value)) return ERROR_FROM_ERRNO("readValue");
        return value;
    }

  protected:
    [[clang::reinitializes]] Result<void> init(const char* path, int fd, bool writable) {
        mMapFd.reset(fd);
        if (!mMapFd.ok()) {
            return ErrnoErrorf("Pinned map not accessible or does not exist: ({})", path);
        }
        // Normally we should return an error here instead of calling abort,
        // but this cannot happen at runtime without a massive code bug (K/V type mismatch)
        // and as such it's better to just blow the system up and let the developer fix it.
        // Crashes are much more likely to be noticed than logs and missing functionality.
        abortOnMismatch(writable);
        return {};
    }

    // Observed 4 <= sizeof(Key) <= 16 (48 for Java), 1 <= sizeof(Value) <= 32 (64 for Java)
    // You can uncomment the following to check:
    //   static_assert(sizeof(Key) >= 4);
    //   static_assert(sizeof(Key) <= 16); // 48 observed, but not in C++
    //   static_assert(sizeof(Value) >= 1);
    //   static_assert(sizeof(Value) <= 32); // 64 observed, but not in C++

    // ~16KiB initial stack usage seems reasonable
    static constexpr int BATCHSIZE = 16384 / (sizeof(Key) + sizeof(Value));
    static_assert(BATCHSIZE >= 64, "consider Key/Value size, whether incr mem limit, decr batch req");
    static_assert(BATCHSIZE * sizeof(Key) + BATCHSIZE * sizeof(Value) <= 16384);

    Result<void> doBulkLookupAndMaybeDelete(bool del, const function<void(const Key &, const Value &)> &f) const {
        union { Key k; uint32_t nr; } batch;
        bool first = true;

        // starting with N == 1 fails with -28/ENOSPC in:
        //   BpfNetworkStatsTest.cpp BpfNetworkStatsHelperTest#TestGetStatsSortedAndGrouped
        // requiring us to loop back around, kernel code itself claims that in practice 5
        // is almost always enough for a bucket (which is what you'd expect, it's not a good
        // hashtable if there's lots of items in a single bucket)
        //
        // Since we start with 64+ we shouldn't ever actually need to increase N...
        // Also note that the 'true' condition is not really an infinite loop,
        // as we'll blow up the stack and crash instead of looping infinitely.
        // But that also shouldn't happen cause it would imply/require a ridiculously
        // large bpf map sitting entirely in one bucket...
        for (int N = BATCHSIZE; true; N *= 2) {
            // N is how many we have space for, can grow on demand as needed
            Key keys[N];
            Value values[N];
            for (;;) {
                uint32_t count = N; // how many to fetch (and possibly delete)
                int rv = batchLookupAndMaybeDelete(mMapFd, first ? NULL : &batch, &batch, &keys, &values, &count, del);
                if (rv && errno == ENOSPC) break;  // not enough space for full HASH bucket, go around the *outer* loop
                if (rv && errno != ENOENT) return ERROR_FROM_ERRNO("bulkLookup&Del");
                // count is now how many *were* fetched (and possibly delete)
                for (unsigned i = 0; i < count; ++i) f(keys[i], values[i]);
                if (rv) return {};  // ENOENT -> success
                first = false;
            }
        }
    }

  public:
    // Function that tries to get map from a pinned path.
    [[clang::reinitializes]] Result<void> init(const char* path) {
        return init(path, mapRetrieveRO(path), /* writable */ false);
    }

    // For all keys in the map call filter() - unless it errors out.
    Result<void> iterate(const function<Result<void>(const Key &)> &filter) const {
        Result<Key> curKey = getFirstKey();
        while (curKey.ok()) {
            const Result<Key> &nextKey = getNextKey(curKey.value());
            Result<void> status = filter(curKey.value());
            if (!status.ok()) return status;
            curKey = nextKey;
        }
        if (curKey.error().code() == ENOENT) return {};
        return curKey.error();
    }

    // Does not allow early termination (via f erroring out) - may be implemented with bulk api
    Result<void> forAll(const function<void(const Key &)> &f) const {
        // No kernel bpfmap bulk lookup api which doesn't return both keys & values.
        if (isAtLeastKernelVersion(5, 10)) return doBulkLookupAndMaybeDelete(/*delete*/ false,
            [&f](const Key &key, const Value &) {
                f(key);
            }
        );
        return iterate(
            [&f](const Key &key) -> Result<void> {
                f(key);
                return {};
            }
        );
    }

    // For all (key, value) pairs in the map call filter() - unless it errors out.
    Result<void> iterate(const function<Result<void>(const Key &, const Value &)> &filter) const {
        Result<Key> curKey = getFirstKey();
        while (curKey.ok()) {
            const Result<Key> &nextKey = getNextKey(curKey.value());
            Result<Value> curValue = readValue(curKey.value());
            if (!curValue.ok()) return curValue.error();
            Result<void> status = filter(curKey.value(), curValue.value());
            if (!status.ok()) return status;
            curKey = nextKey;
        }
        if (curKey.error().code() == ENOENT) return {};
        return curKey.error();
    }

    // Does not allow early termination (via f erroring out) - maybe implemented with bulk api
    Result<void> forAll(const function<void(const Key &, const Value &)> &f) const {
        if (isAtLeastKernelVersion(5, 10)) return doBulkLookupAndMaybeDelete(/*delete*/ false, f);
        return iterate(
            [&f](const Key &key, const Value &value) -> Result<void> {
                f(key, value);
                return {};
            }
        );
    }

#ifdef BPF_MAP_MAKE_VISIBLE_FOR_TESTING
    const unique_fd& getMap() const { return mMapFd; };

    // Copy assignment operator - due to need for fd duping, should not be used in non-test code.
    BpfMapRO<Key, Value>& operator=(const BpfMapRO<Key, Value>& other) {
        if (this != &other) mMapFd.reset(fcntl(other.mMapFd.get(), F_DUPFD_CLOEXEC, 0));
        return *this;
    }
#else
    BpfMapRO<Key, Value>& operator=(const BpfMapRO<Key, Value>&) = delete;
#endif

    // Move assignment operator
    BpfMapRO<Key, Value>& operator=(BpfMapRO<Key, Value>&& other) noexcept {
        if (this != &other) {
            mMapFd = std::move(other.mMapFd);
            other.reset();
        }
        return *this;
    }

#ifdef BPF_MAP_MAKE_VISIBLE_FOR_TESTING
    // Note that unique_fd.reset() carefully saves and restores the errno,
    // and BpfMap.reset() won't touch the errno if passed in fd is negative either,
    // hence you can do something like BpfMap.reset(systemcall()) and then
    // check BpfMap.isValid() and look at errno and see why systemcall() failed.
    [[clang::reinitializes]] void reset(int fd) {
        mMapFd.reset(fd);
        if (mMapFd.ok()) abortOnMismatch(/* writable */ false);  // false isn't ideal
    }

    // unique_fd has an implicit int conversion defined, which combined with the above
    // reset(int) would result in double ownership of the fd, hence we either need a custom
    // implementation of reset(unique_fd), or to delete it and thus cause compile failures
    // to catch this and prevent it.
    void reset(unique_fd fd) = delete;
#endif

    [[clang::reinitializes]] void reset() {
        mMapFd.reset();
    }

    bool isValid() const { return mMapFd.ok(); }

    Result<bool> isEmpty() const {
        auto key = getFirstKey();
        if (key.ok()) return false;
        if (key.error().code() == ENOENT) return true;
        return key.error();
    }

  protected:
    unique_fd mMapFd;
};

template <class Key, class Value>
class BpfMapRW : public BpfMapRO<Key, Value> {
  protected:
    using BpfMapRO<Key, Value>::mMapFd;
    using BpfMapRO<Key, Value>::abortOnMismatch;

  public:
    using BpfMapRO<Key, Value>::BpfMapRO;

    explicit BpfMapRW<Key, Value>(const char* pathname) {
        mMapFd.reset(mapRetrieveRW(pathname));
        abortOnMismatch(/* writable */ true);
    }

    // Function that tries to get map from a pinned path.
    [[clang::reinitializes]] Result<void> init(const char* path) {
        return BpfMapRO<Key,Value>::init(path, mapRetrieveRW(path), /* writable */ true);
    }

    Result<void> writeValue(const Key& key, const Value& value, uint64_t flags) {
        if (writeToMapEntry(mMapFd, &key, &value, flags)) return ERROR_FROM_ERRNO("writeValue");
        return {};
    }

#ifdef BPF_MAP_MAKE_VISIBLE_FOR_TESTING
    [[clang::reinitializes]] Result<void> resetMap(bpf_map_type map_type,
                                                   uint32_t max_entries,
                                                   uint32_t map_flags = 0) {
        if (map_flags & BPF_F_WRONLY) Abort(0, "map_flags is write-only");
        if (map_flags & BPF_F_RDONLY) Abort(0, "map_flags is read-only");
        mMapFd.reset(createMap(map_type, sizeof(Key), sizeof(Value), max_entries,
                               map_flags));
        if (!mMapFd.ok()) return ERROR_FROM_ERRNO("resetMap");
        abortOnMismatch(/* writable */ true);
        return {};
    }
#endif
};

template <class Key, class Value>
class BpfMap : public BpfMapRW<Key, Value> {
  protected:
    using BpfMapRW<Key, Value>::mMapFd;
    using BpfMapRW<Key, Value>::doBulkLookupAndMaybeDelete;

  public:
    using BpfMapRW<Key, Value>::BpfMapRW;
    using BpfMapRW<Key, Value>::getFirstKey;
    using BpfMapRW<Key, Value>::getNextKey;
    using BpfMapRW<Key, Value>::readValue;

    Result<void> deleteValue(const Key& key) {
        if (deleteMapEntry(mMapFd, &key)) return ERROR_FROM_ERRNO("deleteValue");
        return {};
    }

    Result<Value> readAndDeleteValue(const Key& key) {
        if (isAtLeastKernelVersion(5, 4)) {
            Value value;
            if (!findAndDeleteMapEntry(mMapFd, &key, &value)) return value;
            if (errno == ENOENT) return ERROR_FROM_ERRNO("read&DeleteVal");
        };

        // fallback path in case of weird error and for pre-5.4 kernels

        Result<Value> v = readValue(key);
        if (!v.ok()) return v;  // most likely ENOENT
        Result<void> res = deleteValue(key);
        if (res.ok()) return v;
        // We already have the data, not clear what to do on delete failure...
        // Let's just log something...
        // (but ignore ENOENT in case we're racing against someone else)
#ifdef BPFMAP_VERBOSE
        if (res.error().code() != ENOENT)
            ALOGE("BpfMap::readAndDeleteValue(): read but failed to delete data %s",
                  strerror(res.error().code()));
#endif
        return v;
    }

    Result<void> clear() {
        while (true) {
            auto key = getFirstKey();
            if (!key.ok()) {
                if (key.error().code() == ENOENT) return {};  // empty: success
                return key.error();                           // Anything else is an error
            }
            auto res = deleteValue(key.value());
            if (!res.ok()) {
                // Someone else could have deleted the key, so ignore ENOENT
                if (res.error().code() == ENOENT) continue;
#ifdef BPFMAP_VERBOSE
                ALOGE("Failed to delete data %s", strerror(res.error().code()));
#endif
                return res.error();
            }
        }
    }

    // Does not allow early termination (via f erroring out) - maybe implemented with bulk api
    Result<void> consume(const std::function<void(const Key&, const Value&)>& f) {
        if (isAtLeastKernelVersion(5, 10)) return doBulkLookupAndMaybeDelete(/*delete*/true, f);
        Result<Key> curKey = getFirstKey();
        while (curKey.ok()) {
            const Result<Key> &nextKey = getNextKey(curKey.value());
            Result<Value> curValue = readAndDeleteValue(curKey.value());
            // on readAndDelete error (most likely ENOENT due to a delete race) move to next key...
            if (curValue.ok()) f(curKey.value(), curValue.value());
            curKey = nextKey;
        }
        if (curKey.error().code() == ENOENT) return {};
        return curKey.error();
    }
};

}  // namespace bpf
}  // namespace android
