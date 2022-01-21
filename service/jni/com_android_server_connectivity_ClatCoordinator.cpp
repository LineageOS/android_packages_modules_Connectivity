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

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/if_tun.h>
#include <linux/ioctl.h>
#include <nativehelper/JNIHelp.h>
#include <net/if.h>

#include <netjniutils/netjniutils.h>

#include "libclat/clatutils.h"
#include "nativehelper/scoped_utf_chars.h"

// Sync from system/netd/include/netid_client.h
#define MARK_UNSET 0u

namespace android {
static void throwIOException(JNIEnv* env, const char* msg, int error) {
    jniThrowExceptionFmt(env, "java/io/IOException", "%s: %s", msg, strerror(error));
}

jstring com_android_server_connectivity_ClatCoordinator_selectIpv4Address(JNIEnv* env,
                                                                          jobject clazz,
                                                                          jstring v4addr,
                                                                          jint prefixlen) {
    ScopedUtfChars address(env, v4addr);
    in_addr ip;
    if (inet_pton(AF_INET, address.c_str(), &ip) != 1) {
        throwIOException(env, "invalid address", EINVAL);
        return nullptr;
    }

    // Pick an IPv4 address.
    // TODO: this picks the address based on other addresses that are assigned to interfaces, but
    // the address is only actually assigned to an interface once clatd starts up. So we could end
    // up with two clatd instances with the same IPv4 address.
    // Stop doing this and instead pick a free one from the kV4Addr pool.
    in_addr v4 = {net::clat::selectIpv4Address(ip, prefixlen)};
    if (v4.s_addr == INADDR_NONE) {
        jniThrowExceptionFmt(env, "java/io/IOException", "No free IPv4 address in %s/%d",
                             address.c_str(), prefixlen);
        return nullptr;
    }

    char addrstr[INET_ADDRSTRLEN];
    if (!inet_ntop(AF_INET, (void*)&v4, addrstr, sizeof(addrstr))) {
        throwIOException(env, "invalid address", EADDRNOTAVAIL);
        return nullptr;
    }
    return env->NewStringUTF(addrstr);
}

// Picks a random interface ID that is checksum neutral with the IPv4 address and the NAT64 prefix.
jstring com_android_server_connectivity_ClatCoordinator_generateIpv6Address(
        JNIEnv* env, jobject clazz, jstring ifaceStr, jstring v4Str, jstring prefix64Str) {
    ScopedUtfChars iface(env, ifaceStr);
    ScopedUtfChars addr4(env, v4Str);
    ScopedUtfChars prefix64(env, prefix64Str);

    if (iface.c_str() == nullptr) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid null interface name");
        return nullptr;
    }

    in_addr v4;
    if (inet_pton(AF_INET, addr4.c_str(), &v4) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid clat v4 address %s",
                             addr4.c_str());
        return nullptr;
    }

    in6_addr nat64Prefix;
    if (inet_pton(AF_INET6, prefix64.c_str(), &nat64Prefix) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid prefix %s", prefix64.c_str());
        return nullptr;
    }

    in6_addr v6;
    if (net::clat::generateIpv6Address(iface.c_str(), v4, nat64Prefix, &v6)) {
        jniThrowExceptionFmt(env, "java/io/IOException",
                             "Unable to find global source address on %s for %s", iface.c_str(),
                             prefix64.c_str());
        return nullptr;
    }

    char addrstr[INET6_ADDRSTRLEN];
    if (!inet_ntop(AF_INET6, (void*)&v6, addrstr, sizeof(addrstr))) {
        throwIOException(env, "invalid address", EADDRNOTAVAIL);
        return nullptr;
    }
    return env->NewStringUTF(addrstr);
}

static jint com_android_server_connectivity_ClatCoordinator_createTunInterface(JNIEnv* env,
                                                                               jobject clazz,
                                                                               jstring tuniface) {
    ScopedUtfChars v4interface(env, tuniface);

    // open the tun device in non blocking mode as required by clatd
    jint fd = open("/dev/net/tun", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd == -1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "open tun device failed (%s)",
                             strerror(errno));
        return -1;
    }

    struct ifreq ifr = {
            .ifr_flags = IFF_TUN,
    };
    strlcpy(ifr.ifr_name, v4interface.c_str(), sizeof(ifr.ifr_name));

    if (ioctl(fd, TUNSETIFF, &ifr, sizeof(ifr))) {
        close(fd);
        jniThrowExceptionFmt(env, "java/io/IOException", "ioctl(TUNSETIFF) failed (%s)",
                             strerror(errno));
        return -1;
    }

    return fd;
}

