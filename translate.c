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
 * translate.c - CLAT functions / partial implementation of rfc6145
 */
#include <string.h>

#include <netinet/in.h>
#include <netinet/ip.h>
#include <netinet/ip_icmp.h>
#include <netinet/udp.h>
#include <netinet/tcp.h>
#include <netinet/ip6.h>
#include <netinet/icmp6.h>
#include <linux/icmp.h>

#include "translate.h"
#include "checksum.h"
#include "clatd.h"
#include "config.h"
#include "logging.h"
#include "debug.h"

/* function: payload_length
 * calculates the total length of the packet components after pos
 * packet - packet to calculate the length of
 * pos    - position to start counting from
 * returns: the total length of the packet components after pos
 */
uint16_t payload_length(clat_packet packet, int pos) {
  size_t len = 0;
  int i;
  for (i = pos + 1; i < POS_MAX; i++) {
    len += packet[i].iov_len;
  }
  return len;
}

/* function: fill_tun_header
 * fill in the header for the tun fd
 * tun_header - tunnel header, already allocated
 * proto      - ethernet protocol id: ETH_P_IP(ipv4) or ETH_P_IPV6(ipv6)
 */
void fill_tun_header(struct tun_pi *tun_header, uint16_t proto) {
  tun_header->flags = 0;
  tun_header->proto = htons(proto);
}

/* function: ipv6_src_to_ipv4_src
 * return the corresponding ipv4 address for the given ipv6 address
 * sourceaddr - ipv6 source address
 * returns: the IPv4 address
 */
uint32_t ipv6_src_to_ipv4_src(const struct in6_addr *sourceaddr) {
  // assumes a /96 plat subnet
  return sourceaddr->s6_addr32[3];
}

/* function: fill_ip_header
 * generate an ipv4 header from an ipv6 header
 * ip_targ     - (ipv4) target packet header, source: original ipv4 addr, dest: local subnet addr
 * payload_len - length of other data inside packet
 * protocol    - protocol number (tcp, udp, etc)
 * old_header  - (ipv6) source packet header, source: nat64 prefix, dest: local subnet prefix
 */
void fill_ip_header(struct iphdr *ip, uint16_t payload_len, uint8_t protocol,
                    const struct ip6_hdr *old_header) {
  memset(ip, 0, sizeof(struct iphdr));

  ip->ihl = 5;
  ip->version = 4;
  ip->tos = 0;
  ip->tot_len = htons(sizeof(struct iphdr) + payload_len);
  ip->id = 0;
  ip->frag_off = htons(IP_DF);
  ip->ttl = old_header->ip6_hlim;
  ip->protocol = protocol;
  ip->check = 0;

  ip->saddr = ipv6_src_to_ipv4_src(&old_header->ip6_src);
  ip->daddr = Global_Clatd_Config.ipv4_local_subnet.s_addr;
}

/* function: ipv4_dst_to_ipv6_dst
 * return the corresponding ipv6 address for the given ipv4 address
 * destination - ipv4 destination address (network byte order)
 */
struct in6_addr ipv4_dst_to_ipv6_dst(uint32_t destination) {
  struct in6_addr v6_destination;

  // assumes a /96 plat subnet
  v6_destination = Global_Clatd_Config.plat_subnet;
  v6_destination.s6_addr32[3] = destination;

  return v6_destination;
}

/* function: fill_ip6_header
 * generate an ipv6 header from an ipv4 header
 * ip6         - (ipv6) target packet header, source: local subnet prefix, dest: nat64 prefix
 * payload_len - length of other data inside packet
 * protocol    - protocol number (tcp, udp, etc)
 * old_header  - (ipv4) source packet header, source: local subnet addr, dest: internet's ipv4 addr
 */
void fill_ip6_header(struct ip6_hdr *ip6, uint16_t payload_len, uint8_t protocol,
                     const struct iphdr *old_header) {
  memset(ip6, 0, sizeof(struct ip6_hdr));

  ip6->ip6_vfc = 6 << 4;
  ip6->ip6_plen = htons(payload_len);
  ip6->ip6_nxt = protocol;
  ip6->ip6_hlim = old_header->ttl;

  ip6->ip6_src = Global_Clatd_Config.ipv6_local_subnet;
  ip6->ip6_dst = ipv4_dst_to_ipv6_dst(old_header->daddr);
}

