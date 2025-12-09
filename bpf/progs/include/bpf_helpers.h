/* Common BPF helpers to be used by all BPF programs loaded by Android */

#include <linux/bpf.h>
#include <stdbool.h>
#include <stdint.h>

#include "bpf_map_def.h"

/* You should #define BPFLOADER_{MIN/MAX}_VER before #include "bpf_helpers.h"
 * to change which bpfloaders will process the resulting .o file.
 */
#ifndef BPFLOADER_MIN_VER
#error "You must define BPFLOADER_MIN_VER"  // inclusive, ie. >=
#endif

#ifndef BPFLOADER_MAX_VER
#define BPFLOADER_MAX_VER 0x10000u  // exclusive, ie. < v1.0
#endif

/* place things in different elf sections */
#define SECTION(NAME) __attribute__((section(NAME), used))

/* Must be present in every program, example usage:
 *   LICENSE("GPL"); or LICENSE("Apache 2.0");
 */
#define LICENSE(NAME) char _license[] SECTION("license") = (NAME)

// Helpers for writing kernel version specific bpf programs

struct kver_uint { unsigned int kver; };
#define KVER_(v) ((struct kver_uint){ .kver = (v) })
#define KVER(a, b, c) KVER_(((a) << 24) + ((b) << 16) + (c))
#define KVER_4_9  KVER(4, 9, 0)
#define KVER_4_14 KVER(4, 14, 0)
#define KVER_4_19 KVER(4, 19, 0)
#define KVER_5_4  KVER(5, 4, 0)
#define KVER_5_10 KVER(5, 10, 0)
#define KVER_5_15 KVER(5, 15, 0)
#define KVER_6_1  KVER(6, 1, 0)
#define KVER_6_6  KVER(6, 6, 0)
#define KVER_6_12 KVER(6, 12, 0)
#define KVER_INF KVER_(0xFFFFFFFFu)

#define KVER_IS_AT_LEAST(kver, a, b, c) ((kver).kver >= KVER(a, b, c).kver)

// Helpers for writing sdk level specific bpf programs
//
// Note: we choose to follow 'ro.build.version.sdk_full'
// (or just 'sdk' if 'sdk_full' is not available) values,
// multiplied by 100, with 1 added per QPR.
// This will (eventually) match our bpfloader versioning scheme.
//
// This is just for ease of use, really these are only
// ever compared to each other, so they only need to be
// monotonically increasing.
//
// For now this easily suffices for our use case.
//
// Note: 24Q1 is the first trunk stable release,
// and thus where quarters start possibly mattering.
//
// We leave most of these as commented out documentation,
// as it's probably a bad idea to actually use them.

struct sdk_level_uint { unsigned int sdk_level; };
#define SDK_LEVEL_(v) ((struct sdk_level_uint){ .sdk_level = (v) })
//      SDK_LEVEL_NONE   SDK_LEVEL_(0)    // mainline implies S+
#define SDK_LEVEL_S      SDK_LEVEL_(3100) // Android 12     [31]
//      SDK_LEVEL_Sv2    SDK_LEVEL_(3200) // Android 12L    [32]
#define SDK_LEVEL_T      SDK_LEVEL_(3300) // Android 13     [33]
#define SDK_LEVEL_U      SDK_LEVEL_(3400) // Android 14/U   [34]
//      SDK_LEVEL_U_QPR1 SDK_LEVEL_(3401) // Android 14/U QPR1
//      SDK_LEVEL_24Q1   SDK_LEVEL_(3402) // Android 14/U QPR2
//      SDK_LEVEL_24Q2   SDK_LEVEL_(3403) // Android 14/U QPR3
#define SDK_LEVEL_24Q3   SDK_LEVEL_(3500) // Android 15/V   [35]
//      SDK_LEVEL_24Q4   SDK_LEVEL_(3501) // Android 15/V QPR1
//      SDK_LEVEL_25Q1   SDK_LEVEL_(3502) // Android 15/V QPR2
#define SDK_LEVEL_25Q2   SDK_LEVEL_(3600) // Android 16 (B) [36.0]
//      SDK_LEVEL_25Q3   SDK_LEVEL_(3601) // Android 16 QPR
#define SDK_LEVEL_25Q4   SDK_LEVEL_(3610) // Android 16.1   [36.1]
//      SDK_LEVEL_26Q1   SDK_LEVEL_(3611) // Android 16.1 QPR
#define SDK_LEVEL_26Q2   SDK_LEVEL_(3700) // Android 17 (C) [37.0]
//      SDK_LEVEL_26Q3   SDK_LEVEL_(3701) // Android 17 QPR
#define SDK_LEVEL_26Q4   SDK_LEVEL_(3710) // Android 17.1   [37.1]
//      SDK_LEVEL_27Q1   SDK_LEVEL_(3711) // Android 17.1 QPR
#define SDK_LEVEL_27Q2   SDK_LEVEL_(3800) // Android 18     [38.0]

