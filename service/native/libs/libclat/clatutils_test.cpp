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

#include <android-base/stringprintf.h>
#include <arpa/inet.h>
#include <gtest/gtest.h>

extern "C" {
#include "checksum.h"
}

// Default translation parameters.
static const char kIPv4LocalAddr[] = "192.0.0.4";

namespace android {
namespace net {
namespace clat {

using base::StringPrintf;

class ClatUtils : public ::testing::Test {};

TEST_F(ClatUtils, MakeChecksumNeutral) {
    // We can't test generateIPv6Address here since it requires manipulating routing, which we can't
    // do without talking to the real netd on the system.
    uint32_t rand = arc4random_uniform(0xffffffff);
    uint16_t rand1 = rand & 0xffff;
    uint16_t rand2 = (rand >> 16) & 0xffff;
    std::string v6PrefixStr = StringPrintf("2001:db8:%x:%x", rand1, rand2);
    std::string v6InterfaceAddrStr = StringPrintf("%s::%x:%x", v6PrefixStr.c_str(), rand2, rand1);
    std::string nat64PrefixStr = StringPrintf("2001:db8:%x:%x::", rand2, rand1);

    in_addr v4 = {inet_addr(kIPv4LocalAddr)};
    in6_addr v6InterfaceAddr;
    ASSERT_TRUE(inet_pton(AF_INET6, v6InterfaceAddrStr.c_str(), &v6InterfaceAddr));
    in6_addr nat64Prefix;
    ASSERT_TRUE(inet_pton(AF_INET6, nat64PrefixStr.c_str(), &nat64Prefix));

    // Generate a boatload of random IIDs.
    int onebits = 0;
    uint64_t prev_iid = 0;
    for (int i = 0; i < 100000; i++) {
        in6_addr v6 = v6InterfaceAddr;
        makeChecksumNeutral(&v6, v4, nat64Prefix);

        // Check the generated IP address is in the same prefix as the interface IPv6 address.
        EXPECT_EQ(0, memcmp(&v6, &v6InterfaceAddr, 8));

        // Check that consecutive IIDs are not the same.
        uint64_t iid = *(uint64_t*)(&v6.s6_addr[8]);
        ASSERT_TRUE(iid != prev_iid)
                << "Two consecutive random IIDs are the same: " << std::showbase << std::hex << iid
                << "\n";
        prev_iid = iid;

        // Check that the IID is checksum-neutral with the NAT64 prefix and the
        // local prefix.
        uint16_t c1 = ip_checksum_finish(ip_checksum_add(0, &v4, sizeof(v4)));
        uint16_t c2 = ip_checksum_finish(ip_checksum_add(0, &nat64Prefix, sizeof(nat64Prefix)) +
                                         ip_checksum_add(0, &v6, sizeof(v6)));

        if (c1 != c2) {
            char v6Str[INET6_ADDRSTRLEN];
            inet_ntop(AF_INET6, &v6, v6Str, sizeof(v6Str));
            FAIL() << "Bad IID: " << v6Str << " not checksum-neutral with " << kIPv4LocalAddr
                   << " and " << nat64PrefixStr.c_str() << std::showbase << std::hex
                   << "\n  IPv4 checksum: " << c1 << "\n  IPv6 checksum: " << c2 << "\n";
        }

        // Check that IIDs are roughly random and use all the bits by counting the
        // total number of bits set to 1 in a random sample of 100000 generated IIDs.
        onebits += __builtin_popcountll(*(uint64_t*)&iid);
    }
    EXPECT_LE(3190000, onebits);
    EXPECT_GE(3210000, onebits);
}

}  // namespace clat
}  // namespace net
}  // namespace android
