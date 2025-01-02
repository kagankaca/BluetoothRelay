package com.example.bluetoothrelay.model

data class Message(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String,
    val receiver: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val relayChainLength: Int = 0,
    val isDelivered: Boolean = false
)