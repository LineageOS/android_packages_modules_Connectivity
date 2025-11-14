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

from dataclasses import dataclass
import functools
import re
from mobly import asserts
from mobly.controllers import android_device
from mobly.controllers.android_device_lib.adb import AdbError
from net_tests_utils.host.python import adb_utils, assert_utils


class PatternNotFoundException(Exception):
  """Raised when the given pattern cannot be found."""


class UnsupportedOperationException(Exception):
  pass


def get_apf_counter(
    ad: android_device.AndroidDevice, iface: str, counter_name: str
) -> int:
  counters = get_apf_counters_from_cmd(ad, iface)
  try:
    return int(counters.get(counter_name, 0))
  except ValueError:
    return -1


def get_apf_counters_from_cmd(
    ad: android_device.AndroidDevice, iface_name: str
) -> dict:
  cmd = f'cmd network_stack apf {iface_name} dump-counters'
  output = AdbOutputHandler(ad, cmd).get_output()
  data_dict = {}
  for line in output.strip().split('\n'):
    if line:  # Ensure the line is not empty
      # split only at the first colon
      parts = line.split(':', 1)
      if len(parts) == 2:
        data_dict[parts[0]] = parts[1]
      else:
        raise assert_utils.UnexpectedBehaviorError(
            f'Got unexpected output: {output}.'
        )

  return data_dict


def get_ipv4_addresses(
    ad: android_device.AndroidDevice, iface_name: str
) -> list[str]:
  """Retrieves the IPv4 addresses of a given interface on an Android device.

  This function executes an ADB shell command (`ip -4 address show`) to get the
  network interface information and extracts the IPv4 address from the output.
  If devices have no IPv4 address, raise PatternNotFoundException.

  Args:
      ad: The Android device object.
      iface_name: The name of the network interface (e.g., "wlan0").

  Returns:
      The IPv4 addresses of the interface as a list of string.
      Return empty list if no IPv4 address.
  """
  # output format:
  # 54: wlan2: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default qlen 1000
  #     inet 192.168.195.162/24 brd 192.168.195.255 scope global wlan2
  #         valid_lft forever preferred_lft forever
  #     inet 192.168.1.1/24 brd 192.168.1.255 scope global wlan2
  #         valid_lft forever preferred_lft forever
  output = adb_utils.adb_shell(ad, f'ip -4 address show {iface_name}')
  pattern = r'inet\s+(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\/\d+'
  matches = re.findall(pattern, output)

  if matches:
    return matches
  else:
    return []


def get_non_tentative_ipv6_addresses(
    ad: android_device.AndroidDevice, iface_name: str
) -> list[str]:
  """Retrieves the non-tentative IPv6 addresses of a given interface on an Android device.

  This function executes an ADB shell command (`ip -6 address show -tentative`)
  to get the
  network interface information and extracts the IPv6 address from the output.
  If devices have no IPv6 address, raise PatternNotFoundException.

  Args:
      ad: The Android device object.
      iface_name: The name of the network interface (e.g., "wlan0").

  Returns:
      The IPv6 addresses of the interface as a list of string.
      Return empty list if no IPv6 address.
  """
  # output format
  # 54: wlan2: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP qlen 1000
  #     inet6 fe80::10a3:5dff:fe52:de32/64 scope link
  #         valid_lft forever preferred_lft forever
  #     inet6 fe80::1233:aadb:3d32:1234/64 scope link
  #         valid_lft forever preferred_lft forever
  output = adb_utils.adb_shell(
      ad, f'ip -6 address show -tentative {iface_name}'
  )
  pattern = r'inet6\s+([0-9a-fA-F:]+)\/\d+'
  matches = re.findall(pattern, output)

  if matches:
    return matches
  else:
    return []


