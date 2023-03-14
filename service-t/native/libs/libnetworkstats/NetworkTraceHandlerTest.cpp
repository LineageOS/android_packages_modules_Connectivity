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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <vector>

#include "netdbpf/NetworkTraceHandler.h"
#include "protos/perfetto/config/android/network_trace_config.gen.h"
#include "protos/perfetto/trace/android/network_trace.pb.h"
#include "protos/perfetto/trace/trace.pb.h"
#include "protos/perfetto/trace/trace_packet.pb.h"

namespace android {
namespace bpf {
using ::perfetto::protos::NetworkPacketEvent;
using ::perfetto::protos::NetworkPacketTraceConfig;
using ::perfetto::protos::Trace;
using ::perfetto::protos::TracePacket;
using ::perfetto::protos::TrafficDirection;

// This handler makes OnStart and OnStop a no-op so that tracing is not really
// started on the device.
class HandlerForTest : public NetworkTraceHandler {
 public:
  void OnStart(const StartArgs&) override {}
  void OnStop(const StopArgs&) override {}
};

class NetworkTraceHandlerTest : public testing::Test {
 protected:
  // Starts a tracing session with the handler under test.
  std::unique_ptr<perfetto::TracingSession> StartTracing(
      NetworkPacketTraceConfig settings) {
    perfetto::TracingInitArgs args;
    args.backends = perfetto::kInProcessBackend;
    perfetto::Tracing::Initialize(args);

    perfetto::DataSourceDescriptor dsd;
    dsd.set_name("test.network_packets");
    HandlerForTest::Register(dsd);

    perfetto::TraceConfig cfg;
    cfg.add_buffers()->set_size_kb(1024);
    auto* config = cfg.add_data_sources()->mutable_config();
    config->set_name("test.network_packets");
    config->set_network_packet_trace_config_raw(settings.SerializeAsString());

    auto session = perfetto::Tracing::NewTrace(perfetto::kInProcessBackend);
    session->Setup(cfg);
    session->StartBlocking();
    return session;
  }

  // Stops the trace session and reports all relevant trace packets.
  bool StopTracing(perfetto::TracingSession* session,
                   std::vector<TracePacket>* output) {
    session->StopBlocking();

    Trace trace;
    std::vector<char> raw_trace = session->ReadTraceBlocking();
    if (!trace.ParseFromArray(raw_trace.data(), raw_trace.size())) {
      ADD_FAILURE() << "trace.ParseFromArray failed";
      return false;
    }

    // This is a real trace and includes irrelevant trace packets such as trace
    // metadata. The following strips the results to just the packets we want.
    for (const auto& pkt : trace.packet()) {
      if (pkt.has_network_packet() || pkt.has_network_packet_bundle()) {
        output->emplace_back(pkt);
      }
    }

    return true;
  }

  // This runs a trace with a single call to Write.
  bool TraceAndSortPackets(const std::vector<PacketTrace>& input,
                           std::vector<TracePacket>* output,
                           NetworkPacketTraceConfig config = {}) {
    auto session = StartTracing(config);
    HandlerForTest::Trace([&](HandlerForTest::TraceContext ctx) {
      ctx.GetDataSourceLocked()->Write(input, ctx);
      ctx.Flush();
    });

    if (!StopTracing(session.get(), output)) {
      return false;
    }

    // Sort to provide deterministic ordering regardless of Perfetto internals
    // or implementation-defined (e.g. hash map) reshuffling.
    std::sort(output->begin(), output->end(),
              [](const TracePacket& a, const TracePacket& b) {
                return a.timestamp() < b.timestamp();
              });

    return true;
  }
};

TEST_F(NetworkTraceHandlerTest, WriteBasicFields) {
  std::vector<PacketTrace> input = {
      PacketTrace{
          .timestampNs = 1000,
          .length = 100,
          .uid = 10,
          .tag = 123,
          .ipProto = 6,
          .tcpFlags = 1,
      },
  };

  std::vector<TracePacket> events;
  ASSERT_TRUE(TraceAndSortPackets(input, &events));

  ASSERT_EQ(events.size(), 1);
  EXPECT_THAT(events[0].timestamp(), 1000);
  EXPECT_THAT(events[0].network_packet().uid(), 10);
  EXPECT_THAT(events[0].network_packet().tag(), 123);
  EXPECT_THAT(events[0].network_packet().ip_proto(), 6);
  EXPECT_THAT(events[0].network_packet().tcp_flags(), 1);
  EXPECT_THAT(events[0].network_packet().length(), 100);
}

TEST_F(NetworkTraceHandlerTest, WriteDirectionAndPorts) {
  std::vector<PacketTrace> input = {
      PacketTrace{
          .timestampNs = 1,
          .sport = htons(8080),
          .dport = htons(443),
          .egress = true,
      },
      PacketTrace{
          .timestampNs = 2,
          .sport = htons(443),
          .dport = htons(8080),
          .egress = false,
      },
  };

  std::vector<TracePacket> events;
  ASSERT_TRUE(TraceAndSortPackets(input, &events));

  ASSERT_EQ(events.size(), 2);
  EXPECT_THAT(events[0].network_packet().local_port(), 8080);
  EXPECT_THAT(events[0].network_packet().remote_port(), 443);
  EXPECT_THAT(events[0].network_packet().direction(),
              TrafficDirection::DIR_EGRESS);
  EXPECT_THAT(events[1].network_packet().local_port(), 8080);
  EXPECT_THAT(events[1].network_packet().remote_port(), 443);
  EXPECT_THAT(events[1].network_packet().direction(),
              TrafficDirection::DIR_INGRESS);
}

TEST_F(NetworkTraceHandlerTest, BasicBundling) {
  // TODO: remove this once bundling becomes default. Until then, set arbitrary
  // aggregation threshold to enable bundling.
  NetworkPacketTraceConfig config;
  config.set_aggregation_threshold(10);

  std::vector<PacketTrace> input = {
      PacketTrace{.uid = 123, .timestampNs = 2, .length = 200},
      PacketTrace{.uid = 123, .timestampNs = 1, .length = 100},
      PacketTrace{.uid = 123, .timestampNs = 4, .length = 300},

      PacketTrace{.uid = 456, .timestampNs = 2, .length = 400},
      PacketTrace{.uid = 456, .timestampNs = 4, .length = 100},
  };

  std::vector<TracePacket> events;
  ASSERT_TRUE(TraceAndSortPackets(input, &events, config));

  ASSERT_EQ(events.size(), 2);

  EXPECT_THAT(events[0].timestamp(), 1);
  EXPECT_THAT(events[0].network_packet_bundle().ctx().uid(), 123);
  EXPECT_THAT(events[0].network_packet_bundle().packet_lengths(),
              testing::ElementsAre(200, 100, 300));
  EXPECT_THAT(events[0].network_packet_bundle().packet_timestamps(),
              testing::ElementsAre(1, 0, 3));

  EXPECT_THAT(events[1].timestamp(), 2);
  EXPECT_THAT(events[1].network_packet_bundle().ctx().uid(), 456);
  EXPECT_THAT(events[1].network_packet_bundle().packet_lengths(),
              testing::ElementsAre(400, 100));
  EXPECT_THAT(events[1].network_packet_bundle().packet_timestamps(),
              testing::ElementsAre(0, 2));
}

}  // namespace bpf
}  // namespace android
