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

#define LOG_TAG "NetworkTrace"

#include "netdbpf/NetworkTraceHandler.h"

#include <arpa/inet.h>
#include <bpf/BpfUtils.h>
#include <log/log.h>
#include <perfetto/config/android/network_trace_config.pbzero.h>
#include <perfetto/trace/android/network_trace.pbzero.h>
#include <perfetto/trace/profiling/profile_packet.pbzero.h>
#include <perfetto/tracing/platform.h>
#include <perfetto/tracing/tracing.h>

// Note: this is initializing state for a templated Perfetto type that resides
// in the `perfetto` namespace. This must be defined in the global scope.
PERFETTO_DEFINE_DATA_SOURCE_STATIC_MEMBERS(android::bpf::NetworkTraceHandler);

namespace android {
namespace bpf {
using ::android::bpf::internal::NetworkTracePoller;
using ::perfetto::protos::pbzero::NetworkPacketEvent;
using ::perfetto::protos::pbzero::NetworkPacketTraceConfig;
using ::perfetto::protos::pbzero::TracePacket;
using ::perfetto::protos::pbzero::TrafficDirection;

// static
void NetworkTraceHandler::RegisterDataSource() {
  ALOGD("Registering Perfetto data source");
  perfetto::DataSourceDescriptor dsd;
  dsd.set_name("android.network_packets");
  NetworkTraceHandler::Register(dsd);
}

// static
void NetworkTraceHandler::InitPerfettoTracing() {
  perfetto::TracingInitArgs args = {};
  args.backends |= perfetto::kSystemBackend;
  args.enable_system_consumer = false;
  perfetto::Tracing::Initialize(args);
  NetworkTraceHandler::RegisterDataSource();
}

// static
NetworkTracePoller NetworkTraceHandler::sPoller(
    [](const std::vector<PacketTrace>& packets) {
      // Trace calls the provided callback for each active session. The context
      // gets a reference to the NetworkTraceHandler instance associated with
      // the session and delegates writing. The corresponding handler will write
      // with the setting specified in the trace config.
      NetworkTraceHandler::Trace([&](NetworkTraceHandler::TraceContext ctx) {
        ctx.GetDataSourceLocked()->Write(packets, ctx);
      });
    });

void NetworkTraceHandler::OnSetup(const SetupArgs& args) {
  const std::string& raw = args.config->network_packet_trace_config_raw();
  NetworkPacketTraceConfig::Decoder config(raw);

  mPollMs = config.poll_ms();
  if (mPollMs < 100) {
    ALOGI("poll_ms is missing or below the 100ms minimum. Increasing to 100ms");
    mPollMs = 100;
  }

  mInternLimit = config.intern_limit();
  mAggregationThreshold = config.aggregation_threshold();
  mDropLocalPort = config.drop_local_port();
  mDropRemotePort = config.drop_remote_port();
  mDropTcpFlags = config.drop_tcp_flags();
}

void NetworkTraceHandler::OnStart(const StartArgs&) {
  mStarted = sPoller.Start(mPollMs);
}

void NetworkTraceHandler::OnStop(const StopArgs&) {
  if (mStarted) sPoller.Stop();
  mStarted = false;
}

void NetworkTraceHandler::Write(const std::vector<PacketTrace>& packets,
                                NetworkTraceHandler::TraceContext& ctx) {
  for (const PacketTrace& pkt : packets) {
    Fill(pkt, *ctx.NewTracePacket());
  }
}

// static class method
void NetworkTraceHandler::Fill(const PacketTrace& src, TracePacket& dst) {
  dst.set_timestamp(src.timestampNs);
  auto* event = dst.set_network_packet();
  event->set_direction(src.egress ? TrafficDirection::DIR_EGRESS
                                  : TrafficDirection::DIR_INGRESS);
  event->set_length(src.length);
  event->set_uid(src.uid);
  event->set_tag(src.tag);

  event->set_local_port(src.egress ? ntohs(src.sport) : ntohs(src.dport));
  event->set_remote_port(src.egress ? ntohs(src.dport) : ntohs(src.sport));

  event->set_ip_proto(src.ipProto);
  event->set_tcp_flags(src.tcpFlags);

  char ifname[IF_NAMESIZE] = {};
  if (if_indextoname(src.ifindex, ifname) == ifname) {
    event->set_interface(std::string(ifname));
  } else {
    event->set_interface("error");
  }
}

}  // namespace bpf
}  // namespace android
