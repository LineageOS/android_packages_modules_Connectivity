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

package android.nearby.multidevices.fastpair.seeker.dataprovider

import android.accounts.Account
import android.nearby.FastPairDataProviderBase
import android.nearby.FastPairEligibleAccount
import android.util.Log

class FastPairTestDataProvider : FastPairDataProviderBase(TAG) {

    override fun onLoadFastPairAntispoofKeyDeviceMetadata(
        request: FastPairAntispoofKeyDeviceMetadataRequest,
        callback: FastPairAntispoofKeyDeviceMetadataCallback
    ) {
        val requestedModelId = request.modelId.bytesToStringLowerCase()
        Log.d(TAG, "onLoadFastPairAntispoofKeyDeviceMetadata(modelId: $requestedModelId)")

        val fastPairAntispoofKeyDeviceMetadata =
            FastPairTestDataCache.antispoofKeyDeviceMetadataMap[requestedModelId]
        if (fastPairAntispoofKeyDeviceMetadata != null) {
            callback.onFastPairAntispoofKeyDeviceMetadataReceived(fastPairAntispoofKeyDeviceMetadata)
        } else {
            callback.onError(ERROR_CODE_BAD_REQUEST, "No metadata available for $requestedModelId")
        }
    }

    override fun onLoadFastPairAccountDevicesMetadata(
        request: FastPairAccountDevicesMetadataRequest,
        callback: FastPairAccountDevicesMetadataCallback
    ) {
        val requestedAccount = request.account
        val requestedAccountKeys = request.deviceAccountKeys
        Log.d(
            TAG, "onLoadFastPairAccountDevicesMetadata(" +
                    "account: $requestedAccount, accountKeys:$requestedAccountKeys)"
        )
        Log.d(TAG, FastPairTestDataCache.dumpAccountKeyDeviceMetadata())

        callback.onFastPairAccountDevicesMetadataReceived(
            FastPairTestDataCache.accountKeyDeviceMetadata
        )
    }

    override fun onLoadFastPairEligibleAccounts(
        request: FastPairEligibleAccountsRequest,
        callback: FastPairEligibleAccountsCallback
    ) {
        Log.d(TAG, "onLoadFastPairEligibleAccounts()")
        callback.onFastPairEligibleAccountsReceived(ELIGIBLE_ACCOUNTS_TEST_CONSTANT)
    }

    override fun onManageFastPairAccount(
        request: FastPairManageAccountRequest, callback: FastPairManageActionCallback
    ) {
        val requestedAccount = request.account
        val requestType = request.requestType
        Log.d(TAG, "onManageFastPairAccount(account: $requestedAccount, requestType: $requestType)")

        callback.onSuccess()
    }

    override fun onManageFastPairAccountDevice(
        request: FastPairManageAccountDeviceRequest, callback: FastPairManageActionCallback
    ) {
        val requestedAccount = request.account
        val requestType = request.requestType
        val requestTypeString = if (requestType == MANAGE_REQUEST_ADD) "Add" else "Remove"
        val requestedBleAddress = request.bleAddress
        val requestedAccountKeyDeviceMetadata = request.accountKeyDeviceMetadata
        Log.d(
            TAG,
            "onManageFastPairAccountDevice(requestedAccount: $requestedAccount, " +
                    "requestType: $requestTypeString,"
        )
        Log.d(TAG, "requestedBleAddress: $requestedBleAddress,")
        Log.d(TAG, "requestedAccountKeyDeviceMetadata: $requestedAccountKeyDeviceMetadata)")

        FastPairTestDataCache.accountKeyDeviceMetadata += requestedAccountKeyDeviceMetadata

        callback.onSuccess()
    }

    companion object {
        private const val TAG = "FastPairTestDataProvider"
        private val ELIGIBLE_ACCOUNTS_TEST_CONSTANT = listOf(
            FastPairEligibleAccount.Builder()
                .setAccount(Account("nearby-mainline-fpseeker@google.com", "FakeTestAccount"))
                .setOptIn(true)
                .build(),
        )

        private fun ByteArray.bytesToStringLowerCase(): String =
            joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
    }
}
