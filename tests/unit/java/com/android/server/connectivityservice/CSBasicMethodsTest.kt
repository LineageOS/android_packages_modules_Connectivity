@file:Suppress("DEPRECATION") // This file tests a bunch of deprecated methods : don't warn about it

package com.android.server

import android.net.ConnectivityManager
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSBasicMethodsTest : CSTest() {
    @Test
    fun testNetworkTypes() {
        // Ensure that mocks for the networkAttributes config variable work as expected. If they
        // don't, then tests that depend on CONNECTIVITY_ACTION broadcasts for these network types
        // will fail. Failing here is much easier to debug.
        assertTrue(cm.isNetworkSupported(ConnectivityManager.TYPE_WIFI))
        assertTrue(cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE))
        assertTrue(cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE_MMS))
        assertTrue(cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE_FOTA))
        assertFalse(cm.isNetworkSupported(ConnectivityManager.TYPE_PROXY))

        // Check that TYPE_ETHERNET is supported. Unlike the asserts above, which only validate our
        // mocks, this assert exercises the ConnectivityService code path that ensures that
        // TYPE_ETHERNET is supported if the ethernet service is running.
        assertTrue(cm.isNetworkSupported(ConnectivityManager.TYPE_ETHERNET))
    }
}
