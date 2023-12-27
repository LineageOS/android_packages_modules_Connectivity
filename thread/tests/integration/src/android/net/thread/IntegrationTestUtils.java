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
package android.net.thread;

import static android.system.OsConstants.IPPROTO_ICMPV6;

import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_PIO;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.net.TestNetworkInterface;
import android.os.Handler;
import android.os.SystemClock;

import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.Icmpv6Header;
import com.android.net.module.util.structs.Ipv6Header;
import com.android.net.module.util.structs.PrefixInformationOption;
import com.android.net.module.util.structs.RaHeader;
import com.android.testutils.HandlerUtils;
import com.android.testutils.TapPacketReader;

import com.google.common.util.concurrent.SettableFuture;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Static utility methods relating to Thread integration tests. */
public final class IntegrationTestUtils {
    private IntegrationTestUtils() {}

    /**
     * Waits for the given {@link Supplier} to be true until given timeout.
     *
     * <p>It checks the condition once every second.
     *
     * @param condition the condition to check.
     * @param timeoutSeconds the number of seconds to wait for.
     * @throws TimeoutException if the condition is not met after the timeout.
     */
    public static void waitFor(Supplier<Boolean> condition, int timeoutSeconds)
            throws TimeoutException {
        waitFor(condition, timeoutSeconds, 1);
    }

    /**
     * Waits for the given {@link Supplier} to be true until given timeout.
     *
     * <p>It checks the condition once every {@code intervalSeconds}.
     *
     * @param condition the condition to check.
     * @param timeoutSeconds the number of seconds to wait for.
     * @param intervalSeconds the period to check the {@code condition}.
     * @throws TimeoutException if the condition is still not met when the timeout expires.
     */
    public static void waitFor(Supplier<Boolean> condition, int timeoutSeconds, int intervalSeconds)
            throws TimeoutException {
        for (int i = 0; i < timeoutSeconds; i += intervalSeconds) {
            if (condition.get()) {
                return;
            }
            SystemClock.sleep(intervalSeconds * 1000L);
        }
        if (condition.get()) {
            return;
        }
        throw new TimeoutException(
                String.format(
                        "The condition failed to become true in %d seconds.", timeoutSeconds));
    }

    /**
     * Creates a {@link TapPacketReader} given the {@link TestNetworkInterface} and {@link Handler}.
     *
     * @param testNetworkInterface the TUN interface of the test network.
     * @param handler the handler to process the packets.
     * @return the {@link TapPacketReader}.
     */
    public static TapPacketReader newPacketReader(
            TestNetworkInterface testNetworkInterface, Handler handler) {
        FileDescriptor fd = testNetworkInterface.getFileDescriptor().getFileDescriptor();
        final TapPacketReader reader =
                new TapPacketReader(handler, fd, testNetworkInterface.getMtu());
        handler.post(() -> reader.start());
        HandlerUtils.waitForIdle(handler, 5000 /* timeout in milliseconds */);
        return reader;
    }

    /**
     * Waits for the Thread module to enter any state of the given {@code deviceRoles}.
     *
     * @param controller the {@link ThreadNetworkController}.
     * @param deviceRoles the desired device roles. See also {@link
     *     ThreadNetworkController.DeviceRole}.
     * @param timeoutSeconds the number of seconds ot wait for.
     * @return the {@link ThreadNetworkController.DeviceRole} after waiting.
     * @throws TimeoutException if the device hasn't become any of expected roles until the timeout
     *     expires.
     */
    public static int waitForStateAnyOf(
            ThreadNetworkController controller, List<Integer> deviceRoles, int timeoutSeconds)
            throws TimeoutException {
        SettableFuture<Integer> future = SettableFuture.create();
        ThreadNetworkController.StateCallback callback =
                newRole -> {
                    if (deviceRoles.contains(newRole)) {
                        future.set(newRole);
                    }
                };
        controller.registerStateCallback(directExecutor(), callback);
        try {
            int role = future.get(timeoutSeconds, TimeUnit.SECONDS);
            controller.unregisterStateCallback(callback);
            return role;
        } catch (InterruptedException | ExecutionException e) {
            throw new TimeoutException(
                    String.format(
                            "The device didn't become an expected role in %d seconds.",
                            timeoutSeconds));
        }
    }

    /**
     * Reads a packet from a given {@link TapPacketReader} that satisfies the {@code filter}.
     *
     * @param packetReader a TUN packet reader.
     * @param filter the filter to be applied on the packet.
     * @return the first IPv6 packet that satisfies the {@code filter}. If it has waited for more
     *     than 3000ms to read the next packet, the method will return null.
     */
    public static byte[] readPacketFrom(TapPacketReader packetReader, Predicate<byte[]> filter) {
        byte[] packet;
        while ((packet = packetReader.poll(3000 /* timeoutMs */)) != null) {
            if (filter.test(packet)) return packet;
        }
        return null;
    }

    /** Returns {@code true} if {@code packet} is an ICMPv6 packet of given {@code type}. */
    public static boolean isExpectedIcmpv6Packet(byte[] packet, int type) {
        if (packet == null) {
            return false;
        }
        ByteBuffer buf = ByteBuffer.wrap(packet);
        try {
            if (Struct.parse(Ipv6Header.class, buf).nextHeader != (byte) IPPROTO_ICMPV6) {
                return false;
            }
            return Struct.parse(Icmpv6Header.class, buf).type == (short) type;
        } catch (IllegalArgumentException ignored) {
            // It's fine that the passed in packet is malformed because it's could be sent
            // by anybody.
        }
        return false;
    }

    /** Returns the Prefix Information Options (PIO) extracted from an ICMPv6 RA message. */
    public static List<PrefixInformationOption> getRaPios(byte[] raMsg) {
        final ArrayList<PrefixInformationOption> pioList = new ArrayList<>();

        if (raMsg == null) {
            return pioList;
        }

        final ByteBuffer buf = ByteBuffer.wrap(raMsg);
        final Ipv6Header ipv6Header = Struct.parse(Ipv6Header.class, buf);
        if (ipv6Header.nextHeader != (byte) IPPROTO_ICMPV6) {
            return pioList;
        }

        final Icmpv6Header icmpv6Header = Struct.parse(Icmpv6Header.class, buf);
        if (icmpv6Header.type != (short) ICMPV6_ROUTER_ADVERTISEMENT) {
            return pioList;
        }

        Struct.parse(RaHeader.class, buf);
        while (buf.position() < raMsg.length) {
            final int currentPos = buf.position();
            final int type = Byte.toUnsignedInt(buf.get());
            final int length = Byte.toUnsignedInt(buf.get());
            if (type == ICMPV6_ND_OPTION_PIO) {
                final ByteBuffer pioBuf =
                        ByteBuffer.wrap(
                                buf.array(),
                                currentPos,
                                Struct.getSize(PrefixInformationOption.class));
                final PrefixInformationOption pio =
                        Struct.parse(PrefixInformationOption.class, pioBuf);
                pioList.add(pio);

                // Move ByteBuffer position to the next option.
                buf.position(currentPos + Struct.getSize(PrefixInformationOption.class));
            } else {
                // The length is in units of 8 octets.
                buf.position(currentPos + (length * 8));
            }
        }
        return pioList;
    }
}
