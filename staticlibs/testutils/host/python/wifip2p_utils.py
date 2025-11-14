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
from net_tests_utils.host.python import tether_utils


def assume_wifi_p2p_test_preconditions(
    server_device: android_device, client_device: android_device
) -> None:
  """Preconditions check for running Wi-Fi P2P test."""
  server = server_device.connectivity_multi_devices_snippet
  client = client_device.connectivity_multi_devices_snippet

  # Assert pre-conditions
  asserts.skip_if(not server.hasWifiFeature(), 'Server requires Wifi feature')
  asserts.skip_if(not client.hasWifiFeature(), 'Client requires Wifi feature')
  asserts.skip_if(
      not server.isP2pSupported(), 'Server requires Wi-fi P2P feature'
  )
  asserts.skip_if(
      not client.isP2pSupported(), 'Client requires Wi-fi P2P feature'
  )


def setup_wifi_p2p_server_and_client(
    server_device: android_device, client_device: android_device
) -> None:
  """Set up the Wi-Fi P2P server and client, then connect them to establish a Wi-Fi P2P connection."""
  server = server_device.connectivity_multi_devices_snippet
  client = client_device.connectivity_multi_devices_snippet

  # Start Wi-Fi P2P on both server and client.
  server.startWifiP2p()
  client.startWifiP2p()

  # Get the current device name
  server_name = server.getDeviceName()
  client_name = client.getDeviceName()

  # Generate Wi-Fi P2P group passphrase with random characters.
  # network name must starts with prefix "DIRECT-", followed by any two
  # random chars from the set ('A' - 'Z', 'a' - 'z', '0' - '9')
  # This follows the documentation on WifiP2pConfig.Builder#setNetworkName.
  group_name = 'DIRECT-XY' + tether_utils.generate_uuid32_base64()
  group_passphrase = tether_utils.generate_uuid32_base64()

  # Server creates a Wi-Fi P2P group
  server.createGroup(group_name, group_passphrase)

  # Client connects to the group
  client.connectToGroup(group_name, group_passphrase)

  # Wait for a p2p connection changed intent to ensure joining the group
  client.waitForP2pConnectionChanged(group_name)

  # Ensure Wi-Fi P2P connected on both devices
  client.ensureDeviceConnected(server_name)
  server.ensureDeviceConnected(client_name)


def cleanup_wifi_p2p(
    server_device: android_device, client_device: android_device
) -> None:
  # Stop Wi-Fi P2P
  server_device.connectivity_multi_devices_snippet.stopWifiP2p()
  client_device.connectivity_multi_devices_snippet.stopWifiP2p()
