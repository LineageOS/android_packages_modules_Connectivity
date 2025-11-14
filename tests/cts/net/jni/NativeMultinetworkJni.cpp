/*
 * Copyright (C) 2019 The Android Open Source Project
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


#define LOG_TAG "MultinetworkApiTest"

#include <arpa/inet.h>
#include <arpa/nameser.h>
#include <errno.h>
#include <inttypes.h>
#include <jni.h>
#include <netdb.h>
#include <poll.h> /* poll */
#include <resolv.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>

#include <string>

#include <android-base/scopeguard.h>
#include <android-base/unique_fd.h>
#include <android/log.h>
#include <android/multinetwork.h>
#include <nativehelper/JNIHelp.h>

using android::base::make_scope_guard;
using android::base::unique_fd;

#define LOGD(fmt, ...) \
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, fmt, ##__VA_ARGS__)

// Since the tests in this file commonly pass expression statements as parameters to these macros,
// get the returned value of the statements to avoid statement double-called.
// By checking ExceptionCheck(), these macros don't throw another exception if an exception has
// been thrown, because ART's JNI disallows to throw another exception while an exception is
// pending (See CheckThread in check_jni.cc).
#define EXPECT_GE(env, actual_stmt, expected_stmt, msg)              \
    do {                                                             \
        const auto expected = (expected_stmt);                       \
        const auto actual = (actual_stmt);                           \
        if (actual < expected && !env->ExceptionCheck()) {           \
            jniThrowExceptionFmt(env, "java/lang/AssertionError",    \
                    "%s:%d: %s EXPECT_GE: expected %d, got %d",      \
                    __FILE__, __LINE__, msg, expected, actual);      \
        }                                                            \
    } while (0)

#define EXPECT_GT(env, actual_stmt, expected_stmt, msg)              \
    do {                                                             \
        const auto expected = (expected_stmt);                       \
        const auto actual = (actual_stmt);                           \
        if (actual <= expected && !env->ExceptionCheck()) {          \
            jniThrowExceptionFmt(env, "java/lang/AssertionError",    \
                    "%s:%d: %s EXPECT_GT: expected %d, got %d",      \
                    __FILE__, __LINE__, msg, expected, actual);      \
        }                                                            \
    } while (0)

#define EXPECT_EQ(env, expected_stmt, actual_stmt, msg)              \
    do {                                                             \
        const auto expected = (expected_stmt);                       \
        const auto actual = (actual_stmt);                           \
        if (actual != expected && !env->ExceptionCheck()) {          \
            jniThrowExceptionFmt(env, "java/lang/AssertionError",    \
                    "%s:%d: %s EXPECT_EQ: expected %d, got %d",      \
                    __FILE__, __LINE__, msg, expected, actual);      \
        }                                                            \
    } while (0)

static const int MAXPACKET = 8 * 1024;
static const int TIMEOUT_MS = 15000;
static const char kHostname[] = "connectivitycheck.android.com";
static const char kNxDomainName[] = "test1-nx.metric.gstatic.com";
static const char kGoogleName[] = "www.google.com";

int makeQuery(const char* name, int qtype, uint8_t* buf, size_t buflen) {
    return res_mkquery(ns_o_query, name, ns_c_in, qtype, NULL, 0, NULL, buf, buflen);
}

int getAsyncResponse(JNIEnv* env, int fd, int timeoutMs, int* rcode, uint8_t* buf, size_t bufLen) {
    struct pollfd wait_fd = { .fd = fd, .events = POLLIN };

    poll(&wait_fd, 1, timeoutMs);
    if (wait_fd.revents & POLLIN) {
        int n = android_res_nresult(fd, rcode, buf, bufLen);
        // Verify that android_res_nresult() closed the fd
        char dummy;
        EXPECT_EQ(env, -1, read(fd, &dummy, sizeof(dummy)), "res_nresult check for closing fd");
        EXPECT_EQ(env, EBADF, errno, "res_nresult check for errno");
        return n;
    }

    return -ETIMEDOUT;
}

