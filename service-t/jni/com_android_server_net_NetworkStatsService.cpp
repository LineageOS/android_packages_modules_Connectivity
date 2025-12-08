/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "NetworkStatsNative"

#include <cutils/qtaguid.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <jni.h>
#include <nativehelper/jni_macros.h>
#include <nativehelper/ScopedUtfChars.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <utils/Log.h>
#include <utils/misc.h>

#include "bpf/BpfUtils.h"
#include "netdbpf/BpfNetworkStats.h"
#include "netdbpf/NetworkTraceHandler.h"

using android::bpf::bpfGetUidStats;
using android::bpf::bpfGetIfaceStats;
using android::bpf::bpfRegisterIface;
using android::bpf::NetworkTraceHandler;

namespace android {

static struct {
    jclass theClass;
    jmethodID constructor;
    jfieldID rxBytes;
    jfieldID txBytes;
    jfieldID rxPackets;
    jfieldID txPackets;
} gNetworkStatsEntry;

static void nativeRegisterIface(JNIEnv* env, jclass clazz, jstring iface) {
    ScopedUtfChars iface8(env, iface);
    if (!iface8.c_str()) return;
    bpfRegisterIface(iface8.c_str());
}

static jobject statsValueToEntry(JNIEnv* env, StatsValue* stats) {
    // Create a new instance of the Java class
    jobject result = env->NewObject(gNetworkStatsEntry.theClass, gNetworkStatsEntry.constructor);
    if (!result) return nullptr;

    // Set the values of the structure fields in the Java object
    env->SetLongField(result, gNetworkStatsEntry.rxBytes, stats->rxBytes);
    env->SetLongField(result, gNetworkStatsEntry.txBytes, stats->txBytes);
    env->SetLongField(result, gNetworkStatsEntry.rxPackets, stats->rxPackets);
    env->SetLongField(result, gNetworkStatsEntry.txPackets, stats->txPackets);

    return result;
}

static jobject nativeGetTotalStat(JNIEnv* env, jclass clazz) {
    StatsValue stats = {};
    if (bpfGetIfaceStats(nullptr, &stats)) return nullptr;
    return statsValueToEntry(env, &stats);
}

static jobject nativeGetIfaceStat(JNIEnv* env, jclass clazz, jstring iface) {
    ScopedUtfChars iface8(env, iface);
    if (!iface8.c_str()) return nullptr;

    StatsValue stats = {};
    if (bpfGetIfaceStats(iface8.c_str(), &stats)) return nullptr;
    return statsValueToEntry(env, &stats);
}

static jobject nativeGetUidStat(JNIEnv* env, jclass clazz, jint uid) {
    StatsValue stats = {};
    if (bpfGetUidStats(uid, &stats)) return nullptr;
    return statsValueToEntry(env, &stats);
}

static void nativeInitNetworkTracing(JNIEnv* env, jclass clazz) {
    NetworkTraceHandler::InitPerfettoTracing();
}

static const JNINativeMethod gMethods[] = {
    MAKE_JNI_NATIVE_METHOD_AUTOSIG("nativeRegisterIface", nativeRegisterIface),
    MAKE_JNI_NATIVE_METHOD("nativeGetTotalStat", "()Landroid/net/NetworkStats$Entry;", nativeGetTotalStat),
    MAKE_JNI_NATIVE_METHOD("nativeGetIfaceStat", "(Ljava/lang/String;)Landroid/net/NetworkStats$Entry;", nativeGetIfaceStat),
    MAKE_JNI_NATIVE_METHOD("nativeGetUidStat", "(I)Landroid/net/NetworkStats$Entry;", nativeGetUidStat),
    MAKE_JNI_NATIVE_METHOD_AUTOSIG("nativeInitNetworkTracing", nativeInitNetworkTracing),
};

int register_android_server_net_NetworkStatsService(JNIEnv* env) {
    if (jniRegisterNativeMethods(env,
        "android/net/connectivity/com/android/server/net/NetworkStatsService",
        gMethods,
        NELEM(gMethods))) abort();

    // Find the Java class that represents the structure
    jclass clazz = env->FindClass("android/net/NetworkStats$Entry");
    if (!clazz) abort();
    clazz = static_cast<jclass>(env->NewGlobalRef(clazz));
    if (!clazz) abort();
    gNetworkStatsEntry.theClass = clazz;

    // Find the constructor.
    gNetworkStatsEntry.constructor = env->GetMethodID(clazz, "<init>", "()V");
    if (!gNetworkStatsEntry.constructor) abort();

    // and the individual fields...
    gNetworkStatsEntry.rxBytes = env->GetFieldID(clazz, "rxBytes", "J");
    if (!gNetworkStatsEntry.rxBytes) abort();

    gNetworkStatsEntry.txBytes = env->GetFieldID(clazz, "txBytes", "J");
    if (!gNetworkStatsEntry.txBytes) abort();

    gNetworkStatsEntry.rxPackets = env->GetFieldID(clazz, "rxPackets", "J");
    if (!gNetworkStatsEntry.rxPackets) abort();

    gNetworkStatsEntry.txPackets = env->GetFieldID(clazz, "txPackets", "J");
    if (!gNetworkStatsEntry.txPackets) abort();

    return 0;
}

}
