/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <android/file_descriptor_jni.h>
#include <android/multinetwork.h>
#include <dlfcn.h>
#include <nativehelper/JNIHelp.h>

namespace android {

static jint tagSocketFd(JNIEnv* env, jclass, jobject fileDescriptor, jint tag, jint uid) {
  int fd = AFileDescriptor_getFd(env, fileDescriptor);
  if (fd == -1) return -EBADF;
  return android_tag_socket_with_uid(fd, tag, uid);
}

static jint untagSocketFd(JNIEnv* env, jclass, jobject fileDescriptor) {
  int fd = AFileDescriptor_getFd(env, fileDescriptor);
  if (fd == -1) return -EBADF;
  return android_untag_socket(fd);
}

// A system API of the platform's libandroid, so looked up once, as the methods are registered:
// the module also runs on platforms without it.
using SetThreadDnsUid = int (*)(uid_t);
static SetThreadDnsUid sSetThreadDnsUid;

static void setThreadDnsUid(JNIEnv*, jclass, jint uid) {
  if (sSetThreadDnsUid) sSetThreadDnsUid(uid);
}

static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "native_tagSocketFd", "(Ljava/io/FileDescriptor;II)I", (void*) tagSocketFd },
    { "native_untagSocketFd", "(Ljava/io/FileDescriptor;)I", (void*) untagSocketFd },
    { "native_setThreadDnsUid", "(I)V", (void*) setThreadDnsUid },
};

int register_android_net_TrafficStats(JNIEnv* env) {
    void* libandroid = dlopen("libandroid.so", RTLD_NOW);
    if (libandroid) {
        sSetThreadDnsUid = reinterpret_cast<SetThreadDnsUid>(
                dlsym(libandroid, "android_set_thread_dns_uid"));
    }
    return jniRegisterNativeMethods(env, "android/net/TrafficStats", gMethods, NELEM(gMethods));
}

};  // namespace android