int extractIpAddressAnswers(uint8_t* buf, size_t bufLen, int family) {
    ns_msg handle;
    if (ns_initparse((const uint8_t*) buf, bufLen, &handle) < 0) {
        return -errno;
    }
    const int ancount = ns_msg_count(handle, ns_s_an);
    // Answer count = 0 is valid(e.g. response of query with root)
    if (!ancount) {
        return 0;
    }
    ns_rr rr;
    bool hasValidAns = false;
    for (int i = 0; i < ancount; i++) {
        if (ns_parserr(&handle, ns_s_an, i, &rr) < 0) {
            // If there is no valid answer, test will fail.
            continue;
        }

        const int rtype = ns_rr_type(rr);
        if (family == AF_INET) {
            // If there is no expected address type, test will fail.
            if (rtype != ns_t_a) continue;
        } else if (family == AF_INET6) {
            // If there is no expected address type, test will fail.
            if (rtype != ns_t_aaaa) continue;
        } else {
            return -EAFNOSUPPORT;
        }

        const uint8_t* rdata = ns_rr_rdata(rr);
        char buffer[INET6_ADDRSTRLEN];
        if (inet_ntop(family, (const char*) rdata, buffer, sizeof(buffer)) == NULL) {
            return -errno;
        }
        hasValidAns = true;
    }
    return hasValidAns ? 0 : -EBADMSG;
}

int expectAnswersValid(JNIEnv* env, int fd, int family, int expectedRcode) {
    int rcode = -1;
    uint8_t buf[MAXPACKET] = {};
    int res = getAsyncResponse(env, fd, TIMEOUT_MS, &rcode, buf, MAXPACKET);
    if (res < 0) {
        return res;
    }

    EXPECT_EQ(env, expectedRcode, rcode, "rcode is not expected");

    if (expectedRcode == ns_r_noerror && res > 0) {
        return extractIpAddressAnswers(buf, res, family);
    }
    return 0;
}

int expectAnswersNotValid(JNIEnv* env, int fd, int expectedErrno) {
    int rcode = -1;
    uint8_t buf[MAXPACKET] = {};
    int res = getAsyncResponse(env, fd, TIMEOUT_MS, &rcode, buf, MAXPACKET);
    if (res != expectedErrno) {
        LOGD("res:%d, expectedErrno = %d", res, expectedErrno);
        return (res > 0) ? -EREMOTEIO : res;
    }
    return 0;
}

extern "C"
JNIEXPORT void Java_android_net_cts_MultinetworkApiTest_runResNqueryCheck(
        JNIEnv* env, jclass, jlong nethandle) {
    net_handle_t handle = (net_handle_t) nethandle;

    // V4
    int fd = android_res_nquery(handle, kHostname, ns_c_in, ns_t_a, 0);
    EXPECT_GE(env, fd, 0, "v4 res_nquery");
    EXPECT_EQ(env, 0, expectAnswersValid(env, fd, AF_INET, ns_r_noerror),
            "v4 res_nquery check answers");

    // V6
    fd = android_res_nquery(handle, kHostname, ns_c_in, ns_t_aaaa, 0);
    EXPECT_GE(env, fd, 0, "v6 res_nquery");
    EXPECT_EQ(env, 0, expectAnswersValid(env, fd, AF_INET6, ns_r_noerror),
            "v6 res_nquery check answers");
}

extern "C"
JNIEXPORT void Java_android_net_cts_MultinetworkApiTest_runResNsendCheck(
        JNIEnv* env, jclass, jlong nethandle) {
    net_handle_t handle = (net_handle_t) nethandle;
    // V4
    uint8_t buf1[MAXPACKET] = {};

    int len1 = makeQuery(kGoogleName, ns_t_a, buf1, sizeof(buf1));
    EXPECT_GT(env, len1, 0, "v4 res_mkquery 1st");

    uint8_t buf2[MAXPACKET] = {};
    int len2 = makeQuery(kHostname, ns_t_a, buf2, sizeof(buf2));
    EXPECT_GT(env, len2, 0, "v4 res_mkquery 2nd");

    int fd1 = android_res_nsend(handle, buf1, len1, 0);
    EXPECT_GE(env, fd1, 0, "v4 res_nsend 1st");
    int fd2 = android_res_nsend(handle, buf2, len2, 0);
    EXPECT_GE(env, fd2, 0, "v4 res_nsend 2nd");

    EXPECT_EQ(env, 0, expectAnswersValid(env, fd2, AF_INET, ns_r_noerror),
            "v4 res_nsend 2nd check answers");
    EXPECT_EQ(env, 0, expectAnswersValid(env, fd1, AF_INET, ns_r_noerror),
            "v4 res_nsend 1st check answers");

    // V6
    memset(buf1, 0, sizeof(buf1));
    memset(buf2, 0, sizeof(buf2));
    len1 = makeQuery(kGoogleName, ns_t_aaaa, buf1, sizeof(buf1));
    EXPECT_GT(env, len1, 0, "v6 res_mkquery 1st");
    len2 = makeQuery(kHostname, ns_t_aaaa, buf2, sizeof(buf2));
    EXPECT_GT(env, len2, 0, "v6 res_mkquery 2nd");

    fd1 = android_res_nsend(handle, buf1, len1, 0);
    EXPECT_GE(env, fd1, 0, "v6 res_nsend 1st");
    fd2 = android_res_nsend(handle, buf2, len2, 0);
    EXPECT_GE(env, fd2, 0, "v6 res_nsend 2nd");

    EXPECT_EQ(env, 0, expectAnswersValid(env, fd2, AF_INET6, ns_r_noerror),
            "v6 res_nsend 2nd check answers");
    EXPECT_EQ(env, 0, expectAnswersValid(env, fd1, AF_INET6, ns_r_noerror),
            "v6 res_nsend 1st check answers");
}