#define SDK_LEVEL_IS_AT_LEAST(lvl, v) ((lvl).sdk_level >= (SDK_LEVEL_##v).sdk_level)

/*
 * BPFFS (ie. /sys/fs/bpf) labelling is as follows:
 *   subdirectory   selinux context      mainline  usecase / usable by
 *   /              fs_bpf               no [*]    core operating system (ie. platform)
 *   /loader        fs_bpf_loader        no, U+    (as yet unused)
 *   /net_private   fs_bpf_net_private   yes, T+   network_stack
 *   /net_shared    fs_bpf_net_shared    yes, T+   network_stack & system_server
 *   /netd_readonly fs_bpf_netd_readonly yes, T+   network_stack & system_server & r/o to netd
 *   /netd_shared   fs_bpf_netd_shared   yes, T+   network_stack & system_server & netd [**]
 *   /tethering     fs_bpf_tethering     yes, S+   network_stack
 *   /vendor        fs_bpf_vendor        no, T+    vendor
 *
 * [*] initial support for bpf was added back in P,
 *     but things worked differently back then with no bpfloader,
 *     and instead netd doing stuff by hand,
 *     bpfloader with pinning into /sys/fs/bpf was (I believe) added in Q
 *     (and was definitely there in R).
 *
 * [**] additionally bpf programs are accessible to netutils_wrapper
 *      for use by iptables xt_bpf extensions.
 *
 * See cs/p:aosp-master%20-file:prebuilts/%20file:genfs_contexts%20"genfscon%20bpf"
 */

#define IS_VALID_PIN_DIR(min_loader, pin_subdir) \
    ( \
        !__builtin_strcmp(pin_subdir, "tethering") || \
        (min_loader >= BPFLOADER_MAINLINE_T_VERSION) && \
            ( \
                !__builtin_strcmp(pin_subdir, "net_private")   || \
                !__builtin_strcmp(pin_subdir, "net_shared")    || \
                !__builtin_strcmp(pin_subdir, "netd_readonly") || \
                !__builtin_strcmp(pin_subdir, "netd_shared")   || \
                !__builtin_strcmp(pin_subdir, "loader") \
            ) \
    )

#define IS_EMPTY_STRING(s) !__builtin_strcmp(s, "")

#define VALIDATE_SELINUX_CONTEXT(min_loader, pin_subdir) \
    _Static_assert(IS_EMPTY_STRING(pin_subdir) \
                || IS_VALID_PIN_DIR(min_loader, pin_subdir), pin_subdir " is invalid"); \
    _Static_assert(IS_EMPTY_STRING(pin_subdir) \
                || (min_loader >= BPFLOADER_MAINLINE_T_VERSION), "selinux_context requires T+")

#define VALIDATE_PIN_DIR(min_loader, pin_subdir) \
    _Static_assert(IS_VALID_PIN_DIR(min_loader, pin_subdir), pin_subdir " is invalid")

#define CREATE_LOCATION(selinux_context) \
    __builtin_choose_expr(IS_EMPTY_STRING(selinux_context), "", "/sys/fs/bpf/" selinux_context "/tmp")

/*
 * Helper functions called from eBPF programs written in C. These are
 * implemented in the kernel sources.
 */

/* generic functions */

/*
 * Type-unsafe bpf map functions - avoid if possible.
 *
 * Using these it is possible to pass in keys/values of the wrong type/size,
 * or, for 'bpf_map_lookup_elem_unsafe' receive into a pointer to the wrong type.
 * You will not get a compile time failure, and for certain types of errors you
 * might not even get a failure from the kernel's ebpf verifier during program load,
 * instead stuff might just not work right at runtime.
 *
 * Instead please use:
 *   DEFINE_BPF_MAP(foo_map, TYPE, KeyType, ValueType, num_entries)
 * where TYPE can be something like HASH or ARRAY, and num_entries is an integer.
 *
 * This defines the map (hence this should not be used in a header file included
 * from multiple locations) and provides type safe accessors:
 *   ValueType * bpf_foo_map_lookup_elem(const KeyType *)
 *   int bpf_foo_map_update_elem(const KeyType *, const ValueType *, flags)
 *   int bpf_foo_map_delete_elem(const KeyType *)
 *
 * This will make sure that if you change the type of a map you'll get compile
 * errors at any spots you forget to update with the new type.
 *
 * Note: these all take pointers to const map because from the C/eBPF point of view
 * the map struct is really just a readonly map definition of the in kernel object.
 * Runtime modification of the map defining struct is meaningless, since
 * the contents is only ever used during bpf program loading & map creation
 * by the bpf loader, and not by the eBPF program itself.
 */
static void* (*bpf_map_lookup_elem_unsafe)(const void* map,
                                           const void* key) = (void*)BPF_FUNC_map_lookup_elem;
static long (*bpf_map_update_elem_unsafe)(const void* map, const void* key,
                                          const void* value, unsigned long long flags) = (void*)
        BPF_FUNC_map_update_elem;
