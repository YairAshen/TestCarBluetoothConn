package com.example.testcarbluetoothconn

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat

data class ConnectedBluetoothDevice(
    val name: String,
    val address: String
)

data class BluetoothEvent(
    val connected: Boolean,
    val deviceName: String,
    val deviceAddress: String?
)

data class BluetoothUiState(
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val hasPermission: Boolean = false,
    val connectedDevices: List<ConnectedBluetoothDevice> = emptyList(),
    val lastEvent: BluetoothEvent? = null
)

class BluetoothConnectionTracker(
    private val context: Context,
    private val onStateChanged: (BluetoothUiState) -> Unit
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val connectedByAddress = linkedMapOf<String, ConnectedBluetoothDevice>()
    private var lastEvent: BluetoothEvent? = null
    private var registered = false
    private var hasPermission = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !hasPermission) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = extraDevice(intent) ?: return
                    handleAclChange(device, connected = true)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = extraDevice(intent) ?: return
                    handleAclChange(device, connected = false)
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )
                    if (state == BluetoothAdapter.STATE_OFF ||
                        state == BluetoothAdapter.STATE_TURNING_OFF
                    ) {
                        connectedByAddress.clear()
                        publish()
                    } else if (state == BluetoothAdapter.STATE_ON) {
                        refreshConnectedProfiles()
                    }
                }
            }
        }
    }

    fun start(hasPermission: Boolean) {
        this.hasPermission = hasPermission
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        }
        if (hasPermission) {
            refreshConnectedProfiles()
        } else {
            connectedByAddress.clear()
            publish()
        }
    }

    fun stop() {
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }

    fun permissionGranted() {
        start(hasPermission = true)
    }

    @SuppressLint("MissingPermission")
    private fun handleAclChange(device: BluetoothDevice, connected: Boolean) {
        val name = device.safeName()
        val address = device.address.orEmpty()
        if (connected) {
            connectedByAddress[address] = ConnectedBluetoothDevice(name, address)
        } else {
            connectedByAddress.remove(address)
        }
        lastEvent = BluetoothEvent(
            connected = connected,
            deviceName = name,
            deviceAddress = address.ifBlank { null }
        )
        publish()
    }

    @SuppressLint("MissingPermission")
    private fun refreshConnectedProfiles() {
        val localAdapter = adapter
        if (localAdapter == null || !hasPermission || !localAdapter.isEnabled) {
            connectedByAddress.clear()
            publish()
            return
        }

        val profiles = listOf(
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET,
            BluetoothProfile.GATT
        )
        profiles.forEach { profile ->
            localAdapter.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(connectedProfile: Int, proxy: BluetoothProfile) {
                        if (!hasPermission) {
                            localAdapter.closeProfileProxy(connectedProfile, proxy)
                            return
                        }
                        proxy.connectedDevices.forEach { device ->
                            val address = device.address.orEmpty()
                            if (address.isNotBlank()) {
                                connectedByAddress[address] = ConnectedBluetoothDevice(
                                    name = device.safeName(),
                                    address = address
                                )
                            }
                        }
                        localAdapter.closeProfileProxy(connectedProfile, proxy)
                        publish()
                    }

                    override fun onServiceDisconnected(profile: Int) = Unit
                },
                profile
            )
        }
        publish()
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeName(): String {
        val name = name?.takeIf { it.isNotBlank() }
        return name ?: address ?: "Unknown device"
    }

    private fun extraDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun publish() {
        val enabled = hasPermission && adapter?.isEnabled == true
        onStateChanged(
            BluetoothUiState(
                bluetoothAvailable = adapter != null,
                bluetoothEnabled = enabled,
                hasPermission = hasPermission,
                connectedDevices = connectedByAddress.values.toList(),
                lastEvent = lastEvent
            )
        )
    }
}
