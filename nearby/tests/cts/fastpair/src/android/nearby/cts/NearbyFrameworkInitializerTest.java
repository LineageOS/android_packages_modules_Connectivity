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

package android.nearby.cts;

import android.nearby.NearbyFrameworkInitializer;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

// NearbyFrameworkInitializer was added in T
@RunWith(AndroidJUnit4.class)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class NearbyFrameworkInitializerTest {

//    // TODO(b/215435710) This test cannot pass now because our test cannot access system API.
//    // run "adb root && adb shell setenforce permissive" and uncomment testServicesRegistered,
//    // test passes.
//    @Test
//    public void testServicesRegistered() {
//        Context ctx = InstrumentationRegistry.getInstrumentation().getContext();
//        assertNotNull( "NearbyManager not registered",
//                ctx.getSystemService(Context.NEARBY_SERVICE));
//    }

    // registerServiceWrappers can only be called during initialization and should throw otherwise
    @Test(expected = IllegalStateException.class)
    public void testThrowsException() {
        NearbyFrameworkInitializer.registerServiceWrappers();
    }
}
