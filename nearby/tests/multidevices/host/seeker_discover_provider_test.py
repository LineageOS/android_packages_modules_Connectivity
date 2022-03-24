#  Copyright (C) 2022 The Android Open Source Project
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

"""CTS-V Nearby Mainline Fast Pair end-to-end test case: seeker can discover the provider."""

from typing import List

from mobly import base_test
from mobly.controllers import android_device

from test_helper import constants
from test_helper import fast_pair_provider_simulator
from test_helper import fast_pair_seeker

# The model ID to simulate on provider side.
PROVIDER_SIMULATOR_MODEL_ID = constants.DEFAULT_MODEL_ID
# The public key to simulate as registered headsets.
PROVIDER_SIMULATOR_ANTI_SPOOFING_KEY = constants.DEFAULT_ANTI_SPOOFING_KEY

# Time in seconds for events waiting.
SETUP_TIMEOUT_SEC = constants.SETUP_TIMEOUT_SEC
BECOME_DISCOVERABLE_TIMEOUT_SEC = constants.BECOME_DISCOVERABLE_TIMEOUT_SEC
START_ADVERTISING_TIMEOUT_SEC = constants.START_ADVERTISING_TIMEOUT_SEC
SCAN_TIMEOUT_SEC = constants.SCAN_TIMEOUT_SEC

# Abbreviations for common use type.
FastPairProviderSimulator = fast_pair_provider_simulator.FastPairProviderSimulator
FastPairSeeker = fast_pair_seeker.FastPairSeeker


class SeekerDiscoverProviderTest(base_test.BaseTestClass):
    """Fast Pair seeker discover provider test."""

    _duts: List[android_device.AndroidDevice]
    _provider: FastPairProviderSimulator
    _seeker: FastPairSeeker

    def setup_class(self) -> None:
        super().setup_class()
        self._duts = self.register_controller(android_device)

        # Assume the 1st phone is provider, the 2nd is seeker.
        provider_ad, seeker_ad = self._duts
        self._provider = FastPairProviderSimulator(provider_ad)
        self._seeker = FastPairSeeker(seeker_ad)
        self._provider.load_snippet()
        self._seeker.load_snippet()

    def setup_test(self) -> None:
        super().setup_test()
        self._provider.setup_provider_simulator(SETUP_TIMEOUT_SEC)
        self._provider.start_model_id_advertising(
            PROVIDER_SIMULATOR_MODEL_ID, PROVIDER_SIMULATOR_ANTI_SPOOFING_KEY)
        self._provider.wait_for_discoverable_mode(BECOME_DISCOVERABLE_TIMEOUT_SEC)
        self._provider.wait_for_advertising_start(START_ADVERTISING_TIMEOUT_SEC)
        self._seeker.start_scan()

    def teardown_test(self) -> None:
        super().teardown_test()
        self._seeker.stop_scan()
        self._provider.teardown_provider_simulator()
        # Create per-test excepts of logcat.
        for dut in self._duts:
            dut.services.create_output_excerpts_all(self.current_test_info)

    def test_seeker_start_scanning_find_provider(self) -> None:
        provider_ble_mac_address = self._provider.get_ble_mac_address()
        self._seeker.wait_and_assert_provider_found(
            timeout_seconds=SCAN_TIMEOUT_SEC,
            expected_model_id=PROVIDER_SIMULATOR_MODEL_ID,
            expected_ble_mac_address=provider_ble_mac_address)
