package com.example.bluetoothrelay.model

sealed class MessageState {
    data class Success(val message: Message) : MessageState()
    data class Error(val exception: Throwable) : MessageState()
    data object Loading : MessageState()
}