extern "C"
JNIEXPORT void Java_android_net_cts_MultinetworkApiTest_runResNnxDomainCheck(
        JNIEnv* env, jclass, jlong nethandle) {
    net_handle_t handle = (net_handle_t) nethandle;

    // res_nquery V4 NXDOMAIN
    int fd = android_res_nquery(handle, kNxDomainName, ns_c_in, ns_t_a, 0);
    EXPECT_GE(env, fd, 0, "v4 res_nquery NXDOMAIN");
    EXPECT_EQ(env, 0, expectAnswersValid(env, fd, AF_INET, ns_r_nxdomain),
            "v4 res_nquery NXDOMAIN check answers");

    // res_nquery V6 NXDOMAIN
    fd = android_res_nquery(handle, kNxDomainName, ns_c_in, ns_t_aaaa, 0);
    EXPECT_GE(env, fd, 0, "v6 res_nquery NXDOMAIN");
    EXPECT_EQ(env, 0, expectAnswersValid(env, fd, AF_INET6, ns_r_nxdomain),
            "v6 res_nquery NXDOMAIN check answers");

    uint8_t buf[MAXPACKET] = {};
    // res_nsend V4 NXDOMAIN
    int len = makeQuery(kNxDomainName, ns_t_a, buf, sizeof(buf));
    EXPECT_GT(env, len, 0, "v4 res_mkquery NXDOMAIN");
    fd = android_res_nsend(handle, buf, len, 0);
    EXPECT_GE(env, fd, 0, "v4 res_nsend NXDOMAIN");
    EXPECT_EQ(env, 0, expectAnswersValid(env, fd, AF_INET, ns_r_nxdomain),
            "v4 res_nsend NXDOMAIN check answers");

    // res_nsend V6 NXDOMAIN
    memset(buf, 0, sizeof(buf));
    len = makeQuery(kNxDomainName, ns_t_aaaa, buf, sizeof(buf));
    EXPECT_GT(env, len, 0, "v6 res_mkquery NXDOMAIN");
    fd = android_res_nsend(handle, buf, len, 0);
    EXPECT_GE(env, fd, 0, "v6 res_nsend NXDOMAIN");
    EXPECT_EQ(env, 0, expectAnswersValid(env, fd, AF_INET6, ns_r_nxdomain),
            "v6 res_nsend NXDOMAIN check answers");
}


extern "C"
JNIEXPORT void Java_android_net_cts_MultinetworkApiTest_runResNcancelCheck(
        JNIEnv* env, jclass, jlong nethandle) {
    net_handle_t handle = (net_handle_t) nethandle;

    int fd = android_res_nquery(handle, kGoogleName, ns_c_in, ns_t_a, 0);
    errno = 0;
    android_res_cancel(fd);
    int err = errno;
    EXPECT_EQ(env, 0, err, "res_cancel");
    // DO NOT call cancel or result with the same fd more than once,
    // otherwise it will hit fdsan double-close fd.
}

