/*
 * Copyright (C) 2022 The Android Open Source Project
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

#define LOG_TAG "TcUtils"

#include "tcutils/tcutils.h"

#include "kernelversion.h"
#include "scopeguard.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <libgen.h>
#include <linux/if_arp.h>
#include <linux/if_ether.h>
#include <linux/netlink.h>
#include <linux/pkt_cls.h>
#include <linux/pkt_sched.h>
#include <linux/rtnetlink.h>
#include <net/if.h>
#include <stdarg.h>
#include <sys/socket.h>
#include <unistd.h>
#include <utility>

#define BPF_FD_JUST_USE_INT
#include <BpfSyscallWrappers.h>
#undef BPF_FD_JUST_USE_INT

// The maximum length of TCA_BPF_NAME. Sync from net/sched/cls_bpf.c.
#define CLS_BPF_NAME_LEN 256

// Classifier name. See cls_bpf_ops in net/sched/cls_bpf.c.
#define CLS_BPF_KIND_NAME "bpf"

namespace android {
namespace {

void logError(const char *fmt...) {
  va_list args;
  va_start(args, fmt);
  __android_log_vprint(ANDROID_LOG_ERROR, LOG_TAG, fmt, args);
  va_end(args);
}

const sockaddr_nl KERNEL_NLADDR = {AF_NETLINK, 0, 0, 0};
const uint16_t NETLINK_REQUEST_FLAGS = NLM_F_REQUEST | NLM_F_ACK;

int sendAndProcessNetlinkResponse(const void *req, int len) {
  // TODO: use unique_fd instead of ScopeGuard
  int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
  if (fd == -1) {
    int error = errno;
    logError("socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE): %d",
             error);
    return -error;
  }
  auto scopeGuard = base::make_scope_guard([fd] { close(fd); });

  static constexpr int on = 1;
  if (setsockopt(fd, SOL_NETLINK, NETLINK_CAP_ACK, &on, sizeof(on))) {
    int error = errno;
    logError("setsockopt(fd, SOL_NETLINK, NETLINK_CAP_ACK, 1): %d", error);
    return -error;
  }

  // this is needed to get valid strace netlink parsing, it allocates the pid
  if (bind(fd, (const struct sockaddr *)&KERNEL_NLADDR,
           sizeof(KERNEL_NLADDR))) {
    int error = errno;
    logError("bind(fd, {AF_NETLINK, 0, 0}: %d)", error);
    return -error;
  }

  // we do not want to receive messages from anyone besides the kernel
  if (connect(fd, (const struct sockaddr *)&KERNEL_NLADDR,
              sizeof(KERNEL_NLADDR))) {
    int error = errno;
    logError("connect(fd, {AF_NETLINK, 0, 0}): %d", error);
    return -error;
  }

  int rv = send(fd, req, len, 0);

  if (rv == -1) {
    int error = errno;
    logError("send(fd, req, len, 0) failed: %d", error);
    return -error;
  }

  if (rv != len) {
    logError("send(fd, req, len = %d, 0) returned invalid message size %d", len,
             rv);
    return -EMSGSIZE;
  }

  struct {
    nlmsghdr h;
    nlmsgerr e;
    char buf[256];
  } resp = {};

  rv = recv(fd, &resp, sizeof(resp), MSG_TRUNC);

  if (rv == -1) {
    int error = errno;
    logError("recv() failed: %d", error);
    return -error;
  }

  if (rv < (int)NLMSG_SPACE(sizeof(struct nlmsgerr))) {
    logError("recv() returned short packet: %d", rv);
    return -EBADMSG;
  }

  if (resp.h.nlmsg_len != (unsigned)rv) {
    logError("recv() returned invalid header length: %d != %d",
             resp.h.nlmsg_len, rv);
    return -EBADMSG;
  }

  if (resp.h.nlmsg_type != NLMSG_ERROR) {
    logError("recv() did not return NLMSG_ERROR message: %d",
             resp.h.nlmsg_type);
    return -ENOMSG;
  }

  if (resp.e.error) {
    logError("NLMSG_ERROR message return error: %d", resp.e.error);
  }
  return resp.e.error; // returns 0 on success
}

int hardwareAddressType(const char *interface) {
  int fd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
  if (fd < 0)
    return -errno;
  auto scopeGuard = base::make_scope_guard([fd] { close(fd); });

  struct ifreq ifr = {};
  // We use strncpy() instead of strlcpy() since kernel has to be able
  // to handle non-zero terminated junk passed in by userspace anyway,
  // and this way too long interface names (more than IFNAMSIZ-1 = 15
  // characters plus terminating NULL) will not get truncated to 15
  // characters and zero-terminated and thus potentially erroneously
  // match a truncated interface if one were to exist.
  strncpy(ifr.ifr_name, interface, sizeof(ifr.ifr_name));

  if (ioctl(fd, SIOCGIFHWADDR, &ifr, sizeof(ifr))) {
    return -errno;
  }
  return ifr.ifr_hwaddr.sa_family;
}

} // namespace

int isEthernet(const char *iface, bool &isEthernet) {
  int rv = hardwareAddressType(iface);
  if (rv < 0) {
    logError("Get hardware address type of interface %s failed: %s", iface,
             strerror(-rv));
    return -rv;
  }

  // Backwards compatibility with pre-GKI kernels that use various custom
  // ARPHRD_* for their cellular interface
  switch (rv) {
  // ARPHRD_PUREIP on at least some Mediatek Android kernels
  // example: wembley with 4.19 kernel
  case 520:
  // in Linux 4.14+ rmnet support was upstreamed and ARHRD_RAWIP became 519,
  // but it is 530 on at least some Qualcomm Android 4.9 kernels with rmnet
  // example: Pixel 3 family
  case 530:
    // >5.4 kernels are GKI2.0 and thus upstream compatible, however 5.10
    // shipped with Android S, so (for safety) let's limit ourselves to
    // >5.10, ie. 5.11+ as a guarantee we're on Android T+ and thus no
    // longer need this non-upstream compatibility logic
    static bool is_pre_5_11_kernel = !isAtLeastKernelVersion(5, 11, 0);
    if (is_pre_5_11_kernel)
      return false;
  }

  switch (rv) {
  case ARPHRD_ETHER:
    isEthernet = true;
    return 0;
  case ARPHRD_NONE:
  case ARPHRD_PPP:
  case ARPHRD_RAWIP:
    isEthernet = false;
    return 0;
  default:
    logError("Unknown hardware address type %d on interface %s", rv, iface);
    return -ENOENT;
  }
}

// tc filter add dev .. in/egress prio 1 protocol ipv6/ip bpf object-pinned
// /sys/fs/bpf/... direct-action
int tcAddBpfFilter(int ifIndex, bool ingress, uint16_t prio, uint16_t proto,
                   const char *bpfProgPath) {
  const int bpfFd = bpf::retrieveProgram(bpfProgPath);
  if (bpfFd == -1) {
    logError("retrieveProgram failed: %d", errno);
    return -errno;
  }
  auto scopeGuard = base::make_scope_guard([bpfFd] { close(bpfFd); });

  struct {
    nlmsghdr n;
    tcmsg t;
    struct {
      nlattr attr;
      // The maximum classifier name length is defined in
      // tcf_proto_ops in include/net/sch_generic.h.
      char str[NLMSG_ALIGN(sizeof(CLS_BPF_KIND_NAME))];
    } kind;
    struct {
      nlattr attr;
      struct {
        nlattr attr;
        __u32 u32;
      } fd;
      struct {
        nlattr attr;
        char str[NLMSG_ALIGN(CLS_BPF_NAME_LEN)];
      } name;
      struct {
        nlattr attr;
        __u32 u32;
      } flags;
    } options;
  } req = {
      .n =
          {
              .nlmsg_len = sizeof(req),
              .nlmsg_type = RTM_NEWTFILTER,
              .nlmsg_flags = NETLINK_REQUEST_FLAGS | NLM_F_EXCL | NLM_F_CREATE,
          },
      .t =
          {
              .tcm_family = AF_UNSPEC,
              .tcm_ifindex = ifIndex,
              .tcm_handle = TC_H_UNSPEC,
              .tcm_parent = TC_H_MAKE(TC_H_CLSACT, ingress ? TC_H_MIN_INGRESS
                                                           : TC_H_MIN_EGRESS),
              .tcm_info =
                  static_cast<__u32>((static_cast<uint16_t>(prio) << 16) |
                                     htons(static_cast<uint16_t>(proto))),
          },
      .kind =
          {
              .attr =
                  {
                      .nla_len = sizeof(req.kind),
                      .nla_type = TCA_KIND,
                  },
              .str = CLS_BPF_KIND_NAME,
          },
      .options =
          {
              .attr =
                  {
                      .nla_len = sizeof(req.options),
                      .nla_type = NLA_F_NESTED | TCA_OPTIONS,
                  },
              .fd =
                  {
                      .attr =
                          {
                              .nla_len = sizeof(req.options.fd),
                              .nla_type = TCA_BPF_FD,
                          },
                      .u32 = static_cast<__u32>(bpfFd),
                  },
              .name =
                  {
                      .attr =
                          {
                              .nla_len = sizeof(req.options.name),
                              .nla_type = TCA_BPF_NAME,
                          },
                      // Visible via 'tc filter show', but
                      // is overwritten by strncpy below
                      .str = "placeholder",
                  },
              .flags =
                  {
                      .attr =
                          {
                              .nla_len = sizeof(req.options.flags),
                              .nla_type = TCA_BPF_FLAGS,
                          },
                      .u32 = TCA_BPF_FLAG_ACT_DIRECT,
                  },
          },
  };

  snprintf(req.options.name.str, sizeof(req.options.name.str), "%s:[*fsobj]",
           basename(bpfProgPath));

  int error = sendAndProcessNetlinkResponse(&req, sizeof(req));
  return error;
}

// tc filter del dev .. in/egress prio .. protocol ..
int tcDeleteFilter(int ifIndex, bool ingress, uint16_t prio, uint16_t proto) {
  const struct {
    nlmsghdr n;
    tcmsg t;
  } req = {
      .n =
          {
              .nlmsg_len = sizeof(req),
              .nlmsg_type = RTM_DELTFILTER,
              .nlmsg_flags = NETLINK_REQUEST_FLAGS,
          },
      .t =
          {
              .tcm_family = AF_UNSPEC,
              .tcm_ifindex = ifIndex,
              .tcm_handle = TC_H_UNSPEC,
              .tcm_parent = TC_H_MAKE(TC_H_CLSACT, ingress ? TC_H_MIN_INGRESS
                                                           : TC_H_MIN_EGRESS),
              .tcm_info =
                  static_cast<__u32>((static_cast<uint16_t>(prio) << 16) |
                                     htons(static_cast<uint16_t>(proto))),
          },
  };

  return sendAndProcessNetlinkResponse(&req, sizeof(req));
}

} // namespace android
