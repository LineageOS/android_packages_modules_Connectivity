// Copyright (C) 2022 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "libclat/clatutils.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern "C" {
#include "checksum.h"
}

namespace android {
namespace net {
namespace clat {

// Alters the bits in the IPv6 address to make them checksum neutral with v4 and nat64Prefix.
void makeChecksumNeutral(in6_addr* v6, const in_addr v4, const in6_addr& nat64Prefix) {
    // Fill last 8 bytes of IPv6 address with random bits.
    arc4random_buf(&v6->s6_addr[8], 8);

    // Make the IID checksum-neutral. That is, make it so that:
    //   checksum(Local IPv4 | Remote IPv4) = checksum(Local IPv6 | Remote IPv6)
    // in other words (because remote IPv6 = NAT64 prefix | Remote IPv4):
    //   checksum(Local IPv4) = checksum(Local IPv6 | NAT64 prefix)
    // Do this by adjusting the two bytes in the middle of the IID.

    uint16_t middlebytes = (v6->s6_addr[11] << 8) + v6->s6_addr[12];

    uint32_t c1 = ip_checksum_add(0, &v4, sizeof(v4));
    uint32_t c2 = ip_checksum_add(0, &nat64Prefix, sizeof(nat64Prefix)) +
                  ip_checksum_add(0, v6, sizeof(*v6));

    uint16_t delta = ip_checksum_adjust(middlebytes, c1, c2);
    v6->s6_addr[11] = delta >> 8;
    v6->s6_addr[12] = delta & 0xff;
}

// Picks a random interface ID that is checksum neutral with the IPv4 address and the NAT64 prefix.
int generateIpv6Address(const char* iface, const in_addr v4, const in6_addr& nat64Prefix,
                        in6_addr* v6) {
    int s = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s == -1) return -errno;

    if (setsockopt(s, SOL_SOCKET, SO_BINDTODEVICE, iface, strlen(iface) + 1) == -1) {
        close(s);
        return -errno;
    }

    sockaddr_in6 sin6 = {.sin6_family = AF_INET6, .sin6_addr = nat64Prefix};
    if (connect(s, reinterpret_cast<struct sockaddr*>(&sin6), sizeof(sin6)) == -1) {
        close(s);
        return -errno;
    }

    socklen_t len = sizeof(sin6);
    if (getsockname(s, reinterpret_cast<struct sockaddr*>(&sin6), &len) == -1) {
        close(s);
        return -errno;
    }

    *v6 = sin6.sin6_addr;

    if (IN6_IS_ADDR_UNSPECIFIED(v6) || IN6_IS_ADDR_LOOPBACK(v6) || IN6_IS_ADDR_LINKLOCAL(v6) ||
        IN6_IS_ADDR_SITELOCAL(v6) || IN6_IS_ADDR_ULA(v6)) {
        close(s);
        return -ENETUNREACH;
    }

    makeChecksumNeutral(v6, v4, nat64Prefix);
    close(s);

    return 0;
}

}  // namespace clat
}  // namespace net
}  // namespace android