extern "C"
JNIEXPORT void Java_android_net_cts_MultinetworkApiTest_runResNapiMalformedCheck(
        JNIEnv* env, jclass, jlong nethandle) {
    net_handle_t handle = (net_handle_t) nethandle;

    // It is the equivalent of "dig . a", Query with an empty name.
    int fd = android_res_nquery(handle, "", ns_c_in, ns_t_a, 0);
    EXPECT_GE(env, fd, 0, "res_nquery root");
    EXPECT_EQ(env, 0, expectAnswersValid(env, fd, AF_INET, ns_r_noerror),
            "res_nquery root check answers");

    // Label limit 63
    std::string exceedingLabelQuery = "www." + std::string(70, 'g') + ".com";
    // Name limit 255
    std::string exceedingDomainQuery = "www." + std::string(255, 'g') + ".com";

    fd = android_res_nquery(handle, exceedingLabelQuery.c_str(), ns_c_in, ns_t_a, 0);
    EXPECT_EQ(env, -EMSGSIZE, fd, "res_nquery exceedingLabelQuery");
    fd = android_res_nquery(handle, exceedingDomainQuery.c_str(), ns_c_in, ns_t_aaaa, 0);
    EXPECT_EQ(env, -EMSGSIZE, fd, "res_nquery exceedingDomainQuery");

    uint8_t buf[10] = {};
    // empty BLOB
    fd = android_res_nsend(handle, buf, 10, 0);
    EXPECT_GE(env, fd, 0, "res_nsend empty BLOB");
    EXPECT_EQ(env, 0, expectAnswersNotValid(env, fd, -EINVAL),
            "res_nsend empty BLOB check answers");

    uint8_t largeBuf[2 * MAXPACKET] = {};
    // A buffer larger than 8KB
    fd = android_res_nsend(handle, largeBuf, sizeof(largeBuf), 0);
    EXPECT_EQ(env, -EMSGSIZE, fd, "res_nsend buffer larger than 8KB");

    // 5000 bytes filled with 0. This returns EMSGSIZE because FrameworkListener limits the size of
    // commands to 4096 bytes.
    fd = android_res_nsend(handle, largeBuf, 5000, 0);
    EXPECT_EQ(env, -EMSGSIZE, fd, "res_nsend 5000 bytes filled with 0");

    // 500 bytes filled with 0
    fd = android_res_nsend(handle, largeBuf, 500, 0);
    EXPECT_GE(env, fd, 0, "res_nsend 500 bytes filled with 0");
    EXPECT_EQ(env, 0, expectAnswersNotValid(env, fd, -EINVAL),
            "res_nsend 500 bytes filled with 0 check answers");

    // 5000 bytes filled with 0xFF
    uint8_t ffBuf[5001] = {};
    memset(ffBuf, 0xFF, sizeof(ffBuf));
    ffBuf[5000] = '\0';
    fd = android_res_nsend(handle, ffBuf, sizeof(ffBuf), 0);
    EXPECT_EQ(env, -EMSGSIZE, fd, "res_nsend 5000 bytes filled with 0xFF");

    // 500 bytes filled with 0xFF
    ffBuf[500] = '\0';
    fd = android_res_nsend(handle, ffBuf, 501, 0);
    EXPECT_GE(env, fd, 0, "res_nsend 500 bytes filled with 0xFF");
    EXPECT_EQ(env, 0, expectAnswersNotValid(env, fd, -EINVAL),
            "res_nsend 500 bytes filled with 0xFF check answers");
}

extern "C"
JNIEXPORT jint Java_android_net_cts_MultinetworkApiTest_runGetaddrinfoCheck(
        JNIEnv*, jclass, jlong nethandle) {
    net_handle_t handle = (net_handle_t) nethandle;
    struct addrinfo *res = NULL;

    errno = 0;
    int rval = android_getaddrinfofornetwork(handle, kHostname, NULL, NULL, &res);
    const int saved_errno = errno;
    freeaddrinfo(res);

    LOGD("android_getaddrinfofornetwork(%" PRIu64 ", %s) returned rval=%d errno=%d",
          handle, kHostname, rval, saved_errno);
    return rval == 0 ? 0 : -saved_errno;
}

extern "C"
JNIEXPORT jint Java_android_net_cts_MultinetworkApiTest_runSetprocnetwork(
        JNIEnv*, jclass, jlong nethandle) {
    net_handle_t handle = (net_handle_t) nethandle;

    errno = 0;
    int rval = android_setprocnetwork(handle);
    const int saved_errno = errno;
    LOGD("android_setprocnetwork(%" PRIu64 ") returned rval=%d errno=%d",
          handle, rval, saved_errno);
    return rval == 0 ? 0 : -saved_errno;
}

