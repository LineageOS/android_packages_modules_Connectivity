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

import static com.google.common.io.Files.toByteArray;
import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/** Tests for the {@link CertificateTransparencyDownloader}. */
@RunWith(JUnit4.class)
public class CertificateTransparencyDownloaderTest {

    @Mock private DownloadManager mDownloadManager;
    @Mock private CertificateTransparencyLogger mLogger;
    private ArgumentCaptor<LogListUpdateStatus> mUpdateStatusCaptor =
            ArgumentCaptor.forClass(LogListUpdateStatus.class);

    private PrivateKey mPrivateKey;
    private PublicKey mPublicKey;
    private Context mContext;
    private SignatureVerifier mSignatureVerifier;
    private CompatibilityVersion mCompatVersion;
    private CertificateTransparencyDownloader mCertificateTransparencyDownloader;

    private long mNextDownloadId = 666;
    private static final long LOG_LIST_TIMESTAMP = 123456789L;

    @Before
    public void setUp() throws IOException, GeneralSecurityException {
        MockitoAnnotations.initMocks(this);
        KeyPairGenerator instance = KeyPairGenerator.getInstance("RSA");
        KeyPair keyPair = instance.generateKeyPair();
        mPrivateKey = keyPair.getPrivate();
        mPublicKey = keyPair.getPublic();

        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mSignatureVerifier = new SignatureVerifier(mContext);

        CompatibilityVersion.setRootDirectoryForTesting(mContext.getFilesDir());
        mCompatVersion =
                new CompatibilityVersion(
                        /* compatVersion= */ "v666",
                        Config.URL_SIGNATURE_V1,
                        Config.URL_LOG_LIST_V1);
        mCertificateTransparencyDownloader =
                new CertificateTransparencyDownloader(
                        mContext,
                        new DownloadHelper(mDownloadManager),
                        mSignatureVerifier,
                        mLogger,
                        Arrays.asList(mCompatVersion));

        prepareDownloadManager();
        mSignatureVerifier.addAllowedKey(mPublicKey);
    }

    @After
    public void tearDown() {
        mSignatureVerifier.resetPublicKey();
        mCompatVersion.delete();
    }

    @Test
    public void testDownloader_startPublicKeyDownload() {
        assertThat(mCertificateTransparencyDownloader.hasPublicKeyDownloadId()).isFalse();

        long downloadId = mCertificateTransparencyDownloader.startPublicKeyDownload();

        assertThat(mCertificateTransparencyDownloader.hasPublicKeyDownloadId()).isTrue();
        assertThat(mCertificateTransparencyDownloader.getPublicKeyDownloadId())
                .isEqualTo(downloadId);
    }

    @Test
    public void testDownloader_startMetadataDownload() {
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();

        mCertificateTransparencyDownloader.startMetadataDownload();

        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isTrue();
    }

    @Test
    public void testDownloader_startContentDownload() {
        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isFalse();

        mCertificateTransparencyDownloader.startContentDownload(mCompatVersion);

        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isTrue();
    }

    @Test
    public void testDownloader_publicKeyDownloadSuccess_updatePublicKey_startMetadataDownload()
            throws Exception {
        mCertificateTransparencyDownloader.startPublicKeyDownload();

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makePublicKeyDownloadCompleteIntent(writePublicKeyToFile(mPublicKey)));

