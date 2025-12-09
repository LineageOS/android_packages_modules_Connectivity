/*
 * Copyright (C) 2019 The Android Open Source Project
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
#include <linux/if.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <linux/in.h>
#include <linux/in6.h>
#include <linux/ip.h>
#include <linux/ipv6.h>
#include <linux/pkt_cls.h>
#include <linux/tcp.h>
// bionic kernel uapi linux/udp.h header is munged...
#define __kernel_udphdr udphdr
#include <linux/udp.h>
#include <stdbool.h>
#include <stdint.h>

#include "bpf_helpers.h"

// IP flags. (from kernel's include/net/ip.h)
#define IP_CE      0x8000  // Flag: "Congestion" (really reserved 'evil bit')
#define IP_DF      0x4000  // Flag: "Don't Fragment"
#define IP_MF      0x2000  // Flag: "More Fragments"
#define IP_OFFSET  0x1FFF  // "Fragment Offset" part

// IPv6 fragmentation header. (from kernel's include/net/ipv6.h)
struct frag_hdr {
    __u8   nexthdr;
    __u8   reserved;        // always zero
    __be16 frag_off;        // 13 bit offset, 2 bits zero, 1 bit "More Fragments"
    __be32 identification;
};

// ----- Helper functions for offsets to fields -----

// They all assume simple IP packets:
//   - no VLAN ethernet tags
//   - no IPv4 options (see IPV4_HLEN/TCP4_OFFSET/UDP4_OFFSET)
//   - no IPv6 extension headers
//   - no TCP options (see TCP_HLEN)

//#define ETH_HLEN sizeof(struct ethhdr)
#define IP4_HLEN sizeof(struct iphdr)
#define IP6_HLEN sizeof(struct ipv6hdr)
#define TCP_HLEN sizeof(struct tcphdr)
#define UDP_HLEN sizeof(struct udphdr)

// Offsets from beginning of L4 (TCP/UDP) header
#define TCP_OFFSET(field) offsetof(struct tcphdr, field)
#define UDP_OFFSET(field) offsetof(struct udphdr, field)

// Offsets from beginning of L3 (IPv4) header
#define IP4_OFFSET(field) offsetof(struct iphdr, field)
#define IP4_TCP_OFFSET(field) (IP4_HLEN + TCP_OFFSET(field))
#define IP4_UDP_OFFSET(field) (IP4_HLEN + UDP_OFFSET(field))

// Offsets from beginning of L3 (IPv6) header
#define IP6_OFFSET(field) offsetof(struct ipv6hdr, field)
#define IP6_TCP_OFFSET(field) (IP6_HLEN + TCP_OFFSET(field))
#define IP6_UDP_OFFSET(field) (IP6_HLEN + UDP_OFFSET(field))

// Offsets from beginning of L2 (ie. Ethernet) header (which must be present)
#define ETH_IP4_OFFSET(field) (ETH_HLEN + IP4_OFFSET(field))
#define ETH_IP4_TCP_OFFSET(field) (ETH_HLEN + IP4_TCP_OFFSET(field))
#define ETH_IP4_UDP_OFFSET(field) (ETH_HLEN + IP4_UDP_OFFSET(field))
#define ETH_IP6_OFFSET(field) (ETH_HLEN + IP6_OFFSET(field))
#define ETH_IP6_TCP_OFFSET(field) (ETH_HLEN + IP6_TCP_OFFSET(field))
#define ETH_IP6_UDP_OFFSET(field) (ETH_HLEN + IP6_UDP_OFFSET(field))

static uint64_t (*bpf_get_netns_cookie)(void* ctx) = (void*)BPF_FUNC_get_netns_cookie;

// this returns 0 iff skb->sk is NULL
static uint64_t (*bpf_get_socket_cookie)(struct __sk_buff* skb) = (void*)BPF_FUNC_get_socket_cookie;
static uint64_t (*bpf_get_sk_cookie)(struct bpf_sock* sk) = (void*)BPF_FUNC_get_socket_cookie;

static uint32_t (*bpf_get_socket_uid)(struct __sk_buff* skb) = (void*)BPF_FUNC_get_socket_uid;

static long (*bpf_skb_pull_data)(struct __sk_buff* skb, __u32 len) = (void*)BPF_FUNC_skb_pull_data;

static long (*bpf_skb_load_bytes)(const struct __sk_buff* skb, int off, void* to,
                                  int len) = (void*)BPF_FUNC_skb_load_bytes;

static long (*bpf_skb_load_bytes_relative)(const struct __sk_buff* skb, int off, void* to, int len,
                                           int start_hdr) = (void*)BPF_FUNC_skb_load_bytes_relative;

static long (*bpf_skb_store_bytes)(struct __sk_buff* skb, __u32 offset, const void* from, __u32 len,
                                  __u64 flags) = (void*)BPF_FUNC_skb_store_bytes;

static int64_t (*bpf_csum_diff)(__be32* from, __u32 from_size, __be32* to, __u32 to_size,
                                __wsum seed) = (void*)BPF_FUNC_csum_diff;

static int64_t (*bpf_csum_update)(struct __sk_buff* skb, __wsum csum) = (void*)BPF_FUNC_csum_update;

static long (*bpf_skb_change_proto)(struct __sk_buff* skb, __be16 proto,
                                    __u64 flags) = (void*)BPF_FUNC_skb_change_proto;
static long (*bpf_l3_csum_replace)(struct __sk_buff* skb, __u32 offset, __u64 from, __u64 to,
                                   __u64 flags) = (void*)BPF_FUNC_l3_csum_replace;
static long (*bpf_l4_csum_replace)(struct __sk_buff* skb, __u32 offset, __u64 from, __u64 to,
                                   __u64 flags) = (void*)BPF_FUNC_l4_csum_replace;
static long (*bpf_redirect)(__u32 ifindex, __u64 flags) = (void*)BPF_FUNC_redirect;
static long (*bpf_redirect_map)(const struct bpf_map_def* map, __u32 key,
                                __u64 flags) = (void*)BPF_FUNC_redirect_map;

static long (*bpf_skb_change_head)(struct __sk_buff* skb, __u32 head_room,
                                   __u64 flags) = (void*)BPF_FUNC_skb_change_head;
static long (*bpf_skb_adjust_room)(struct __sk_buff* skb, __s32 len_diff, __u32 mode,
                                   __u64 flags) = (void*)BPF_FUNC_skb_adjust_room;

// Android only supports little endian architectures
#define htons(x) (__builtin_constant_p(x) ? ___constant_swab16(x) : __builtin_bswap16(x))
#define htonl(x) (__builtin_constant_p(x) ? ___constant_swab32(x) : __builtin_bswap32(x))
#define ntohs(x) htons(x)
#define ntohl(x) htonl(x)

static inline __always_inline __unused bool is_received_skb(struct __sk_buff* skb) {
    return skb->pkt_type == PACKET_HOST || skb->pkt_type == PACKET_BROADCAST ||
           skb->pkt_type == PACKET_MULTICAST;
}

// try to make the first 'len' header bytes readable/writable via direct packet access
// (note: AFAIK there is no way to ask for only direct packet read without also getting write)
static inline __always_inline void try_make_writable(struct __sk_buff* skb, unsigned len) {
    if (len > skb->len) len = skb->len;
    if (skb->data_end - skb->data < len) bpf_skb_pull_data(skb, len);
}

// anti-compiler-optimizer no-op: explicitly force full calculation of 'v'
//
// The use for this is to force full calculation of a complex arithmetic (likely binary
// bitops) value, and then check the result only once (thus likely reducing the number
// of required conditional jump instructions that badly affect bpf verifier runtime)
//
// The compiler cannot look into the assembly statement, so it doesn't know it does nothing.
// Since the statement takes 'v' as both input and output in a register (+r),
// the compiler must fully calculate the precise value of 'v' before this,
// and must use the (possibly modified) value of 'v' afterwards (thus cannot
// do funky optimizations to use partial results from before the asm).
//
// As this is not flagged 'volatile' this may still be moved out of a loop,
// or even entirely optimized out if 'v' is never used afterwards.
//
// See: https://gcc.gnu.org/onlinedocs/gcc/Extended-Asm.html
#define COMPILER_FORCE_CALCULATION(v) asm ("" : "+r" (v))

struct egress_bool { bool egress; };
#define INGRESS ((struct egress_bool){ .egress = false })
#define EGRESS ((struct egress_bool){ .egress = true })

struct stream_bool { bool down; };
#define UPSTREAM ((struct stream_bool){ .down = false })
#define DOWNSTREAM ((struct stream_bool){ .down = true })

struct rawip_bool { bool rawip; };
#define ETHER ((struct rawip_bool){ .rawip = false })
#define RAWIP ((struct rawip_bool){ .rawip = true })

struct updatetime_bool { bool updatetime; };
#define NO_UPDATETIME ((struct updatetime_bool){ .updatetime = false })
#define UPDATETIME ((struct updatetime_bool){ .updatetime = true })

// Return value for xt_bpf (netfilter match extension) programs
static const int XTBPF_NOMATCH = 0;
static const int XTBPF_MATCH = 1;

static const int BPF_DISALLOW = 0;
static const int BPF_ALLOW = 1;
