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

package com.android.server.net.ct;

import static android.security.Flags.certificateTransparencyConfiguration;

import static com.android.net.ct.flags.Flags.certificateTransparencyService;

import android.annotation.RequiresApi;
import android.content.Context;
import android.net.ct.ICertificateTransparencyManager;
import android.os.Build;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.util.Log;

import com.android.server.SystemService;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Executors;

/** Implementation of the Certificate Transparency service. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class CertificateTransparencyService extends ICertificateTransparencyManager.Stub
        implements DeviceConfig.OnPropertiesChangedListener {

    private static final String TAG = "CertificateTransparencyService";

    private final CertificateTransparencyJob mCertificateTransparencyJob;

    /**
     * @return true if the CertificateTransparency service is enabled.
     */
    public static boolean enabled(Context context) {
        return certificateTransparencyService() && certificateTransparencyConfiguration();
    }

    /** Creates a new {@link CertificateTransparencyService} object. */
    public CertificateTransparencyService(Context context) {
        SignatureVerifier signatureVerifier = new SignatureVerifier(context);
        Collection<CompatibilityVersion> compatVersions =
                Arrays.asList(
                        new CompatibilityVersion(
                                Config.COMPATIBILITY_VERSION_V2,
                                Config.URL_SIGNATURE_V2,
                                Config.URL_LOG_LIST_V2));

        mCertificateTransparencyJob =
                new CertificateTransparencyJob(
                        context,
                        new CertificateTransparencyDownloader(
                                context,
                                new DownloadHelper(context),
                                signatureVerifier,
                                new CertificateTransparencyLoggerImpl(),
                                compatVersions),
                        signatureVerifier,
                        compatVersions);
    }

    /**
     * Called by {@link com.android.server.ConnectivityServiceInitializer}.
     *
     * @see com.android.server.SystemService#onBootPhase
     */
    public void onBootPhase(int phase) {
        switch (phase) {
            case SystemService.PHASE_BOOT_COMPLETED:
                DeviceConfig.addOnPropertiesChangedListener(
                        Config.NAMESPACE_NETWORK_SECURITY,
                        Executors.newSingleThreadExecutor(),
                        this);
                onPropertiesChanged(
                        new Properties.Builder(Config.NAMESPACE_NETWORK_SECURITY).build());
                break;
            default:
        }
    }

    @Override
    public void onPropertiesChanged(Properties properties) {
        if (!Config.NAMESPACE_NETWORK_SECURITY.equals(properties.getNamespace())) {
            return;
        }

        if (DeviceConfig.getBoolean(
                Config.NAMESPACE_NETWORK_SECURITY,
                Config.FLAG_SERVICE_ENABLED,
                /* defaultValue= */ true)) {
            startService();
        } else {
            stopService();
        }
    }

    private void startService() {
        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyService start");
        }
        mCertificateTransparencyJob.schedule();
    }

    private void stopService() {
        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyService stop");
        }
        mCertificateTransparencyJob.cancel();
    }
}
