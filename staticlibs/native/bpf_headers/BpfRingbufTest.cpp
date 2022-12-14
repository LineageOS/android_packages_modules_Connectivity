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

#include <android-base/file.h>
#include <android-base/macros.h>
#include <android-base/result-gmock.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdlib.h>
#include <unistd.h>

#include "bpf/BpfUtils.h"

namespace android {
namespace bpf {
using ::android::base::testing::HasError;
using ::android::base::testing::HasValue;
using ::android::base::testing::WithMessage;
using ::testing::HasSubstr;

class BpfRingbufTest : public ::testing::Test {
 protected:
  BpfRingbufTest()
      : mProgPath("/sys/fs/bpf/prog_bpfRingbufProg_skfilter_ringbuf_test"),
        mRingbufPath("/sys/fs/bpf/map_bpfRingbufProg_test_ringbuf") {}

  void SetUp() {
    if (!android::bpf::isAtLeastKernelVersion(5, 8, 0)) {
      GTEST_SKIP() << "BPF ring buffers not supported";
      return;
    }

    errno = 0;
    mProgram.reset(retrieveProgram(mProgPath.c_str()));
    EXPECT_EQ(errno, 0);
    ASSERT_GE(mProgram.get(), 0)
        << mProgPath << " was either not found or inaccessible.";
  }

  std::string mProgPath;
  std::string mRingbufPath;
  android::base::unique_fd mProgram;
};

TEST_F(BpfRingbufTest, CheckSetUp) {}

}  // namespace bpf
}  // namespace android
