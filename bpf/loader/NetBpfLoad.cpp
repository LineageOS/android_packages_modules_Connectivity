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

#include <arpa/inet.h>
#include <bpf/btf.h>
#include <bpf/libbpf.h>
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <error.h>
#include <fcntl.h>
#include <fstream>
#include <inttypes.h>
#include <iostream>
#include <linux/unistd.h>
#include <log/log.h>
#include <net/if.h>
#include <optional>
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

#include "BpfSyscallWrappers.h"
#include "bpf/BpfUtils.h"
#include "bpf_map_def.h"

// The following matches bpf_helpers.h, which is only for inclusion in bpf code
#define BPFLOADER_MAINLINE_S_VERSION 42u
#define BPFLOADER_MAINLINE_25Q2_VERSION 47u

using android::base::EndsWith;
using android::base::GetIntProperty;
using android::base::GetProperty;
using android::base::InitLogging;
using android::base::KernelLogger;
using android::base::SetProperty;
using android::base::Split;
using android::base::StartsWith;
using android::base::Tokenize;
using android::base::unique_fd;
using std::ifstream;
using std::ios;
using std::optional;
using std::string;
using std::vector;

namespace android {
namespace bpf {

// Bpf programs may specify per-program & per-map selinux_context and pin_subdir.
//
// The BpfLoader needs to convert these bpf.o specified strings into an enum
// for internal use (to check that valid values were specified for the specific
// location of the bpf.o file).
//
// It also needs to map selinux_context's into pin_subdir's.
// This is because of how selinux_context is actually implemented via pin+rename.
//
// Thus 'domain' enumerates all selinux_context's/pin_subdir's that the BpfLoader
// is aware of.  Thus there currently needs to be a 1:1 mapping between the two.
//
enum class domain : int {
    unspecified = 0,    // means just use the default for that specific pin location
    tethering,          // (S+) fs_bpf_tethering     /sys/fs/bpf/tethering
    net_private,        // (T+) fs_bpf_net_private   /sys/fs/bpf/net_private
    net_shared,         // (T+) fs_bpf_net_shared    /sys/fs/bpf/net_shared
    netd_readonly,      // (T+) fs_bpf_netd_readonly /sys/fs/bpf/netd_readonly
    netd_shared,        // (T+) fs_bpf_netd_shared   /sys/fs/bpf/netd_shared
    loader,             // (U+) fs_bpf_loader        /sys/fs/bpf/loader
                        // on T due to lack of sepolicy/genfscon rules it behaves simply as 'fs_bpf'
};

static constexpr domain AllDomains[] = {
    domain::unspecified,
    domain::tethering,
    domain::net_private,
    domain::net_shared,
    domain::netd_readonly,
    domain::netd_shared,
    domain::loader,
};

static constexpr bool specified(domain d) {
    return d != domain::unspecified;
}

struct Location {
    const char* const dir = "";
    const char* const prefix = "";
    const bool t_plus = true;
};

// Returns the build type string (from ro.build.type).
const std::string& getBuildType() {
    static std::string t = GetProperty("ro.build.type", "unknown");
    return t;
}

// The following functions classify the 3 Android build types.
inline bool isEng() {
    return getBuildType() == "eng";
}

inline bool isUser() {
    return getBuildType() == "user";
}

inline bool isUserdebug() {
    return getBuildType() == "userdebug";
}

#define BPF_FS_PATH "/sys/fs/bpf/"

static unsigned int page_size = static_cast<unsigned int>(getpagesize());

constexpr const char* lookupSelinuxContext(const domain d) {
    switch (d) {
        case domain::unspecified:   return "";
        case domain::tethering:     return "fs_bpf_tethering";
        case domain::net_private:   return "fs_bpf_net_private";
        case domain::net_shared:    return "fs_bpf_net_shared";
        case domain::netd_readonly: return "fs_bpf_netd_readonly";
        case domain::netd_shared:   return "fs_bpf_netd_shared";
        case domain::loader:        return "fs_bpf_loader";
    }
}

domain getDomainFromSelinuxContext(const char s[BPF_SELINUX_CONTEXT_CHAR_ARRAY_SIZE]) {
    for (domain d : AllDomains) {
        // Not sure how to enforce this at compile time, so abort() bpfloader at boot instead
        if (strlen(lookupSelinuxContext(d)) >= BPF_SELINUX_CONTEXT_CHAR_ARRAY_SIZE) abort();
        if (!strncmp(s, lookupSelinuxContext(d), BPF_SELINUX_CONTEXT_CHAR_ARRAY_SIZE)) return d;
    }
    ALOGE("unrecognized selinux_context '%-32s'", s);
    // Note: we *can* just abort() here as we only load bpf .o files shipped
    // in the same mainline module / apex as NetBpfLoad itself.
    abort();
}

constexpr const char* lookupPinSubdir(const domain d, const char* const unspecified = "") {
    switch (d) {
        case domain::unspecified:   return unspecified;
        case domain::tethering:     return "tethering/";
        case domain::net_private:   return "net_private/";
        case domain::net_shared:    return "net_shared/";
        case domain::netd_readonly: return "netd_readonly/";
        case domain::netd_shared:   return "netd_shared/";
        case domain::loader:        return "loader/";
    }
};

domain getDomainFromPinSubdir(const char s[BPF_PIN_SUBDIR_CHAR_ARRAY_SIZE]) {
    for (domain d : AllDomains) {
        // Not sure how to enforce this at compile time, so abort() bpfloader at boot instead
        if (strlen(lookupPinSubdir(d)) >= BPF_PIN_SUBDIR_CHAR_ARRAY_SIZE) abort();
        if (!strncmp(s, lookupPinSubdir(d), BPF_PIN_SUBDIR_CHAR_ARRAY_SIZE)) return d;
    }
    ALOGE("unrecognized pin_subdir '%-32s'", s);
    // Note: we *can* just abort() here as we only load bpf .o files shipped
    // in the same mainline module / apex as NetBpfLoad itself.
    abort();
}

static string pathToObjName(const string& path) {
    // extract everything after the final slash, ie. this is the filename 'foo@1.o' or 'bar.o'
    string filename = Split(path, "/").back();
    // strip off everything from the final period onwards (strip '.o' suffix), ie. 'foo@1' or 'bar'
    string name = filename.substr(0, filename.find_last_of('.'));
    // strip any potential @1 suffix, this will leave us with just 'foo' or 'bar'
    // this can be used to provide duplicate programs (mux based on the bpfloader version)
    return name.substr(0, name.find_last_of('@'));
}

typedef struct {
    const char* name;
    enum bpf_prog_type type;
    enum bpf_attach_type attach_type;
} sectionType;

/*
 * Map section name prefixes to program types, the section name will be:
 *   SECTION(<prefix>/<name-of-program>)
 * For example:
 *   SECTION("tracepoint/sched_switch_func") where sched_switch_funcs
 * is the name of the program, and tracepoint is the type.
 *
 * However, be aware that you should not be directly using the SECTION() macro.
 * Instead use the DEFINE_(BPF|XDP)_(PROG|MAP)... & LICENSE macros.
 *
 * Programs shipped inside the tethering apex should be limited to networking stuff,
 * as KPROBE, PERF_EVENT, TRACEPOINT are dangerous to use from mainline updatable code,
 * since they are less stable abi/api and may conflict with platform uses of bpf.
 */
sectionType sectionNameTypes[] = {
        {"bind4/",             BPF_PROG_TYPE_CGROUP_SOCK_ADDR, BPF_CGROUP_INET4_BIND},
        {"bind6/",             BPF_PROG_TYPE_CGROUP_SOCK_ADDR, BPF_CGROUP_INET6_BIND},
        {"cgroupskb/",         BPF_PROG_TYPE_CGROUP_SKB},
        {"cgroupsock/",        BPF_PROG_TYPE_CGROUP_SOCK},
        {"cgroupsockcreate/",  BPF_PROG_TYPE_CGROUP_SOCK,      BPF_CGROUP_INET_SOCK_CREATE},
        {"cgroupsockrelease/", BPF_PROG_TYPE_CGROUP_SOCK,      BPF_CGROUP_INET_SOCK_RELEASE},
        {"connect4/",          BPF_PROG_TYPE_CGROUP_SOCK_ADDR, BPF_CGROUP_INET4_CONNECT},
        {"connect6/",          BPF_PROG_TYPE_CGROUP_SOCK_ADDR, BPF_CGROUP_INET6_CONNECT},
        {"egress/",            BPF_PROG_TYPE_CGROUP_SKB,       BPF_CGROUP_INET_EGRESS},
        {"getsockopt/",        BPF_PROG_TYPE_CGROUP_SOCKOPT,   BPF_CGROUP_GETSOCKOPT},
        {"ingress/",           BPF_PROG_TYPE_CGROUP_SKB,       BPF_CGROUP_INET_INGRESS},
        {"postbind4/",         BPF_PROG_TYPE_CGROUP_SOCK,      BPF_CGROUP_INET4_POST_BIND},
        {"postbind6/",         BPF_PROG_TYPE_CGROUP_SOCK,      BPF_CGROUP_INET6_POST_BIND},
        {"recvmsg4/",          BPF_PROG_TYPE_CGROUP_SOCK_ADDR, BPF_CGROUP_UDP4_RECVMSG},
        {"recvmsg6/",          BPF_PROG_TYPE_CGROUP_SOCK_ADDR, BPF_CGROUP_UDP6_RECVMSG},
        {"schedact/",          BPF_PROG_TYPE_SCHED_ACT},
        {"schedcls/",          BPF_PROG_TYPE_SCHED_CLS},
        {"sendmsg4/",          BPF_PROG_TYPE_CGROUP_SOCK_ADDR, BPF_CGROUP_UDP4_SENDMSG},
        {"sendmsg6/",          BPF_PROG_TYPE_CGROUP_SOCK_ADDR, BPF_CGROUP_UDP6_SENDMSG},
        {"setsockopt/",        BPF_PROG_TYPE_CGROUP_SOCKOPT,   BPF_CGROUP_SETSOCKOPT},
        {"skfilter/",          BPF_PROG_TYPE_SOCKET_FILTER},
        {"sockops/",           BPF_PROG_TYPE_SOCK_OPS,         BPF_CGROUP_SOCK_OPS},
        {"sysctl",             BPF_PROG_TYPE_CGROUP_SYSCTL,    BPF_CGROUP_SYSCTL},
        {"xdp/",               BPF_PROG_TYPE_XDP},
};

typedef struct {
    enum bpf_prog_type type;
    enum bpf_attach_type attach_type;
    string name;
    vector<char> data;
    vector<char> rel_data;
    optional<struct bpf_prog_def> prog_def;

    unique_fd prog_fd; // fd after loading
} codeSection;

static int readElfHeader(ifstream& elfFile, Elf64_Ehdr* eh) {
    elfFile.seekg(0);
    if (elfFile.fail()) return -1;

    if (!elfFile.read((char*)eh, sizeof(*eh))) return -1;

    return 0;
}

// Reads all section header tables into an Shdr array
static int readSectionHeadersAll(ifstream& elfFile, vector<Elf64_Shdr>& shTable) {
    Elf64_Ehdr eh;
    int ret = 0;

    ret = readElfHeader(elfFile, &eh);
    if (ret) return ret;

    elfFile.seekg(eh.e_shoff);
    if (elfFile.fail()) return -1;

    // Read shdr table entries
    shTable.resize(eh.e_shnum);

    if (!elfFile.read((char*)shTable.data(), (eh.e_shnum * eh.e_shentsize))) return -ENOMEM;

    return 0;
}

// Read a section by its index - for ex to get sec hdr strtab blob
static int readSectionByIdx(ifstream& elfFile, int id, vector<char>& sec) {
    vector<Elf64_Shdr> shTable;
    int ret = readSectionHeadersAll(elfFile, shTable);
    if (ret) return ret;

    elfFile.seekg(shTable[id].sh_offset);
    if (elfFile.fail()) return -1;

    sec.resize(shTable[id].sh_size);
    if (!elfFile.read(sec.data(), shTable[id].sh_size)) return -1;

    return 0;
}

// Read whole section header string table
static int readSectionHeaderStrtab(ifstream& elfFile, vector<char>& strtab) {
    Elf64_Ehdr eh;
    int ret = readElfHeader(elfFile, &eh);
    if (ret) return ret;

    ret = readSectionByIdx(elfFile, eh.e_shstrndx, strtab);
    if (ret) return ret;

    return 0;
}

// Get name from offset in strtab
static int getSymName(ifstream& elfFile, int nameOff, string& name) {
    int ret;
    vector<char> secStrTab;

    ret = readSectionHeaderStrtab(elfFile, secStrTab);
    if (ret) return ret;

    if (nameOff >= (int)secStrTab.size()) return -1;

    name = string((char*)secStrTab.data() + nameOff);
    return 0;
}

// Reads a full section by name - example to get the GPL license
static int readSectionByName(const char* name, ifstream& elfFile, vector<char>& data) {
    vector<char> secStrTab;
    vector<Elf64_Shdr> shTable;
    int ret;

    ret = readSectionHeadersAll(elfFile, shTable);
    if (ret) return ret;

    ret = readSectionHeaderStrtab(elfFile, secStrTab);
    if (ret) return ret;

    for (int i = 0; i < (int)shTable.size(); i++) {
        char* secname = secStrTab.data() + shTable[i].sh_name;
        if (!secname) continue;

        if (!strcmp(secname, name)) {
            vector<char> dataTmp;
            dataTmp.resize(shTable[i].sh_size);

            elfFile.seekg(shTable[i].sh_offset);
            if (elfFile.fail()) return -1;

            if (!elfFile.read((char*)dataTmp.data(), shTable[i].sh_size)) return -1;

            data = dataTmp;
            return 0;
        }
    }
    return -2;
}

unsigned int readSectionUint(const char* name, ifstream& elfFile) {
    vector<char> theBytes;
    int ret = readSectionByName(name, elfFile, theBytes);
    if (ret) {
        ALOGE("Couldn't find section %s.", name);
        abort();
    } else if (theBytes.size() < sizeof(unsigned int)) {
        ALOGE("Section %s is too short.", name);
        abort();
    } else {
        // decode first 4 bytes as LE32 uint, there will likely be more bytes due to alignment.
        unsigned int value = static_cast<unsigned char>(theBytes[3]);
        value <<= 8;
        value += static_cast<unsigned char>(theBytes[2]);
        value <<= 8;
        value += static_cast<unsigned char>(theBytes[1]);
        value <<= 8;
        value += static_cast<unsigned char>(theBytes[0]);
        ALOGD("Section %s value is %u [0x%x]", name, value, value);
        return value;
    }
}

static int readSectionByType(ifstream& elfFile, int type, vector<char>& data) {
    int ret;
    vector<Elf64_Shdr> shTable;

    ret = readSectionHeadersAll(elfFile, shTable);
    if (ret) return ret;

    for (int i = 0; i < (int)shTable.size(); i++) {
        if ((int)shTable[i].sh_type != type) continue;

        vector<char> dataTmp;
        dataTmp.resize(shTable[i].sh_size);

        elfFile.seekg(shTable[i].sh_offset);
        if (elfFile.fail()) return -1;

        if (!elfFile.read((char*)dataTmp.data(), shTable[i].sh_size)) return -1;

        data = dataTmp;
        return 0;
    }
    return -2;
}

static bool symCompare(Elf64_Sym a, Elf64_Sym b) {
    return (a.st_value < b.st_value);
}

static int readSymTab(ifstream& elfFile, int sort, vector<Elf64_Sym>& data) {
    int ret, numElems;
    Elf64_Sym* buf;
    vector<char> secData;

    ret = readSectionByType(elfFile, SHT_SYMTAB, secData);
    if (ret) return ret;

    buf = (Elf64_Sym*)secData.data();
    numElems = (secData.size() / sizeof(Elf64_Sym));
    data.assign(buf, buf + numElems);

    if (sort) std::sort(data.begin(), data.end(), symCompare);
    return 0;
}

static enum bpf_prog_type getSectionType(string& name) {
    for (auto& snt : sectionNameTypes)
        if (StartsWith(name, snt.name)) return snt.type;

