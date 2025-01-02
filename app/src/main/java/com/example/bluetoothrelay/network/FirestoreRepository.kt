package com.example.bluetoothrelay.network

import com.example.bluetoothrelay.model.Message
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirestoreRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private val messagesCollection = firestore.collection("messages")

    suspend fun sendMessage(message: Message) {
        try {
            messagesCollection.document(message.id).set(message).await()
        } catch (e: Exception) {
            throw e
        }
    }

    fun getPendingMessages(username: String): Flow<List<Message>> = callbackFlow {
        val subscription = messagesCollection
            .whereEqualTo("receiver", username)
            .whereEqualTo("isDelivered", false)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Handle error
                    return@addSnapshotListener
                }

                val messages = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(Message::class.java)
                } ?: emptyList()

                trySend(messages)
            }

        awaitClose { subscription.remove() }
    }

    suspend fun markMessageAsDelivered(messageId: String) {
        try {
            messagesCollection.document(messageId)
                .update("isDelivered", true)
                .await()
        } catch (e: Exception) {
            throw e
        }
    }
}