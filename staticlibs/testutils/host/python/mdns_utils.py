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

from mobly import asserts
from mobly.controllers import android_device


def assume_mdns_test_preconditions(
    advertising_device: android_device, discovery_device: android_device
) -> None:
  advertising = advertising_device.connectivity_multi_devices_snippet
  discovery = discovery_device.connectivity_multi_devices_snippet

  asserts.skip_if(
      not advertising.isAtLeastT(), 'Advertising device SDK is lower than T.'
  )
  asserts.skip_if(
      not discovery.isAtLeastT(), 'Discovery device SDK is lower than T.'
  )


def register_mdns_service_and_discover_resolve(
    advertising_device: android_device, discovery_device: android_device
) -> None:
  """Test mdns advertising, discovery and resolution

  One device registers an mDNS service, and another device discovers and
  resolves that service.
  """
  advertising = advertising_device.connectivity_multi_devices_snippet
  discovery = discovery_device.connectivity_multi_devices_snippet

  # Register a mDns service
  advertising.registerMDnsService()

  # Ensure the discovery and resolution of the mDNS service
  discovery.ensureMDnsServiceDiscoveryAndResolution()


def cleanup_mdns_service(
    advertising_device: android_device, discovery_device: android_device
) -> None:
  # Unregister the mDns service
  advertising_device.connectivity_multi_devices_snippet.unregisterMDnsService()
  # Stop discovery
  discovery_device.connectivity_multi_devices_snippet.stopMDnsServiceDiscovery()