extern "C"
JNIEXPORT jint Java_android_net_cts_MultinetworkApiTest_runSetsocknetwork(
        JNIEnv*, jclass, jlong nethandle) {
    net_handle_t handle = (net_handle_t) nethandle;

    errno = 0;
    int fd = socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP);
    if (fd < 0) {
        LOGD("socket() failed, errno=%d", errno);
        return -errno;
    }

    errno = 0;
    int rval = android_setsocknetwork(handle, fd);
    const int saved_errno = errno;
    LOGD("android_setprocnetwork(%" PRIu64 ", %d) returned rval=%d errno=%d",
          handle, fd, rval, saved_errno);
    close(fd);
    return rval == 0 ? 0 : -saved_errno;
}

// Use sizeof("x") - 1 because we need a compile-time constant, and strlen("x")
// isn't guaranteed to fold to a constant.
static const int kSockaddrStrLen = INET6_ADDRSTRLEN + sizeof("[]:65535") - 1;

void sockaddr_ntop(const struct sockaddr *sa, socklen_t salen, char *dst, const size_t size) {
    char addrstr[INET6_ADDRSTRLEN];
    char portstr[sizeof("65535")];
    char buf[kSockaddrStrLen+1];

    int ret = getnameinfo(sa, salen,
                          addrstr, sizeof(addrstr),
                          portstr, sizeof(portstr),
                          NI_NUMERICHOST | NI_NUMERICSERV);
    if (ret == 0) {
        snprintf(buf, sizeof(buf),
                 (sa->sa_family == AF_INET6) ? "[%s]:%s" : "%s:%s",
                 addrstr, portstr);
    } else {
        sprintf(buf, "???");
    }

    strlcpy(dst, buf, size);
}

static jobject create_query_test_result(JNIEnv* env, uint16_t src_port, int attempts, int errnum) {
    jclass clazz = env->FindClass(
        "android/net/cts/MultinetworkApiTest$QueryTestResult");
    jmethodID ctor = env->GetMethodID(clazz, "<init>", "(III)V");

    return env->NewObject(clazz, ctor, src_port, attempts, errnum);
}

