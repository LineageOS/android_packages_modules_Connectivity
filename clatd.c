/*
 * Copyright 2012 Daniel Drown
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
 * clatd.c - tun interface setup and main event loop
 */
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <linux/filter.h>
#include <linux/if.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <linux/if_tun.h>
#include <net/if.h>
#include <sys/uio.h>

#include "clatd.h"
#include "checksum.h"
#include "config.h"
#include "dump.h"
#include "getaddr.h"
#include "logging.h"
#include "translate.h"

struct clat_config Global_Clatd_Config;

/* 40 bytes IPv6 header - 20 bytes IPv4 header + 8 bytes fragment header */
#define MTU_DELTA 28

volatile sig_atomic_t running = 1;

int ipv6_address_changed(const char *interface) {
  union anyip *interface_ip;

  interface_ip = getinterface_ip(interface, AF_INET6);
  if (!interface_ip) {
    logmsg(ANDROID_LOG_ERROR, "Unable to find an IPv6 address on interface %s", interface);
    return 1;
  }

  if (!ipv6_prefix_equal(&interface_ip->ip6, &Global_Clatd_Config.ipv6_local_subnet)) {
    char oldstr[INET6_ADDRSTRLEN];
    char newstr[INET6_ADDRSTRLEN];
    inet_ntop(AF_INET6, &Global_Clatd_Config.ipv6_local_subnet, oldstr, sizeof(oldstr));
    inet_ntop(AF_INET6, &interface_ip->ip6, newstr, sizeof(newstr));
    logmsg(ANDROID_LOG_INFO, "IPv6 prefix on %s changed: %s -> %s", interface, oldstr, newstr);
    free(interface_ip);
    return 1;
  } else {
    free(interface_ip);
    return 0;
  }
}

/* function: read_packet
 * reads a packet from the tunnel fd and translates it
 *   read_fd  - file descriptor to read original packet from
 *   write_fd - file descriptor to write translated packet to
 *   to_ipv6  - whether the packet is to be translated to ipv6 or ipv4
 */
void read_packet(int read_fd, int write_fd, int to_ipv6) {
  uint8_t buf[PACKETLEN];
  ssize_t readlen = read(read_fd, buf, PACKETLEN);

  if (readlen < 0) {
    if (errno != EAGAIN) {
      logmsg(ANDROID_LOG_WARN, "read_packet/read error: %s", strerror(errno));
    }
    return;
  } else if (readlen == 0) {
    logmsg(ANDROID_LOG_WARN, "read_packet/tun interface removed");
    running = 0;
    return;
  }

  if (!to_ipv6) {
    translate_packet(write_fd, 0 /* to_ipv6 */, buf, readlen);
    return;
  }

  struct tun_pi *tun_header = (struct tun_pi *)buf;
  if (readlen < (ssize_t)sizeof(*tun_header)) {
    logmsg(ANDROID_LOG_WARN, "read_packet/short read: got %ld bytes", readlen);
    return;
  }

  uint16_t proto = ntohs(tun_header->proto);
  if (proto != ETH_P_IP) {
    logmsg(ANDROID_LOG_WARN, "%s: unknown packet type = 0x%x", __func__, proto);
    return;
  }

  if (tun_header->flags != 0) {
    logmsg(ANDROID_LOG_WARN, "%s: unexpected flags = %d", __func__, tun_header->flags);
  }

  uint8_t *packet = (uint8_t *)(tun_header + 1);
  readlen -= sizeof(*tun_header);
  translate_packet(write_fd, 1 /* to_ipv6 */, packet, readlen);
}