static jint com_android_server_connectivity_ClatCoordinator_detectMtu(JNIEnv* env, jobject clazz,
                                                                      jstring platSubnet,
                                                                      jint plat_suffix, jint mark) {
    ScopedUtfChars platSubnetStr(env, platSubnet);

    in6_addr plat_subnet;
    if (inet_pton(AF_INET6, platSubnetStr.c_str(), &plat_subnet) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid plat prefix address %s",
                             platSubnetStr.c_str());
        return -1;
    }

    int ret = net::clat::detect_mtu(&plat_subnet, plat_suffix, mark);
    if (ret < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "detect mtu failed: %s", strerror(-ret));
        return -1;
    }

    return ret;
}

static jint com_android_server_connectivity_ClatCoordinator_openPacketSocket(JNIEnv* env,
                                                                              jobject clazz) {
    // Will eventually be bound to htons(ETH_P_IPV6) protocol,
    // but only after appropriate bpf filter is attached.
    int sock = socket(AF_PACKET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (sock < 0) {
        throwIOException(env, "packet socket failed", errno);
        return -1;
    }
    return sock;
}

static jint com_android_server_connectivity_ClatCoordinator_openRawSocket6(JNIEnv* env,
                                                                           jobject clazz,
                                                                           jint mark) {
    int sock = socket(AF_INET6, SOCK_RAW | SOCK_NONBLOCK | SOCK_CLOEXEC, IPPROTO_RAW);
    if (sock < 0) {
        throwIOException(env, "raw socket failed", errno);
        return -1;
    }

    // TODO: check the mark validation
    if (mark != MARK_UNSET && setsockopt(sock, SOL_SOCKET, SO_MARK, &mark, sizeof(mark)) < 0) {
        throwIOException(env, "could not set mark on raw socket", errno);
        close(sock);
        return -1;
    }

    return sock;
}

static void com_android_server_connectivity_ClatCoordinator_addAnycastSetsockopt(
        JNIEnv* env, jobject clazz, jobject javaFd, jstring addr6, jint ifindex) {
    int sock = netjniutils::GetNativeFileDescriptor(env, javaFd);
    if (sock < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid file descriptor");
        return;
    }

    ScopedUtfChars addrStr(env, addr6);

    in6_addr addr;
    if (inet_pton(AF_INET6, addrStr.c_str(), &addr) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid IPv6 address %s",
                             addrStr.c_str());
        return;
    }

    struct ipv6_mreq mreq = {addr, ifindex};
    int ret = setsockopt(sock, SOL_IPV6, IPV6_JOIN_ANYCAST, &mreq, sizeof(mreq));
    if (ret) {
        jniThrowExceptionFmt(env, "java/io/IOException", "setsockopt IPV6_JOIN_ANYCAST failed: %s",
                             strerror(errno));
        return;
    }
}

static void com_android_server_connectivity_ClatCoordinator_configurePacketSocket(
        JNIEnv* env, jobject clazz, jobject javaFd, jstring addr6, jint ifindex) {
    ScopedUtfChars addrStr(env, addr6);

    int sock = netjniutils::GetNativeFileDescriptor(env, javaFd);
    if (sock < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid file descriptor");
        return;
    }

    in6_addr addr;
    if (inet_pton(AF_INET6, addrStr.c_str(), &addr) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid IPv6 address %s",
                             addrStr.c_str());
        return;
    }

    int ret = net::clat::configure_packet_socket(sock, &addr, ifindex);
    if (ret < 0) {
        throwIOException(env, "configure packet socket failed", -ret);
        return;
    }
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"native_selectIpv4Address", "(Ljava/lang/String;I)Ljava/lang/String;",
         (void*)com_android_server_connectivity_ClatCoordinator_selectIpv4Address},
        {"native_generateIpv6Address",
         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
         (void*)com_android_server_connectivity_ClatCoordinator_generateIpv6Address},
        {"native_createTunInterface", "(Ljava/lang/String;)I",
         (void*)com_android_server_connectivity_ClatCoordinator_createTunInterface},
        {"native_detectMtu", "(Ljava/lang/String;II)I",
         (void*)com_android_server_connectivity_ClatCoordinator_detectMtu},
        {"native_openPacketSocket", "()I",
         (void*)com_android_server_connectivity_ClatCoordinator_openPacketSocket},
        {"native_openRawSocket6", "(I)I",
         (void*)com_android_server_connectivity_ClatCoordinator_openRawSocket6},
        {"native_addAnycastSetsockopt", "(Ljava/io/FileDescriptor;Ljava/lang/String;I)V",
         (void*)com_android_server_connectivity_ClatCoordinator_addAnycastSetsockopt},
        {"native_configurePacketSocket", "(Ljava/io/FileDescriptor;Ljava/lang/String;I)V",
         (void*)com_android_server_connectivity_ClatCoordinator_configurePacketSocket},
};

int register_android_server_connectivity_ClatCoordinator(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/connectivity/ClatCoordinator",
                                    gMethods, NELEM(gMethods));
}

};  // namespace android
