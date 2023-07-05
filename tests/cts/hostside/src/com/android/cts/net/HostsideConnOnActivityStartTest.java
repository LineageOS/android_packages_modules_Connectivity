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

package com.android.cts.net;

import android.platform.test.annotations.FlakyTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HostsideConnOnActivityStartTest extends HostsideNetworkTestCase {
    private static final String TEST_CLASS = TEST_PKG + ".ConnOnActivityStartTest";
    @Before
    public void setUp() throws Exception {
        super.setUp();

        uninstallPackage(TEST_APP2_PKG, false);
        installPackage(TEST_APP2_APK);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();

        uninstallPackage(TEST_APP2_PKG, true);
    }

    @Test
    public void testStartActivity_batterySaver() throws Exception {
        runDeviceTests(TEST_PKG, TEST_CLASS, "testStartActivity_batterySaver");
    }

    @Test
    public void testStartActivity_dataSaver() throws Exception {
        runDeviceTests(TEST_PKG, TEST_CLASS, "testStartActivity_dataSaver");
    }

    @FlakyTest(bugId = 231440256)
    @Test
    public void testStartActivity_doze() throws Exception {
        runDeviceTests(TEST_PKG, TEST_CLASS, "testStartActivity_doze");
    }

    @Test
    public void testStartActivity_appStandby() throws Exception {
        runDeviceTests(TEST_PKG, TEST_CLASS, "testStartActivity_appStandby");
    }
}
