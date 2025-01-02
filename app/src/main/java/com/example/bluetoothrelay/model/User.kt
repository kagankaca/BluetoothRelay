package com.example.bluetoothrelay.model

data class User(
    val username: String,
    val deviceId: String = java.util.UUID.randomUUID().toString()
)