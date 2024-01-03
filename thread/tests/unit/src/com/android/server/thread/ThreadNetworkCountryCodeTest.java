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

package com.android.server.thread;

import static android.net.thread.ThreadNetworkException.ERROR_INTERNAL_ERROR;

import static com.android.server.thread.ThreadNetworkCountryCode.DEFAULT_COUNTRY_CODE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.thread.IOperationReceiver;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.ActiveCountryCodeChangedCallback;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.connectivity.resources.R;
import com.android.server.connectivity.ConnectivityResources;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.Locale;

/** Unit tests for {@link ThreadNetworkCountryCode}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ThreadNetworkCountryCodeTest {
    private static final String TEST_COUNTRY_CODE_US = "US";
    private static final String TEST_COUNTRY_CODE_CN = "CN";

    @Mock LocationManager mLocationManager;
    @Mock Geocoder mGeocoder;
    @Mock ThreadNetworkControllerService mThreadNetworkControllerService;
    @Mock PackageManager mPackageManager;
    @Mock Location mLocation;
    @Mock Resources mResources;
    @Mock ConnectivityResources mConnectivityResources;
    @Mock WifiManager mWifiManager;

    private ThreadNetworkCountryCode mThreadNetworkCountryCode;
    private boolean mErrorSetCountryCode;

    @Captor private ArgumentCaptor<LocationListener> mLocationListenerCaptor;
    @Captor private ArgumentCaptor<Geocoder.GeocodeListener> mGeocodeListenerCaptor;
    @Captor private ArgumentCaptor<IOperationReceiver> mOperationReceiverCaptor;
    @Captor private ArgumentCaptor<ActiveCountryCodeChangedCallback> mWifiCountryCodeReceiverCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mConnectivityResources.get()).thenReturn(mResources);
        when(mResources.getBoolean(anyInt())).thenReturn(true);

        when(mLocation.getLatitude()).thenReturn(0.0);
        when(mLocation.getLongitude()).thenReturn(0.0);

        Answer setCountryCodeCallback =
                invocation -> {
                    Object[] args = invocation.getArguments();
                    IOperationReceiver cb = (IOperationReceiver) args[1];

                    if (mErrorSetCountryCode) {
                        cb.onError(ERROR_INTERNAL_ERROR, new String("Invalid country code"));
                    } else {
                        cb.onSuccess();
                    }
                    return new Object();
                };

        doAnswer(setCountryCodeCallback)
                .when(mThreadNetworkControllerService)
                .setCountryCode(any(), any(IOperationReceiver.class));

        mThreadNetworkCountryCode =
                new ThreadNetworkCountryCode(
                        mLocationManager,
                        mThreadNetworkControllerService,
                        mGeocoder,
                        mConnectivityResources,
                        mWifiManager);
    }

    private static Address newAddress(String countryCode) {
        Address address = new Address(Locale.ROOT);
        address.setCountryCode(countryCode);
        return address;
    }

    @Test
    public void initialize_defaultCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(DEFAULT_COUNTRY_CODE);
    }

    @Test
    public void initialize_locationUseIsDisabled_locationFunctionIsNotCalled() {
        when(mResources.getBoolean(R.bool.config_thread_location_use_for_country_code_enabled))
                .thenReturn(false);

        mThreadNetworkCountryCode.initialize();

        verifyNoMoreInteractions(mGeocoder);
        verifyNoMoreInteractions(mLocationManager);
    }

    @Test
    public void locationCountryCode_locationChanged_locationCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();

        verify(mLocationManager)
                .requestLocationUpdates(
                        anyString(), anyLong(), anyFloat(), mLocationListenerCaptor.capture());
        mLocationListenerCaptor.getValue().onLocationChanged(mLocation);
        verify(mGeocoder)
                .getFromLocation(
                        anyDouble(), anyDouble(), anyInt(), mGeocodeListenerCaptor.capture());
        mGeocodeListenerCaptor.getValue().onGeocode(List.of(newAddress(TEST_COUNTRY_CODE_US)));

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_US);
    }

    @Test
    public void wifiCountryCode_bothWifiAndLocationAreAvailable_wifiCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();
        verify(mLocationManager)
                .requestLocationUpdates(
                        anyString(), anyLong(), anyFloat(), mLocationListenerCaptor.capture());
        mLocationListenerCaptor.getValue().onLocationChanged(mLocation);
        verify(mGeocoder)
                .getFromLocation(
                        anyDouble(), anyDouble(), anyInt(), mGeocodeListenerCaptor.capture());
        Address mockAddress = mock(Address.class);
        when(mockAddress.getCountryCode()).thenReturn(TEST_COUNTRY_CODE_US);
        List<Address> addresses = List.of(mockAddress);
        mGeocodeListenerCaptor.getValue().onGeocode(addresses);

        verify(mWifiManager)
                .registerActiveCountryCodeChangedCallback(
                        any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE_CN);

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_CN);
    }

    @Test
    public void wifiCountryCode_wifiCountryCodeIsActive_wifiCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();

        verify(mWifiManager)
                .registerActiveCountryCodeChangedCallback(
                        any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE_US);

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_US);
    }

    @Test
    public void wifiCountryCode_wifiCountryCodeIsInactive_defaultCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();
        verify(mWifiManager)
                .registerActiveCountryCodeChangedCallback(
                        any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE_US);

        mWifiCountryCodeReceiverCaptor.getValue().onCountryCodeInactive();

        assertThat(mThreadNetworkCountryCode.getCountryCode())
                .isEqualTo(ThreadNetworkCountryCode.DEFAULT_COUNTRY_CODE);
    }

    @Test
    public void updateCountryCode_noForceUpdateDefaultCountryCode_noCountryCodeIsUpdated() {
        mThreadNetworkCountryCode.initialize();
        clearInvocations(mThreadNetworkControllerService);

        mThreadNetworkCountryCode.updateCountryCode(false /* forceUpdate */);

        verify(mThreadNetworkControllerService, never()).setCountryCode(any(), any());
    }

    @Test
    public void updateCountryCode_forceUpdateDefaultCountryCode_countryCodeIsUpdated() {
        mThreadNetworkCountryCode.initialize();
        clearInvocations(mThreadNetworkControllerService);

        mThreadNetworkCountryCode.updateCountryCode(true /* forceUpdate */);

        verify(mThreadNetworkControllerService)
                .setCountryCode(eq(DEFAULT_COUNTRY_CODE), mOperationReceiverCaptor.capture());
    }

    @Test
    public void setOverrideCountryCode_defaultCountryCodeAvailable_overrideCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();

        mThreadNetworkCountryCode.setOverrideCountryCode(TEST_COUNTRY_CODE_CN);

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_CN);
    }

    @Test
    public void clearOverrideCountryCode_defaultCountryCodeAvailable_defaultCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();
        mThreadNetworkCountryCode.setOverrideCountryCode(TEST_COUNTRY_CODE_CN);

        mThreadNetworkCountryCode.clearOverrideCountryCode();

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(DEFAULT_COUNTRY_CODE);
    }

    @Test
    public void setCountryCodeFailed_defaultCountryCodeAvailable_countryCodeIsNotUpdated() {
        mThreadNetworkCountryCode.initialize();

        mErrorSetCountryCode = true;
        mThreadNetworkCountryCode.setOverrideCountryCode(TEST_COUNTRY_CODE_CN);

        verify(mThreadNetworkControllerService)
                .setCountryCode(eq(TEST_COUNTRY_CODE_CN), mOperationReceiverCaptor.capture());
        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(DEFAULT_COUNTRY_CODE);
    }
}
