package com.example.bluetoothrelay.model

data class BluetoothDeviceInfo(
    val address: String,
    val name: String?,
    val isConnected: Boolean = false
)