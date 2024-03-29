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

#include "jni.h"
#include "utils/Log.h"

namespace android {
int register_com_android_server_thread_TunInterfaceController(JNIEnv* env);
int register_com_android_server_thread_InfraInterfaceController(JNIEnv* env);
}

using namespace android;

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = NULL;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("GetEnv failed!");
        return -1;
    }
    ALOG_ASSERT(env != NULL, "Could not retrieve the env!");

    register_com_android_server_thread_TunInterfaceController(env);
    register_com_android_server_thread_InfraInterfaceController(env);
    return JNI_VERSION_1_4;
}
