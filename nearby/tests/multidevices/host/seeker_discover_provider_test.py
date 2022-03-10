# Lint as: python3
"""CTS-V Nearby Mainline Fast Pair end-to-end test case: seeker can discover the provider."""

import logging
import sys

from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly.controllers import android_device

import fast_pair_provider_simulator
import fast_pair_seeker

# Default model ID to simulate on provider side.
DEFAULT_MODEL_ID = '00000C'
# Default public key to simulate as registered headsets.
DEFAULT_ANTI_SPOOFING_KEY = 'Cbj9eCJrTdDgSYxLkqtfADQi86vIaMvxJsQ298sZYWE='
# Time in seconds for events waiting.
BECOME_DISCOVERABLE_TIMEOUT_SEC = 10
START_ADVERTISING_TIMEOUT_SEC = 5
SCAN_TIMEOUT_SEC = 30

# Abbreviations for common use type.
FastPairProviderSimulator = fast_pair_provider_simulator.FastPairProviderSimulator
FastPairSeeker = fast_pair_seeker.FastPairSeeker


class SeekerDiscoverProviderTest(base_test.BaseTestClass):
    """Fast Pair seeker discover provider test."""

    _provider: FastPairProviderSimulator
    _seeker: FastPairSeeker

    def setup_class(self) -> None:
        super().setup_class()
        self.duts = self.register_controller(android_device)

        # Assume the 1st phone is provider, the 2nd is seeker.
        provider_ad, seeker_ad = self.duts
        provider_ad.debug_tag = 'FastPairProviderSimulator'
        seeker_ad.debug_tag = 'MainlineFastPairSeeker'
        self._provider = FastPairProviderSimulator(provider_ad)
        self._seeker = FastPairSeeker(seeker_ad)
        self._provider.load_snippet()
        self._seeker.load_snippet()

    def setup_test(self) -> None:
        super().setup_test()
        self._provider.start_provider_simulator(DEFAULT_MODEL_ID,
                                                DEFAULT_ANTI_SPOOFING_KEY)
        self._provider.wait_for_discoverable_mode(BECOME_DISCOVERABLE_TIMEOUT_SEC)
        self._provider.wait_for_advertising_start(START_ADVERTISING_TIMEOUT_SEC)
        self._seeker.start_scan()

    def teardown_test(self) -> None:
        super().teardown_test()
        self._seeker.stop_scan()
        self._provider.stop_provider_simulator()
        # Create per-test excepts of logcat.
        for dut in self.duts:
            dut.services.create_output_excerpts_all(self.current_test_info)

    def test_seeker_start_scanning_find_provider(self) -> None:
        provider_ble_mac_address = self._provider.get_ble_mac_address()
        self._seeker.wait_and_assert_provider_found(
            timeout_seconds=SCAN_TIMEOUT_SEC,
            expected_model_id=DEFAULT_MODEL_ID,
            expected_ble_mac_address=provider_ble_mac_address)


if __name__ == '__main__':
    # Take test args
    index = sys.argv.index('--')
    sys.argv = sys.argv[:1] + sys.argv[index + 1:]

    logging.basicConfig(filename="/tmp/seeker_scan_provider_test_log.txt", level=logging.INFO)
    test_runner.main()