        assertThat(mSignatureVerifier.getPublicKey()).hasValue(mPublicKey);
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isTrue();
    }

    @Test
    public void
            testDownloader_publicKeyDownloadSuccess_publicKeyNotAllowed_doNotStartMetadataDownload()
                    throws Exception {
        mCertificateTransparencyDownloader.startPublicKeyDownload();
        PublicKey notAllowed = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makePublicKeyDownloadCompleteIntent(writePublicKeyToFile(notAllowed)));

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
    }

    @Test
    public void testDownloader_publicKeyDownloadSuccess_publicKeyFileNotRead_logsFailure()
            throws Exception {
        mCertificateTransparencyDownloader.startPublicKeyDownload();

        File publicKeyFile = writePublicKeyToFile(mPublicKey);
        // Set the public key file to not be readable to simulate an IOException being thrown
        publicKeyFile.setReadable(false);
        mCertificateTransparencyDownloader.onReceive(
                mContext, makePublicKeyDownloadCompleteIntent(publicKeyFile));

        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(
                        LogListUpdateStatus.builder()
                                .setState(CTLogListUpdateState.UNABLE_TO_READ_FILE)
                                .build());
    }

    @Test
    public void
            testDownloader_publicKeyDownloadSuccess_publicKeyNotAllowed_logsFailure()
                    throws Exception {
        mCertificateTransparencyDownloader.startPublicKeyDownload();
        PublicKey notAllowed = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();

        mCertificateTransparencyDownloader.onReceive(
                mContext, makePublicKeyDownloadCompleteIntent(writePublicKeyToFile(notAllowed)));

        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(
                        LogListUpdateStatus.builder()
                                .setState(CTLogListUpdateState.PUBLIC_KEY_NOT_ALLOWED)
                                .build());
    }

    @Test
    public void
            testDownloader_publicKeyDownloadSuccess_publicKeyInvalidEncoding_logsFailure()
                    throws Exception {
        mCertificateTransparencyDownloader.startPublicKeyDownload();

        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makePublicKeyDownloadCompleteIntent(
                        writeToFile("i_am_not_a_base64_encoded_public_key".getBytes())));

        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(
                        LogListUpdateStatus.builder()
                                .setState(CTLogListUpdateState.PUBLIC_KEY_INVALID)
                                .build());
    }

    @Test
    public void
            testDownloader_publicKeyDownloadSuccess_updatePublicKeyFail_doNotStartMetadataDownload()
                    throws Exception {
        mCertificateTransparencyDownloader.startPublicKeyDownload();

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makePublicKeyDownloadCompleteIntent(
                        writeToFile("i_am_not_a_base64_encoded_public_key".getBytes())));

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
    }

    @Test
    public void testDownloader_publicKeyDownloadFail_doNotUpdatePublicKey() throws Exception {
        mCertificateTransparencyDownloader.startPublicKeyDownload();

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makePublicKeyDownloadFailedIntent(DownloadManager.ERROR_INSUFFICIENT_SPACE));
        mCertificateTransparencyDownloader.onReceive(
                mContext, makePublicKeyDownloadFailedIntent(DownloadManager.ERROR_HTTP_DATA_ERROR));

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
    }

    @Test
    public void testDownloader_publicKeyDownloadFail_logsDownloadFailure() throws Exception {
        mCertificateTransparencyDownloader.startPublicKeyDownload();

        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makePublicKeyDownloadFailedIntent(DownloadManager.ERROR_INSUFFICIENT_SPACE));

        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(
                        LogListUpdateStatus.builder()
                                .setDownloadStatus(
                                        Optional.of(DownloadManager.ERROR_INSUFFICIENT_SPACE))
                                .build());
    }

    @Test
    public void testDownloader_metadataDownloadSuccess_startContentDownload() {
        mCertificateTransparencyDownloader.startMetadataDownload();

        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makeMetadataDownloadCompleteIntent(mCompatVersion, new File("log_list.sig")));

        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isTrue();
    }

    @Test
    public void testDownloader_metadataDownloadFail_doNotStartContentDownload() {
        mCertificateTransparencyDownloader.startMetadataDownload();

        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makeMetadataDownloadFailedIntent(
                        mCompatVersion, DownloadManager.ERROR_INSUFFICIENT_SPACE));
        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makeMetadataDownloadFailedIntent(
                        mCompatVersion, DownloadManager.ERROR_HTTP_DATA_ERROR));

        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isFalse();
    }

    @Test
    public void testDownloader_metadataDownloadFail_logsFailure() throws Exception {
        mCertificateTransparencyDownloader.startMetadataDownload();

        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makeMetadataDownloadFailedIntent(
                        mCompatVersion, DownloadManager.ERROR_INSUFFICIENT_SPACE));

        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(
                        LogListUpdateStatus.builder()
                                .setDownloadStatus(
                                        Optional.of(DownloadManager.ERROR_INSUFFICIENT_SPACE))
                                .build());
    }

    @Test
    public void testDownloader_contentDownloadSuccess_installSuccess() throws Exception {
        String newVersion = "456";
        File logListFile = makeLogListFile(newVersion);
        File metadataFile = sign(logListFile);
        mSignatureVerifier.setPublicKey(mPublicKey);
        mCertificateTransparencyDownloader.startMetadataDownload();

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeMetadataDownloadCompleteIntent(mCompatVersion, metadataFile));
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeContentDownloadCompleteIntent(mCompatVersion, logListFile));

        assertInstallSuccessful(newVersion);
    }

    @Test
    public void testDownloader_contentDownloadFail_doNotInstall() throws Exception {
        mCertificateTransparencyDownloader.startContentDownload(mCompatVersion);

        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makeContentDownloadFailedIntent(
                        mCompatVersion, DownloadManager.ERROR_INSUFFICIENT_SPACE));
        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makeContentDownloadFailedIntent(
                        mCompatVersion, DownloadManager.ERROR_HTTP_DATA_ERROR));

        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_contentDownloadFail_logsFailure() throws Exception {
        mCertificateTransparencyDownloader.startContentDownload(mCompatVersion);

        mCertificateTransparencyDownloader.onReceive(
                mContext,
                makeContentDownloadFailedIntent(
                        mCompatVersion, DownloadManager.ERROR_INSUFFICIENT_SPACE));

        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(
                        LogListUpdateStatus.builder()
                                .setDownloadStatus(
                                        Optional.of(DownloadManager.ERROR_INSUFFICIENT_SPACE))
                                .build());
    }

    @Test
    public void testDownloader_contentDownloadSuccess_invalidLogList_installFails()
            throws Exception {
        File invalidLogListFile = writeToFile("not_a_json_log_list".getBytes());
        File metadataFile = sign(invalidLogListFile);
        mSignatureVerifier.setPublicKey(mPublicKey);
        mCertificateTransparencyDownloader.startMetadataDownload();

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeMetadataDownloadCompleteIntent(mCompatVersion, metadataFile));
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeContentDownloadCompleteIntent(mCompatVersion, invalidLogListFile));

        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_contentDownloadSuccess_noPublicKeyFound_logsSingleFailure()
            throws Exception {
        File logListFile = makeLogListFile("456");
        File metadataFile = sign(logListFile);
        mSignatureVerifier.setPublicKey(mPublicKey);
        mCertificateTransparencyDownloader.startMetadataDownload();

        // Set the public key to be missing
        mSignatureVerifier.resetPublicKey();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeMetadataDownloadCompleteIntent(mCompatVersion, metadataFile));
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeContentDownloadCompleteIntent(mCompatVersion, logListFile));

        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(mUpdateStatusCaptor.capture());
        assertThat(mUpdateStatusCaptor.getValue().state())
                .isEqualTo(CTLogListUpdateState.PUBLIC_KEY_NOT_FOUND);
    }

    @Test
    public void testDownloader_contentDownloadSuccess_signatureFileNotRead_logsSingleFailure()
            throws Exception {
        File logListFile = makeLogListFile("456");
        File metadataFile = sign(logListFile);
        mSignatureVerifier.setPublicKey(mPublicKey);
        mCertificateTransparencyDownloader.startMetadataDownload();

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeMetadataDownloadCompleteIntent(mCompatVersion, metadataFile));
        // Set the log list file to not be readable to simulate an IOException being thrown
        logListFile.setReadable(false);
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeContentDownloadCompleteIntent(mCompatVersion, logListFile));

        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(mUpdateStatusCaptor.capture());
        assertThat(mUpdateStatusCaptor.getValue().state())
                .isEqualTo(CTLogListUpdateState.UNABLE_TO_READ_FILE);
    }

    @Test
    public void testDownloader_contentDownloadSuccess_wrongSignatureAlgo_logsSingleFailure()
            throws Exception {
        // Arrange
        File logListFile = makeLogListFile("456");
        File metadataFile = sign(logListFile);

        // Set the key to be deliberately wrong by using diff algorithm
        PublicKey wrongAlgorithmKey =
                KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic();
        mSignatureVerifier.addAllowedKey(wrongAlgorithmKey);
        mSignatureVerifier.setPublicKey(wrongAlgorithmKey);

        // Act
        mCertificateTransparencyDownloader.startMetadataDownload();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeMetadataDownloadCompleteIntent(mCompatVersion, metadataFile));
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeContentDownloadCompleteIntent(mCompatVersion, logListFile));

        // Assert
        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(mUpdateStatusCaptor.capture());
        assertThat(mUpdateStatusCaptor.getValue().state())
                .isEqualTo(CTLogListUpdateState.SIGNATURE_INVALID);
    }

    @Test
    public void testDownloader_contentDownloadSuccess_signatureNotVerified_logsSingleFailure()
            throws Exception {
        // Arrange
        File logListFile = makeLogListFile("456");
        mSignatureVerifier.setPublicKey(mPublicKey);

        // Sign the list with a disallowed key pair
        KeyPairGenerator instance = KeyPairGenerator.getInstance("RSA");
        File metadataFile = sign(logListFile, instance.generateKeyPair().getPrivate());

        // Act
        mCertificateTransparencyDownloader.startMetadataDownload();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeMetadataDownloadCompleteIntent(mCompatVersion, metadataFile));
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeContentDownloadCompleteIntent(mCompatVersion, logListFile));

        // Assert
        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(mUpdateStatusCaptor.capture());
        LogListUpdateStatus statusValue = mUpdateStatusCaptor.getValue();
        assertThat(statusValue.state())
                .isEqualTo(CTLogListUpdateState.SIGNATURE_VERIFICATION_FAILED);
        assertThat(statusValue.signature()).isEqualTo(new String(toByteArray(metadataFile)));
    }

    @Test
    public void testDownloader_contentDownloadSuccess_installFail_logsFailure() throws Exception {
        File invalidLogListFile = writeToFile("not_a_json_log_list".getBytes());
        File metadataFile = sign(invalidLogListFile);
        mSignatureVerifier.setPublicKey(mPublicKey);

        mCertificateTransparencyDownloader.startMetadataDownload();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeMetadataDownloadCompleteIntent(mCompatVersion, metadataFile));
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeContentDownloadCompleteIntent(mCompatVersion, invalidLogListFile));

        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(mUpdateStatusCaptor.capture());
        LogListUpdateStatus statusValue = mUpdateStatusCaptor.getValue();
        assertThat(statusValue.state()).isEqualTo(CTLogListUpdateState.LOG_LIST_INVALID);
        assertThat(statusValue.signature()).isEqualTo(new String(toByteArray(metadataFile)));
    }

    @Test
    public void testDownloader_contentDownloadSuccess_verificationFail_doNotInstall()
            throws Exception {
        File logListFile = makeLogListFile("456");
        File metadataFile = File.createTempFile("log_list-wrong_metadata", "sig");
        mSignatureVerifier.setPublicKey(mPublicKey);

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.startMetadataDownload();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeMetadataDownloadCompleteIntent(mCompatVersion, metadataFile));
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeContentDownloadCompleteIntent(mCompatVersion, logListFile));

        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_contentDownloadSuccess_missingVerificationPublicKey_doNotInstall()
            throws Exception {
        File logListFile = makeLogListFile("456");
        File metadataFile = sign(logListFile);
        mSignatureVerifier.resetPublicKey();

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.startMetadataDownload();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeMetadataDownloadCompleteIntent(mCompatVersion, metadataFile));
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeContentDownloadCompleteIntent(mCompatVersion, logListFile));

        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_endToEndSuccess_installNewVersion_andLogsSuccess() throws Exception {
        // Arrange
        String newVersion = "456";
        File logListFile = makeLogListFile(newVersion);
        File metadataFile = sign(logListFile);
        File publicKeyFile = writePublicKeyToFile(mPublicKey);

        assertNoVersionIsInstalled();

        // Act
        // 1. Start download of public key.
        mCertificateTransparencyDownloader.startPublicKeyDownload();

        // 2. On successful public key download, set the key and start the metatadata
        // download.
        mCertificateTransparencyDownloader.onReceive(
                mContext, makePublicKeyDownloadCompleteIntent(publicKeyFile));

        // 3. On successful metadata download, start the content download.
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeMetadataDownloadCompleteIntent(mCompatVersion, metadataFile));

        // 4. On successful content download, verify the signature and install the new
        // version.
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeContentDownloadCompleteIntent(mCompatVersion, logListFile));

        // Assert
        assertInstallSuccessful(newVersion);
        verify(mLogger, times(1))
                .logCTLogListUpdateStateChangedEvent(mUpdateStatusCaptor.capture());

        LogListUpdateStatus statusValue = mUpdateStatusCaptor.getValue();
        assertThat(statusValue.state()).isEqualTo(CTLogListUpdateState.SUCCESS);
        assertThat(statusValue.signature()).isEqualTo(new String(toByteArray(metadataFile)));
        assertThat(statusValue.logListTimestamp()).isEqualTo(LOG_LIST_TIMESTAMP);
    }

    private void assertNoVersionIsInstalled() {
        assertThat(mCompatVersion.getVersionDir().exists()).isFalse();
    }

    private void assertInstallSuccessful(String version) {
        File logsDir =
                new File(
                        mCompatVersion.getVersionDir(),
                        CompatibilityVersion.LOGS_DIR_PREFIX + version);
        assertThat(logsDir.exists()).isTrue();
        File logsFile = new File(logsDir, CompatibilityVersion.LOGS_LIST_FILE_NAME);
        assertThat(logsFile.exists()).isTrue();
    }

    private void prepareDownloadManager() {
        when(mDownloadManager.enqueue(any(Request.class)))
                .thenAnswer(invocation -> mNextDownloadId++);
    }

    private Intent makePublicKeyDownloadCompleteIntent(File publicKeyfile) {
        return makeDownloadCompleteIntent(
                mCertificateTransparencyDownloader.getPublicKeyDownloadId(), publicKeyfile);
    }

    private Intent makeMetadataDownloadCompleteIntent(
            CompatibilityVersion compatVersion, File signatureFile) {
        return makeDownloadCompleteIntent(
                mCertificateTransparencyDownloader.getMetadataDownloadId(compatVersion),
                signatureFile);
    }

    private Intent makeContentDownloadCompleteIntent(
            CompatibilityVersion compatVersion, File logListFile) {
        return makeDownloadCompleteIntent(
                mCertificateTransparencyDownloader.getContentDownloadId(compatVersion),
                logListFile);
    }

    private Intent makeDownloadCompleteIntent(long downloadId, File file) {
        when(mDownloadManager.query(any(Query.class))).thenReturn(makeSuccessfulDownloadCursor());
        when(mDownloadManager.getUriForDownloadedFile(downloadId)).thenReturn(Uri.fromFile(file));
        return new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
    }

    private Cursor makeSuccessfulDownloadCursor() {
        MatrixCursor cursor =
                new MatrixCursor(
                        new String[] {
                            DownloadManager.COLUMN_STATUS, DownloadManager.COLUMN_REASON
                        });
        cursor.addRow(new Object[] {DownloadManager.STATUS_SUCCESSFUL, -1});
        return cursor;
    }

    private Intent makePublicKeyDownloadFailedIntent(int error) {
        return makeDownloadFailedIntent(
                mCertificateTransparencyDownloader.getPublicKeyDownloadId(), error);
    }

    private Intent makeMetadataDownloadFailedIntent(CompatibilityVersion compatVersion, int error) {
        return makeDownloadFailedIntent(
                mCertificateTransparencyDownloader.getMetadataDownloadId(compatVersion), error);
    }

    private Intent makeContentDownloadFailedIntent(CompatibilityVersion compatVersion, int error) {
        return makeDownloadFailedIntent(
                mCertificateTransparencyDownloader.getContentDownloadId(compatVersion), error);
    }

    private Intent makeDownloadFailedIntent(long downloadId, int error) {
        when(mDownloadManager.query(any(Query.class))).thenReturn(makeFailedDownloadCursor(error));
        when(mDownloadManager.getUriForDownloadedFile(downloadId)).thenReturn(null);
        return new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
    }

    private Cursor makeFailedDownloadCursor(int error) {
        MatrixCursor cursor =
                new MatrixCursor(
                        new String[] {
                            DownloadManager.COLUMN_STATUS, DownloadManager.COLUMN_REASON
                        });
        cursor.addRow(new Object[] {DownloadManager.STATUS_FAILED, error});
        return cursor;
    }

    private File writePublicKeyToFile(PublicKey publicKey)
            throws IOException, GeneralSecurityException {
        return writeToFile(Base64.getEncoder().encode(publicKey.getEncoded()));
    }

    private File writeToFile(byte[] bytes) throws IOException, GeneralSecurityException {
        File file = File.createTempFile("temp_file", "tmp");

        try (OutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(bytes);
        }

        return file;
    }

    private File makeLogListFile(String version) throws IOException, JSONException {
        File logListFile = File.createTempFile("log_list", "json");

        try (OutputStream outputStream = new FileOutputStream(logListFile)) {
            JSONObject contentJson =
                    new JSONObject()
                            .put("version", version)
                            .put("log_list_timestamp", LOG_LIST_TIMESTAMP);
            outputStream.write(contentJson.toString().getBytes());
        }

        return logListFile;
    }

    private File sign(File file) throws IOException, GeneralSecurityException {
        return sign(file, mPrivateKey);
    }

    private File sign(File file, PrivateKey privateKey)
            throws IOException, GeneralSecurityException {
        File signatureFile = File.createTempFile("log_list-metadata", "sig");
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);

        try (InputStream fileStream = new FileInputStream(file);
                OutputStream outputStream = new FileOutputStream(signatureFile)) {
            signer.update(fileStream.readAllBytes());
            outputStream.write(Base64.getEncoder().encode(signer.sign()));
        }

        return signatureFile;
    }
}