static long (*bpf_map_delete_elem_unsafe)(const void* map,
                                          const void* key) = (void*)BPF_FUNC_map_delete_elem;
static long (*bpf_ringbuf_output_unsafe)(const void* ringbuf,
                                         const void* data, __u64 size, __u64 flags) = (void*)
        BPF_FUNC_ringbuf_output;
static void* (*bpf_ringbuf_reserve_unsafe)(const void* ringbuf,
                                           __u64 size, __u64 flags) = (void*)
        BPF_FUNC_ringbuf_reserve;
static void (*bpf_ringbuf_submit_unsafe)(const void* data, __u64 flags) = (void*)
        BPF_FUNC_ringbuf_submit;
static void* (*bpf_sk_storage_get_unsafe) (const void* sk_storage, const void* sk,
                                           const void* value, unsigned long long flags) = (void*)
        BPF_FUNC_sk_storage_get;
static long (*bpf_sk_storage_delete_unsafe) (const void* sk_storage,
                                             const void* sk) = (void*) BPF_FUNC_sk_storage_delete;

#define BPF_ANNOTATE_KV_PAIR(name, type_key, type_val)  \
        struct ____btf_map_##name {                     \
                type_key key;                           \
                type_val value;                         \
        };                                              \
        struct ____btf_map_##name                       \
        __attribute__ ((section(".maps." #name), used)) \
                ____btf_map_##name = { }

#define ABSOLUTE(x) ((x) < 0 ? -(x) : (x))

#define BPF_MAP_TYPE_MMAPABLE_ARRAY BPF_MAP_TYPE_ARRAY

#define DEFAULT_FLAGS_FOR_BPF_MAP_TYPE_ARRAY		0
#define DEFAULT_FLAGS_FOR_BPF_MAP_TYPE_DEVMAP_HASH	0
#define DEFAULT_FLAGS_FOR_BPF_MAP_TYPE_HASH		0
#define DEFAULT_FLAGS_FOR_BPF_MAP_TYPE_LPM_TRIE		BPF_F_NO_PREALLOC
#define DEFAULT_FLAGS_FOR_BPF_MAP_TYPE_LRU_HASH		0
#define DEFAULT_FLAGS_FOR_BPF_MAP_TYPE_MMAPABLE_ARRAY	BPF_F_MMAPABLE
#define DEFAULT_FLAGS_FOR_BPF_MAP_TYPE_PERCPU_ARRAY	0
#define DEFAULT_FLAGS_FOR_BPF_MAP_TYPE_RINGBUF		0
#define DEFAULT_FLAGS_FOR_BPF_MAP_TYPE_SK_STORAGE	BPF_F_NO_PREALLOC

#define DEFAULT_BPF_MAP_FLAGS(TYPE, num_entries, mapflags) \
    (DEFAULT_FLAGS_FOR_BPF_MAP_TYPE_##TYPE | ((num_entries) < 0 ? BPF_F_NO_PREALLOC : 0) | (mapflags))

#define DEFINE_BPF_MAP_BASE(the_map, TYPE, keysize, valuesize, num_entries, usr, grp, md,       \
                            selinux, pindir, minkver, maxkver, minloader, maxloader, mapflags)  \
    VALIDATE_SELINUX_CONTEXT(minloader, selinux);                                               \
    VALIDATE_PIN_DIR(minloader, pindir);                                                        \
    const struct bpf_map_def SECTION(".android_maps") the_map##_def = {                         \
        .type = BPF_MAP_TYPE_##TYPE,                                                            \
        .key_size = (keysize),                                                                  \
        .value_size = (valuesize),                                                              \
        .max_entries = ABSOLUTE(num_entries),                                                   \
        .map_flags = DEFAULT_BPF_MAP_FLAGS(TYPE, num_entries, mapflags),                        \
        .uid = (usr),                                                                           \
        .gid = (grp),                                                                           \
        .mode = (md),                                                                           \
        .bpfloader_min_ver = (minloader),                                                       \
        .bpfloader_max_ver = (maxloader),                                                       \
        .min_kver = (minkver).kver,                                                             \
        .max_kver = (maxkver).kver,                                                             \
        .create_location = CREATE_LOCATION(selinux),                                            \
        .pin_location = "/sys/fs/bpf/" pindir "/map_" BPF_OBJ_NAME "_" #the_map "\0",           \
        .name_idx = __builtin_strlen("/sys/fs/bpf/" pindir "/map_" BPF_OBJ_NAME "_"),           \
    };

#define __uint(name, val) int (*name)[val]
#define __type(name, val) typeof(val) *name

#define DEFINE_LIBBPF_MAP(the_map, TYPE, KeyType, ValueType, num_entries, mapflags) \
    struct {                                                                        \
        __uint(type, BPF_MAP_TYPE_##TYPE);                                          \
        __uint(map_flags, DEFAULT_BPF_MAP_FLAGS(TYPE, num_entries, mapflags));      \
        __type(key, KeyType);                                                       \
        __type(value, ValueType);                                                   \
        __uint(max_entries, ABSOLUTE(num_entries));                                 \
    } the_map SECTION(".maps");

#define DEFINE_LIBBPF_RINGBUF(the_map, num_entries)  \
    struct {                                         \
        __uint(type, BPF_MAP_TYPE_RINGBUF);          \
        __uint(max_entries, ABSOLUTE(num_entries));  \
    } the_map SECTION(".maps");

// Type safe macro to declare a ring buffer and related output functions.
// Compatibility:
// * BPF ring buffers are only available kernels 5.8 and above. Any program
//   accessing the ring buffer should set a program level min_kver >= 5.10,
//   since 5.10 is the next LTS version.
// * The definition below sets a map min_kver of 5.10 which requires targeting
//   a BPFLOADER_MIN_VER >= BPFLOADER_S_VERSION.
#define DEFINE_BPF_RINGBUF_EXT(the_map, ValueType, size_bytes, usr, grp, md,   \
                               selinux, pindir, min_loader, max_loader)        \
    DEFINE_BPF_MAP_BASE(the_map, RINGBUF, 0, 0, size_bytes, usr, grp, md,      \
                        selinux, pindir, KVER_5_10, KVER_INF,                  \
                        min_loader, max_loader, 0);                            \
    DEFINE_LIBBPF_RINGBUF(the_map, size_bytes);                                \
                                                                               \
    _Static_assert((size_bytes) >= 4096, "min 4 kiB ringbuffer size");         \
    _Static_assert((size_bytes) <= 0x10000000, "max 256 MiB ringbuffer size"); \
    _Static_assert(((size_bytes) & ((size_bytes) - 1)) == 0,                   \
                   "ring buffer size must be a power of two");                 \
                                                                               \
    static inline __always_inline __unused int bpf_##the_map##_output(         \
            const ValueType* v) {                                              \
        return bpf_ringbuf_output_unsafe(&the_map, v, sizeof(*v), 0);          \
    }                                                                          \
                                                                               \
    static inline __always_inline __unused                                     \
            ValueType* bpf_##the_map##_reserve() {                             \
        return bpf_ringbuf_reserve_unsafe(&the_map, sizeof(ValueType), 0);     \
    }                                                                          \
                                                                               \
    static inline __always_inline __unused void bpf_##the_map##_submit(        \
            const ValueType* v) {                                              \
        bpf_ringbuf_submit_unsafe(v, 0);                                       \
    }

