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

#include "bpf/BpfUtils.h"

#include <jni.h>
#include <nativehelper/JNIHelp.h>

namespace android {

static jint native_synchronizeKernelRCU(JNIEnv* env, jobject self) {
    return -bpf::synchronizeKernelRCU();
}

/*
 * JNI registration.
 */
// clang-format off
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    {"native_synchronizeKernelRCU", "()I",
    (void*)native_synchronizeKernelRCU},
};
// clang-format on

int register_com_android_server_BpfNetMaps(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "android/net/connectivity/com/android/server/BpfNetMaps",
                                    gMethods, NELEM(gMethods));
}

}; // namespace android
