/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.cts;

import static android.os.Process.INVALID_UID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.INetworkStatsService;
import android.net.TrafficStats;
import android.net.connectivity.android.net.netstats.StatsResult;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;

import androidx.test.InstrumentationRegistry;

import com.android.net.module.util.CollectionUtils;
import com.android.testutils.ConnectivityModuleTest;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@ConnectivityModuleTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2) // Mainline NetworkStats starts from T.
@RunWith(DevSdkIgnoreRunner.class)
public class NetworkStatsBinderTest {
    @Nullable
    private StatsResult getUidStatsFromBinder(int uid) throws Exception {
        final Method getServiceMethod = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", new Class[]{String.class});
        final IBinder binder = (IBinder) getServiceMethod.invoke(
                null, Context.NETWORK_STATS_SERVICE);
        final INetworkStatsService nss = INetworkStatsService.Stub.asInterface(binder);
        return nss.getUidStats(uid);
    }

    private int getFirstAppUidThat(@NonNull Predicate<Integer> predicate) {
        PackageManager pm = InstrumentationRegistry.getContext().getPackageManager();
        List<PackageInfo> apps = pm.getInstalledPackages(0 /* flags */);
        final PackageInfo match = CollectionUtils.findFirst(apps,
                it -> it.applicationInfo != null && predicate.test(it.applicationInfo.uid));
        if (match != null) return match.applicationInfo.uid;
        return INVALID_UID;
    }

    @Test
    public void testAccessUidStatsFromBinder() throws Exception {
        final int myUid = Process.myUid();
        final List<Integer> testUidList = new ArrayList<>();

        // Prepare uid list for testing.
        testUidList.add(INVALID_UID);
        testUidList.add(Process.ROOT_UID);
        testUidList.add(Process.SYSTEM_UID);
        testUidList.add(myUid);
        testUidList.add(Process.LAST_APPLICATION_UID);
        testUidList.add(Process.LAST_APPLICATION_UID + 1);
        // If available, pick another existing uid for testing that is not already contained
        // in the list above.
        final int notMyUid = getFirstAppUidThat(uid -> uid >= 0 && !testUidList.contains(uid));
        if (notMyUid != INVALID_UID) testUidList.add(notMyUid);

        for (final int uid : testUidList) {
            try {
                final StatsResult uidStatsFromBinder = getUidStatsFromBinder(uid);

                if (uid != myUid) {
                    // Verify that null is returned if the uid is not current app uid.
                    assertNull(uidStatsFromBinder);
                } else {
                    // Verify that returned result is the same with the result get from
                    // TrafficStats.
                    assertEquals(uidStatsFromBinder.rxBytes, TrafficStats.getUidRxBytes(uid));
                    assertEquals(uidStatsFromBinder.rxPackets, TrafficStats.getUidRxPackets(uid));
                    assertEquals(uidStatsFromBinder.txBytes, TrafficStats.getUidTxBytes(uid));
                    assertEquals(uidStatsFromBinder.txPackets, TrafficStats.getUidTxPackets(uid));
                }
            } catch (IllegalAccessException e) {
                /* Java language access prevents exploitation. */
                return;
            } catch (InvocationTargetException e) {
                /* Underlying method has been changed. */
                return;
            } catch (ClassNotFoundException e) {
                /* not vulnerable if hidden API no longer available */
                return;
            } catch (NoSuchMethodException | NoSuchMethodError e) {
                /* not vulnerable if hidden API no longer available */
                return;
            } catch (RemoteException e) {
                return;
            }
        }
    }
}
