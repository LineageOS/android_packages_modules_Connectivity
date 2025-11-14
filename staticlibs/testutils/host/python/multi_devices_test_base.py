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

from mobly import asserts
from mobly import base_test
from mobly import utils
from mobly.controllers import android_device
from net_tests_utils.host.python import adb_utils

CONNECTIVITY_MULTI_DEVICES_SNIPPET_PACKAGE = 'com.google.snippet.connectivity'


class MultiDevicesTestBase(base_test.BaseTestClass):

  def setup_class(self):
    # Declare that two Android devices are needed.
    self.clientDevice, self.serverDevice = self.register_controller(
        android_device, min_number=2
    )

    if self.clientDevice.is_adb_root:
      adb_utils.unroot(self.clientDevice)

    if self.serverDevice.is_adb_root:
      adb_utils.unroot(self.serverDevice)

    asserts.assert_false(
        self.clientDevice.is_adb_root, 'client device should be unroot'
    )
    asserts.assert_false(
        self.serverDevice.is_adb_root, 'server device should be unroot'
    )

    def setup_device(device):
      device.load_snippet(
          'connectivity_multi_devices_snippet',
          CONNECTIVITY_MULTI_DEVICES_SNIPPET_PACKAGE,
      )

    # Set up devices in parallel to save time.
    utils.concurrent_exec(
        setup_device,
        ((self.clientDevice,), (self.serverDevice,)),
        max_workers=2,
        raise_on_exception=True,
    )
    self.client = self.clientDevice.connectivity_multi_devices_snippet
    self.server = self.serverDevice.connectivity_multi_devices_snippet
