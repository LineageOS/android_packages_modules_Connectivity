/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a {@link BroadcastRequest}.
 *
 * @hide
 */
@SystemApi
@SuppressLint("ParcelNotFinal")  // BroadcastRequest constructor is not public
public abstract class BroadcastRequest implements Parcelable {

    /** Broadcast type for advertising using nearby presence protocol. */
    public static final int BROADCAST_TYPE_NEARBY_PRESENCE = 3;

    /** @hide **/
    // Currently, only Nearby Presence broadcast is supported, in the future
    // broadcasting using other nearby specifications will be added.
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({BROADCAST_TYPE_NEARBY_PRESENCE})
    public @interface BroadcastType {
    }

    /**
     * Tx Power when the value is not set in the broadcast.
     */
    public static final int UNKNOWN_TX_POWER = -100;

    /**
     * V0 of Nearby Presence Protocol.
     */
    public static final int PRESENCE_VERSION_V0 = 0;

    /**
     * V1 of Nearby Presence Protocol.
     */
    public static final int PRESENCE_VERSION_V1 = 1;

    /** @hide **/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({PRESENCE_VERSION_V0, PRESENCE_VERSION_V1})
    public @interface BroadcastVersion {
    }

    public static final @NonNull Creator<BroadcastRequest> CREATOR =
            new Creator<BroadcastRequest>() {
                @Override
                public BroadcastRequest createFromParcel(Parcel in) {
                    int type = in.readInt();
                    switch (type) {
                        case BroadcastRequest.BROADCAST_TYPE_NEARBY_PRESENCE:
                            return PresenceBroadcastRequest.createFromParcelBody(in);
                        default:
                            throw new IllegalStateException(
                                    "Unexpected broadcast type (value " + type + ") in parcel.");
                    }
                }

                @Override
                public BroadcastRequest[] newArray(int size) {
                    return new BroadcastRequest[size];
                }
            };

    private final @BroadcastType int mType;
    private final @BroadcastVersion int mVersion;
    private final int mTxPower;
    private final List<Integer> mMediums;

    BroadcastRequest(@BroadcastType int type, @BroadcastVersion int version, int txPower,
            List<Integer> mediums) {
        this.mType = type;
        this.mVersion = version;
        this.mTxPower = txPower;
        this.mMediums = mediums;
    }

    BroadcastRequest(@BroadcastType int type, Parcel in) {
        mType = type;
        mVersion = in.readInt();
        mTxPower = in.readInt();
        mMediums = new ArrayList<>();
        in.readList(mMediums, Integer.class.getClassLoader(), Integer.class);
    }

    /**
     * Returns the type of the broadcast.
     */
    public @BroadcastType int getType() {
        return mType;
    }

    /**
     * Returns the version of the broadcast.
     */
    public @BroadcastVersion int getVersion() {
        return mVersion;
    }

    /**
     * Returns the calibrated TX power when this request is broadcast.
     */
    @IntRange(from = -127, to = 126)
    public int getTxPower() {
        return mTxPower;
    }

    /**
     * Returns the list of broadcast mediums.
     */
    @NonNull
    public List<Integer> getMediums() {
        return mMediums;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mVersion);
        dest.writeInt(mTxPower);
        dest.writeList(mMediums);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
