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

import time
from absl.testing import parameterized
from mobly import asserts
from net_tests_utils.host.python import apf_test_base, apf_utils
from scapy.layers.l2 import Ether

# Constants.
COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED = 'DROPPED_ETHERTYPE_NOT_ALLOWED'
ETHER_BROADCAST_ADDR = 'FFFFFFFFFFFF'
MIN_PACKET_SIZE = 60


class ApfV4Test(apf_test_base.ApfTestBase, parameterized.TestCase):

  def setup_class(self):
    super().setup_class()
    # Check apf version preconditions.
    caps = apf_utils.get_apf_capabilities(
        self.clientDevice, self.client_iface_name
    )
    if self.client.getVsrApiLevel() >= 34:
      # Enforce APFv4 support for Android 14+ VSR.
      asserts.assert_true(
          caps.apf_version_supported >= 4,
          'APFv4 became mandatory in Android 14 VSR.',
      )
    else:
      # Skip tests for APF version < 4 before Android 14 VSR.
      apf_utils.assume_apf_version_support_at_least(
          self.clientDevice, self.client_iface_name, 4
      )

  # APF L2 packet filtering on V+ Android allows only specific
  # types: IPv4, ARP, IPv6, EAPOL, WAPI.
  # Tests can use any disallowed packet type. Currently,
  # several ethertypes from the legacy ApfFilter denylist are used.
  @parameterized.parameters(
      0x88A2,  # ATA over Ethernet
      0x88A4,  # EtherCAT
      0x88B8,  # GOOSE (Generic Object Oriented Substation event)
      0x88CD,  # SERCOS III
      0x88E3,  # Media Redundancy Protocol (IEC62439-2)
  )  # Declare inputs for state_str and expected_result.
  def test_apf_drop_ethertype_not_allowed(self, blocked_ether_type):
    eth = Ether(
        src=self.server_mac_address,
        dst=self.client_mac_address,
        type=blocked_ether_type,
    )
    packet = bytes(eth).hex()

    # Add zero padding up to minimum ethernet frame length
    packet = packet.ljust(MIN_PACKET_SIZE * 2, '0')

    # Pause packet sending between tests to avoid APF disablement due to high throughput.
    time.sleep(3)
    self.send_packet_and_expect_counter_increased(
        packet, COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED
    )
