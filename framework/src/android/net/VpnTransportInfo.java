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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.annotation.SystemApi.Client.PRIVILEGED_APPS;
import static android.net.NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.NetworkCapabilities.RedactionType;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;

import java.util.Objects;

/**
 * Container for VPN-specific transport information.
 *
 * @see android.net.TransportInfo
 * @see NetworkCapabilities#getTransportInfo()
 *
 * @hide
 */
@SystemApi(client = PRIVILEGED_APPS)
public final class VpnTransportInfo implements TransportInfo, Parcelable {
    /** Type of this VPN. */
    private final int mType;

    @Nullable
    private final String mSessionId;

    private final boolean mBypassable;

    // TODO: Refer to Build.VERSION_CODES when it's available in every branch.
    private static final int UPSIDE_DOWN_CAKE = 34;

    /** @hide */
    @SystemApi(client = MODULE_LIBRARIES)
    @Override
    public @RedactionType long getApplicableRedactions() {
        return REDACT_FOR_NETWORK_SETTINGS;
    }

    /**
     * Create a copy of a {@link VpnTransportInfo} with the sessionId redacted if necessary.
     * @hide
     */
    @NonNull
    @SystemApi(client = MODULE_LIBRARIES)
    public VpnTransportInfo makeCopy(@RedactionType long redactions) {
        return new VpnTransportInfo(mType,
            ((redactions & REDACT_FOR_NETWORK_SETTINGS) != 0) ? null : mSessionId, mBypassable);
    }

    /**
     * @deprecated please use {@link VpnTransportInfo(int,String,boolean)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi(client = MODULE_LIBRARIES)
    public VpnTransportInfo(int type, @Nullable String sessionId) {
        // When the module runs on older SDKs, |bypassable| will always be false since the old Vpn
        // code will call this constructor. For Settings VPNs, this is always correct as they are
        // never bypassable. For VpnManager and VpnService types, this may be wrong since both of
        // them have a choice. However, on these SDKs VpnTransportInfo#getBypassable is not
        // available anyway, so this should be harmless. False is a better choice than true here
        // regardless because it is the default value for both VpnManager and VpnService if the app
        // does not do anything about it.
        this(type, sessionId, false /* bypassable */);
    }

    public VpnTransportInfo(int type, @Nullable String sessionId, boolean bypassable) {
        this.mType = type;
        this.mSessionId = sessionId;
        this.mBypassable = bypassable;
    }

    /**
     * Returns whether the VPN is allowing bypass.
     *
     * This method is not supported in SDK below U, and will throw
     * {@code UnsupportedOperationException} if called.
     */
    @RequiresApi(UPSIDE_DOWN_CAKE)
    public boolean getBypassable() {
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException("Not supported before U");
        }

        return mBypassable;
    }

    /**
     * Returns the session Id of this VpnTransportInfo.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @Nullable
    public String getSessionId() {
        return mSessionId;
    }

    /**
     * Returns the type of this VPN.
     */
    public int getType() {
        return mType;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VpnTransportInfo)) return false;

        VpnTransportInfo that = (VpnTransportInfo) o;
        return (this.mType == that.mType) && TextUtils.equals(this.mSessionId, that.mSessionId)
                && (this.mBypassable == that.mBypassable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mSessionId, mBypassable);
    }

    @Override
    public String toString() {
        return String.format("VpnTransportInfo{type=%d, sessionId=%s, bypassable=%b}",
                mType, mSessionId, mBypassable);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeString(mSessionId);
        dest.writeBoolean(mBypassable);
    }

    public static final @NonNull Creator<VpnTransportInfo> CREATOR =
            new Creator<VpnTransportInfo>() {
        public VpnTransportInfo createFromParcel(Parcel in) {
            return new VpnTransportInfo(in.readInt(), in.readString(), in.readBoolean());
        }
        public VpnTransportInfo[] newArray(int size) {
            return new VpnTransportInfo[size];
        }
    };
}
