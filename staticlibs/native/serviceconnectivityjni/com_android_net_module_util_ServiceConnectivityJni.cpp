/*
 * Copyright (C) 2024 The Android Open Source Project
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
#include <jni.h>
#include <linux/ethtool.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <linux/ipv6_route.h>
#include <linux/route.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/timerfd.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <android-base/unique_fd.h>
#include <bpf/KernelUtils.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_utf_chars.h>

#define MSEC_PER_SEC 1000
#define NSEC_PER_MSEC 1000000

#ifndef IFF_NO_CARRIER
#define IFF_NO_CARRIER 0x0040
#endif

namespace android {

static jstring getDriverNameForInterface(JNIEnv *env, jclass clazz, jstring jifname) {
  base::unique_fd fd(socket(AF_INET6, SOCK_DGRAM, 0));
  if (!fd.ok()) {
    jniThrowErrnoException(env, "getDriverNameForInterface", errno);
    return nullptr;
  }

  ScopedUtfChars ifname(env, jifname);
  if (!ifname.c_str()) {
    jniThrowNullPointerException(env, "getDriverNameForInterface: ifname is null");
    return nullptr;
  }

  ethtool_drvinfo msg = { .cmd = ETHTOOL_GDRVINFO };
  ifreq ifr = { .ifr_data = reinterpret_cast<char *>(&msg) };
  strlcpy(ifr.ifr_name, ifname.c_str(), IFNAMSIZ);

  if (ioctl(fd, SIOCETHTOOL, &ifr)) {
    jniThrowErrnoException(env, "getDriverNameForInterface", errno);
    return nullptr;
  }

  return env->NewStringUTF(msg.driver);
}

static void throwException(JNIEnv *env, int error, const char *action,
                           const char *iface) {
  const std::string &msg = "Error: " + std::string(action) + " " +
                           std::string(iface) + ": " +
                           std::string(strerror(error));
  jniThrowException(env, "java/lang/IllegalStateException", msg.c_str());
}

// enable or disable  carrier on tun / tap interface.
static void setTunTapCarrierEnabledImpl(JNIEnv *env, const char *iface,
                                        int tunFd, bool enabled) {
  uint32_t carrierOn = enabled;
  if (ioctl(tunFd, TUNSETCARRIER, &carrierOn)) {
    throwException(env, errno, "set carrier", iface);
  }
}

static int createTunTapImpl(JNIEnv *env, bool isTun, bool hasCarrier,
                            bool setIffMulticast, const char *iface) {
  base::unique_fd tun(open("/dev/tun", O_RDWR | O_NONBLOCK));
  ifreq ifr{};

  // Allocate interface.
  ifr.ifr_flags = (isTun ? IFF_TUN : IFF_TAP) | IFF_NO_PI;
  if (!hasCarrier) {
    // Using IFF_NO_CARRIER is supported starting in kernel version >= 6.0
    // Up until then, unsupported flags are ignored.
    if (!bpf::isAtLeastKernelVersion(6, 0)) {
      throwException(env, EOPNOTSUPP, "IFF_NO_CARRIER not supported",
                     ifr.ifr_name);
      return -1;
    }
    ifr.ifr_flags |= IFF_NO_CARRIER;
  }
  strlcpy(ifr.ifr_name, iface, IFNAMSIZ);
  if (ioctl(tun.get(), TUNSETIFF, &ifr)) {
    throwException(env, errno, "allocating", ifr.ifr_name);
    return -1;
  }

  // Mark some TAP interfaces as supporting multicast
  if (setIffMulticast && !isTun) {
    base::unique_fd inet6CtrlSock(socket(AF_INET6, SOCK_DGRAM, 0));
    ifr.ifr_flags = IFF_MULTICAST;

    if (ioctl(inet6CtrlSock.get(), SIOCSIFFLAGS, &ifr)) {
      throwException(env, errno, "set IFF_MULTICAST", ifr.ifr_name);
      return -1;
    }
  }

  return tun.release();
}

static void bringUpInterfaceImpl(JNIEnv *env, const char *iface) {
  // Activate interface using an unconnected datagram socket.
  base::unique_fd inet6CtrlSock(socket(AF_INET6, SOCK_DGRAM, 0));

  ifreq ifr{};
  strlcpy(ifr.ifr_name, iface, IFNAMSIZ);
  if (ioctl(inet6CtrlSock.get(), SIOCGIFFLAGS, &ifr)) {
    throwException(env, errno, "read flags", iface);
    return;
  }
  ifr.ifr_flags |= IFF_UP;
  if (ioctl(inet6CtrlSock.get(), SIOCSIFFLAGS, &ifr)) {
    throwException(env, errno, "set IFF_UP", iface);
    return;
  }
}

//------------------------------------------------------------------------------

static void setTunTapCarrierEnabled(JNIEnv *env, jclass /* clazz */,
                                    jstring jIface, jint tunFd,
                                    jboolean enabled) {
  ScopedUtfChars iface(env, jIface);
  if (!iface.c_str()) {
    jniThrowNullPointerException(env, "iface");
    return;
  }
  setTunTapCarrierEnabledImpl(env, iface.c_str(), tunFd, enabled);
}

static jint createTunTap(JNIEnv *env, jclass /* clazz */, jboolean isTun,
                         jboolean hasCarrier, jboolean setIffMulticast,
                         jstring jIface) {
  ScopedUtfChars iface(env, jIface);
  if (!iface.c_str()) {
    jniThrowNullPointerException(env, "iface");
    return -1;
  }

  return createTunTapImpl(env, isTun, hasCarrier, setIffMulticast,
                          iface.c_str());
}

static void bringUpInterface(JNIEnv *env, jclass /* clazz */, jstring jIface) {
  ScopedUtfChars iface(env, jIface);
  if (!iface.c_str()) {
    jniThrowNullPointerException(env, "iface");
    return;
  }
  bringUpInterfaceImpl(env, iface.c_str());
}

//------------------------------------------------------------------------------

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    {"getDriverNameForInterface", "(Ljava/lang/String;)Ljava/lang/String;",
      (void *)getDriverNameForInterface},
    {"setTunTapCarrierEnabled", "(Ljava/lang/String;IZ)V",
     (void *)setTunTapCarrierEnabled},
    {"createTunTap", "(ZZZLjava/lang/String;)I", (void *)createTunTap},
    {"bringUpInterface", "(Ljava/lang/String;)V", (void *)bringUpInterface},
};

int register_com_android_net_module_util_ServiceConnectivityJni(
    JNIEnv *env, char const *class_name) {
  return jniRegisterNativeMethods(env, class_name, gMethods, NELEM(gMethods));
}

}; // namespace android
