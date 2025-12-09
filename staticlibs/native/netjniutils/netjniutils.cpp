// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#define LOG_TAG "netjniutils"

#include "netjniutils/netjniutils.h"

#include <dlfcn.h>
#include <stdbool.h>
#include <stdlib.h>

#include <android/log.h>

namespace android {
namespace netjniutils {

int GetNativeFileDescriptor(JNIEnv* env, jobject javaFd) {
  if (!javaFd) return -1;

  // Since Android S, there is an NDK API to get a file descriptor present in libnativehelper.so.
  // libnativehelper is loaded into all processes by the zygote since the zygote uses it
  // to load the Android Runtime and is also a public library (because of the NDK API).
  typedef int (*ndkGetFd_t)(JNIEnv*, jobject);
  static const ndkGetFd_t ndkGetFd = []() -> ndkGetFd_t {
    void* handle = dlopen("libnativehelper.so", RTLD_NOLOAD | RTLD_NODELETE);
    auto ndkGetFd = reinterpret_cast<ndkGetFd_t>(dlsym(handle, "AFileDescriptor_getFd"));
    if (ndkGetFd == nullptr) {
      __android_log_print(ANDROID_LOG_FATAL, LOG_TAG,
                          "Failed to dlsym(AFileDescriptor_getFd): %s", dlerror());
      dlclose(handle);
      abort();
    }
    return ndkGetFd;
  }();

  return ndkGetFd(env, javaFd);
}

}  // namespace netjniutils
}  // namespace android
