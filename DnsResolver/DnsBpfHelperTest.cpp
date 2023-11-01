/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <gtest/gtest.h>
#include <private/android_filesystem_config.h>

#define BPF_MAP_MAKE_VISIBLE_FOR_TESTING
#include "DnsBpfHelper.h"

using namespace android::bpf;  // NOLINT(google-build-using-namespace): exempted

namespace android {
namespace net {

constexpr int TEST_MAP_SIZE = 2;

#define ASSERT_VALID(x) ASSERT_TRUE((x).isValid())

class DnsBpfHelperTest : public ::testing::Test {
 protected:
  DnsBpfHelper mDnsBpfHelper;
  BpfMap<uint32_t, uint32_t> mFakeConfigurationMap;
  BpfMap<uint32_t, UidOwnerValue> mFakeUidOwnerMap;

  void SetUp() {
    mFakeConfigurationMap.resetMap(BPF_MAP_TYPE_ARRAY, CONFIGURATION_MAP_SIZE);
    ASSERT_VALID(mFakeConfigurationMap);

    mFakeUidOwnerMap.resetMap(BPF_MAP_TYPE_HASH, TEST_MAP_SIZE);
    ASSERT_VALID(mFakeUidOwnerMap);

    mDnsBpfHelper.mConfigurationMap = mFakeConfigurationMap;
    ASSERT_VALID(mDnsBpfHelper.mConfigurationMap);
    mDnsBpfHelper.mUidOwnerMap = mFakeUidOwnerMap;
    ASSERT_VALID(mDnsBpfHelper.mUidOwnerMap);
  }

  void ResetAllMaps() {
    mDnsBpfHelper.mConfigurationMap.reset();
    mDnsBpfHelper.mUidOwnerMap.reset();
  }
};

TEST_F(DnsBpfHelperTest, IsUidNetworkingBlocked) {
  struct TestConfig {
    const uid_t uid;
    const uint32_t enabledRules;
    const uint32_t uidRules;
    const int expectedResult;
    std::string toString() const {
      return fmt::format(
          "uid: {}, enabledRules: {}, uidRules: {}, expectedResult: {}",
          uid, enabledRules, uidRules, expectedResult);
    }
  } testConfigs[] = {
    // clang-format off
    //   No rule enabled:
    // uid,         enabledRules,                  uidRules,                      expectedResult
    {AID_APP_START, NO_MATCH,                      NO_MATCH,                      false},

    //   An allowlist rule:
    {AID_APP_START, NO_MATCH,                      DOZABLE_MATCH,                 false},
    {AID_APP_START, DOZABLE_MATCH,                 NO_MATCH,                      true},
    {AID_APP_START, DOZABLE_MATCH,                 DOZABLE_MATCH,                 false},
    //   A denylist rule
    {AID_APP_START, NO_MATCH,                      STANDBY_MATCH,                 false},
    {AID_APP_START, STANDBY_MATCH,                 NO_MATCH,                      false},
    {AID_APP_START, STANDBY_MATCH,                 STANDBY_MATCH,                 true},

    //   Multiple rules enabled:
    //     Match only part of the enabled allowlist rules.
    {AID_APP_START, DOZABLE_MATCH|POWERSAVE_MATCH, DOZABLE_MATCH,                 true},
    {AID_APP_START, DOZABLE_MATCH|POWERSAVE_MATCH, POWERSAVE_MATCH,               true},
    //     Match all of the enabled allowlist rules.
    {AID_APP_START, DOZABLE_MATCH|POWERSAVE_MATCH, DOZABLE_MATCH|POWERSAVE_MATCH, false},
    //     Match allowlist.
    {AID_APP_START, DOZABLE_MATCH|STANDBY_MATCH,   DOZABLE_MATCH,                 false},
    //     Match no rule.
    {AID_APP_START, DOZABLE_MATCH|STANDBY_MATCH,   NO_MATCH,                      true},
    {AID_APP_START, DOZABLE_MATCH|POWERSAVE_MATCH, NO_MATCH,                      true},

    // System UID: always unblocked.
    {AID_SYSTEM,    NO_MATCH,                      NO_MATCH,                      false},
    {AID_SYSTEM,    NO_MATCH,                      DOZABLE_MATCH,                 false},
    {AID_SYSTEM,    DOZABLE_MATCH,                 NO_MATCH,                      false},
    {AID_SYSTEM,    DOZABLE_MATCH,                 DOZABLE_MATCH,                 false},
    {AID_SYSTEM,    NO_MATCH,                      STANDBY_MATCH,                 false},
    {AID_SYSTEM,    STANDBY_MATCH,                 NO_MATCH,                      false},
    {AID_SYSTEM,    STANDBY_MATCH,                 STANDBY_MATCH,                 false},
    {AID_SYSTEM,    DOZABLE_MATCH|POWERSAVE_MATCH, DOZABLE_MATCH,                 false},
    {AID_SYSTEM,    DOZABLE_MATCH|POWERSAVE_MATCH, POWERSAVE_MATCH,               false},
    {AID_SYSTEM,    DOZABLE_MATCH|POWERSAVE_MATCH, DOZABLE_MATCH|POWERSAVE_MATCH, false},
    {AID_SYSTEM,    DOZABLE_MATCH|STANDBY_MATCH,   DOZABLE_MATCH,                 false},
    {AID_SYSTEM,    DOZABLE_MATCH|STANDBY_MATCH,   NO_MATCH,                      false},
    {AID_SYSTEM,    DOZABLE_MATCH|POWERSAVE_MATCH, NO_MATCH,                      false},
    // clang-format on
  };

  for (const auto& config : testConfigs) {
    SCOPED_TRACE(config.toString());

    // Setup maps.
    EXPECT_RESULT_OK(mFakeConfigurationMap.writeValue(UID_RULES_CONFIGURATION_KEY,
                                                      config.enabledRules, BPF_EXIST));
    EXPECT_RESULT_OK(mFakeUidOwnerMap.writeValue(config.uid, {.iif = 0, .rule = config.uidRules},
                                                 BPF_ANY));

    // Verify the function.
    auto result = mDnsBpfHelper.isUidNetworkingBlocked(config.uid, /*metered=*/false);
    EXPECT_TRUE(result.ok());
    EXPECT_EQ(config.expectedResult, result.value());
  }
}

TEST_F(DnsBpfHelperTest, IsUidNetworkingBlocked_uninitialized) {
  ResetAllMaps();

  auto result = mDnsBpfHelper.isUidNetworkingBlocked(AID_APP_START, /*metered=*/false);
  EXPECT_FALSE(result.ok());
  EXPECT_EQ(EUNATCH, result.error().code());

  result = mDnsBpfHelper.isUidNetworkingBlocked(AID_SYSTEM, /*metered=*/false);
  EXPECT_TRUE(result.ok());
  EXPECT_FALSE(result.value());
}

}  // namespace net
}  // namespace android
