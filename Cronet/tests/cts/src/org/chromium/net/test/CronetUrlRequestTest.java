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
package org.chromium.net.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.chromium.net.CronetEngine;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;
import org.chromium.net.test.util.TestUrlRequestCallback;
import org.chromium.net.test.util.TestUrlRequestCallback.ResponseStep;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

@RunWith(AndroidJUnit4.class)
public class CronetUrlRequestTest {
    private static final String TAG = CronetUrlRequestTest.class.getSimpleName();
    private static final String HTTPS_PREFIX = "https://";

    private final String[] mTestDomains = {"www.google.com", "www.android.com"};
    @NonNull private CronetEngine mCronetEngine;
    @NonNull private ConnectivityManager mCm;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mCm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        CronetEngine.Builder builder = new CronetEngine.Builder(context);
        builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, 100 * 1024)
                .enableHttp2(true)
                // .enableBrotli(true)
                .enableQuic(true);
        mCronetEngine = builder.build();
    }

    private static void assertGreaterThan(String msg, int first, int second) {
        assertTrue(msg + " Excepted " + first + " to be greater than " + second, first > second);
    }

    private void assertHasTestableNetworks() {
        assertNotNull("This test requires a working Internet connection", mCm.getActiveNetwork());
    }

    private String getRandomDomain() {
        int index = (new Random()).nextInt(mTestDomains.length);
        return mTestDomains[index];
    }

    @Test
    public void testUrlRequestGet_CompletesSuccessfully() throws Exception {
        assertHasTestableNetworks();
        String url = HTTPS_PREFIX + getRandomDomain();
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder = mCronetEngine.newUrlRequestBuilder(url, callback,
                callback.getExecutor());
        builder.build().start();

        callback.expectCallback(ResponseStep.ON_SUCCEEDED);

        UrlResponseInfo info = callback.mResponseInfo;
        assertEquals("Unexpected http status code from " + url + ".", 200,
                info.getHttpStatusCode());
        assertGreaterThan(
                "Received byte from " + url + " is 0.", (int) info.getReceivedByteCount(), 0);
    }
}
