/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.connectivity.mdns;

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.InetAddresses;
import android.text.TextUtils;

import com.android.server.connectivity.mdns.MdnsServiceInfo.TextEntry;
import com.android.server.connectivity.mdns.MdnsServiceTypeClient.QueryTaskConfig;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Tests for {@link MdnsServiceTypeClient}. */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsServiceTypeClientTests {
    private static final int INTERFACE_INDEX = 999;
    private static final String SERVICE_TYPE = "_googlecast._tcp.local";
    private static final String[] SERVICE_TYPE_LABELS = TextUtils.split(SERVICE_TYPE, "\\.");

    @Mock
    private MdnsServiceBrowserListener mockListenerOne;
    @Mock
    private MdnsServiceBrowserListener mockListenerTwo;
    @Mock
    private MdnsPacketWriter mockPacketWriter;
    @Mock
    private MdnsSocketClient mockSocketClient;
    @Captor
    private ArgumentCaptor<MdnsServiceInfo> serviceInfoCaptor;

    private final byte[] buf = new byte[10];

    private DatagramPacket[] expectedPackets;
    private ScheduledFuture<?>[] expectedSendFutures;
    private FakeExecutor currentThreadExecutor = new FakeExecutor();

    private MdnsServiceTypeClient client;

    @Before
    @SuppressWarnings("DoNotMock")
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);

        expectedPackets = new DatagramPacket[16];
        expectedSendFutures = new ScheduledFuture<?>[16];

        for (int i = 0; i < expectedSendFutures.length; ++i) {
            expectedPackets[i] = new DatagramPacket(buf, 0, 5);
            expectedSendFutures[i] = Mockito.mock(ScheduledFuture.class);
        }
        when(mockPacketWriter.getPacket(any(SocketAddress.class)))
                .thenReturn(expectedPackets[0])
                .thenReturn(expectedPackets[1])
                .thenReturn(expectedPackets[2])
                .thenReturn(expectedPackets[3])
                .thenReturn(expectedPackets[4])
                .thenReturn(expectedPackets[5])
                .thenReturn(expectedPackets[6])
                .thenReturn(expectedPackets[7])
                .thenReturn(expectedPackets[8])
                .thenReturn(expectedPackets[9])
                .thenReturn(expectedPackets[10])
                .thenReturn(expectedPackets[11])
                .thenReturn(expectedPackets[12])
                .thenReturn(expectedPackets[13])
                .thenReturn(expectedPackets[14])
                .thenReturn(expectedPackets[15]);

        client =
                new MdnsServiceTypeClient(SERVICE_TYPE, mockSocketClient, currentThreadExecutor) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mockPacketWriter;
                    }
                };
    }

    @Test
    public void sendQueries_activeScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);

        // First burst, 3 queries.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                1, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Second burst will be sent after initialTimeBetweenBurstsMs, 3 queries.
        verifyAndSendQuery(
                3, MdnsConfigs.initialTimeBetweenBurstsMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                4, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                5, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Third burst will be sent after initialTimeBetweenBurstsMs * 2, 3 queries.
        verifyAndSendQuery(
                6, MdnsConfigs.initialTimeBetweenBurstsMs() * 2, /* expectsUnicastResponse= */
                false);
        verifyAndSendQuery(
                7, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                8, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Forth burst will be sent after initialTimeBetweenBurstsMs * 4, 3 queries.
        verifyAndSendQuery(
                9, MdnsConfigs.initialTimeBetweenBurstsMs() * 4, /* expectsUnicastResponse= */
                false);
        verifyAndSendQuery(
                10, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                11, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Fifth burst will be sent after timeBetweenBurstsMs, 3 queries.
        verifyAndSendQuery(12, MdnsConfigs.timeBetweenBurstsMs(), /* expectsUnicastResponse= */
                false);
        verifyAndSendQuery(
                13, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                14, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);

        // Stop sending packets.
        client.stopSendAndReceive(mockListenerOne);
        verify(expectedSendFutures[15]).cancel(true);
    }

    @Test
    public void sendQueries_reentry_activeScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);

        // First burst, first query is sent.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);

        // After the first query is sent, change the subtypes, and restart.
        searchOptions =
                MdnsSearchOptions.newBuilder()
                        .addSubtype("12345")
                        .addSubtype("abcde")
                        .setIsPassiveMode(false)
                        .build();
        client.startSendAndReceive(mockListenerOne, searchOptions);
        // The previous scheduled task should be canceled.
        verify(expectedSendFutures[1]).cancel(true);

        // Queries should continue to be sent.
        verifyAndSendQuery(1, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                3, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);

        // Stop sending packets.
        client.stopSendAndReceive(mockListenerOne);
        verify(expectedSendFutures[5]).cancel(true);
    }

    @Test
    public void sendQueries_passiveScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);

        // First burst, 3 query.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                1, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Second burst will be sent after timeBetweenBurstsMs, 1 query.
        verifyAndSendQuery(3, MdnsConfigs.timeBetweenBurstsMs(), /* expectsUnicastResponse= */
                false);
        // Third burst will be sent after timeBetweenBurstsMs, 1 query.
        verifyAndSendQuery(4, MdnsConfigs.timeBetweenBurstsMs(), /* expectsUnicastResponse= */
                false);

        // Stop sending packets.
        client.stopSendAndReceive(mockListenerOne);
        verify(expectedSendFutures[5]).cancel(true);
    }

    @Test
    public void sendQueries_reentry_passiveScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);

        // First burst, first query is sent.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);

        // After the first query is sent, change the subtypes, and restart.
        searchOptions =
                MdnsSearchOptions.newBuilder()
                        .addSubtype("12345")
                        .addSubtype("abcde")
                        .setIsPassiveMode(true)
                        .build();
        client.startSendAndReceive(mockListenerOne, searchOptions);
        // The previous scheduled task should be canceled.
        verify(expectedSendFutures[1]).cancel(true);

        // Queries should continue to be sent.
        verifyAndSendQuery(1, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                3, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);

        // Stop sending packets.
        client.stopSendAndReceive(mockListenerOne);
        verify(expectedSendFutures[5]).cancel(true);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testQueryTaskConfig_alwaysAskForUnicastResponse() {
        //MdnsConfigsFlagsImpl.alwaysAskForUnicastResponseInEachBurst.override(true);
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        QueryTaskConfig config =
                new QueryTaskConfig(searchOptions.getSubtypes(), searchOptions.isPassiveMode(), 1);

        // This is the first query. We will ask for unicast response.
        assertTrue(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, 1);

        // For the rest of queries in this burst, we will NOT ask for unicast response.
        for (int i = 1; i < MdnsConfigs.queriesPerBurst(); i++) {
            int oldTransactionId = config.transactionId;
            config = config.getConfigForNextRun();
            assertFalse(config.expectUnicastResponse);
            assertEquals(config.subtypes, searchOptions.getSubtypes());
            assertEquals(config.transactionId, oldTransactionId + 1);
        }

        // This is the first query of a new burst. We will ask for unicast response.
        int oldTransactionId = config.transactionId;
        config = config.getConfigForNextRun();
        assertTrue(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, oldTransactionId + 1);
    }

    @Test
    public void testQueryTaskConfig_askForUnicastInFirstQuery() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        QueryTaskConfig config =
                new QueryTaskConfig(searchOptions.getSubtypes(), searchOptions.isPassiveMode(), 1);

        // This is the first query. We will ask for unicast response.
        assertTrue(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, 1);

        // For the rest of queries in this burst, we will NOT ask for unicast response.
        for (int i = 1; i < MdnsConfigs.queriesPerBurst(); i++) {
            int oldTransactionId = config.transactionId;
            config = config.getConfigForNextRun();
            assertFalse(config.expectUnicastResponse);
            assertEquals(config.subtypes, searchOptions.getSubtypes());
            assertEquals(config.transactionId, oldTransactionId + 1);
        }

        // This is the first query of a new burst. We will NOT ask for unicast response.
        int oldTransactionId = config.transactionId;
        config = config.getConfigForNextRun();
        assertFalse(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, oldTransactionId + 1);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testIfPreviousTaskIsCanceledWhenNewSessionStarts() {
        //MdnsConfigsFlagsImpl.useSessionIdToScheduleMdnsTask.override(true);
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);
        Runnable firstMdnsTask = currentThreadExecutor.getAndClearSubmittedRunnable();

        // Change the sutypes and start a new session.
        searchOptions =
                MdnsSearchOptions.newBuilder()
                        .addSubtype("12345")
                        .addSubtype("abcde")
                        .setIsPassiveMode(true)
                        .build();
        client.startSendAndReceive(mockListenerOne, searchOptions);

        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the first mdns task is not successful canceled and it gets
        // executed anyway.
        firstMdnsTask.run();

        // Although it gets executes, no more task gets scheduled.
        assertNull(currentThreadExecutor.getAndClearLastScheduledRunnable());
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testIfPreviousTaskIsCanceledWhenSessionStops() {
        //MdnsConfigsFlagsImpl.shouldCancelScanTaskWhenFutureIsNull.override(true);
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);
        // Change the sutypes and start a new session.
        client.stopSendAndReceive(mockListenerOne);
        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the first mdns task is not successful canceled and it gets
        // executed anyway.
        currentThreadExecutor.getAndClearSubmittedRunnable().run();

        // Although it gets executes, no more task gets scheduled.
        assertNull(currentThreadExecutor.getAndClearLastScheduledRunnable());
    }

    private static void verifyServiceInfo(MdnsServiceInfo serviceInfo, String serviceName,
            String[] serviceType, String ipv4Address, String ipv6Address, int port,
            List<String> subTypes, Map<String, String> attributes, int interfaceIndex) {
        assertEquals(serviceName, serviceInfo.getServiceInstanceName());
        assertArrayEquals(serviceType, serviceInfo.getServiceType());
        assertEquals(ipv4Address, serviceInfo.getIpv4Address());
        assertEquals(ipv6Address, serviceInfo.getIpv6Address());
        assertEquals(port, serviceInfo.getPort());
        assertEquals(subTypes, serviceInfo.getSubtypes());
        for (String key : attributes.keySet()) {
            assertEquals(attributes.get(key), serviceInfo.getAttributeByKey(key));
        }
        assertEquals(interfaceIndex, serviceInfo.getInterfaceIndex());
    }

    @Test
    public void processResponse_incompleteResponse() {
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());

        MdnsResponse response = mock(MdnsResponse.class);
        when(response.getServiceInstanceName()).thenReturn("service-instance-1");
        doReturn(INTERFACE_INDEX).when(response).getInterfaceIndex();
        when(response.isComplete()).thenReturn(false);

        client.processResponse(response);
        verify(mockListenerOne).onServiceNameDiscovered(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(0),
                "service-instance-1",
                SERVICE_TYPE_LABELS,
                null /* ipv4Address */,
                null /* ipv6Address */,
                0 /* port */,
                List.of() /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                INTERFACE_INDEX);

        verify(mockListenerOne, never()).onServiceFound(any(MdnsServiceInfo.class));
        verify(mockListenerOne, never()).onServiceUpdated(any(MdnsServiceInfo.class));
    }

    @Test
    public void processIPv4Response_completeResponseForNewServiceInstance() throws Exception {
        final String ipV4Address = "192.168.1.1";
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());

        // Process the initial response.
        MdnsResponse initialResponse =
                createResponse(
                        "service-instance-1",
                        ipV4Address,
                        5353,
                        /* subtype= */ "ABCDE",
                        Collections.emptyMap(),
                        /* interfaceIndex= */ 20);
        client.processResponse(initialResponse);

        // Process a second response with a different port and updated text attributes.
        MdnsResponse secondResponse =
                createResponse(
                        "service-instance-1",
                        ipV4Address,
                        5354,
                        /* subtype= */ "ABCDE",
                        Collections.singletonMap("key", "value"),
                        /* interfaceIndex= */ 20);
        client.processResponse(secondResponse);

        // Verify onServiceNameDiscovered was called once for the initial response.
        verify(mockListenerOne).onServiceNameDiscovered(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(0),
                "service-instance-1",
                SERVICE_TYPE_LABELS,
                ipV4Address /* ipv4Address */,
                null /* ipv6Address */,
                5353 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                20 /* interfaceIndex */);

        // Verify onServiceFound was called once for the initial response.
        verify(mockListenerOne).onServiceFound(serviceInfoCaptor.capture());
        MdnsServiceInfo initialServiceInfo = serviceInfoCaptor.getAllValues().get(1);
        assertEquals(initialServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(initialServiceInfo.getIpv4Address(), ipV4Address);
        assertEquals(initialServiceInfo.getPort(), 5353);
        assertEquals(initialServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertNull(initialServiceInfo.getAttributeByKey("key"));
        assertEquals(initialServiceInfo.getInterfaceIndex(), 20);

        // Verify onServiceUpdated was called once for the second response.
        verify(mockListenerOne).onServiceUpdated(serviceInfoCaptor.capture());
        MdnsServiceInfo updatedServiceInfo = serviceInfoCaptor.getAllValues().get(2);
        assertEquals(updatedServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(updatedServiceInfo.getIpv4Address(), ipV4Address);
        assertEquals(updatedServiceInfo.getPort(), 5354);
        assertTrue(updatedServiceInfo.hasSubtypes());
        assertEquals(updatedServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertEquals(updatedServiceInfo.getAttributeByKey("key"), "value");
        assertEquals(updatedServiceInfo.getInterfaceIndex(), 20);
    }

    @Test
    public void processIPv6Response_getCorrectServiceInfo() throws Exception {
        final String ipV6Address = "2000:3333::da6c:63ff:fe7c:7483";
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());

        // Process the initial response.
        MdnsResponse initialResponse =
                createResponse(
                        "service-instance-1",
                        ipV6Address,
                        5353,
                        /* subtype= */ "ABCDE",
                        Collections.emptyMap(),
                        /* interfaceIndex= */ 20);
        client.processResponse(initialResponse);

        // Process a second response with a different port and updated text attributes.
        MdnsResponse secondResponse =
                createResponse(
                        "service-instance-1",
                        ipV6Address,
                        5354,
                        /* subtype= */ "ABCDE",
                        Collections.singletonMap("key", "value"),
                        /* interfaceIndex= */ 20);
        client.processResponse(secondResponse);

        System.out.println("secondResponses ip"
                + secondResponse.getInet6AddressRecord().getInet6Address().getHostAddress());

        // Verify onServiceNameDiscovered was called once for the initial response.
        verify(mockListenerOne).onServiceNameDiscovered(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(0),
                "service-instance-1",
                SERVICE_TYPE_LABELS,
                null /* ipv4Address */,
                ipV6Address /* ipv6Address */,
                5353 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                20 /* interfaceIndex */);

        // Verify onServiceFound was called once for the initial response.
        verify(mockListenerOne).onServiceFound(serviceInfoCaptor.capture());
        MdnsServiceInfo initialServiceInfo = serviceInfoCaptor.getAllValues().get(1);
        assertEquals(initialServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(initialServiceInfo.getIpv6Address(), ipV6Address);
        assertEquals(initialServiceInfo.getPort(), 5353);
        assertEquals(initialServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertNull(initialServiceInfo.getAttributeByKey("key"));
        assertEquals(initialServiceInfo.getInterfaceIndex(), 20);

        // Verify onServiceUpdated was called once for the second response.
        verify(mockListenerOne).onServiceUpdated(serviceInfoCaptor.capture());
        MdnsServiceInfo updatedServiceInfo = serviceInfoCaptor.getAllValues().get(2);
        assertEquals(updatedServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(updatedServiceInfo.getIpv6Address(), ipV6Address);
        assertEquals(updatedServiceInfo.getPort(), 5354);
        assertTrue(updatedServiceInfo.hasSubtypes());
        assertEquals(updatedServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertEquals(updatedServiceInfo.getAttributeByKey("key"), "value");
        assertEquals(updatedServiceInfo.getInterfaceIndex(), 20);
    }

    private void verifyServiceRemovedNoCallback(MdnsServiceBrowserListener listener) {
        verify(listener, never()).onServiceRemoved(any());
        verify(listener, never()).onServiceNameRemoved(any());
    }

    private void verifyServiceRemovedCallback(MdnsServiceBrowserListener listener,
            String serviceName, String[] serviceType, int interfaceIndex) {
        verify(listener).onServiceRemoved(argThat(
                info -> serviceName.equals(info.getServiceInstanceName())
                        && Arrays.equals(serviceType, info.getServiceType())
                        && info.getInterfaceIndex() == interfaceIndex));
        verify(listener).onServiceNameRemoved(argThat(
                info -> serviceName.equals(info.getServiceInstanceName())
                        && Arrays.equals(serviceType, info.getServiceType())
                        && info.getInterfaceIndex() == interfaceIndex));
    }

    @Test
    public void processResponse_goodBye() throws Exception {
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        client.startSendAndReceive(mockListenerTwo, MdnsSearchOptions.getDefaultOptions());

        final String serviceName = "service-instance-1";
        final String ipV6Address = "2000:3333::da6c:63ff:fe7c:7483";
        // Process the initial response.
        final MdnsResponse initialResponse =
                createResponse(
                        serviceName,
                        ipV6Address,
                        5353 /* port */,
                        /* subtype= */ "ABCDE",
                        Collections.emptyMap(),
                        INTERFACE_INDEX);
        client.processResponse(initialResponse);
        MdnsResponse response = mock(MdnsResponse.class);
        doReturn("goodbye-service").when(response).getServiceInstanceName();
        doReturn(INTERFACE_INDEX).when(response).getInterfaceIndex();
        doReturn(true).when(response).isGoodbye();
        client.processResponse(response);
        // Verify removed callback won't be called if the service is not existed.
        verifyServiceRemovedNoCallback(mockListenerOne);
        verifyServiceRemovedNoCallback(mockListenerTwo);

        // Verify removed callback would be called.
        doReturn(serviceName).when(response).getServiceInstanceName();
        client.processResponse(response);
        verifyServiceRemovedCallback(
                mockListenerOne, serviceName, SERVICE_TYPE_LABELS, INTERFACE_INDEX);
        verifyServiceRemovedCallback(
                mockListenerTwo, serviceName, SERVICE_TYPE_LABELS, INTERFACE_INDEX);
    }

    @Test
    public void reportExistingServiceToNewlyRegisteredListeners() throws Exception {
        // Process the initial response.
        MdnsResponse initialResponse =
                createResponse(
                        "service-instance-1",
                        "192.168.1.1",
                        5353,
                        /* subtype= */ "ABCDE",
                        Collections.emptyMap(),
                        INTERFACE_INDEX);
        client.processResponse(initialResponse);

        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());

        // Verify onServiceNameDiscovered was called once for the existing response.
        verify(mockListenerOne).onServiceNameDiscovered(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(0),
                "service-instance-1",
                SERVICE_TYPE_LABELS,
                "192.168.1.1" /* ipv4Address */,
                null /* ipv6Address */,
                5353 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                INTERFACE_INDEX);

        // Verify onServiceFound was called once for the existing response.
        verify(mockListenerOne).onServiceFound(serviceInfoCaptor.capture());
        MdnsServiceInfo existingServiceInfo = serviceInfoCaptor.getAllValues().get(1);
        assertEquals(existingServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(existingServiceInfo.getIpv4Address(), "192.168.1.1");
        assertEquals(existingServiceInfo.getPort(), 5353);
        assertEquals(existingServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertNull(existingServiceInfo.getAttributeByKey("key"));

        // Process a goodbye message for the existing response.
        MdnsResponse goodByeResponse = mock(MdnsResponse.class);
        when(goodByeResponse.getServiceInstanceName()).thenReturn("service-instance-1");
        when(goodByeResponse.isGoodbye()).thenReturn(true);
        client.processResponse(goodByeResponse);

        client.startSendAndReceive(mockListenerTwo, MdnsSearchOptions.getDefaultOptions());

        // Verify onServiceFound was not called on the newly registered listener after the existing
        // response is gone.
        verify(mockListenerTwo, never()).onServiceNameDiscovered(any(MdnsServiceInfo.class));
        verify(mockListenerTwo, never()).onServiceFound(any(MdnsServiceInfo.class));
    }

    @Test
    public void processResponse_notAllowRemoveSearch_shouldNotRemove() throws Exception {
        final String serviceInstanceName = "service-instance-1";
        client.startSendAndReceive(
                mockListenerOne,
                MdnsSearchOptions.newBuilder().build());
        Runnable firstMdnsTask = currentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        MdnsResponse initialResponse =
                createMockResponse(
                        serviceInstanceName, "192.168.1.1", 5353, List.of("ABCDE"),
                        Map.of(), INTERFACE_INDEX);
        client.processResponse(initialResponse);

        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is after TTL.
        when(initialResponse.getServiceRecord().getRemainingTTL(anyLong())).thenReturn((long) 0);
        firstMdnsTask.run();

        // Verify removed callback was not called.
        verifyServiceRemovedNoCallback(mockListenerOne);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void processResponse_allowSearchOptionsToRemoveExpiredService_shouldRemove()
            throws Exception {
        //MdnsConfigsFlagsImpl.allowSearchOptionsToRemoveExpiredService.override(true);
        final String serviceInstanceName = "service-instance-1";
        client =
                new MdnsServiceTypeClient(SERVICE_TYPE, mockSocketClient, currentThreadExecutor) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mockPacketWriter;
                    }
                };
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        Runnable firstMdnsTask = currentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        MdnsResponse initialResponse =
                createMockResponse(
                        serviceInstanceName, "192.168.1.1", 5353, List.of("ABCDE"),
                        Map.of(), INTERFACE_INDEX);
        client.processResponse(initialResponse);

        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is under TTL.
        when(initialResponse.getServiceRecord().getRemainingTTL(anyLong())).thenReturn((long) 1000);
        firstMdnsTask.run();

        // Verify removed callback was not called.
        verifyServiceRemovedNoCallback(mockListenerOne);

        // Simulate the case where the response is after TTL.
        when(initialResponse.getServiceRecord().getRemainingTTL(anyLong())).thenReturn((long) 0);
        firstMdnsTask.run();

        // Verify removed callback was called.
        verifyServiceRemovedCallback(
                mockListenerOne, serviceInstanceName, SERVICE_TYPE_LABELS, INTERFACE_INDEX);
    }

    @Test
    public void processResponse_searchOptionsNotEnableServiceRemoval_shouldNotRemove()
            throws Exception {
        final String serviceInstanceName = "service-instance-1";
        client =
                new MdnsServiceTypeClient(SERVICE_TYPE, mockSocketClient, currentThreadExecutor) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mockPacketWriter;
                    }
                };
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        Runnable firstMdnsTask = currentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        MdnsResponse initialResponse =
                createMockResponse(
                        serviceInstanceName, "192.168.1.1", 5353, List.of("ABCDE"),
                        Map.of(), INTERFACE_INDEX);
        client.processResponse(initialResponse);

        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is after TTL.
        when(initialResponse.getServiceRecord().getRemainingTTL(anyLong())).thenReturn((long) 0);
        firstMdnsTask.run();

        // Verify removed callback was not called.
        verifyServiceRemovedNoCallback(mockListenerOne);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void processResponse_removeServiceAfterTtlExpiresEnabled_shouldRemove()
            throws Exception {
        //MdnsConfigsFlagsImpl.removeServiceAfterTtlExpires.override(true);
        final String serviceInstanceName = "service-instance-1";
        client =
                new MdnsServiceTypeClient(SERVICE_TYPE, mockSocketClient, currentThreadExecutor) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mockPacketWriter;
                    }
                };
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        Runnable firstMdnsTask = currentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        MdnsResponse initialResponse =
                createMockResponse(
                        serviceInstanceName, "192.168.1.1", 5353, List.of("ABCDE"),
                        Map.of(), INTERFACE_INDEX);
        client.processResponse(initialResponse);

        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is after TTL.
        when(initialResponse.getServiceRecord().getRemainingTTL(anyLong())).thenReturn((long) 0);
        firstMdnsTask.run();

        // Verify removed callback was called.
        verifyServiceRemovedCallback(
                mockListenerOne, serviceInstanceName, SERVICE_TYPE_LABELS, INTERFACE_INDEX);
    }

    @Test
    public void testProcessResponse_InOrder() throws Exception {
        final String serviceName = "service-instance";
        final String ipV4Address = "192.0.2.0";
        final String ipV6Address = "2001:db8::";
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        InOrder inOrder = inOrder(mockListenerOne);

        // Process the initial response which is incomplete.
        final MdnsResponse initialResponse =
                createResponse(
                        serviceName,
                        null,
                        5353,
                        "ABCDE" /* subtype */,
                        Collections.emptyMap(),
                        INTERFACE_INDEX);
        client.processResponse(initialResponse);

        // Process a second response which has ip address to make response become complete.
        final MdnsResponse secondResponse =
                createResponse(
                        serviceName,
                        ipV4Address,
                        5353,
                        "ABCDE" /* subtype */,
                        Collections.emptyMap(),
                        INTERFACE_INDEX);
        client.processResponse(secondResponse);

        // Process a third response with a different ip address, port and updated text attributes.
        final MdnsResponse thirdResponse =
                createResponse(
                        serviceName,
                        ipV6Address,
                        5354,
                        "ABCDE" /* subtype */,
                        Collections.singletonMap("key", "value"),
                        INTERFACE_INDEX);
        client.processResponse(thirdResponse);

        // Process the last response which is goodbye message.
        final MdnsResponse lastResponse = mock(MdnsResponse.class);
        doReturn(serviceName).when(lastResponse).getServiceInstanceName();
        doReturn(true).when(lastResponse).isGoodbye();
        client.processResponse(lastResponse);

        // Verify onServiceNameDiscovered was first called for the initial response.
        inOrder.verify(mockListenerOne).onServiceNameDiscovered(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(0),
                serviceName,
                SERVICE_TYPE_LABELS,
                null /* ipv4Address */,
                null /* ipv6Address */,
                5353 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                INTERFACE_INDEX);

        // Verify onServiceFound was second called for the second response.
        inOrder.verify(mockListenerOne).onServiceFound(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(1),
                serviceName,
                SERVICE_TYPE_LABELS,
                ipV4Address /* ipv4Address */,
                null /* ipv6Address */,
                5353 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                INTERFACE_INDEX);

        // Verify onServiceUpdated was third called for the third response.
        inOrder.verify(mockListenerOne).onServiceUpdated(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(2),
                serviceName,
                SERVICE_TYPE_LABELS,
                ipV4Address /* ipv4Address */,
                ipV6Address /* ipv6Address */,
                5354 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", "value") /* attributes */,
                INTERFACE_INDEX);

        // Verify onServiceRemoved was called for the last response.
        inOrder.verify(mockListenerOne).onServiceRemoved(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(3),
                serviceName,
                SERVICE_TYPE_LABELS,
                ipV4Address /* ipv4Address */,
                ipV6Address /* ipv6Address */,
                5354 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", "value") /* attributes */,
                INTERFACE_INDEX);

        // Verify onServiceNameRemoved was called for the last response.
        inOrder.verify(mockListenerOne).onServiceNameRemoved(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(4),
                serviceName,
                SERVICE_TYPE_LABELS,
                ipV4Address /* ipv4Address */,
                ipV6Address /* ipv6Address */,
                5354 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", "value") /* attributes */,
                INTERFACE_INDEX);
    }

    // verifies that the right query was enqueued with the right delay, and send query by executing
    // the runnable.
    private void verifyAndSendQuery(int index, long timeInMs, boolean expectsUnicastResponse) {
        assertEquals(currentThreadExecutor.getAndClearLastScheduledDelayInMs(), timeInMs);
        currentThreadExecutor.getAndClearLastScheduledRunnable().run();
        if (expectsUnicastResponse) {
            verify(mockSocketClient).sendUnicastPacket(expectedPackets[index]);
        } else {
            verify(mockSocketClient).sendMulticastPacket(expectedPackets[index]);
        }
    }

    // A fake ScheduledExecutorService that keeps tracking the last scheduled Runnable and its delay
    // time.
    private class FakeExecutor extends ScheduledThreadPoolExecutor {
        private long lastScheduledDelayInMs;
        private Runnable lastScheduledRunnable;
        private Runnable lastSubmittedRunnable;
        private int futureIndex;

        FakeExecutor() {
            super(1);
            lastScheduledDelayInMs = -1;
        }

        @Override
        public Future<?> submit(Runnable command) {
            Future<?> future = super.submit(command);
            lastSubmittedRunnable = command;
            return future;
        }

        // Don't call through the real implementation, just track the scheduled Runnable, and
        // returns a ScheduledFuture.
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            lastScheduledDelayInMs = delay;
            lastScheduledRunnable = command;
            return expectedSendFutures[futureIndex++];
        }

        // Returns the delay of the last scheduled task, and clear it.
        long getAndClearLastScheduledDelayInMs() {
            long val = lastScheduledDelayInMs;
            lastScheduledDelayInMs = -1;
            return val;
        }

        // Returns the last scheduled task, and clear it.
        Runnable getAndClearLastScheduledRunnable() {
            Runnable val = lastScheduledRunnable;
            lastScheduledRunnable = null;
            return val;
        }

        Runnable getAndClearSubmittedRunnable() {
            Runnable val = lastSubmittedRunnable;
            lastSubmittedRunnable = null;
            return val;
        }
    }

    // Creates a mock mDNS response.
    private MdnsResponse createMockResponse(
            @NonNull String serviceInstanceName,
            @NonNull String host,
            int port,
            @NonNull List<String> subtypes,
            @NonNull Map<String, String> textAttributes,
            int interfaceIndex)
            throws Exception {
        String[] hostName = new String[]{"hostname"};
        MdnsServiceRecord serviceRecord = mock(MdnsServiceRecord.class);
        when(serviceRecord.getServiceHost()).thenReturn(hostName);
        when(serviceRecord.getServicePort()).thenReturn(port);

        MdnsResponse response = spy(new MdnsResponse(0));

        MdnsInetAddressRecord inetAddressRecord = mock(MdnsInetAddressRecord.class);
        if (host.contains(":")) {
            when(inetAddressRecord.getInet6Address())
                    .thenReturn((Inet6Address) Inet6Address.getByName(host));
            response.setInet6AddressRecord(inetAddressRecord);
            response.setInterfaceIndex(interfaceIndex);
        } else {
            when(inetAddressRecord.getInet4Address())
                    .thenReturn((Inet4Address) Inet4Address.getByName(host));
            response.setInet4AddressRecord(inetAddressRecord);
            response.setInterfaceIndex(interfaceIndex);
        }

        MdnsTextRecord textRecord = mock(MdnsTextRecord.class);
        List<String> textStrings = new ArrayList<>();
        List<TextEntry> textEntries = new ArrayList<>();
        for (Map.Entry<String, String> kv : textAttributes.entrySet()) {
            textStrings.add(kv.getKey() + "=" + kv.getValue());
            textEntries.add(new TextEntry(kv.getKey(), kv.getValue().getBytes(UTF_8)));
        }
        when(textRecord.getStrings()).thenReturn(textStrings);
        when(textRecord.getEntries()).thenReturn(textEntries);

        response.setServiceRecord(serviceRecord);
        response.setTextRecord(textRecord);

        doReturn(false).when(response).isGoodbye();
        doReturn(true).when(response).isComplete();
        doReturn(serviceInstanceName).when(response).getServiceInstanceName();
        doReturn(new ArrayList<>(subtypes)).when(response).getSubtypes();
        return response;
    }

    // Creates a mDNS response.
    private MdnsResponse createResponse(
            @NonNull String serviceInstanceName,
            @Nullable String host,
            int port,
            @NonNull String subtype,
            @NonNull Map<String, String> textAttributes,
            int interfaceIndex)
            throws Exception {
        MdnsResponse response = new MdnsResponse(0);
        response.setInterfaceIndex(interfaceIndex);

        // Set PTR record
        final MdnsPointerRecord pointerRecord = new MdnsPointerRecord(
                new String[]{subtype, MdnsConstants.SUBTYPE_LABEL, "test"} /* name */,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                120000L /* ttlMillis */,
                new String[]{serviceInstanceName});
        response.addPointerRecord(pointerRecord);

        // Set SRV record.
        final MdnsServiceRecord serviceRecord = new MdnsServiceRecord(
                new String[] {"service"} /* name */,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                120000L /* ttlMillis */,
                0 /* servicePriority */,
                0 /* serviceWeight */,
                port,
                new String[]{"hostname"});
        response.setServiceRecord(serviceRecord);

        // Set A/AAAA record.
        if (host != null) {
            if (InetAddresses.parseNumericAddress(host) instanceof Inet6Address) {
                final MdnsInetAddressRecord inetAddressRecord = new MdnsInetAddressRecord(
                        new String[] {"address"} /* name */,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        120000L /* ttlMillis */,
                        Inet6Address.getByName(host));
                response.setInet6AddressRecord(inetAddressRecord);
            } else {
                final MdnsInetAddressRecord inetAddressRecord = new MdnsInetAddressRecord(
                        new String[] {"address"} /* name */,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        120000L /* ttlMillis */,
                        Inet4Address.getByName(host));
                response.setInet4AddressRecord(inetAddressRecord);
            }
        }

        // Set TXT record.
        final List<TextEntry> textEntries = new ArrayList<>();
        for (Map.Entry<String, String> kv : textAttributes.entrySet()) {
            textEntries.add(new TextEntry(kv.getKey(), kv.getValue().getBytes(UTF_8)));
        }
        final MdnsTextRecord textRecord = new MdnsTextRecord(
                new String[] {"text"} /* name */,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                120000L /* ttlMillis */,
                textEntries);
        response.setTextRecord(textRecord);
        return response;
    }
}