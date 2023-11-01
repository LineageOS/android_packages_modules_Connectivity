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

#define LOG_TAG "DnsBpfHelper"

#include "DnsBpfHelper.h"

#include <android-base/logging.h>
#include <android-modules-utils/sdk_level.h>

namespace android {
namespace net {

base::Result<void> DnsBpfHelper::init() {
  if (!android::modules::sdklevel::IsAtLeastT()) {
    LOG(ERROR) << __func__ << ": Unsupported before Android T.";
    return base::Error(EOPNOTSUPP);
  }

  auto result = mConfigurationMap.init(CONFIGURATION_MAP_PATH);
  if (!result.ok()) {
    LOG(ERROR) << __func__ << ": Failed to init configuration_map: "
               << strerror(result.error().code());
    return result;
  }

  result = mUidOwnerMap.init(UID_OWNER_MAP_PATH);
  if (!result.ok()) {
    LOG(ERROR) << __func__ << ": Failed to init uid_owner_map: "
               << strerror(result.error().code());
  }
  return result;
}

base::Result<bool> DnsBpfHelper::isUidNetworkingBlocked(uid_t uid, bool) {
  if (is_system_uid(uid)) return false;
  if (!mConfigurationMap.isValid() || !mUidOwnerMap.isValid()) {
    LOG(ERROR) << __func__
               << ": BPF maps are not ready. Forgot to call ADnsHelper_init?";
    return base::Error(EUNATCH);
  }

  auto enabledRules = mConfigurationMap.readValue(UID_RULES_CONFIGURATION_KEY);
  if (!enabledRules.ok()) {
    LOG(ERROR) << __func__
               << ": Failed to read enabled rules from configuration_map: "
               << strerror(enabledRules.error().code());
    return enabledRules.error();
  }

  auto value = mUidOwnerMap.readValue(uid);
  uint32_t uidRules = value.ok() ? value.value().rule : 0;

  if (isBlockedByUidRules(enabledRules.value(), uidRules)) return true;

  // TODO: Read data saver settings from bpf maps. For metered network, check penalty box, happy box
  // and data saver settings.

  return false;
}

}  // namespace net
}  // namespace android
