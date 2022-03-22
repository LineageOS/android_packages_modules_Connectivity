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

package com.android.server.nearby.presence;

import static com.android.server.nearby.presence.PresenceManager.NANOAPP_ID;
import static com.android.server.nearby.presence.PresenceManager.NANOAPP_MESSAGE_TYPE_FILTER_RESULT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.location.NanoAppMessage;

import androidx.test.filters.SdkSuppress;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

import service.proto.Blefilter;

public class PresenceManagerTest {
    @Mock private Context mContext;
    @Mock private PresenceManager.PresenceCallback mPresenceCallback;
    @Mock private ChreCommunication mChreCommunication;

    @Captor ArgumentCaptor<ChreCommunication.ContextHubCommsCallback> mChreCallbackCaptor;
    @Captor ArgumentCaptor<NanoAppMessage> mNanoAppMessageCaptor;

    private PresenceManager mPresenceManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mPresenceManager = new PresenceManager(mContext, mPresenceCallback);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testInit() {
        mPresenceManager.initiate(mChreCommunication);
        verify(mChreCommunication).start(any(), eq(Collections.singleton(NANOAPP_ID)));
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testOnFilterResults() {
        Blefilter.BleFilterResults results = Blefilter.BleFilterResults.newBuilder().build();
        NanoAppMessage chre_message =
                NanoAppMessage.createMessageToNanoApp(
                        NANOAPP_ID, NANOAPP_MESSAGE_TYPE_FILTER_RESULT, results.toByteArray());
        mPresenceManager.initiate(mChreCommunication);
        verify(mChreCommunication).start(mChreCallbackCaptor.capture(), any());
        mChreCallbackCaptor.getValue().onMessageFromNanoApp(chre_message);
        verify(mPresenceCallback).onFilterResults(eq(results));
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testUpdateFiltersBeforeChreStarted() {
        Blefilter.BleFilters filters = Blefilter.BleFilters.newBuilder().build();
        mPresenceManager.updateFilters(filters);
        verify(mChreCommunication, never()).sendMessageToNanoApp(any());
        mPresenceManager.initiate(mChreCommunication);
        verify(mChreCommunication).start(mChreCallbackCaptor.capture(), any());
        mChreCallbackCaptor.getValue().started(true);
        verify(mChreCommunication, times(1)).sendMessageToNanoApp(mNanoAppMessageCaptor.capture());
        assertThat(mNanoAppMessageCaptor.getValue().getMessageBody())
                .isEqualTo(filters.toByteArray());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testUpdateFiltersAfterChreStarted() {
        mPresenceManager.initiate(mChreCommunication);
        verify(mChreCommunication).start(mChreCallbackCaptor.capture(), any());
        mChreCallbackCaptor.getValue().started(true);
        Blefilter.BleFilters filters = Blefilter.BleFilters.newBuilder().build();
        mPresenceManager.updateFilters(filters);
        verify(mChreCommunication, times(1)).sendMessageToNanoApp(mNanoAppMessageCaptor.capture());
        assertThat(mNanoAppMessageCaptor.getValue().getMessageBody())
                .isEqualTo(filters.toByteArray());
    }
}
