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
#include <sys/capability.h>
#include <sys/uio.h>

#include <netid_client.h>                       // For MARK_UNSET.
#include <private/android_filesystem_config.h>  // For AID_CLAT.

#include "clatd.h"
#include "config.h"
#include "dump.h"
#include "getaddr.h"
#include "logging.h"
#include "setif.h"
#include "translate.h"

struct clat_config Global_Clatd_Config;

/* 40 bytes IPv6 header - 20 bytes IPv4 header + 8 bytes fragment header */
#define MTU_DELTA 28

volatile sig_atomic_t running = 1;

/* function: configure_packet_socket
 * Binds the packet socket and attaches the receive filter to it.
 *   sock - the socket to configure
 */
int configure_packet_socket(int sock) {
  uint32_t *ipv6 = Global_Clatd_Config.ipv6_local_subnet.s6_addr32;

  // clang-format off
  struct sock_filter filter_code[] = {
    // Load the first four bytes of the IPv6 destination address (starts 24 bytes in).
    // Compare it against the first four bytes of our IPv6 address, in host byte order (BPF loads
    // are always in host byte order). If it matches, continue with next instruction (JMP 0). If it
    // doesn't match, jump ahead to statement that returns 0 (ignore packet). Repeat for the other
    // three words of the IPv6 address, and if they all match, return PACKETLEN (accept packet).
    BPF_STMT(BPF_LD  | BPF_W   | BPF_ABS,  24),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    htonl(ipv6[0]), 0, 7),
    BPF_STMT(BPF_LD  | BPF_W   | BPF_ABS,  28),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    htonl(ipv6[1]), 0, 5),
    BPF_STMT(BPF_LD  | BPF_W   | BPF_ABS,  32),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    htonl(ipv6[2]), 0, 3),
    BPF_STMT(BPF_LD  | BPF_W   | BPF_ABS,  36),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    htonl(ipv6[3]), 0, 1),
    BPF_STMT(BPF_RET | BPF_K,              PACKETLEN),
    BPF_STMT(BPF_RET | BPF_K,              0),
  };
  // clang-format on
  struct sock_fprog filter = { sizeof(filter_code) / sizeof(filter_code[0]), filter_code };

  if (setsockopt(sock, SOL_SOCKET, SO_ATTACH_FILTER, &filter, sizeof(filter))) {
    logmsg(ANDROID_LOG_FATAL, "attach packet filter failed: %s", strerror(errno));
    return 0;
  }

  struct sockaddr_ll sll = {
    .sll_family   = AF_PACKET,
    .sll_protocol = htons(ETH_P_IPV6),
    .sll_ifindex  = if_nametoindex(Global_Clatd_Config.native_ipv6_interface),
    .sll_pkttype  = PACKET_OTHERHOST,  // The 464xlat IPv6 address is not assigned to the kernel.
  };
  if (bind(sock, (struct sockaddr *)&sll, sizeof(sll))) {
    logmsg(ANDROID_LOG_FATAL, "binding packet socket: %s", strerror(errno));
    return 0;
  }

  return 1;
}

/* function: set_capability
 * set the permitted, effective and inheritable capabilities of the current
 * thread
 */
void set_capability(uint64_t target_cap) {
  struct __user_cap_header_struct header = {
    .version = _LINUX_CAPABILITY_VERSION_3,
    .pid     = 0  // 0 = change myself
  };
  struct __user_cap_data_struct cap[_LINUX_CAPABILITY_U32S_3] = {};

  cap[0].permitted = cap[0].effective = cap[0].inheritable = target_cap;
  cap[1].permitted = cap[1].effective = cap[1].inheritable = target_cap >> 32;

  if (capset(&header, cap) < 0) {
    logmsg(ANDROID_LOG_FATAL, "capset failed: %s", strerror(errno));
    exit(1);
  }
}

/* function: drop_root_and_caps
 * drops root privs and all capabilities
 */
void drop_root_and_caps() {
  // see man setgroups: this drops all supplementary groups
  if (setgroups(0, NULL) < 0) {
    logmsg(ANDROID_LOG_FATAL, "setgroups failed: %s", strerror(errno));
    exit(1);
  }

  if (setresgid(AID_CLAT, AID_CLAT, AID_CLAT) < 0) {
    logmsg(ANDROID_LOG_FATAL, "setresgid failed: %s", strerror(errno));
    exit(1);
  }
  if (setresuid(AID_CLAT, AID_CLAT, AID_CLAT) < 0) {
    logmsg(ANDROID_LOG_FATAL, "setresuid failed: %s", strerror(errno));
    exit(1);
  }

  set_capability(0);
}

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

/* function: configure_clat_ipv6_address
 * picks the clat IPv6 address and configures packet translation to use it.
 *   tunnel - tun device data
 *   interface - uplink interface name
 *   returns: 1 on success, 0 on failure
 */
int configure_clat_ipv6_address(const struct tun_data *tunnel, const char *interface,
                                const char *v6_addr) {
  if (!v6_addr || !inet_pton(AF_INET6, v6_addr, &Global_Clatd_Config.ipv6_local_subnet)) {
    logmsg(ANDROID_LOG_FATAL, "Invalid source address %s", v6_addr);
    return 0;
  }

  char addrstr[INET6_ADDRSTRLEN];
  inet_ntop(AF_INET6, &Global_Clatd_Config.ipv6_local_subnet, addrstr, sizeof(addrstr));
  logmsg(ANDROID_LOG_INFO, "Using IPv6 address %s on %s", addrstr, interface);

  // Start translating packets to the new prefix.
  add_anycast_address(tunnel->write_fd6, &Global_Clatd_Config.ipv6_local_subnet, interface);

  // Update our packet socket filter to reflect the new 464xlat IP address.
  if (!configure_packet_socket(tunnel->read_fd6)) {
    // Things aren't going to work. Bail out and hope we have better luck next time.
    // We don't log an error here because configure_packet_socket has already done so.
    return 0;
  }

  return 1;
}

/* function: configure_interface
 * reads the configuration and applies it to the interface
 *   uplink_interface - network interface to use to reach the ipv6 internet
 *   plat_prefix      - PLAT prefix to use
 *   v6_addr          - the v6 address to use on the native interface
 *   tunnel           - tun device data
 */
void configure_interface(const char *uplink_interface, const char *plat_prefix, const char *v6_addr,
                         struct tun_data *tunnel) {
  Global_Clatd_Config.native_ipv6_interface = uplink_interface;
  if (!plat_prefix || inet_pton(AF_INET6, plat_prefix, &Global_Clatd_Config.plat_subnet) <= 0) {
    logmsg(ANDROID_LOG_FATAL, "invalid IPv6 address specified for plat prefix: %s", plat_prefix);
    exit(1);
  }

  if (!configure_clat_ipv6_address(tunnel, uplink_interface, v6_addr)) {
    exit(1);
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

/* function: event_loop
 * reads packets from the tun network interface and passes them down the stack
 *   tunnel - tun device data
 */
void event_loop(struct tun_data *tunnel) {
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
