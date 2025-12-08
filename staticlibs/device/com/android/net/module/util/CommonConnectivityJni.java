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

package com.android.net.module.util;

import android.annotation.RequiresApi;
import android.os.Build;
import android.os.IBinder;
import android.system.ErrnoException;

/**
 * Contains JNI functions for use in
 *    ConnectivityService module
 *      - libandroid_net_connectivity_com_android_net_module_util_jni_CommonConnectivityJni
 *    Tethering apex
 *      - libcom_android_networkstack_tethering_util_jni_CommonConnectivityJni
 *    NetworkStack module
 *      - libcom_android_networkstack_com_android_net_module_util_jni_CommonConnectivityJni
 */
public class CommonConnectivityJni {
    static {
        final String libName =
                JniUtil.getJniLibraryName(CommonConnectivityJni.class);
        System.loadLibrary(libName);
    }

    /**
     * Create a timerfd.
     *
     * @throws ErrnoException if the timerfd creation is failed.
     */
    public static native int createTimerFd() throws ErrnoException;

    /**
     * Set given time to the timerfd.
     *
     * @param timeMs target time
     * @throws ErrnoException if setting expiration time is failed.
     */
    public static native void setTimerFdTime(int fd, long timeMs) throws ErrnoException;

    /**
     * Returns the specified service from the service manager.
     *
     * If the service is not running, service manager will attempt to start it, and this function
     * will wait for it to be ready.
     *
     * @return {@code null} only if there are permission problems or fatal errors
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public static native IBinder waitForService(String serviceName);
}
