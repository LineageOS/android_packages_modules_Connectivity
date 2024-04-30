/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.cts.netpolicy;

import static com.android.cts.netpolicy.arguments.InstrumentationArguments.ARG_CONNECTION_CHECK_CUSTOM_URL;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.android.ddmlib.Log;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.targetprep.suite.SuiteApkInstaller;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.AfterClassWithInfo;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.BeforeClassWithInfo;
import com.android.tradefed.testtype.junit4.DeviceTestRunOptions;
import com.android.tradefed.util.RunUtil;

import org.junit.runner.RunWith;

import java.util.Map;

@RunWith(DeviceJUnit4ClassRunner.class)
abstract class HostsideNetworkPolicyTestCase extends BaseHostJUnit4Test {
    protected static final boolean DEBUG = false;
    protected static final String TAG = "HostsideNetworkPolicyTests";
    protected static final String TEST_PKG = "com.android.cts.netpolicy.hostside";
    protected static final String TEST_APK = "CtsHostsideNetworkPolicyTestsApp.apk";
    protected static final String TEST_APP2_PKG = "com.android.cts.netpolicy.hostside.app2";
    protected static final String TEST_APP2_APK = "CtsHostsideNetworkPolicyTestsApp2.apk";

    @Option(name = "custom-url", importance = Option.Importance.IF_UNSET,
            description = "A custom url to use for testing network connections")
    protected String mCustomUrl;

    @BeforeClassWithInfo
    public static void setUpOnceBase(TestInformation testInfo) throws Exception {
        uninstallPackage(testInfo, TEST_PKG, false);
        installPackage(testInfo, TEST_APK);
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

    protected void assertPackageUninstalled(String packageName) throws DeviceNotAvailableException,
            InterruptedException {
        final String command = "cmd package list packages " + packageName;
        final int max_tries = 5;
        for (int i = 1; i <= max_tries; i++) {
            final String result = runCommand(command);
            if (result.trim().isEmpty()) {
                return;
            }
            // 'list packages' filters by substring, so we need to iterate with the results
            // and check one by one, otherwise 'com.android.cts.netpolicy.hostside' could return
            // 'com.android.cts.netpolicy.hostside.app2'
            boolean found = false;
            for (String line : result.split("[\\r\\n]+")) {
                if (line.endsWith(packageName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return;
            }
            i++;
            Log.v(TAG, "Package " + packageName + " not uninstalled yet (" + result
                    + "); sleeping 1s before polling again");
            RunUtil.getDefault().sleep(1000);
        }
        fail("Package '" + packageName + "' not uinstalled after " + max_tries + " seconds");
    }

    protected int getUid(String packageName) throws DeviceNotAvailableException {
        final int currentUser = getDevice().getCurrentUser();
        final String uidLines = runCommand(
                "cmd package list packages -U --user " + currentUser + " " + packageName);
        for (String uidLine : uidLines.split("\n")) {
            if (uidLine.startsWith("package:" + packageName + " uid:")) {
                final String[] uidLineParts = uidLine.split(":");
                // 3rd entry is package uid
                return Integer.parseInt(uidLineParts[2].trim());
            }
        }
        throw new IllegalStateException("Failed to find the test app on the device; pkg="
                + packageName + ", u=" + currentUser);
    }

    protected boolean runDeviceTestsWithCustomOptions(String packageName, String className)
            throws DeviceNotAvailableException {
        return runDeviceTestsWithCustomOptions(packageName, className, null);
    }

    protected boolean runDeviceTestsWithCustomOptions(String packageName, String className,
            String methodName) throws DeviceNotAvailableException {
        return runDeviceTestsWithCustomOptions(packageName, className, methodName, null);
    }

    protected boolean runDeviceTestsWithCustomOptions(String packageName, String className,
            String methodName, Map<String, String> testArgs) throws DeviceNotAvailableException {
        final DeviceTestRunOptions deviceTestRunOptions = new DeviceTestRunOptions(packageName)
                .setTestClassName(className)
                .setTestMethodName(methodName);

        // Currently there is only one custom option that the test exposes.
        if (mCustomUrl != null) {
            deviceTestRunOptions.addInstrumentationArg(ARG_CONNECTION_CHECK_CUSTOM_URL, mCustomUrl);
        }
        // Pass over any test specific arguments.
        if (testArgs != null) {
            for (Map.Entry<String, String> arg : testArgs.entrySet()) {
                deviceTestRunOptions.addInstrumentationArg(arg.getKey(), arg.getValue());
            }
        }
        return runDeviceTests(deviceTestRunOptions);
    }

    protected String runCommand(String command) throws DeviceNotAvailableException {
        Log.d(TAG, "Command: '" + command + "'");
        final String output = getDevice().executeShellCommand(command);
        if (DEBUG) Log.v(TAG, "Output: " + output.trim());
        return output;
    }
}