#define DEFINE_BPF_RINGBUF(the_map, ValueType, size_bytes, usr, grp, md)            \
    DEFINE_BPF_RINGBUF_EXT(the_map, ValueType, size_bytes, usr, grp, md,            \
                           DEFAULT_BPF_MAP_SELINUX_CONTEXT, DEFAULT_BPF_PIN_SUBDIR, \
                           BPFLOADER_MIN_VER, BPFLOADER_MAX_VER)

// Type safe macro to declare a sk storage and related accessor functions.
// BPF_MAP_TYPE_SK_STORAGE was introduced in kernel 5.2 but this map requires BTF and
// BTF is enabled on kernel 5.10 or higher.
#define DEFINE_BPF_SK_STORAGE_EXT(the_map, ValueType, usr, grp, md, selinux, pindir,    \
                                  min_loader, max_loader, mapFlags)                     \
    DEFINE_BPF_MAP_BASE(the_map, SK_STORAGE, sizeof(uint32_t), sizeof(ValueType),       \
                        0, usr, grp, md, selinux, pindir,                               \
                        KVER_5_10, KVER_INF, min_loader, max_loader, mapFlags);         \
    DEFINE_LIBBPF_MAP(the_map, SK_STORAGE, uint32_t, ValueType, 0, mapFlags);           \
    BPF_ANNOTATE_KV_PAIR(the_map, uint32_t, ValueType);                                 \
                                                                                        \
    static inline __always_inline __unused ValueType* bpf_##the_map##_get(              \
            const struct bpf_sock* sk, const ValueType* v, unsigned long long flags) {  \
        return bpf_sk_storage_get_unsafe(&the_map, sk, v, flags);                       \
    };                                                                                  \
                                                                                        \
    static inline __always_inline __unused int bpf_##the_map##_delete(                  \
            const struct bpf_sock* sk) {                                                \
        return bpf_sk_storage_delete_unsafe(&the_map, sk);                              \
    };

#define DEFINE_BPF_SK_STORAGE(the_map, TypeOfValue)                          \
    DEFINE_BPF_SK_STORAGE_EXT(the_map, TypeOfValue,                          \
                              AID_ROOT, AID_NET_BW_ACCT, 0060, "net_shared", \
                              DEFAULT_BPF_PIN_SUBDIR,                        \
                              BPFLOADER_MIN_VER, BPFLOADER_MAX_VER, 0)

