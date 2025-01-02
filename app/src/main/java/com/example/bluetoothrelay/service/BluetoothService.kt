package com.example.bluetoothrelay.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.bluetoothrelay.model.BluetoothDeviceInfo
import com.example.bluetoothrelay.model.ConnectionState
import com.example.bluetoothrelay.model.Message
import com.example.bluetoothrelay.model.RelayMessage
import com.example.bluetoothrelay.network.FirestoreRepository
import com.example.bluetoothrelay.util.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class BluetoothService(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val deviceAddress = bluetoothAdapter?.address ?: ""

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val connectedSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val messageCache = ConcurrentHashMap<String, RelayMessage>()
    private var hasInternetConnection = false
    private val firestoreRepository = FirestoreRepository()

    private val preferencesManager = PreferencesManager(context)

    private val deviceDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            val deviceInfo = BluetoothDeviceInfo(
                                address = it.address,
                                name = it.name,
                                isConnected = false
                            )
                            updateDiscoveredDevices(deviceInfo)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    init {
        registerBluetoothReceiver()
        // Start periodic cache cleaning
        scope.launch {
            while (true) {
                delay(60000) // Clean every minute
                cleanMessageCache()
            }
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(deviceDiscoveryReceiver, filter)
    }

    private fun updateDiscoveredDevices(newDevice: BluetoothDeviceInfo) {
        val currentDevices = _discoveredDevices.value.toMutableList()
        if (!currentDevices.any { it.address == newDevice.address }) {
            currentDevices.add(newDevice)
            _discoveredDevices.value = currentDevices
        }
    }

    fun startDiscovery() {
        if (!hasRequiredPermissions()) {
            _connectionState.value = ConnectionState.Error("Missing required permissions")
            return
        }

        scope.launch {
            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothAdapter?.startDiscovery()
                    _connectionState.value = ConnectionState.Scanning
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("Failed to start discovery: ${e.message}")
            }
        }
    }

    fun stopDiscovery() {
        if (!hasRequiredPermissions()) return

        scope.launch {
            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothAdapter?.cancelDiscovery()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop discovery", e)
            }
        }
    }

    fun sendMessage(message: Message) {
        if (!hasRequiredPermissions()) return

        scope.launch {
            try {
                val relayMessage = RelayMessage(
                    message = message,
                    route = listOf(deviceAddress)
                )

                // Cache the message
                messageCache[relayMessage.messageId] = relayMessage

                // Attempt to send to all connected devices
                relayMessageToConnectedDevices(relayMessage)

                // Update local messages list
                updateLocalMessages(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    private fun relayMessageToConnectedDevices(relayMessage: RelayMessage) {
        if (relayMessage.route.size >= relayMessage.maxHops ||
            System.currentTimeMillis() > relayMessage.timeToLive) {
            return
        }

        connectedSockets.values.forEach { socket ->
            try {
                val outputStream = ObjectOutputStream(socket.outputStream)
                outputStream.writeObject(relayMessage)
                outputStream.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to relay message to device", e)
            }
        }
    }

    fun connectToDevice(deviceAddress: String) {
        if (!hasRequiredPermissions()) {
            _connectionState.value = ConnectionState.Error("Missing required permissions")
            return
        }

        scope.launch {
            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                    device?.let {
                        connectToSocket(it)
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("Failed to connect: ${e.message}")
            }
        }
    }

    private fun connectToSocket(device: BluetoothDevice) {
        if (!hasRequiredPermissions()) return

        var socket: BluetoothSocket? = null
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()

                connectedSockets[device.address] = socket
                _connectionState.value = ConnectionState.Connected

                // Start listening for messages from this device
                handleConnectedSocket(socket)
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Socket connection failed: ${e.message}")
            socket?.close()
        }
    }

    private fun handleConnectedSocket(socket: BluetoothSocket) {
        scope.launch {
            try {
                val inputStream = ObjectInputStream(socket.inputStream)
                while (true) {
                    when (val received = inputStream.readObject()) {
                        is RelayMessage -> handleRelayMessage(received)
                        is Message -> updateLocalMessages(received)
                    }
                }
            } catch (e: IOException) {
                _connectionState.value = ConnectionState.Error("Connection lost: ${e.message}")
            } finally {
                socket.close()
            }
        }
    }

    private fun handleRelayMessage(relayMessage: RelayMessage) {
        // Check if we've seen this message before
        if (messageCache.containsKey(relayMessage.messageId)) {
            return
        }

        // Cache the message
        messageCache[relayMessage.messageId] = relayMessage

        // If we're the intended recipient, process the message
        if (relayMessage.message.receiver == getCurrentUsername()) {
            updateLocalMessages(relayMessage.message)
            return
        }

        // Otherwise, relay the message
        val updatedRoute = relayMessage.route + deviceAddress
        val updatedRelayMessage = relayMessage.copy(route = updatedRoute)
        relayMessageToConnectedDevices(updatedRelayMessage)
    }

    private fun updateLocalMessages(message: Message) {
        val currentMessages = _messages.value.toMutableList()
        if (!currentMessages.any { it.id == message.id }) {
            currentMessages.add(message)
            _messages.value = currentMessages
        }
    }

    fun updateInternetConnection(hasInternet: Boolean) {
        hasInternetConnection = hasInternet
        if (hasInternet) {
            syncWithServer()
        }
    }

    private fun syncWithServer() {
        if (!hasInternetConnection) return

        scope.launch {
            val username = getCurrentUsername() ?: return@launch

            // Send cached messages to server
            messageCache.values.forEach { relayMessage ->
                try {
                    firestoreRepository.sendMessage(relayMessage.message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync message to server", e)
                }
            }

            // Listen for pending messages
            firestoreRepository.getPendingMessages(username)
                .collect { messages ->
                    messages.forEach { message ->
                        if (!messageCache.containsKey(message.id)) {
                            val relayMessage = RelayMessage(message)
                            messageCache[message.id] = relayMessage
                            updateLocalMessages(message)
                            relayMessageToConnectedDevices(relayMessage)
                            // Mark as delivered
                            firestoreRepository.markMessageAsDelivered(message.id)
                        }
                    }
                }
        }
    }

    private fun getCurrentUsername(): String? {
        return preferencesManager.getUsername()
    }

    private fun cleanMessageCache() {
        val currentTime = System.currentTimeMillis()
        messageCache.entries.removeIf { (_, message) ->
            currentTime > message.timeToLive
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(deviceDiscoveryReceiver)
            connectedSockets.values.forEach { it.close() }
            connectedSockets.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    companion object {
        private const val TAG = "BluetoothService"
        private val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}