    return BPF_PROG_TYPE_UNSPEC;
}

static int readProgDefs(ifstream& elfFile, vector<struct bpf_prog_def>& pd) {
    vector<char> pdData;
    int ret = readSectionByName("progs", elfFile, pdData);
    if (ret) return ret;

    if (pdData.size() % sizeof(struct bpf_prog_def)) {
        ALOGE("readProgDefs failed due to improper sized progs section, %zu %% %zu != 0",
              pdData.size(), sizeof(struct bpf_prog_def));
        return -1;
    };

    pd.resize(pdData.size() / sizeof(struct bpf_prog_def));

    const char* dataPtr = pdData.data();
    for (auto& p : pd) {
        // Copy the structure from the ELF file and move to the next one.
        memcpy(&p, dataPtr, sizeof(struct bpf_prog_def));
        dataPtr += sizeof(struct bpf_prog_def);
    }
    return 0;
}

static int getSectionSymNames(ifstream& elfFile, const string& sectionName, vector<string>& names,
                              optional<unsigned> symbolType = std::nullopt) {
    int ret;
    string name;
    vector<Elf64_Sym> symtab;
    vector<Elf64_Shdr> shTable;

    ret = readSymTab(elfFile, 1 /* sort */, symtab);
    if (ret) return ret;

    // Get index of section
    ret = readSectionHeadersAll(elfFile, shTable);
    if (ret) return ret;

    int sec_idx = -1;
    for (int i = 0; i < (int)shTable.size(); i++) {
        ret = getSymName(elfFile, shTable[i].sh_name, name);
        if (ret) return ret;

        if (!name.compare(sectionName)) {
            sec_idx = i;
            break;
        }
    }

    // No section found with matching name
    if (sec_idx == -1) {
        ALOGW("No %s section could be found in elf object", sectionName.c_str());
        return -1;
    }

    for (int i = 0; i < (int)symtab.size(); i++) {
        if (symbolType.has_value() && ELF_ST_TYPE(symtab[i].st_info) != symbolType) continue;

        if (symtab[i].st_shndx == sec_idx) {
            string s;
            ret = getSymName(elfFile, symtab[i].st_name, s);
            if (ret) return ret;
            names.push_back(s);
        }
    }

    return 0;
}

// Read a section by its index - for ex to get sec hdr strtab blob
static int readCodeSections(ifstream& elfFile, vector<codeSection>& cs) {
    vector<Elf64_Shdr> shTable;
    int entries, ret = 0;

    ret = readSectionHeadersAll(elfFile, shTable);
    if (ret) return ret;
    entries = shTable.size();

    vector<struct bpf_prog_def> pd;
    ret = readProgDefs(elfFile, pd);
    if (ret) return ret;
    vector<string> progDefNames;
    ret = getSectionSymNames(elfFile, "progs", progDefNames);
    if (!pd.empty() && ret) return ret;

    for (int i = 0; i < entries; i++) {
        string name;
        codeSection cs_temp;
        cs_temp.type = BPF_PROG_TYPE_UNSPEC;

        ret = getSymName(elfFile, shTable[i].sh_name, name);
        if (ret) return ret;

        enum bpf_prog_type ptype = getSectionType(name);

        if (ptype == BPF_PROG_TYPE_UNSPEC) continue;

        // This must be done before '/' is replaced with '_'.
        for (auto& snt : sectionNameTypes)
            if (StartsWith(name, snt.name)) cs_temp.attach_type = snt.attach_type;

        string oldName = name;

        // convert all slashes to underscores
        std::replace(name.begin(), name.end(), '/', '_');

        cs_temp.type = ptype;
        cs_temp.name = name;

        ret = readSectionByIdx(elfFile, i, cs_temp.data);
        if (ret) return ret;
        ALOGV("Loaded code section %d (%s)", i, name.c_str());

        vector<string> csSymNames;
        ret = getSectionSymNames(elfFile, oldName, csSymNames, STT_FUNC);
        if (ret || !csSymNames.size()) return ret;
        for (size_t j = 0; j < progDefNames.size(); ++j) {
            if (!progDefNames[j].compare(csSymNames[0] + "_def")) {
                cs_temp.prog_def = pd[j];
                break;
            }
        }

        // Check for rel section
        if (cs_temp.data.size() > 0 && i < entries) {
            ret = getSymName(elfFile, shTable[i + 1].sh_name, name);
            if (ret) return ret;

            if (name == (".rel" + oldName)) {
                ret = readSectionByIdx(elfFile, i + 1, cs_temp.rel_data);
                if (ret) return ret;
                ALOGV("Loaded relo section %d (%s)", i, name.c_str());
            }
        }

        if (cs_temp.data.size() > 0) {
            cs.push_back(std::move(cs_temp));
            ALOGV("Adding section %d to cs list", i);
        }
    }
    return 0;
}

static int getSymNameByIdx(ifstream& elfFile, int index, string& name) {
    vector<Elf64_Sym> symtab;
    int ret = 0;

    ret = readSymTab(elfFile, 0 /* !sort */, symtab);
    if (ret) return ret;

    if (index >= (int)symtab.size()) return -1;

    return getSymName(elfFile, symtab[index].st_name, name);
}

static bool mapMatchesExpectations(const unique_fd& fd, const string& mapName,
                                   const struct bpf_map_def& mapDef, const enum bpf_map_type type) {
    // bpfGetFd... family of functions require at minimum a 4.14 kernel,
    // so on 4.9-T kernels just pretend the map matches our expectations.
    // Additionally we'll get almost equivalent test coverage on newer devices/kernels.
    // This is because the primary failure mode we're trying to detect here
    // is either a source code misconfiguration (which is likely kernel independent)
    // or a newly introduced kernel feature/bug (which is unlikely to get backported to 4.9).
    if (!isAtLeastKernelVersion(4, 14, 0)) return true;

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
    int desired_map_flags = (int)mapDef.map_flags;
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
        (fd_map_flags == desired_map_flags)) {
        return true;
    }

