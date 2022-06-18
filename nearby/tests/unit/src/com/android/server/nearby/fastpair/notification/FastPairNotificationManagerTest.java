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

package com.android.server.nearby.fastpair.notification;

import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.nearby.common.locator.LocatorContextWrapper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class FastPairNotificationManagerTest {

    @Mock private Context mContext;
    private static final boolean USE_LARGE_ICON = true;
    private static final int NOTIFICATION_ID = 1;
    private static final String COMPANION_APP = "companionApp";
    private static final int BATTERY_LEVEL = 1;
    private static final String DEVICE_NAME = "deviceName";
    private static final String ADDRESS = "address";
    private FastPairNotificationManager mFastPairNotificationManager;
    private LocatorContextWrapper mLocatorContextWrapper;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mLocatorContextWrapper = new LocatorContextWrapper(mContext);
        when(mContext.getContentResolver()).thenReturn(
                InstrumentationRegistry.getInstrumentation().getContext().getContentResolver());
        mFastPairNotificationManager =
                new FastPairNotificationManager(mLocatorContextWrapper, null,
                        USE_LARGE_ICON, NOTIFICATION_ID);
    }

    @Test
    public void  notifyPairingProcessDone() {
        mFastPairNotificationManager.notifyPairingProcessDone(true, true,
                "privateAddress", "publicAddress");
    }

    @Test
    public void  showConnectingNotification() {
        mFastPairNotificationManager.showConnectingNotification();
    }

    @Test
    public void   showPairingFailedNotification() {
        mFastPairNotificationManager.showPairingFailedNotification(new byte[]{1});
    }

    @Test
    public void  showPairingSucceededNotification() {
        mFastPairNotificationManager.showPairingSucceededNotification(COMPANION_APP,
                BATTERY_LEVEL, DEVICE_NAME, ADDRESS);
    }
}
