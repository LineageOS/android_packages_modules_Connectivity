package com.android.server.nearby.common.bluetooth.gatt.server;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;

import com.android.server.nearby.common.bluetooth.BluetoothGattException;
import com.android.server.nearby.common.bluetooth.gatt.server.BluetoothGattServerConnection.Notifier;

/** Servlet to handle GATT operations on a characteristic. */
@TargetApi(18)
public abstract class BluetoothGattServlet {
    public byte[] read(BluetoothGattServerConnection connection,
            @SuppressWarnings("unused") int offset) throws BluetoothGattException {
        throw new BluetoothGattException("Read not supported.",
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED);
    }

    public void write(BluetoothGattServerConnection connection,
            @SuppressWarnings("unused") int offset, @SuppressWarnings("unused") byte[] value)
            throws BluetoothGattException {
        throw new BluetoothGattException("Write not supported.",
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED);
    }

    public byte[] readDescriptor(BluetoothGattServerConnection connection,
            BluetoothGattDescriptor descriptor, @SuppressWarnings("unused") int offset)
            throws BluetoothGattException {
        throw new BluetoothGattException("Read not supported.",
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED);
    }

    public void writeDescriptor(BluetoothGattServerConnection connection,
            BluetoothGattDescriptor descriptor,
            @SuppressWarnings("unused") int offset, @SuppressWarnings("unused") byte[] value)
            throws BluetoothGattException {
        throw new BluetoothGattException("Write not supported.",
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED);
    }

    public void enableNotification(BluetoothGattServerConnection connection, Notifier notifier)
            throws BluetoothGattException {
        throw new BluetoothGattException("Notification not supported.",
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED);
    }

    public void disableNotification(BluetoothGattServerConnection connection, Notifier notifier)
            throws BluetoothGattException {
        throw new BluetoothGattException("Notification not supported.",
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED);
    }

    public abstract BluetoothGattCharacteristic getCharacteristic();
}