/* There exist buggy kernels with pre-T OS, that due to
 * kernel patch "[ALPS05162612] bpf: fix ubsan error"
 * do not support userspace writes into non-zero index of bpf map arrays.
 *
 * We use this assert to prevent us from being able to define such a map.
 */

#ifdef THIS_BPF_PROGRAM_IS_FOR_TEST_PURPOSES_ONLY
#define BPF_MAP_ASSERT_OK(type, entries, mode)
#elif BPFLOADER_MIN_VER >= BPFLOADER_MAINLINE_T_VERSION
#define BPF_MAP_ASSERT_OK(type, entries, mode)
#else
#define BPF_MAP_ASSERT_OK(type, entries, mode) \
  _Static_assert(((type) != BPF_MAP_TYPE_ARRAY) || ((entries) <= 1) || !((mode) & 0222), \
  "Writable arrays with more than 1 element not supported on pre-T devices.")
#endif

/* type safe macro to declare a map and related accessor functions */
#define DEFINE_BPF_MAP_EXT(the_map, TYPE, KeyType, ValueType, num_entries, usr, grp, md,         \
                           selinux, pindir, min_loader, max_loader, mapFlags)                    \
  DEFINE_BPF_MAP_BASE(the_map, TYPE, sizeof(KeyType), sizeof(ValueType),                         \
                      num_entries, usr, grp, md, selinux, pindir,                                \
                      KVER_4_9, KVER_INF, min_loader, max_loader, mapFlags);                     \
    DEFINE_LIBBPF_MAP(the_map, TYPE, KeyType, ValueType, num_entries, mapFlags);                 \
    BPF_MAP_ASSERT_OK(BPF_MAP_TYPE_##TYPE, (num_entries), (md));                                 \
    _Static_assert(sizeof(KeyType) < 1024, "aosp/2370288 requires < 1024 byte keys");            \
    _Static_assert(sizeof(ValueType) < 65536, "aosp/2370288 requires < 65536 byte values");      \
    BPF_ANNOTATE_KV_PAIR(the_map, KeyType, ValueType);                                           \
                                                                                                 \
    static inline __always_inline __unused ValueType* bpf_##the_map##_lookup_elem(               \
            const KeyType* k) {                                                                  \
        return bpf_map_lookup_elem_unsafe(&the_map, k);                                          \
    };                                                                                           \
                                                                                                 \
    static inline __always_inline __unused int bpf_##the_map##_update_elem(                      \
            const KeyType* k, const ValueType* v, unsigned long long flags) {                    \
        return bpf_map_update_elem_unsafe(&the_map, k, v, flags);                                \
    };                                                                                           \
                                                                                                 \
    static inline __always_inline __unused int bpf_##the_map##_delete_elem(const KeyType* k) {   \
        return bpf_map_delete_elem_unsafe(&the_map, k);                                          \
    };

#ifndef BPF_OBJ_NAME
#error "Must define BPF_OBJ_NAME"
#endif

#ifndef DEFAULT_BPF_MAP_SELINUX_CONTEXT
#define DEFAULT_BPF_MAP_SELINUX_CONTEXT ""
#endif

#ifndef DEFAULT_BPF_PIN_SUBDIR
#error "Must define DEFAULT_BPF_PIN_SUBDIR"
#endif

#ifndef DEFAULT_BPF_MAP_UID
#define DEFAULT_BPF_MAP_UID AID_ROOT
#endif

// for maps not meant to be accessed from userspace
#define DEFINE_BPF_MAP_KERNEL_INTERNAL(the_map, TYPE, KeyType, ValueType, num_entries)           \
    DEFINE_BPF_MAP_EXT(the_map, TYPE, KeyType, ValueType, num_entries, AID_ROOT, AID_ROOT, 0000, \
                       "loader", DEFAULT_BPF_PIN_SUBDIR, BPFLOADER_MIN_VER, BPFLOADER_MAX_VER, 0)

#define DEFINE_BPF_MAP_UGM(the_map, TYPE, KeyType, ValueType, num_entries, usr, grp, md) \
    DEFINE_BPF_MAP_EXT(the_map, TYPE, KeyType, ValueType, num_entries, usr, grp, md,     \
                       DEFAULT_BPF_MAP_SELINUX_CONTEXT, DEFAULT_BPF_PIN_SUBDIR,          \
                       BPFLOADER_MIN_VER, BPFLOADER_MAX_VER, 0)

#define DEFINE_BPF_MAP(the_map, TYPE, KeyType, ValueType, num_entries) \
    DEFINE_BPF_MAP_UGM(the_map, TYPE, KeyType, ValueType, num_entries, \
                       DEFAULT_BPF_MAP_UID, AID_ROOT, 0600)

