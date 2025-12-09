/*
 * Copyright (C) 2025 The Android Open Source Project
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
#define LOG_TAG "CommonConnectivityJni"

#include <dlfcn.h>
#include <sys/timerfd.h>

#include <android/binder_ibinder_jni.h>
#include <android/log.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>

#define MSEC_PER_SEC 1000
#define NSEC_PER_MSEC 1000000
#define LIBBINDER_NDK_PATH "libbinder_ndk.so"

namespace android {

static jint createTimerFd(JNIEnv *env, jclass clazz) {
    int tfd;
    // For safety, the file descriptor should have O_NONBLOCK(TFD_NONBLOCK) set
    // using fcntl during creation. This ensures that, in the worst-case
    // scenario, an EAGAIN error is returned when reading.
    tfd = timerfd_create(CLOCK_BOOTTIME, TFD_NONBLOCK);
    if (tfd == -1) {
        jniThrowErrnoException(env, "createTimerFd", tfd);
    }
    return tfd;
}

static void setTimerFdTime(JNIEnv *env, jclass clazz, jint tfd,
                           jlong milliseconds) {
    struct itimerspec new_value;
    new_value.it_value.tv_sec = milliseconds / MSEC_PER_SEC;
    new_value.it_value.tv_nsec = (milliseconds % MSEC_PER_SEC) * NSEC_PER_MSEC;
    // Set the interval time to 0 because it's designed for repeated timer
    // expirations after the initial expiration, which doesn't fit the current
    // usage.
    new_value.it_interval.tv_sec = 0;
    new_value.it_interval.tv_nsec = 0;

    int ret = timerfd_settime(tfd, 0, &new_value, NULL);
    if (ret == -1) {
        jniThrowErrnoException(env, "setTimerFdTime", ret);
    }
}

using waitForServiceFunc = AIBinder *(*)(const char *instance);

static jobject ServiceManagerWrapper_waitForService(JNIEnv *env, jobject clazz,
                                                    jstring serviceName) {
    // AServiceManager_waitForService system APIs currently only callable via
    // dlsym(): b/376759605. When built with sdk_version current, the NDK stubs
    // for "sdk" variants only include NDK APIs, excluding #systemapi APIs.
    // TODO: AServiceManager_waitForService requires SDK version 31.
    // Remove this after dropping mainline support for R.
    auto waitForService = (waitForServiceFunc)dlsym(RTLD_DEFAULT, "AServiceManager_waitForService");
    if (waitForService == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to look up AServiceManager_waitForService");
        return nullptr;
    }

    ScopedUtfChars name(env, serviceName);
    return AIBinder_toJavaBinder(env, waitForService(name.c_str()));
}

//------------------------------------------------------------------------------

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    {"createTimerFd", "()I", (void *)createTimerFd},
    {"setTimerFdTime", "(IJ)V", (void *)setTimerFdTime},
    {"waitForService", "(Ljava/lang/String;)Landroid/os/IBinder;",
     (void *)ServiceManagerWrapper_waitForService},
};

int register_CommonConnectivityJni(JNIEnv *env, char const *class_name) {
    return jniRegisterNativeMethods(env, class_name, gMethods, NELEM(gMethods));
}

} // namespace android
