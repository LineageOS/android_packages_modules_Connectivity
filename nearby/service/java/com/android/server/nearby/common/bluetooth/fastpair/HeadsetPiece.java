/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.nearby.common.bluetooth.fastpair;

import android.annotation.TargetApi;
import android.net.Uri;
import android.os.Build.VERSION_CODES;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.auto.value.AutoValue;

import java.util.Arrays;
import java.util.Objects;

/**
 * This class is subclass of real headset. It contains image url, battery value and charging
 * status.
 */
// Objects.equals is only available after KitKat.
@TargetApi(VERSION_CODES.KITKAT)
@AutoValue
public abstract class HeadsetPiece implements Parcelable {

    /**
     * The low level threshold.
     */
    public abstract int lowLevelThreshold();

    /**
     * The battery level.
     */
    public abstract int batteryLevel();

    /**
     * The web URL of the image.
     */
    public abstract String imageUrl();

    /**
     * Whether the headset is charging.
     */
    public abstract boolean charging();

    /**
     * The content Uri of the image if it could be downloaded from the web URL and generated through
     * {@link FileProvider#getUriForFile} successfully, otherwise null.
     */
    @Nullable
    public abstract Uri imageContentUri();

    /**
     * Returns a builder of HeadsetPiece.
     */
    public static HeadsetPiece.Builder builder() {
        return new AutoValue_HeadsetPiece.Builder();
    }

    HeadsetPiece() {
    }

    /**
     * @return whether battery is low or not.
     */
    public boolean isBatteryLow() {
        return batteryLevel() <= lowLevelThreshold() && batteryLevel() >= 0 && !charging();
    }

    /**
     * Builder function for headset piece.
     */
    @AutoValue.Builder
    public abstract static class Builder {

        /**
         * Set low level threshold.
         */
        public abstract HeadsetPiece.Builder setLowLevelThreshold(int lowLevelThreshold);

        /**
         * Set battery level.
         */
        public abstract HeadsetPiece.Builder setBatteryLevel(int level);

        /**
         * Set image url.
         */
        public abstract HeadsetPiece.Builder setImageUrl(String url);

        /**
         * Set charging.
         */
        public abstract HeadsetPiece.Builder setCharging(boolean charging);

        /**
         * Set image content Uri.
         */
        public abstract HeadsetPiece.Builder setImageContentUri(Uri uri);

        /**
         * Builds HeadSetPiece.
         */
        public abstract HeadsetPiece build();
    }

    @Override
    public final void writeToParcel(Parcel dest, int flags) {
        dest.writeString(imageUrl());
        dest.writeInt(lowLevelThreshold());
        dest.writeInt(batteryLevel());
        // Writes 1 if charging, otherwise 0.
        dest.writeByte((byte) (charging() ? 1 : 0));
        dest.writeParcelable(imageContentUri(), flags);
    }

    @Override
    public final int describeContents() {
        return 0;
    }

    public static final Creator<HeadsetPiece> CREATOR =
            new Creator<HeadsetPiece>() {
                @Override
                public HeadsetPiece createFromParcel(Parcel in) {
                    String imageUrl = in.readString();
                    return HeadsetPiece.builder()
                            .setImageUrl(imageUrl != null ? imageUrl : "")
                            .setLowLevelThreshold(in.readInt())
                            .setBatteryLevel(in.readInt())
                            .setCharging(in.readByte() != 0)
                            .setImageContentUri(in.readParcelable(Uri.class.getClassLoader()))
                            .build();
                }

                @Override
                public HeadsetPiece[] newArray(int size) {
                    return new HeadsetPiece[size];
                }
            };

    @Override
    public final int hashCode() {
        return Arrays.hashCode(
                new Object[]{
                        lowLevelThreshold(), batteryLevel(), imageUrl(), charging(),
                        imageContentUri()
                });
    }

    @Override
    public final boolean equals(@Nullable Object other) {
        if (other == null) {
            return false;
        }

        if (this == other) {
            return true;
        }

        if (!(other instanceof HeadsetPiece)) {
            return false;
        }

        HeadsetPiece that = (HeadsetPiece) other;
        return lowLevelThreshold() == that.lowLevelThreshold()
                && batteryLevel() == that.batteryLevel()
                && Objects.equals(imageUrl(), that.imageUrl())
                && charging() == that.charging()
                && Objects.equals(imageContentUri(), that.imageContentUri());
    }
}
