/*
 * Copyright (C) 2018-2024 The Android Open Source Project
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

#define LOG_TAG "NetBpfLoad"

#include <algorithm>
#include <arpa/inet.h>
#include <bpf/btf.h>
#include <bpf/libbpf.h>
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <error.h>
#include <fcntl.h>
#include <inttypes.h>
#include <iostream>
#include <linux/unistd.h>
#include <log/log.h>
#include <net/if.h>
#include <optional>
#include <span>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <sysexits.h>
#include <unistd.h>
#include <unordered_map>
#include <vector>

#include <android-base/cmsg.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/macros.h>
#include <android-base/properties.h>
#include <android-base/scopeguard.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <android/api-level.h>

#include <com_android_tethering_readonly_flags.h>

#define BPF_UTILS_MORE_IS_FOO_HELPERS
#include "BpfSyscallWrappers.h"
#include "bpf/BpfUtils.h"
#include "bpf_map_def.h"

using android::base::borrowed_fd;
using android::base::GetIntProperty;
using android::base::GetProperty;
using android::base::InitLogging;
using android::base::KernelLogger;
using android::base::SetProperty;
using android::base::Split;
using android::base::unique_fd;
using std::optional;
using std::span;
using std::string;
using std::vector;
using std::chrono::duration_cast;
using std::chrono::milliseconds;
using std::chrono::steady_clock;

namespace android {
namespace bpf {

// This verifies the macro and the C++ api are in sync, and that they're compile time known.
constexpr auto useLibBpf = COM_ANDROID_TETHERING_READONLY_FLAGS_USE_LIBBPF;
static_assert(std::is_same<decltype(useLibBpf), const bool>::value, "useLibBpf must be a boolean.");
static_assert(useLibBpf == com::android::tethering::readonly::flags::use_libbpf(), "useLibBpf flag mismatch");

// Due to support for RiscV not yet having been released,
// there is no minimum supported api level for it.
//
// Thus in //build/soong/cc/api_level.go
//   MinApiForArch(riscv64)
// will return FutureApiLevel which results in
// __ANDROID_API__ == __ANDROID_MIN_SDK_VERSION__ == 10000
//
// Normally __ANDROID_API__ is 'min_sdk_version = 31' from Android.bp
#if defined(__riscv)
static_assert(__ANDROID_API__ == 10000, "TODO: add proper mainline riscv support");
#else
static_assert(__ANDROID_API__ == 31, "NetBpfLoad must be compiled for 31/S");
#endif

#ifndef __ANDROID_APEX__
#error "NetBpfLoad must be compiled into the Tethering Mainline Module APEX"
#endif

// We want to be able to run time enforce minimum kernel versions,
// which means we can't just assume the minimum applies,
// for this reason the value of 'minSupportedKernelVer'
// should be 0 for APEX/mainline builds.
static_assert(!minSupportedKernelVer, "NetBpfLoad must not assume min kver");

static unsigned int page_size = static_cast<unsigned int>(getpagesize());

typedef struct {
    const char* program_name;
    vector<char> data;
    span<const Elf64_Rel> rel_data;
    optional<struct bpf_prog_def> prog_def;

    unique_fd prog_fd; // fd after loading
} codeSection;

class ElfObject {
    void* base;
    size_t size;
    const Elf64_Ehdr* eh;
    span<const char> bytes;
    span<const Elf64_Shdr> sh;
    span<const char> strtab;
    span<const Elf64_Sym> symtab;
    vector<Elf64_Sym> sortedSymtab;

  public:
    const char * const path;

    ElfObject(const char* elfPath) : base(MAP_FAILED), size(0), eh(nullptr), path(elfPath) {
        unique_fd fd(open(path, O_RDONLY | O_CLOEXEC));
        if (fd < 0) {
            ALOGE("open(%s) failed: %s", path, strerror(errno));
            abort();
        }
        struct stat st;
        if (fstat(fd, &st) < 0) {
            ALOGE("fstat(%s) failed: %s", path, strerror(errno));
            abort();
        }

        static const dev_t self_dev = []() {
            struct stat self_st;
            if (stat("/proc/self/exe", &self_st) < 0) {
                ALOGE("stat(/proc/self/exe) failed: %s", strerror(errno));
                abort();
            }
            return self_st.st_dev;
        }();

        if (st.st_dev != self_dev) {
            ALOGE("file %s is on device %llu, while we are on device %llu",
                  path, (unsigned long long)st.st_dev, (unsigned long long)self_dev);
            abort();
        }

        size = static_cast<size_t>(st.st_size);
        if (size < sizeof(Elf64_Ehdr)) {
            ALOGE("file %s too small: %zu", path, size);
            abort();
        }
        base = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (base == MAP_FAILED) {
            ALOGE("mmap(%s) failed: %s", path, strerror(errno));
            abort();
        }
        eh = (const Elf64_Ehdr*)base;
        bytes = {(const char*)base, size};

        if (eh->e_shoff + eh->e_shnum * eh->e_shentsize > size) {
            ALOGE("file %s shdr table beyond file size", path);
            abort();
        }
        if (eh->e_shentsize != sizeof(Elf64_Shdr)) {
            ALOGE("file %s shdr entry size mismatch", path);
            abort();
        }
        sh = {(const Elf64_Shdr*)((char*)base + eh->e_shoff), eh->e_shnum};
        strtab = getSectionByIdx(eh->e_shstrndx);
        if (readSectionByType(SHT_SYMTAB, symtab)) {
            ALOGE("file %s symtab not found", path);
            abort();
        }
        sortedSymtab.assign(symtab.begin(), symtab.end());
        std::sort(sortedSymtab.begin(), sortedSymtab.end(), symCompare);
    }

    ~ElfObject() {
        if (base != MAP_FAILED) munmap(base, size);
    }

    const Elf64_Ehdr & EH() const { return *eh; }

    auto SHsize() const { return sh.size(); }

    // UB if idx > SHsize()
    const Elf64_Shdr & SH(unsigned idx) const { return sh[idx]; }

    // Get a span pointing to a section by its index
    template <typename T = char>
    span<const T> getSectionByIdx(unsigned id) const {
        if (id >= sh.size()) return {};
        const auto& s = sh[id];
        if (s.sh_offset > size || s.sh_size > size - s.sh_offset) return {};
        if (s.sh_size % sizeof(T)) abort();
        return {reinterpret_cast<const T*>(bytes.data() + s.sh_offset),
                static_cast<size_t>(s.sh_size / sizeof(T))};
    }

    // Get string from offset in strtab.
    // This is safe because ELF string tables are null-terminated, and we have
    // verified above that the ELF file is a trusted file from our own filesystem.
    const char* getStr(unsigned off) const {
        if (off >= strtab.size()) return nullptr;
        return strtab.data() + off;
    }

    // Reads a full section by name - example to get the GPL license
    template <typename T>
    int readSectionByName(const char* name, span<const T>& data) const {
        for (unsigned i = 0; i < sh.size(); i++) {
            const char* secname = getStr(sh[i].sh_name);
            if (!secname) continue;

            if (!strcmp(secname, name)) {
                data = getSectionByIdx<T>(i);
                if (data.empty() && sh[i].sh_size > 0) return -1;
                return 0;
            }
        }
        return -2;
    }

    template <typename T>
    int readSectionByType(unsigned type, span<const T>& data) const {
        for (unsigned i = 0; i < sh.size(); i++) {
            if (sh[i].sh_type != type) continue;

            data = getSectionByIdx<T>(i);
            if (data.empty() && sh[i].sh_size > 0) return -1;
            return 0;
        }
        return -2;
    }

    static bool symCompare(Elf64_Sym a, Elf64_Sym b) {
        return (a.st_value < b.st_value);
    }

    int getSectionSymNames(const char* const sectionName, vector<const char*>& names,
                           optional<unsigned> symbolType = std::nullopt) const {
        // Get index of section
        int sec_idx = -1;
        for (unsigned i = 0; i < sh.size(); i++) {
            const char* name = getStr(sh[i].sh_name);
            if (!name) return -1;

            if (!strcmp(sectionName, name)) {
                sec_idx = i;
                break;
            }
        }

        // No section found with matching name
        if (sec_idx == -1) {
            ALOGW("No %s section could be found in elf object", sectionName);
            return -1;
        }

        for (unsigned i = 0; i < sortedSymtab.size(); i++) {
            if (symbolType.has_value() && ELF_ST_TYPE(sortedSymtab[i].st_info) != symbolType) continue;

            if (sortedSymtab[i].st_shndx == sec_idx) {
                const char* s = getStr(sortedSymtab[i].st_name);
                if (!s) return -1;
                names.push_back(s);
            }
        }

        return 0;
    }

    const char* getSymNameByIdx(unsigned index) const {
        if (index >= symtab.size()) return nullptr;
        return getStr(symtab[index].st_name);
    }

    // sym.st_value is an Elf64_Addr (u64), however we don't need to support huge ELF objects
    int getSymOffsetByName(const char *name) const {
        for (const auto& sym : symtab) {
            const char* s = getStr(sym.st_name);
            if (!s) continue;
            if (!strcmp(s, name)) return sym.st_value;
        }
        return -1;  // not found
    }
};

// Read a section by its index - for ex to get sec hdr strtab blob
int readCodeSections(const ElfObject& elfObj, vector<codeSection>& cs) {
    int entries = elfObj.SHsize();
    int ret = 0;

    span<const struct bpf_prog_def> pd;
    ret = elfObj.readSectionByName(".android_progs", pd);
    if (ret) return ret;
    vector<const char*> progDefNames;
    ret = elfObj.getSectionSymNames(".android_progs", progDefNames);
    if (!pd.empty() && ret) return ret;

    for (int i = 0; i < entries; i++) {
        const char* const name = elfObj.getStr(elfObj.SH(i).sh_name);
        if (!name) return -1;

        // all we want to process is sections FOO/BAR, but:
        // - section 0 has an empty name (experimentally observed)
        // - .relFOO/BAR would break us later (relocations)
        // - 'license' is special, but doesn't have a /
        if (name[0] == '.') continue;

        // Find the first slash
        const char* first_slash = strchr(name, '/');

        // Ignore sections without a /  (basically 'license' section)
        if (!first_slash) continue;

        string sanitizedName = name;
        sanitizedName[first_slash - name] = '_';

        if (strchr(sanitizedName.c_str(), '/')) abort(); // There should only be one!

        auto s = elfObj.getSectionByIdx(i);
        if (s.empty() && elfObj.SH(i).sh_size > 0) return -1;
        codeSection cs_temp;
        cs_temp.data.assign(s.begin(), s.end());
        ALOGV("Loaded code section %d (%s)", i, sanitizedName.c_str());

        vector<const char*> csSymNames;
        ret = elfObj.getSectionSymNames(name, csSymNames, STT_FUNC);
        if (ret || !csSymNames.size()) return ret;
        cs_temp.program_name = csSymNames[0];
        string prog_def_name = string(cs_temp.program_name) + "_def";
        for (size_t j = 0; j < progDefNames.size(); ++j) {
            if (prog_def_name == progDefNames[j]) {
                cs_temp.prog_def = pd[j];
                break;
            }
        }

        if (!cs_temp.prog_def) abort();

        string relname = string(".rel") + name;

        // Check for rel section
        if (cs_temp.data.size() > 0 && i + 1 < entries) {
            const char* next_name = elfObj.getStr(elfObj.SH(i + 1).sh_name);
            if (!next_name) return -1;

            if (!strcmp(next_name, relname.c_str())) {
                cs_temp.rel_data = elfObj.getSectionByIdx<Elf64_Rel>(i + 1);
                if (cs_temp.rel_data.empty() && elfObj.SH(i + 1).sh_size > 0) return -1;
                ALOGV("Loaded relo section %d (%s)", i, next_name);
            }
        }

        if (cs_temp.data.size() > 0) {
            cs.push_back(std::move(cs_temp));
            ALOGV("Adding section %d to cs list", i);
        }
    }
    return 0;
}

static unsigned int sanitizeMapFlags(unsigned int map_flags) {
    if (!isAtLeastKernelVersion(5, 10)) map_flags &= ~BPF_F_MMAPABLE;
    return map_flags;
}

static bool mapMatchesExpectations(const unique_fd& fd,
                                   const struct bpf_map_def& mapDef, const enum bpf_map_type type) {
    // bpfGetFd... family of functions require at minimum a 4.14 kernel,
    // so on 4.9-T kernels just pretend the map matches our expectations.
    // Additionally we'll get almost equivalent test coverage on newer devices/kernels.
    // This is because the primary failure mode we're trying to detect here
    // is either a source code misconfiguration (which is likely kernel independent)
    // or a newly introduced kernel feature/bug (which is unlikely to get backported to 4.9).
    if (!isAtLeastKernelVersion(4, 14)) return true;

    // Assuming fd is a valid Bpf Map file descriptor then
    // all the following should always succeed on a 4.14+ kernel.
    // If they somehow do fail, they'll return -1 (and set errno),
    // which should then cause (among others) a key_size mismatch.
    int fd_type = bpfGetFdMapType(fd);
    int fd_key_size = bpfGetFdKeySize(fd);
    int fd_value_size = bpfGetFdValueSize(fd);
    int fd_max_entries = bpfGetFdMaxEntries(fd);
    int fd_map_flags = bpfGetFdMapFlags(fd);

    // DEVMAPs are readonly from the bpf program side's point of view, as such
    // the kernel in kernel/bpf/devmap.c dev_map_init_map() will set the flag
    unsigned int desired_map_flags = sanitizeMapFlags(mapDef.map_flags);
    if (type == BPF_MAP_TYPE_DEVMAP || type == BPF_MAP_TYPE_DEVMAP_HASH)
        desired_map_flags |= BPF_F_RDONLY_PROG;

    // The .h file enforces that this is a power of two, and page size will
    // also always be a power of two, so this logic is actually enough to
    // force it to be a multiple of the page size, as required by the kernel.
    unsigned int desired_max_entries = mapDef.max_entries;
    if (type == BPF_MAP_TYPE_RINGBUF) {
        if (desired_max_entries < page_size) desired_max_entries = page_size;
    }

    // The following checks should *never* trigger, if one of them somehow does,
    // it probably means a bpf .o file has been changed/replaced at runtime
    // and bpfloader was manually rerun (normally it should only run *once*
    // early during the boot process).
    // Another possibility is that something is misconfigured in the code:
    // most likely a shared map is declared twice differently.
    // But such a change should never be checked into the source tree...
    if ((fd_type == type) &&
        (fd_key_size == (int)mapDef.key_size) &&
        (fd_value_size == (int)mapDef.value_size) &&
        (fd_max_entries == (int)desired_max_entries) &&
        (fd_map_flags == (int)desired_map_flags)) {
        return true;
    }

    ALOGE("bpf map name %s mismatch: desired/found (errno: %d): "
          "type:%d/%d key:%u/%d value:%u/%d entries:%u/%d flags:%u/%d",
          mapDef.name(), errno, type, fd_type, mapDef.key_size, fd_key_size,
          mapDef.value_size, fd_value_size, mapDef.max_entries, fd_max_entries,
          desired_map_flags, fd_map_flags);
    return false;
}

static int setBtfDatasecSize(const ElfObject &elfObj, struct btf *btf,
                             struct btf_type *bt) {
    const char *name = btf__name_by_offset(btf, bt->name_off);
    if (!name) {
        ALOGE("Couldn't resolve section name, errno: %d", errno);
        return -errno;
    }

    span<const char> data;
    int ret = elfObj.readSectionByName(name, data);
    if (ret) {
        ALOGE("Couldn't read section %s, ret: %d", name, ret);
        return ret;
    }
    bt->size = data.size();
    return 0;
}

static int setBtfVarOffset(const ElfObject &elfObj, struct btf *btf,
                           struct btf_type *datasecBt) {
    int i, vars = btf_vlen(datasecBt);
    struct btf_var_secinfo *vsi;
    const char *datasecName = btf__name_by_offset(btf, datasecBt->name_off);
    if (!datasecName) {
        ALOGE("Couldn't resolve section name, errno: %d", errno);
        return -errno;
    }

    for (i = 0, vsi = btf_var_secinfos(datasecBt); i < vars; i++, vsi++) {
        const struct btf_type *varBt = btf__type_by_id(btf, vsi->type);
        if (!varBt || !btf_is_var(varBt)) {
            ALOGE("Found non VAR kind btf_type, section: %s id: %u", datasecName,
                  vsi->type);
            return -1;
        }

        const struct btf_var *var = btf_var(varBt);
        if (var->linkage == BTF_VAR_STATIC) continue;

        const char *varName = btf__name_by_offset(btf, varBt->name_off);
        if (!varName) {
            ALOGE("Failed to resolve var name, section: %s", datasecName);
            return -1;
        }

        vsi->offset = elfObj.getSymOffsetByName(varName);
        if (!~vsi->offset) {
            ALOGE("No offset found in symbol table, section: %s, var: %s",
                  datasecName, varName);
            return -1;
        }
    }
    return 0;
}

#define BTF_INFO_ENC(kind, kind_flag, vlen)                                    \
    ((!!(kind_flag) << 31) | ((kind) << 24) | ((vlen) & BTF_MAX_VLEN))
#define BTF_INT_ENC(encoding, bits_offset, nr_bits)                            \
    ((encoding) << 24 | (bits_offset) << 16 | (nr_bits))

static int sanitizeBtf(struct btf *btf) {
    for (unsigned int i = 1; i < btf__type_cnt(btf); ++i) {
        struct btf_type *bt = (struct btf_type *)btf__type_by_id(btf, i);

        // Replace BTF_KIND_VAR (5.2+) with BTF_KIND_INT (4.18+)
        if (btf_is_var(bt)) {
            bt->info = BTF_INFO_ENC(BTF_KIND_INT, 0, 0);
            // using size = 1 is the safest choice, 4 will be too
            // big and cause kernel BTF validation failure if
            // original variable took less than 4 bytes
            bt->size = 1;
            *(int *)(bt + 1) = BTF_INT_ENC(0, 0, 8);
            continue;
        }

        // Replace BTF_KIND_FUNC_PROTO (5.0+) with BTF_KIND_ENUM (4.18+)
        if (btf_is_func_proto(bt)) {
            int vlen = btf_vlen(bt);
            bt->info = BTF_INFO_ENC(BTF_KIND_ENUM, 0, vlen);
            bt->size = sizeof(__u32); // kernel enforced
            continue;
        }

        // Replace BTF_KIND_FUNC (5.0+) with BTF_KIND_TYPEDEF (4.18+)
        if (btf_is_func(bt)) {
            bt->info = BTF_INFO_ENC(BTF_KIND_TYPEDEF, 0, 0);
            continue;
        }

        // Replace BTF_KIND_DATASEC (5.2+) with BTF_KIND_STRUCT (4.18+)
        if (btf_is_datasec(bt)) {
            const struct btf_var_secinfo *v = btf_var_secinfos(bt);
            struct btf_member *m = btf_members(bt);
            char *name;

            name = (char *)btf__name_by_offset(btf, bt->name_off);
            while (*name) {
                if (*name == '.' || *name == '?') *name = '_';
                name++;
            }

            int vlen = btf_vlen(bt);
            bt->info = BTF_INFO_ENC(BTF_KIND_STRUCT, 0, vlen);
            for (int j = 0; j < vlen; j++, v++, m++) {
                // order of field assignments is important
                m->offset = v->offset * 8;
                m->type = v->type;
                // preserve variable name as member name
                const struct btf_type *vt = btf__type_by_id(btf, v->type);
                m->name_off = vt->name_off;
            }
        }
    }
    return 0;
}

static int loadBtf(const ElfObject &elfObj, struct btf *btf) {
    int ret;
    for (unsigned int i = 1; i < btf__type_cnt(btf); ++i) {
        struct btf_type *bt = (struct btf_type *)btf__type_by_id(btf, i);
        if (!btf_is_datasec(bt)) continue;
        ret = setBtfDatasecSize(elfObj, btf, bt);
        if (ret) return ret;
        ret = setBtfVarOffset(elfObj, btf, bt);
        if (ret) return ret;
    }

    if (!isAtLeastKernelVersion(5, 10)) {
        // Likely unnecessary on kernel 5.4 but untested.
        sanitizeBtf(btf);
    }

    ret = btf__load_into_kernel(btf);
    if (ret) {
        if (errno != EINVAL) {
            ALOGE("btf__load_into_kernel failed, errno: %d", errno);
            return ret;
        };
        // For BTF_KIND_FUNC, newer kernels can read the BTF_INFO_VLEN bits of
        // struct btf_type to distinguish static vs. global vs. extern
        // functions, but older kernels enforce that only the BTF_INFO_KIND bits
        // can be set. Retry with non-BTF_INFO_KIND bits zeroed out to handle
        // this case.
        for (unsigned int i = 1; i < btf__type_cnt(btf); ++i) {
            struct btf_type *bt = (struct btf_type *)btf__type_by_id(btf, i);
            if (btf_is_func(bt)) {
                bt->info = (BTF_INFO_KIND(bt->info)) << 24;
            }
        }
        ret = btf__load_into_kernel(btf);
        if (ret) {
            ALOGE("btf__load_into_kernel retry failed, errno: %d", errno);
            return ret;
        };
    }
    return 0;
}

int getKeyValueTids(const struct btf *btf, const char *mapName,
                    uint32_t expectedKeySize, uint32_t expectedValueSize,
                    uint32_t *keyTypeId, uint32_t *valueTypeId) {
    const struct btf_type *kvBt;
    const struct btf_member *key, *value;
    const size_t max_name = 256;
    char kvTypeName[max_name];
    int64_t keySize, valueSize;
    int32_t kvId;

    if (snprintf(kvTypeName, max_name, "____btf_map_%s", mapName) == max_name) {
        ALOGE("____btf_map_%s is too long", mapName);
        return -1;
    }

    kvId = btf__find_by_name(btf, kvTypeName);
    if (kvId < 0) {
        ALOGE("section not found, map: %s typeName: %s", mapName, kvTypeName);
        return -1;
    }

    kvBt = btf__type_by_id(btf, kvId);
    if (!kvBt) {
        ALOGE("Couldn't find BTF type, map: %s id: %d", mapName, kvId);
        return -1;
    }

    if (!btf_is_struct(kvBt) || btf_vlen(kvBt) < 2) {
        ALOGE("Non Struct kind or invalid vlen, map: %s id: %d", mapName, kvId);
        return -1;
    }

    key = btf_members(kvBt);
    value = key + 1;

    keySize = btf__resolve_size(btf, key->type);
    if (keySize < 0) {
        ALOGE("Couldn't get key size, map: %s errno: %d", mapName, errno);
        return -1;
    }

    valueSize = btf__resolve_size(btf, value->type);
    if (valueSize < 0) {
        ALOGE("Couldn't get value size, map: %s errno: %d", mapName, errno);
        return -1;
    }

    if (expectedKeySize != keySize || expectedValueSize != valueSize) {
        ALOGE("Key value size mismatch, map: %s"
              " key size: %" PRId64 " expected key size: %u"
              " value size: %" PRId64 " expected value size: %u",
              mapName, keySize, expectedKeySize, valueSize, expectedValueSize);
        return -1;
    }

    *keyTypeId = key->type;
    *valueTypeId = value->type;

    return 0;
}

static bool isBtfSupported(enum bpf_map_type type) {
    return type != BPF_MAP_TYPE_DEVMAP_HASH && type != BPF_MAP_TYPE_RINGBUF;
}

// Duplicates and stores the kernel_stats_map fd during BPF object loading.
// Entries are written to this map once the object is loaded.
// Note: We cannot reopen this BPF map later because of sepolicy limitations.
static unique_fd bpfKernelStatsMapFd;

static int pinMap(const borrowed_fd& fd, const struct bpf_map_def& mapDef) {
        int ret;
        if (mapDef.create_location[0]) {
            ret = bpfFdPin(fd, mapDef.create_location);
            if (ret) {
                const int err = errno;
                ALOGE("create %s -> %d [%d:%s]", mapDef.create_location, ret, err, strerror(err));
                return -err;
            }
            ret = renameat2(AT_FDCWD, mapDef.create_location,
                            AT_FDCWD, mapDef.pin_location, RENAME_NOREPLACE);
            if (ret) {
                const int err = errno;
                ALOGE("rename %s %s -> %d [%d:%s]", mapDef.create_location, mapDef.pin_location, ret,
                      err, strerror(err));
                return -err;
            }
        } else {
            ret = bpfFdPin(fd, mapDef.pin_location);
            if (ret) {
                const int err = errno;
                ALOGE("pin %s -> %d [%d:%s]", mapDef.pin_location, ret, err, strerror(err));
                return -err;
            }
        }
        ret = chmod(mapDef.pin_location, mapDef.mode);
        if (ret) {
            const int err = errno;
            ALOGE("chmod(%s, 0%o) = %d [%d:%s]", mapDef.pin_location, mapDef.mode, ret, err,
                  strerror(err));
            return -err;
        }
        ret = chown(mapDef.pin_location, (uid_t)mapDef.uid, (gid_t)mapDef.gid);
        if (ret) {
            const int err = errno;
            ALOGE("chown(%s, %u, %u) = %d [%d:%s]", mapDef.pin_location, mapDef.uid, mapDef.gid,
                  ret, err, strerror(err));
            return -err;
        }

        if (!strcmp(mapDef.pin_location, "/sys/fs/bpf/tethering/map_test_kernel_stats_map")) {
            bpfKernelStatsMapFd = unique_fd(fcntl(fd.get(), F_DUPFD_CLOEXEC, 0));
            if (!bpfKernelStatsMapFd.ok()) {
                const int err = errno;
                ALOGE("fcntl(%d, F_DUPFD_CLOEXEC, 0) failed [%d:%s]", fd.get(), err, strerror(err));
                return -err;
            }
        }

        if (isAtLeastKernelVersion(4, 14)) {
            int mapId = bpfGetFdMapId(fd);
            if (mapId == -1) {
                const int err = errno;
                ALOGE("bpfGetFdMapId failed, errno: %d", err);
                return -err;
            }
            if (!isUser) ALOGD("map %s id %d", mapDef.pin_location, mapId);
        }
        return 0;
}

static bool isMapTypeSupported(enum bpf_map_type type) {
    if (type == BPF_MAP_TYPE_LPM_TRIE && !isAtLeastKernelVersion(4, 14)) {
        // On Linux Kernels older than 4.14 this map type doesn't exist - autoskip.
        return false;
    }
    return true;
}

static enum bpf_map_type sanitizeMapType(enum bpf_map_type type) {
    if (type == BPF_MAP_TYPE_DEVMAP && !isAtLeastKernelVersion(4, 14)) {
        // On Linux Kernels older than 4.14 this map type doesn't exist, but it can kind
        // of be approximated: ARRAY has the same userspace api, though it is not usable
        // by the same ebpf programs.  However, that's okay because the bpf_redirect_map()
        // helper doesn't exist on 4.9-T anyway (so the bpf program would fail to load,
        // and thus needs to be tagged as 4.14+ either way), so there's nothing useful you
        // could do with a DEVMAP anyway (that isn't already provided by an ARRAY)...
        // Hence using an ARRAY instead of a DEVMAP simply makes life easier for userspace.
        return BPF_MAP_TYPE_ARRAY;
    }
    if (type == BPF_MAP_TYPE_DEVMAP_HASH && !isAtLeastKernelVersion(5, 4)) {
        // On Linux Kernels older than 5.4 this map type doesn't exist, but it can kind
        // of be approximated: HASH has the same userspace visible api.
        // However it cannot be used by ebpf programs in the same way.
        // Since bpf_redirect_map() only requires 4.14, a program using a DEVMAP_HASH map
        // would fail to load (due to trying to redirect to a HASH instead of DEVMAP_HASH).
        // One must thus tag any BPF_MAP_TYPE_DEVMAP_HASH + bpf_redirect_map() using
        // programs as being 5.4+...
        return  BPF_MAP_TYPE_HASH;
    }
    // No sanitization is required.
    return type;
}

static int createMaps(const ElfObject& elfObj, const span<const struct bpf_map_def> md,
                      vector<unique_fd>& mapFds) {
    int ret = 0;
    span<const char> btfData;
    struct btf *btf = NULL;
    auto btfGuard = base::make_scope_guard([&btf] { if (btf) btf__free(btf); });
    if (isAtLeastKernelVersion(4, 19)) {
        // On Linux Kernels older than 4.18 BPF_BTF_LOAD command doesn't exist.
        ret = elfObj.readSectionByName(".BTF", btfData);
        if (ret) {
            ALOGE("Failed to read .BTF section, ret:%d", ret);
            return ret;
        }
        btf = btf__new(btfData.data(), btfData.size());
        if (btf == NULL) {
            ALOGE("btf__new failed, errno: %d", errno);
            return -errno;
        }

        ret = loadBtf(elfObj, btf);
        if (ret) return ret;
    }

    for (unsigned i = 0; i < md.size(); i++) {
        if (api_level_full < md[i].min_api_level_full) {
            ALOGD("skipping map %s which requires api >= %d", md[i].name(),
                  md[i].min_api_level_full);
            mapFds.push_back(unique_fd());
            continue;
        }

        if (api_level_full >= md[i].max_api_level_full) {
            ALOGD("skipping map %s which requires api < %d", md[i].name(),
                  md[i].max_api_level_full);
            mapFds.push_back(unique_fd());
            continue;
        }

        if (kernelVer < md[i].min_kver) {
            ALOGD("skipping map %s which requires kernel version 0x%x >= 0x%x",
                  md[i].name(), kernelVer, md[i].min_kver);
            mapFds.push_back(unique_fd());
            continue;
        }

        if (kernelVer >= md[i].max_kver) {
            ALOGD("skipping map %s which requires kernel version 0x%x < 0x%x",
                  md[i].name(), kernelVer, md[i].max_kver);
            mapFds.push_back(unique_fd());
            continue;
        }

        if (!isMapTypeSupported(md[i].type)) {
            ALOGD("skipping unsupported map type(%d): %s", md[i].type, md[i].name());
            mapFds.push_back(unique_fd());
            continue;
        }
        enum bpf_map_type type = sanitizeMapType(md[i].type);

        // The .h file enforces that this is a power of two, and page size will
        // also always be a power of two, so this logic is actually enough to
        // force it to be a multiple of the page size, as required by the kernel.
        unsigned int max_entries = md[i].max_entries;
        if (type == BPF_MAP_TYPE_RINGBUF) {
            if (max_entries < page_size) max_entries = page_size;
        }

        unique_fd fd;
        int saved_errno;

        if (access(md[i].pin_location, F_OK) == 0) {
            fd.reset(mapRetrieveRO(md[i].pin_location));
            saved_errno = errno;
            ALOGD("bpf_create_map reusing map %s, ret: %d", md[i].name(), fd.get());
            abort();
        } else {
            union bpf_attr req = {
              .map_type = type,
              .key_size = md[i].key_size,
              .value_size = md[i].value_size,
              .max_entries = max_entries,
              .map_flags = sanitizeMapFlags(md[i].map_flags),
            };
            if (isAtLeastKernelVersion(4, 15))
                strlcpy(req.map_name, md[i].name(), sizeof(req.map_name));

            bool haveBtf = btf && isBtfSupported(type);
            if (haveBtf) {
                uint32_t kTid, vTid;
                ret = getKeyValueTids(btf, md[i].name(), md[i].key_size,
                                      md[i].value_size, &kTid, &vTid);
                if (ret) return ret;
                req.btf_fd = btf__fd(btf);
                req.btf_key_type_id = kTid;
                req.btf_value_type_id = vTid;
            }

            fd.reset(bpf(BPF_MAP_CREATE, req));
            saved_errno = errno;
            if (fd.ok()) {
                ALOGD("bpf_create_map[%s] btf:%d -> %d",
                      md[i].name(), haveBtf, fd.get());
            } else {
                ALOGE("bpf_create_map[%s] btf:%d -> %d errno:%d",
                      md[i].name(), haveBtf, fd.get(), saved_errno);
            }
        }

        if (!fd.ok()) return -saved_errno;

        // When reusing a pinned map, we need to check the map type/sizes/etc match, but for
        // safety (since reuse code path is rare) run these checks even if we just created it.
        // We assume failure is due to pinned map mismatch, hence the 'NOT UNIQUE' return code.
        if (!mapMatchesExpectations(fd, md[i], type)) return -ENOTUNIQ;

        ret = pinMap(fd, md[i]);
        if (ret) return ret;

        mapFds.push_back(std::move(fd));
    }

    return ret;
}

static void applyRelo(void* insnsPtr, Elf64_Addr offset, int fd) {
    int insnIndex;
    struct bpf_insn *insn, *insns;

    insns = (struct bpf_insn*)(insnsPtr);

    insnIndex = offset / sizeof(struct bpf_insn);
    insn = &insns[insnIndex];

    // Occasionally might be useful for relocation debugging, but pretty spammy
    if (0) {
        ALOGV("applying relo to instruction at byte offset: %llu, "
              "insn offset %d, insn %llx",
              (unsigned long long)offset, insnIndex, *(unsigned long long*)insn);
    }

    if (insn->code != (BPF_LD | BPF_IMM | BPF_DW)) {
        ALOGE("invalid relo for insn %d: code 0x%x", insnIndex, insn->code);
        return;
    }

    insn->imm = fd;
    insn->src_reg = BPF_PSEUDO_MAP_FD;
}

static void applyMapRelo(const ElfObject& elfObj, const span<const struct bpf_map_def> md,
                         vector<unique_fd> &mapFds, vector<codeSection>& cs) {
    for (unsigned k = 0; k < cs.size(); k++) {
        for (const auto& rel : cs[k].rel_data) {
            int symIndex = ELF64_R_SYM(rel.r_info);

            const char* symName = elfObj.getSymNameByIdx(symIndex);
            if (!symName) return;

            // Find the map fd and apply relo
            for (unsigned j = 0; j < md.size(); j++) {
                if (!strcmp(symName, md[j].name())) {
                    applyRelo(cs[k].data.data(), rel.r_offset, mapFds[j]);
                    break;
                }
            }
        }
    }
}

static int pinProg(const borrowed_fd& fd, const struct bpf_prog_def& progDef) {
    int ret;
    if (progDef.create_location[0]) {
        ret = bpfFdPin(fd, progDef.create_location);
        if (ret) {
            const int err = errno;
            ALOGE("create %s -> %d [%d:%s]", progDef.create_location, ret, err, strerror(err));
            return -err;
        }
        ret = renameat2(AT_FDCWD, progDef.create_location,
                        AT_FDCWD, progDef.pin_location, RENAME_NOREPLACE);
        if (ret) {
            const int err = errno;
            ALOGE("rename %s %s -> %d [%d:%s]", progDef.create_location, progDef.pin_location, ret,
                  err, strerror(err));
            return -err;
        }
    } else {
        ret = bpfFdPin(fd, progDef.pin_location);
        if (ret) {
            const int err = errno;
            ALOGE("create %s -> %d [%d:%s]", progDef.pin_location, ret, err, strerror(err));
            return -err;
        }
    }
    if (chmod(progDef.pin_location, 0440)) {
        const int err = errno;
        ALOGE("chmod %s 0440 -> [%d:%s]", progDef.pin_location, err, strerror(err));
        return -err;
    }
    if (chown(progDef.pin_location, (uid_t)progDef.uid,
              (gid_t)progDef.gid)) {
        const int err = errno;
        ALOGE("chown %s %u %u -> [%d:%s]", progDef.pin_location, progDef.uid,
              progDef.gid, err, strerror(err));
        return -err;
    }
    return 0;
}

static int validateProg(const borrowed_fd& fd, const char* const progPinLoc) {
    if (!isAtLeastKernelVersion(4, 14)) {
        return 0;
    }
    int progId = bpfGetFdProgId(fd);
    if (progId == -1) {
        const int err = errno;
        ALOGE("bpfGetFdProgId failed, errno: %d", err);
        return -err;
    }

    int jitLen = bpfGetFdJitProgLen(fd);
    if (jitLen == -1) {
        const int err = errno;
        ALOGE("bpfGetFdJitProgLen failed, ret: %d", err);
        return -err;
    }

    int xlatLen = bpfGetFdXlatProgLen(fd);
    if (xlatLen == -1) {
        const int err = errno;
        ALOGE("bpfGetFdXlatProgLen failed, ret: %d", err);
        return -err;
    }
    if (!isUser) ALOGD("prog %s id %d len jit:%d xlat:%d", progPinLoc, progId, jitLen, xlatLen);

    if (!jitLen && api_level_full >= NETBPFLOAD_25Q2_VER) {
        ALOGE("Kernel eBPF JIT failure for %s", progPinLoc);
        return -ENOTSUP;
    }
    return 0;
}

static enum bpf_attach_type fixup_attach(enum bpf_prog_type prog_type, enum bpf_attach_type expected_attach_type) {
    if (!isAtLeastKernelVersion(4, 19))
        if (prog_type == BPF_PROG_TYPE_CGROUP_SKB)
            if (expected_attach_type == BPF_CGROUP_INET_EGRESS)
                return BPF_CGROUP_INET_INGRESS; // aka BPF_PROG_ATTACH_TYPE_DEFAULT
    return expected_attach_type;
}

static int loadCodeSections(const ElfObject& elfObj, vector<codeSection>& cs,
                            const char* const license) {
    for (unsigned i = 0; i < cs.size(); i++) {
        unique_fd& fd = cs[i].prog_fd;
        int ret;

        if (!cs[i].prog_def.has_value()) {
            ALOGE("[%u] missing program definition! bad bpf.o build?", i);
            return -EINVAL;
        }

        ALOGD("cs[%u].name:%s kver in [%x,%x) api level in [%d,%d)",
              i, cs[i].prog_def->name(),
              cs[i].prog_def->min_kver, cs[i].prog_def->max_kver,
              cs[i].prog_def->min_api_level_full, cs[i].prog_def->max_api_level_full);

        if (kernelVer < cs[i].prog_def->min_kver) continue;
        if (kernelVer >= cs[i].prog_def->max_kver) continue;
        if (api_level_full < cs[i].prog_def->min_api_level_full) continue;
        if (api_level_full >= cs[i].prog_def->max_api_level_full) continue;

        bool reuse = false;
        if (access(cs[i].prog_def->pin_location, F_OK) == 0) {
            fd.reset(retrieveProgram(cs[i].prog_def->pin_location));
            ALOGD("New bpf prog load reusing prog %s, ret: %d (%s)", cs[i].prog_def->pin_location, fd.get(),
                  !fd.ok() ? std::strerror(errno) : "ok");
            reuse = true;
        } else {
            static char log_buf[1 << 20];  // 1 MiB logging buffer
            log_buf[0] = 0;

            union bpf_attr req = {
              .prog_type = cs[i].prog_def->type,
              .insn_cnt = static_cast<__u32>(cs[i].data.size() / sizeof(struct bpf_insn)),
              .insns = ptr_to_u64(cs[i].data.data()),
              .license = ptr_to_u64(license),
              .expected_attach_type = fixup_attach(cs[i].prog_def->type, cs[i].prog_def->attach_type),
            };
            if (isAtLeastKernelVersion(4, 15))
                strlcpy(req.prog_name, cs[i].prog_def->name(), sizeof(req.prog_name));

            // use a copy, so that bpf() system call cannot scribble over our req,
            // which we want to mutate (to enable logging) and reuse on failure
            const union bpf_attr req_copy = req;
            fd.reset(bpf(BPF_PROG_LOAD, req_copy));

            if (fd.ok()) {
                // on success, trivial logging
                ALOGD("BPF_PROG_LOAD call for %s (%s) returned fd: %d", elfObj.path,
                      cs[i].prog_def->name(), fd.get());
            } else {
                // on failure, try again with logging enabled
                req.log_level = 1;
                req.log_size = sizeof(log_buf);
                req.log_buf = ptr_to_u64(log_buf);
                fd.reset(bpf(BPF_PROG_LOAD, req));

                // Kernel should have NULL terminated the log buffer, but force it anyway for safety
                log_buf[sizeof(log_buf) - 1] = 0;

                // Strip out final newline if present
                int log_chars = strlen(log_buf);
                if (log_chars && log_buf[log_chars - 1] == '\n') log_buf[--log_chars] = 0;

                bool log_oneline = !strchr(log_buf, '\n');

                ALOGD("BPF_PROG_LOAD call for %s (%s) returned '%s' fd: %d (%s)", elfObj.path,
                      cs[i].prog_def->name(), log_oneline ? log_buf : "{multiline}",
                      fd.get(), !fd.ok() ? std::strerror(errno) : "ok");
            }

            if (!fd.ok()) {
                // kernel NULL terminates log_buf, so this checks for non-empty string
                if (log_buf[0] && !isUser) {
                    vector<string> lines = Split(log_buf, "\n");

                    ALOGW("BPF_PROG_LOAD - BEGIN log_buf contents:");
                    for (const auto& line : lines) ALOGW("%s", line.c_str());
                    ALOGW("BPF_PROG_LOAD - END log_buf contents.");
                }

                if (cs[i].prog_def->optional) {
                    ALOGW("failed program %s is marked optional - continuing...",
                          cs[i].prog_def->name());
                    continue;
                }
                ALOGE("non-optional program %s failed to load.", cs[i].prog_def->name());
            }
        }

        if (!fd.ok()) return fd.get();

        if (!reuse) {
            ret = pinProg(fd, cs[i].prog_def.value());
            if (ret) return ret;
        }
        ret = validateProg(fd, cs[i].prog_def->pin_location);
        if (ret) return ret;
    }

    return 0;
}

static int prepareLoadMaps(const struct bpf_object* obj, const span<const struct bpf_map_def> md) {
    for (unsigned i = 0; i < md.size(); i++) {
        struct bpf_map* m = bpf_object__find_map_by_name(obj, md[i].name());
        if (!m) {
            ALOGE("bpf_object does not contain map: %s", md[i].name());
            return -1;
        }

        if (api_level_full < md[i].min_api_level_full ||
            api_level_full >= md[i].max_api_level_full) {
            ALOGD("skipping map %s: api %d is outside required range [%d, %d)",
                  md[i].name(), api_level_full,
                  md[i].min_api_level_full, md[i].max_api_level_full);
            bpf_map__set_autocreate(m, false);
            continue;
        }

        if (kernelVer < md[i].min_kver || kernelVer >= md[i].max_kver) {
            ALOGD("skipping map %s: kernel version 0x%x is outside required range [0x%x, 0x%x)",
                  md[i].name(), kernelVer, md[i].min_kver, md[i].max_kver);
            bpf_map__set_autocreate(m, false);
            continue;
        }

        if (!isMapTypeSupported(md[i].type)) {
            ALOGD("skipping unsupported map type(%d): %s", md[i].type, md[i].name());
            bpf_map__set_autocreate(m, false);
            continue;
        }

        bpf_map__set_type(m, sanitizeMapType(md[i].type));
        bpf_map__set_map_flags(m, sanitizeMapFlags(md[i].map_flags));
    }
    return 0;
}

static int prepareLoadProgs(const struct bpf_object* obj, const vector<codeSection>& cs) {
    for (unsigned i = 0; i < cs.size(); i++) {
        if (!cs[i].prog_def.has_value()) {
            ALOGE("[%u] missing program definition! bad bpf.o build?", i);
            return -EINVAL;
        }
        struct bpf_program* prog = bpf_object__find_program_by_name(obj, cs[i].program_name);
        if (!prog) {
            ALOGE("bpf_object does not contain program: %s", cs[i].program_name);
            return -1;
        }

        unsigned min_kver = cs[i].prog_def->min_kver;
        unsigned max_kver = cs[i].prog_def->max_kver;
        if (kernelVer < min_kver || kernelVer >= max_kver) {
            ALOGD("skipping prog %s: kernel version 0x%x is outside required range [0x%x, 0x%x)",
                  cs[i].prog_def->name(), kernelVer, min_kver, max_kver);
            bpf_program__set_autoload(prog, false);
            continue;
        }

        if (api_level_full < cs[i].prog_def->min_api_level_full ||
            api_level_full >= cs[i].prog_def->max_api_level_full) {
            ALOGD("skipping prog %s: api %d is outside required range [%d, %d)",
                  cs[i].prog_def->name(), api_level_full,
                  cs[i].prog_def->min_api_level_full, cs[i].prog_def->max_api_level_full);
            bpf_program__set_autoload(prog, false);
            continue;
        }

        if (cs[i].prog_def->optional) {
            // TODO: Support optional program
            ALOGE("Optional program cannot be loaded by libbpf");
            return -1;
        }

        bpf_program__set_type(prog, cs[i].prog_def->type);
        bpf_program__set_expected_attach_type(prog, fixup_attach(cs[i].prog_def->type, cs[i].prog_def->attach_type));
    }
    return 0;
}

static int pinMaps(const struct bpf_object* obj, const span<const struct bpf_map_def> md) {
    for (unsigned i = 0; i < md.size(); i++) {
        struct bpf_map* m = bpf_object__find_map_by_name(obj, md[i].name());
        if (!m) {
            ALOGE("bpf_object does not contain map: %s", md[i].name());
            return -1;
        }
        // This map was skipped
        if (!bpf_map__autocreate(m)) continue;

        if (access(md[i].pin_location, F_OK) == 0) {
            ALOGE("Reusing map is not supported: %s", md[i].name());
            return -1;
        }

        int ret = pinMap(bpf_map__fd(m), md[i]);
        if (ret) return ret;
    }
    return 0;
}

static int pinProgs(const struct bpf_object * obj,
                    const vector<codeSection>& cs) {
    int ret;

    for (unsigned i = 0; i < cs.size(); i++) {
        struct bpf_program* prog = bpf_object__find_program_by_name(obj, cs[i].program_name);
        if (!prog) {
            ALOGE("bpf_object does not contain program: %s", cs[i].program_name);
            return -1;
        }
        // This program was skipped
        if (!bpf_program__autoload(prog)) continue;

        if (access(cs[i].prog_def->pin_location, F_OK) == 0) {
            // TODO: Skip loading lower priority program
            ALOGI("Higher priority program is already pinned, skip pinning %s", cs[i].prog_def->name());
            continue;
        }

        int fd = bpf_program__fd(prog);
        ret = pinProg(fd, cs[i].prog_def.value());
        if (ret) return ret;
        ret = validateProg(fd, cs[i].prog_def->pin_location);
        if (ret) return ret;
    }
    return 0;
}

static int loadProgByLibbpf(const char* const elfPath) {
    ElfObject elfObj(elfPath);
    span<const struct bpf_map_def> md;
    vector<codeSection> cs;
    int ret;

    LIBBPF_OPTS(bpf_object_open_opts, opts,
        .bpf_token_path = "",
    );
    struct bpf_object* obj = bpf_object__open_file(elfPath, &opts);
    if (!obj) return -1;
    auto objGuard = base::make_scope_guard([&obj] { bpf_object__close(obj); });

    ret = elfObj.readSectionByName(".android_maps", md);
    if (ret) return ret;

    ret = prepareLoadMaps(obj, md);
    if (ret) return ret;

    ret = readCodeSections(elfObj, cs);
    if (ret && ret != -ENOENT) return ret;

    ret = prepareLoadProgs(obj, cs);
    if (ret) return ret;

    ret = bpf_object__load(obj);
    if (ret) return ret;
    // On Linux Kernels older than 4.18 BPF_BTF_LOAD command doesn't exist.
    if (isAtLeastKernelVersion(4, 19) && bpf_object__btf_fd(obj) < 0) return -1;

    ret = pinMaps(obj, md);
    if (ret) return ret;

    ret = pinProgs(obj, cs);
    if (ret) return ret;

    return 0;
}

int loadProg(const char* const elfPath) {
    ElfObject elfObj(elfPath);
    span<const char> license;
    vector<codeSection> cs;
    span<const struct bpf_map_def> md;
    vector<unique_fd> mapFds;

    // Read license section - must exist and be NUL terminated.
    if (elfObj.readSectionByName("license", license)) abort();
    if (license.empty()) abort();
    if (license.data()[license.size() - 1]) abort();

    ALOGD("Processing ELF object %s (license %s)", elfPath, license.data());

    int ret = elfObj.readSectionByName(".android_maps", md);
    if (ret == -2) ret = 0; // -2 means there were no maps to read
    if (ret) return ret;

    ret = createMaps(elfObj, md, mapFds);
    if (ret) {
        ALOGE("Failed to create maps: (ret=%d) in %s", ret, elfPath);
        return ret;
    }

    for (unsigned i = 0; i < mapFds.size(); i++)
        ALOGV("map_fd found at %u is %d in %s", i, mapFds[i].get(), elfPath);

    ret = readCodeSections(elfObj, cs);
    if (ret == -ENOENT) return 0;
    if (ret) {
        ALOGE("Couldn't read all code sections in %s", elfPath);
        return ret;
    }

    applyMapRelo(elfObj, md, mapFds, cs);

    ret = loadCodeSections(elfObj, cs, license.data());
    if (ret) ALOGE("Failed to load programs, loadCodeSections ret=%d", ret);

    return ret;
}

static bool exists(const char* const path) {
    int v = access(path, F_OK);
    if (!v) return true;
    if (errno == ENOENT) return false;
    ALOGE("FATAL: access(%s, F_OK) -> %d [%d:%s]", path, v, errno, strerror(errno));
    abort();  // can only hit this if permissions (likely selinux) are screwed up
}

static bool loadObject(const char* const progPath, const bool useLibbpf = true) {
    if (useLibbpf ? loadProgByLibbpf(progPath) : loadProg(progPath)) {
        ALOGE("Failed to load object: %s, libbpf: %d", progPath, useLibbpf);
        return false;
    }
    ALOGD("Loaded object: %s, libbpf: %d", progPath, useLibbpf);
    return true;
}

#define APEXROOT "/apex/com.android.tethering"
#define BPFROOT APEXROOT "/etc/bpf/mainline/"

static bool loadAllObjects() {
    // Enable on kernels that have the BPF CFI backports, see: b/488034908
    // b/494690861: funcs may trigger Real-time Kernel Protection (RKP)
    bool funcs = isAtLeast26Q2 && isAtLeastKernelVersion(6, 6, 118);

    if (!loadObject(BPFROOT "offload.o", /*libbpf*/false)) return false;
    if (!loadObject(BPFROOT "test.o")) return false;
    if (!loadObject(BPFROOT "clatd.o")) return false;
    if (funcs) {
        if (!loadObject(BPFROOT "dscpPolicy@funcs.o")) return false;
        if (!loadObject(BPFROOT "netd@funcs.o")) return false;
    } else {
        if (!loadObject(BPFROOT "dscpPolicy.o")) return false;
        if (!loadObject(BPFROOT "netd.o")) return false;
    }
    return true;
}

