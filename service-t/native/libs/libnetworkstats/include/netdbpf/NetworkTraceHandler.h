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

#include "bpf/BpfMap.h"
#include "bpf/BpfRingbuf.h"

// For PacketTrace struct definition
#include "netd.h"

namespace android {
namespace bpf {

class NetworkTraceHandler : public perfetto::DataSource<NetworkTraceHandler> {
 public:
  // Registers this DataSource.
  static void RegisterDataSource();

  // Connects to the system Perfetto daemon and registers the trace handler.
  static void InitPerfettoTracing();

  // Initialize with the default Perfetto callback.
  NetworkTraceHandler();

  // Testonly: initialize with a callback capable of intercepting data.
  NetworkTraceHandler(std::function<void(const PacketTrace&)> callback)
      : mCallback(std::move(callback)) {}

  // Testonly: standalone functions without perfetto dependency.
  bool Start();
  bool Stop();
  bool ConsumeAll();

  // perfetto::DataSource overrides:
  void OnSetup(const SetupArgs&) override;
  void OnStart(const StartArgs&) override;
  void OnStop(const StopArgs&) override;

  // Convert a PacketTrace into a Perfetto trace packet.
  void Fill(const PacketTrace& src,
            ::perfetto::protos::pbzero::TracePacket& dst);

 private:
  void Loop();

  // How often to poll the ring buffer, defined by the trace config.
  uint32_t mPollMs;

  // The function to process PacketTrace, typically a Perfetto sink.
  std::function<void(const PacketTrace&)> mCallback;

  // The BPF ring buffer handle.
  std::unique_ptr<BpfRingbuf<PacketTrace>> mRingBuffer;

  // The packet tracing config map (really a 1-element array).
  BpfMap<uint32_t, bool> mConfigurationMap;

  // This must be the last member, causing it to be the first deleted. If it is
  // not, members required for callbacks can be deleted before it's stopped.
  std::unique_ptr<perfetto::base::TaskRunner> mTaskRunner;
};

}  // namespace bpf
}  // namespace android
