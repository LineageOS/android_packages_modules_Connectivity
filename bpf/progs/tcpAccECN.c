/*
 * Copyright (C) 2025 Samsung Electronics.
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
 *
 * Authors: Jayendra Reddy Kovvuri, Madhan Raj Kanagarathinam, Sandeep Irlanki
 *
 * Filename: tcpAccECN.c
 * Description: eBPF-based implementation of AccECN for IPv4 and IPv6 TCP connections.
 *              Includes separate handling for Ethernet and raw IP packets.
 */

#include <linux/bpf.h>
#include <linux/filter.h>
#include <linux/if.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <linux/in.h>
#include <linux/in6.h>
#include <linux/ip.h>
#include <linux/ipv6.h>
#include <linux/pkt_cls.h>
#include <linux/tcp.h>
#include <stdint.h>

// The resulting maps/programs need to load on Android 26Q2+
#undef BPFLOADER_MIN_VER
#define BPFLOADER_MIN_VER BPFLOADER_MAINLINE_26Q2_VERSION

#include "bpf_net_helpers.h"

#define TCP_FLAGS_OFF 12
#define IP4_TCP_FLAGS_OFF (sizeof(struct iphdr) + TCP_FLAGS_OFF)
#define IP6_TCP_FLAGS_OFF (sizeof(struct ipv6hdr) + TCP_FLAGS_OFF)

#define ETH_IP4_TCP_FLAGS_OFF (ETH_HLEN + IP4_TCP_FLAGS_OFF)
#define ETH_IP6_TCP_FLAGS_OFF (ETH_HLEN + IP6_TCP_FLAGS_OFF)

#define CUSTOM_TCP_OPTION_KIND 174
#define CUSTOM_TCP_OPTION_SIZE 11
#define TCPHDR_SYN 0x02

// an LRU map discards the least recently used entry when it is full.
#define L4S_ACCECN_MAP_SIZE 128
DEFINE_BPF_MAP(l4s_accecn_ce_map, LRU_HASH, uint32_t, uint32_t, L4S_ACCECN_MAP_SIZE)
DEFINE_BPF_MAP(l4s_accecn_byte_map, LRU_HASH, uint32_t, EcnByteCounters, L4S_ACCECN_MAP_SIZE)
DEFINE_BPF_MAP(l4s_accecn_mss_map, LRU_HASH, uint32_t, uint16_t, L4S_ACCECN_MAP_SIZE)

static long (*bpf_sock_ops_cb_flags_set)(struct bpf_sock_ops *skops, int flags) = (void *) BPF_FUNC_sock_ops_cb_flags_set;
static long (*bpf_reserve_hdr_opt)(struct bpf_sock_ops *skops, int space, long flags) = (void *) BPF_FUNC_reserve_hdr_opt;
static long (*bpf_store_hdr_opt)(struct bpf_sock_ops *skops, const void *from, int len, long flags) = (void *) BPF_FUNC_store_hdr_opt;

static inline __attribute__((always_inline)) int
find_accecn_options_offset(struct __sk_buff *skb, uint8_t offset) {
    int ret;
    uint8_t opt_off;
    struct tcphdr tcp_header = {0};

    ret = bpf_skb_load_bytes(skb, offset, &tcp_header, (sizeof(struct tcphdr)));
    if (ret) {
        return -1;
    }
    opt_off = offset + (sizeof(struct tcphdr));
    for (int i = 0; i < 8; i++) {
        uint8_t kind;
        uint8_t length;

        ret = bpf_skb_load_bytes(skb, opt_off, &kind, 1);
        if (ret) {
            return -1;
        }
        if (kind == 172 || kind == 174) {
            return opt_off-offset;
        }
        if (kind == 0) {
            break;
        } else if (kind == 1) {
            opt_off += 1;
        } else {
            ret = bpf_skb_load_bytes(skb, opt_off + 1, &length, 1);
            if (ret || length < 2) {
                return -1;
            }
            opt_off += length;
        }
    }
    return -1;
}

