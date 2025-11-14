/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.net.ct;

import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.LOG_LIST_INVALID;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.SUCCESS;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.UNKNOWN_STATE;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.VERSION_ALREADY_EXISTS;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Tests for the {@link CompatibilityVersion}. */
@RunWith(JUnit4.class)
public class CompatibilityVersionTest {

    private static final String TEST_VERSION = "v123";
    private static final long LOG_LIST_TIMESTAMP = 123456789L;
    private static final String SIGNATURE = "fake_signature";

    private final File mTestDir =
            InstrumentationRegistry.getInstrumentation().getContext().getFilesDir();

    private CompatibilityVersion mCompatVersion;

    @Before
    public void setUp() {
        CompatibilityVersion.setRootDirectoryForTesting(mTestDir);
        mCompatVersion =
                new CompatibilityVersion(
                        TEST_VERSION, Config.URL_SIGNATURE_V1, Config.URL_LOG_LIST_V1);
    }

    @After
    public void tearDown() {
        mCompatVersion.delete();
    }

    @Test
    public void testCompatibilityVersion_versionDirectory_setupSuccessful() {
        File versionDir = mCompatVersion.getVersionDir();

        assertThat(versionDir.exists()).isFalse();
        assertThat(versionDir.getAbsolutePath()).startsWith(mTestDir.getAbsolutePath());
        assertThat(versionDir.getAbsolutePath()).endsWith(TEST_VERSION);
    }

    @Test
    public void testCompatibilityVersion_symlink_setupSuccessful() {
        File dirSymlink = mCompatVersion.getLogsDirSymlink();

        assertThat(dirSymlink.exists()).isFalse();
        assertThat(dirSymlink.getAbsolutePath())
                .startsWith(mCompatVersion.getVersionDir().getAbsolutePath());
    }

    @Test
    public void testCompatibilityVersion_logsFile_setupSuccessful() {
        File logsFile = mCompatVersion.getLogsFile();

        assertThat(logsFile.exists()).isFalse();
        assertThat(logsFile.getAbsolutePath())
                .startsWith(mCompatVersion.getLogsDirSymlink().getAbsolutePath());
    }

    @Test
    public void testCompatibilityVersion_installSuccessful_keepsStatusDetails() throws Exception {
        String version = "i_am_version";
        JSONObject logList = makeLogList(version, "i_am_content");

        try (InputStream inputStream = asStream(logList)) {
            assertThat(
                            mCompatVersion.install(
                                    inputStream,
                                    LogListUpdateStatus.builder()
                                            .setSignature(SIGNATURE)
                                            .setState(UNKNOWN_STATE)))
                    .isEqualTo(
                            LogListUpdateStatus.builder()
                                    .setSignature(SIGNATURE)
                                    .setLogListTimestamp(LOG_LIST_TIMESTAMP)
                                    // Ensure the state is correctly overridden to SUCCESS
                                    .setState(SUCCESS)
                                    .build());
        }
    }

    @Test
    public void testCompatibilityVersion_installSuccessful() throws Exception {
        String version = "i_am_version";
        JSONObject logList = makeLogList(version, "i_am_content");

        try (InputStream inputStream = asStream(logList)) {
            assertThat(mCompatVersion.install(inputStream, LogListUpdateStatus.builder()))
                    .isEqualTo(getSuccessfulUpdateStatus(LOG_LIST_TIMESTAMP));
        }

        File logListFile = mCompatVersion.getLogsFile();
        assertThat(logListFile.exists()).isTrue();
        assertThat(logListFile.getCanonicalPath())
                .isEqualTo(
                        // <path-to-test-files>/v123/logs-i_am_version/log_list.json
                        new File(
                                        new File(
                                                mCompatVersion.getVersionDir(),
                                                CompatibilityVersion.LOGS_DIR_PREFIX + version),
                                        CompatibilityVersion.LOGS_LIST_FILE_NAME)
                                .getCanonicalPath());
        assertThat(logListFile.getAbsolutePath())
                .isEqualTo(
                        // <path-to-test-files>/v123/current/log_list.json
                        new File(
                                        new File(
                                                mCompatVersion.getVersionDir(),
                                                CompatibilityVersion.CURRENT_LOGS_DIR_SYMLINK_NAME),
                                        CompatibilityVersion.LOGS_LIST_FILE_NAME)
                                .getAbsolutePath());
    }

    @Test
    public void testCompatibilityVersion_deleteSuccessfully() throws Exception {
        try (InputStream inputStream = asStream(makeLogList(/* version= */ "123", "any_content"))) {
            assertThat(mCompatVersion.install(inputStream, LogListUpdateStatus.builder()))
                    .isEqualTo(getSuccessfulUpdateStatus(LOG_LIST_TIMESTAMP));
        }

        mCompatVersion.delete();

        assertThat(mCompatVersion.getLogsFile().exists()).isFalse();
    }

