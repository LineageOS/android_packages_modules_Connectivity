/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.cts.netpolicy.hostside.app2;

import static android.net.ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED;

import static com.android.cts.netpolicy.hostside.app2.Common.ACTION_RECEIVER_READY;
import static com.android.cts.netpolicy.hostside.app2.Common.ACTION_SHOW_TOAST;
import static com.android.cts.netpolicy.hostside.app2.Common.ACTION_SNOOZE_WARNING;
import static com.android.cts.netpolicy.hostside.app2.Common.MANIFEST_RECEIVER;
import static com.android.cts.netpolicy.hostside.app2.Common.NOTIFICATION_TYPE_ACTION;
import static com.android.cts.netpolicy.hostside.app2.Common.NOTIFICATION_TYPE_ACTION_BUNDLE;
import static com.android.cts.netpolicy.hostside.app2.Common.NOTIFICATION_TYPE_ACTION_REMOTE_INPUT;
import static com.android.cts.netpolicy.hostside.app2.Common.NOTIFICATION_TYPE_BUNDLE;
import static com.android.cts.netpolicy.hostside.app2.Common.NOTIFICATION_TYPE_CONTENT;
import static com.android.cts.netpolicy.hostside.app2.Common.NOTIFICATION_TYPE_DELETE;
import static com.android.cts.netpolicy.hostside.app2.Common.NOTIFICATION_TYPE_FULL_SCREEN;
import static com.android.cts.netpolicy.hostside.app2.Common.TAG;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * Receiver used to:
 * <ol>
 *   <li>Count number of {@code RESTRICT_BACKGROUND_CHANGED} broadcasts received.
 *   <li>Show a toast.
 * </ol>
 */
public class MyBroadcastReceiver extends BroadcastReceiver {

    private final String mName;

    public MyBroadcastReceiver() {
        this(MANIFEST_RECEIVER);
    }

    MyBroadcastReceiver(String name) {
        Log.d(TAG, "Constructing MyBroadcastReceiver named " + name);
        mName = name;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive() for " + mName + ": " + intent);
        final String action = intent.getAction();
        switch (action) {
            case ACTION_SNOOZE_WARNING:
                increaseCounter(context, action);
                break;
            case ACTION_RESTRICT_BACKGROUND_CHANGED:
                increaseCounter(context, action);
                break;
            case ACTION_RECEIVER_READY:
                final String message = mName + " is ready to rumble";
                Log.d(TAG, message);
                setResultData(message);
                break;
            case ACTION_SHOW_TOAST:
                showToast(context);
                break;
            default:
                Log.e(TAG, "received unexpected action: " + action);
        }
    }

    @Override
    public String toString() {
        return "[MyBroadcastReceiver: mName=" + mName + "]";
    }

    private void increaseCounter(Context context, String action) {
        final SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(mName, Context.MODE_PRIVATE);
        final int value = prefs.getInt(action, 0) + 1;
        Log.d(TAG, "increaseCounter('" + action + "'): setting '" + mName + "' to " + value);
        prefs.edit().putInt(action, value).apply();
    }

    static int getCounter(Context context, String action, String receiverName) {
        final SharedPreferences prefs = context.getSharedPreferences(receiverName,
                Context.MODE_PRIVATE);
        final int value = prefs.getInt(action, 0);
        Log.d(TAG, "getCounter('" + action + "', '" + receiverName + "'): " + value);
        return value;
    }

    static String getRestrictBackgroundStatus(Context context) {
        final ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        final int apiStatus = cm.getRestrictBackgroundStatus();
        Log.d(TAG, "getRestrictBackgroundStatus: returning " + apiStatus);
        return String.valueOf(apiStatus);
    }

    /**
     * Sends a system notification containing actions with pending intents to launch the app's
     * main activitiy or service.
     */
    static void sendNotification(Context context, String channelId, int notificationId,
            String notificationType ) {
        Log.d(TAG, "sendNotification: id=" + notificationId + ", type=" + notificationType);
        final Intent serviceIntent = new Intent(context, MyService.class);
        final PendingIntent pendingIntent = PendingIntent.getService(context, 0, serviceIntent,
                PendingIntent.FLAG_MUTABLE);
        final Bundle bundle = new Bundle();
        bundle.putCharSequence("parcelable", "I am not");

        final Notification.Builder builder = new Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification);

        Action action = null;
        switch (notificationType) {
            case NOTIFICATION_TYPE_CONTENT:
                builder
                    .setContentTitle("Light, Cameras...")
                    .setContentIntent(pendingIntent);
                break;
            case NOTIFICATION_TYPE_DELETE:
                builder.setDeleteIntent(pendingIntent);
                break;
            case NOTIFICATION_TYPE_FULL_SCREEN:
                builder.setFullScreenIntent(pendingIntent, true);
                break;
            case NOTIFICATION_TYPE_BUNDLE:
                bundle.putParcelable("Magnum P.I. (Pending Intent)", pendingIntent);
                builder.setExtras(bundle);
                break;
            case NOTIFICATION_TYPE_ACTION:
                action = new Action.Builder(
                        R.drawable.ic_notification, "ACTION", pendingIntent)
                        .build();
                builder.addAction(action);
                break;
            case NOTIFICATION_TYPE_ACTION_BUNDLE:
                bundle.putParcelable("Magnum A.P.I. (Action Pending Intent)", pendingIntent);
                action = new Action.Builder(
                        R.drawable.ic_notification, "ACTION WITH BUNDLE", null)
                        .addExtras(bundle)
                        .build();
                builder.addAction(action);
                break;
            case NOTIFICATION_TYPE_ACTION_REMOTE_INPUT:
                bundle.putParcelable("Magnum R.I. (Remote Input)", null);
                final RemoteInput remoteInput = new RemoteInput.Builder("RI")
                    .addExtras(bundle)
                    .build();
                action = new Action.Builder(
                        R.drawable.ic_notification, "ACTION WITH REMOTE INPUT", pendingIntent)
                        .addRemoteInput(remoteInput)
                        .build();
                builder.addAction(action);
                break;
            default:
                Log.e(TAG, "Unknown notification type: " + notificationType);
                return;
        }

        final Notification notification = builder.build();
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
            .notify(notificationId, notification);
    }

    private void showToast(Context context) {
        Toast.makeText(context, "Toast from CTS test", Toast.LENGTH_SHORT).show();
        setResultData("Shown");
    }
}
