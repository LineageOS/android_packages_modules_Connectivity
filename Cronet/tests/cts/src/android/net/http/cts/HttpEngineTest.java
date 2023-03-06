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
import static android.net.http.cts.util.TestUtilsKt.assumeOKStatusCode;
import static android.net.http.cts.util.TestUtilsKt.skipIfNoInternetConnection;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.http.HttpEngine;
import android.net.http.UrlRequest;
import android.net.http.UrlResponseInfo;
import android.net.http.cts.util.TestUrlRequestCallback;
import android.net.http.cts.util.TestUrlRequestCallback.ResponseStep;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

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
    private UrlRequest mRequest;
    private HttpEngine mEngine;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        skipIfNoInternetConnection(mContext);
        mEngineBuilder = new HttpEngine.Builder(mContext);
        mCallback = new TestUrlRequestCallback();
    }

    @After
    public void tearDown() throws Exception {
        if (mRequest != null) {
            mRequest.cancel();
            mCallback.blockForDone();
        }
        if (mEngine != null) {
            mEngine.shutdown();
        }
    }

    private boolean isQuic(String negotiatedProtocol) {
        return negotiatedProtocol.startsWith("http/2+quic") || negotiatedProtocol.startsWith("h3");
    }

    @Test
    public void testHttpEngine_Default() throws Exception {
        mEngine = mEngineBuilder.build();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(URL, mCallback, mCallback.getExecutor());
        mRequest = builder.build();
        mRequest.start();

        // This tests uses a non-hermetic server. Instead of asserting, assume the next callback.
        // This way, if the request were to fail, the test would just be skipped instead of failing.
        mCallback.assumeCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertEquals("h2", info.getNegotiatedProtocol());
    }

    @Test
    public void testHttpEngine_EnableHttpCache() {
        // We need a server which sets cache-control != no-cache.
        String url = "https://www.example.com";
        mEngine =
                mEngineBuilder
                        .setStoragePath(mContext.getApplicationInfo().dataDir)
                        .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK,
                                            /* maxSize */ 100 * 1024)
                        .build();

        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(url, mCallback, mCallback.getExecutor());
        mRequest = builder.build();
        mRequest.start();
        // This tests uses a non-hermetic server. Instead of asserting, assume the next callback.
        // This way, if the request were to fail, the test would just be skipped instead of failing.
        mCallback.assumeCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assumeOKStatusCode(info);
        assertFalse(info.wasCached());

        mCallback = new TestUrlRequestCallback();
        builder = mEngine.newUrlRequestBuilder(url, mCallback, mCallback.getExecutor());
        mRequest = builder.build();
        mRequest.start();
        mCallback.assumeCallback(ResponseStep.ON_SUCCEEDED);
        info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertTrue(info.wasCached());
    }

    @Test
    public void testHttpEngine_DisableHttp2() throws Exception {
        mEngine = mEngineBuilder.setEnableHttp2(false).build();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(URL, mCallback, mCallback.getExecutor());
        mRequest = builder.build();
        mRequest.start();

        // This tests uses a non-hermetic server. Instead of asserting, assume the next callback.
        // This way, if the request were to fail, the test would just be skipped instead of failing.
        mCallback.assumeCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertEquals("http/1.1", info.getNegotiatedProtocol());
    }

    @Test
    public void testHttpEngine_EnableQuic() throws Exception {
        mEngine = mEngineBuilder.setEnableQuic(true).addQuicHint(HOST, 443, 443).build();
        // The hint doesn't guarantee that QUIC will win the race, just that it will race TCP.
        // We send multiple requests to reduce the flakiness of the test.
        boolean quicWasUsed = false;
        for (int i = 0; i < 5; i++) {
            mCallback = new TestUrlRequestCallback();
            UrlRequest.Builder builder =
                    mEngine.newUrlRequestBuilder(URL, mCallback, mCallback.getExecutor());
            mRequest = builder.build();
            mRequest.start();

            // This tests uses a non-hermetic server. Instead of asserting, assume the next
            // callback. This way, if the request were to fail, the test would just be skipped
            // instead of failing.
            mCallback.assumeCallback(ResponseStep.ON_SUCCEEDED);
            UrlResponseInfo info = mCallback.mResponseInfo;
            assumeOKStatusCode(info);
            quicWasUsed = isQuic(info.getNegotiatedProtocol());
            if (quicWasUsed) {
                break;
            }
        }
        assertTrue(quicWasUsed);
    }

    @Test
    public void testHttpEngine_GetDefaultUserAgent() throws Exception {
        assertThat(mEngineBuilder.getDefaultUserAgent(), containsString("AndroidHttpClient"));
    }
}
