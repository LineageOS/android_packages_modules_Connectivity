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

package android.nearby;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.WorkSource;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * An encapsulation of various parameters for requesting nearby scans.
 *
 * @hide
 */
public final class ScanRequest implements Parcelable {

    /** @hide **/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SCAN_TYPE_FAST_PAIR, SCAN_TYPE_NEARBY_SHARE, SCAN_TYPE_NEARBY_PRESENCE,
            SCAN_TYPE_EXPOSURE_NOTIFICATION})
    public @interface ScanType{}

    /** Scan type for scanning devices using fast pair protocol. */
    public static final int SCAN_TYPE_FAST_PAIR = 1;
    /** Scan type for scanning devices using nearby share protocol. */
    public static final int SCAN_TYPE_NEARBY_SHARE = 2;
    /** Scan type for scanning devices using nearby presence protocol. */
    public static final int SCAN_TYPE_NEARBY_PRESENCE = 3;
    /** Scan type for scanning devices using exposure notification protocol. */
    public static final int SCAN_TYPE_EXPOSURE_NOTIFICATION = 4;

    private final @ScanType int mScanType;
    private final @Nullable WorkSource mWorkSource;

    private ScanRequest(@ScanType int scanType, @Nullable WorkSource workSource) {
        mScanType = scanType;
        mWorkSource = workSource;
    }

    /**
     * Returns the scan type for this request.
     */
    public @ScanType int getScanType() {
        return mScanType;
    }

    /**
     * Returns the work source used for power attribution of this request.
     *
     * @hide
     */
    public @Nullable WorkSource getWorkSource() {
        return mWorkSource;
    }

    public static final Creator<ScanRequest> CREATOR = new Creator<ScanRequest>() {
        @Override
        public ScanRequest createFromParcel(Parcel in) {
            return new ScanRequest(
                    /* scanType= */ in.readInt(),
                    /* workSource= */ in.readTypedObject(WorkSource.CREATOR));
        }

        @Override
        public ScanRequest[] newArray(int size) {
            return new ScanRequest[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Request[")
                .append("scanType=").append(mScanType);
        if (mWorkSource != null && !mWorkSource.isEmpty()) {
            stringBuilder.append(", workSource=").append(mWorkSource);
        }
        stringBuilder.append("]");
        return stringBuilder.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mScanType);
        dest.writeTypedObject(mWorkSource, /* parcelableFlags= */0);
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof ScanRequest) {
            ScanRequest otherRequest = (ScanRequest) other;
            return mScanType == otherRequest.mScanType
                    && Objects.equals(mWorkSource, otherRequest.mWorkSource);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mScanType, mWorkSource);
    }

    /** A builder class for {@link ScanRequest}. */
    public static final class Builder {
        private static final int INVALID_SCAN_TYPE = -1;
        private @ScanType int mScanType;
        private @Nullable WorkSource mWorkSource;

        /** Creates a new Builder with the given scan type. */
        public Builder() {
            mScanType = INVALID_SCAN_TYPE;
            mWorkSource = null;
        }

        /**
         * Sets the scan type for the request. The scan type must be one of the SCAN_TYPE_ constants
         * in {@link ScanRequest}.
         */
        public Builder setScanType(@ScanType int scanType) {
            mScanType = scanType;
            return this;
        }
        /**
         * Sets the work source to use for power attribution for this scan request. Defaults to
         * empty work source, which implies the caller that sends the scan request will be used
         * for power attribution.
         *
         * <p>Permission enforcement occurs when the resulting scan request is used, not when
         * this method is invoked.
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.UPDATE_DEVICE_STATS)
        public @NonNull Builder setWorkSource(@Nullable WorkSource workSource) {
            this.mWorkSource = workSource;
            return this;
        }

        /**
         * Builds a scan request from this builder.
         *
         * @throws IllegalStateException if the scanType is not one of the SCAN_TYPE_ constants in
         *         {@link ScanRequest}.
         * @return a new nearby scan request.
         */
        public @NonNull ScanRequest build() {
            Preconditions.checkState(isValidScanType(mScanType),
                    "invalid scan type : " + mScanType
                            + ", scan type must be one of ScanRequest#SCAN_TYPE_");
            return new ScanRequest(mScanType, mWorkSource);
        }

        private static boolean isValidScanType(int scanType) {
            return scanType == SCAN_TYPE_FAST_PAIR
                    || scanType == SCAN_TYPE_NEARBY_SHARE
                    || scanType == SCAN_TYPE_NEARBY_PRESENCE
                    || scanType == SCAN_TYPE_EXPOSURE_NOTIFICATION;
        }
    }
}
