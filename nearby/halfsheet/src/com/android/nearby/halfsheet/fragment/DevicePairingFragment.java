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
package com.android.nearby.halfsheet.fragment;

import static android.text.TextUtils.isEmpty;

import static com.android.nearby.halfsheet.HalfSheetActivity.ACTION_HALF_SHEET_STATUS_CHANGE;
import static com.android.nearby.halfsheet.HalfSheetActivity.ARG_FRAGMENT_STATE;
import static com.android.nearby.halfsheet.HalfSheetActivity.EXTRA_CLASSIC_MAC_ADDRESS;
import static com.android.nearby.halfsheet.HalfSheetActivity.EXTRA_DESCRIPTION;
import static com.android.nearby.halfsheet.HalfSheetActivity.EXTRA_HALF_SHEET_ACCOUNT_NAME;
import static com.android.nearby.halfsheet.HalfSheetActivity.EXTRA_HALF_SHEET_CONTENT;
import static com.android.nearby.halfsheet.HalfSheetActivity.EXTRA_HALF_SHEET_ID;
import static com.android.nearby.halfsheet.HalfSheetActivity.EXTRA_HALF_SHEET_IS_RETROACTIVE;
import static com.android.nearby.halfsheet.HalfSheetActivity.EXTRA_HALF_SHEET_IS_SUBSEQUENT_PAIR;
import static com.android.nearby.halfsheet.HalfSheetActivity.EXTRA_HALF_SHEET_PAIRING_RESURFACE;
import static com.android.nearby.halfsheet.HalfSheetActivity.EXTRA_TITLE;
import static com.android.nearby.halfsheet.HalfSheetActivity.FINISHED_STATE;
import static com.android.nearby.halfsheet.fragment.HalfSheetModuleFragment.HalfSheetFragmentState.NOT_STARTED;
import static com.android.nearby.halfsheet.fragment.HalfSheetModuleFragment.HalfSheetFragmentState.RESULT_FAILURE;
import static com.android.server.nearby.common.bluetooth.fastpair.FastPairConstants.EXTRA_MODEL_ID;
import static com.android.server.nearby.fastpair.Constant.DISMISS;
import static com.android.server.nearby.fastpair.Constant.EXTRA_BINDER;
import static com.android.server.nearby.fastpair.Constant.EXTRA_BUNDLE;
import static com.android.server.nearby.fastpair.Constant.EXTRA_HALF_SHEET_INFO;
import static com.android.server.nearby.fastpair.Constant.FAIL_STATE;
import static com.android.server.nearby.fastpair.Constant.SUCCESS_STATE;
import static com.android.server.nearby.fastpair.UserActionHandler.ACTION_FAST_PAIR;
import static com.android.server.nearby.fastpair.UserActionHandler.EXTRA_PRIVATE_BLE_ADDRESS;

import android.animation.AnimatorSet;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.nearby.IFastPairHalfSheetCallback;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.nearby.halfsheet.HalfSheetActivity;
import com.android.nearby.halfsheet.R;
import com.android.nearby.halfsheet.utils.BroadcastUtils;
import com.android.nearby.halfsheet.utils.FastPairUtils;
import com.android.server.nearby.fastpair.UserActionHandler;

import com.google.protobuf.InvalidProtocolBufferException;

import service.proto.Cache.ScanFastPairStoreItem;

/**
 * Modularize half sheet for fast pair this fragment will show when half sheet does device pairing.
 *
 * <p>This fragment will handle initial pairing subsequent pairing and retroactive pairing.
 */
@SuppressWarnings("nullness")
public class DevicePairingFragment extends HalfSheetModuleFragment {
    private Button mConnectButton;
    private Button mSetupButton;
    private Button mCancelButton;
    private ImageView mInfoIconButton;
    private ProgressBar mConnectProgressBar;
    private View mRootView;
    private TextView mSubTitle;
    private TextView mTitle;
    private ImageView mImage;
    private ScanFastPairStoreItem mScanFastPairStoreItem;
    // This open companion app intent will be triggered after user finish Fast Pair.
    private Intent mOpenCompanionAppIntent;
    // Indicates that the setup button is clicked before.
    private boolean mSetupButtonClicked = false;
    private boolean mIsSubsequentPair = false;
    private boolean mIsPairingResurface = false;
    private String mBluetoothMacAddress = "";
    private HalfSheetFragmentState mFragmentState = NOT_STARTED;
    private AnimatorSet mAnimatorSet = new AnimatorSet();
    // True means pairing was successful and false means failed.
    private Boolean mPairingResult = false;
    private Bundle mBundle;