// IPv6 DAD packet format:
//   Ethernet header (if needed) will be added by the kernel:
//     u8[6] src_mac; u8[6] dst_mac '33:33:ff:XX:XX:XX'; be16 ethertype '0x86DD'
//   IPv6 header:
//     be32 0x60000000 - ipv6, tclass 0, flowlabel 0
//     be16 payload_length '32'; u8 nxt_hdr ICMPv6 '58'; u8 hop limit '255'
//     u128 src_ip6 '::'
//     u128 dst_ip6 'ff02::1:ffXX:XXXX'
//   ICMPv6 header:
//     u8 type '135'; u8 code '0'; u16 icmp6 checksum; u32 reserved '0'
//   ICMPv6 neighbour solicitation payload:
//     u128 tgt_ip6
//   ICMPv6 ND options:
//     u8 opt nr '14'; u8 length '1'; u8[6] nonce '6 random bytes'
void send_dad(int fd, const struct in6_addr* tgt) {
  struct {
    struct ip6_hdr ip6h;
    struct nd_neighbor_solicit ns;
    uint8_t ns_opt_nr;
    uint8_t ns_opt_len;
    uint8_t ns_opt_nonce[6];
  } dad_pkt = {
    .ip6h = {
      .ip6_flow = htonl(6 << 28),  // v6, 0 tclass, 0 flowlabel
      .ip6_plen = htons(sizeof(dad_pkt) - sizeof(struct ip6_hdr)),  // payload length, ie. 32
      .ip6_nxt = IPPROTO_ICMPV6,  // 58
      .ip6_hlim = 255,
      .ip6_src = {},  // ::
      .ip6_dst.s6_addr = {
        0xFF, 0x02, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 1,
        0xFF, tgt->s6_addr[13], tgt->s6_addr[14], tgt->s6_addr[15],
      },  // ff02::1:ffXX:XXXX - multicast group address derived from bottom 24-bits of tgt
    },
    .ns = {
      .nd_ns_type = ND_NEIGHBOR_SOLICIT,  // 135
      .nd_ns_code = 0,
      .nd_ns_cksum = 0,  // will be calculated later
      .nd_ns_reserved = 0,
      .nd_ns_target = *tgt,
    },
    .ns_opt_nr = 14,  // icmp6 option 'nonce' from RFC3971
    .ns_opt_len = 1,  // in units of 8 bytes, including option nr and len
    .ns_opt_nonce = {},  // opt_len *8 - sizeof u8(opt_nr) - sizeof u8(opt_len) = 6 ranodmized bytes
  };
  arc4random_buf(&dad_pkt.ns_opt_nonce, sizeof(dad_pkt.ns_opt_nonce));

  // 40 byte IPv6 header + 8 byte ICMPv6 header + 16 byte ipv6 target address + 8 byte nonce option
  _Static_assert(sizeof(dad_pkt) == 40 + 8 + 16 + 8, "sizeof dad packet != 72");

  // IPv6 header checksum is standard negated 16-bit one's complement sum over the icmpv6 pseudo
  // header (which includes payload length, nextheader, and src/dst ip) and the icmpv6 payload.
  //
  // Src/dst ip immediately prefix the icmpv6 header itself, so can be handled along
  // with the payload.  We thus only need to manually account for payload len & next header.
  //
  // The magic '8' is simply the offset of the ip6_src field in the ipv6 header,
  // ie. we're skipping over the ipv6 version, tclass, flowlabel, payload length, next header
  // and hop limit fields, because they're not quite where we want them to be.
  //
  // ip6_plen is already in network order, while ip6_nxt is a single byte and thus needs htons().
  uint32_t csum = dad_pkt.ip6h.ip6_plen + htons(dad_pkt.ip6h.ip6_nxt);
  csum = ip_checksum_add(csum, &dad_pkt.ip6h.ip6_src, sizeof(dad_pkt) - 8);
  dad_pkt.ns.nd_ns_cksum = ip_checksum_finish(csum);

  const struct sockaddr_in6 dst = {
    .sin6_family = AF_INET6,
    .sin6_addr = dad_pkt.ip6h.ip6_dst,
    .sin6_scope_id = if_nametoindex(Global_Clatd_Config.native_ipv6_interface),
  };

  sendto(fd, &dad_pkt, sizeof(dad_pkt), 0 /*flags*/, (const struct sockaddr *)&dst, sizeof(dst));
}

/* function: event_loop
 * reads packets from the tun network interface and passes them down the stack
 *   tunnel - tun device data
 */
void event_loop(struct tun_data *tunnel) {
  // Apparently some network gear will refuse to perform NS for IPs that aren't DAD'ed,
  // this would then result in an ipv6-only network with working native ipv6, working
  // IPv4 via DNS64, but non-functioning IPv4 via CLAT (ie. IPv4 literals + IPv4 only apps).
  // The kernel itself doesn't do DAD for anycast ips (but does handle IPV6 MLD and handle ND).
  // So we'll spoof dad here, and yeah, we really should check for a response and in
  // case of failure pick a different IP.  Seeing as 48-bits of the IP are utterly random
  // (with the other 16 chosen to guarantee checksum neutrality) this seems like a remote
  // concern...
  // TODO: actually perform true DAD
  send_dad(tunnel->write_fd6, &Global_Clatd_Config.ipv6_local_subnet);

  time_t last_interface_poll;
  struct pollfd wait_fd[] = {
    { tunnel->read_fd6, POLLIN, 0 },
    { tunnel->fd4, POLLIN, 0 },
  };

  // start the poll timer
  last_interface_poll = time(NULL);

  while (running) {
    if (poll(wait_fd, ARRAY_SIZE(wait_fd), NO_TRAFFIC_INTERFACE_POLL_FREQUENCY * 1000) == -1) {
      if (errno != EINTR) {
        logmsg(ANDROID_LOG_WARN, "event_loop/poll returned an error: %s", strerror(errno));
      }
    } else {
      // Call read_packet if the socket has data to be read, but also if an
      // error is waiting. If we don't call read() after getting POLLERR, a
      // subsequent poll() will return immediately with POLLERR again,
      // causing this code to spin in a loop. Calling read() will clear the
      // socket error flag instead.
      if (wait_fd[0].revents) read_packet(tunnel->read_fd6, tunnel->fd4, 0 /* to_ipv6 */);
      if (wait_fd[1].revents) read_packet(tunnel->fd4, tunnel->write_fd6, 1 /* to_ipv6 */);
    }

    time_t now = time(NULL);
    if (now >= (last_interface_poll + INTERFACE_POLL_FREQUENCY)) {
      last_interface_poll = now;
      if (ipv6_address_changed(Global_Clatd_Config.native_ipv6_interface)) {
        break;
      }
    }
  }
}