static bool createDir(const char* const dir) {
    mode_t prevUmask = umask(0);

    if (mkdir(dir, S_ISVTX | S_IRWXU | S_IRWXG | S_IRWXO) && errno != EEXIST) {
        umask(prevUmask); // cannot fail
        ALOGE("Failed to create directory: %s, err: %s", dir, std::strerror(errno));
        return false;
    }

    umask(prevUmask);
    return true;
}

// Technically 'value' doesn't need to be newline terminated, but it's best
// to include a newline to match 'echo "value" > /proc/sys/...foo' behaviour,
// which is usually how kernel devs test the actual sysctl interfaces.
static bool writeFile(const char *filename, const char *value) {
    unique_fd fd(open(filename, O_WRONLY | O_CLOEXEC));
    if (fd < 0) {
        ALOGE("open('%s', O_WRONLY | O_CLOEXEC) -> %s", filename, strerror(errno));
        return false;
    }
    int len = strlen(value);
    int v = write(fd, value, len);
    if (v < 0) {
        ALOGE("write('%s', '%s', %d) -> %s", filename, value, len, strerror(errno));
        return false;
    }
    if (v != len) {
        ALOGE("write('%s', '%s', %d) -> short write [%d]", filename, value, len, v);
        return false;
    }
    return true;
}

