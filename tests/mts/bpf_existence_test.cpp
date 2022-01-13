/*
 * Copyright 2022 The Android Open Source Project
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
 * bpf_existence_test.cpp - checks that the device runs expected BPF programs
 */

#include <cstdint>
#include <string>
#include <vector>

#include <android-base/strings.h>
#include <android-base/properties.h>
#include <android-modules-utils/sdk_level.h>

#include <gtest/gtest.h>

using std::find;
using std::string;
using std::vector;

using android::modules::sdklevel::IsAtLeastR;
using android::modules::sdklevel::IsAtLeastS;
using android::modules::sdklevel::IsAtLeastT;

class BpfExistenceTest : public ::testing::Test {
};

static const vector<string> INTRODUCED_R = {
    "/sys/fs/bpf/prog_offload_schedcls_ingress_tether_ether",
    "/sys/fs/bpf/prog_offload_schedcls_ingress_tether_rawip",
};

static const vector<string> INTRODUCED_S = {
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_downstream4_ether",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_downstream4_rawip",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_downstream6_ether",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_downstream6_rawip",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_upstream4_ether",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_upstream4_rawip",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_upstream6_ether",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_upstream6_rawip",
};

static const vector<string> REMOVED_S = {
    "/sys/fs/bpf/prog_offload_schedcls_ingress_tether_ether",
    "/sys/fs/bpf/prog_offload_schedcls_ingress_tether_rawip",
};

static const vector<string> INTRODUCED_T = {
};

static const vector<string> REMOVED_T = {
};

void addAll(vector<string>* a, const vector<string>& b) {
    a->insert(a->end(), b.begin(), b.end());
}

void removeAll(vector<string>* a, const vector<string> b) {
    for (const auto& toRemove : b) {
        auto iter = find(a->begin(), a->end(), toRemove);
        while (iter != a->end()) {
            a->erase(iter);
            iter = find(a->begin(), a->end(), toRemove);
        }
    }
}

void getFileLists(vector<string>* expected, vector<string>* unexpected) {
    unexpected->clear();
    expected->clear();

    addAll(unexpected, INTRODUCED_R);
    addAll(unexpected, INTRODUCED_S);
    addAll(unexpected, INTRODUCED_T);

    if (IsAtLeastR()) {
        addAll(expected, INTRODUCED_R);
        removeAll(unexpected, INTRODUCED_R);
        // Nothing removed in R.
    }

    if (IsAtLeastS()) {
        addAll(expected, INTRODUCED_S);
        removeAll(expected, REMOVED_S);

        addAll(unexpected, REMOVED_S);
        removeAll(unexpected, INTRODUCED_S);
    }

    // Nothing added or removed in SCv2.

    if (IsAtLeastT()) {
        addAll(expected, INTRODUCED_T);
        removeAll(expected, REMOVED_T);

        addAll(unexpected, REMOVED_T);
        removeAll(unexpected, INTRODUCED_T);
    }
}

void checkFiles() {
    vector<string> mustExist;
    vector<string> mustNotExist;

    getFileLists(&mustExist, &mustNotExist);

    for (const auto& file : mustExist) {
        EXPECT_EQ(0, access(file.c_str(), R_OK)) << file << " does not exist";
    }
    for (const auto& file : mustNotExist) {
        int ret = access(file.c_str(), R_OK);
        int err = errno;
        EXPECT_EQ(-1, ret) << file << " unexpectedly exists";
        if (ret == -1) {
            EXPECT_EQ(ENOENT, err) << " accessing " << file << " failed with errno " << err;
        }
    }
}

TEST_F(BpfExistenceTest, TestPrograms) {
    // Pre-flight check to ensure test has been updated.
    uint64_t buildVersionSdk = android::base::GetUintProperty<uint64_t>("ro.build.version.sdk", 0);
    ASSERT_NE(0, buildVersionSdk) << "Unable to determine device SDK version";
    if (buildVersionSdk > 33 && buildVersionSdk != 10000) {
            FAIL() << "Unknown OS version " << buildVersionSdk << ", please update this test";
    }

    // Only unconfined root is guaranteed to be able to access everything in /sys/fs/bpf.
    ASSERT_EQ(0, getuid()) << "This test must run as root.";

    checkFiles();
}
