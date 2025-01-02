package com.example.bluetoothrelay.model

sealed class ConnectionState {
    data object Connected : ConnectionState()
    data object Disconnected : ConnectionState()
    data object Scanning : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}