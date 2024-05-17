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

# Lint as: python3

import logging
import time

from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly.controllers import android_device

class ThreadNetworkMultiDeviceTest(base_test.BaseTestClass):
    def setup_class(self):
        self.node_a, self.node_b = self.register_controller(
            android_device, min_number=2)
        self.node_a.adb.shell([
            'ot-ctl', 'factoryreset',
        ])
        self.node_b.adb.shell([
            'ot-ctl', 'factoryreset',
        ])
        time.sleep(1)

    def ot_ctl(self, node, cmd, expect_done=True):
        args = cmd.split(' ')
        args = ['ot-ctl'] + args
        stdout = node.adb.shell(args).decode('utf-8')
        if expect_done:
            asserts.assert_in('Done', stdout)
        return stdout

    def test_b_should_be_able_to_discover_a(self):
        self.ot_ctl(self.node_a, 'dataset init new')
        self.ot_ctl(self.node_a, 'dataset commit active')
        self.ot_ctl(self.node_a, 'ifconfig up')
        self.ot_ctl(self.node_a, 'thread start')
        self.ot_ctl(self.node_a, 'state leader')
        stdout = self.ot_ctl(self.node_a, 'extaddr')
        extaddr = stdout.splitlines()[0]
        logging.info('node a extaddr: %s', extaddr)
        asserts.assert_equal(len(extaddr), 16)

        stdout = self.ot_ctl(self.node_b, 'scan')
        asserts.assert_in(extaddr, stdout)
        logging.info('discovered node a')


if __name__ == '__main__':
    # Take test args
    if '--' in sys.argv:
        index = sys.argv.index('--')
        sys.argv = sys.argv[:1] + sys.argv[index + 1:]

    test_runner.main()