def get_exclude_all_host_ipv6_multicast_addresses(
    ad: android_device.AndroidDevice, iface_name: str
) -> list[str]:
  """Retrieves the IPv6 multicast addresses of a given interface on an Android device.

  This function executes an ADB shell command (`ip -6 maddr show`) to get the
  network interface information and extracts the IPv6 multicast address from the
  output.
  If devices have no IPv6 multicast address, raise PatternNotFoundException.

  Args:
      ad: The Android device object.
      iface_name: The name of the network interface (e.g., "wlan0").

  Returns:
      The IPv6 multicast addresses of the interface as a list of string.
      Return empty list if no IPv6 multicast address.
  """
  # output format
  # 47:     wlan0
  #         inet6 ff02::1:ff99:37b0
  #         inet6 ff02::1:ffb7:cba2 users 2
  #         inet6 ff02::1
  #         inet6 ff01::1
  output = adb_utils.adb_shell(ad, f'ip -6 maddr show {iface_name}')
  pattern = r'inet6\s+([a-fA-F0-9:]+)(?:\s+users\s+\d+)?'
  matches = re.findall(pattern, output)

  if matches:
    return [addr for addr in matches if addr not in ('ff02::1', 'ff01::1')]
  else:
    return []


def get_hardware_address(
    ad: android_device.AndroidDevice, iface_name: str
) -> str:
  """Retrieves the hardware (MAC) address for a given network interface.

  Returns:
      The hex representative of the MAC address in uppercase.
      E.g. 12:34:56:78:90:AB

  Raises:
      PatternNotFoundException: If the MAC address is not found in the command
      output.
  """

  # Run the "ip link" command and get its output.
  ip_link_output = adb_utils.adb_shell(ad, f'ip link')

  # Regular expression to extract the MAC address.
  # Parse hardware address from ip link output like below:
  # 45: rmnet29: <POINTOPOINT,MULTICAST,NOARP> mtu 1500 qdisc ...
  #    link/ether 73:01:23:45:df:e3 brd ff:ff:ff:ff:ff:ff
  # 46: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq ...
  #    link/ether 72:05:77:82:21:e0 brd ff:ff:ff:ff:ff:ff
  # 47: wlan1: <BROADCAST,MULTICAST> mtu 1500 qdisc ...
  #    link/ether 6a:16:81:ff:82:9b brd ff:ff:ff:ff:ff:ff"
  pattern = (
      rf'{iface_name}:.*?link/ether (([0-9a-fA-F]{{2}}:){{5}}[0-9a-fA-F]{{2}})'
  )
  match = re.search(pattern, ip_link_output, re.DOTALL)

  if match:
    return match.group(1).upper()  # Extract the MAC address string.
  else:
    raise PatternNotFoundException(
        'Cannot get hardware address for ' + iface_name
    )


def is_packet_capture_supported(
    ad: android_device.AndroidDevice,
) -> bool:

  try:
    # Invoke the shell command with empty argument and see how NetworkStack respond.
    # If supported, an IllegalArgumentException with help page will be printed.
    assert_utils.expect_throws(
        lambda: start_capture_packets(ad, ''),
        assert_utils.UnexpectedBehaviorError,
    )
    assert_utils.expect_throws(
        lambda: stop_capture_packets(ad, ''),
        assert_utils.UnexpectedBehaviorError,
    )
    assert_utils.expect_throws(
        lambda: get_matched_packet_counts(ad, '', ''),
        assert_utils.UnexpectedBehaviorError,
    )
  except assert_utils.UnexpectedExceptionError:
    return False

  # If no UnsupportOperationException is thrown, regard it as supported
  return True


def is_apf_dump_counters_supported(ad: android_device.AndroidDevice) -> bool:
  try:
    # Invoke the shell command with empty argument and see how NetworkStack respond.
    # If supported, an IllegalArgumentException with help page will be printed.
    assert_utils.expect_throws(
        lambda: get_apf_counters_from_cmd(ad, ''),
        assert_utils.UnexpectedBehaviorError,
    )
  except assert_utils.UnexpectedExceptionError:
    return False

  # If no UnsupportOperationException is thrown, regard it as supported
  return True


