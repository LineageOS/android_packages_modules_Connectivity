/*
 * Copyright (C) 2020 The Android Open Source Project
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

/* This file is separate because it's included both by eBPF programs (via include
 * in bpf_helpers.h) and directly by the boot time bpfloader (Loader.cpp).
 */

#include <linux/bpf.h>

// Pull in AID_* constants from //system/core/libcutils/include/private/android_filesystem_config.h
#include <cutils/android_filesystem_config.h>

#ifdef __cplusplus
#include <type_traits>
#endif

/*
 * The bpf_{map,prog}_def structures are compiled for different architectures.
 * Once by the BPF compiler for the BPF architecture, and once by a C++
 * compiler for the native Android architecture for the bpfloader.
 *
 * For things to work, their layout must be the same between the two.
 * The BPF architecture is platform independent ('64-bit LSB bpf').
 * So this effectively means these structures must be the same layout
 * on 6 architectures, all of them little endian:
 *   64-bit BPF, x86_64, arm, riscv  and  32-bit x86 and arm
 *
 * As such for any types we use inside of these structs we must make sure that
 * the size and alignment are the same, so the same amount of padding is used.
 *
 * Currently we only use: bool, enum bpf_map_type and unsigned int.
 * Additionally we use char for padding.
 *
 * !!! WARNING: HERE BE DRAGONS !!!
 *
 * Be particularly careful with 64-bit integers.
 * You will need to manually override their alignment to 8 bytes.
 *
 * To quote some parts of https://gcc.gnu.org/bugzilla/show_bug.cgi?id=69560
 *
 * Some types have weaker alignment requirements when they are structure members.
 *
 * unsigned long long on x86 is such a type.
 *
 * C distinguishes C11 _Alignof (the minimum alignment the type is guaranteed
 * to have in all contexts, so 4, see min_align_of_type) from GNU C __alignof
 * (the normal alignment of the type, so 8).
 *
 * alignof / _Alignof == minimum alignment required by target ABI
 * __alignof / __alignof__ == preferred alignment
 *
 * When in a struct, apparently the minimum alignment is used.
 */

_Static_assert(sizeof(bool) == 1, "sizeof bool != 1");
_Static_assert(__alignof__(bool) == 1, "__alignof__ bool != 1");
_Static_assert(_Alignof(bool) == 1, "_Alignof bool != 1");

_Static_assert(sizeof(char) == 1, "sizeof char != 1");
_Static_assert(__alignof__(char) == 1, "__alignof__ char != 1");
_Static_assert(_Alignof(char) == 1, "_Alignof char != 1");

// This basically verifies that an enum is 'just' a 32-bit int
_Static_assert(sizeof(enum bpf_map_type) == 4, "sizeof enum bpf_map_type != 4");
_Static_assert(__alignof__(enum bpf_map_type) == 4, "__alignof__ enum bpf_map_type != 4");
_Static_assert(_Alignof(enum bpf_map_type) == 4, "_Alignof enum bpf_map_type != 4");

// Linux kernel requires sizeof(int) == 4, sizeof(void*) == sizeof(long), sizeof(long long) == 8
_Static_assert(sizeof(int) == 4, "sizeof int != 4");
_Static_assert(__alignof__(int) == 4, "__alignof__ int != 4");
_Static_assert(_Alignof(int) == 4, "_Alignof int != 4");

_Static_assert(sizeof(unsigned int) == 4, "sizeof unsigned int != 4");
_Static_assert(__alignof__(unsigned int) == 4, "__alignof__ unsigned int != 4");
_Static_assert(_Alignof(unsigned int) == 4, "_Alignof unsigned int != 4");

// We don't currently use any 64-bit types in these structs, so this is purely to document issue.
// Here sizeof & __alignof__ are consistent, but _Alignof is not: compile for 'aosp_cf_x86_phone'
_Static_assert(sizeof(unsigned long long) == 8, "sizeof unsigned long long != 8");
_Static_assert(__alignof__(unsigned long long) == 8, "__alignof__ unsigned long long != 8");
// BPF & everyone else wants 8, but 32-bit x86 wants 4
#if defined(__i386__)
_Static_assert(_Alignof(unsigned long long) == 4, "x86-32 _Alignof unsigned long long != 4");
#else
_Static_assert(_Alignof(unsigned long long) == 8, "_Alignof unsigned long long != 8");
#endif


// for programs:
struct optional_bool { bool optional; };
#define MANDATORY ((struct optional_bool){ .optional = false })
#define OPTIONAL ((struct optional_bool){ .optional = true })


// Length of strings (incl. selinux_context and pin_subdir)
// in the bpf_map_def and bpf_prog_def structs.
#define BPF_DEF_CHAR_ARRAY_SIZE 70  // must be even for alignment sanity

