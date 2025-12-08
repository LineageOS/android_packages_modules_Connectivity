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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.app.DownloadManager;

import android.database.MatrixCursor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for the {@link DownloadHelper}. */
@RunWith(JUnit4.class)
public class DownloadHelperTest {

    @Mock private DownloadManager mDownloadManager;

    private DownloadHelper mDownloadHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mDownloadHelper = new DownloadHelper(mDownloadManager);
    }

    @Test
    public void testDownloadHelper_scheduleDownload() {
        long downloadId = 666;
        when(mDownloadManager.enqueue(any())).thenReturn(downloadId);

        assertThat(mDownloadHelper.startDownload("http://test.org")).isEqualTo(downloadId);
    }

    @Test
    public void testDownloadHelper_multipleDownloads_doesNotRescheduleDuplicates() {
        long downloadId1 = 666;
        String url1 = "http://test1.org";
        long downloadId2 = 777;
        String url2 = "http://test2.org";
        when(mDownloadManager.enqueue(any())).thenReturn(downloadId1, downloadId2);

        assertThat(mDownloadHelper.startDownload(url1)).isEqualTo(downloadId1);
        mockPendingDownload(downloadId1, url1);

        assertThat(mDownloadHelper.startDownload(url2)).isEqualTo(downloadId2);
        assertThat(mDownloadHelper.startDownload(url1)).isEqualTo(downloadId1);
    }

    @Test
    public void testDownloadHelper_wrongUri() {
        when(mDownloadManager.enqueue(any())).thenReturn(666L);

        assertThrows(
                IllegalArgumentException.class, () -> mDownloadHelper.startDownload("not_a_uri"));
    }

    private void mockPendingDownload(long downloadId, String url) {
        MatrixCursor cursor =
                new MatrixCursor(
                        new String[] {
                            DownloadManager.COLUMN_ID,
                            DownloadManager.COLUMN_URI,
                            DownloadManager.COLUMN_STATUS
                        });
        cursor.addRow(new Object[] {downloadId, url, DownloadManager.STATUS_PENDING});

        when(mDownloadManager.query(any())).thenReturn(cursor);
    }
}
