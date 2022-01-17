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
 * TcUtilsTest.cpp - unit tests for TcUtils.cpp
 */

#include <gtest/gtest.h>

#include <tcutils/tcutils.h>

#include <errno.h>

namespace android {

TEST(LibTcUtilsTest, IsEthernetOfNonExistingIf) {
  bool result = false;
  int error = isEthernet("not_existing_if", result);
  ASSERT_FALSE(result);
  ASSERT_EQ(-ENODEV, error);
}

TEST(LibTcUtilsTest, IsEthernetOfLoopback) {
  bool result = false;
  int error = isEthernet("lo", result);
  ASSERT_FALSE(result);
  ASSERT_EQ(-EAFNOSUPPORT, error);
}

// If wireless 'wlan0' interface exists it should be Ethernet.
// See also HardwareAddressTypeOfWireless.
TEST(LibTcUtilsTest, IsEthernetOfWireless) {
  bool result = false;
  int error = isEthernet("wlan0", result);
  if (!result && error == -ENODEV)
    return;

  ASSERT_EQ(0, error);
  ASSERT_TRUE(result);
}

// If cellular 'rmnet_data0' interface exists it should
// *probably* not be Ethernet and instead be RawIp.
// See also HardwareAddressTypeOfCellular.
TEST(LibTcUtilsTest, IsEthernetOfCellular) {
  bool result = false;
  int error = isEthernet("rmnet_data0", result);
  if (!result && error == -ENODEV)
    return;

  ASSERT_EQ(0, error);
  ASSERT_FALSE(result);
}

} // namespace android
