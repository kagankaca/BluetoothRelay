package com.example.bluetoothrelay.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetoothrelay.model.ConnectionState
import DeviceInfo
import android.content.pm.PackageManager
import com.example.bluetoothrelay.model.Message
import com.example.bluetoothrelay.service.WiFiDirectService
import com.example.bluetoothrelay.util.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.core.app.ActivityCompat

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val wifiDirectService = WiFiDirectService(application)
    private val preferencesManager = PreferencesManager(application)

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Registration)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _currentPermissionRequest = MutableStateFlow<String?>(Manifest.permission.ACCESS_FINE_LOCATION)
    val currentPermissionRequest: StateFlow<String?> = _currentPermissionRequest.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = wifiDirectService.connectionState
    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()
    val messages: StateFlow<List<Message>> = wifiDirectService.messages

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Error -> {
                        if (state.message == "Missing required permissions") {
                            // Start permission request flow again
                            _currentPermissionRequest.value = Manifest.permission.ACCESS_FINE_LOCATION
                        }
                    }
                    else -> { /* Handle other states */ }
                }
            }
        }
    }

    fun retryConnection() {
        _currentPermissionRequest.value = Manifest.permission.ACCESS_FINE_LOCATION
    }

    fun resetPermissionState() {
        _currentPermissionRequest.value = Manifest.permission.ACCESS_FINE_LOCATION
    }

    fun onPermissionGranted(permission: String) {
        when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> {
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    _currentPermissionRequest.value = null
                    onAllPermissionsGranted()
                } else {
                    // If not actually granted, keep asking
                    _currentPermissionRequest.value = Manifest.permission.ACCESS_FINE_LOCATION
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(
            getApplication(),
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun verifyPermissions() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            _currentPermissionRequest.value = Manifest.permission.ACCESS_FINE_LOCATION
        }
    }




    private fun onAllPermissionsGranted() {
        viewModelScope.launch {
            val savedUsername = preferencesManager.getUsername()
            _username.value = savedUsername
            _uiState.value = if (savedUsername != null) {
                startScanning()
                UiState.Chat
            } else {
                UiState.Registration
            }
        }
    }

    fun setUsername(name: String) {
        preferencesManager.saveUsername(name)
        _username.value = name
        _uiState.value = UiState.Chat
        startScanning()
    }

    fun startScanning() {
        // Check permission before starting scan
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            _currentPermissionRequest.value = Manifest.permission.ACCESS_FINE_LOCATION
            return
        }
        viewModelScope.launch {
            wifiDirectService.startDiscovery()
            _isScanning.value = true
        }
    }

    fun stopScanning() {
        viewModelScope.launch {
            wifiDirectService.stopDiscovery()
            _isScanning.value = false
        }
    }

    fun connectToDevice(deviceInfo: DeviceInfo) {
        viewModelScope.launch {
            wifiDirectService.connectToDevice(deviceInfo)
        }
    }



    fun sendMessage(receiverUsername: String, content: String) {
        val currentUsername = _username.value ?: return
        val message = Message(
            sender = currentUsername,
            receiver = receiverUsername,
            content = content
        )
        viewModelScope.launch {
            wifiDirectService.sendMessage(message)
        }
    }

    private fun setupNetworkCallback() {
        val connectivityManager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiDirectService.updateInternetConnection(true)
            }

            override fun onLost(network: Network) {
                wifiDirectService.updateInternetConnection(false)
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let {
            val connectivityManager = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
        wifiDirectService.cleanup()
    }

    sealed class UiState {
        data object Registration : UiState()
        data object Chat : UiState()
    }
}