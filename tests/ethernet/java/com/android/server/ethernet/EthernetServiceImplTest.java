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

package com.android.server.ethernet;

import static org.junit.Assert.assertThrows;

import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.InternalNetworkUpdateRequest;
import android.net.IpConfiguration;
import android.net.StaticIpConfiguration;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class EthernetServiceImplTest {
    private EthernetServiceImpl mEthernetServiceImpl;
    @Mock private Context mContext;
    @Mock private PackageManager mPackageManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        doReturn(mPackageManager).when(mContext).getPackageManager();
        mEthernetServiceImpl = new EthernetServiceImpl(mContext);
        mEthernetServiceImpl.mStarted.set(true);
    }

    @Test
    public void testSetConfigurationRejectsWhenEthNotStarted() {
        mEthernetServiceImpl.mStarted.set(false);
        assertThrows(IllegalStateException.class, () -> {
            mEthernetServiceImpl.setConfiguration("" /* iface */, new IpConfiguration());
        });
    }

    @Test
    public void testUpdateConfigurationRejectsWhenEthNotStarted() {
        mEthernetServiceImpl.mStarted.set(false);
        assertThrows(IllegalStateException.class, () -> {
            final InternalNetworkUpdateRequest r =
                    new InternalNetworkUpdateRequest(new StaticIpConfiguration(), null);

            mEthernetServiceImpl.updateConfiguration("" /* iface */, r, null /* listener */);
        });
    }

    @Test
    public void testConnectNetworkRejectsWhenEthNotStarted() {
        mEthernetServiceImpl.mStarted.set(false);
        assertThrows(IllegalStateException.class, () -> {
            mEthernetServiceImpl.connectNetwork("" /* iface */, null /* listener */);
        });
    }

    @Test
    public void testDisconnectNetworkRejectsWhenEthNotStarted() {
        mEthernetServiceImpl.mStarted.set(false);
        assertThrows(IllegalStateException.class, () -> {
            mEthernetServiceImpl.disconnectNetwork("" /* iface */, null /* listener */);
        });
    }

    @Test
    public void testUpdateConfigurationRejectsNullIface() {
        assertThrows(NullPointerException.class, () -> {
            final InternalNetworkUpdateRequest r =
                    new InternalNetworkUpdateRequest(new StaticIpConfiguration(), null);

            mEthernetServiceImpl.updateConfiguration(null /* iface */, r, null /* listener */);
        });
    }

    @Test
    public void testConnectNetworkRejectsNullIface() {
        assertThrows(NullPointerException.class, () -> {
            mEthernetServiceImpl.connectNetwork(null /* iface */, null /* listener */);
        });
    }

    @Test
    public void testDisconnectNetworkRejectsNullIface() {
        assertThrows(NullPointerException.class, () -> {
            mEthernetServiceImpl.disconnectNetwork(null /* iface */, null /* listener */);
        });
    }

    @Test
    public void testUpdateConfigurationRejectsWithoutAutomotiveFeature() {
        disableAutomotiveFeature();
        assertThrows(UnsupportedOperationException.class, () -> {
            final InternalNetworkUpdateRequest r =
                    new InternalNetworkUpdateRequest(new StaticIpConfiguration(), null);

            mEthernetServiceImpl.updateConfiguration("" /* iface */, r, null /* listener */);
        });
    }

    @Test
    public void testConnectNetworkRejectsWithoutAutomotiveFeature() {
        disableAutomotiveFeature();
        assertThrows(UnsupportedOperationException.class, () -> {
            mEthernetServiceImpl.connectNetwork("" /* iface */, null /* listener */);
        });
    }

    @Test
    public void testDisconnectNetworkRejectsWithoutAutomotiveFeature() {
        disableAutomotiveFeature();
        assertThrows(UnsupportedOperationException.class, () -> {
            mEthernetServiceImpl.disconnectNetwork("" /* iface */, null /* listener */);
        });
    }

    private void disableAutomotiveFeature() {
        doReturn(false).when(mPackageManager).hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }
}