static inline __attribute__((always_inline)) int
parse_tcp_mss_option(struct __sk_buff *skb, uint8_t offset) {
    int ret;
    uint8_t opt_off;
    struct tcphdr tcp_header = {0};

    ret = bpf_skb_load_bytes(skb, offset, &tcp_header, sizeof(struct tcphdr));
    if (ret)
        return -1;

    opt_off = offset + sizeof(struct tcphdr);

    for (int i = 0; i < 8; i++) {
        uint8_t kind = 0, length = 0;
        ret = bpf_skb_load_bytes(skb, opt_off, &kind, 1);
        if (ret)
            return -1;
        if (kind == 0)
            break;
        if (kind == 1) {
            opt_off += 1;
            continue;
        }

        ret = bpf_skb_load_bytes(skb, opt_off + 1, &length, 1);
        if (ret || length < 2)
            return -1;
        if (kind == 2 && length == 4) {
            uint16_t mss = 0;
            ret = bpf_skb_load_bytes(skb, opt_off + 2, &mss, 2);
            if (ret)
                return -1;
            return ntohs(mss);
        }
        opt_off += length;
    }
    return -1;
}

static const struct {
    __u8 kind;
    __u8 length;
    __u8 data[CUSTOM_TCP_OPTION_SIZE - 2];
} __attribute__((packed)) tcp_option = {
    .kind = CUSTOM_TCP_OPTION_KIND,
    .length = CUSTOM_TCP_OPTION_SIZE,
    .data = {0},
};

DEFINE_BPF_PROG_KVER(sockops, accecn_option, , AID_SYSTEM, 6_1)
(struct bpf_sock_ops *skops) {
    switch (skops->op) {
        case BPF_SOCK_OPS_TCP_CONNECT_CB:
        case BPF_SOCK_OPS_PASSIVE_ESTABLISHED_CB:
             bpf_sock_ops_cb_flags_set(
                skops,
                skops->bpf_sock_ops_cb_flags |
                BPF_SOCK_OPS_WRITE_HDR_OPT_CB_FLAG
            );
            break;
        case BPF_SOCK_OPS_HDR_OPT_LEN_CB:
        {
            if (skops->skb_tcp_flags & TCPHDR_SYN) {
               break;
            }

            __u32 flow_key = ((uint16_t)((skops->remote_port & 0xFFFF0000)>>16) << 16) | htons((uint16_t)(skops->local_port & 0xFFFF));
            EcnByteCounters* byte_count = bpf_l4s_accecn_byte_map_lookup_elem(&flow_key);
            if (!byte_count){
                break;
            }
            bpf_reserve_hdr_opt(skops, CUSTOM_TCP_OPTION_SIZE, 0);
            break;
        }
        case BPF_SOCK_OPS_WRITE_HDR_OPT_CB:
        {
            if (skops->skb_tcp_flags & TCPHDR_SYN) {
                break;
            }

            __u32 flow_key = ((uint16_t)((skops->remote_port & 0xFFFF0000)>>16) << 16) | htons((uint16_t)(skops->local_port & 0xFFFF));
            EcnByteCounters* byte_count = bpf_l4s_accecn_byte_map_lookup_elem(&flow_key);
            if (!byte_count){
                break;
            }
            bpf_store_hdr_opt(skops, &tcp_option, CUSTOM_TCP_OPTION_SIZE, 0);
            break;
        }
        default:
            break;
    }
    return 1;
}

static const EcnByteCounters new_cnt = {
    .ceb = 0,
    .e0b = 1,
    .e1b = 1,
};

