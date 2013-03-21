/*
 * Copyright 2011 Daniel Drown
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * translate.h - translate from one version of ip to another
 */
#ifndef __TRANSLATE_H__
#define __TRANSLATE_H__

#include <linux/if_tun.h>

#define MAX_TCP_HDR (15 * 4)   // Data offset field is 4 bits and counts in 32-bit words.

// A clat_packet is an array of iovec structures representing a packet that we are translating.
// The POS_XXX constants represent the array indices within the clat_packet that contain specific
// parts of the packet.
enum clat_packet_index { POS_TUNHDR, POS_IPHDR, POS_TRANSPORTHDR, POS_ICMPIPHDR,
                         POS_PAYLOAD, POS_MAX };
typedef struct iovec clat_packet[POS_MAX];

// Returns the total length of the packet components after index.
uint16_t payload_length(clat_packet packet, int index);

// Functions to create tun, IPv4, and IPv6 headers.
void fill_tun_header(struct tun_pi *tun_header, uint16_t proto);
void fill_ip_header(struct iphdr *ip_targ, uint16_t payload_len, uint8_t protocol,
                    const struct ip6_hdr *old_header);
void fill_ip6_header(struct ip6_hdr *ip6, uint16_t payload_len, uint8_t protocol,
                     const struct iphdr *old_header);

// Translate ICMP packets.
int icmp_to_icmp6(clat_packet out, int pos, const struct icmphdr *icmp, uint32_t checksum,
                  const char *payload, size_t payload_size);
int icmp6_to_icmp(clat_packet out, int pos, const struct icmp6_hdr *icmp6, uint32_t checksum,
                  const char *payload, size_t payload_size);

// Translate TCP and UDP packets.
int tcp_packet(clat_packet out, int pos, const struct tcphdr *tcp, uint32_t checksum, size_t len);
int udp_packet(clat_packet out, int pos, const struct udphdr *udp, uint32_t checksum, size_t len);

int tcp_translate(clat_packet out, int pos, const struct tcphdr *tcp, size_t header_size,
                  uint32_t checksum, const char *payload, size_t payload_size);
int udp_translate(clat_packet out, int pos, const struct udphdr *udp, uint32_t checksum,
                  const char *payload, size_t payload_size);

#endif /* __TRANSLATE_H__ */