const char * const platformBpfLoader = "/system/bin/bpfloader";
const char *const uprobestatsBpfLoader =
    "/apex/com.android.uprobestats/bin/uprobestatsbpfload";

static int logApexVersion(const char* const apex_pretty_name, const char* const apex_mount_point) {
    char src_apex[16] = {};
    if (!isUser) {
        // man readlink: Upon success, readlink() returns count of bytes placed in the buffer.
        // Otherwise, it shall return a value of -1, leave buffer unchanged, and set errno.
        int res = readlink("/system/etc/source_apex_version", src_apex, sizeof(src_apex) - 1);
        src_apex[res >= 0 ? res : 0] = 0; // forcibly NUL terminate, safe since sizeof-1 above
    }

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
        if (strcmp(mntpath, apex_mount_point)) continue;
        found_blockdev = strdup(blockdev);
        break;
    }
    fclose(f);
    f = NULL;

    if (!found_blockdev) return 2;
    ALOGV("Found %s Apex mounted from blockdev %s", apex_pretty_name, found_blockdev);

    f = fopen("/proc/mounts", "re");
    if (!f) { free(found_blockdev); return 3; }

    int apex_mount_point_len = strlen(apex_mount_point);

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
        if (strncmp(mntpath, apex_mount_point, apex_mount_point_len)) continue;
        char * at = mntpath + apex_mount_point_len;
        if (*at != '@') continue;
        char * ver = at + 1;
        if (isUser) {
            ALOGI("%s APEX version %s", apex_pretty_name, ver);
        } else {
            ALOGI("%s APEX version %s (system: %s)", apex_pretty_name, ver, src_apex);
        }
    }
    fclose(f);
    free(found_blockdev);
    return 0;
}

