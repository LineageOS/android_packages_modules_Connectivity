// Copyright (C) 2021 The Android Open Source Project
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

#pragma once

#include <arpa/inet.h>
#include <linux/if.h>

namespace android {
namespace net {
namespace clat {

struct ClatdTracker {
    unsigned ifIndex;
    char iface[IFNAMSIZ];
    unsigned v4ifIndex;
    char v4iface[IFNAMSIZ];
    in_addr v4;
    in6_addr v6;
    in6_addr pfx96;
};

int initMaps(void);
void maybeStartBpf(const ClatdTracker& tracker);
void maybeStopBpf(const ClatdTracker& tracker);

}  // namespace clat
}  // namespace net
}  // namespace android
