#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

import logging
import re
import time
import types
from mobly import utils
from mobly.controllers import android_device
from mobly.controllers.android_device_lib import adb
from net_tests_utils.host.python import assert_utils

BYTE_DECODE_UTF_8 = 'utf-8'


def set_doze_mode(ad: android_device.AndroidDevice, enable: bool) -> None:
  if enable:
    adb_shell(ad, 'cmd battery unplug')
    expect_dumpsys_state_with_retry(
        ad, 'deviceidle', key='mCharging', expected_state=False
    )
    _set_screen_state(ad, False)
    adb_shell(ad, 'dumpsys deviceidle enable deep')
    expect_dumpsys_state_with_retry(
        ad, 'deviceidle', key='mDeepEnabled', expected_state=True
    )
    adb_shell(ad, 'dumpsys deviceidle force-idle deep')
    expect_dumpsys_state_with_retry(
        ad, 'deviceidle', key='mForceIdle', expected_state=True
    )
  else:
    adb_shell(ad, 'cmd battery reset')
    expect_dumpsys_state_with_retry(
        ad, 'deviceidle', key='mCharging', expected_state=True
    )
    adb_shell(ad, 'dumpsys deviceidle unforce')
    expect_dumpsys_state_with_retry(
        ad, 'deviceidle', key='mForceIdle', expected_state=False
    )


def _set_screen_state(
    ad: android_device.AndroidDevice, target_state: bool
) -> None:
  assert_utils.expect_with_retry(
      predicate=lambda: _get_screen_state(ad) == target_state,
      retry_action=lambda: adb_shell(
          ad, 'input keyevent KEYCODE_POWER'
      ),  # Toggle power key again when retry.
  )


def _get_screen_state(ad: android_device.AndroidDevice) -> bool:
  return get_value_of_key_from_dumpsys(ad, 'power', 'mWakefulness') == 'Awake'


def get_value_of_key_from_dumpsys(
    ad: android_device.AndroidDevice, service: str, key: str
) -> str:
  output = get_dumpsys_for_service(ad, service)
  # Search for key=value pattern from the dumpsys output.
  # e.g. mWakefulness=Awake
  pattern = rf'{key}=(.*)'
  # Only look for the first occurrence.
  match = re.search(pattern, output)
  if match:
    ad.log.debug(
        'Getting key-value from dumpsys: ' + key + '=' + match.group(1)
    )
    return match.group(1)
  else:
    return None


def expect_dumpsys_state_with_retry(
    ad: android_device.AndroidDevice,
    service: str,
    key: str,
    expected_state: bool,
    retry_interval_sec: int = 1,
) -> None:
  def predicate():
    value = get_value_of_key_from_dumpsys(ad, service, key)
    if value is None:
      return False
    return value.lower() == str(expected_state).lower()

  assert_utils.expect_with_retry(
      predicate=predicate,
      retry_interval_sec=retry_interval_sec,
  )


def get_dumpsys_for_service(
    ad: android_device.AndroidDevice, service: str
) -> str:
  return adb_shell(ad, 'dumpsys ' + service)


def unroot_adb(self) -> bytes:
  """Executes the 'adb unroot' command on the device with retry logic.

  This method is intended to be dynamically attached to an AdbProxy instance
  (or similar object) that provides an '_exec_adb_cmd' method for executing
  ADB commands. It attempts to unroot the device, and if an AdbError occurs,
  it retries the command a specified number of times with an exponentially
  increasing delay.

  Args:
    self: The instance of the ADB client (e.g., an AdbProxy object) that has the
      '_exec_adb_cmd' method available to run ADB commands.

  Returns:
    bytes: The raw byte output from the 'adb unroot' command's successful
      execution.

  Raises:
    adb.AdbError: If the 'adb unroot' command fails after all specified
      retry attempts.
  """
  retry_interval = adb.ADB_ROOT_RETRY_ATTEMPT_INTERVAL_SEC
  for attempt in range(adb.ADB_ROOT_RETRY_ATTEMPTS):
    try:
      # Assuming 'self' here is an object that has a '_exec_adb_cmd' method,
      # similar to your original 'root' method's context (e.g., an AdbProxy).
      return self._exec_adb_cmd(
          'unroot', args=None, shell=False, timeout=None, stderr=None
      )
    except adb.AdbError as e:
      if attempt + 1 < adb.ADB_ROOT_RETRY_ATTEMPTS:
        # Decode stderr for logging, as Mobly often provides bytes
        decoded_stderr = e.stderr.decode('utf-8', errors='ignore').strip()
        logging.error(
            f'Retry the command "{utils.cli_cmd_to_string(e.cmd)}" since Error'
            f' "{decoded_stderr}" occurred (attempt'
            f' {attempt + 1}/{adb.ADB_ROOT_RETRY_ATTEMPTS}).'
        )
        time.sleep(retry_interval)
        retry_interval *= 2
      else:
        raise e


def unroot(ad: android_device.AndroidDevice):
  """Dynamically unroots an Android device by attaching 'unroot_adb' to its ADB proxy.

  This function addresses a potential issue where Mobly might attempt to root
  a device upon registration. When running user or userdebug builds,
  this automatic rooting can lead to unexpected or inconsistent device behavior.
  To mitigate this, this function temporarily attaches the 'unroot_adb' method
  to the device's ADB proxy (`ad.adb`), executes it, and then removes it,
  ensuring the device returns to an unrooted state and the ADB proxy's state
  remains clean.

  Args:
    ad: The `android_device.AndroidDevice` instance to be unrooted.
  """
  attach_method_name = 'unroot_adb'
  try:
    setattr(ad.adb, attach_method_name, types.MethodType(unroot_adb, ad.adb))
    getattr(ad.adb, attach_method_name)()
    ad.adb.wait_for_device()
  finally:
    delattr(ad.adb, attach_method_name)


def adb_shell(ad: android_device.AndroidDevice, shell_cmd: str) -> str:
  """Runs adb shell command.

  Args:
    ad: Android device object.
    shell_cmd: string of list of strings, adb shell command.

  Returns:
    string, replies from adb shell command.
  """
  ad.log.debug('Executing adb shell %s', shell_cmd)
  data = ad.adb.shell(shell_cmd)
  return data.decode(BYTE_DECODE_UTF_8).strip()
