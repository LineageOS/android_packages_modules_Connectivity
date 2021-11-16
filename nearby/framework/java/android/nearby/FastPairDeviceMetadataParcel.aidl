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

package android.nearby;

/**
 * Configuration details for requesting tethering.
 * @hide
 */
parcelable FastPairDeviceMetadataParcel {

    // The image to show on the notification.
    String imageUrl;

    // The intent that will be launched via the notification.
    String intentUri;

    // Anti spoof public key;
    byte[] antiSpoofPublicKey;

    // The transmit power of the device's BLE chip.
    int bleTxPower;

    // The distance that the device must be within to show a notification.
    // If no distance is set, we default to 0.6 meters. Only Nearby admins can
    // change this.
    float triggerDistance;

    // The image icon that shows in the notification.
    byte[] image;

    int deviceType;

    // The image for device with device type "true wireless".
    byte[] trueWirelessImageUrlLeftBud;
    byte[] trueWirelessImageUrlRightBud;
    byte[] trueWirelessImageUrlCase;
}