DEFINE_BPF_PROG_KVER(schedcls, ingress_accecn_eth, , AID_SYSTEM, 6_1)
(struct __sk_buff* skb) {
    void* data = (void*)(long)skb->data;
    void* data_end = (void*)(long)skb->data_end;

    if (data + sizeof(struct ethhdr) + sizeof(struct iphdr) > data_end) {
        return TC_ACT_PIPE;
    }

    const bool isIpv4 = skb->protocol == htons(ETH_P_IP);
    const bool isIpv6 = skb->protocol == htons(ETH_P_IPV6);
    struct tcphdr* tcph = NULL;
    __u8 ip_ecn = 0;
    uint64_t payload_size = 0;
    int hdr_len = sizeof(struct ethhdr);

    if (isIpv4) {
        struct iphdr* ip = data + ETH_HLEN;
        if (ip->protocol == IPPROTO_TCP) {
            if (data + sizeof(struct ethhdr) + sizeof(struct iphdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip + 1);
            ip_ecn = ip->tos & 0x03;
            payload_size = ntohs(ip->tot_len) - (ip->ihl * 4) - (tcph->doff * 4);
            hdr_len += sizeof(struct iphdr);
        } else {
            return TC_ACT_PIPE;
        }
    } else if (isIpv6) {
        if (data + sizeof(struct ethhdr) + sizeof(struct ipv6hdr) > data_end) {
            return TC_ACT_PIPE;
        }
        struct ipv6hdr* ip6 = data + ETH_HLEN;
        if (ip6->nexthdr == IPPROTO_TCP) {
            if (data + sizeof(struct ethhdr) + sizeof(struct ipv6hdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip6 + 1);
            ip_ecn = (ip6->flow_lbl[0] & 0x30) >> 4;
            payload_size = ntohs(ip6->payload_len) - (tcph->doff * 4);
            hdr_len += sizeof(struct ipv6hdr);
        } else {
            return TC_ACT_PIPE;
        }
    } else {
        return TC_ACT_PIPE;
    }

    uint32_t flow_key = (tcph->source << 16) | tcph->dest;
    uint32_t *ce_count = bpf_l4s_accecn_ce_map_lookup_elem(&flow_key);
    uint32_t conn_key = 0;
    uint32_t *conn_count = bpf_l4s_accecn_ce_map_lookup_elem(&conn_key);

    EcnByteCounters* byte_count = bpf_l4s_accecn_byte_map_lookup_elem(&flow_key);

    int tcp_flags_offset = isIpv4 ? ETH_IP4_TCP_FLAGS_OFF : ETH_IP6_TCP_FLAGS_OFF;

    if (!ce_count) {
        // SYN/ACK
        if (tcph->syn && tcph->ack) {
            __u16 flags = load_half(skb, tcp_flags_offset);
            __u16 ace = (flags & 0x01c0) >> 6;

            // if the ACE is valid, add the entry to the map
            if (ace == 0b010 || ace == 0b011 || ace == 0b100 || ace == 0b110) {
                uint32_t init_value = (ip_ecn == 0b11) ? 0b110 : 0b101;
                bpf_l4s_accecn_ce_map_update_elem(&flow_key, &init_value, 0);
                int mss_value = parse_tcp_mss_option(skb, hdr_len);
                if (mss_value > 0) {
                    uint16_t mss_val = (__u16)mss_value;
                    bpf_l4s_accecn_mss_map_update_elem(&flow_key, &mss_val, 0);
                }

                uint32_t oneConnection = 1;
                if (!conn_count) {
                    bpf_l4s_accecn_ce_map_update_elem(&conn_key, &oneConnection, 0);
                } else {
                    __sync_fetch_and_add(conn_count, oneConnection);
                }

                if (!byte_count) {
                    int is_accecn = find_accecn_options_offset(skb, hdr_len);
                    if (is_accecn != -1) {
                        bpf_l4s_accecn_byte_map_update_elem(&flow_key, &new_cnt, 0);
                    }
                }
                return TC_ACT_PIPE;
            }
            return TC_ACT_PIPE;
        }
    } else {
        // if FIN or RST, remove entry from the map
        if (tcph->fin || tcph->rst) {
            bpf_l4s_accecn_ce_map_delete_elem(&flow_key);
            bpf_l4s_accecn_mss_map_delete_elem(&flow_key);
            if (byte_count) {
                bpf_l4s_accecn_byte_map_delete_elem(&flow_key);
            }
            return TC_ACT_PIPE;
        }

        // update the map if CE is marked
        if (ip_ecn == 0b11) {
            uint32_t ce_packets = 1;
            uint16_t *mss_ptr = bpf_l4s_accecn_mss_map_lookup_elem(&flow_key);
            if (mss_ptr) {
                uint16_t mss = *mss_ptr;
                if (mss != 0xFFFF && mss != 0) {
                    ce_packets = (payload_size / mss) + 1;
                }
            }
            __sync_fetch_and_add(ce_count, ce_packets);
        }

        if (byte_count) {
            if (ip_ecn == 0b11) {
                __sync_fetch_and_add(&byte_count->ceb, payload_size);
            } else if (ip_ecn == 0b10) {
                __sync_fetch_and_add(&byte_count->e0b, payload_size);
            } else if (ip_ecn == 0b01) {
                __sync_fetch_and_add(&byte_count->e1b, payload_size);
            }
        }
    }
    return TC_ACT_PIPE;
}


DEFINE_BPF_PROG_KVER(schedcls, egress_accecn_eth, , AID_SYSTEM, 6_1)
(struct __sk_buff* skb) {
    void* data = (void*)(long)skb->data;
    void* data_end = (void*)(long)skb->data_end;

    if (data + sizeof(struct ethhdr) + sizeof(struct iphdr) > data_end) {
        return TC_ACT_PIPE;
    }

    const bool isIpv4 = skb->protocol == htons(ETH_P_IP);
    const bool isIpv6 = skb->protocol == htons(ETH_P_IPV6);
    struct tcphdr* tcph = NULL;
    int hdr_len = sizeof(struct ethhdr);

    if (isIpv4) {
        struct iphdr* ip = data + ETH_HLEN;
        if (ip->protocol == IPPROTO_TCP) {
            if (data + sizeof(struct ethhdr) + sizeof(struct iphdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip + 1);
            hdr_len += sizeof(struct iphdr);
        } else {
            return TC_ACT_PIPE;
        }
    } else if (isIpv6) {
        if (data + sizeof(struct ethhdr) + sizeof(struct ipv6hdr) > data_end) {
            return TC_ACT_PIPE;
        }
        struct ipv6hdr* ip6 = data + ETH_HLEN;
        if (ip6->nexthdr == IPPROTO_TCP) {
            if (data + sizeof(struct ethhdr) + sizeof(struct ipv6hdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip6 + 1);
            hdr_len += sizeof(struct ipv6hdr);
        } else {
            return TC_ACT_PIPE;
        }
    } else {
        return TC_ACT_PIPE;
    }

    uint32_t flow_key = (tcph->dest << 16) | tcph->source;
    int tcp_flags_offset = isIpv4 ? ETH_IP4_TCP_FLAGS_OFF : ETH_IP6_TCP_FLAGS_OFF;
    int tcp_csum_offset = isIpv4 ? ETH_IP4_TCP_OFFSET(check) : ETH_IP6_TCP_OFFSET(check);
    int ret = 0;

    // if SYN, then set ACE to 111
    if (tcph->syn && !tcph->ack) {
        __u16 cur_flags = load_half(skb, tcp_flags_offset);
        __u16 new_flags = htons(cur_flags | 0x01c0);
        __u16 cur_ace = (cur_flags & 0x01c0) >> 6;

        // connection requesting AccECN by default
        if (cur_ace == 0b111) {
            return TC_ACT_PIPE;
        } else {
            ret = bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
            if (ret) return TC_ACT_PIPE;
            ret = bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);
            if (ret) return TC_ACT_PIPE;
        }
        return TC_ACT_PIPE;
    }

    // if present in map set ACE value and IP ECN bits
    uint32_t *ce_count = bpf_l4s_accecn_ce_map_lookup_elem(&flow_key);
    if (ce_count) {
        __u16 cur_flags = load_half(skb, tcp_flags_offset);
        __u16 new_flags = htons((cur_flags & 0xfe3f) | ((*ce_count & 7) << 6));

        bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
        bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);

        int ip_tos_offset = isIpv4 ? ETH_IP4_OFFSET(tos) : ETH_IP6_OFFSET(flow_lbl);

        __u8 old_tos = load_byte(skb, ip_tos_offset);
        __u8 new_tos = old_tos | (isIpv4 ? 0x01 : 0x10);

        if (isIpv4) {
            bpf_l3_csum_replace(skb, ETH_IP4_OFFSET(check), htons(old_tos), htons(new_tos), 2);
        }
        bpf_skb_store_bytes(skb, ip_tos_offset, &new_tos, sizeof(new_tos), 0);

        EcnByteCounters* byte_count = bpf_l4s_accecn_byte_map_lookup_elem(&flow_key);
        if (byte_count) {
            __u8 ace_option[12] = {0};
            __u32 e0b_val = htonl((__u32)(byte_count->e0b & 0x0000000000FFFFFF)) >> 8;
            __u32 ceb_val = htonl((__u32)(byte_count->ceb & 0x0000000000FFFFFF)) >> 8;
            __u32 e1b_val = htonl((__u32)(byte_count->e1b & 0x0000000000FFFFFF)) >> 8;
            __builtin_memcpy(&ace_option[0], &e0b_val, 3);
            __builtin_memcpy(&ace_option[3], &ceb_val, 3);
            __builtin_memcpy(&ace_option[6], &e1b_val, 3);

            int offset = find_accecn_options_offset(skb, hdr_len);
            if (offset < 0) return TC_ACT_PIPE;

            __u16 current_checksum = ntohs(load_half(skb, tcp_csum_offset));
            int64_t res = bpf_csum_diff(NULL, 0, (__be32 *)ace_option, 12, current_checksum);
            if (res < 0) return TC_ACT_PIPE;

            ret = bpf_l4_csum_replace(skb, tcp_csum_offset, 0, (__u64)res, 0);
            if (ret) return TC_ACT_PIPE;

            ret = bpf_skb_store_bytes(skb, hdr_len + offset + 2, &e1b_val, 3, BPF_F_RECOMPUTE_CSUM);
            if (ret) return TC_ACT_PIPE;

            ret = bpf_skb_store_bytes(skb, hdr_len + offset + 5, &ceb_val, 3, BPF_F_RECOMPUTE_CSUM);
            if (ret) return TC_ACT_PIPE;

            ret = bpf_skb_store_bytes(skb, hdr_len + offset + 8, &e0b_val, 3, BPF_F_RECOMPUTE_CSUM);
            if (ret) return TC_ACT_PIPE;
        }
    }
    return TC_ACT_PIPE;
}

DEFINE_BPF_PROG_KVER(schedcls, ingress_accecn_rawip, , AID_SYSTEM, 6_1)
(struct __sk_buff* skb) {
    void* data = (void*)(long)skb->data;
    void* data_end = (void*)(long)skb->data_end;

    if (data + sizeof(struct iphdr) > data_end) {
        return TC_ACT_PIPE;
    }

    const bool isIpv4 = skb->protocol == htons(ETH_P_IP);
    const bool isIpv6 = skb->protocol == htons(ETH_P_IPV6);
    struct tcphdr* tcph = NULL;
    __u8 ip_ecn = 0;
    uint64_t payload_size = 0;
    int hdr_len = 0;

    if (isIpv4) {
        struct iphdr* ip = data;
        if (ip->protocol == IPPROTO_TCP) {
            if (data + sizeof(struct iphdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip + 1);
            ip_ecn = ip->tos & 0x03;
            payload_size = ntohs(ip->tot_len) - (ip->ihl * 4) - (tcph->doff * 4);
            hdr_len += sizeof(struct iphdr);
        } else {
            return TC_ACT_PIPE;
        }
    } else if (isIpv6) {
        if (data + sizeof(struct ipv6hdr) > data_end) {
            return TC_ACT_PIPE;
        }
        struct ipv6hdr* ip6 = data;
        if (ip6->nexthdr == IPPROTO_TCP) {
            if (data + sizeof(struct ipv6hdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip6 + 1);
            ip_ecn = (ip6->flow_lbl[0] & 0x30) >> 4;
            payload_size = ntohs(ip6->payload_len) - (tcph->doff * 4);
            hdr_len += sizeof(struct ipv6hdr);
        } else {
            return TC_ACT_PIPE;
        }
    } else {
        return TC_ACT_PIPE;
    }

    uint32_t flow_key = (tcph->source << 16) | tcph->dest;
    uint32_t *ce_count = bpf_l4s_accecn_ce_map_lookup_elem(&flow_key);
    uint32_t conn_key = 0;
    uint32_t *conn_count = bpf_l4s_accecn_ce_map_lookup_elem(&conn_key);

    EcnByteCounters* byte_count = bpf_l4s_accecn_byte_map_lookup_elem(&flow_key);

    int tcp_flags_offset = isIpv4 ? IP4_TCP_FLAGS_OFF : IP6_TCP_FLAGS_OFF;

    if (!ce_count) {
        // SYN/ACK
        if (tcph->syn && tcph->ack) {
            __u16 flags = load_half(skb, tcp_flags_offset);
            __u16 ace = (flags & 0x01c0) >> 6;

            // if the ACE is valid, add the entry to the map
            if (ace == 0b010 || ace == 0b011 || ace == 0b100 || ace == 0b110) {
                uint32_t init_value = (ip_ecn == 0b11) ? 0b110 : 0b101;
                bpf_l4s_accecn_ce_map_update_elem(&flow_key, &init_value, 0);
                int mss_value = parse_tcp_mss_option(skb, hdr_len);
                if (mss_value > 0) {
                    uint16_t mss_val = (__u16)mss_value;
                    bpf_l4s_accecn_mss_map_update_elem(&flow_key, &mss_val, 0);
                }

                uint32_t oneConnection = 1;
                if (!conn_count) {
                    bpf_l4s_accecn_ce_map_update_elem(&conn_key, &oneConnection, 0);
                } else {
                    __sync_fetch_and_add(conn_count, oneConnection);
                }

                if (!byte_count) {
                    int is_accecn = find_accecn_options_offset(skb, hdr_len);
                    if (is_accecn != -1) {
                        bpf_l4s_accecn_byte_map_update_elem(&flow_key, &new_cnt, 0);
                    }
                }
                return TC_ACT_PIPE;
            }
            return TC_ACT_PIPE;
        }
    } else {
        // if FIN or RST, remove entry from the map
        if (tcph->fin || tcph->rst) {
            bpf_l4s_accecn_ce_map_delete_elem(&flow_key);
            bpf_l4s_accecn_mss_map_delete_elem(&flow_key);
            if (byte_count) {
                bpf_l4s_accecn_byte_map_delete_elem(&flow_key);
            }
            return TC_ACT_PIPE;
        }

        // update the map if CE is marked
        if (ip_ecn == 0b11) {
            uint32_t ce_packets = 1;
            uint16_t *mss_ptr = bpf_l4s_accecn_mss_map_lookup_elem(&flow_key);
            if (mss_ptr) {
                uint16_t mss = *mss_ptr;
                if (mss != 0xFFFF && mss != 0) {
                    ce_packets = (payload_size / mss) + 1;
                }
            }
            __sync_fetch_and_add(ce_count, ce_packets);
        }

        if (byte_count) {
            if (ip_ecn == 0b11) {
                __sync_fetch_and_add(&byte_count->ceb, payload_size);
            } else if (ip_ecn == 0b10) {
                __sync_fetch_and_add(&byte_count->e0b, payload_size);
            } else if (ip_ecn == 0b01) {
                __sync_fetch_and_add(&byte_count->e1b, payload_size);
            }
        }
    }
    return TC_ACT_PIPE;
}


DEFINE_BPF_PROG_KVER(schedcls, egress_accecn_rawip, , AID_SYSTEM, 6_1)
(struct __sk_buff* skb) {
    void* data = (void*)(long)skb->data;
    void* data_end = (void*)(long)skb->data_end;

    if (data + sizeof(struct iphdr) > data_end) {
        return TC_ACT_PIPE;
    }

    const bool isIpv4 = skb->protocol == htons(ETH_P_IP);
    const bool isIpv6 = skb->protocol == htons(ETH_P_IPV6);
    struct tcphdr* tcph = NULL;
    int hdr_len = 0;

    if (isIpv4) {
        struct iphdr* ip = data;
        if (ip->protocol == IPPROTO_TCP) {
            if (data + sizeof(struct iphdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip + 1);
            hdr_len += sizeof(struct iphdr);
        } else {
            return TC_ACT_PIPE;
        }
    } else if (isIpv6) {
        if (data + sizeof(struct ipv6hdr) > data_end) {
            return TC_ACT_PIPE;
        }
        struct ipv6hdr* ip6 = data;
        if (ip6->nexthdr == IPPROTO_TCP) {
            if (data + sizeof(struct ipv6hdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip6 + 1);
            hdr_len += sizeof(struct ipv6hdr);
        } else {
            return TC_ACT_PIPE;
        }
    } else {
        return TC_ACT_PIPE;
    }

    uint32_t flow_key = (tcph->dest << 16) | tcph->source;
    int tcp_flags_offset = isIpv4 ? IP4_TCP_FLAGS_OFF : IP6_TCP_FLAGS_OFF;
    int tcp_csum_offset = isIpv4 ? IP4_TCP_OFFSET(check) : IP6_TCP_OFFSET(check);
    int ret = 0;

    // if SYN, then set ACE to 111
    if (tcph->syn && !tcph->ack) {
        __u16 cur_flags = load_half(skb, tcp_flags_offset);
        __u16 new_flags = htons(cur_flags | 0x01c0);
        __u16 cur_ace = (cur_flags & 0x01c0) >> 6;

        // connection requesting AccECN by default
        if (cur_ace == 0b111) {
            return TC_ACT_PIPE;
        } else {
            ret = bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
            if (ret) return TC_ACT_PIPE;
            ret = bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);
            if (ret) return TC_ACT_PIPE;
        }
        return TC_ACT_PIPE;
    }

    // if present in map set ACE value and IP ECN bits
    uint32_t *ce_count = bpf_l4s_accecn_ce_map_lookup_elem(&flow_key);
    if (ce_count) {
        __u16 cur_flags = load_half(skb, tcp_flags_offset);
        __u16 new_flags = htons((cur_flags & 0xfe3f) | ((*ce_count & 7) << 6));

        bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
        bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);

        int ip_tos_offset = isIpv4 ? IP4_OFFSET(tos) : IP6_OFFSET(flow_lbl);

        __u8 old_tos = load_byte(skb, ip_tos_offset);
        __u8 new_tos = old_tos | (isIpv4 ? 0x01 : 0x10);

        if (isIpv4) {
            bpf_l3_csum_replace(skb, IP4_OFFSET(check), htons(old_tos), htons(new_tos), 2);
        }
        bpf_skb_store_bytes(skb, ip_tos_offset, &new_tos, sizeof(new_tos), 0);

        EcnByteCounters* byte_count = bpf_l4s_accecn_byte_map_lookup_elem(&flow_key);
        if (byte_count) {
            __u8 ace_option[12] = {0};
            __u32 e0b_val = htonl((__u32)(byte_count->e0b & 0x0000000000FFFFFF)) >> 8;
            __u32 ceb_val = htonl((__u32)(byte_count->ceb & 0x0000000000FFFFFF)) >> 8;
            __u32 e1b_val = htonl((__u32)(byte_count->e1b & 0x0000000000FFFFFF)) >> 8;
            __builtin_memcpy(&ace_option[0], &e0b_val, 3);
            __builtin_memcpy(&ace_option[3], &ceb_val, 3);
            __builtin_memcpy(&ace_option[6], &e1b_val, 3);

            int offset = find_accecn_options_offset(skb, hdr_len);
            if (offset < 0) return TC_ACT_PIPE;

            __u16 current_checksum = ntohs(load_half(skb, tcp_csum_offset));
            int64_t res = bpf_csum_diff(NULL, 0, (__be32 *)ace_option, 12, current_checksum);
            if (res < 0) return TC_ACT_PIPE;

            ret = bpf_l4_csum_replace(skb, tcp_csum_offset, 0, (__u64)res, 0);
            if (ret) return TC_ACT_PIPE;

            ret = bpf_skb_store_bytes(skb, hdr_len + offset + 2, &e1b_val, 3, BPF_F_RECOMPUTE_CSUM);
            if (ret) return TC_ACT_PIPE;

            ret = bpf_skb_store_bytes(skb, hdr_len + offset + 5, &ceb_val, 3, BPF_F_RECOMPUTE_CSUM);
            if (ret) return TC_ACT_PIPE;

            ret = bpf_skb_store_bytes(skb, hdr_len + offset + 8, &e0b_val, 3, BPF_F_RECOMPUTE_CSUM);
            if (ret) return TC_ACT_PIPE;
        }
    }
    return TC_ACT_PIPE;
}
