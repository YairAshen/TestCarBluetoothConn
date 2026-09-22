package com.example.testcarbluetoothconn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.testcarbluetoothconn.ui.theme.TestCarBluetoothConnTheme

class MainActivity : ComponentActivity() {
    // neverEqualPolicy: always recompose when the tracker publishes, even if device lists look equal.
    private var uiState by mutableStateOf(BluetoothUiState(), neverEqualPolicy())
    private lateinit var tracker: BluetoothConnectionTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tracker = BluetoothConnectionTracker(this) { state ->
            uiState = state
        }
        enableEdgeToEdge()
        setContent {
            TestCarBluetoothConnTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    if (results.values.all { it }) {
                        tracker.permissionGranted()
                    } else {
                        tracker.start(hasPermission = false)
                    }
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BluetoothStatusScreen(
                        state = uiState,
                        onRequestPermission = {
                            permissionLauncher.launch(requiredBluetoothPermissions())
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        tracker.start(hasBluetoothPermission())
    }

    override fun onStop() {
        tracker.stop()
        super.onStop()
    }

    private fun hasBluetoothPermission(): Boolean {
        return requiredBluetoothPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}

private fun requiredBluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }
}

@Composable
fun BluetoothStatusScreen(
    state: BluetoothUiState,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Car Bluetooth connection",
            style = MaterialTheme.typography.headlineSmall
        )

        when {
            !state.bluetoothAvailable -> {
                StatusCard(
                    title = "Bluetooth unavailable",
                    body = "This device does not support Bluetooth."
                )
            }

            !state.hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                StatusCard(
                    title = "Permission required",
                    body = "Bluetooth connect permission is needed to see paired device names and connection events."
                )
                Button(onClick = onRequestPermission) {
                    Text("Grant Bluetooth permission")
                }
            }

            !state.bluetoothEnabled -> {
                StatusCard(
                    title = "Bluetooth off",
                    body = "Turn on Bluetooth, then connect headphones or the car from Android Bluetooth settings."
                )
            }

            else -> {
                val connectedDevices = state.connectedDevices
                StatusCard(
                    title = if (connectedDevices.isNotEmpty()) "Connected" else "Disconnected",
                    body = if (connectedDevices.isNotEmpty()) {
                        "${connectedDevices.size} Bluetooth device(s) currently connected."
                    } else {
                        "No Bluetooth devices are currently connected. Paired devices may still be listed below."
                    }
                )

                state.lastEvent?.let { event ->
                    val title = if (event.connected) {
                        "Bluetooth connected"
                    } else {
                        "Bluetooth disconnected"
                    }
                    val details = buildString {
                        append(event.deviceName)
                        if (event.connected && !event.deviceAddress.isNullOrBlank()) {
                            append("\n")
                            append(event.deviceAddress)
                        }
                    }
                    StatusCard(title = title, body = details)
                }

                Text(
                    text = "Currently connected",
                    style = MaterialTheme.typography.titleMedium
                )
                if (connectedDevices.isEmpty()) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    connectedDevices.forEach { device ->
                        DeviceCard(device)
                    }
                }

                Text(
                    text = "Paired devices",
                    style = MaterialTheme.typography.titleMedium
                )
                val pairedDevices = state.devices.filter { it.paired }
                if (pairedDevices.isEmpty()) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    pairedDevices.forEach { device ->
                        DeviceCard(device)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Keep this screen open, then connect or disconnect headphones or the car. Only devices with an active A2DP, HFP, or GATT profile are shown as connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(device: TrackedBluetoothDevice) {
    val status = when {
        device.connected -> "Connected"
        device.paired -> "Disconnected (paired)"
        else -> "Disconnected"
    }
    val body = buildString {
        append(status)
        append("\n")
        append(device.address)
        if (device.connected && device.connectedProfiles.isNotEmpty()) {
            append("\nProfiles: ")
            append(device.connectedProfiles.joinToString(", "))
        }
    }
    StatusCard(title = device.name, body = body)
}

@Composable
private fun StatusCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BluetoothStatusPreview() {
    TestCarBluetoothConnTheme {
        BluetoothStatusScreen(
            state = BluetoothUiState(
                bluetoothAvailable = true,
                bluetoothEnabled = true,
                hasPermission = true,
                devices = listOf(
                    TrackedBluetoothDevice(
                        name = "Headphones",
                        address = "AA:BB:CC:DD:EE:FF",
                        paired = true,
                        connected = true,
                        connectedProfiles = listOf("A2DP", "HFP")
                    ),
                    TrackedBluetoothDevice(
                        name = "Car",
                        address = "11:22:33:44:55:66",
                        paired = true,
                        connected = false,
                        connectedProfiles = emptyList()
                    )
                ),
                lastEvent = BluetoothEvent(
                    connected = true,
                    deviceName = "Headphones",
                    deviceAddress = "AA:BB:CC:DD:EE:FF"
                )
            ),
            onRequestPermission = {}
        )
    }
}
