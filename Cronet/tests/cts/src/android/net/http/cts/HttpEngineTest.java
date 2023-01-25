/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.net.http.HttpEngine;
import android.net.http.UrlRequest;
import android.net.http.UrlResponseInfo;
import android.net.http.cts.util.TestUrlRequestCallback;
import android.net.http.cts.util.TestUrlRequestCallback.ResponseStep;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class HttpEngineTest {
    private static final String HOST = "source.android.com";
    private static final String URL = "https://" + HOST;

    private HttpEngine.Builder mEngineBuilder;
    private TestUrlRequestCallback mCallback;
    private HttpEngine mEngine;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        skipIfNoInternetConnection(context);
        mEngineBuilder = new HttpEngine.Builder(context);
        mCallback = new TestUrlRequestCallback();
    }

    @After
    public void tearDown() throws Exception {
        if (mEngine != null) {
            mEngine.shutdown();
        }
    }

    @Test
    public void testHttpEngine_Default() throws Exception {
        mEngine = mEngineBuilder.build();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(URL, mCallback, mCallback.getExecutor());
        builder.build().start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertEquals("h2", info.getNegotiatedProtocol());
    }

    @Test
    public void testHttpEngine_DisableHttp2() throws Exception {
        mEngine = mEngineBuilder.setEnableHttp2(false).build();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(URL, mCallback, mCallback.getExecutor());
        builder.build().start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertEquals("http/1.1", info.getNegotiatedProtocol());
    }

    @Test
    public void testHttpEngine_EnableQuic() throws Exception {
        // The hint doesn't guarantee that QUIC will win the race, just that it will race TCP.
        // If this ends up being flaky, consider sending multiple requests.
        mEngine = mEngineBuilder.setEnableQuic(true).addQuicHint(HOST, 443, 443).build();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(URL, mCallback, mCallback.getExecutor());
        builder.build().start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertEquals("h3", info.getNegotiatedProtocol());
    }

    @Test
    public void testHttpEngine_GetDefaultUserAgent() throws Exception {
        assertThat(mEngineBuilder.getDefaultUserAgent(), containsString("AndroidHttpClient"));
    }
}