/* function: icmp_to_icmp6
 * translate ipv4 icmp to ipv6 icmp (only currently supports echo/echo reply)
 * out          - output packet
 * icmp         - source packet icmp header
 * checksum     - pseudo-header checksum
 * payload      - icmp payload
 * payload_size - size of payload
 * returns: the highest position in the output clat_packet that's filled in
 */
int icmp_to_icmp6(clat_packet out, int pos, const struct icmphdr *icmp, uint32_t checksum,
                  const char *payload, size_t payload_size) {
  struct icmp6_hdr *icmp6_targ = out[pos].iov_base;
  uint32_t checksum_temp;

  if((icmp->type != ICMP_ECHO) && (icmp->type != ICMP_ECHOREPLY)) {
    logmsg_dbg(ANDROID_LOG_WARN,"icmp_to_icmp6/unhandled icmp type: 0x%x", icmp->type);
    return 0;
  }

  memset(icmp6_targ, 0, sizeof(struct icmp6_hdr));
  icmp6_targ->icmp6_type = (icmp->type == ICMP_ECHO) ? ICMP6_ECHO_REQUEST : ICMP6_ECHO_REPLY;
  icmp6_targ->icmp6_code = 0;
  icmp6_targ->icmp6_id = icmp->un.echo.id;
  icmp6_targ->icmp6_seq = icmp->un.echo.sequence;

  icmp6_targ->icmp6_cksum = 0;
  checksum = ip_checksum_add(checksum, icmp6_targ, sizeof(struct icmp6_hdr));
  checksum = ip_checksum_add(checksum, payload, payload_size);
  icmp6_targ->icmp6_cksum = ip_checksum_finish(checksum);

  out[pos].iov_len = sizeof(struct icmp6_hdr);
  out[POS_PAYLOAD].iov_base = (char *) payload;
  out[POS_PAYLOAD].iov_len = payload_size;

  return POS_PAYLOAD + 1;
}

/* function: icmp6_to_icmp
 * translate ipv6 icmp to ipv4 icmp (only currently supports echo/echo reply)
 * out          - output packet
 * icmp6        - source packet icmp6 header
 * checksum     - pseudo-header checksum (unused)
 * payload      - icmp6 payload
 * payload_size - size of payload
 * returns: the highest position in the output clat_packet that's filled in
 */
int icmp6_to_icmp(clat_packet out, int pos, const struct icmp6_hdr *icmp6, uint32_t checksum,
                  const char *payload, size_t payload_size) {
  struct icmphdr *icmp_targ = out[pos].iov_base;

  if((icmp6->icmp6_type != ICMP6_ECHO_REQUEST) && (icmp6->icmp6_type != ICMP6_ECHO_REPLY)) {
    logmsg_dbg(ANDROID_LOG_WARN,"icmp6_to_icmp/unhandled icmp6 type: 0x%x",icmp6->icmp6_type);
    return 0;
  }

  memset(icmp_targ, 0, sizeof(struct icmphdr));

  icmp_targ->type = (icmp6->icmp6_type == ICMP6_ECHO_REQUEST) ? ICMP_ECHO : ICMP_ECHOREPLY;
  icmp_targ->code = 0x0;
  icmp_targ->un.echo.id = icmp6->icmp6_id;
  icmp_targ->un.echo.sequence = icmp6->icmp6_seq;

  icmp_targ->checksum = 0;
  checksum = ip_checksum_add(0, icmp_targ, sizeof(struct icmphdr));
  checksum = ip_checksum_add(checksum, (void *)payload, payload_size);
  icmp_targ->checksum = ip_checksum_finish(checksum);

  out[pos].iov_len = sizeof(struct icmphdr);
  out[POS_PAYLOAD].iov_base = (char *) payload;
  out[POS_PAYLOAD].iov_len = payload_size;

  return POS_PAYLOAD + 1;
}

/* function: udp_packet
 * takes a udp packet and sets it up for translation
 * out      - output packet
 * udp      - pointer to udp header in packet
 * checksum - pseudo-header checksum
 * len      - size of ip payload
 */
int udp_packet(clat_packet out, int pos, const struct udphdr *udp, uint32_t checksum, size_t len) {
  const char *payload;
  size_t payload_size;

  if(len < sizeof(struct udphdr)) {
    logmsg_dbg(ANDROID_LOG_ERROR,"udp_packet/(too small)");
    return 0;
  }

  payload = (const char *) (udp + 1);
  payload_size = len - sizeof(struct udphdr);

  return udp_translate(out, pos, udp, checksum, payload, payload_size);
}

