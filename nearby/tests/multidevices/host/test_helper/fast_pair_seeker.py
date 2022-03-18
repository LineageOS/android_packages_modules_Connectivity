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

"""Fast Pair seeker role."""

from mobly import asserts
from mobly.controllers import android_device
from mobly.controllers.android_device_lib import snippet_event

from test_helper import event_helper

# The package name of the Nearby Mainline Fast Pair seeker Mobly snippet.
FP_SEEKER_SNIPPETS_PACKAGE = 'android.nearby.multidevices'

# Events reported from the seeker snippet.
ON_PROVIDER_FOUND_EVENT = 'onDiscovered'

# Abbreviations for common use type.
AndroidDevice = android_device.AndroidDevice
SnippetEvent = snippet_event.SnippetEvent
wait_for_event = event_helper.wait_callback_event


class FastPairSeeker:
    """A proxy for seeker snippet on the device."""

    def __init__(self, ad: AndroidDevice) -> None:
        self._ad = ad
        self._ad.debug_tag = 'MainlineFastPairSeeker'
        self._scan_result_callback = None

    def load_snippet(self) -> None:
        """Starts the seeker snippet and connects.

        Raises:
          SnippetError: Illegal load operations are attempted.
        """
        self._ad.load_snippet(name='fp', package=FP_SEEKER_SNIPPETS_PACKAGE)

    def start_scan(self) -> None:
        """Starts scanning to find Fast Pair provider devices."""
        self._scan_result_callback = self._ad.fp.startScan()

    def stop_scan(self) -> None:
        """Stops the Fast Pair seeker scanning."""
        self._ad.fp.stopScan()

    def start_pair(self, model_id: str, address: str) -> None:
        """Starts the Fast Pair seeker pairing.

        Args:
          model_id: A 3-byte hex string for seeker side to recognize the provider
            device (ex: 0x00000C).
          address: The BLE mac address of the Fast Pair provider.
        """
        self._ad.log.info('Before calling startPairing')
        self._ad.fp.startPairing(model_id, address)
        self._ad.log.info('After calling startPairing')

    def wait_and_assert_provider_found(self, timeout_seconds: int,
                                       expected_model_id: str,
                                       expected_ble_mac_address: str) -> None:
        """Waits and asserts any onDiscovered event from the seeker.

        Args:
          timeout_seconds: The number of seconds to wait before giving up.
          expected_model_id: The expected model ID of the remote Fast Pair provider
            device.
          expected_ble_mac_address: The expected BLE MAC address of the remote Fast
            Pair provider device.
        """

        def _on_provider_found_event_received(provider_found_event: SnippetEvent,
                                              elapsed_time: int) -> bool:
            nearby_device_str = provider_found_event.data['device']
            self._ad.log.info('Seeker discovered first provider(%s) in %d seconds.',
                              nearby_device_str, elapsed_time)
            return expected_ble_mac_address in nearby_device_str

        def _on_provider_found_event_waiting(elapsed_time: int) -> None:
            self._ad.log.info(
                'Still waiting "%s" event callback from seeker side '
                'after %d seconds...', ON_PROVIDER_FOUND_EVENT, elapsed_time)

        def _on_provider_found_event_missed() -> None:
            asserts.fail(f'Timed out after {timeout_seconds} seconds waiting for '
                         f'the specific "{ON_PROVIDER_FOUND_EVENT}" event.')

        wait_for_event(
            callback_event_handler=self._scan_result_callback,
            event_name=ON_PROVIDER_FOUND_EVENT,
            timeout_seconds=timeout_seconds,
            on_received=_on_provider_found_event_received,
            on_waiting=_on_provider_found_event_waiting,
            on_missed=_on_provider_found_event_missed)
