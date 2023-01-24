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

#include <string>
#include <unordered_map>

#include "bpf/BpfMap.h"
#include "bpf/BpfRingbuf.h"

// For PacketTrace struct definition
#include "netd.h"

namespace android {
namespace bpf {

class NetworkTraceHandler {
 public:
  // Initialize with a callback capable of intercepting data.
  NetworkTraceHandler(std::function<void(const PacketTrace&)> callback)
      : mCallback(std::move(callback)) {}

  // Standalone functions without perfetto dependency.
  bool Start();
  bool Stop();
  bool ConsumeAll();

 private:
  // The function to process PacketTrace, typically a Perfetto sink.
  std::function<void(const PacketTrace&)> mCallback;

  // The BPF ring buffer handle.
  std::unique_ptr<BpfRingbuf<PacketTrace>> mRingBuffer;

  // The packet tracing config map (really a 1-element array).
  BpfMap<uint32_t, bool> mConfigurationMap;
};

}  // namespace bpf
}  // namespace android