extern "C"
JNIEXPORT jobject Java_android_net_cts_MultinetworkApiTest_runDatagramCheck(
        JNIEnv* env, jclass, jlong nethandle, jint src_port) {
    const struct addrinfo kHints = {
        .ai_flags = AI_ADDRCONFIG,
        .ai_family = AF_UNSPEC,
        .ai_socktype = SOCK_DGRAM,
        .ai_protocol = IPPROTO_UDP,
    };
    struct addrinfo *res = NULL;
    net_handle_t handle = (net_handle_t) nethandle;

    static const char kPort[] = "443";
    int rval = android_getaddrinfofornetwork(handle, kHostname, kPort, &kHints, &res);
    auto res_guard = make_scope_guard([res] { freeaddrinfo(res); });
    if (rval != 0) {
        LOGD("android_getaddrinfofornetwork(%llu, %s) returned rval=%d errno=%d",
              handle, kHostname, rval, errno);
        return create_query_test_result(env, 0, 0, errno);
    }

    // Rely upon getaddrinfo sorting the best destination to the front.
    auto fd = unique_fd(socket(res->ai_family, res->ai_socktype, res->ai_protocol));
    if (!fd.ok()) {
        LOGD("socket(%d, %d, %d) failed, errno=%d",
              res->ai_family, res->ai_socktype, res->ai_protocol, errno);
        return create_query_test_result(env, 0, 0, errno);
    }

    rval = android_setsocknetwork(handle, fd.get());
    LOGD("android_setprocnetwork(%llu, %d) returned rval=%d errno=%d",
          handle, fd.get(), rval, errno);
    if (rval != 0) {
        return create_query_test_result(env, 0, 0, errno);
    }

    sockaddr_storage src_addr;
    socklen_t src_addrlen = sizeof(src_addr);
    if (src_port) {
        if (res->ai_family == AF_INET6) {
            *reinterpret_cast<sockaddr_in6*>(&src_addr) = (sockaddr_in6) {
                .sin6_family = AF_INET6,
                .sin6_port = htons(src_port),
                .sin6_addr = in6addr_any,
            };
        } else {
            *reinterpret_cast<sockaddr_in*>(&src_addr) = (sockaddr_in) {
                .sin_family = AF_INET,
                .sin_port = htons(src_port),
                .sin_addr = { .s_addr = INADDR_ANY },
            };
        }
        if (bind(fd.get(), (sockaddr *)&src_addr, src_addrlen) != 0) {
            LOGD("Error binding to port %d", src_port);
            return create_query_test_result(env, 0, 0, errno);
        }
    }

    char addrstr[kSockaddrStrLen+1];
    sockaddr_ntop(res->ai_addr, res->ai_addrlen, addrstr, sizeof(addrstr));
    LOGD("Attempting connect() to %s ...", addrstr);

    rval = connect(fd.get(), res->ai_addr, res->ai_addrlen);
    if (rval != 0) {
        return create_query_test_result(env, 0, 0, errno);
    }

    if (getsockname(fd.get(), (struct sockaddr *)&src_addr, &src_addrlen) != 0) {
        return create_query_test_result(env, 0, 0, errno);
    }
    sockaddr_ntop((const struct sockaddr *)&src_addr, sizeof(src_addr), addrstr, sizeof(addrstr));
    LOGD("... from %s", addrstr);

    uint16_t socket_src_port;
    if (res->ai_family == AF_INET6) {
        socket_src_port = ntohs(reinterpret_cast<sockaddr_in6*>(&src_addr)->sin6_port);
    } else if (src_addr.ss_family == AF_INET) {
        socket_src_port = ntohs(reinterpret_cast<sockaddr_in*>(&src_addr)->sin_port);
    } else {
        LOGD("Invalid source address family %d", src_addr.ss_family);
        return create_query_test_result(env, 0, 0, EAFNOSUPPORT);
    }

    // Don't let reads or writes block indefinitely.
    const struct timeval timeo = { 2, 0 };  // 2 seconds
    setsockopt(fd.get(), SOL_SOCKET, SO_RCVTIMEO, &timeo, sizeof(timeo));
    setsockopt(fd.get(), SOL_SOCKET, SO_SNDTIMEO, &timeo, sizeof(timeo));

    // For reference see:
    //     https://datatracker.ietf.org/doc/html/draft-ietf-quic-invariants
    uint8_t quic_packet[1200] = {
        0xc0,                    // long header
        0xaa, 0xda, 0xca, 0xca,  // reserved-space version number
        0x08,                    // destination connection ID length
        0, 0, 0, 0, 0, 0, 0, 0,  // 64bit connection ID
        0x00,                    // source connection ID length
    };

    arc4random_buf(quic_packet + 6, 8);  // random connection ID

    uint8_t response[1500];
    ssize_t sent, rcvd;
    static const int MAX_RETRIES = 5;
    int i, errnum = 0;

    for (i = 0; i < MAX_RETRIES; i++) {
        sent = send(fd.get(), quic_packet, sizeof(quic_packet), 0);
        if (sent < (ssize_t)sizeof(quic_packet)) {
            errnum = errno;
            LOGD("send(QUIC packet) returned sent=%zd, errno=%d", sent, errnum);
            return create_query_test_result(env, socket_src_port, i + 1, errnum);
        }

        rcvd = recv(fd.get(), response, sizeof(response), 0);
        if (rcvd > 0) {
            break;
        } else {
            errnum = errno;
            LOGD("[%d/%d] recv(QUIC response) returned rcvd=%zd, errno=%d",
                  i + 1, MAX_RETRIES, rcvd, errnum);
        }
    }
    if (rcvd < 15) {
        LOGD("QUIC UDP %s: sent=%zd but rcvd=%zd, errno=%d", kPort, sent, rcvd, errnum);
        if (rcvd <= 0) {
            LOGD("Does this network block UDP port %s?", kPort);
        }
        return create_query_test_result(env, socket_src_port, i + 1,
                rcvd <= 0 ? errnum : EPROTO);
    }

    int conn_id_cmp = memcmp(quic_packet + 6, response + 7, 8);
    if (conn_id_cmp != 0) {
        LOGD("sent and received connection IDs do not match");
        return create_query_test_result(env, socket_src_port, i + 1, EPROTO);
    }

    // TODO: Replace this quick 'n' dirty test with proper QUIC-capable code.
    return create_query_test_result(env, socket_src_port, i + 1, 0);
}