#define DEFINE_BPF_MAP_RO(the_map, TYPE, KeyType, ValueType, num_entries, gid) \
    DEFINE_BPF_MAP_UGM(the_map, TYPE, KeyType, ValueType, num_entries, \
                       DEFAULT_BPF_MAP_UID, gid, 0440)

#define DEFINE_BPF_MAP_GWO(the_map, TYPE, KeyType, ValueType, num_entries, gid) \
    DEFINE_BPF_MAP_UGM(the_map, TYPE, KeyType, ValueType, num_entries, \
                       DEFAULT_BPF_MAP_UID, gid, 0620)

#define DEFINE_BPF_MAP_GRO(the_map, TYPE, KeyType, ValueType, num_entries, gid) \
    DEFINE_BPF_MAP_UGM(the_map, TYPE, KeyType, ValueType, num_entries, \
                       DEFAULT_BPF_MAP_UID, gid, 0640)

#define DEFINE_BPF_MAP_GRW(the_map, TYPE, KeyType, ValueType, num_entries, gid) \
    DEFINE_BPF_MAP_UGM(the_map, TYPE, KeyType, ValueType, num_entries, \
                       DEFAULT_BPF_MAP_UID, gid, 0660)

// idea from Linux include/linux/compiler_types.h (eBPF is always a 64-bit arch)
#define NATIVE_WORD(t) ((sizeof(t) == 1) || (sizeof(t) == 2) || (sizeof(t) == 4) || (sizeof(t) == 8))

// simplified from Linux include/asm-generic/rwonce.h
#define READ_ONCE(x) \
  ({ \
    _Static_assert(NATIVE_WORD(x), "READ_ONCE requires a native word size"); \
    (*(const volatile typeof(x) *)&(x)) \
  })

#define WRITE_ONCE(x, value) \
  do { \
    _Static_assert(NATIVE_WORD(x), "WRITE_ONCE requires a native word size"); \
    *(volatile typeof(x) *)&(x) = (value); \
  } while (0)

// LLVM eBPF builtins: they directly generate BPF_LD_ABS/BPF_LD_IND (skb may be ignored?)
unsigned long long load_byte(void* skb, unsigned long long off) asm("llvm.bpf.load.byte");
unsigned long long load_half(void* skb, unsigned long long off) asm("llvm.bpf.load.half");
unsigned long long load_word(void* skb, unsigned long long off) asm("llvm.bpf.load.word");

static long (*bpf_probe_read)(void* dst, int size, void* unsafe_ptr) = (void*) BPF_FUNC_probe_read;
static long (*bpf_probe_read_str)(void* dst, int size, void* unsafe_ptr) = (void*) BPF_FUNC_probe_read_str;
static long (*bpf_probe_read_user)(void* dst, int size, const void* unsafe_ptr) = (void*)BPF_FUNC_probe_read_user;
static long (*bpf_probe_read_user_str)(void* dst, int size, const void* unsafe_ptr) = (void*) BPF_FUNC_probe_read_user_str;
static unsigned long long (*bpf_ktime_get_ns)(void) = (void*) BPF_FUNC_ktime_get_ns;
static unsigned long long (*bpf_ktime_get_boot_ns)(void) = (void*)BPF_FUNC_ktime_get_boot_ns;
static unsigned long long (*bpf_get_current_pid_tgid)(void) = (void*) BPF_FUNC_get_current_pid_tgid;
static unsigned long long (*bpf_get_current_uid_gid)(void) = (void*) BPF_FUNC_get_current_uid_gid;
static unsigned long long (*bpf_get_smp_processor_id)(void) = (void*) BPF_FUNC_get_smp_processor_id;
static long (*bpf_get_stackid)(void* ctx, void* map, uint64_t flags) = (void*) BPF_FUNC_get_stackid;
static long (*bpf_get_current_comm)(void* buf, uint32_t buf_size) = (void*) BPF_FUNC_get_current_comm;
// bpf_sk_fullsock requires 5.1+ kernel
static struct bpf_sock* (*bpf_sk_fullsock)(struct bpf_sock* sk) = (void*) BPF_FUNC_sk_fullsock;

// GPL only:
static long (*bpf_trace_printk)(const char* fmt, int fmt_size, ...) = (void*) BPF_FUNC_trace_printk;
#define bpf_printf(s, n...) bpf_trace_printk(s, sizeof(s), ## n)
// Note: bpf only supports up to 3 arguments, log via: bpf_printf("msg %d %d %d", 1, 2, 3);
// and read via the blocking: sudo cat /sys/kernel/debug/tracing/trace_pipe

