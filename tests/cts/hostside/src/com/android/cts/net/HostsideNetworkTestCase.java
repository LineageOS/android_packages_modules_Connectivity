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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.android.ddmlib.Log;
import com.android.modules.utils.build.testing.DeviceSdkLevel;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.DeviceTestRunOptions;
import com.android.tradefed.util.RunUtil;

import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
abstract class HostsideNetworkTestCase extends BaseHostJUnit4Test {
    protected static final boolean DEBUG = false;
    protected static final String TAG = "HostsideNetworkTests";
    protected static final String TEST_PKG = "com.android.cts.net.hostside";
    protected static final String TEST_APK = "CtsHostsideNetworkTestsApp.apk";
    protected static final String TEST_APK_NEXT = "CtsHostsideNetworkTestsAppNext.apk";
    protected static final String TEST_APP2_PKG = "com.android.cts.net.hostside.app2";
    protected static final String TEST_APP2_APK = "CtsHostsideNetworkTestsApp2.apk";

    protected void setUp() throws Exception {
        DeviceSdkLevel deviceSdkLevel = new DeviceSdkLevel(getDevice());
        String testApk = deviceSdkLevel.isDeviceAtLeastT() ? TEST_APK_NEXT
                : TEST_APK;

        uninstallPackage(TEST_PKG, false);
        installPackage(testApk);
    }

    protected void tearDown() throws Exception {
        uninstallPackage(TEST_PKG, true);
    }

    protected void installPackage(String apk) throws DeviceNotAvailableException, TargetSetupError {
        final DeviceTestRunOptions installOptions = new DeviceTestRunOptions(
                null /* packageName */);
        final int userId = getDevice().getCurrentUser();
        installPackageAsUser(apk, true /* grantPermission */, userId, "-t");
    }

    protected void uninstallPackage(String packageName, boolean shouldSucceed)
            throws DeviceNotAvailableException {
        final String result = uninstallPackage(packageName);
        if (shouldSucceed) {
            assertNull("uninstallPackage(" + packageName + ") failed: " + result, result);
        }
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
            // and check one by one, otherwise 'com.android.cts.net.hostside' could return
            // 'com.android.cts.net.hostside.app2'
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

    protected String runCommand(String command) throws DeviceNotAvailableException {
        Log.d(TAG, "Command: '" + command + "'");
        final String output = getDevice().executeShellCommand(command);
        if (DEBUG) Log.v(TAG, "Output: " + output.trim());
        return output;
    }
}
