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

"""Main entrypoint for all of unittest."""

import sys
from host.python.adb_utils_test import TestAdbUtils
from host.python.apf_utils_test import TestApfUtils
from host.python.assert_utils_test import TestAssertUtils
from mobly import suite_runner


if __name__ == '__main__':
  # For MoblyBinaryHostTest, this entry point will be called twice:
  # 1. List tests.
  #   <mobly-par-file-name> -- --list_tests
  # 2. Run tests.
  #   <mobly-par-file-name> -- --config=<yaml-path> --device_serial=<device-serial> --log_path=<log-path>
  # Strip the "--" since suite runner doesn't recognize it.
  sys.argv.pop(1)
  # TODO: make the tests can be executed without manually list classes.
  suite_runner.run_suite(
      [TestAssertUtils, TestAdbUtils, TestApfUtils], sys.argv
  )
