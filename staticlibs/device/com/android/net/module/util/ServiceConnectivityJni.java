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

import android.annotation.NonNull;
import android.system.ErrnoException;

/**
 * Contains JNI functions for use in service-connectivity
 */
public class ServiceConnectivityJni {
    static {
        final String libName = JniUtil.getJniLibraryName(ServiceConnectivityJni.class.getPackage());
        if (libName.equals("android_net_connectivity_com_android_net_module_util_jni")) {
            // This library is part of service-connectivity.jar when in the system server,
            // so libservice-connectivity.so is the library to load.
            System.loadLibrary("service-connectivity");
        } else {
            System.loadLibrary(libName);
        }
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
     * Get the driver name for a given interface using the ethtool API.
     *
     * @param ifname name of the interface to query.
     * @return the name of the driver.
     * @throws ErrnoException if ioctl() returns -1.
     */
    public static native String getDriverNameForInterface(String ifname) throws ErrnoException;

    /** Create tun/tap interface */
    public static native int createTunTap(boolean isTun, boolean hasCarrier,
            boolean setIffMulticast, @NonNull String iface);

    /** Enable carrier on tun/tap interface */
    public static native void setTunTapCarrierEnabled(@NonNull String iface, int tunFd,
            boolean enabled);

    /** Bring up tun/tap interface */
    public static native void bringUpInterface(String iface);
}
