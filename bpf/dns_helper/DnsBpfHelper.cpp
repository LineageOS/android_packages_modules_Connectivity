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
#include <android-base/properties.h>
#include <android-modules-utils/sdk_level.h>

namespace android {
namespace net {

#define RETURN_IF_RESULT_NOT_OK(result)                                                            \
  do {                                                                                             \
    if (!result.ok()) {                                                                            \
      LOG(ERROR) << "L" << __LINE__ << " " << __func__ << ": " << strerror(result.error().code()); \
      return result.error();                                                                       \
    }                                                                                              \
  } while (0)

// copied from BpfHandler.cpp
static bool mainlineNetBpfLoadDone() {
  return !access("/sys/fs/bpf/netd_shared/mainline_done", F_OK);
}

// copied from BpfHandler.cpp
static inline void waitForNetProgsLoaded() {
  // infinite loop until success with 5/10/20/40/60/60/60... delay
  for (int delay = 5;; delay *= 2) {
    if (delay > 60) delay = 60;
    if (base::WaitForProperty("init.svc.mdnsd_netbpfload", "stopped", std::chrono::seconds(delay)))
      break;
    LOG(WARNING) << "Waited " << delay << "s for init.svc.mdnsd_netbpfload=stopped, still waiting.";
  }
  if (!mainlineNetBpfLoadDone()) {
    LOG(ERROR) << "FATAL: init.svc.mdnsd_netbpfload=stopped, yet !mainlineNetBpfLoadDone";
    // mdnsd_netbpfload is marked 'reboot_on_failure', init should start a reboot very soon now,
    // spamming logs with an abort is pointless
    for (;;);
  }
}

base::Result<void> DnsBpfHelper::init() {
  if (!android::modules::sdklevel::IsAtLeastT()) {
    LOG(INFO) << "performing Android S mainline NetBpfload magic!";
    if (!mainlineNetBpfLoadDone()) {
      // We're on S/Sv2 & it's the first time netd is starting up (unless crashlooping)
      if (!base::SetProperty("ctl.start", "mdnsd_netbpfload")) {
        LOG(ERROR) << "Failed to set property ctl.start=mdnsd_netbpfload, see dmesg for reason.";
        return base::Error(ENOEXEC);
      }

      LOG(INFO) << "Waiting for Networking BPF programs";
      waitForNetProgsLoaded();
      LOG(INFO) << "Networking BPF programs are loaded";
    }
    return {};
  }

  RETURN_IF_RESULT_NOT_OK(mConfigurationMap.init(CONFIGURATION_MAP_PATH));
  RETURN_IF_RESULT_NOT_OK(mUidOwnerMap.init(UID_OWNER_MAP_PATH));
  RETURN_IF_RESULT_NOT_OK(mDataSaverEnabledMap.init(DATA_SAVER_ENABLED_MAP_PATH));
  RETURN_IF_RESULT_NOT_OK(mUidMigrationEnabledMap.init(UID_MIGRATION_ENABLED_MAP_PATH));
  RETURN_IF_RESULT_NOT_OK(mUidPermissionChunkMap.init(UID_PERMISSION_CHUNK_MAP_PATH));
  RETURN_IF_RESULT_NOT_OK(mUidPermissionMap.init(UID_PERMISSION_MAP_PATH));
  return {};
}

// copied from BpfHandler.cpp
bool DnsBpfHelper::hasUpdateDeviceStatsPermission(uid_t uid) {
  // This implementation is the same logic as method ActivityManager#checkComponentPermission.
  // It implies that real uid can never be same as AID_USER_OFFSET == PER_USER_RANGE == 100000
  uint32_t appId = uid % AID_USER_OFFSET;
  uint32_t mapKey = 0;
  auto isUidMigrationEnabled = mUidMigrationEnabledMap.readValue(mapKey);
  if (isUidMigrationEnabled.ok() && isUidMigrationEnabled.value()) {
    uint32_t chunkId = uid / CHUNK_UID_COUNT;
    uint32_t index = uid / UIDS_PER_INT64 % CHUNK_INT64_COUNT;
    int shift = (uid % UIDS_PER_INT64 * PERMISSION_COUNT) & 63;
    auto chunk = mUidPermissionChunkMap.readValue(chunkId);
    if (chunk.ok() && (chunk.value().block[index] >> shift) & PERMISSION_BIT_UPDATE_DEVICE_STATS) {
      return true;
    }
  } else {
    auto permission = mUidPermissionMap.readValue(appId);
    if (permission.ok() && (permission.value() & BPF_PERMISSION_UPDATE_DEVICE_STATS)) {
      return true;
    }
  }
  return ((appId == AID_ROOT) || (appId == AID_SYSTEM) || (appId == AID_DNS));
}

base::Result<bool> DnsBpfHelper::isUidNetworkingBlocked(uid_t uid, bool metered) {
  if (is_system_uid(uid)) return false;
  if (!mConfigurationMap.isValid() || !mUidOwnerMap.isValid()) {
    LOG(ERROR) << __func__
               << ": BPF maps are not ready. Forgot to call ADnsHelper_init?";
    return base::Error(EUNATCH);
  }

  auto enabledRules = mConfigurationMap.readValue(UID_RULES_CONFIGURATION_KEY);
  RETURN_IF_RESULT_NOT_OK(enabledRules);

  auto value = mUidOwnerMap.readValue(uid);
  uint32_t uidRules = value.ok() ? value.value().rule : 0;

  // For doze mode, battery saver, low power standby.
  if (isBlockedByUidRules(enabledRules.value(), uidRules)) return true;

  // For data saver.
  // DataSaverEnabled map on V+ platforms is the only reliable source of information about the
  // current data saver status. While ConnectivityService offers two ways to update this map for U
  // and V+, the U- platform implementation can have delays, potentially leading to inaccurate
  // results. Conversely, the V+ platform implementation is synchronized with the actual data saver
  // state, making it a trustworthy source. Since this library primarily serves DNS resolvers,
  // relying solely on V+ data prevents erroneous blocking of DNS queries.
  if (android::modules::sdklevel::IsAtLeastV() && metered) {
    // The background data setting (PENALTY_BOX_USER_MATCH, PENALTY_BOX_ADMIN_MATCH) and
    // unrestricted data usage setting (HAPPY_BOX_MATCH) for individual apps override the system
    // wide Data Saver setting.
    if (uidRules & (PENALTY_BOX_USER_MATCH | PENALTY_BOX_ADMIN_MATCH)) return true;
    if (uidRules & HAPPY_BOX_MATCH) return false;

    auto dataSaverSetting = mDataSaverEnabledMap.readValue(DATA_SAVER_ENABLED_KEY);
    RETURN_IF_RESULT_NOT_OK(dataSaverSetting);
    return dataSaverSetting.value();
  }

  return false;
}

base::Result<bool> DnsBpfHelper::isUidOemDenied(uid_t uid) {
  if (is_system_uid(uid)) return false;
  if (!mConfigurationMap.isValid() || !mUidOwnerMap.isValid()) {
    LOG(ERROR) << __func__
               << ": BPF maps are not ready. Forgot to call ADnsHelper_init?";
    return base::Error(EUNATCH);
  }

  auto enabledRules = mConfigurationMap.readValue(UID_RULES_CONFIGURATION_KEY);
  RETURN_IF_RESULT_NOT_OK(enabledRules);

  auto value = mUidOwnerMap.readValue(uid);
  uint32_t uidRules = value.ok() ? value.value().rule : 0;
  return (enabledRules.value() & uidRules &
          (OEM_DENY_1_MATCH | OEM_DENY_2_MATCH | OEM_DENY_3_MATCH)) != 0;
}

}  // namespace net
}  // namespace android
