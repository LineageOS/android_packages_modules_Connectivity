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

package android.net.http.cts;

import static android.net.http.cts.util.TestUtilsKt.assertOKStatusCode;
import static android.net.http.cts.util.TestUtilsKt.skipIfNoInternetConnection;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.net.http.HttpEngine;
import android.net.http.UrlRequest;
import android.net.http.UrlRequest.Status;
import android.net.http.UrlResponseInfo;
import android.net.http.cts.util.HttpCtsTestServer;
import android.net.http.cts.util.TestStatusListener;
import android.net.http.cts.util.TestUploadDataProvider;
import android.net.http.cts.util.TestUrlRequestCallback;
import android.net.http.cts.util.TestUrlRequestCallback.ResponseStep;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UrlRequestTest {
    private TestUrlRequestCallback mCallback;
    private HttpCtsTestServer mTestServer;
    private HttpEngine mHttpEngine;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        skipIfNoInternetConnection(context);
        HttpEngine.Builder builder = new HttpEngine.Builder(context);
        mHttpEngine = builder.build();
        mCallback = new TestUrlRequestCallback();
        mTestServer = new HttpCtsTestServer(context);
    }

    @After
    public void tearDown() throws Exception {
        if (mHttpEngine != null) {
            mHttpEngine.shutdown();
        }
        if (mTestServer != null) {
            mTestServer.shutdown();
        }
    }

    private UrlRequest.Builder createUrlRequestBuilder(String url) {
        return mHttpEngine.newUrlRequestBuilder(url, mCallback, mCallback.getExecutor());
    }

    @Test
    public void testUrlRequestGet_CompletesSuccessfully() throws Exception {
        String url = mTestServer.getSuccessUrl();
        UrlRequest request = createUrlRequestBuilder(url).build();
        request.start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertThat("Received byte count must be > 0", info.getReceivedByteCount(), greaterThan(0L));
    }

    @Test
    public void testUrlRequestStatus_InvalidBeforeRequestStarts() throws Exception {
        UrlRequest request = createUrlRequestBuilder(mTestServer.getSuccessUrl()).build();
        // Calling before request is started should give Status.INVALID,
        // since the native adapter is not created.
        TestStatusListener statusListener = new TestStatusListener();
        request.getStatus(statusListener);
        statusListener.expectStatus(Status.INVALID);
    }

    @Test
    public void testUrlRequestCancel_CancelCalled() throws Exception {
        UrlRequest request = createUrlRequestBuilder(mTestServer.getSuccessUrl()).build();
        mCallback.setAutoAdvance(false);

        request.start();
        mCallback.waitForNextStep();
        assertSame(mCallback.mResponseStep, ResponseStep.ON_RESPONSE_STARTED);

        request.cancel();
        mCallback.expectCallback(ResponseStep.ON_CANCELED);
    }

    @Test
    public void testUrlRequestPost_EchoRequestBody() throws Exception {
        String testData = "test";
        UrlRequest.Builder builder = createUrlRequestBuilder(mTestServer.getEchoBodyUrl());

        TestUploadDataProvider dataProvider =
                new TestUploadDataProvider(
                        TestUploadDataProvider.SuccessCallbackMode.SYNC, mCallback.getExecutor());
        dataProvider.addRead(testData.getBytes());
        builder.setUploadDataProvider(dataProvider, mCallback.getExecutor());
        builder.addHeader("Content-Type", "text/html");
        builder.build().start();
        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);

        assertOKStatusCode(mCallback.mResponseInfo);
        assertEquals(testData, mCallback.mResponseAsString);
        dataProvider.assertClosed();
    }

    @Test
    public void testUrlRequestFail_FailedCalled() throws Exception {
        createUrlRequestBuilder("http://0.0.0.0:0/").build().start();
        mCallback.expectCallback(ResponseStep.ON_FAILED);
    }
}
