/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <arpa/inet.h>
#include <jni.h>
#include <linux/if_arp.h>
#include <linux/if_ether.h>
#include <linux/netlink.h>
#include <linux/pkt_cls.h>
#include <linux/pkt_sched.h>
#include <linux/rtnetlink.h>
#include <nativehelper/JNIHelp.h>
#include <net/if.h>
#include <stdio.h>
#include <sys/socket.h>
#include <sys/utsname.h>

// TODO: use unique_fd.
#define BPF_FD_JUST_USE_INT
#include "BpfSyscallWrappers.h"
#include "bpf_tethering.h"
#include "nativehelper/scoped_utf_chars.h"

// The maximum length of TCA_BPF_NAME. Sync from net/sched/cls_bpf.c.
#define CLS_BPF_NAME_LEN 256

// Classifier name. See cls_bpf_ops in net/sched/cls_bpf.c.
#define CLS_BPF_KIND_NAME "bpf"

namespace android {
// Sync from system/netd/server/NetlinkCommands.h
const uint16_t NETLINK_REQUEST_FLAGS = NLM_F_REQUEST | NLM_F_ACK;
const sockaddr_nl KERNEL_NLADDR = {AF_NETLINK, 0, 0, 0};

static void throwIOException(JNIEnv *env, const char* msg, int error) {
    jniThrowExceptionFmt(env, "java/io/IOException", "%s: %s", msg, strerror(error));
}

// TODO: move to frameworks/libs/net/common/native for sharing with
// system/netd/server/OffloadUtils.{c, h}.
static void sendAndProcessNetlinkResponse(JNIEnv* env, const void* req, int len) {
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);  // TODO: use unique_fd
    if (fd == -1) {
        throwIOException(env, "socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE)", errno);
        return;
    }

    static constexpr int on = 1;
    if (setsockopt(fd, SOL_NETLINK, NETLINK_CAP_ACK, &on, sizeof(on))) {
        throwIOException(env, "setsockopt(fd, SOL_NETLINK, NETLINK_CAP_ACK, 1)", errno);
        close(fd);
        return;
    }

    // this is needed to get valid strace netlink parsing, it allocates the pid
    if (bind(fd, (const struct sockaddr*)&KERNEL_NLADDR, sizeof(KERNEL_NLADDR))) {
        throwIOException(env, "bind(fd, {AF_NETLINK, 0, 0})", errno);
        close(fd);
        return;
    }

    // we do not want to receive messages from anyone besides the kernel
    if (connect(fd, (const struct sockaddr*)&KERNEL_NLADDR, sizeof(KERNEL_NLADDR))) {
        throwIOException(env, "connect(fd, {AF_NETLINK, 0, 0})", errno);
        close(fd);
        return;
    }

    int rv = send(fd, req, len, 0);

    if (rv == -1) {
        throwIOException(env, "send(fd, req, len, 0)", errno);
        close(fd);
        return;
    }

    if (rv != len) {
        throwIOException(env, "send(fd, req, len, 0)", EMSGSIZE);
        close(fd);
        return;
    }

    struct {
        nlmsghdr h;
        nlmsgerr e;
        char buf[256];
    } resp = {};

    rv = recv(fd, &resp, sizeof(resp), MSG_TRUNC);

    if (rv == -1) {
        throwIOException(env, "recv() failed", errno);
        close(fd);
        return;
    }

    if (rv < (int)NLMSG_SPACE(sizeof(struct nlmsgerr))) {
        jniThrowExceptionFmt(env, "java/io/IOException", "recv() returned short packet: %d", rv);
        close(fd);
        return;
    }

    if (resp.h.nlmsg_len != (unsigned)rv) {
        jniThrowExceptionFmt(env, "java/io/IOException",
                             "recv() returned invalid header length: %d != %d", resp.h.nlmsg_len,
                             rv);
        close(fd);
        return;
    }

    if (resp.h.nlmsg_type != NLMSG_ERROR) {
        jniThrowExceptionFmt(env, "java/io/IOException",
                             "recv() did not return NLMSG_ERROR message: %d", resp.h.nlmsg_type);
        close(fd);
        return;
    }

    if (resp.e.error) {  // returns 0 on success
        throwIOException(env, "NLMSG_ERROR message return error", -resp.e.error);
    }
    close(fd);
    return;
}

static int hardwareAddressType(const char* interface) {
    int fd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -errno;

    struct ifreq ifr = {};
    // We use strncpy() instead of strlcpy() since kernel has to be able
    // to handle non-zero terminated junk passed in by userspace anyway,
    // and this way too long interface names (more than IFNAMSIZ-1 = 15
    // characters plus terminating NULL) will not get truncated to 15
    // characters and zero-terminated and thus potentially erroneously
    // match a truncated interface if one were to exist.
    strncpy(ifr.ifr_name, interface, sizeof(ifr.ifr_name));

    int rv;
    if (ioctl(fd, SIOCGIFHWADDR, &ifr, sizeof(ifr))) {
        rv = -errno;
    } else {
        rv = ifr.ifr_hwaddr.sa_family;
    }

    close(fd);
    return rv;
}