    public static final String APP_LAUNCH_FRAGMENT_TYPE = "APP_LAUNCH";
    public static final String FAST_PAIR_CONSENT_FRAGMENT_TYPE = "FAST_PAIR_CONSENT";
    private static final String ARG_SETUP_BUTTON_CLICKED = "SETUP_BUTTON_CLICKED";
    public static final String RESULT_FAIL = "RESULT_FAIL";
    private static final String ARG_PAIRING_RESULT = "PAIRING_RESULT";


    /**
     * Create certain fragment according to the intent.
     */
    @Nullable
    public static HalfSheetModuleFragment newInstance(
            Intent intent, @Nullable Bundle saveInstanceStates) {
        Bundle args = new Bundle();
        byte[] infoArray = intent.getByteArrayExtra(EXTRA_HALF_SHEET_INFO);
        boolean isRetroactive = intent.getBooleanExtra(EXTRA_HALF_SHEET_IS_RETROACTIVE, false);
        boolean isSubsequentPair = intent.getBooleanExtra(EXTRA_HALF_SHEET_IS_SUBSEQUENT_PAIR,
                false);
        boolean isPairingResurface = intent.getBooleanExtra(EXTRA_HALF_SHEET_PAIRING_RESURFACE,
                false);
        Bundle bundle = intent.getBundleExtra(EXTRA_BUNDLE);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String description = intent.getStringExtra(EXTRA_DESCRIPTION);
        String accountName = intent.getStringExtra(EXTRA_HALF_SHEET_ACCOUNT_NAME);
        String result = intent.getStringExtra(EXTRA_HALF_SHEET_CONTENT);
        String publicAddress = intent.getStringExtra(EXTRA_CLASSIC_MAC_ADDRESS);
        int halfSheetId = intent.getIntExtra(EXTRA_HALF_SHEET_ID, 0);

        args.putByteArray(EXTRA_HALF_SHEET_INFO, infoArray);
        args.putBoolean(EXTRA_HALF_SHEET_IS_RETROACTIVE, isRetroactive);
        args.putBoolean(EXTRA_HALF_SHEET_IS_SUBSEQUENT_PAIR, isSubsequentPair);
        args.putBoolean(EXTRA_HALF_SHEET_PAIRING_RESURFACE, isPairingResurface);
        args.putString(EXTRA_HALF_SHEET_ACCOUNT_NAME, accountName);
        args.putString(EXTRA_TITLE, title);
        args.putString(EXTRA_DESCRIPTION, description);
        args.putInt(EXTRA_HALF_SHEET_ID, halfSheetId);
        args.putString(EXTRA_HALF_SHEET_CONTENT, result == null ? "" : result);
        args.putBundle(EXTRA_BUNDLE, bundle);
        if (saveInstanceStates != null) {
            if (saveInstanceStates.containsKey(ARG_FRAGMENT_STATE)) {
                args.putSerializable(
                        ARG_FRAGMENT_STATE, saveInstanceStates.getSerializable(ARG_FRAGMENT_STATE));
            }
            if (saveInstanceStates.containsKey(BluetoothDevice.EXTRA_DEVICE)) {
                args.putParcelable(
                        BluetoothDevice.EXTRA_DEVICE,
                        saveInstanceStates.getParcelable(BluetoothDevice.EXTRA_DEVICE));
            }
            if (saveInstanceStates.containsKey(BluetoothDevice.EXTRA_PAIRING_KEY)) {
                args.putInt(
                        BluetoothDevice.EXTRA_PAIRING_KEY,
                        saveInstanceStates.getInt(BluetoothDevice.EXTRA_PAIRING_KEY));
            }
            if (saveInstanceStates.containsKey(ARG_SETUP_BUTTON_CLICKED)) {
                args.putBoolean(
                        ARG_SETUP_BUTTON_CLICKED,
                        saveInstanceStates.getBoolean(ARG_SETUP_BUTTON_CLICKED));
            }
            if (saveInstanceStates.containsKey(ARG_PAIRING_RESULT)) {
                args.putBoolean(ARG_PAIRING_RESULT,
                        saveInstanceStates.getBoolean(ARG_PAIRING_RESULT));
            }
        }
        DevicePairingFragment fragment = new DevicePairingFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(
            LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        mRootView =
                inflater.inflate(
                        R.layout.fast_pair_device_pairing_fragment, container, /* attachToRoot= */
                        false);
        if (getContext() == null) {
            Log.d("DevicePairingFragment", "can't find the attached activity");
            return mRootView;
        }
        Bundle args = getArguments();
        byte[] storeFastPairItemBytesArray = args.getByteArray(EXTRA_HALF_SHEET_INFO);
        boolean isRetroactive = args.getBoolean(EXTRA_HALF_SHEET_IS_RETROACTIVE);
        mIsSubsequentPair = args.getBoolean(EXTRA_HALF_SHEET_IS_SUBSEQUENT_PAIR);
        mIsPairingResurface = args.getBoolean(EXTRA_HALF_SHEET_PAIRING_RESURFACE);
        String accountName = args.getString(EXTRA_HALF_SHEET_ACCOUNT_NAME);
        mBundle = args.getBundle(EXTRA_BUNDLE);
        if (args.containsKey(ARG_FRAGMENT_STATE)) {
            mFragmentState = (HalfSheetFragmentState) args.getSerializable(ARG_FRAGMENT_STATE);
        }
        if (args.containsKey(ARG_SETUP_BUTTON_CLICKED)) {
            mSetupButtonClicked = args.getBoolean(ARG_SETUP_BUTTON_CLICKED);
        }
        if (args.containsKey(ARG_PAIRING_RESULT)) {
            mPairingResult = args.getBoolean(ARG_PAIRING_RESULT);
        } else {
            mPairingResult = false;
        }

        // title = ((FragmentActivity) getContext()).findViewById(R.id.toolbar_title);
        mConnectButton = mRootView.findViewById(R.id.connect_btn);
        mImage = mRootView.findViewById(R.id.pairing_pic);

        mConnectProgressBar = mRootView.findViewById(R.id.connect_progressbar);
        mConnectProgressBar.setVisibility(View.INVISIBLE);

        DisplayMetrics displayMetrics = getContext().getResources().getDisplayMetrics();
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mRootView.getLayoutParams().height = displayMetrics.heightPixels * 4 / 5;
            mRootView.getLayoutParams().width = displayMetrics.heightPixels * 4 / 5;
            mImage.getLayoutParams().height = displayMetrics.heightPixels / 2;
            mImage.getLayoutParams().width = displayMetrics.heightPixels / 2;
            mConnectProgressBar.getLayoutParams().width = displayMetrics.heightPixels / 2;
            mConnectButton.getLayoutParams().width = displayMetrics.heightPixels / 2;
        }

        mCancelButton = mRootView.findViewById(R.id.cancel_btn);
        mSetupButton = mRootView.findViewById(R.id.setup_btn);
        mInfoIconButton = mRootView.findViewById(R.id.info_icon);
        mSubTitle = mRootView.findViewById(R.id.header_subtitle);
        mSetupButton.setVisibility(View.GONE);
        mInfoIconButton.setVisibility(View.GONE);

        try {
            if (storeFastPairItemBytesArray != null) {
                mScanFastPairStoreItem =
                        ScanFastPairStoreItem.parseFrom(storeFastPairItemBytesArray);
            }

            // If the fragmentState is not NOT_STARTED, it is because the fragment was just
            // resumed from
            // configuration change (e.g. rotating the screen or half-sheet resurface). Let's
            // recover the
            // UI directly.
            if (mFragmentState != NOT_STARTED) {
                switch (mFragmentState) {
                    case PAIRING:
                        Log.d("DevicePairingFragment", "redraw for PAIRING state.");
                        return mRootView;
                    case RESULT_SUCCESS:
                        Log.d("DevicePairingFragment", "redraw for RESULT_SUCCESS state.");
                        return mRootView;
                    case RESULT_FAILURE:
                        Log.d("DevicePairingFragment", "redraw for RESULT_FAILURE state.");
                        return mRootView;
                    default:
                        // fall-out
                        Log.d("DevicePairingFragment",
                                "DevicePairingFragment: not supported state");
                }
            }
            if (mIsPairingResurface) {
                // Since the Settings contextual card has sent the pairing intent, we don't send the
                // pairing intent here.
                onConnectClick(/* sendPairingIntent= */ false);
            } else {
                mSubTitle.setText(this.getArguments().getString(EXTRA_DESCRIPTION));
                mSubTitle.setText("");
                mConnectButton.setOnClickListener(
                        v -> onConnectClick(/* sendPairingIntent= */ true));
                // Pairing fail half sheet resurface
                if (this.getArguments().getString(EXTRA_HALF_SHEET_CONTENT).equals(RESULT_FAIL)) {
                    mFragmentState = RESULT_FAILURE;
                    showFailInfo();
                } else {
                    mConnectButton.setOnClickListener(
                            v -> onConnectClick(/* sendPairingIntent= */ true));
                }
            }
        } catch (InvalidProtocolBufferException e) {
            Log.w("DevicePairingFragment",
                    "DevicePairingFragment: error happens when pass info to half sheet");
        }
        return mRootView;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Get access to the activity's menu
        setHasOptionsMenu(true);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getContext() != null) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_HALF_SHEET_STATUS_CHANGE);
            BroadcastUtils.registerReceiver(getContext(), mHalfSheetChangeReceiver, intentFilter);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (getContext() != null) {
            BroadcastUtils.unregisterReceiver(getContext(), mHalfSheetChangeReceiver);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);

        savedInstanceState.putSerializable(ARG_FRAGMENT_STATE, mFragmentState);
        savedInstanceState.putBoolean(ARG_SETUP_BUTTON_CLICKED, mSetupButtonClicked);
        savedInstanceState.putBoolean(ARG_PAIRING_RESULT, mPairingResult);


    }

    @Nullable
    private Intent createCompletionIntent(@Nullable String companionApp, @Nullable String address) {
        if (isEmpty(companionApp)) {
            return null;
        } else if (FastPairUtils.isAppInstalled(companionApp, getContext())
                && isLaunchable(companionApp)) {
            mOpenCompanionAppIntent = createCompanionAppIntent(companionApp, address);
            return mOpenCompanionAppIntent;
        } else {
            return null;
        }
    }

    @Nullable
    private Intent createCompanionAppIntent(String packageName, @Nullable String address) {
        return createCompanionAppIntent(getContext(), packageName, address);
    }

    @Nullable
    private static Intent createCompanionAppIntent(
            Context context, String packageName, @Nullable String address) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (address != null && manager != null) {
            BluetoothAdapter adapter = manager.getAdapter();
            if (intent != null && adapter != null) {
                intent.putExtra(BluetoothDevice.EXTRA_DEVICE, adapter.getRemoteDevice(address));
            }
        }
        return intent;
    }

    private void onConnectClick(boolean sendPairingIntent) {
        if (mScanFastPairStoreItem == null) {
            Log.w("DevicePairingFragment", "No pairing related information in half sheet");
            return;
        }

        Log.d("FastPairHalfSheet", "on connect click");
        // Allow user to setup device before connection setup.
        // showPairingLastPhase();
        ((Activity) getContext())
                .findViewById(R.id.background)
                .setOnClickListener(
                        v ->
                                Log.d("DevicePairingFragment",
                                        "DevicePairingFragment: tap empty area do not dismiss "
                                                + "half sheet when pairing."));
        if (sendPairingIntent) {
            try {
                Log.d("FastPairHalfSheet", "on connect click");
                Intent intent =
                        new Intent(ACTION_FAST_PAIR)
                                // Using the DiscoveryChimeraService notification id for
                                // backwards compat
                                .putExtra(
                                        UserActionHandler.EXTRA_DISCOVERY_ITEM,
                                        FastPairUtils.convertFrom(
                                                mScanFastPairStoreItem).toByteArray())
                                .putExtra(EXTRA_MODEL_ID, mScanFastPairStoreItem.getModelId())
                                .putExtra(EXTRA_PRIVATE_BLE_ADDRESS,
                                        mScanFastPairStoreItem.getAddress());
                IFastPairHalfSheetCallback.Stub.asInterface(mBundle.getBinder(EXTRA_BINDER))
                        .onHalfSheetConnectionConfirm(intent);
            } catch (RemoteException e) {
                Log.d("FastPairHalfSheet", "invoke callback fall");
            }
        }
    }

    private void onFailConnectClick() {
        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private final BroadcastReceiver mHalfSheetChangeReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!ACTION_HALF_SHEET_STATUS_CHANGE.equals(intent.getAction())) {
                        return;
                    }
                    if (SUCCESS_STATE.equals(intent.getStringExtra(FINISHED_STATE))) {
                        mBluetoothMacAddress = intent.getStringExtra(EXTRA_CLASSIC_MAC_ADDRESS);
                        showSuccessInfo();
                        if (mOpenCompanionAppIntent != null) {
                            //((HalfSheetActivity) getContext()).halfSheetStateChange();
                            String companionApp =
                                    FastPairUtils.getCompanionAppFromActionUrl(
                                            mScanFastPairStoreItem.getActionUrl());
                            // Redirect user to companion app if user choose to setup the app.
                            // Recreate the intent
                            // since the correct mac address just populated.
                            startActivity(
                                    createCompletionIntent(companionApp, mBluetoothMacAddress));
                        }
                    } else if (FAIL_STATE.equals(intent.getStringExtra(FINISHED_STATE))) {
                        showFailInfo();
                    } else if (DISMISS.equals(intent.getStringExtra(FINISHED_STATE))) {
                        if (getContext() != null) {
                            HalfSheetActivity activity = (HalfSheetActivity) getContext();
                            activity.finish();
                        }
                    }
                }
            };

    private boolean isLaunchable(String companionApp) {
        return createCompanionAppIntent(companionApp, null) != null;
    }
}