#define BPF_PROG_TYPE_bind4             BPF_PROG_TYPE_CGROUP_SOCK_ADDR
#define BPF_PROG_TYPE_bind6             BPF_PROG_TYPE_CGROUP_SOCK_ADDR
#define BPF_PROG_TYPE_cgroupskb         BPF_PROG_TYPE_CGROUP_SKB
#define BPF_PROG_TYPE_cgroupsock        BPF_PROG_TYPE_CGROUP_SOCK
#define BPF_PROG_TYPE_cgroupsockcreate  BPF_PROG_TYPE_CGROUP_SOCK
#define BPF_PROG_TYPE_cgroupsockrelease BPF_PROG_TYPE_CGROUP_SOCK
#define BPF_PROG_TYPE_connect4          BPF_PROG_TYPE_CGROUP_SOCK_ADDR
#define BPF_PROG_TYPE_connect6          BPF_PROG_TYPE_CGROUP_SOCK_ADDR
#define BPF_PROG_TYPE_egress            BPF_PROG_TYPE_CGROUP_SKB
#define BPF_PROG_TYPE_getsockopt        BPF_PROG_TYPE_CGROUP_SOCKOPT
#define BPF_PROG_TYPE_ingress           BPF_PROG_TYPE_CGROUP_SKB
#define BPF_PROG_TYPE_postbind4         BPF_PROG_TYPE_CGROUP_SOCK
#define BPF_PROG_TYPE_postbind6         BPF_PROG_TYPE_CGROUP_SOCK
#define BPF_PROG_TYPE_recvmsg4          BPF_PROG_TYPE_CGROUP_SOCK_ADDR
#define BPF_PROG_TYPE_recvmsg6          BPF_PROG_TYPE_CGROUP_SOCK_ADDR
#define BPF_PROG_TYPE_schedact          BPF_PROG_TYPE_SCHED_ACT
#define BPF_PROG_TYPE_schedcls          BPF_PROG_TYPE_SCHED_CLS
#define BPF_PROG_TYPE_sendmsg4          BPF_PROG_TYPE_CGROUP_SOCK_ADDR
#define BPF_PROG_TYPE_sendmsg6          BPF_PROG_TYPE_CGROUP_SOCK_ADDR
#define BPF_PROG_TYPE_setsockopt        BPF_PROG_TYPE_CGROUP_SOCKOPT
#define BPF_PROG_TYPE_skfilter          BPF_PROG_TYPE_SOCKET_FILTER
#define BPF_PROG_TYPE_sockops           BPF_PROG_TYPE_SOCK_OPS
#define BPF_PROG_TYPE_sysctl            BPF_PROG_TYPE_CGROUP_SYSCTL
#define BPF_PROG_TYPE_xdp               BPF_PROG_TYPE_XDP

#define BPF_PROG_ATTACH_TYPE_DEFAULT BPF_CGROUP_INET_INGRESS

#define BPF_PROG_ATTACH_TYPE_bind4             BPF_CGROUP_INET4_BIND
#define BPF_PROG_ATTACH_TYPE_bind6             BPF_CGROUP_INET6_BIND
#define BPF_PROG_ATTACH_TYPE_cgroupskb         BPF_PROG_ATTACH_TYPE_DEFAULT
#define BPF_PROG_ATTACH_TYPE_cgroupsock        BPF_PROG_ATTACH_TYPE_DEFAULT
#define BPF_PROG_ATTACH_TYPE_cgroupsockcreate  BPF_CGROUP_INET_SOCK_CREATE
#define BPF_PROG_ATTACH_TYPE_cgroupsockrelease BPF_CGROUP_INET_SOCK_RELEASE
#define BPF_PROG_ATTACH_TYPE_connect4          BPF_CGROUP_INET4_CONNECT
#define BPF_PROG_ATTACH_TYPE_connect6          BPF_CGROUP_INET6_CONNECT
#define BPF_PROG_ATTACH_TYPE_egress            BPF_CGROUP_INET_EGRESS
#define BPF_PROG_ATTACH_TYPE_getsockopt        BPF_CGROUP_GETSOCKOPT
#define BPF_PROG_ATTACH_TYPE_ingress           BPF_CGROUP_INET_INGRESS
#define BPF_PROG_ATTACH_TYPE_postbind4         BPF_CGROUP_INET4_POST_BIND
#define BPF_PROG_ATTACH_TYPE_postbind6         BPF_CGROUP_INET6_POST_BIND
#define BPF_PROG_ATTACH_TYPE_recvmsg4          BPF_CGROUP_UDP4_RECVMSG
#define BPF_PROG_ATTACH_TYPE_recvmsg6          BPF_CGROUP_UDP6_RECVMSG
#define BPF_PROG_ATTACH_TYPE_schedact          BPF_PROG_ATTACH_TYPE_DEFAULT
#define BPF_PROG_ATTACH_TYPE_schedcls          BPF_PROG_ATTACH_TYPE_DEFAULT
#define BPF_PROG_ATTACH_TYPE_sendmsg4          BPF_CGROUP_UDP4_SENDMSG
#define BPF_PROG_ATTACH_TYPE_sendmsg6          BPF_CGROUP_UDP6_SENDMSG
#define BPF_PROG_ATTACH_TYPE_setsockopt        BPF_CGROUP_SETSOCKOPT
#define BPF_PROG_ATTACH_TYPE_skfilter          BPF_PROG_ATTACH_TYPE_DEFAULT
#define BPF_PROG_ATTACH_TYPE_sockops           BPF_CGROUP_SOCK_OPS
#define BPF_PROG_ATTACH_TYPE_sysctl            BPF_CGROUP_SYSCTL
#define BPF_PROG_ATTACH_TYPE_xdp               BPF_PROG_ATTACH_TYPE_DEFAULT