def is_send_raw_packet_downstream_supported(
    ad: android_device.AndroidDevice,
) -> bool:
  try:
    # Invoke the shell command with empty argument and see how NetworkStack respond.
    # If supported, an IllegalArgumentException with help page will be printed.
    send_raw_packet_downstream(ad, '', '')
  except assert_utils.UnexpectedBehaviorError:
    return True
  except UnsupportedOperationException:
    return False


def send_raw_packet_downstream(
    ad: android_device.AndroidDevice,
    iface_name: str,
    packet_in_hex: str,
) -> None:
  """Sends a raw packet over the specified downstream interface.

  This function constructs and sends a raw packet using the
  `send-raw-packet-downstream`
  command provided by NetworkStack process. It's primarily intended for testing
  purposes.

  Args:
      ad: The AndroidDevice object representing the connected device.
      iface_name: The name of the network interface to use (e.g., "wlan0",
        "eth0").
      packet_in_hex: The raw packet data starting from L2 header encoded in
        hexadecimal string format.

  Raises:
      UnsupportedOperationException: If the NetworkStack doesn't support
        the `send-raw-packet` command.
      UnexpectedBehaviorException: If the command execution produces unexpected
        output other than an empty response or "Unknown command".

  Important Considerations:
      Security: This method only works on tethering downstream interfaces due
        to security restrictions.
      Packet Format: The `packet_in_hex` must be a valid hexadecimal
        representation of a packet starting from L2 header.
  """

  cmd = (
      'cmd network_stack send-raw-packet-downstream'
      f' {iface_name} {packet_in_hex}'
  )

  # Expect no output or Unknown command if NetworkStack is too old. Throw otherwise.
  adb_output = AdbOutputHandler(ad, cmd).get_output()
  if adb_output:
    raise assert_utils.UnexpectedBehaviorError(
        f'Got unexpected output: {adb_output} for command: {cmd}.'
    )


def start_capture_packets(
    ad: android_device.AndroidDevice, iface_name: str
) -> None:
  """Starts packet capturing on a specified network interface.

  This function initiates packet capture on the given network interface of an
  Android device using an ADB shell command. It handles potential errors
  related to unsupported commands or unexpected output.
  This command only supports downstream tethering interface.

  Args:
    ad: The Android device object.
    iface_name: The name of the network interface (e.g., "wlan0").
  """
  cmd = f'cmd network_stack capture start {iface_name}'

  # Expect no output or Unknown command if NetworkStack is too old. Throw otherwise.
  adb_output = AdbOutputHandler(ad, cmd).get_output()
  if adb_output != 'success':
    raise assert_utils.UnexpectedBehaviorError(
        f'Got unexpected output: {adb_output} for command: {cmd}.'
    )


def stop_capture_packets(
    ad: android_device.AndroidDevice, iface_name: str
) -> None:
  """Stops packet capturing on a specified network interface.

  This function terminates packet capture on the given network interface of an
  Android device using an ADB shell command. It handles potential errors
  related to unsupported commands or unexpected output.

  Args:
    ad: The Android device object.
    iface_name: The name of the network interface (e.g., "wlan0").
  """
  cmd = f'cmd network_stack capture stop {iface_name}'

  # Expect no output or Unknown command if NetworkStack is too old. Throw otherwise.
  adb_output = AdbOutputHandler(ad, cmd).get_output()
  if adb_output != 'success':
    raise assert_utils.UnexpectedBehaviorError(
        f'Got unexpected output: {adb_output} for command: {cmd}.'
    )


