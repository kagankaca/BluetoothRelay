package com.example.bluetoothrelay.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetoothrelay.model.BluetoothDeviceInfo
import com.example.bluetoothrelay.model.ConnectionState
import com.example.bluetoothrelay.model.Message
import com.example.bluetoothrelay.service.BluetoothService
import com.example.bluetoothrelay.util.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val bluetoothService = BluetoothService(application)

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Registration)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Expose the StateFlows from BluetoothService
    val connectionState: StateFlow<ConnectionState> = bluetoothService.connectionState
    val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = bluetoothService.discoveredDevices
    val messages: StateFlow<List<Message>> = bluetoothService.messages
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val preferencesManager = PreferencesManager(application)

    init {
        // Monitor connection state changes
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Scanning -> _isScanning.value = true
                    is ConnectionState.Connected -> _isScanning.value = false
                    is ConnectionState.Disconnected -> _isScanning.value = false
                    is ConnectionState.Error -> {
                        _isScanning.value = false
                        // Handle error state if needed
                    }
                }
            }
        }

        // Check for existing username
        viewModelScope.launch {
            _username.value = preferencesManager.getUsername()
            _uiState.value = if (_username.value != null) {
                startScanning() // Start scanning if user already exists
                UiState.Chat
            } else {
                UiState.Registration
            }
        }

        setupNetworkCallback()
    }

    fun setUsername(name: String) {
        preferencesManager.saveUsername(name)
        _username.value = name
        _uiState.value = UiState.Chat
        // Start scanning for devices once username is set
        startScanning()
    }

    fun startScanning() {
        viewModelScope.launch {
            bluetoothService.startDiscovery()
            _isScanning.value = true
        }
    }

    fun stopScanning() {
        viewModelScope.launch {
            bluetoothService.stopDiscovery()
            _isScanning.value = false
        }
    }

    fun connectToDevice(deviceAddress: String) {
        viewModelScope.launch {
            bluetoothService.connectToDevice(deviceAddress)
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
            bluetoothService.sendMessage(message)
        }
    }

    fun retryConnection(deviceAddress: String) {
        stopScanning()
        connectToDevice(deviceAddress)
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let {
            val connectivityManager = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
        bluetoothService.cleanup()
    }

    private fun setupNetworkCallback() {
        val connectivityManager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                bluetoothService.updateInternetConnection(true)
            }

            override fun onLost(network: Network) {
                bluetoothService.updateInternetConnection(false)
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    sealed class UiState {
        data object Registration : UiState()
        data object Chat : UiState()
    }
}