/*
 * Map structure to be used by Android eBPF C programs. The Android eBPF loader
 * uses this structure from eBPF object to create maps at boot time.
 *
 * The eBPF C program should define structure in the maps section using
 * SECTION(".android_maps") otherwise it will be ignored by the eBPF loader.
 *
 * For example:
 *   const struct bpf_map_def SECTION(".android_maps") mymap { .type=... , .key_size=... }
 *
 * See 'bpf_helpers.h' for helpful macros for eBPF program use.
 */
struct bpf_map_def {
    enum bpf_map_type type;
    unsigned int key_size;
    unsigned int value_size;
    unsigned int max_entries;
    unsigned int map_flags;

    // The following are not supported by the Android bpfloader:
    //   unsigned int inner_map_idx;
    //   unsigned int numa_node;

    unsigned int uid;   // uid_t
    unsigned int gid;   // gid_t
    unsigned int mode;  // mode_t

    int bpfloader_min_ver;
    int bpfloader_max_ver;

    // kernelVer must be >= min_kver and < max_kver
    unsigned int min_kver;
    unsigned int max_kver;

    // These are fixed length ASCIIZ strings, padded with null bytes
    char create_location[BPF_DEF_CHAR_ARRAY_SIZE];
    char pin_location[BPF_DEF_CHAR_ARRAY_SIZE];
    unsigned int name_idx;

#ifdef __cplusplus
    const char * name() const { return this->pin_location + this->name_idx; }
#endif
};

#ifdef __cplusplus
static_assert(std::is_pod_v<struct bpf_map_def>);
static_assert(std::is_standard_layout_v<struct bpf_map_def>);
#endif

// This needs to be updated whenever the above structure definition is expanded.
// These asserts are here to make sure we have cross-6-arch consistency.
_Static_assert(sizeof(struct bpf_map_def) == 52 + 2 * BPF_DEF_CHAR_ARRAY_SIZE, "wrong sizeof struct bpf_map_def");
_Static_assert(__alignof__(struct bpf_map_def) == 4, "__alignof__ struct bpf_map_def != 4");
_Static_assert(_Alignof(struct bpf_map_def) == 4, "_Alignof struct bpf_map_def != 4");

struct bpf_prog_def {
    enum bpf_prog_type type;
    enum bpf_attach_type attach_type;

    unsigned int uid;
    unsigned int gid;

    // kernelVer must be >= min_kver and < max_kver
    unsigned int min_kver;
    unsigned int max_kver;

    bool optional;  // program section (ie. function) may fail to load, continue onto next func.

    char pad0[3];  // manually pad up to 4 byte alignment, may be used for extensions in the future

    int bpfloader_min_ver;
    int bpfloader_max_ver;

    char create_location[BPF_DEF_CHAR_ARRAY_SIZE];
    char pin_location[BPF_DEF_CHAR_ARRAY_SIZE];
    unsigned int name_idx;

#ifdef __cplusplus
    const char * name() const { return this->pin_location + this->name_idx; }
#endif
};

#ifdef __cplusplus
static_assert(std::is_pod_v<struct bpf_prog_def>);
static_assert(std::is_standard_layout_v<struct bpf_prog_def>);
#endif

// This needs to be updated whenever the above structure definition is expanded.
// These asserts are here to make sure we have cross-6-arch consistency.
_Static_assert(sizeof(struct bpf_prog_def) == 40 + 2 * BPF_DEF_CHAR_ARRAY_SIZE, "wrong sizeof struct bpf_prog_def");
_Static_assert(__alignof__(struct bpf_prog_def) == 4, "__alignof__ struct bpf_prog_def != 4");
_Static_assert(_Alignof(struct bpf_prog_def) == 4, "_Alignof struct bpf_prog_def != 4");

// Android Mainline BpfLoader version when running on:
#define BPFLOADER_MAINLINE_S_VERSION      3100 // Android S (31)
#define BPFLOADER_MAINLINE_T_VERSION      3300 // Android T (33)
#define BPFLOADER_MAINLINE_U_VERSION      3400 // Android U (34)
#define BPFLOADER_MAINLINE_V_VERSION      3500 // Android V (35)
#define BPFLOADER_MAINLINE_25Q2_VERSION   3600 // Android 25Q2 (36.0)
#define BPFLOADER_MAINLINE_25Q4_VERSION   3610 // Android 25Q4 (36.1)
#define BPFLOADER_MAINLINE_26Q1_VERSION   3612 // Android 26Q1 (36.1+)
#define BPFLOADER_MAINLINE_26Q2_VERSION   3700 // Android 26Q2 (37.0)
