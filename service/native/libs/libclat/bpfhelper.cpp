/*
 * Copyright 2021 The Android Open Source Project
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
 * main.c - main function
 */
#define LOG_TAG "bpfhelper"

#include "libclat/bpfhelper.h"

#include <android-base/unique_fd.h>
#include <log/log.h>

#include "bpf/BpfMap.h"
#include "libclat/TcUtils.h"

#define DEVICEPREFIX "v4-"

using android::base::unique_fd;
using android::net::RAWIP;
using android::net::getClatEgress4MapFd;
using android::net::getClatIngress6MapFd;
using android::net::getClatEgress4ProgFd;
using android::net::getClatIngress6ProgFd;
using android::net::tcQdiscAddDevClsact;
using android::net::tcFilterAddDevEgressClatIpv4;
using android::net::tcFilterAddDevIngressClatIpv6;
using android::net::tcFilterDelDevEgressClatIpv4;
using android::net::tcFilterDelDevIngressClatIpv6;
using android::bpf::BpfMap;

BpfMap<ClatEgress4Key, ClatEgress4Value> mClatEgress4Map;
BpfMap<ClatIngress6Key, ClatIngress6Value> mClatIngress6Map;

namespace android {
namespace net {
namespace clat {

// TODO: have a clearMap function to remove all stubs while system server crash.
// For long term, move bpf access into java and map initialization should live
// ClatCoordinator constructor.
int initMaps(void) {
    int rv = getClatEgress4MapFd();
    if (rv < 0) {
        ALOGE("getClatEgress4MapFd() failure: %s", strerror(-rv));
        return -rv;
    }
    mClatEgress4Map.reset(rv);

    rv = getClatIngress6MapFd();
    if (rv < 0) {
        ALOGE("getClatIngress6MapFd() failure: %s", strerror(-rv));
        mClatEgress4Map.reset(-1);
        return -rv;
    }
    mClatIngress6Map.reset(rv);

    return 0;
}

void maybeStartBpf(const ClatdTracker& tracker) {
    auto isEthernet = android::net::isEthernet(tracker.iface);
    if (!isEthernet.ok()) {
        ALOGE("isEthernet(%s[%d]) failure: %s", tracker.iface, tracker.ifIndex,
              isEthernet.error().message().c_str());
        return;
    }

    // This program will be attached to the v4-* interface which is a TUN and thus always rawip.
    int rv = getClatEgress4ProgFd(RAWIP);
    if (rv < 0) {
        ALOGE("getClatEgress4ProgFd(RAWIP) failure: %s", strerror(-rv));
        return;
    }
    unique_fd txRawIpProgFd(rv);

    rv = getClatIngress6ProgFd(isEthernet.value());
    if (rv < 0) {
        ALOGE("getClatIngress6ProgFd(%d) failure: %s", isEthernet.value(), strerror(-rv));
        return;
    }
    unique_fd rxProgFd(rv);

    ClatEgress4Key txKey = {
            .iif = tracker.v4ifIndex,
            .local4 = tracker.v4,
    };
    ClatEgress4Value txValue = {
            .oif = tracker.ifIndex,
            .local6 = tracker.v6,
            .pfx96 = tracker.pfx96,
            .oifIsEthernet = isEthernet.value(),
    };

    auto ret = mClatEgress4Map.writeValue(txKey, txValue, BPF_ANY);
    if (!ret.ok()) {
        ALOGE("mClatEgress4Map.writeValue failure: %s", strerror(ret.error().code()));
        return;
    }

    ClatIngress6Key rxKey = {
            .iif = tracker.ifIndex,
            .pfx96 = tracker.pfx96,
            .local6 = tracker.v6,
    };
    ClatIngress6Value rxValue = {
            // TODO: move all the clat code to eBPF and remove the tun interface entirely.
            .oif = tracker.v4ifIndex,
            .local4 = tracker.v4,
    };

    ret = mClatIngress6Map.writeValue(rxKey, rxValue, BPF_ANY);
    if (!ret.ok()) {
        ALOGE("mClatIngress6Map.writeValue failure: %s", strerror(ret.error().code()));
        ret = mClatEgress4Map.deleteValue(txKey);
        if (!ret.ok())
            ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        return;
    }

    // We do tc setup *after* populating the maps, so scanning through them
    // can always be used to tell us what needs cleanup.

    // Usually the clsact will be added in RouteController::addInterfaceToPhysicalNetwork.
    // But clat is started before the v4- interface is added to the network. The clat startup have
    // to add clsact of v4- tun interface first for adding bpf filter in maybeStartBpf.
    // TODO: move "qdisc add clsact" of v4- tun interface out from ClatdController.
    rv = tcQdiscAddDevClsact(tracker.v4ifIndex);
    if (rv) {
        ALOGE("tcQdiscAddDevClsact(%d[%s]) failure: %s", tracker.v4ifIndex, tracker.v4iface,
              strerror(-rv));
        ret = mClatEgress4Map.deleteValue(txKey);
        if (!ret.ok())
            ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        ret = mClatIngress6Map.deleteValue(rxKey);
        if (!ret.ok())
            ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));
        return;
    }

