/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.net.thread.utils;

import static android.net.thread.utils.IntegrationTestUtils.SERVICE_DISCOVERY_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.waitFor;

import static com.google.common.io.BaseEncoding.base16;

import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.nsd.NsdServiceInfo;
import android.net.thread.ActiveOperationalDataset;

import com.google.errorprone.annotations.FormatMethod;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A class that launches and controls a simulation Full Thread Device (FTD).
 *
 * <p>This class launches an `ot-cli-ftd` process and communicates with it via command line input
 * and output. See <a
 * href="https://github.com/openthread/openthread/blob/main/src/cli/README.md">this page</a> for
 * available commands.
 */
public final class FullThreadDevice {
    private static final int HOP_LIMIT = 64;
    private static final int PING_INTERVAL = 1;
    private static final int PING_SIZE = 100;
    // There may not be a response for the ping command, using a short timeout to keep the tests
    // short.
    private static final float PING_TIMEOUT_0_1_SECOND = 0.1f;
    // 1 second timeout should be used when response is expected.
    private static final float PING_TIMEOUT_1_SECOND = 1f;

    private final Process mProcess;
    private final BufferedReader mReader;
    private final BufferedWriter mWriter;

    private ActiveOperationalDataset mActiveOperationalDataset;

    /**
     * Constructs a {@link FullThreadDevice} for the given node ID.
     *
     * <p>It launches an `ot-cli-ftd` process using the given node ID. The node ID is an integer in
     * range [1, OPENTHREAD_SIMULATION_MAX_NETWORK_SIZE]. `OPENTHREAD_SIMULATION_MAX_NETWORK_SIZE`
     * is defined in `external/openthread/examples/platforms/simulation/platform-config.h`.
     *
     * @param nodeId the node ID for the simulation Full Thread Device.
     * @throws IllegalStateException the node ID is already occupied by another simulation Thread
     *     device.
     */
    public FullThreadDevice(int nodeId) {
        try {
            mProcess = Runtime.getRuntime().exec("/system/bin/ot-cli-ftd " + nodeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start ot-cli-ftd (id=" + nodeId + ")", e);
        }
        mReader = new BufferedReader(new InputStreamReader(mProcess.getInputStream()));
        mWriter = new BufferedWriter(new OutputStreamWriter(mProcess.getOutputStream()));
        mActiveOperationalDataset = null;
    }

    public void destroy() {
        mProcess.destroy();
    }

    /**
     * Returns an OMR (Off-Mesh-Routable) address on this device if any.
     *
     * <p>This methods goes through all unicast addresses on the device and returns the first
     * address which is neither link-local nor mesh-local.
     */
    public Inet6Address getOmrAddress() {
        List<String> addresses = executeCommand("ipaddr");
        IpPrefix meshLocalPrefix = mActiveOperationalDataset.getMeshLocalPrefix();
        for (String address : addresses) {
            if (address.startsWith("fe80:")) {
                continue;
            }
            Inet6Address addr = (Inet6Address) InetAddresses.parseNumericAddress(address);
            if (!meshLocalPrefix.contains(addr)) {
                return addr;
            }
        }
        return null;
    }

    /** Returns the Mesh-local EID address on this device if any. */
    public Inet6Address getMlEid() {
        List<String> addresses = executeCommand("ipaddr mleid");
        return (Inet6Address) InetAddresses.parseNumericAddress(addresses.get(0));
    }

    /**
     * Returns the link-local address of the device.
     *
     * <p>This methods goes through all unicast addresses on the device and returns the address that
     * begins with fe80.
     */
    public Inet6Address getLinkLocalAddress() {
        List<String> output = executeCommand("ipaddr linklocal");
        if (!output.isEmpty() && output.get(0).startsWith("fe80:")) {
            return (Inet6Address) InetAddresses.parseNumericAddress(output.get(0));
        }
        return null;
    }

    /**
     * Returns the mesh-local addresses of the device.
     *
     * <p>This methods goes through all unicast addresses on the device and returns the address that
     * begins with mesh-local prefix.
     */
    public List<Inet6Address> getMeshLocalAddresses() {
        List<String> addresses = executeCommand("ipaddr");
        List<Inet6Address> meshLocalAddresses = new ArrayList<>();
        IpPrefix meshLocalPrefix = mActiveOperationalDataset.getMeshLocalPrefix();
        for (String address : addresses) {
            Inet6Address addr = (Inet6Address) InetAddresses.parseNumericAddress(address);
            if (meshLocalPrefix.contains(addr)) {
                meshLocalAddresses.add(addr);
            }
        }
        return meshLocalAddresses;
    }

    /**
     * Joins the Thread network using the given {@link ActiveOperationalDataset}.
     *
     * @param dataset the Active Operational Dataset
     */
    public void joinNetwork(ActiveOperationalDataset dataset) {
        mActiveOperationalDataset = dataset;
        executeCommand("dataset set active " + base16().lowerCase().encode(dataset.toThreadTlvs()));
        executeCommand("ifconfig up");
        executeCommand("thread start");
    }

    /** Stops the Thread network radio. */
    public void stopThreadRadio() {
        executeCommand("thread stop");
        executeCommand("ifconfig down");
    }

    /**
     * Waits for the Thread device to enter the any state of the given {@link List<String>}.
     *
     * @param states the list of states to wait for. Valid states are "disabled", "detached",
     *     "child", "router" and "leader".
     * @param timeout the time to wait for the expected state before throwing
     */
    public void waitForStateAnyOf(List<String> states, Duration timeout) throws TimeoutException {
        waitFor(() -> states.contains(getState()), timeout);
    }

    /**
     * Gets the state of the Thread device.
     *
     * @return a string representing the state.
     */
    public String getState() {
        return executeCommand("state").get(0);
    }

    /** Closes the UDP socket. */
    public void udpClose() {
        executeCommand("udp close");
    }

    /** Opens the UDP socket. */
    public void udpOpen() {
        executeCommand("udp open");
    }

    /** Opens the UDP socket and binds it to a specific address and port. */
    public void udpBind(Inet6Address address, int port) {
        udpClose();
        udpOpen();
        executeCommand("udp bind %s %d", address.getHostAddress(), port);
    }

    /** Returns the message received on the UDP socket. */
    public String udpReceive() throws IOException {
        Pattern pattern =
                Pattern.compile("> (\\d+) bytes from ([\\da-f:]+) (\\d+) ([\\x00-\\x7F]+)");
        Matcher matcher = pattern.matcher(mReader.readLine());
        matcher.matches();

        return matcher.group(4);
    }

    /** Sends a UDP message to given IPv6 address and port. */
    public void udpSend(String message, Inet6Address serverAddr, int serverPort) {
        executeCommand("udp send %s %d %s", serverAddr.getHostAddress(), serverPort, message);
    }

    /** Enables the SRP client and run in autostart mode. */
    public void autoStartSrpClient() {
        executeCommand("srp client autostart enable");
    }

    /** Sets the hostname (e.g. "MyHost") for the SRP client. */
    public void setSrpHostname(String hostname) {
        executeCommand("srp client host name " + hostname);
    }

    /** Sets the host addresses for the SRP client. */
    public void setSrpHostAddresses(List<Inet6Address> addresses) {
        executeCommand(
                "srp client host address "
                        + String.join(
                                " ",
                                addresses.stream().map(Inet6Address::getHostAddress).toList()));
    }

    /** Removes the SRP host */
    public void removeSrpHost() {
        executeCommand("srp client host remove 1 1");
    }

    /**
     * Adds an SRP service for the SRP client and wait for the registration to complete.
     *
     * @param serviceName the service name like "MyService"
     * @param serviceType the service type like "_test._tcp"
     * @param subtypes the service subtypes like "_sub1"
     * @param port the port number in range [1, 65535]
     * @param txtMap the map of TXT names and values
     * @throws TimeoutException if the service isn't registered within timeout
     */
    public void addSrpService(
            String serviceName,
            String serviceType,
            List<String> subtypes,
            int port,
            Map<String, byte[]> txtMap)
            throws TimeoutException {
        StringBuilder fullServiceType = new StringBuilder(serviceType);
        for (String subtype : subtypes) {
            fullServiceType.append(",").append(subtype);
        }
        executeCommand(
                "srp client service add %s %s %d %d %d %s",
                serviceName,
                fullServiceType,
                port,
                0 /* priority */,
                0 /* weight */,
                txtMapToHexString(txtMap));
        waitFor(() -> isSrpServiceRegistered(serviceName, serviceType), SERVICE_DISCOVERY_TIMEOUT);
    }

    /**
     * Removes an SRP service for the SRP client.
     *
     * @param serviceName the service name like "MyService"
     * @param serviceType the service type like "_test._tcp"
     * @param notifyServer whether to notify SRP server about the removal
     */
    public void removeSrpService(String serviceName, String serviceType, boolean notifyServer) {
        String verb = notifyServer ? "remove" : "clear";
        executeCommand("srp client service %s %s %s", verb, serviceName, serviceType);
    }

    /**
     * Updates an existing SRP service for the SRP client.
     *
     * <p>This is essentially a 'remove' and an 'add' on the SRP client's side.
     *
     * @param serviceName the service name like "MyService"
     * @param serviceType the service type like "_test._tcp"
     * @param subtypes the service subtypes like "_sub1"
     * @param port the port number in range [1, 65535]
     * @param txtMap the map of TXT names and values
     * @throws TimeoutException if the service isn't updated within timeout
     */
    public void updateSrpService(
            String serviceName,
            String serviceType,
            List<String> subtypes,
            int port,
            Map<String, byte[]> txtMap)
            throws TimeoutException {
        removeSrpService(serviceName, serviceType, false /* notifyServer */);
        addSrpService(serviceName, serviceType, subtypes, port, txtMap);
    }

    /** Checks if an SRP service is registered. */
    public boolean isSrpServiceRegistered(String serviceName, String serviceType) {
        List<String> lines = executeCommand("srp client service");
        for (String line : lines) {
            if (line.contains(serviceName) && line.contains(serviceType)) {
                return line.contains("Registered");
            }
        }
        return false;
    }

    /** Checks if an SRP host is registered. */
    public boolean isSrpHostRegistered() {
        List<String> lines = executeCommand("srp client host");
        for (String line : lines) {
            return line.contains("Registered");
        }
        return false;
    }

    /** Sets the DNS server address. */
    public void setDnsServerAddress(String address) {
        executeCommand("dns config " + address);
    }

    /** Returns the first browsed service instance of {@code serviceType}. */
    public NsdServiceInfo browseService(String serviceType) {
        // CLI output:
        // DNS browse response for _testservice._tcp.default.service.arpa.
        // test-service
        //    Port:12345, Priority:0, Weight:0, TTL:10
        //    Host:testhost.default.service.arpa.
        //    HostAddress:2001:0:0:0:0:0:0:1 TTL:10
        //    TXT:[key1=0102, key2=03] TTL:10

        List<String> lines = executeCommand("dns browse " + serviceType);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(lines.get(1));
        info.setServiceType(serviceType);
        info.setPort(DnsServiceCliOutputParser.parsePort(lines.get(2)));
        info.setHostname(DnsServiceCliOutputParser.parseHostname(lines.get(3)));
        info.setHostAddresses(List.of(DnsServiceCliOutputParser.parseHostAddress(lines.get(4))));
        DnsServiceCliOutputParser.parseTxtIntoServiceInfo(lines.get(5), info);

        return info;
    }

    /** Returns the resolved service instance. */
    public NsdServiceInfo resolveService(String serviceName, String serviceType) {
        // CLI output:
        // DNS service resolution response for test-service for service
        // _test._tcp.default.service.arpa.
        // Port:12345, Priority:0, Weight:0, TTL:10
        // Host:Android.default.service.arpa.
        // HostAddress:2001:0:0:0:0:0:0:1 TTL:10
        // TXT:[key1=0102, key2=03] TTL:10

        List<String> lines = executeCommand("dns service %s %s", serviceName, serviceType);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(serviceType);
        info.setPort(DnsServiceCliOutputParser.parsePort(lines.get(1)));
        info.setHostname(DnsServiceCliOutputParser.parseHostname(lines.get(2)));
        info.setHostAddresses(List.of(DnsServiceCliOutputParser.parseHostAddress(lines.get(3))));
        DnsServiceCliOutputParser.parseTxtIntoServiceInfo(lines.get(4), info);

        return info;
    }

    /** Runs the "factoryreset" command on the device. */
    public void factoryReset() {
        try {
            mWriter.write("factoryreset\n");
            mWriter.flush();
            // fill the input buffer to avoid truncating next command
            for (int i = 0; i < 1000; ++i) {
                mWriter.write("\n");
            }
            mWriter.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run factoryreset on ot-cli-ftd", e);
        }
    }

    public void subscribeMulticastAddress(Inet6Address address) {
        executeCommand("ipmaddr add " + address.getHostAddress());
    }

    public void ping(Inet6Address address, Inet6Address source) {
        ping(
                address,
                source,
                PING_SIZE,
                1 /* count */,
                PING_INTERVAL,
                HOP_LIMIT,
                PING_TIMEOUT_0_1_SECOND);
    }

    public void ping(Inet6Address address) {
        ping(
                address,
                null,
                PING_SIZE,
                1 /* count */,
                PING_INTERVAL,
                HOP_LIMIT,
                PING_TIMEOUT_0_1_SECOND);
    }

    /** Returns the number of ping reply packets received. */
    public int ping(Inet6Address address, int count) {
        List<String> output =
                ping(
                        address,
                        null,
                        PING_SIZE,
                        count,
                        PING_INTERVAL,
                        HOP_LIMIT,
                        PING_TIMEOUT_1_SECOND);
        return getReceivedPacketsCount(output);
    }

    private List<String> ping(
            Inet6Address address,
            Inet6Address source,
            int size,
            int count,
            int interval,
            int hopLimit,
            float timeout) {
        String cmd =
                "ping"
                        + ((source == null) ? "" : (" -I " + source.getHostAddress()))
                        + " "
                        + address.getHostAddress()
                        + " "
                        + size
                        + " "
                        + count
                        + " "
                        + interval
                        + " "
                        + hopLimit
                        + " "
                        + timeout;
        return executeCommand(cmd);
    }

    private int getReceivedPacketsCount(List<String> stringList) {
        Pattern pattern = Pattern.compile("([\\d]+) packets received");

        for (String message : stringList) {
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                String packetCountStr = matcher.group(1);
                return Integer.parseInt(packetCountStr);
            }
        }
        // No match found
        return -1;
    }

