/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.testutils

import android.database.ContentObserver
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.test.mock.MockContentResolver
import android.util.ArrayMap
import android.util.Log
import android.util.Pair
import com.android.internal.util.test.FakeSettingsProvider
import java.util.Collections

private const val TAG = "CRWithFakeSettings"

class ContentResolverWithFakeSettingsProvider : MockContentResolver() {
    private val observers = ArrayMap<Uri, Pair<ContentObserver, UserHandle>>()

    init {
        addProvider(Settings.AUTHORITY, FakeSettingsProvider({ userId: Int, uri: Uri ->
            val p = observers.get(uri)
            if (p == null) return@FakeSettingsProvider
            val observer = p.first
            val callbackUser = p.second
            val user = UserHandle(userId)
            val currentUser = UserHandle.getUserHandleForUid(Process.myUid())
            if (callbackUser == UserHandle.CURRENT && currentUser == user) {
                // Use the overload without the user parameter since it works on S.
                // TODO: when S is no longer supported, remove this and always use the branch below.
                Log.d(TAG, "Notifying change in $uri")
                observer.onChange(false, Collections.singletonList(uri), 0)
            } else if (callbackUser == UserHandle.ALL || callbackUser.equals(user)) {
                Log.d(TAG, "Notifying change in $uri on user $user")
                observer.onChange(false, Collections.singletonList(uri), 0, user)
            }
        }))
    }

    /**
     * Registers a ContentObserver that gets callbacks when the given URI changes.
     * Note: this method does not override any actual ContentResolver methods - they are all final.
     */
    fun registerContentObserver(uri: Uri, observer: ContentObserver) {
        if (observers.containsKey(uri)) {
            throw IllegalStateException("Duplicate ContentObserver for URI: $uri")
        }
        observers.put(uri, Pair(observer, UserHandle.CURRENT))
    }

    /**
     * Registers a ContentObserver that gets callbacks when the given URI changes.
     * Note: this method does not override any actual ContentResolver methods - they are all final.
     */
    fun registerContentObserverAsUser(uri: Uri, observer: ContentObserver, userHandle: UserHandle) {
        if (observers.containsKey(uri)) {
            throw IllegalStateException("Duplicate ContentObserver for URI: $uri")
        }
        observers.put(uri, Pair(observer, userHandle))
    }
}
