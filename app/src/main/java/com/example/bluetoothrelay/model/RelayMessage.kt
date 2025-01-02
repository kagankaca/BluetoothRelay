package com.example.bluetoothrelay.model

data class RelayMessage(
    val message: Message,
    val route: List<String> = emptyList(),  // List of device addresses in relay chain
    val maxHops: Int = 10,  // Maximum number of relay hops
    val timeToLive: Long = System.currentTimeMillis() + 300000, // 5 minutes TTL
    val messageId: String = message.id
) : java.io.Serializable