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

import base64
import uuid

from mobly import asserts
from mobly.controllers import android_device


class UpstreamType:
  CELLULAR = 1
  WIFI = 2


def generate_uuid32_base64() -> str:
  """Generates a UUID32 and encodes it in Base64.

  Returns:
      str: The Base64-encoded UUID32 string. Which is 22 characters.
  """
  # Strip padding characters to make it safer for hotspot name length limit.
  return base64.b64encode(uuid.uuid1().bytes).decode("utf-8").strip("=")


def assume_hotspot_test_preconditions(
    server_device: android_device,
    client_device: android_device,
    upstream_type: UpstreamType,
) -> None:
  server = server_device.connectivity_multi_devices_snippet
  client = client_device.connectivity_multi_devices_snippet

  # Assert pre-conditions specific to each upstream type.
  asserts.skip_if(not client.hasWifiFeature(), "Client requires Wifi feature")
  asserts.skip_if(
      not server.hasHotspotFeature(), "Server requires hotspot feature"
  )
  if upstream_type == UpstreamType.CELLULAR:
    asserts.skip_if(
        not server.hasTelephonyFeature(), "Server requires Telephony feature"
    )
  elif upstream_type == UpstreamType.WIFI:
    asserts.skip_if(
        not server.isStaApConcurrencySupported(),
        "Server requires Wifi AP + STA concurrency",
    )
  else:
    raise ValueError(f"Invalid upstream type: {upstream_type}")


def setup_hotspot_and_client_for_upstream_type(
    server_device: android_device,
    client_device: android_device,
    upstream_type: UpstreamType,
) -> (str, int):
  """Setup the hotspot with a connected client with the specified upstream type.

  This creates a hotspot, make the client connect
  to it, and verify the packet is forwarded by the hotspot.
  And returns interface name of both if successful.
  """
  server = server_device.connectivity_multi_devices_snippet
  client = client_device.connectivity_multi_devices_snippet

  if upstream_type == UpstreamType.CELLULAR:
    server.requestCellularAndEnsureDefault()
  elif upstream_type == UpstreamType.WIFI:
    server.ensureWifiIsDefault()
  else:
    raise ValueError(f"Invalid upstream type: {upstream_type}")

  # Generate ssid/passphrase with random characters to make sure nearby devices won't
  # connect unexpectedly. Note that total length of ssid cannot go over 32.
  test_ssid = "HOTSPOT-" + generate_uuid32_base64()
  test_passphrase = generate_uuid32_base64()

  # Create a hotspot with fixed SSID and password.
  hotspot_interface = server.startHotspot(test_ssid, test_passphrase)

  # Make the client connects to the hotspot.
  client_network = client.connectToWifi(test_ssid, test_passphrase)

  return hotspot_interface, client_network


def cleanup_tethering_for_upstream_type(
    server_device: android_device, upstream_type: UpstreamType
) -> None:
  server = server_device.connectivity_multi_devices_snippet
  if upstream_type == UpstreamType.CELLULAR:
    server.unregisterAll()
  # Teardown the hotspot.
  server.stopAllTethering()
