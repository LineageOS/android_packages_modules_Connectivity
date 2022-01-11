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

package com.android.nearby.multidevices.fastpair.provider

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.nearby.multidevices.fastpair.provider.BluetoothStateChangeReceiver
import androidx.annotation.RequiresPermission
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.util.Log
import com.google.common.truth.Truth.assertThat
import com.android.nearby.multidevices.common.Mockotlin.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

/** Robolectric tests for [BluetoothStateChangeReceiver]. */
@RunWith(AndroidJUnit4::class)
class BluetoothStateChangeReceiverTest {
  private lateinit var bluetoothStateChangeReceiver: BluetoothStateChangeReceiver
  private lateinit var context: Context
  private val mockListener = mock<BluetoothStateChangeReceiver.EventListener>()

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().context
    bluetoothStateChangeReceiver = BluetoothStateChangeReceiver(context)
    Log.apkLogTag = "BluetoothStateChangeReceiverTest"
  }

  @Test
  fun testRegister_setsListener() {
    bluetoothStateChangeReceiver.register(mockListener)

    assertThat(bluetoothStateChangeReceiver.listener).isNotNull()
  }

  @Test
  fun testUnregister_clearListener() {
    bluetoothStateChangeReceiver.register(mockListener)

    bluetoothStateChangeReceiver.unregister()

    assertThat(bluetoothStateChangeReceiver.listener).isNull()
  }

  @Test
  @RequiresPermission(Manifest.permission.BLUETOOTH)
  fun testOnReceive_actionScanModeChanged_reportsOnScanModeChange() {
    val intent =
      Intent(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
        .putExtra(
          BluetoothAdapter.EXTRA_SCAN_MODE,
          BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        )
    bluetoothStateChangeReceiver.register(mockListener)

    bluetoothStateChangeReceiver.onReceive(context, intent)

    verify(mockListener).onScanModeChange("DISCOVERABLE")
  }
}
