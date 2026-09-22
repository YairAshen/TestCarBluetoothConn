package com.example.testcarbluetoothconn

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

data class TrackedBluetoothDevice(
    val name: String,
    val address: String,
    val paired: Boolean,
    val connected: Boolean,
    val connectedProfiles: List<String>
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
    val devices: List<TrackedBluetoothDevice> = emptyList(),
    val lastEvent: BluetoothEvent? = null
) {
    val connectedDevices: List<TrackedBluetoothDevice>
        get() = devices.filter { it.connected }
}

class BluetoothConnectionTracker(
    private val context: Context,
    private val onStateChanged: (BluetoothUiState) -> Unit
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var lastEvent: BluetoothEvent? = null
    private var registered = false
    private var hasPermission = false
    private var a2dpProfile: BluetoothProfile? = null
    private var headsetProfile: BluetoothProfile? = null
    private var previousConnectedAddresses = emptySet<String>()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProfile = proxy
                BluetoothProfile.HEADSET -> headsetProfile = proxy
            }
            refreshFromProfiles()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProfile = null
                BluetoothProfile.HEADSET -> headsetProfile = null
            }
            refreshFromProfiles()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !hasPermission) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val device = extraDevice(intent)
                    // Profile state often lags the ACL broadcast by a short time.
                    refreshFromProfiles(triggerDevice = device)
                    handler.removeCallbacks(delayedRefresh)
                    handler.postDelayed(delayedRefresh, PROFILE_SETTLE_DELAY_MS)
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )
                    if (state == BluetoothAdapter.STATE_OFF ||
                        state == BluetoothAdapter.STATE_TURNING_OFF
                    ) {
                        handler.removeCallbacks(delayedRefresh)
                        previousConnectedAddresses = emptySet()
                        lastEvent = null
                        publish(emptyList())
                    } else if (state == BluetoothAdapter.STATE_ON) {
                        bindProfileProxies()
                        refreshFromProfiles()
                    }
                }
            }
        }
    }

    private val delayedRefresh = Runnable { refreshFromProfiles() }

    fun start(hasPermission: Boolean) {
        this.hasPermission = hasPermission
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
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
            bindProfileProxies()
            refreshFromProfiles()
        } else {
            closeProfileProxies()
            previousConnectedAddresses = emptySet()
            publish(emptyList())
        }
    }

    fun stop() {
        handler.removeCallbacks(delayedRefresh)
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
        closeProfileProxies()
    }

    fun permissionGranted() {
        start(hasPermission = true)
    }

    private fun bindProfileProxies() {
        val localAdapter = adapter ?: return
        if (!hasPermission) return
        if (a2dpProfile == null) {
            localAdapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        }
        if (headsetProfile == null) {
            localAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
        }
    }

    private fun closeProfileProxies() {
        val localAdapter = adapter
        a2dpProfile?.let { proxy ->
            localAdapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        }
        headsetProfile?.let { proxy ->
            localAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
        }
        a2dpProfile = null
        headsetProfile = null
    }

    @SuppressLint("MissingPermission")
    private fun refreshFromProfiles(triggerDevice: BluetoothDevice? = null) {
        val localAdapter = adapter
        if (localAdapter == null || !hasPermission || !localAdapter.isEnabled) {
            previousConnectedAddresses = emptySet()
            publish(emptyList())
            return
        }

        val connectedByAddress = linkedMapOf<String, MutableSet<String>>()
        val liveDevices = linkedMapOf<String, BluetoothDevice>()

        addConnectedFromProxy(a2dpProfile, PROFILE_A2DP, connectedByAddress, liveDevices)
        addConnectedFromProxy(headsetProfile, PROFILE_HEADSET, connectedByAddress, liveDevices)
        addConnectedFromGatt(connectedByAddress, liveDevices)

        val pairedDevices = localAdapter.bondedDevices.orEmpty()
        val pairedByAddress = pairedDevices.associateBy { it.address }
        val allAddresses = LinkedHashSet<String>().apply {
            addAll(pairedByAddress.keys)
            addAll(connectedByAddress.keys)
        }

        val devices = allAddresses.mapNotNull { address ->
            if (address.isBlank()) return@mapNotNull null
            val device = liveDevices[address]
                ?: pairedByAddress[address]
                ?: triggerDevice.takeIf { it?.address == address }
            val profiles = connectedByAddress[address].orEmpty().sorted()
            TrackedBluetoothDevice(
                name = device?.safeName() ?: address,
                address = address,
                paired = pairedByAddress.containsKey(address),
                connected = profiles.isNotEmpty(),
                connectedProfiles = profiles
            )
        }

        val connectedAddresses = devices.filter { it.connected }.map { it.address }.toSet()
        updateLastEvent(triggerDevice, connectedAddresses)
        previousConnectedAddresses = connectedAddresses
        publish(devices)
    }

    @SuppressLint("MissingPermission")
    private fun addConnectedFromProxy(
        proxy: BluetoothProfile?,
        profileLabel: String,
        into: MutableMap<String, MutableSet<String>>,
        liveDevices: MutableMap<String, BluetoothDevice>
    ) {
        if (proxy == null) return
        proxy.connectedDevices.forEach { device ->
            if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                val address = device.address.orEmpty()
                if (address.isNotBlank()) {
                    into.getOrPut(address) { mutableSetOf() }.add(profileLabel)
                    liveDevices[address] = device
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addConnectedFromGatt(
        into: MutableMap<String, MutableSet<String>>,
        liveDevices: MutableMap<String, BluetoothDevice>
    ) {
        val manager = bluetoothManager ?: return
        manager.getConnectedDevices(BluetoothProfile.GATT).forEach { device ->
            if (manager.getConnectionState(device, BluetoothProfile.GATT) ==
                BluetoothProfile.STATE_CONNECTED
            ) {
                val address = device.address.orEmpty()
                if (address.isNotBlank()) {
                    into.getOrPut(address) { mutableSetOf() }.add(PROFILE_GATT)
                    liveDevices[address] = device
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateLastEvent(
        triggerDevice: BluetoothDevice?,
        connectedAddresses: Set<String>
    ) {
        val added = connectedAddresses - previousConnectedAddresses
        val removed = previousConnectedAddresses - connectedAddresses
        val triggerAddress = triggerDevice?.address
        when {
            triggerAddress != null && triggerAddress in added -> {
                lastEvent = BluetoothEvent(
                    connected = true,
                    deviceName = triggerDevice.safeName(),
                    deviceAddress = triggerAddress
                )
            }
            triggerAddress != null && triggerAddress in removed -> {
                lastEvent = BluetoothEvent(
                    connected = false,
                    deviceName = triggerDevice.safeName(),
                    deviceAddress = triggerAddress
                )
            }
            added.isNotEmpty() -> {
                val address = added.first()
                lastEvent = BluetoothEvent(
                    connected = true,
                    deviceName = displayName(address, triggerDevice),
                    deviceAddress = address
                )
            }
            removed.isNotEmpty() -> {
                val address = removed.first()
                lastEvent = BluetoothEvent(
                    connected = false,
                    deviceName = displayName(address, triggerDevice),
                    deviceAddress = address
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun displayName(address: String, triggerDevice: BluetoothDevice?): String {
        if (triggerDevice?.address == address) return triggerDevice.safeName()
        return adapter?.bondedDevices
            ?.firstOrNull { it.address == address }
            ?.safeName()
            ?: address
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
    private fun publish(devices: List<TrackedBluetoothDevice>) {
        val enabled = hasPermission && adapter?.isEnabled == true
        onStateChanged(
            BluetoothUiState(
                bluetoothAvailable = adapter != null,
                bluetoothEnabled = enabled,
                hasPermission = hasPermission,
                devices = devices,
                lastEvent = lastEvent
            )
        )
    }

    companion object {
        private const val PROFILE_SETTLE_DELAY_MS = 400L
        private const val PROFILE_A2DP = "A2DP"
        private const val PROFILE_HEADSET = "HFP"
        private const val PROFILE_GATT = "GATT"
    }
}