    ALOGE("bpf map name %s mismatch: desired/found: "
          "type:%d/%d key:%u/%d value:%u/%d entries:%u/%d flags:%u/%d",
          mapName.c_str(), type, fd_type, mapDef.key_size, fd_key_size, mapDef.value_size,
          fd_value_size, mapDef.max_entries, fd_max_entries, desired_map_flags, fd_map_flags);
    return false;
}

static int setBtfDatasecSize(ifstream &elfFile, struct btf *btf,
                             struct btf_type *bt) {
    const char *name = btf__name_by_offset(btf, bt->name_off);
    if (!name) {
        ALOGE("Couldn't resolve section name, errno: %d", errno);
        return -errno;
    }

    vector<char> data;
    int ret = readSectionByName(name, elfFile, data);
    if (ret) {
        ALOGE("Couldn't read section %s, ret: %d", name, ret);
        return ret;
    }
    bt->size = data.size();
    return 0;
}

static int getSymOffsetByName(ifstream &elfFile, const char *name, int *off) {
    vector<Elf64_Sym> symtab;
    int ret = readSymTab(elfFile, 1 /* sort */, symtab);
    if (ret) return ret;
    for (int i = 0; i < (int)symtab.size(); i++) {
        string s;
        ret = getSymName(elfFile, symtab[i].st_name, s);
        if (ret) continue;
        if (!strcmp(s.c_str(), name)) {
            *off = symtab[i].st_value;
            return 0;
        }
    }
    return -1;
}

static int setBtfVarOffset(ifstream &elfFile, struct btf *btf,
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
            ALOGE("Found non VAR kind btf_type, section: %s id: %d", datasecName,
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

        int off;
        int ret = getSymOffsetByName(elfFile, varName, &off);
        if (ret) {
            ALOGE("No offset found in symbol table, section: %s, var: %s, ret: %d",
                  datasecName, varName, ret);
            return ret;
        }
        vsi->offset = off;
    }
    return 0;
}

static int loadBtf(ifstream &elfFile, struct btf *btf) {
    int ret;
    for (unsigned int i = 1; i < btf__type_cnt(btf); ++i) {
        struct btf_type *bt = (struct btf_type *)btf__type_by_id(btf, i);
        if (!btf_is_datasec(bt)) continue;
        ret = setBtfDatasecSize(elfFile, btf, bt);
        if (ret) return ret;
        ret = setBtfVarOffset(elfFile, btf, bt);
        if (ret) return ret;
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
        ALOGE("Couldn't find BTF type, map: %s id: %u", mapName, kvId);
        return -1;
    }

    if (!btf_is_struct(kvBt) || btf_vlen(kvBt) < 2) {
        ALOGE("Non Struct kind or invalid vlen, map: %s id: %u", mapName, kvId);
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
        ALOGE("Key value size mismatch, map: %s key size: %d expected key size: "
              "%d value size: %d expected value size: %d",
              mapName, (uint32_t)keySize, expectedKeySize, (uint32_t)valueSize,
              expectedValueSize);
        return -1;
    }

    *keyTypeId = key->type;
    *valueTypeId = value->type;

    return 0;
}

static bool isBtfSupported(enum bpf_map_type type) {
    return type != BPF_MAP_TYPE_DEVMAP_HASH && type != BPF_MAP_TYPE_RINGBUF;
}

static int createMaps(const char* elfPath, ifstream& elfFile, vector<unique_fd>& mapFds,
                      const char* prefix, const unsigned int bpfloader_ver) {
    int ret;
    vector<char> mdData, btfData;
    vector<struct bpf_map_def> md;
    vector<string> mapNames;
    string objName = pathToObjName(string(elfPath));

    ret = readSectionByName("maps", elfFile, mdData);
    if (ret == -2) return 0;  // no maps to read
    if (ret) return ret;

    if (mdData.size() % sizeof(struct bpf_map_def)) {
        ALOGE("createMaps failed due to improper sized maps section, %zu %% %zu != 0",
              mdData.size(), sizeof(struct bpf_map_def));
        return -1;
    };

    md.resize(mdData.size() / sizeof(struct bpf_map_def));

    const char* dataPtr = mdData.data();
    for (auto& m : md) {
        // Copy the structure from the ELF file and move to the next one.
        memcpy(&m, dataPtr, sizeof(struct bpf_map_def));
        dataPtr += sizeof(struct bpf_map_def);
    }

    ret = getSectionSymNames(elfFile, "maps", mapNames);
    if (ret) return ret;

    struct btf *btf = NULL;
    auto scopeGuard = base::make_scope_guard([btf] { if (btf) btf__free(btf); });
    if (isAtLeastKernelVersion(5, 10, 0)) {
        // Untested on Linux Kernel 5.4, but likely compatible.
        // On Linux Kernels older than 4.18 BPF_BTF_LOAD command doesn't exist.
        // On Linux Kernels older than 5.2 BTF_KIND_VAR and BTF_KIND_DATASEC don't exist.
        ret = readSectionByName(".BTF", elfFile, btfData);
        if (ret) {
            ALOGE("Failed to read .BTF section, ret:%d", ret);
            return ret;
        }
        btf = btf__new(btfData.data(), btfData.size());
        if (btf == NULL) {
            ALOGE("btf__new failed, errno: %d", errno);
            return -errno;
        }

        ret = loadBtf(elfFile, btf);
        if (ret) return ret;
    }

    unsigned kvers = kernelVersion();

    for (int i = 0; i < (int)mapNames.size(); i++) {
        if (md[i].zero != 0) abort();

        if (bpfloader_ver < md[i].bpfloader_min_ver) {
            ALOGD("skipping map %s which requires bpfloader min ver 0x%05x", mapNames[i].c_str(),
                  md[i].bpfloader_min_ver);
            mapFds.push_back(unique_fd());
            continue;
        }

        if (bpfloader_ver >= md[i].bpfloader_max_ver) {
            ALOGD("skipping map %s which requires bpfloader max ver 0x%05x", mapNames[i].c_str(),
                  md[i].bpfloader_max_ver);
            mapFds.push_back(unique_fd());
            continue;
        }

        if (kvers < md[i].min_kver) {
            ALOGD("skipping map %s which requires kernel version 0x%x >= 0x%x",
                  mapNames[i].c_str(), kvers, md[i].min_kver);
            mapFds.push_back(unique_fd());
            continue;
        }

        if (kvers >= md[i].max_kver) {
            ALOGD("skipping map %s which requires kernel version 0x%x < 0x%x",
                  mapNames[i].c_str(), kvers, md[i].max_kver);
            mapFds.push_back(unique_fd());
            continue;
        }

        if ((md[i].ignore_on_eng && isEng()) || (md[i].ignore_on_user && isUser()) ||
            (md[i].ignore_on_userdebug && isUserdebug())) {
            ALOGD("skipping map %s which is ignored on %s builds", mapNames[i].c_str(),
                  getBuildType().c_str());
            mapFds.push_back(unique_fd());
            continue;
        }

        if ((isArm() && isKernel32Bit() && md[i].ignore_on_arm32) ||
            (isArm() && isKernel64Bit() && md[i].ignore_on_aarch64) ||
            (isX86() && isKernel32Bit() && md[i].ignore_on_x86_32) ||
            (isX86() && isKernel64Bit() && md[i].ignore_on_x86_64) ||
            (isRiscV() && md[i].ignore_on_riscv64)) {
            ALOGD("skipping map %s which is ignored on %s", mapNames[i].c_str(),
                  describeArch());
            mapFds.push_back(unique_fd());
            continue;
        }

        enum bpf_map_type type = md[i].type;
        if (type == BPF_MAP_TYPE_LPM_TRIE && !isAtLeastKernelVersion(4, 14, 0)) {
            // On Linux Kernels older than 4.14 this map type doesn't exist - autoskip.
            ALOGD("skipping LPM_TRIE map %s - requires kver 4.14+", mapNames[i].c_str());
            mapFds.push_back(unique_fd());
            continue;
        }
        if (type == BPF_MAP_TYPE_DEVMAP && !isAtLeastKernelVersion(4, 14, 0)) {
            // On Linux Kernels older than 4.14 this map type doesn't exist, but it can kind
            // of be approximated: ARRAY has the same userspace api, though it is not usable
            // by the same ebpf programs.  However, that's okay because the bpf_redirect_map()
            // helper doesn't exist on 4.9-T anyway (so the bpf program would fail to load,
            // and thus needs to be tagged as 4.14+ either way), so there's nothing useful you
            // could do with a DEVMAP anyway (that isn't already provided by an ARRAY)...
            // Hence using an ARRAY instead of a DEVMAP simply makes life easier for userspace.
            type = BPF_MAP_TYPE_ARRAY;
        }
        if (type == BPF_MAP_TYPE_DEVMAP_HASH && !isAtLeastKernelVersion(5, 4, 0)) {
            // On Linux Kernels older than 5.4 this map type doesn't exist, but it can kind
            // of be approximated: HASH has the same userspace visible api.
            // However it cannot be used by ebpf programs in the same way.
            // Since bpf_redirect_map() only requires 4.14, a program using a DEVMAP_HASH map
            // would fail to load (due to trying to redirect to a HASH instead of DEVMAP_HASH).
            // One must thus tag any BPF_MAP_TYPE_DEVMAP_HASH + bpf_redirect_map() using
            // programs as being 5.4+...
            type = BPF_MAP_TYPE_HASH;
        }

        // The .h file enforces that this is a power of two, and page size will
        // also always be a power of two, so this logic is actually enough to
        // force it to be a multiple of the page size, as required by the kernel.
        unsigned int max_entries = md[i].max_entries;
        if (type == BPF_MAP_TYPE_RINGBUF) {
            if (max_entries < page_size) max_entries = page_size;
        }

        domain selinux_context = getDomainFromSelinuxContext(md[i].selinux_context);
        if (specified(selinux_context)) {
            ALOGV("map %s selinux_context [%-32s] -> %d -> '%s' (%s)", mapNames[i].c_str(),
                  md[i].selinux_context, static_cast<int>(selinux_context),
                  lookupSelinuxContext(selinux_context), lookupPinSubdir(selinux_context));
        }

        domain pin_subdir = getDomainFromPinSubdir(md[i].pin_subdir);
        if (specified(pin_subdir)) {
            ALOGV("map %s pin_subdir [%-32s] -> %d -> '%s'", mapNames[i].c_str(), md[i].pin_subdir,
                  static_cast<int>(pin_subdir), lookupPinSubdir(pin_subdir));
        }

        // Format of pin location is /sys/fs/bpf/<pin_subdir|prefix>map_<objName>_<mapName>
        // except that maps shared across .o's have empty <objName>
        // Note: <objName> refers to the extension-less basename of the .o file (without @ suffix).
        string mapPinLoc = string(BPF_FS_PATH) + lookupPinSubdir(pin_subdir, prefix) + "map_" +
                           (md[i].shared ? "" : objName) + "_" + mapNames[i];
        bool reuse = false;
        unique_fd fd;
        int saved_errno;

        if (access(mapPinLoc.c_str(), F_OK) == 0) {
            fd.reset(mapRetrieveRO(mapPinLoc.c_str()));
            saved_errno = errno;
            ALOGD("bpf_create_map reusing map %s, ret: %d", mapNames[i].c_str(), fd.get());
            reuse = true;
        } else {
            union bpf_attr req = {
              .map_type = type,
              .key_size = md[i].key_size,
              .value_size = md[i].value_size,
              .max_entries = max_entries,
              .map_flags = md[i].map_flags,
            };
            if (isAtLeastKernelVersion(4, 15, 0))
                strlcpy(req.map_name, mapNames[i].c_str(), sizeof(req.map_name));

            bool haveBtf = btf && isBtfSupported(type);
            if (haveBtf) {
                uint32_t kTid, vTid;
                ret = getKeyValueTids(btf, mapNames[i].c_str(), md[i].key_size,
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
                      mapNames[i].c_str(), haveBtf, fd.get());
            } else {
                ALOGE("bpf_create_map[%s] btf:%d -> %d errno:%d",
                      mapNames[i].c_str(), haveBtf, fd.get(), saved_errno);
            }
        }

        if (!fd.ok()) return -saved_errno;

        // When reusing a pinned map, we need to check the map type/sizes/etc match, but for
        // safety (since reuse code path is rare) run these checks even if we just created it.
        // We assume failure is due to pinned map mismatch, hence the 'NOT UNIQUE' return code.
        if (!mapMatchesExpectations(fd, mapNames[i], md[i], type)) return -ENOTUNIQ;

        if (!reuse) {
            if (specified(selinux_context)) {
                string createLoc = string(BPF_FS_PATH) + lookupPinSubdir(selinux_context) +
                                   "tmp_map_" + objName + "_" + mapNames[i];
                ret = bpfFdPin(fd, createLoc.c_str());
                if (ret) {
                    const int err = errno;
                    ALOGE("create %s -> %d [%d:%s]", createLoc.c_str(), ret, err, strerror(err));
                    return -err;
                }
                ret = renameat2(AT_FDCWD, createLoc.c_str(),
                                AT_FDCWD, mapPinLoc.c_str(), RENAME_NOREPLACE);
                if (ret) {
                    const int err = errno;
                    ALOGE("rename %s %s -> %d [%d:%s]", createLoc.c_str(), mapPinLoc.c_str(), ret,
                          err, strerror(err));
                    return -err;
                }
            } else {
                ret = bpfFdPin(fd, mapPinLoc.c_str());
                if (ret) {
                    const int err = errno;
                    ALOGE("pin %s -> %d [%d:%s]", mapPinLoc.c_str(), ret, err, strerror(err));
                    return -err;
                }
            }
            ret = chmod(mapPinLoc.c_str(), md[i].mode);
            if (ret) {
                const int err = errno;
                ALOGE("chmod(%s, 0%o) = %d [%d:%s]", mapPinLoc.c_str(), md[i].mode, ret, err,
                      strerror(err));
                return -err;
            }
            ret = chown(mapPinLoc.c_str(), (uid_t)md[i].uid, (gid_t)md[i].gid);
            if (ret) {
                const int err = errno;
                ALOGE("chown(%s, %u, %u) = %d [%d:%s]", mapPinLoc.c_str(), md[i].uid, md[i].gid,
                      ret, err, strerror(err));
                return -err;
            }
        }

        if (isAtLeastKernelVersion(4, 14, 0)) {
            int mapId = bpfGetFdMapId(fd);
            if (mapId == -1) {
                const int err = errno;
                ALOGE("bpfGetFdMapId failed, errno: %d", err);
                return -err;
            }
            ALOGI("map %s id %d", mapPinLoc.c_str(), mapId);
        }

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

static void applyMapRelo(ifstream& elfFile, vector<unique_fd> &mapFds, vector<codeSection>& cs) {
    vector<string> mapNames;

    int ret = getSectionSymNames(elfFile, "maps", mapNames);
    if (ret) return;

    for (int k = 0; k != (int)cs.size(); k++) {
        Elf64_Rel* rel = (Elf64_Rel*)(cs[k].rel_data.data());
        int n_rel = cs[k].rel_data.size() / sizeof(*rel);

        for (int i = 0; i < n_rel; i++) {
            int symIndex = ELF64_R_SYM(rel[i].r_info);
            string symName;

            ret = getSymNameByIdx(elfFile, symIndex, symName);
            if (ret) return;

            // Find the map fd and apply relo
            for (int j = 0; j < (int)mapNames.size(); j++) {
                if (!mapNames[j].compare(symName)) {
                    applyRelo(cs[k].data.data(), rel[i].r_offset, mapFds[j]);
                    break;
                }
            }
        }
    }
}

static int loadCodeSections(const char* elfPath, vector<codeSection>& cs, const string& license,
                            const char* prefix, const unsigned int bpfloader_ver) {
    unsigned kvers = kernelVersion();

    if (!kvers) {
        ALOGE("unable to get kernel version");
        return -EINVAL;
    }

    string objName = pathToObjName(string(elfPath));

    for (int i = 0; i < (int)cs.size(); i++) {
        unique_fd& fd = cs[i].prog_fd;
        int ret;
        string name = cs[i].name;

        if (!cs[i].prog_def.has_value()) {
            ALOGE("[%d] '%s' missing program definition! bad bpf.o build?", i, name.c_str());
            return -EINVAL;
        }

        unsigned min_kver = cs[i].prog_def->min_kver;
        unsigned max_kver = cs[i].prog_def->max_kver;
        ALOGD("cs[%d].name:%s min_kver:%x .max_kver:%x (kvers:%x)", i, name.c_str(), min_kver,
             max_kver, kvers);
        if (kvers < min_kver) continue;
        if (kvers >= max_kver) continue;

        unsigned bpfMinVer = cs[i].prog_def->bpfloader_min_ver;
        unsigned bpfMaxVer = cs[i].prog_def->bpfloader_max_ver;
        domain selinux_context = getDomainFromSelinuxContext(cs[i].prog_def->selinux_context);
        domain pin_subdir = getDomainFromPinSubdir(cs[i].prog_def->pin_subdir);

        ALOGD("cs[%d].name:%s requires bpfloader version [0x%05x,0x%05x)", i, name.c_str(),
              bpfMinVer, bpfMaxVer);
        if (bpfloader_ver < bpfMinVer) continue;
        if (bpfloader_ver >= bpfMaxVer) continue;

        if ((cs[i].prog_def->ignore_on_eng && isEng()) ||
            (cs[i].prog_def->ignore_on_user && isUser()) ||
            (cs[i].prog_def->ignore_on_userdebug && isUserdebug())) {
            ALOGD("cs[%d].name:%s is ignored on %s builds", i, name.c_str(),
                  getBuildType().c_str());
            continue;
        }

        if ((isArm() && isKernel32Bit() && cs[i].prog_def->ignore_on_arm32) ||
            (isArm() && isKernel64Bit() && cs[i].prog_def->ignore_on_aarch64) ||
            (isX86() && isKernel32Bit() && cs[i].prog_def->ignore_on_x86_32) ||
            (isX86() && isKernel64Bit() && cs[i].prog_def->ignore_on_x86_64) ||
            (isRiscV() && cs[i].prog_def->ignore_on_riscv64)) {
            ALOGD("cs[%d].name:%s is ignored on %s", i, name.c_str(), describeArch());
            continue;
        }

        if (specified(selinux_context)) {
            ALOGV("prog %s selinux_context [%-32s] -> %d -> '%s' (%s)", name.c_str(),
                  cs[i].prog_def->selinux_context, static_cast<int>(selinux_context),
                  lookupSelinuxContext(selinux_context), lookupPinSubdir(selinux_context));
        }

        if (specified(pin_subdir)) {
            ALOGV("prog %s pin_subdir [%-32s] -> %d -> '%s'", name.c_str(),
                  cs[i].prog_def->pin_subdir, static_cast<int>(pin_subdir),
                  lookupPinSubdir(pin_subdir));
        }

        // strip any potential $foo suffix
        // this can be used to provide duplicate programs
        // conditionally loaded based on running kernel version
        name = name.substr(0, name.find_last_of('$'));

        bool reuse = false;
        // Format of pin location is
        // /sys/fs/bpf/<prefix>prog_<objName>_<progName>
        string progPinLoc = string(BPF_FS_PATH) + lookupPinSubdir(pin_subdir, prefix) + "prog_" +
                            objName + '_' + string(name);
        if (access(progPinLoc.c_str(), F_OK) == 0) {
            fd.reset(retrieveProgram(progPinLoc.c_str()));
            ALOGD("New bpf prog load reusing prog %s, ret: %d (%s)", progPinLoc.c_str(), fd.get(),
                  !fd.ok() ? std::strerror(errno) : "ok");
            reuse = true;
        } else {
            static char log_buf[1 << 20];  // 1 MiB logging buffer

            union bpf_attr req = {
              .prog_type = cs[i].type,
              .insn_cnt = static_cast<__u32>(cs[i].data.size() / sizeof(struct bpf_insn)),
              .insns = ptr_to_u64(cs[i].data.data()),
              .license = ptr_to_u64(license.c_str()),
              .log_level = 1,
              .log_size = sizeof(log_buf),
              .log_buf = ptr_to_u64(log_buf),
              .kern_version = kvers,
              .expected_attach_type = cs[i].attach_type,
            };
            if (isAtLeastKernelVersion(4, 15, 0))
                strlcpy(req.prog_name, cs[i].name.c_str(), sizeof(req.prog_name));
            fd.reset(bpf(BPF_PROG_LOAD, req));

            // Kernel should have NULL terminated the log buffer, but force it anyway for safety
            log_buf[sizeof(log_buf) - 1] = 0;

            // Strip out final newline if present
            int log_chars = strlen(log_buf);
            if (log_chars && log_buf[log_chars - 1] == '\n') log_buf[--log_chars] = 0;

            bool log_oneline = !strchr(log_buf, '\n');

            ALOGD("BPF_PROG_LOAD call for %s (%s) returned '%s' fd: %d (%s)", elfPath,
                  cs[i].name.c_str(), log_oneline ? log_buf : "{multiline}",
                  fd.get(), !fd.ok() ? std::strerror(errno) : "ok");

            if (!fd.ok()) {
                // kernel NULL terminates log_buf, so this checks for non-empty string
                if (log_buf[0]) {
                    vector<string> lines = Split(log_buf, "\n");

                    ALOGW("BPF_PROG_LOAD - BEGIN log_buf contents:");
                    for (const auto& line : lines) ALOGW("%s", line.c_str());
                    ALOGW("BPF_PROG_LOAD - END log_buf contents.");
                }

                if (cs[i].prog_def->optional) {
                    ALOGW("failed program %s is marked optional - continuing...",
                          cs[i].name.c_str());
                    continue;
                }
                ALOGE("non-optional program %s failed to load.", cs[i].name.c_str());
            }
        }

        if (!fd.ok()) return fd.get();

        if (!reuse) {
            if (specified(selinux_context)) {
                string createLoc = string(BPF_FS_PATH) + lookupPinSubdir(selinux_context) +
                                   "tmp_prog_" + objName + '_' + string(name);
                ret = bpfFdPin(fd, createLoc.c_str());
                if (ret) {
                    const int err = errno;
                    ALOGE("create %s -> %d [%d:%s]", createLoc.c_str(), ret, err, strerror(err));
                    return -err;
                }
                ret = renameat2(AT_FDCWD, createLoc.c_str(),
                                AT_FDCWD, progPinLoc.c_str(), RENAME_NOREPLACE);
                if (ret) {
                    const int err = errno;
                    ALOGE("rename %s %s -> %d [%d:%s]", createLoc.c_str(), progPinLoc.c_str(), ret,
                          err, strerror(err));
                    return -err;
                }
            } else {
                ret = bpfFdPin(fd, progPinLoc.c_str());
                if (ret) {
                    const int err = errno;
                    ALOGE("create %s -> %d [%d:%s]", progPinLoc.c_str(), ret, err, strerror(err));
                    return -err;
                }
            }
            if (chmod(progPinLoc.c_str(), 0440)) {
                const int err = errno;
                ALOGE("chmod %s 0440 -> [%d:%s]", progPinLoc.c_str(), err, strerror(err));
                return -err;
            }
            if (chown(progPinLoc.c_str(), (uid_t)cs[i].prog_def->uid,
                      (gid_t)cs[i].prog_def->gid)) {
                const int err = errno;
                ALOGE("chown %s %d %d -> [%d:%s]", progPinLoc.c_str(), cs[i].prog_def->uid,
                      cs[i].prog_def->gid, err, strerror(err));
                return -err;
            }
        }

        if (isAtLeastKernelVersion(4, 14, 0)) {
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
            ALOGI("prog %s id %d len jit:%d xlat:%d", progPinLoc.c_str(), progId, jitLen, xlatLen);

            if (!jitLen && bpfloader_ver >= BPFLOADER_MAINLINE_25Q2_VERSION) {
                ALOGE("Kernel eBPF JIT failure for %s", progPinLoc.c_str());
                return -ENOTSUP;
            }
        }
    }

    return 0;
}

int loadProg(const char* const elfPath, const unsigned int bpfloader_ver,
             const char* const prefix) {
    vector<char> license;
    vector<codeSection> cs;
    vector<unique_fd> mapFds;
    int ret;

    ifstream elfFile(elfPath, ios::in | ios::binary);
    if (!elfFile.is_open()) return -1;

    ret = readSectionByName("license", elfFile, license);
    if (ret) {
        ALOGE("Couldn't find license in %s", elfPath);
        return ret;
    } else {
        ALOGD("Loading ELF object %s with license %s",
              elfPath, (char*)license.data());
    }

    unsigned int bpfLoaderMinVer = readSectionUint("bpfloader_min_ver", elfFile);
    unsigned int bpfLoaderMaxVer = readSectionUint("bpfloader_max_ver", elfFile);

    // inclusive lower bound check
    if (bpfloader_ver < bpfLoaderMinVer) {
        ALOGD("BpfLoader version 0x%05x ignoring ELF object %s with min ver 0x%05x",
              bpfloader_ver, elfPath, bpfLoaderMinVer);
        return 0;
    }

    // exclusive upper bound check
    if (bpfloader_ver >= bpfLoaderMaxVer) {
        ALOGD("BpfLoader version 0x%05x ignoring ELF object %s with max ver 0x%05x",
              bpfloader_ver, elfPath, bpfLoaderMaxVer);
        return 0;
    }

    ALOGD("BpfLoader version 0x%05x processing ELF object %s with ver [0x%05x,0x%05x)",
          bpfloader_ver, elfPath, bpfLoaderMinVer, bpfLoaderMaxVer);

    ret = createMaps(elfPath, elfFile, mapFds, prefix, bpfloader_ver);
    if (ret) {
        ALOGE("Failed to create maps: (ret=%d) in %s", ret, elfPath);
        return ret;
    }

    for (int i = 0; i < (int)mapFds.size(); i++)
        ALOGV("map_fd found at %d is %d in %s", i, mapFds[i].get(), elfPath);

    ret = readCodeSections(elfFile, cs);
    // BPF .o's with no programs are only supported by mainline netbpfload,
    // make sure .o's targeting non-mainline (ie. S) bpfloader don't show up.
    if (ret == -ENOENT && bpfLoaderMinVer >= BPFLOADER_MAINLINE_S_VERSION)
        return 0;
    if (ret) {
        ALOGE("Couldn't read all code sections in %s", elfPath);
        return ret;
    }

    applyMapRelo(elfFile, mapFds, cs);

    ret = loadCodeSections(elfPath, cs, string(license.data()), prefix, bpfloader_ver);
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

#define APEXROOT "/apex/com.android.tethering"
#define BPFROOT APEXROOT "/etc/bpf"

const Location locations[] = {
        // S+ Tethering mainline module (network_stack): tether offload
        {
                .dir = BPFROOT "/tethering/",
                .prefix = "tethering/",
                .t_plus = false,
        },
        // T+ Tethering mainline module (shared with netd & system server)
        // netutils_wrapper (for iptables xt_bpf) has access to programs
        {
                .dir = BPFROOT "/netd_shared/",
                .prefix = "netd_shared/",
        },
        // T+ Tethering mainline module (shared with netd & system server)
        // netutils_wrapper has no access, netd has read only access
        {
                .dir = BPFROOT "/netd_readonly/",
                .prefix = "netd_readonly/",
        },
        // T+ Tethering mainline module (shared with system server)
        {
                .dir = BPFROOT "/net_shared/",
                .prefix = "net_shared/",
        },
        // T+ Tethering mainline module (not shared, just network_stack)
        {
                .dir = BPFROOT "/net_private/",
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

            int ret = loadProg(progPath.c_str(), bpfloader_ver, location.prefix);
            if (ret) {
                retVal = ret;
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
    unique_fd fd(open(filename, O_WRONLY | O_CLOEXEC));
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
const char *const uprobestatsBpfLoader =
    "/apex/com.android.uprobestats/bin/uprobestatsbpfload";

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

static bool hasGSM() {
    static string ph = GetProperty("gsm.current.phone-type", "");
    static bool gsm = (ph != "");
    static bool logged = false;
    if (!logged) {
        logged = true;
        ALOGI("hasGSM(gsm.current.phone-type='%s'): %s", ph.c_str(), gsm ? "true" : "false");
    }
    return gsm;
}

static bool isTV() {
    if (hasGSM()) return false;  // TVs don't do GSM

    static string key = GetProperty("ro.oem.key1", "");
    static bool tv = StartsWith(key, "ATV00");
    static bool logged = false;
    if (!logged) {
        logged = true;
        ALOGI("isTV(ro.oem.key1='%s'): %s.", key.c_str(), tv ? "true" : "false");
    }
    return tv;
}

static bool isWear() {
    static string wearSdkStr = GetProperty("ro.cw_build.wear_sdk.version", "");
    static int wearSdkInt = GetIntProperty("ro.cw_build.wear_sdk.version", 0);
    static string buildChars = GetProperty("ro.build.characteristics", "");
    static vector<string> v = Tokenize(buildChars, ",");
    static bool watch = (std::find(v.begin(), v.end(), "watch") != v.end());
    static bool wear = (wearSdkInt > 0) || watch;
    static bool logged = false;
    if (!logged) {
        logged = true;
        ALOGI("isWear(ro.cw_build.wear_sdk.version=%d[%s] ro.build.characteristics='%s'): %s",
              wearSdkInt, wearSdkStr.c_str(), buildChars.c_str(), wear ? "true" : "false");
    }
    return wear;
}

static int libbpfPrint(enum libbpf_print_level lvl, const char *const formatStr,
                       va_list argList) {
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
    char *s = strdup(formatStr ?: "(no format string)");
    int len = strlen(s);
    if (len && s[len - 1] == '\n')
        s[len - 1] = 0;
    LOG_PRI_VA(prio, LOG_TAG, s, argList);
    free(s);
    return 0;
}

static int doLoad(char** argv, char * const envp[]) {
    if (!isAtLeastS) {
        ALOGE("Impossible - not reachable on Android <S.");
        // for safety, we don't fail, this is a just-in-case workaround
        // for any possible busted 'optimized' start everything vendor init hacks on R
        return 0;
    }
    libbpf_set_print(libbpfPrint);

    const bool runningAsRoot = !getuid();  // true iff U QPR3 or V+

    const int first_api_level = GetIntProperty("ro.board.first_api_level", api_level);

    // last in U QPR2 beta1
    const bool has_platform_bpfloader_rc = exists("/system/etc/init/bpfloader.rc");
    // first in U QPR2 beta~2
    const bool has_platform_netbpfload_rc = exists("/system/etc/init/netbpfload.rc");

    // Version of Network BpfLoader depends on the Android OS version
    unsigned int bpfloader_ver = BPFLOADER_MAINLINE_S_VERSION;  // [42u]
    if (isAtLeastT) ++bpfloader_ver;     // [43] BPFLOADER_MAINLINE_T_VERSION
    if (isAtLeastU) ++bpfloader_ver;     // [44] BPFLOADER_MAINLINE_U_VERSION
    if (runningAsRoot) ++bpfloader_ver;  // [45] BPFLOADER_MAINLINE_U_QPR3_VERSION
    if (isAtLeastV) ++bpfloader_ver;     // [46] BPFLOADER_MAINLINE_V_VERSION
    if (isAtLeast25Q2) ++bpfloader_ver;  // [47] BPFLOADER_MAINLINE_25Q2_VERSION

    ALOGI("NetBpfLoad v0.%u (%s) api:%d/%d kver:%07x (%s) libbpf: v%u.%u "
          "uid:%d rc:%d%d",
          bpfloader_ver, argv[0], android_get_device_api_level(), api_level,
          kernelVersion(), describeArch(), libbpf_major_version(),
          libbpf_minor_version(), getuid(), has_platform_bpfloader_rc,
          has_platform_netbpfload_rc);

    if (!has_platform_bpfloader_rc && !has_platform_netbpfload_rc) {
        ALOGE("Unable to find platform's bpfloader & netbpfload init scripts.");
        return 1;
    }

    if (has_platform_bpfloader_rc && has_platform_netbpfload_rc) {
        ALOGE("Platform has *both* bpfloader & netbpfload init scripts.");
        return 1;
    }

    logTetheringApexVersion();

    // both S and T require kernel 4.9 (and eBpf support)
    if (!isAtLeastKernelVersion(4, 9, 0)) {
        ALOGW("Android S & T require kernel 4.9.");
    }

    // U bumps the kernel requirement up to 4.14
    if (isAtLeastU && !isAtLeastKernelVersion(4, 14, 0)) {
        ALOGW("Android U requires kernel 4.14.");
    }

    // V bumps the kernel requirement up to 4.19
    // see also: //system/netd/tests/kernel_test.cpp TestKernel419
    if (isAtLeastV && !isAtLeastKernelVersion(4, 19, 0)) {
        ALOGW("Android V requires kernel 4.19.");
    }

    // 25Q2 bumps the kernel requirement up to 5.4
    // see also: //system/netd/tests/kernel_test.cpp TestKernel54
    if (isAtLeast25Q2 && !isAtLeastKernelVersion(5, 4, 0)) {
        ALOGW("Android 25Q2 requires kernel 5.4.");
    }

    // Technically already required by U, but only enforce on V+
    // see also: //system/netd/tests/kernel_test.cpp TestKernel64Bit
    if (isAtLeastV && isKernel32Bit() && isAtLeastKernelVersion(5, 16, 0)) {
        ALOGE("Android V+ platform with 32 bit kernel version >= 5.16.0 is unsupported");
        if (!isTV()) return 1;
    }

    if (isKernel32Bit() && isAtLeast25Q2) {
        ALOGE("Android 25Q2 requires 64 bit kernel.");
        return 1;
    }

    // 6.6 is highest version supported by Android V, so this is effectively W+ (sdk=36+)
    if (isKernel32Bit() && isAtLeastKernelVersion(6, 7, 0)) {
        ALOGE("Android platform with 32 bit kernel version >= 6.7.0 is unsupported");
        return 1;
    }

    // Various known ABI layout issues, particularly wrt. bpf and ipsec/xfrm.
    if (isAtLeastV && isKernel32Bit() && isX86()) {
        ALOGE("Android V requires X86 kernel to be 64-bit.");
        if (!isTV()) return 1;
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

#undef REQUIRE

        if (bad) {
            ALOGE("Unsupported kernel version (%07x).", kernelVersion());
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
    if (isUserspace32bit() && isAtLeastKernelVersion(6, 2, 0)) {
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
            return 1;
        } else { // x86 since RiscV cannot be 32-bit
            ALOGE("[x86] 64-bit userspace required on 6.2+ kernels.");
            return 1;
        }
    }

    // On handheld, 6.6 is highest version supported by Android V (sdk=35), so this is for sdk=36+
    if (!isArm() && isUserspace32bit() && isAtLeastKernelVersion(6, 7, 0)) {
        ALOGE("64-bit userspace required on 6.7+ kernels.");
        return 1;
    }

    if (isAtLeast25Q2) {
        FILE * f = fopen("/system/etc/init/netbpfload.rc", "re");
        if (!f) {
            ALOGE("failure opening /system/etc/init/netbpfload.rc");
            return 1;
        }
        int y = -1, q = -1, a = -1, b = -1, c = -1;
        int v = fscanf(f, "# %d %d %d %d %d #", &y, &q, &a, &b, &c);
        ALOGI("detected %d of 5: %dQ%d api:%d.%d.%d", v, y, q, a, b, c);
        fclose(f);
        if (v != 5 || y != 2025 || q != 2 || a != 36 || b || c) return 1;
    }

    // Ensure we can determine the Android build type.
    if (!isEng() && !isUser() && !isUserdebug()) {
        ALOGE("Failed to determine the build type: got %s, want 'eng', 'user', or 'userdebug'",
              getBuildType().c_str());
        return 1;
    }

    if (runningAsRoot) {
        // Note: writing this proc file requires being root (always the case on V+)

        // Linux 5.16-rc1 changed the default to 2 (disabled but changeable),
        // but we need 0 (enabled)
        // (this writeFile is known to fail on at least 4.19, but always defaults to 0 on
        // pre-5.13, on 5.13+ it depends on CONFIG_BPF_UNPRIV_DEFAULT_OFF)
        if (writeProcSysFile("/proc/sys/kernel/unprivileged_bpf_disabled", "0\n") &&
            isAtLeastKernelVersion(5, 13, 0)) return 1;
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
        if (location.t_plus && !isAtLeastT) continue;
        if (createSysFsBpfSubDir(location.prefix)) return 1;
    }

    if (isAtLeastT) {
        // Note: there's no actual src dir for fs_bpf_loader .o's,
        // so it is not listed in 'locations[].prefix'.
        // This is because this is primarily meant for triggering genfscon rules,
        // and as such this will likely always be the case.
        // Thus we need to manually create the /sys/fs/bpf/loader subdirectory.
        if (createSysFsBpfSubDir("loader")) return 1;
    }

    // Load all ELF objects, create programs and maps, and pin them
    for (const auto& location : locations) {
        if (location.t_plus && !isAtLeastT) continue;
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
    unique_fd map(
            createMap(BPF_MAP_TYPE_ARRAY, sizeof(key), sizeof(value), 2, 0));
    if (writeToMapEntry(map, &key, &value, BPF_ANY)) {
        ALOGE("Critical kernel bug - failure to write into index 1 of 2 element bpf map array.");
        return 1;
    }

    // on S we haven't created this subdir yet, but we need it for 'mainline_done' flag below
    if (!isAtLeastT && createSysFsBpfSubDir("netd_shared")) return 1;

    // leave a flag that we're done
    if (createSysFsBpfSubDir("netd_shared/mainline_done")) return 1;

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
    return 1;
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

    if (argc == 2 && !strcmp(argv[1], "done")) {
        // we're being re-exec'ed from platform bpfloader to 'finalize' things
        if (!SetProperty("bpf.progs_loaded", "1")) {
            ALOGE("Failed to set bpf.progs_loaded property to 1.");
            return 125;
        }
        ALOGI("success.");
        return 0;
    }

    return android::bpf::doLoad(argv, envp);
}