    @FormatMethod
    private List<String> executeCommand(String commandFormat, Object... args) {
        return executeCommand(String.format(commandFormat, args));
    }

    private List<String> executeCommand(String command) {
        try {
            mWriter.write(command + "\n");
            mWriter.flush();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to write the command " + command + " to ot-cli-ftd", e);
        }
        try {
            return readUntilDone();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read the ot-cli-ftd output of command: " + command, e);
        }
    }

    private List<String> readUntilDone() throws IOException {
        ArrayList<String> result = new ArrayList<>();
        String line;
        while ((line = mReader.readLine()) != null) {
            if (line.equals("Done")) {
                break;
            }
            if (line.startsWith("Error")) {
                throw new IOException("ot-cli-ftd reported an error: " + line);
            }
            if (!line.startsWith("> ")) {
                result.add(line);
            }
        }
        return result;
    }

    private static String txtMapToHexString(Map<String, byte[]> txtMap) {
        if (txtMap == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : txtMap.entrySet()) {
            int length = entry.getKey().length() + entry.getValue().length + 1;
            sb.append(String.format("%02x", length));
            sb.append(toHexString(entry.getKey()));
            sb.append(toHexString("="));
            sb.append(toHexString(entry.getValue()));
        }
        return sb.toString();
    }

