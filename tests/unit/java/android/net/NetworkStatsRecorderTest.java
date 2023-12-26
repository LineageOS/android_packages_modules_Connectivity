/*
 * Copyright (C) 2022 The Android Open Source Project
 *i
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

package com.android.server.net;

import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_UID;
import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_XT;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;

import static com.android.server.ConnectivityStatsLog.NETWORK_STATS_RECORDER_FILE_OPERATED__RECORDER_PREFIX__PREFIX_UID;
import static com.android.server.ConnectivityStatsLog.NETWORK_STATS_RECORDER_FILE_OPERATED__RECORDER_PREFIX__PREFIX_XT;
import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.NetworkIdentity;
import android.net.NetworkIdentitySet;
import android.net.NetworkStats;
import android.net.NetworkStatsCollection;
import android.os.DropBoxManager;

import androidx.test.filters.SmallTest;

import com.android.internal.util.FileRotator;
import com.android.metrics.NetworkStatsMetricsLogger;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public final class NetworkStatsRecorderTest {
    private static final String TAG = NetworkStatsRecorderTest.class.getSimpleName();

    private static final String TEST_PREFIX = "test";
    private static final int TEST_UID1 = 1234;
    private static final int TEST_UID2 = 1235;

    @Mock private DropBoxManager mDropBox;
    @Mock private NetworkStats.NonMonotonicObserver mObserver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    private NetworkStatsRecorder buildRecorder(FileRotator rotator, boolean wipeOnError) {
        return new NetworkStatsRecorder(rotator, mObserver, mDropBox, TEST_PREFIX,
                HOUR_IN_MILLIS, false /* includeTags */, wipeOnError,
                false /* useFastDataInput */);
    }

    @Test
    public void testWipeOnError() throws Exception {
        final FileRotator rotator = mock(FileRotator.class);
        final NetworkStatsRecorder wipeOnErrorRecorder = buildRecorder(rotator, true);

        // Assuming that the rotator gets an exception happened when read data.
        doThrow(new IOException()).when(rotator).readMatching(any(), anyLong(), anyLong());
        wipeOnErrorRecorder.getOrLoadPartialLocked(Long.MIN_VALUE, Long.MAX_VALUE);
        // Verify that the files will be deleted.
        verify(rotator, times(1)).deleteAll();
        reset(rotator);

        final NetworkStatsRecorder noWipeOnErrorRecorder = buildRecorder(rotator, false);
        doThrow(new IOException()).when(rotator).readMatching(any(), anyLong(), anyLong());
        noWipeOnErrorRecorder.getOrLoadPartialLocked(Long.MIN_VALUE, Long.MAX_VALUE);
        // Verify that the rotator won't delete files.
        verify(rotator, never()).deleteAll();
    }

    @Test
    public void testFileReadingMetrics_empty() {
        final NetworkStatsCollection collection = new NetworkStatsCollection(30);
        final NetworkStatsMetricsLogger.Dependencies deps =
                mock(NetworkStatsMetricsLogger.Dependencies.class);
        final NetworkStatsMetricsLogger logger = new NetworkStatsMetricsLogger(deps);
        logger.logRecorderFileReading(PREFIX_XT, 888, collection);
        verify(deps).writeRecorderFileReadingStats(
                NETWORK_STATS_RECORDER_FILE_OPERATED__RECORDER_PREFIX__PREFIX_XT,
                1 /* readIndex */,
                888 /* readLatencyMillis */,
                0 /* fileCount */,
                0 /* totalFileSize */,
                0 /* keys */,
                0 /* uids */,
                0 /* totalHistorySize */
        );

        // Write second time, verify the index increases.
        logger.logRecorderFileReading(PREFIX_XT, 567, collection);
        verify(deps).writeRecorderFileReadingStats(
                NETWORK_STATS_RECORDER_FILE_OPERATED__RECORDER_PREFIX__PREFIX_XT,
                2 /* readIndex */,
                567 /* readLatencyMillis */,
                0 /* fileCount */,
                0 /* totalFileSize */,
                0 /* keys */,
                0 /* uids */,
                0 /* totalHistorySize */
        );
    }

    @Test
    public void testFileReadingMetrics() {
        final NetworkStatsCollection collection = new NetworkStatsCollection(30);
        final NetworkStats.Entry entry = new NetworkStats.Entry();
        final NetworkIdentitySet identSet = new NetworkIdentitySet();
        identSet.add(new NetworkIdentity.Builder().build());
        // Empty entries will be skipped, put some ints to make sure they can be recorded.
        entry.rxBytes = 1;

        collection.recordData(identSet, TEST_UID1, SET_DEFAULT, TAG_NONE, 0, 60, entry);
        collection.recordData(identSet, TEST_UID2, SET_DEFAULT, TAG_NONE, 0, 60, entry);
        collection.recordData(identSet, TEST_UID2, SET_FOREGROUND, TAG_NONE, 30, 60, entry);

        final NetworkStatsMetricsLogger.Dependencies deps =
                mock(NetworkStatsMetricsLogger.Dependencies.class);
        final NetworkStatsMetricsLogger logger = new NetworkStatsMetricsLogger(deps);
        logger.logRecorderFileReading(PREFIX_UID, 123, collection);
        verify(deps).writeRecorderFileReadingStats(
                NETWORK_STATS_RECORDER_FILE_OPERATED__RECORDER_PREFIX__PREFIX_UID,
                1 /* readIndex */,
                123 /* readLatencyMillis */,
                0 /* fileCount */,
                0 /* totalFileSize */,
                3 /* keys */,
                2 /* uids */,
                5 /* totalHistorySize */
        );
    }
}
