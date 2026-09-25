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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.testcarbluetoothconn.ui.theme.TestCarBluetoothConnTheme

class MainActivity : ComponentActivity() {
    private var uiState by mutableStateOf(BluetoothUiState())
    private var cameraPermissionGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(false)
    private lateinit var tracker: BluetoothConnectionTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tracker = BluetoothConnectionTracker(applicationContext) { state ->
            runOnUiThread { uiState = state }
        }
        cameraPermissionGranted = hasCameraPermission()
        notificationPermissionGranted = hasNotificationPermission()
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
                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    cameraPermissionGranted = granted
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    notificationPermissionGranted = granted
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BluetoothStatusScreen(
                        state = uiState,
                        cameraPermissionGranted = cameraPermissionGranted,
                        notificationPermissionGranted = notificationPermissionGranted,
                        onRequestPermission = {
                            permissionLauncher.launch(requiredBluetoothPermissions())
                        },
                        onRequestCameraPermission = {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onRequestNotificationPermission = {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        cameraPermissionGranted = hasCameraPermission()
        notificationPermissionGranted = hasNotificationPermission()
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

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
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
    cameraPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
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

        if (!cameraPermissionGranted) {
            StatusCard(
                title = "Camera permission required",
                body = "Camera permission is needed before the camera can open."
            )
            Button(onClick = onRequestCameraPermission) {
                Text("Grant camera permission")
            }
        }

        if (!notificationPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            StatusCard(
                title = "Notification permission required",
                body = "Notification permission is needed before the app can post notifications."
            )
            Button(onClick = onRequestNotificationPermission) {
                Text("Grant notification permission")
            }
        }

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
                    body = "Turn on Bluetooth, then connect to the car from Android Bluetooth settings."
                )
            }

            else -> {
                val connected = state.connectedDevices.isNotEmpty()
                StatusCard(
                    title = if (connected) "Connected" else "Not connected",
                    body = if (connected) {
                        "${state.connectedDevices.size} Bluetooth device(s) connected."
                    } else {
                        "No Bluetooth devices are currently connected."
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
                    text = "Currently connected devices",
                    style = MaterialTheme.typography.titleMedium
                )
                if (state.connectedDevices.isEmpty()) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    state.connectedDevices.forEach { device ->
                        StatusCard(
                            title = device.name,
                            body = device.address
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Keep this screen open, then connect or disconnect the car Bluetooth from the phone or the car.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
                connectedDevices = listOf(
                    ConnectedBluetoothDevice("Car", "AA:BB:CC:DD:EE:FF")
                ),
                lastEvent = BluetoothEvent(
                    connected = true,
                    deviceName = "Car",
                    deviceAddress = "AA:BB:CC:DD:EE:FF"
                )
            ),
            cameraPermissionGranted = true,
            notificationPermissionGranted = true,
            onRequestPermission = {},
            onRequestCameraPermission = {},
            onRequestNotificationPermission = {}
        )
    }
}
