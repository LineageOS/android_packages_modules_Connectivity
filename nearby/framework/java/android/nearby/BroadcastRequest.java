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
import android.annotation.NonNull;
import android.annotation.SuppressLint;
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
@SuppressLint("ParcelNotFinal")  // BroadcastRequest constructor is not public
public abstract class BroadcastRequest implements Parcelable {

    /** Broadcast type for advertising using nearby presence protocol. */
    public static final int BROADCAST_TYPE_NEARBY_PRESENCE = 3;

    /**
     * Tx Power when the value is not set in the broadcast.
     */
    public static final int UNKNOWN_TX_POWER = -100;

    /** @hide **/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({BROADCAST_TYPE_NEARBY_PRESENCE})
    public @interface BroadcastType {
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
    private final int mTxPower;
    private final List<Integer> mMediums;

    BroadcastRequest(@BroadcastType int type, int txPower, List<Integer> mediums) {
        this.mType = type;
        this.mTxPower = txPower;
        this.mMediums = mediums;
    }

    BroadcastRequest(@BroadcastType int type, Parcel in) {
        mType = type;
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
     * Returns the calibrated TX power when this request is broadcast.
     */
    public int getTxPower() {
        return mTxPower;
    }

    /**
     * Returns the list broadcast mediums.
     */
    @NonNull
    public List<Integer> getMediums() {
        return mMediums;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mTxPower);
        dest.writeList(mMediums);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
