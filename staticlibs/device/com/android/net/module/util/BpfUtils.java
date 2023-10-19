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
package com.android.net.module.util;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * The classes and the methods for BPF utilization.
 *
 * {@hide}
 */
public class BpfUtils {
    static {
        System.loadLibrary(JniUtil.getJniLibraryName(BpfUtils.class.getPackage()));
    }

    // Defined in include/uapi/linux/bpf.h. Only adding the CGROUPS currently being used for now.
    public static final int BPF_CGROUP_INET_INGRESS = 0;
    public static final int BPF_CGROUP_INET_EGRESS = 1;
    public static final int BPF_CGROUP_INET_SOCK_CREATE = 2;
    public static final int BPF_CGROUP_INET4_BIND = 8;
    public static final int BPF_CGROUP_INET6_BIND = 9;

    // Note: This is only guaranteed to be accurate on U+ devices. It is likely to be accurate
    // on T+ devices as well, but this is not guaranteed.
    public static final String CGROUP_PATH = "/sys/fs/cgroup/";

    /**
     * Attach BPF program to CGROUP
     */
    public static void attachProgram(int type, @NonNull String programPath,
            @NonNull String cgroupPath, int flags) throws IOException {
        native_attachProgramToCgroup(type, programPath, cgroupPath, flags);
    }

    /**
     * Detach BPF program from CGROUP
     */
    public static void detachProgram(int type, @NonNull String cgroupPath)
            throws IOException {
        native_detachProgramFromCgroup(type, cgroupPath);
    }

    /**
     * Get BPF program Id from CGROUP.
     *
     * Note: This requires a 4.19 kernel which is only guaranteed on V+.
     *
     * @param attachType Bpf attach type. See bpf_attach_type in include/uapi/linux/bpf.h.
     * @param cgroupPath Path of cgroup.
     * @return Positive integer for a Program Id. 0 if no program is attached.
     * @throws IOException if failed to open the cgroup directory or query bpf program.
     */
    public static int getProgramId(int attachType, @NonNull String cgroupPath) throws IOException {
        return native_getProgramIdFromCgroup(attachType, cgroupPath);
    }

    /**
     * Detach single BPF program from CGROUP
     */
    public static void detachSingleProgram(int type, @NonNull String programPath,
            @NonNull String cgroupPath) throws IOException {
        native_detachSingleProgramFromCgroup(type, programPath, cgroupPath);
    }

    private static native boolean native_attachProgramToCgroup(int type, String programPath,
            String cgroupPath, int flags) throws IOException;
    private static native boolean native_detachProgramFromCgroup(int type, String cgroupPath)
            throws IOException;
    private static native boolean native_detachSingleProgramFromCgroup(int type,
            String programPath, String cgroupPath) throws IOException;
    private static native int native_getProgramIdFromCgroup(int type, String cgroupPath)
            throws IOException;
}