    rv = tcFilterAddDevEgressClatIpv4(tracker.v4ifIndex, txRawIpProgFd, RAWIP);
    if (rv) {
        ALOGE("tcFilterAddDevEgressClatIpv4(%d[%s], RAWIP) failure: %s", tracker.v4ifIndex,
              tracker.v4iface, strerror(-rv));

        // The v4- interface clsact is not deleted for unwinding error because once it is created
        // with interface addition, the lifetime is till interface deletion. Moreover, the clsact
        // has no clat filter now. It should not break anything.

        ret = mClatEgress4Map.deleteValue(txKey);
        if (!ret.ok())
            ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        ret = mClatIngress6Map.deleteValue(rxKey);
        if (!ret.ok())
            ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));
        return;
    }

    rv = tcFilterAddDevIngressClatIpv6(tracker.ifIndex, rxProgFd, isEthernet.value());
    if (rv) {
        ALOGE("tcFilterAddDevIngressClatIpv6(%d[%s], %d) failure: %s", tracker.ifIndex,
              tracker.iface, isEthernet.value(), strerror(-rv));
        rv = tcFilterDelDevEgressClatIpv4(tracker.v4ifIndex);
        if (rv) {
            ALOGE("tcFilterDelDevEgressClatIpv4(%d[%s]) failure: %s", tracker.v4ifIndex,
                  tracker.v4iface, strerror(-rv));
        }

        // The v4- interface clsact is not deleted. See the reason in the error unwinding code of
        // the egress filter attaching of v4- tun interface.

        ret = mClatEgress4Map.deleteValue(txKey);
        if (!ret.ok())
            ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        ret = mClatIngress6Map.deleteValue(rxKey);
        if (!ret.ok())
            ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));
        return;
    }

    // success
}

void maybeStopBpf(const ClatdTracker& tracker) {
    int rv = tcFilterDelDevIngressClatIpv6(tracker.ifIndex);
    if (rv < 0) {
        ALOGE("tcFilterDelDevIngressClatIpv6(%d[%s]) failure: %s", tracker.ifIndex, tracker.iface,
              strerror(-rv));
    }

    rv = tcFilterDelDevEgressClatIpv4(tracker.v4ifIndex);
    if (rv < 0) {
        ALOGE("tcFilterDelDevEgressClatIpv4(%d[%s]) failure: %s", tracker.v4ifIndex,
              tracker.v4iface, strerror(-rv));
    }

    // We cleanup the maps last, so scanning through them can be used to
    // determine what still needs cleanup.

    ClatEgress4Key txKey = {
            .iif = tracker.v4ifIndex,
            .local4 = tracker.v4,
    };

    auto ret = mClatEgress4Map.deleteValue(txKey);
    if (!ret.ok()) ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));

    ClatIngress6Key rxKey = {
            .iif = tracker.ifIndex,
            .pfx96 = tracker.pfx96,
            .local6 = tracker.v6,
    };

    ret = mClatIngress6Map.deleteValue(rxKey);
    if (!ret.ok()) ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));
}

}  // namespace clat
}  // namespace net
}  // namespace android
