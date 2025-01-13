package com.example.bluetoothrelay.service

import DeviceInfo
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.bluetoothrelay.model.ConnectionState
import com.example.bluetoothrelay.model.Message
import com.example.bluetoothrelay.model.RelayMessage
import com.example.bluetoothrelay.network.FirestoreRepository
import com.example.bluetoothrelay.util.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WiFiDirectService(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifiP2pManager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private val channel: WifiP2pManager.Channel by lazy {
        wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val firestoreRepository = FirestoreRepository()
    private val preferencesManager = PreferencesManager(context)
    private var hasInternetConnection = false

    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        val refreshedPeersList = peerList.deviceList.map { device ->
            DeviceInfo(
                address = device.deviceAddress,
                name = device.deviceName,
                isConnected = device.status == WifiP2pDevice.CONNECTED
            )
        }
        _discoveredDevices.value = refreshedPeersList
    }

    private val connectionListener = WifiP2pManager.ConnectionInfoListener { info ->
        if (info.groupFormed) {
            _connectionState.value = ConnectionState.Connected
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        _connectionState.value = ConnectionState.Disconnected
                    } else {
                        _connectionState.value = ConnectionState.Error("Wi-Fi Direct is not enabled")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (checkPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                        try {
                            wifiP2pManager.requestPeers(channel, peerListListener)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Security exception when requesting peers", e)
                            _connectionState.value = ConnectionState.Error("Permission denied")
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (checkPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                        try {
                            wifiP2pManager.requestConnectionInfo(channel, connectionListener)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Security exception when requesting connection info", e)
                            _connectionState.value = ConnectionState.Error("Permission denied")
                        }
                    }
                }
            }
        }
    }

    init {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)
    }

    private fun checkPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkRequiredPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRequiredPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startDiscovery() {
        if (!hasRequiredPermissions()) {
            _connectionState.value = ConnectionState.Error("Missing required permissions")
            return
        }

        if (!checkPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            _connectionState.value = ConnectionState.Error("Missing required permissions")
            return
        }

        _connectionState.value = ConnectionState.Scanning
        try {
            wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Discovery started successfully")
                }

                override fun onFailure(reason: Int) {
                    val errorMsg = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
                        WifiP2pManager.ERROR -> "Discovery failed due to internal error"
                        WifiP2pManager.BUSY -> "Discovery failed because system is busy"
                        else -> "Discovery failed with error code: $reason"
                    }
                    _connectionState.value = ConnectionState.Error(errorMsg)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception when starting discovery", e)
            _connectionState.value = ConnectionState.Error("Permission denied")
        }
    }

    fun stopDiscovery() {
        if (!checkPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) return

        try {
            wifiP2pManager.stopPeerDiscovery(channel, null)
            _connectionState.value = ConnectionState.Disconnected
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception when stopping discovery", e)
            _connectionState.value = ConnectionState.Error("Permission denied")
        }
    }

    fun connectToDevice(deviceInfo: DeviceInfo) {
        if (!checkRequiredPermissions()) {
            _connectionState.value = ConnectionState.Error("Missing required permissions")
            return
        }

        if (!checkPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            _connectionState.value = ConnectionState.Error("Missing required permissions")
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = deviceInfo.address
        }

        try {
            wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection initiated successfully")
                }

                override fun onFailure(reason: Int) {
                    val errorMsg = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
                        WifiP2pManager.ERROR -> "Connection failed due to internal error"
                        WifiP2pManager.BUSY -> "System is busy"
                        else -> "Connection failed with error code: $reason"
                    }
                    _connectionState.value = ConnectionState.Error(errorMsg)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception when connecting to device", e)
            _connectionState.value = ConnectionState.Error("Permission denied")
        }
    }

    fun sendMessage(message: Message) {
        if (connectionState.value !is ConnectionState.Connected) {
            return
        }

        // Since we're connected, update local messages immediately
        updateLocalMessages(message)

        // If we have internet, sync with server
        if (hasInternetConnection) {
            scope.launch {
                try {
                    firestoreRepository.sendMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send message to server", e)
                }
            }
        }
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
            try {
                firestoreRepository.getPendingMessages(username)
                    .collect { messages ->
                        messages.forEach { message ->
                            updateLocalMessages(message)
                            firestoreRepository.markMessageAsDelivered(message.id)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync with server", e)
            }
        }
    }

    private fun getCurrentUsername(): String? {
        return preferencesManager.getUsername()
    }

    fun cleanup() {
        scope.cancel()
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    companion object {
        private const val TAG = "WiFiDirectService"
    }
}