// -----------------------------------------------------------------------------
// TODO - just use BpfUtils.h once that is available in sc-mainline-prod and has kernelVersion()
//
// In the mean time copying verbatim from:
//   system/bpf/libbpf_android/include/bpf/BpfUtils.h
// and
//   system/bpf/libbpf_android/BpfUtils.cpp

#define KVER(a, b, c) (((a) << 24) + ((b) << 16) + (c))

static unsigned kernelVersion() {
    struct utsname buf;
    int ret = uname(&buf);
    if (ret) return 0;

    unsigned kver_major;
    unsigned kver_minor;
    unsigned kver_sub;
    char discard;
    ret = sscanf(buf.release, "%u.%u.%u%c", &kver_major, &kver_minor, &kver_sub, &discard);
    // Check the device kernel version
    if (ret < 3) return 0;

    return KVER(kver_major, kver_minor, kver_sub);
}

static inline bool isAtLeastKernelVersion(unsigned major, unsigned minor, unsigned sub) {
    return kernelVersion() >= KVER(major, minor, sub);
}
// -----------------------------------------------------------------------------

static jboolean com_android_networkstack_tethering_BpfUtils_isEthernet(JNIEnv* env, jobject clazz,
                                                                       jstring iface) {
    ScopedUtfChars interface(env, iface);

    int rv = hardwareAddressType(interface.c_str());
    if (rv < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException",
                             "Get hardware address type of interface %s failed: %s",
                             interface.c_str(), strerror(-rv));
        return false;
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
            if (is_pre_5_11_kernel) return false;
    }

    switch (rv) {
        case ARPHRD_ETHER:
            return true;
        case ARPHRD_NONE:
        case ARPHRD_PPP:
        case ARPHRD_RAWIP:
            return false;
        default:
            jniThrowExceptionFmt(env, "java/io/IOException",
                                 "Unknown hardware address type %d on interface %s", rv,
                                 interface.c_str());
            return false;
    }
}

// tc filter add dev .. in/egress prio 1 protocol ipv6/ip bpf object-pinned /sys/fs/bpf/...
// direct-action
static void com_android_networkstack_tethering_BpfUtils_tcFilterAddDevBpf(
        JNIEnv* env, jobject clazz, jint ifIndex, jboolean ingress, jshort prio, jshort proto,
        jstring bpfProgPath) {
    ScopedUtfChars pathname(env, bpfProgPath);

    const int bpfFd = bpf::retrieveProgram(pathname.c_str());
    if (bpfFd == -1) {
        throwIOException(env, "retrieveProgram failed", errno);
        return;
    }

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
                            .tcm_parent = TC_H_MAKE(TC_H_CLSACT,
                                                    ingress ? TC_H_MIN_INGRESS : TC_H_MIN_EGRESS),
                            .tcm_info = static_cast<__u32>((static_cast<uint16_t>(prio) << 16) |
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
            basename(pathname.c_str()));

    // The exception may be thrown from sendAndProcessNetlinkResponse. Close the file descriptor of
    // BPF program before returning the function in any case.
    sendAndProcessNetlinkResponse(env, &req, sizeof(req));
    close(bpfFd);
}

// tc filter del dev .. in/egress prio .. protocol ..
static void com_android_networkstack_tethering_BpfUtils_tcFilterDelDev(JNIEnv* env, jobject clazz,
                                                                       jint ifIndex,
                                                                       jboolean ingress,
                                                                       jshort prio, jshort proto) {
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
                            .tcm_parent = TC_H_MAKE(TC_H_CLSACT,
                                                    ingress ? TC_H_MIN_INGRESS : TC_H_MIN_EGRESS),
                            .tcm_info = static_cast<__u32>((static_cast<uint16_t>(prio) << 16) |
                                                           htons(static_cast<uint16_t>(proto))),
                    },
    };

    sendAndProcessNetlinkResponse(env, &req, sizeof(req));
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"isEthernet", "(Ljava/lang/String;)Z",
         (void*)com_android_networkstack_tethering_BpfUtils_isEthernet},
        {"tcFilterAddDevBpf", "(IZSSLjava/lang/String;)V",
         (void*)com_android_networkstack_tethering_BpfUtils_tcFilterAddDevBpf},
        {"tcFilterDelDev", "(IZSS)V",
         (void*)com_android_networkstack_tethering_BpfUtils_tcFilterDelDev},
};

int register_com_android_networkstack_tethering_BpfUtils(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/networkstack/tethering/BpfUtils", gMethods,
                                    NELEM(gMethods));
}

};  // namespace android