/* function: tcp_packet
 * takes a tcp packet and sets it up for translation
 * out      - output packet
 * tcp      - pointer to tcp header in packet
 * checksum - pseudo-header checksum
 * len      - size of ip payload
 * returns: the highest position in the output clat_packet that's filled in
 */
int tcp_packet(clat_packet out, int pos, const struct tcphdr *tcp, uint32_t checksum, size_t len) {
  const char *payload;
  size_t payload_size, header_size;

  if(len < sizeof(struct tcphdr)) {
    logmsg_dbg(ANDROID_LOG_ERROR,"tcp_packet/(too small)");
    return 0;
  }

  if(tcp->doff < 5) {
    logmsg_dbg(ANDROID_LOG_ERROR,"tcp_packet/tcp header length set to less than 5: %x", tcp->doff);
    return 0;
  }

  if((size_t) tcp->doff*4 > len) {
    logmsg_dbg(ANDROID_LOG_ERROR,"tcp_packet/tcp header length set too large: %x", tcp->doff);
    return 0;
  }

  header_size = tcp->doff * 4;
  payload = ((const char *) tcp) + header_size;
  payload_size = len - header_size;

  return tcp_translate(out, pos, tcp, header_size, checksum, payload, payload_size);
}

/* function: udp_translate
 * common between ipv4/ipv6 - setup checksum and send udp packet
 * out          - output packet
 * udp          - udp header
 * checksum     - pseudo-header checksum
 * payload      - tcp payload
 * payload_size - size of payload
 * returns: the highest position in the output clat_packet that's filled in
 */
int udp_translate(clat_packet out, int pos, const struct udphdr *udp, uint32_t checksum,
                  const char *payload, size_t payload_size) {
  struct udphdr *udp_targ = out[pos].iov_base;

  memcpy(udp_targ, udp, sizeof(struct udphdr));
  udp_targ->check = 0; // reset checksum, to be calculated

  checksum = ip_checksum_add(checksum, udp_targ, sizeof(struct udphdr));
  checksum = ip_checksum_add(checksum, payload, payload_size);
  udp_targ->check = ip_checksum_finish(checksum);

  out[pos].iov_len = sizeof(struct udphdr);
  out[POS_PAYLOAD].iov_base = (char *) payload;
  out[POS_PAYLOAD].iov_len = payload_size;

  return POS_PAYLOAD + 1;
}

/* function: tcp_translate
 * common between ipv4/ipv6 - setup checksum and send tcp packet
 * out          - output packet
 * tcp          - tcp header
 * header_size  - size of tcp header including options
 * checksum     - partial checksum covering ipv4/ipv6 header
 * payload      - tcp payload
 * payload_size - size of payload
 * returns: the highest position in the output clat_packet that's filled in
 *
 * TODO: mss rewrite
 * TODO: hosts without pmtu discovery - non DF packets will rely on fragmentation (unimplemented)
 */
int tcp_translate(clat_packet out, int pos, const struct tcphdr *tcp, size_t header_size,
                  uint32_t checksum, const char *payload, size_t payload_size) {
  struct tcphdr *tcp_targ = out[pos].iov_base;
  out[pos].iov_len = header_size;

  if (header_size > MAX_TCP_HDR) {
    // A TCP header cannot be more than MAX_TCP_HDR bytes long because it's a 4-bit field that
    // counts in 4-byte words. So this can never happen unless there is a bug in the caller.
    logmsg(ANDROID_LOG_ERROR, "tcp_translate: header too long %d > %d, truncating",
           header_size, MAX_TCP_HDR);
    header_size = MAX_TCP_HDR;
  }

  memcpy(tcp_targ, tcp, header_size);

  tcp_targ->check = 0;
  checksum = ip_checksum_add(checksum, tcp_targ, header_size);
  checksum = ip_checksum_add(checksum, payload, payload_size);
  tcp_targ->check = ip_checksum_finish(checksum);

  out[POS_PAYLOAD].iov_base = (char *)payload;
  out[POS_PAYLOAD].iov_len = payload_size;

  return POS_PAYLOAD + 1;
}
