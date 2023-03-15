/**
 * Copyright (c) 2023, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <perfetto/base/task_runner.h>
#include <perfetto/tracing.h>

#include <string>
#include <unordered_map>

#include "netdbpf/NetworkTracePoller.h"

// For PacketTrace struct definition
#include "netd.h"

namespace android {
namespace bpf {

// NetworkTraceHandler implements the android.network_packets data source. This
// class is registered with Perfetto and is instantiated when tracing starts and
// destroyed when tracing ends. There is one instance per trace session.
class NetworkTraceHandler : public perfetto::DataSource<NetworkTraceHandler> {
 public:
  // Registers this DataSource.
  static void RegisterDataSource();

  // Connects to the system Perfetto daemon and registers the trace handler.
  static void InitPerfettoTracing();

  // perfetto::DataSource overrides:
  void OnSetup(const SetupArgs& args) override;
  void OnStart(const StartArgs&) override;
  void OnStop(const StopArgs&) override;

  // Writes the packets as Perfetto TracePackets, creating packets as needed
  // using the provided callback (which allows easy testing).
  void Write(const std::vector<PacketTrace>& packets,
             NetworkTraceHandler::TraceContext& ctx);

 private:
  // Convert a PacketTrace into a Perfetto trace packet.
  void Fill(const PacketTrace& src,
            ::perfetto::protos::pbzero::NetworkPacketEvent* event);

  static internal::NetworkTracePoller sPoller;
  bool mStarted;

  // Values from config, see proto for details.
  uint32_t mPollMs;
  uint32_t mInternLimit;
  uint32_t mAggregationThreshold;
  bool mDropLocalPort;
  bool mDropRemotePort;
  bool mDropTcpFlags;
};

}  // namespace bpf
}  // namespace android
