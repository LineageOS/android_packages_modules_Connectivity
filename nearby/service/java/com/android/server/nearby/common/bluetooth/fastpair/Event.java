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

import android.bluetooth.BluetoothDevice;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.server.nearby.intdefs.NearbyEventIntDefs.EventCode;

import com.google.auto.value.AutoValue;

import javax.annotation.Nullable;

/**
 * Describes events that are happening during fast pairing. EventCode is required, everything else
 * is optional.
 */
@AutoValue
public abstract class Event implements Parcelable {

    /**
     * Returns event code.
     */
    public abstract @EventCode int getEventCode();

    /**
     * Returns timestamp.
     */
    public abstract long getTimestamp();

    /**
     * Returns profile.
     */
    @Nullable
    public abstract Short getProfile();

    /**
     * Returns Bluetooth device.
     */
    @Nullable
    public abstract BluetoothDevice getBluetoothDevice();

    /**
     * Returns exception.
     */
    @Nullable
    public abstract Exception getException();

    /**
     * Returns whether profile is not null.
     */
    public boolean hasProfile() {
        return getProfile() != null;
    }

    /**
     * Returns whether Bluetooth device is not null.
     */
    public boolean hasBluetoothDevice() {
        return getBluetoothDevice() != null;
    }

    /**
     * Returns a builder.
     */
    public static Builder builder() {
        return new AutoValue_Event.Builder();
    }

    /**
     * Returns whether it fails.
     */
    public boolean isFailure() {
        return getException() != null;
    }

    /**
     * Builder
     */
    @AutoValue.Builder
    public abstract static class Builder {

        /**
         * Set event code.
         */
        public abstract Builder setEventCode(@EventCode int eventCode);

        /**
         * Set timestamp.
         */
        public abstract Builder setTimestamp(long startTimestamp);

        /**
         * Set profile.
         */
        public abstract Builder setProfile(@Nullable Short profile);

        /**
         * Set Bluetooth device.
         */
        public abstract Builder setBluetoothDevice(@Nullable BluetoothDevice device);

        /**
         * Set exception.
         */
        public abstract Builder setException(@Nullable Exception exception);

        /**
         * Builds event.
         */
        public abstract Event build();
    }

    @Override
    public final void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(getEventCode());
        dest.writeLong(getTimestamp());
        dest.writeValue(getProfile());
        dest.writeParcelable(getBluetoothDevice(), 0);
        dest.writeSerializable(getException());
    }

    @Override
    public final int describeContents() {
        return 0;
    }

    /**
     * Event Creator instance.
     */
    public static final Creator<Event> CREATOR =
            new Creator<Event>() {
                @Override
                /** Creates Event from Parcel. */
                public Event createFromParcel(Parcel in) {
                    return Event.builder()
                            .setEventCode(in.readInt())
                            .setTimestamp(in.readLong())
                            .setProfile((Short) in.readValue(Short.class.getClassLoader()))
                            .setBluetoothDevice(
                                    in.readParcelable(BluetoothDevice.class.getClassLoader()))
                            .setException((Exception) in.readSerializable())
                            .build();
                }

                @Override
                /** Returns Event array. */
                public Event[] newArray(int size) {
                    return new Event[size];
                }
            };
}
