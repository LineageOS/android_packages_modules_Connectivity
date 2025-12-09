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

#include <inttypes.h>
#include <net/if.h>
#include <string.h>
#include <unordered_set>

#include <utils/Log.h>
#include <utils/misc.h>

#include "android-base/file.h"
#include "android-base/strings.h"
#include "android-base/unique_fd.h"
#include "bpf/BpfMap.h"
#include "netd.h"
#include "netdbpf/BpfNetworkStats.h"

#ifdef LOG_TAG
#undef LOG_TAG
#endif

#define LOG_TAG "BpfNetworkStats"

namespace android {
namespace bpf {

using base::Result;

BpfMapRW<uint32_t, IfaceValue>& getIfaceIndexNameMap() {
    static BpfMapRW<uint32_t, IfaceValue> ifaceIndexNameMap(IFACE_INDEX_NAME_MAP_PATH);
    return ifaceIndexNameMap;
}

const BpfMapRO<uint32_t, StatsValue>& getIfaceStatsMap() {
    static BpfMapRO<uint32_t, StatsValue> ifaceStatsMap(IFACE_STATS_MAP_PATH);
    return ifaceStatsMap;
}

static constexpr unsigned cacheSize = 1024;  // see 'iface_index_name_map' bpf map size

#if !defined(__ILP32__) && !defined(__riscv)
static_assert(std::atomic<IfaceValue>::is_always_lock_free,
              "Error: 16-byte IfaceValue is not lock-free on this platform!");
#define CACHE_IS_ATOMIC
static std::atomic<IfaceValue> cache[cacheSize];
#else
static IfaceValue cache[cacheSize];
#endif


static inline const IfaceValue& updateCache(unsigned i, const IfaceValue &v) {
    if (i < cacheSize) {
#ifdef CACHE_IS_ATOMIC
        cache[i].store(v, std::memory_order_relaxed);
#else
        cache[i] = v;
#endif
    }
    return v;
}

Result<IfaceValue> ifindex2name(const uint32_t ifindex) {
    if (ifindex < cacheSize) {
#ifdef CACHE_IS_ATOMIC
        IfaceValue c = cache[ifindex].load(std::memory_order_relaxed);
#else
        IfaceValue c = cache[ifindex];
#endif
        if (c.name[0]) return c;
    }

    Result<IfaceValue> v = getIfaceIndexNameMap().readValue(ifindex);
    if (v.ok()) return updateCache(ifindex, v.value());
    IfaceValue iv = {};
    if (!if_indextoname(ifindex, iv.name)) return v;  // v is an error
    getIfaceIndexNameMap().writeValue(ifindex, iv, BPF_ANY);
    return updateCache(ifindex, iv);
}

void bpfRegisterIface(const char* iface) {
    if (!iface) return;
    if (strlen(iface) >= sizeof(IfaceValue)) return;
    uint32_t ifindex = if_nametoindex(iface);
    if (!ifindex) return;
    IfaceValue ifname = {};
    strlcpy(ifname.name, iface, sizeof(ifname.name));
    getIfaceIndexNameMap().writeValue(ifindex, ifname, BPF_ANY);
    updateCache(ifindex, ifname);
}

int bpfGetUidStatsInternal(uid_t uid, StatsValue* stats,
                           const BpfMapRO<uint32_t, StatsValue>& appUidStatsMap) {
    auto statsEntry = appUidStatsMap.readValue(uid);
    if (!statsEntry.ok()) {
        *stats = {};
        return (statsEntry.error().code() == ENOENT) ? 0 : -statsEntry.error().code();
    }
    *stats = statsEntry.value();
    return 0;
}

int bpfGetUidStats(uid_t uid, StatsValue* stats) {
    static BpfMapRO<uint32_t, StatsValue> appUidStatsMap(APP_UID_STATS_MAP_PATH);
    return bpfGetUidStatsInternal(uid, stats, appUidStatsMap);
}

int bpfGetIfaceStatsInternal(const char* iface, StatsValue* stats,
                             const BpfMapRO<uint32_t, StatsValue>& ifaceStatsMap,
                             const IfIndexToNameFunc ifindex2name) {
    *stats = {};

    if (!iface) { // wildcard iface
        auto res = ifaceStatsMap.forAll(
            [stats](const uint32_t &, const StatsValue &value) {
                *stats += value;
            }
        );
        return res.ok() ? 0 : -res.error().code();
    }

    // specific iface
    int64_t unknownIfaceBytesTotal = 0;

    if (isAtLeastKernelVersion(5, 10)) {
        // On 5.10+ bulk lookup api returns values for free, so just use forAll(f(K,V))
        auto res = ifaceStatsMap.forAll(
            [iface, stats, ifindex2name, &unknownIfaceBytesTotal, &ifaceStatsMap](const uint32_t& key, const StatsValue& value) {
                Result<IfaceValue> ifname = ifindex2name(key);
                if (!ifname.ok()) {
                    maybeLogUnknownIface(key, ifaceStatsMap, key, &unknownIfaceBytesTotal);
                    return;
                }
                if (!strcmp(iface, ifname.value().name)) *stats += value;
            }
        );
        return res.ok() ? 0 : -res.error().code();
    }

    // Before 5.10 we try to avoid doing readValue unless needed
    auto res = ifaceStatsMap.forAll(
        [iface, stats, ifindex2name, &unknownIfaceBytesTotal, &ifaceStatsMap](const uint32_t& key) {
            Result<IfaceValue> ifname = ifindex2name(key);
            if (!ifname.ok()) {
                maybeLogUnknownIface(key, ifaceStatsMap, key, &unknownIfaceBytesTotal);
                return;
            }
            if (!strcmp(iface, ifname.value().name)) {
                Result<StatsValue> statsEntry = ifaceStatsMap.readValue(key);
                // error shouldn't be possible, if it somehow does, just ignore it and keep going
                if (statsEntry.ok()) *stats += statsEntry.value();
            }
        }
    );
    return res.ok() ? 0 : -res.error().code();
}

int bpfGetIfaceStats(const char* iface, StatsValue* stats) {
    return bpfGetIfaceStatsInternal(iface, stats, getIfaceStatsMap(), ifindex2name);
}

int bpfGetIfIndexStatsInternal(uint32_t ifindex, StatsValue* stats,
                               const BpfMapRO<uint32_t, StatsValue>& ifaceStatsMap) {
    auto statsEntry = ifaceStatsMap.readValue(ifindex);
    if (!statsEntry.ok()) {
        *stats = {};
        return (statsEntry.error().code() == ENOENT) ? 0 : -statsEntry.error().code();
    }
    *stats = statsEntry.value();
    return 0;
}

int bpfGetIfIndexStats(int ifindex, StatsValue* stats) {
    return bpfGetIfIndexStatsInternal(ifindex, stats, getIfaceStatsMap());
}

stats_line populateStatsEntry(const StatsKey& statsKey, const StatsValue& statsEntry,
                              const IfaceValue& ifname) {
    stats_line newLine;
    strlcpy(newLine.iface, ifname.name, sizeof(newLine.iface));
    newLine.uid = (int32_t)statsKey.uid;
    newLine.set = (int32_t)statsKey.counterSet;
    newLine.tag = (int32_t)statsKey.tag;
    newLine.rxPackets = statsEntry.rxPackets;
    newLine.txPackets = statsEntry.txPackets;
    newLine.rxBytes = statsEntry.rxBytes;
    newLine.txBytes = statsEntry.txBytes;
    return newLine;
}

// Note: return code is *informational* only, lines always contains whatever we managed to retrieve
// and the contents of the statsMap is consumed (ie. cleared).
int parseBpfNetworkStatsDetailInternal(std::vector<stats_line>& lines,
                                       BpfMap<StatsKey, StatsValue>& statsMap,
                                       const IfIndexToNameFunc ifindex2name) {
    int64_t unknownIfaceBytesTotal = 0;
    Result<void> res = statsMap.consume(
        [&lines, &unknownIfaceBytesTotal, &ifindex2name, &statsMap](const StatsKey &key, const StatsValue &value) {
            Result<IfaceValue> ifname = ifindex2name(key.ifaceIndex);
            if (!ifname.ok()) {
                // these stats will be lost/deleted
                maybeLogUnknownIface(key.ifaceIndex, statsMap, key, &unknownIfaceBytesTotal);
                return;
            }
            stats_line newLine = populateStatsEntry(key, value, ifname.value());
            lines.push_back(newLine);
            if (newLine.tag) {
                // account tagged traffic in the untagged stats (for historical reasons?)
                newLine.tag = 0;
                lines.push_back(newLine);
            }
        }
    );

    // Since eBPF use hash map to record stats, network stats collected from
    // eBPF will be out of order. And the performance of findIndexHinted in
    // NetworkStats will also be impacted.
    //
    // Furthermore, since the StatsKey contains iface index, the network stats
    // reported to framework would create items with the same iface, uid, tag
    // and set, which causes NetworkStats maps wrong item to subtract.
    //
    // Thus, the stats needs to be properly sorted and grouped before reported.
    groupNetworkStats(lines);

    // There isn't really an expected way for consume() to fail, either:
    // - map iteration failed: getFirstKey() & getNextKey(K) with something besides ENOENT
    // - readValue(K) / deleteValue(K) failed for a K that was just in the map...
    // but the map is inactive and noone should be modifying it...
    //
    // Even if it does somehow fail, whatever we did retrieve is valid data,
    // hence this return code is informational only (mostly for tests)
    if (!res.ok()) {
        ALOGE("failed to consume per uid Stats map for detail traffic stats: %s",
              strerror(res.error().code()));
        return -res.error().code();
    }
    return 0;
}

int parseBpfNetworkStatsDetail(std::vector<stats_line>* lines) {
    static BpfMapRO<uint32_t, uint32_t> configurationMap(CONFIGURATION_MAP_PATH);
    static BpfMap<StatsKey, StatsValue> statsMapA(STATS_MAP_A_PATH);
    static BpfMap<StatsKey, StatsValue> statsMapB(STATS_MAP_B_PATH);
    auto configuration = configurationMap.readValue(CURRENT_STATS_MAP_CONFIGURATION_KEY);
    if (!configuration.ok()) {
        ALOGE("Cannot read the old configuration from map: %s",
              configuration.error().message().c_str());
        return -configuration.error().code();
    }
    // The target map for stats reading should be the inactive map, which is opposite
    // from the config value.
    BpfMap<StatsKey, StatsValue> *inactiveStatsMap;
    switch (configuration.value()) {
      case SELECT_MAP_A:
        inactiveStatsMap = &statsMapB;
        break;
      case SELECT_MAP_B:
        inactiveStatsMap = &statsMapA;
        break;
      default:
        ALOGE("%s unknown configuration value: %d", __func__, configuration.value());
        return -EINVAL;
    }

    // It is safe to read and clear the old map now since the
    // networkStatsFactory should call netd to swap the map in advance already.
    // TODO: the above comment feels like it may be obsolete / out of date,
    // since we no longer swap the map via netd binder rpc - though we do
    // still swap it.
    // Ignore errors and return what we can.
    (void)parseBpfNetworkStatsDetailInternal(*lines, *inactiveStatsMap, ifindex2name);
    return 0;
}

int parseBpfNetworkStatsDevInternal(std::vector<stats_line>& lines,
                                    const BpfMapRO<uint32_t, StatsValue>& statsMap,
                                    const IfIndexToNameFunc ifindex2name) {
    int64_t unknownIfaceBytesTotal = 0;
    Result<void> res = statsMap.forAll(
        [&lines, &unknownIfaceBytesTotal, ifindex2name, &statsMap](const uint32_t& key, const StatsValue& value) {
            Result<IfaceValue> ifname = ifindex2name(key);
            if (!ifname.ok()) {
                maybeLogUnknownIface(key, statsMap, key, &unknownIfaceBytesTotal);
                return;
            }
            StatsKey fakeKey = {
                .uid = (uint32_t)UID_ALL,
                .tag = (uint32_t)TAG_NONE,
                .counterSet = (uint32_t)SET_ALL,
            };
            lines.push_back(populateStatsEntry(fakeKey, value, ifname.value()));
        }
    );
    if (!res.ok()) {
        ALOGE("failed to iterate per uid Stats map for detail traffic stats: %s",
              strerror(res.error().code()));
        return -res.error().code();
    }

    groupNetworkStats(lines);
    return 0;
}

int parseBpfNetworkStatsDev(std::vector<stats_line>* lines) {
    return parseBpfNetworkStatsDevInternal(*lines, getIfaceStatsMap(), ifindex2name);
}

void groupNetworkStats(std::vector<stats_line>& lines) {
    if (lines.size() <= 1) return;
    std::sort(lines.begin(), lines.end());

    // Similar to std::unique(), but aggregates the duplicates rather than discarding them.
    size_t currentOutput = 0;
    for (size_t i = 1; i < lines.size(); i++) {
        // note that == operator only compares the 'key' portion: iface/uid/tag/set
        if (lines[currentOutput] == lines[i]) {
            // while += operator only affects the 'data' portion: {rx,tx}{Bytes,Packets}
            lines[currentOutput] += lines[i];
        } else {
            // okay, we're done aggregating the current line, move to the next one
            lines[++currentOutput] = lines[i];
        }
    }

    // possibly shrink the vector - currentOutput is the last line with valid data
    lines.resize(currentOutput + 1);
}

// True if lhs equals to rhs, only compare iface, uid, tag and set.
bool operator==(const stats_line& lhs, const stats_line& rhs) {
    return ((lhs.uid == rhs.uid) && (lhs.tag == rhs.tag) && (lhs.set == rhs.set) &&
            !strncmp(lhs.iface, rhs.iface, sizeof(lhs.iface)));
}

// True if lhs is smaller than rhs, only compare iface, uid, tag and set.
bool operator<(const stats_line& lhs, const stats_line& rhs) {
    int ret = strncmp(lhs.iface, rhs.iface, sizeof(lhs.iface));
    if (ret != 0) return ret < 0;
    if (lhs.uid < rhs.uid) return true;
    if (lhs.uid > rhs.uid) return false;
    if (lhs.tag < rhs.tag) return true;
    if (lhs.tag > rhs.tag) return false;
    if (lhs.set < rhs.set) return true;
    if (lhs.set > rhs.set) return false;
    return false;
}

stats_line& stats_line::operator=(const stats_line& rhs) {
    if (this == &rhs) return *this;

    strlcpy(iface, rhs.iface, sizeof(iface));
    uid = rhs.uid;
    set = rhs.set;
    tag = rhs.tag;
    rxPackets = rhs.rxPackets;
    txPackets = rhs.txPackets;
    rxBytes = rhs.rxBytes;
    txBytes = rhs.txBytes;
    return *this;
}

stats_line& stats_line::operator+=(const stats_line& rhs) {
    rxPackets += rhs.rxPackets;
    txPackets += rhs.txPackets;
    rxBytes += rhs.rxBytes;
    txBytes += rhs.txBytes;
    return *this;
}

}  // namespace bpf
}  // namespace android