def get_matched_packet_counts(
    ad: android_device.AndroidDevice, iface_name: str, packet_in_hex: str
) -> int:
  """Gets the number of captured packets matching a specific hexadecimal pattern.

  This function retrieves the count of captured packets on the specified
  network interface that match a given hexadecimal pattern. It uses an ADB
  shell command and handles potential errors related to unsupported commands,
  unexpected output, or invalid output format.

  Args:
    ad: The Android device object.
    iface_name: The name of the network interface (e.g., "wlan0").
    packet_in_hex: The hexadecimal string representing the packet pattern.

  Returns:
    The number of matched packets as an integer.
  """
  cmd = (
      'cmd network_stack capture matched-packet-counts'
      f' {iface_name} {packet_in_hex}'
  )

  # Expect no output or Unknown command if NetworkStack is too old. Throw otherwise.
  adb_output = AdbOutputHandler(ad, cmd).get_output()
  try:
    return int(adb_output)
  except ValueError as e:
    raise assert_utils.UnexpectedBehaviorError(
        f'Got unexpected exception: {e} for command: {cmd}.'
    )


@dataclass
class ApfCapabilities:
  """APF program support capabilities.

  See android.net.apf.ApfCapabilities.

  Attributes:
      apf_version_supported (int): Version of APF instruction set supported for
        packet filtering. 0 indicates no support for packet filtering using APF
        programs.
      apf_ram_size (int): Size of APF ram.
      apf_packet_format (int): Format of packets passed to APF filter. Should be
        one of ARPHRD_*
  """

  apf_version_supported: int
  apf_ram_size: int
  apf_packet_format: int

  def __init__(
      self,
      apf_version_supported: int,
      apf_ram_size: int,
      apf_packet_format: int,
  ):
    self.apf_version_supported = apf_version_supported
    self.apf_ram_size = apf_ram_size
    self.apf_packet_format = apf_packet_format

  def __str__(self):
    """Returns a user-friendly string representation of the APF capabilities."""
    return (
        f'APF Version: {self.apf_version_supported}\n'
        f'Ram Size: {self.apf_ram_size} bytes\n'
        f'Packet Format: {self.apf_packet_format}'
    )


def get_apf_capabilities(
    ad: android_device.AndroidDevice, iface_name: str
) -> ApfCapabilities:
  output = adb_utils.adb_shell(
      ad, f'cmd network_stack apf {iface_name} capabilities'
  )
  try:
    values = [int(value_str) for value_str in output.split(',')]
  except ValueError:
    return ApfCapabilities(0, 0, 0)  # Conversion to integer failed
  return ApfCapabilities(values[0], values[1], values[2])


def assume_apf_version_support_at_least(
    ad: android_device.AndroidDevice, iface_name: str, expected_version: int
) -> None:
  caps = get_apf_capabilities(ad, iface_name)
  asserts.abort_class_if(
      caps.apf_version_supported < expected_version,
      f'Supported apf version {caps.apf_version_supported} < expected version'
      f' {expected_version}',
  )


def at_least_B():
  def decorator(test_function):
    @functools.wraps(test_function)
    def wrapper(self, *args, **kwargs):
      asserts.abort_class_if(
          (not hasattr(self, 'client'))
          or (not hasattr(self.client, 'isAtLeastB')),
          'no valid client attribute',
      )

      asserts.abort_class_if(
          not self.client.isAtLeastB(), 'client device is not Android B+'
      )
      return test_function(self, *args, **kwargs)

    return wrapper

  return decorator


def apf_ram_at_least(size):
  def decorator(test_function):
    @functools.wraps(test_function)
    def wrapper(self, *args, **kwargs):
      asserts.abort_class_if(
          (not hasattr(self, 'clientDevice'))
          or (not hasattr(self, 'client_iface_name')),
          'no valid client attribute',
      )

      caps = get_apf_capabilities(self.clientDevice, self.client_iface_name)
      asserts.skip_if(
          caps.apf_ram_size < size,
          f'APF rame size {caps.apf_ram_size} < {size}',
      )
      return test_function(self, *args, **kwargs)

    return wrapper

  return decorator


class AdbOutputHandler:

  def __init__(self, ad, cmd):
    self._ad = ad
    self._cmd = cmd

  def get_output(self) -> str:
    try:
      return adb_utils.adb_shell(self._ad, self._cmd)
    except AdbError as e:
      output = str(e.stdout)
      if 'Unknown command' in output:
        raise UnsupportedOperationException(f'{self._cmd} is not supported.')
      return output
