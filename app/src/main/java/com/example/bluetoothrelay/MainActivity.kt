package com.example.bluetoothrelay

import DeviceInfo
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetoothrelay.model.Message
import com.example.bluetoothrelay.ui.theme.BluetoothRelayTheme
import com.example.bluetoothrelay.viewmodel.MainViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextAlign  // For TextAlign
import com.example.bluetoothrelay.model.ConnectionState
import com.google.accompanist.permissions.rememberPermissionState // For rememberPermissionState
import kotlinx.coroutines.delay


@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BluetoothRelayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val currentPermissionRequest by viewModel.currentPermissionRequest.collectAsState()
                    val uiState by viewModel.uiState.collectAsState()

                    DisposableEffect(Unit) {
                        onDispose {
                            // Reset state when activity is destroyed
                            viewModel.resetPermissionState()
                        }
                    }

                    when (val permission = currentPermissionRequest) {
                        null -> {
                            // Only show ChatScreen if we have the required permission
                            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                                == PackageManager.PERMISSION_GRANTED) {
                                when (uiState) {
                                    is MainViewModel.UiState.Registration -> RegistrationScreen(viewModel)
                                    is MainViewModel.UiState.Chat -> ChatScreen(viewModel)
                                }
                            } else {
                                // If we don't have permission, request it
                                LaunchedEffect(Unit) {
                                    viewModel.retryConnection()
                                }
                            }
                        }
                        else -> {
                            val permissionState = rememberPermissionState(permission) { isGranted ->
                                if (isGranted) {
                                    viewModel.onPermissionGranted(permission)
                                }
                            }

                            LaunchedEffect(Unit) {
                                delay(500)  // Add small delay for better UX
                                permissionState.launchPermissionRequest()
                            }

                            PermissionsScreenContent(
                                title = "Location Permission Required",
                                description = "Location permission is required for WiFi Direct functionality. Please grant it to continue.",
                                onRequestPermission = {
                                    permissionState.launchPermissionRequest()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

    private fun getPermissionText(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
            Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Devices"
            else -> "Required"
        }
    }


@Composable
private fun PermissionsScreenContent(
    title: String,
    description: String,
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}


@Composable
fun RegistrationScreen(viewModel: MainViewModel) {
    var username by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Enter your username",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.setUsername(username) },
            enabled = username.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

@Composable
fun ChatScreen(viewModel: MainViewModel) {
    var message by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf("") }
    val messages = viewModel.messages.collectAsState(initial = emptyList())
    val connectionState = viewModel.connectionState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val username by viewModel.username.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Status Section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text("Username: ${username ?: "Not set"}")
                Text("Scanning: ${if (isScanning) "Yes" else "No"}")
                Text("Connection: ${connectionState.value}")
                Text("Discovered Devices: ${discoveredDevices.size}")
            }
        }

        when (val state = connectionState.value) {
            is ConnectionState.Error -> {
                if (state.message == "Missing required permissions") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Additional permissions are required",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { viewModel.retryConnection() }) {
                                Text("Grant Permissions")
                            }
                        }
                    }
                }
            }
            else -> {
                // Scan Control Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = {
                            if (isScanning) viewModel.stopScanning()
                            else viewModel.startScanning()
                        }
                    ) {
                        Text(if (isScanning) "Stop Scanning" else "Start Scanning")
                    }
                }

                // Device List
                Text(
                    "Discovered Devices",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(discoveredDevices) { device ->
                        DeviceItem(device) {
                            viewModel.connectToDevice(device)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Messages
                Text(
                    "Messages",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(messages.value) { message ->
                        MessageItem(message)
                    }
                }

                // Message Input
                OutlinedTextField(
                    value = receiver,
                    onValueChange = { receiver = it },
                    label = { Text("Receiver Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text("Message") },
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            viewModel.sendMessage(receiver, message)
                            message = ""
                        },
                        enabled = message.isNotBlank() && receiver.isNotBlank()
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(device: DeviceInfo, onConnect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = device.name ?: "Unknown Device",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(onClick = onConnect) {
                Text("Connect")
            }
        }
    }
}


@Composable
fun MessageRelayInfo(relayChainLength: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Relay Chain: $relayChainLength hop(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
fun MessageItem(message: Message) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "From: ${message.sender}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "To: ${message.receiver}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            MessageRelayInfo(message.relayChainLength)
        }
    }
}