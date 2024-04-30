/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.android.modules.utils.build.testing.DeviceSdkLevel;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.targetprep.suite.SuiteApkInstaller;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.AfterClassWithInfo;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.BeforeClassWithInfo;

import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
abstract class HostsideNetworkTestCase extends BaseHostJUnit4Test {
    protected static final String TEST_PKG = "com.android.cts.net.hostside";
    protected static final String TEST_APK = "CtsHostsideNetworkTestsApp.apk";
    protected static final String TEST_APK_NEXT = "CtsHostsideNetworkTestsAppNext.apk";
    protected static final String TEST_APP2_PKG = "com.android.cts.net.hostside.app2";
    protected static final String TEST_APP2_APK = "CtsHostsideNetworkTestsApp2.apk";

    @BeforeClassWithInfo
    public static void setUpOnceBase(TestInformation testInfo) throws Exception {
        DeviceSdkLevel deviceSdkLevel = new DeviceSdkLevel(testInfo.getDevice());
        String testApk = deviceSdkLevel.isDeviceAtLeastV() ? TEST_APK_NEXT : TEST_APK;

        uninstallPackage(testInfo, TEST_PKG, false);
        installPackage(testInfo, testApk);
    }

    @AfterClassWithInfo
    public static void tearDownOnceBase(TestInformation testInfo)
            throws DeviceNotAvailableException {
        uninstallPackage(testInfo, TEST_PKG, true);
    }

    // Custom static method to install the specified package, this is used to bypass auto-cleanup
    // per test in BaseHostJUnit4.
    protected static void installPackage(TestInformation testInfo, String apk)
            throws DeviceNotAvailableException, TargetSetupError {
        assertNotNull(testInfo);
        final int userId = testInfo.getDevice().getCurrentUser();
        final SuiteApkInstaller installer = new SuiteApkInstaller();
        // Force the apk clean up
        installer.setCleanApk(true);
        installer.addTestFileName(apk);
        installer.setUserId(userId);
        installer.setShouldGrantPermission(true);
        installer.addInstallArg("-t");
        try {
            installer.setUp(testInfo);
        } catch (BuildError e) {
            throw new TargetSetupError(
                    e.getMessage(), e, testInfo.getDevice().getDeviceDescriptor(), e.getErrorId());
        }
    }

    protected void installPackage(String apk) throws DeviceNotAvailableException, TargetSetupError {
        installPackage(getTestInformation(), apk);
    }

    protected static void uninstallPackage(TestInformation testInfo, String packageName,
            boolean shouldSucceed)
            throws DeviceNotAvailableException {
        assertNotNull(testInfo);
        final String result = testInfo.getDevice().uninstallPackage(packageName);
        if (shouldSucceed) {
            assertNull("uninstallPackage(" + packageName + ") failed: " + result, result);
        }
    }

    protected void uninstallPackage(String packageName,
            boolean shouldSucceed)
            throws DeviceNotAvailableException {
        uninstallPackage(getTestInformation(), packageName, shouldSucceed);
    }
}