    @Test
    public void testCompatibilityVersion_invalidLogList() throws Exception {
        try (InputStream inputStream = new ByteArrayInputStream(("not_a_valid_list".getBytes()))) {
            assertThat(mCompatVersion.install(inputStream, LogListUpdateStatus.builder()))
                    .isEqualTo(LogListUpdateStatus.builder().setState(LOG_LIST_INVALID).build());
        }

        assertThat(mCompatVersion.getLogsFile().exists()).isFalse();
    }

    @Test
    public void testCompatibilityVersion_incompleteVersionExists_replacesOldVersion()
            throws Exception {
        String existingVersion = "666";
        File existingLogDir =
                new File(
                        mCompatVersion.getVersionDir(),
                        CompatibilityVersion.LOGS_DIR_PREFIX + existingVersion);
        assertThat(existingLogDir.mkdirs()).isTrue();
        File logsListFile = new File(existingLogDir, CompatibilityVersion.LOGS_LIST_FILE_NAME);
        assertThat(logsListFile.createNewFile()).isTrue();

        JSONObject newLogList = makeLogList(existingVersion, "i_am_the_real_content");
        try (InputStream inputStream = asStream(newLogList)) {
            assertThat(mCompatVersion.install(inputStream, LogListUpdateStatus.builder()))
                    .isEqualTo(getSuccessfulUpdateStatus(LOG_LIST_TIMESTAMP));
        }

        assertThat(readAsString(logsListFile)).isEqualTo(newLogList.toString());
    }

    @Test
    public void testCompatibilityVersion_versionAlreadyExists_earlierTimestamp_installFails()
            throws Exception {
        String existingVersion = "666";
        long existingTimestamp = 123456;
        JSONObject existingLogList =
                makeLogList(existingVersion, "i_was_installed_successfully", existingTimestamp);
        try (InputStream inputStream = asStream(existingLogList)) {
            assertThat(mCompatVersion.install(inputStream, LogListUpdateStatus.builder()))
                    .isEqualTo(getSuccessfulUpdateStatus(existingTimestamp));
        }

        try (InputStream inputStream =
                asStream(
                        makeLogList(
                                existingVersion,
                                "i_am_same_version_but_older",
                                existingTimestamp - 1))) {
            assertThat(mCompatVersion.install(inputStream, LogListUpdateStatus.builder()))
                    .isEqualTo(
                            LogListUpdateStatus.builder()
                                    .setState(VERSION_ALREADY_EXISTS)
                                    .setLogListTimestamp(existingTimestamp - 1)
                                    .build());
        }

        assertThat(readAsString(mCompatVersion.getLogsFile()))
                .isEqualTo(existingLogList.toString());
    }

    @Test
    public void testCompatibilityVersion_versionAlreadyExists_laterTimestamp_installSucceeds()
            throws Exception {
        String existingVersion = "666";
        long existingTimestamp = 123456L;
        JSONObject existingLogList =
                makeLogList(existingVersion, "i_was_installed_successfully", existingTimestamp);
        try (InputStream inputStream = asStream(existingLogList)) {
            assertThat(mCompatVersion.install(inputStream, LogListUpdateStatus.builder()))
                    .isEqualTo(getSuccessfulUpdateStatus(existingTimestamp));
        }

        JSONObject newLogList =
                makeLogList(existingVersion, "i_am_same_version_but_newer", existingTimestamp + 1);
        try (InputStream inputStream = asStream(newLogList)) {
            assertThat(mCompatVersion.install(inputStream, LogListUpdateStatus.builder()))
                    .isEqualTo(getSuccessfulUpdateStatus(existingTimestamp + 1));
        }

        assertThat(readAsString(mCompatVersion.getLogsFile())).isEqualTo(newLogList.toString());
    }

    private static InputStream asStream(JSONObject logList) throws IOException {
        return new ByteArrayInputStream(logList.toString().getBytes());
    }

    private static JSONObject makeLogList(String version, String content) throws JSONException {
        return makeLogList(version, content, LOG_LIST_TIMESTAMP);
    }

    private static JSONObject makeLogList(String version, String content, long timestamp)
            throws JSONException {
        return new JSONObject()
                .put("version", version)
                .put("log_list_timestamp", timestamp)
                .put("content", content);
    }

    private static LogListUpdateStatus getSuccessfulUpdateStatus(long timestamp) {
        return LogListUpdateStatus.builder()
                .setState(SUCCESS)
                .setLogListTimestamp(timestamp)
                .build();
    }

    private static String readAsString(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(in.readAllBytes());
        }
    }
}