static int libbpfPrint(enum libbpf_print_level lvl, const char *const formatStr,
                       va_list argList) {
#ifndef NETBPFLOAD_VERBOSE_LOG
    if (lvl != LIBBPF_WARN) return 0;
#endif
    int32_t prio;
    switch (lvl) {
      case LIBBPF_WARN:
        prio = ANDROID_LOG_WARN;
        break;
      case LIBBPF_INFO:
        prio = ANDROID_LOG_INFO;
        break;
      case LIBBPF_DEBUG:
        prio = ANDROID_LOG_DEBUG;
        break;
    }
    if (!formatStr) {
        LOG_PRI(prio, LOG_TAG, "libbpf (null format string)");
        return 0;
    }

    // Print each line to avoid being truncated.
    char *s = NULL;
    int ret = vasprintf(&s, formatStr, argList);
    if (ret == -1) {
        LOG_PRI(prio, LOG_TAG, "libbpf (format failure)");
        return 0;
    }
    int len = strlen(s);
    if (len && s[len - 1] == '\n')
        s[len - 1] = 0;
    vector<string> lines = Split(s, "\n");
    for (const auto& line : lines) LOG_PRI(prio, LOG_TAG, "%s", line.c_str());
    free(s);
    return 0;
}

static int doLoad(char** argv, char * const envp[]) {
    libbpf_set_print(libbpfPrint);

    const bool runningAsRoot = !getuid();  // true iff U QPR3 or V+

    const int first_api_level = GetIntProperty("ro.board.first_api_level", api_level_full / 100);

    // last in U QPR2 beta1
    const bool has_platform_bpfloader_rc = exists("/system/etc/init/bpfloader.rc");
    // first in U QPR2 beta~2
    const bool has_platform_netbpfload_rc = exists("/system/etc/init/netbpfload.rc");

    ALOGI("%s api:%d/%d kver:%07x (%s:%uk) libbpf: v%u.%u uid:%u rc:%d%d user:%d%d%d",
          argv[0], android_get_device_api_level(), api_level_full,
          kernelVer, describeArch(), page_size >> 10,
          libbpf_major_version(), libbpf_minor_version(), getuid(), has_platform_bpfloader_rc,
          has_platform_netbpfload_rc, isUser, isUserdebug, isEng);

    if (!has_platform_bpfloader_rc && !has_platform_netbpfload_rc) {
        ALOGE("Unable to find platform's bpfloader & netbpfload init scripts.");
        return 1;
    }

    if (has_platform_bpfloader_rc && has_platform_netbpfload_rc) {
        ALOGE("Platform has *both* bpfloader & netbpfload init scripts.");
        return 1;
    }

    logApexVersion("Tethering", "/apex/com.android.tethering");

    if (exists("/apex/com.android.resolv/lib") ||
        exists("/apex/com.android.resolv/lib64")) {
        logApexVersion("DnsResolver", "/apex/com.android.resolv");
        ALOGE("Incorrect DNS Resolver APEX found.");
        return 2;
    }

    // both S and T require kernel 4.9 (and eBpf support)
    // (this also guarantees 'kernelVer' isn't an invalid uninitialized 0)
    if (!isAtLeastKernelVersion(4, 9)) {
        ALOGE("Android S & T require kernel 4.9.");
        return 3;
    }

    // U bumps the kernel requirement up to 4.14
    if (isAtLeastU && !isAtLeastKernelVersion(4, 14)) {
        ALOGE("Android U requires kernel 4.14.");
        return 4;
    }

    // V bumps the kernel requirement up to 4.19
    // see also: //system/netd/tests/kernel_test.cpp TestKernel419
    if (isAtLeastV && !isAtLeastKernelVersion(4, 19)) {
        ALOGE("Android V requires kernel 4.19.");
        return 5;
    }

    // 25Q2 bumps the kernel requirement up to 5.4
    // see also: //system/netd/tests/kernel_test.cpp TestKernel54
    if (isAtLeast25Q2 && !isAtLeastKernelVersion(5, 4)) {
        ALOGE("Android 25Q2 requires kernel 5.4.");
        return 6;
    }

    // 25Q4 bumps the kernel requirement up to 5.10
    // see also: //system/netd/tests/kernel_test.cpp TestKernel510
    if (isAtLeast25Q4 && !isAtLeastKernelVersion(5, 10)) {
        ALOGW("Android 25Q4 requires kernel 5.10.");
    }

    // 26Q4 bumps the kernel requirement up to 5.15
    if (isAtLeast26Q4 && !isAtLeastKernelVersion(5, 15)) {
        ALOGE("Android 26Q4 requires kernel 5.15.");
        return 7;
    }

    // Technically already required by U, but only enforce on V+
    // see also: //system/netd/tests/kernel_test.cpp TestKernel64Bit
    if (isAtLeastV && isKernel32Bit() && isAtLeastKernelVersion(5, 16)) {
        ALOGE("Android V+ platform with 32 bit kernel version >= 5.16.0 is unsupported");
        if (!isTV()) return 8;
    }

    if (isKernel32Bit() && isAtLeast25Q2) {
        ALOGE("Android 25Q2 requires 64 bit kernel.");
        return 9;
    }

    // 6.6 is highest version supported by Android V, so this is effectively W+ (sdk=36+)
    if (isKernel32Bit() && isAtLeastKernelVersion(6, 7)) {
        ALOGE("Android platform with 32 bit kernel version >= 6.7.0 is unsupported");
        return 10;
    }

    // Various known ABI layout issues, particularly wrt. bpf and ipsec/xfrm.
    if (isAtLeastV && isKernel32Bit() && isX86()) {
        ALOGE("Android V requires X86 kernel to be 64-bit.");
        if (!isTV()) return 11;
    }

    if (isAtLeastV) {
        bool bad = false;

        if (!isLtsKernel()) {
            ALOGW("Android V+ only supports LTS kernels.");
            bad = true;
        }

#define REQUIRE(maj, min, sub) \
        if (isKernelVersion(maj, min) && !isAtLeastKernelVersion(maj, min, sub)) { \
            ALOGW("Android V+ requires %d.%d kernel to be %d.%d.%d+.", maj, min, maj, min, sub); \
            bad = true; \
        }

        REQUIRE(4, 19, 236)
        REQUIRE(5, 4, 186)
        REQUIRE(5, 10, 199)
        REQUIRE(5, 15, 136)
        REQUIRE(6, 1, 57)
        REQUIRE(6, 6, 0)
        REQUIRE(6, 12, 0)
        REQUIRE(6, 18, 9)

#undef REQUIRE

        if (bad) {
            ALOGE("Unsupported kernel version (%07x).", kernelVer);
        }
    }

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
     * (for example "ANDROID: xfrm: remove in_compat_syscall() checks").
     * Note: this check/enforcement only applies to *system* userspace code,
     * it does not affect unprivileged apps, the 32-on-64 compatibility
     * problems are AFAIK limited to various CAP_NET_ADMIN protected interfaces.
     *
     * Additionally the 32-bit kernel jit support is poor,
     * and 32-bit userspace on 64-bit kernel bpf ringbuffer compatibility is broken.
     * Note, however, that TV and Wear devices will continue to support 32-bit userspace
     * on ARM64.
     */
    if (isUserspace32bit() && isAtLeastKernelVersion(6, 2)) {
        // Stuff won't work reliably, but...
        if (isArm() && (isTV() || isWear())) {
            // exempt Arm TV or Wear devices (arm32 ABI is far less problematic than x86-32)
            ALOGW("[Arm TV/Wear] 32-bit userspace unsupported on 6.2+ kernels.");
        } else if (first_api_level <= 33 /*T*/ && isArm()) {
            // also exempt Arm devices upgrading with major kernel rev from T-
            // might possibly be better for them to run with a newer kernel...
            ALOGW("[Arm KernelUpRev] 32-bit userspace unsupported on 6.2+ kernels.");
        } else if (isArm()) {
            ALOGE("[Arm] 64-bit userspace required on 6.2+ kernels (%d).", first_api_level);
            return 12;
        } else { // x86 since RiscV cannot be 32-bit
            ALOGE("[x86] 64-bit userspace required on 6.2+ kernels.");
            return 13;
        }
    }

    // Linux 6.12 was an LTS released at the end of 2024 (Nov 17),
    // and was first supported by Android 16 / 25Q2 (released in June 2025).
    // The next Linux LTS should be released near the end of 2025,
    // and will likely be 6.18.
    // Since officially Android only supports LTS, 6.13+ really means 6.18+,
    // and won't be supported before 2026, most likely Android 17 / 26Q2.
    // 6.13+ (implying 26Q2+) requires 64-bit userspace.
    if (isUserspace32bit() && isAtLeastKernelVersion(6, 13)) {
        // due to previous check only reachable on Arm && (<=T kernel uprev || TV || Wear)
        ALOGE("64-bit userspace required on 6.13+ kernels.");
        return 14;
    }

    if (isAtLeast25Q2) {
        FILE * f = fopen("/system/etc/init/netbpfload.rc", "re");
        if (!f) {
            ALOGE("failure opening /system/etc/init/netbpfload.rc");
            return 15;
        }
        int y = -1, q = -1, a = -1, b = -1, c = -1;
        int v = fscanf(f, "# %d %d %d %d %d #", &y, &q, &a, &b, &c);
        fclose(f);
        // y = year, q = quarter, a = major sdk, b = minor sdk
        int abc = a * 100 + b * 10 + c * 2;
        if ((api_level_full & ~1) == abc) {  // bottom bit means unreleased, ignore it
            ALOGI("detected %d of 5: %dQ%d api:%d.%d.%d=%d", v, y, q, a, b, c, abc);
        } else {
            // it did not match, presumably we upgraded due to apex version
            int yy = y;
            int qq = q + 1;
            if (qq == 5) { qq = 1; yy++; };
            ALOGI("parsed %d of 5: %dQ%d -> %dQ%d api:%d.%d.%d=%d -> %d",
                  v, y, q, yy, qq, a, b, c, abc, api_level_full & ~1);
        }
        if (v != 5) return 16;
        if (y < 2025 || y > 2099) return 17;
        if (q < 1 || q > 4) return 18;
        if (a < 36) return 19;
        if (b < 0 || b > 4) return 20;
        if (c < 0) return 21;
    }

    // Ensure we can determine the Android build type.
    if (!isEng && !isUser && !isUserdebug) {
        ALOGE("Failed to determine the build type.");
        return 22;
    }

    if (runningAsRoot) {
        // Note: writing this proc file requires being root (always the case on V+)

        // Linux 5.16-rc1 changed the default to 2 (disabled but changeable),
        // but we need 0 (enabled)
        // (this writeFile is known to fail on at least 4.19, but always defaults to 0 on
        // pre-5.13, on 5.13+ it depends on CONFIG_BPF_UNPRIV_DEFAULT_OFF)
        if (!writeFile("/proc/sys/kernel/unprivileged_bpf_disabled", "0\n") &&
            isAtLeastKernelVersion(5, 13)) return 23;
    }

    if (isAtLeastU) {
        // Note: writing these proc files requires CAP_NET_ADMIN
        // and sepolicy which is only present on U+,
        // on Android T and earlier versions they're written from the 'load_bpf_programs'
        // trigger (ie. by init itself) instead.

        // Enable the eBPF JIT -- but do note that on 64-bit kernels it is likely
        // already force enabled by the kernel config option BPF_JIT_ALWAYS_ON.
        // (Note: this (open) will fail with ENOENT 'No such file or directory' if
        //  kernel does not have CONFIG_BPF_JIT=y)
        // BPF_JIT is required by R VINTF (which means 4.14/4.19/5.4 kernels),
        // but 4.14/4.19 were released with P & Q, and only 5.4 is new in R+.
        if (!writeFile("/proc/sys/net/core/bpf_jit_enable", "1\n")) return 24;

        // Enable JIT kallsyms export for privileged users only
        // (Note: this (open) will fail with ENOENT 'No such file or directory' if
        //  kernel does not have CONFIG_HAVE_EBPF_JIT=y)
        if (!writeFile("/proc/sys/net/core/bpf_jit_kallsyms", "1\n")) return 25;
    }

    // Create all the pin subdirectories
    // (this must be done first to allow create_location and pin_subdir functionality,
    //  which could otherwise fail with ENOENT during object pinning or renaming,
    //  due to ordering issues)
    if (!createDir("/sys/fs/bpf/tethering")) return 26;
    // This is technically T+ but S also needs it for the 'mainline_done' file.
    if (!createDir("/sys/fs/bpf/netd_shared")) return 27;

    if (isAtLeastT) {
        if (!createDir("/sys/fs/bpf/netd_readonly")) return 28;
        if (!createDir("/sys/fs/bpf/net_shared")) return 29;
        if (!createDir("/sys/fs/bpf/net_private")) return 30;

        // This one is primarily meant for triggering genfscon rules.
        if (!createDir("/sys/fs/bpf/loader")) return 31;
    }

    if (runningAsRoot) {  // implies U QPR3+ and kernel 4.14+
        // There should not be any programs or maps yet
        errno = 0;
        uint32_t progId = bpfGetNextProgId(0);  // expect 0 with errno == ENOENT
        if (progId || errno != ENOENT) {
            ALOGE("bpfGetNextProgId(zero) returned %u (errno %d)", progId, errno);
            return 32;
        }
        errno = 0;
        uint32_t mapId = bpfGetNextMapId(0);  // expect 0 with errno == ENOENT
        if (mapId || errno != ENOENT) {
            ALOGE("bpfGetNextMapId(zero) returned %u (errno %d)", mapId, errno);
            return 33;
        }
    } else if (isAtLeastKernelVersion(4, 14)) {  // implies S through U QPR2
        // bpfGetNext{Prog,Map}Id require 4.14+
        // furthermore since we're not running as root, we're not the initial
        // platform bpfloader, so there may already be some maps & programs.
        uint32_t mapId = 0;
        while (true) {
            errno = 0;
            uint32_t next = bpfGetNextMapId(mapId);
            if (!next && errno == ENOENT) break;
            if (next <= mapId) {
                ALOGE("bpfGetNextMapId(%u) returned %u errno %d", mapId, next, errno);
                return 34;
            }
            mapId = next;
        }
        // mapId is now the last map id, creating a new map should change that
        unique_fd map(createMap(BPF_MAP_TYPE_ARRAY, sizeof(int), sizeof(int), 1, 0));
        errno = 0;
        uint32_t next = bpfGetNextMapId(mapId);
        if (next <= mapId) {
            // We should fail here on Xiaomi S 4.14.180 due to kernel uapi bug,
            // which causes bpfGetNextMapId to behave as bpfGetNextProgId,
            // and thus it should return 0 with errno == ENOENT.
            ALOGE("bpfGetNextMapId(final %u) returned %u errno %d", mapId, next, errno);
            if (next || errno != ENOENT) return 35;
            if (isAtLeastT || isAtLeastKernelVersion(4, 20)) return 36;
            // implies Android S with 4.14 or 4.19 kernel
            ALOGW("Detected kernel with invalid BPF UAPI - disabling mainline use of eBPF.");
            // leave a flag that we're 'done'
            if (!createDir("/sys/fs/bpf/netd_shared/mainline_done")) return 37;
            return 0;
        }
    } else {  // implies S/T with 4.9 kernel
        // nothing we can do.
    }

    auto start = steady_clock::now();
    // Load all ELF objects, create programs and maps, and pin them
    if (!loadAllObjects()) {
        ALOGE("=== CRITICAL FAILURE LOADING BPF PROGRAMS ===");
        ALOGE("If this triggers reliably, you're probably missing kernel options or patches.");
        ALOGE("If this triggers randomly, you might be hitting some memory allocation "
              "problems or startup script race.");
        ALOGE("--- DO NOT EXPECT SYSTEM TO BOOT SUCCESSFULLY ---");
        sleep(20);
        return 38;
    }

    auto end = steady_clock::now();
    auto timeTaken = duration_cast<milliseconds>(end - start).count();
    ALOGD("Loaded total objects took %lld ms", timeTaken);
    {
        uint32_t key = BPF_KERNEL_STATS_MAP_KEY_TOTAL_OBJS_LOAD_TIME_MS;
        if (writeToMapEntry(bpfKernelStatsMapFd, &key, &timeTaken, BPF_ANY)) {
            ALOGE("Failed to write object load time to kernel stats map, err: [%d, %s]",
                  errno, strerror(errno));
            return 39;
        }

        uint32_t value = 123;
        key = BPF_KERNEL_STATS_MAP_KEY_UBSAN_BUG;
        if (writeToMapEntry(bpfKernelStatsMapFd, &key, &value, BPF_ANY)) {
            ALOGE("Critical kernel bug - failure to write into index 1 of 2 element bpf map array."
                  "[%d, %s]", errno, strerror(errno));
            if (isAtLeastT) return 40;
        }
    }

    // leave a flag that we're done
    if (!createDir("/sys/fs/bpf/netd_shared/mainline_done")) return 41;

    // platform bpfloader will only succeed when run as root
    if (!runningAsRoot) {
        // unreachable on U QPR3+ which always runs netbpfload as root

        ALOGI("mainline done, no need to transfer control to platform bpf loader.");
        return 0;
    }

    // unreachable before U QPR3
    if (exists(uprobestatsBpfLoader)) {
      ALOGI("done, transferring control to uprobestatsbpfload.");
      const char *args[] = {
          uprobestatsBpfLoader,
          NULL,
      };
      execve(args[0], (char **)args, envp);
      ALOGI("unable to execute uprobestatsbpfload, transferring control to "
            "platform bpfloader.");
    }

    // platform BpfLoader *needs* to run as root
    const char * args[] = { platformBpfLoader, NULL, };
    execve(args[0], (char**)args, envp);
    ALOGE("FATAL: execve('%s'): %d[%s]", platformBpfLoader, errno, strerror(errno));
    return 42;
}

}  // namespace bpf
}  // namespace android

int main(int argc, char** argv, char * const envp[]) {
    if (android::bpf::isAtLeastT) {
        InitLogging(argv, &KernelLogger);
    } else {
        // S lacks the sepolicy to make non-root uid KernelLogger viable
        InitLogging(argv);
    }

    // U QPR3+: we should never be run twice -- exit if bpf.progs_loaded is already set.
    if (GetIntProperty("bpf.progs_loaded", 0) && !getuid()) {
        ALOGE("bpf.progs_loaded already set to 1 - exiting.");
        return 0;
    }

    if (argc == 2 && !strcmp(argv[1], "done")) {
        // we're being re-exec'ed from platform bpfloader to 'finalize' things
        if (!SetProperty("bpf.progs_loaded", "1")) {
            ALOGE("Failed to set bpf.progs_loaded property to 1.");
            return 125;
        }
        ALOGI("success.");
        return 0;
    }

    // Normal 'initial' entry point
    return android::bpf::doLoad(argv, envp);
}