#define DEFINE_BPF_PROG_EXT(TYPE, NAME, VER, prog_uid, prog_gid, min_kv, max_kv,              \
                            min_loader, max_loader, opt, selinux, pindir)                     \
    VALIDATE_SELINUX_CONTEXT(min_loader, selinux);                                            \
    VALIDATE_PIN_DIR(min_loader, pindir);                                                     \
    const struct bpf_prog_def SECTION(".android_progs") TYPE##_##NAME##_##VER##_def = {       \
        .type = BPF_PROG_TYPE_##TYPE,                                                         \
        .attach_type = BPF_PROG_ATTACH_TYPE_##TYPE,                                           \
        .uid = (prog_uid),                                                                    \
        .gid = (prog_gid),                                                                    \
        .min_kver = (KVER_##min_kv).kver,                                                     \
        .max_kver = (KVER_##max_kv).kver,                                                     \
        .optional = (opt).optional,                                                           \
        .bpfloader_min_ver = (min_loader),                                                    \
        .bpfloader_max_ver = (max_loader),                                                    \
        .create_location = CREATE_LOCATION(selinux),                                          \
        .pin_location = "/sys/fs/bpf/" pindir "/prog_" BPF_OBJ_NAME "_" #TYPE "_" #NAME "\0", \
        .name_idx = __builtin_strlen("/sys/fs/bpf/" pindir "/prog_" BPF_OBJ_NAME "_"),        \
    };                                                                                        \
    SECTION(#TYPE "/" #NAME "$" #VER)                                                         \
    long TYPE##_##NAME##_##VER

#define DEFINE_BPF_PROG_KVER_RANGE_OPT(TYPE, NAME, VER, prog_gid, min_kv, max_kv, opt) \
    DEFINE_BPF_PROG_EXT(TYPE, NAME, VER, AID_ROOT, prog_gid, min_kv, max_kv,           \
                        BPFLOADER_MIN_VER, BPFLOADER_MAX_VER, opt,                     \
                        DEFAULT_BPF_MAP_SELINUX_CONTEXT, DEFAULT_BPF_PIN_SUBDIR)

// Programs (here used in the sense of functions/sections) marked optional are allowed to fail
// to load (for example due to missing kernel patches).
// The bpfloader will just ignore these failures and continue processing the next section.
//
// A non-optional program (function/section) failing to load causes a failure and aborts
// processing of the entire .o, if the .o is additionally marked critical, this will result
// in the entire bpfloader process terminating with a failure and not setting the bpf.progs_loaded
// system property.  This in turn results in waitForProgsLoaded() never finishing.
//
// ie. a non-optional program in a critical .o is mandatory for kernels matching the min/max kver.

// programs requiring a kernel version >= min_kv && < max_kv
#define DEFINE_BPF_PROG_KVER_RANGE(TYPE, NAME, VER, prog_gid, min_kv, max_kv) \
    DEFINE_BPF_PROG_KVER_RANGE_OPT(TYPE, NAME, VER, prog_gid, min_kv, max_kv, MANDATORY)
#define DEFINE_OPTIONAL_BPF_PROG_KVER_RANGE(TYPE, NAME, VER, prog_gid, min_kv, max_kv)  \
    DEFINE_BPF_PROG_KVER_RANGE_OPT(TYPE, NAME, VER, prog_gid, min_kv, max_kv, OPTIONAL)

// programs requiring a kernel version >= min_kv
#define DEFINE_BPF_PROG_KVER(TYPE, NAME, VER, prog_gid, min_kv)                 \
    DEFINE_BPF_PROG_KVER_RANGE_OPT(TYPE, NAME, VER, prog_gid, min_kv, INF, MANDATORY)
#define DEFINE_OPTIONAL_BPF_PROG_KVER(TYPE, NAME, VER, prog_gid, min_kv)        \
    DEFINE_BPF_PROG_KVER_RANGE_OPT(TYPE, NAME, VER, prog_gid, min_kv, INF, OPTIONAL)

// programs with no kernel version requirements
#define DEFINE_BPF_PROG(TYPE, NAME, VER, prog_gid) \
    DEFINE_BPF_PROG_KVER_RANGE_OPT(TYPE, NAME, VER, prog_gid, 4_9, INF, MANDATORY)
#define DEFINE_OPTIONAL_BPF_PROG(TYPE, NAME, VER, prog_gid) \
    DEFINE_BPF_PROG_KVER_RANGE_OPT(TYPE, NAME, VER, prog_gid, 4_9, INF, OPTIONAL)
