/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <errno.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

#include "nativehelper/scoped_primitive_array.h"
#include "nativehelper/scoped_utf_chars.h"

#define BPF_FD_JUST_USE_INT
#include "BpfSyscallWrappers.h"

namespace android {

static jint com_android_networkstack_tethering_BpfMap_closeMap(JNIEnv *env, jobject clazz,
        jint fd) {
    int ret = close(fd);

    if (ret) jniThrowErrnoException(env, "closeMap", errno);

    return ret;
}

static jint com_android_networkstack_tethering_BpfMap_bpfFdGet(JNIEnv *env, jobject clazz,
        jstring path, jint mode) {
    ScopedUtfChars pathname(env, path);

    jint fd = bpf::bpfFdGet(pathname.c_str(), static_cast<unsigned>(mode));

    if (fd < 0) jniThrowErrnoException(env, "bpfFdGet", errno);

    return fd;
}

static void com_android_networkstack_tethering_BpfMap_writeToMapEntry(JNIEnv *env, jobject clazz,
        jint fd, jbyteArray key, jbyteArray value, jint flags) {
    ScopedByteArrayRO keyRO(env, key);
    ScopedByteArrayRO valueRO(env, value);

    int ret = bpf::writeToMapEntry(static_cast<int>(fd), keyRO.get(), valueRO.get(),
            static_cast<int>(flags));

    if (ret) jniThrowErrnoException(env, "writeToMapEntry", errno);
}

static jboolean throwIfNotEnoent(JNIEnv *env, const char* functionName, int ret, int err) {
    if (ret == 0) return true;

    if (err != ENOENT) jniThrowErrnoException(env, functionName, err);
    return false;
}

static jboolean com_android_networkstack_tethering_BpfMap_deleteMapEntry(JNIEnv *env, jobject clazz,
        jint fd, jbyteArray key) {
    ScopedByteArrayRO keyRO(env, key);

    // On success, zero is returned.  If the element is not found, -1 is returned and errno is set
    // to ENOENT.
    int ret = bpf::deleteMapEntry(static_cast<int>(fd), keyRO.get());

    return throwIfNotEnoent(env, "deleteMapEntry", ret, errno);
}

static jboolean com_android_networkstack_tethering_BpfMap_getNextMapKey(JNIEnv *env, jobject clazz,
        jint fd, jbyteArray key, jbyteArray nextKey) {
    // If key is found, the operation returns zero and sets the next key pointer to the key of the
    // next element.  If key is not found, the operation returns zero and sets the next key pointer
    // to the key of the first element.  If key is the last element, -1 is returned and errno is
    // set to ENOENT.  Other possible errno values are ENOMEM, EFAULT, EPERM, and EINVAL.
    ScopedByteArrayRW nextKeyRW(env, nextKey);
    int ret;
    if (key == nullptr) {
        // Called by getFirstKey. Find the first key in the map.
        ret = bpf::getNextMapKey(static_cast<int>(fd), nullptr, nextKeyRW.get());
    } else {
        ScopedByteArrayRO keyRO(env, key);
        ret = bpf::getNextMapKey(static_cast<int>(fd), keyRO.get(), nextKeyRW.get());
    }

    return throwIfNotEnoent(env, "getNextMapKey", ret, errno);
}

static jboolean com_android_networkstack_tethering_BpfMap_findMapEntry(JNIEnv *env, jobject clazz,
        jint fd, jbyteArray key, jbyteArray value) {
    ScopedByteArrayRO keyRO(env, key);
    ScopedByteArrayRW valueRW(env, value);

    // If an element is found, the operation returns zero and stores the element's value into
    // "value".  If no element is found, the operation returns -1 and sets errno to ENOENT.
    int ret = bpf::findMapEntry(static_cast<int>(fd), keyRO.get(), valueRW.get());

    return throwIfNotEnoent(env, "findMapEntry", ret, errno);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "closeMap", "(I)I",
        (void*) com_android_networkstack_tethering_BpfMap_closeMap },
    { "bpfFdGet", "(Ljava/lang/String;I)I",
        (void*) com_android_networkstack_tethering_BpfMap_bpfFdGet },
    { "writeToMapEntry", "(I[B[BI)V",
        (void*) com_android_networkstack_tethering_BpfMap_writeToMapEntry },
    { "deleteMapEntry", "(I[B)Z",
        (void*) com_android_networkstack_tethering_BpfMap_deleteMapEntry },
    { "getNextMapKey", "(I[B[B)Z",
        (void*) com_android_networkstack_tethering_BpfMap_getNextMapKey },
    { "findMapEntry", "(I[B[B)Z",
        (void*) com_android_networkstack_tethering_BpfMap_findMapEntry },

};

int register_com_android_networkstack_tethering_BpfMap(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "com/android/networkstack/tethering/BpfMap",
            gMethods, NELEM(gMethods));
}

}; // namespace android
