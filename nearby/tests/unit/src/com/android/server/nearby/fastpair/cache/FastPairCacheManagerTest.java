/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.nearby.fastpair.cache;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import service.proto.Cache;

public class FastPairCacheManagerTest {

    private static final String MODEL_ID = "001";
    private static final String MODEL_ID2 = "002";
    private static final String APP_NAME = "APP_NAME";
    @Mock
    DiscoveryItem mDiscoveryItem;
    @Mock DiscoveryItem mDiscoveryItem2;
    Cache.StoredDiscoveryItem mStoredDiscoveryItem = Cache.StoredDiscoveryItem.newBuilder()
            .setTriggerId(MODEL_ID)
            .setAppName(APP_NAME).build();
    Cache.StoredDiscoveryItem mStoredDiscoveryItem2 = Cache.StoredDiscoveryItem.newBuilder()
            .setTriggerId(MODEL_ID2)
            .setAppName(APP_NAME).build();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void notSaveRetrieveInfo() {
        Context mContext = ApplicationProvider.getApplicationContext();
        when(mDiscoveryItem.getCopyOfStoredItem()).thenReturn(mStoredDiscoveryItem);
        when(mDiscoveryItem.getTriggerId()).thenReturn(MODEL_ID);

        FastPairCacheManager fastPairCacheManager = new FastPairCacheManager(mContext);

        assertThat(fastPairCacheManager.getStoredDiscoveryItem(MODEL_ID).getAppName())
                .isNotEqualTo(APP_NAME);
    }

    @Test
    public void saveRetrieveInfo() {
        Context mContext = ApplicationProvider.getApplicationContext();
        when(mDiscoveryItem.getCopyOfStoredItem()).thenReturn(mStoredDiscoveryItem);
        when(mDiscoveryItem.getTriggerId()).thenReturn(MODEL_ID);

        FastPairCacheManager fastPairCacheManager = new FastPairCacheManager(mContext);
        fastPairCacheManager.saveDiscoveryItem(mDiscoveryItem);
        assertThat(fastPairCacheManager.getStoredDiscoveryItem(MODEL_ID).getAppName())
                .isEqualTo(APP_NAME);
    }

    @Test
    public void getAllInfo() {
        Context mContext = ApplicationProvider.getApplicationContext();
        when(mDiscoveryItem.getCopyOfStoredItem()).thenReturn(mStoredDiscoveryItem);
        when(mDiscoveryItem.getTriggerId()).thenReturn(MODEL_ID);
        when(mDiscoveryItem2.getCopyOfStoredItem()).thenReturn(mStoredDiscoveryItem2);
        when(mDiscoveryItem2.getTriggerId()).thenReturn(MODEL_ID2);

        FastPairCacheManager fastPairCacheManager = new FastPairCacheManager(mContext);
        fastPairCacheManager.saveDiscoveryItem(mDiscoveryItem);

        assertThat(fastPairCacheManager.getAllSavedStoreDiscoveryItem()).hasSize(2);

        fastPairCacheManager.saveDiscoveryItem(mDiscoveryItem2);

        assertThat(fastPairCacheManager.getAllSavedStoreDiscoveryItem()).hasSize(3);
    }

}