    private static String toHexString(String s) {
        return toHexString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHexString(byte[] bytes) {
        return base16().encode(bytes);
    }

    private static final class DnsServiceCliOutputParser {
        /** Returns the first match in the input of a given regex pattern. */
        private static Matcher firstMatchOf(String input, String regex) {
            Matcher matcher = Pattern.compile(regex).matcher(input);
            matcher.find();
            return matcher;
        }

        // Example: "Port:12345"
        private static int parsePort(String line) {
            return Integer.parseInt(firstMatchOf(line, "Port:(\\d+)").group(1));
        }

        // Example: "Host:Android.default.service.arpa."
        private static String parseHostname(String line) {
            return firstMatchOf(line, "Host:(.+)").group(1);
        }

        // Example: "HostAddress:2001:0:0:0:0:0:0:1"
        private static InetAddress parseHostAddress(String line) {
            return InetAddresses.parseNumericAddress(
                    firstMatchOf(line, "HostAddress:([^ ]+)").group(1));
        }

        // Example: "TXT:[key1=0102, key2=03]"
        private static void parseTxtIntoServiceInfo(String line, NsdServiceInfo serviceInfo) {
            String txtString = firstMatchOf(line, "TXT:\\[([^\\]]+)\\]").group(1);
            for (String txtEntry : txtString.split(",")) {
                String[] nameAndValue = txtEntry.trim().split("=");
                String name = nameAndValue[0];
                String value = nameAndValue[1];
                byte[] bytes = new byte[value.length() / 2];
                for (int i = 0; i < value.length(); i += 2) {
                    byte b = (byte) ((value.charAt(i) - '0') << 4 | (value.charAt(i + 1) - '0'));
                    bytes[i / 2] = b;
                }
                serviceInfo.setAttribute(name, bytes);
            }
        }
    }
}
