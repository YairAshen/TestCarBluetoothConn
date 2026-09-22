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
import android.util.Log
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
    val lastEvent: BluetoothEvent? = null,
    val revision: Long = 0L
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
    private var visible = false
    private var hasPermission = false
    private var a2dpProfile: BluetoothProfile? = null
    private var headsetProfile: BluetoothProfile? = null
    private var previousConnectedAddresses = emptySet<String>()
    private var publishedDevices: List<TrackedBluetoothDevice> = emptyList()
    private var revision = 0L
    private var a2dpGeneration = 0
    private var headsetGeneration = 0

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            handler.post {
                when (profile) {
                    BluetoothProfile.A2DP -> {
                        a2dpProfile = proxy
                        a2dpGeneration += 1
                        Log.i(TAG, "profileProxy connected A2DP gen=$a2dpGeneration")
                    }
                    BluetoothProfile.HEADSET -> {
                        headsetProfile = proxy
                        headsetGeneration += 1
                        Log.i(TAG, "profileProxy connected HEADSET gen=$headsetGeneration")
                    }
                }
                refreshFromProfiles(reason = "profile_proxy_connected:$profile")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            handler.post {
                when (profile) {
                    BluetoothProfile.A2DP -> {
                        a2dpProfile = null
                        Log.i(TAG, "profileProxy disconnected A2DP")
                    }
                    BluetoothProfile.HEADSET -> {
                        headsetProfile = null
                        Log.i(TAG, "profileProxy disconnected HEADSET")
                    }
                }
                refreshFromProfiles(reason = "profile_proxy_disconnected:$profile")
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) {
                Log.i(TAG, "broadcast ignored: null intent")
                return
            }
            if (!hasPermission) {
                Log.i(TAG, "broadcast ignored (no permission): ${intent.action}")
                return
            }
            handleBroadcast(intent)
        }
    }

    fun start(hasPermission: Boolean) {
        this.hasPermission = hasPermission
        visible = true
        registerReceiverIfNeeded()
        if (hasPermission) {
            bindProfileProxies()
            refreshFromProfiles(reason = "tracker_start")
            handler.removeCallbacks(foregroundResync)
            handler.postDelayed(foregroundResync, RESYNC_INTERVAL_MS)
        } else {
            closeProfileProxies()
            previousConnectedAddresses = emptySet()
            publish(emptyList(), reason = "no_permission")
        }
        Log.i(TAG, "tracker start permission=$hasPermission receiverRegistered=$registered")
    }

    fun stop() {
        visible = false
        handler.removeCallbacks(foregroundResync)
        handler.removeCallbacks(delayedRefresh)
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
            Log.i(TAG, "receiver unregistered")
        }
        closeProfileProxies()
        Log.i(TAG, "tracker stop")
    }

    fun permissionGranted() {
        start(hasPermission = true)
    }

    private fun registerReceiverIfNeeded() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            null,
            handler,
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
        Log.i(TAG, "receiver registered for ACL, adapter, A2DP, HEADSET")
    }

    private fun handleBroadcast(intent: Intent) {
        val action = intent.action ?: return
        val device = extraDevice(intent)
        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.i(TAG, "ACL_CONNECTED device=${deviceLabel(device)}")
                onLinkOrProfileEvent(device, reason = "ACL_CONNECTED")
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(TAG, "ACL_DISCONNECTED device=${deviceLabel(device)}")
                onDisconnectEvent(device, reason = "ACL_DISCONNECTED")
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                Log.i(
                    TAG,
                    "A2DP connection state ${profileStateName(previous)} -> ${profileStateName(state)} " +
                        "device=${deviceLabel(device)}"
                )
                if (state == BluetoothProfile.STATE_DISCONNECTED ||
                    state == BluetoothProfile.STATE_DISCONNECTING
                ) {
                    onDisconnectEvent(device, reason = "A2DP_STATE:${profileStateName(state)}")
                } else {
                    onLinkOrProfileEvent(device, reason = "A2DP_STATE:${profileStateName(state)}")
                }
            }
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                Log.i(
                    TAG,
                    "HEADSET/HFP connection state ${profileStateName(previous)} -> ${profileStateName(state)} " +
                        "device=${deviceLabel(device)}"
                )
                if (state == BluetoothProfile.STATE_DISCONNECTED ||
                    state == BluetoothProfile.STATE_DISCONNECTING
                ) {
                    onDisconnectEvent(device, reason = "HEADSET_STATE:${profileStateName(state)}")
                } else {
                    onLinkOrProfileEvent(device, reason = "HEADSET_STATE:${profileStateName(state)}")
                }
            }
            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                val previous = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_CONNECTION_STATE, -1)
                Log.i(
                    TAG,
                    "adapter ACL connection ${profileStateName(previous)} -> ${profileStateName(state)} " +
                        "device=${deviceLabel(device)}"
                )
                if (state == BluetoothAdapter.STATE_DISCONNECTED ||
                    state == BluetoothAdapter.STATE_DISCONNECTING
                ) {
                    onDisconnectEvent(device, reason = "ADAPTER_CONNECTION:${profileStateName(state)}")
                } else {
                    onLinkOrProfileEvent(device, reason = "ADAPTER_CONNECTION:${profileStateName(state)}")
                }
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                Log.i(TAG, "adapter state ${adapterStateName(state)}")
                if (state == BluetoothAdapter.STATE_OFF ||
                    state == BluetoothAdapter.STATE_TURNING_OFF
                ) {
                    handler.removeCallbacks(delayedRefresh)
                    previousConnectedAddresses = emptySet()
                    lastEvent = null
                    publish(emptyList(), reason = "adapter_off")
                } else if (state == BluetoothAdapter.STATE_ON) {
                    bindProfileProxies()
                    refreshFromProfiles(reason = "adapter_on")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onDisconnectEvent(device: BluetoothDevice?, reason: String) {
        logDeviceProfileStates("after $reason", device)
        // Cached A2DP/HFP proxies often keep reporting CONNECTED after a car is powered off.
        // Rebind them so the next query is live, then drop the device if no profile is CONNECTED.
        rebindProfileProxies()
        refreshFromProfiles(
            triggerDevice = device,
            reason = reason,
            treatAsDisconnectedIfUnverified = device?.address
        )
        handler.removeCallbacks(delayedRefresh)
        handler.postDelayed(delayedRefresh, PROFILE_SETTLE_DELAY_MS)
        handler.postDelayed(delayedRefresh, PROFILE_SETTLE_DELAY_MS * 3)
    }

    private fun onLinkOrProfileEvent(device: BluetoothDevice?, reason: String) {
        logDeviceProfileStates(reason, device)
        rebindProfileProxies()
        refreshFromProfiles(triggerDevice = device, reason = reason)
        handler.removeCallbacks(delayedRefresh)
        handler.postDelayed(delayedRefresh, PROFILE_SETTLE_DELAY_MS)
    }

    private val delayedRefresh = Runnable {
        refreshFromProfiles(reason = "delayed_profile_verify")
    }

    private val foregroundResync = object : Runnable {
        override fun run() {
            if (!visible || !hasPermission) return
            // Rebind on a timer so a silent car power-off is noticed without leaving the app.
            // This is not onStart/onResume detection; it runs the whole time the Activity is visible.
            rebindProfileProxies()
            handler.postDelayed(this, RESYNC_INTERVAL_MS)
        }
    }

    private fun bindProfileProxies() {
        val localAdapter = adapter ?: return
        if (!hasPermission) return
        if (a2dpProfile == null) {
            Log.i(TAG, "binding A2DP profile proxy")
            localAdapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        }
        if (headsetProfile == null) {
            Log.i(TAG, "binding HEADSET profile proxy")
            localAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
        }
    }

    private fun rebindProfileProxies() {
        Log.i(TAG, "rebinding A2DP/HEADSET profile proxies")
        closeProfileProxies()
        bindProfileProxies()
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
    private fun refreshFromProfiles(
        triggerDevice: BluetoothDevice? = null,
        reason: String,
        treatAsDisconnectedIfUnverified: String? = null
    ) {
        val localAdapter = adapter
        if (localAdapter == null || !hasPermission || !localAdapter.isEnabled) {
            previousConnectedAddresses = emptySet()
            publish(emptyList(), reason = "$reason:adapter_unavailable")
            return
        }

        val connectedByAddress = linkedMapOf<String, MutableSet<String>>()
        val liveDevices = linkedMapOf<String, BluetoothDevice>()

        addConnectedFromProxy(a2dpProfile, PROFILE_A2DP, connectedByAddress, liveDevices)
        addConnectedFromProxy(headsetProfile, PROFILE_HEADSET, connectedByAddress, liveDevices)
        addConnectedFromGatt(connectedByAddress, liveDevices)

        val proxiesReady = a2dpProfile != null || headsetProfile != null
        val explicitDisconnect = treatAsDisconnectedIfUnverified != null
        if (!proxiesReady && !explicitDisconnect && reason != "tracker_start" && reason != "adapter_on") {
            Log.i(TAG, "refresh skipped until A2DP/HFP proxy is ready ($reason)")
            return
        }

        if (treatAsDisconnectedIfUnverified != null) {
            val stillVerified = deviceHasConnectedProfile(treatAsDisconnectedIfUnverified)
            Log.i(
                TAG,
                "ACL_DISCONNECTED verify address=$treatAsDisconnectedIfUnverified " +
                    "stillHasConnectedProfile=$stillVerified " +
                    "a2dp=${a2dpProfile != null} headset=${headsetProfile != null}"
            )
            if (!stillVerified) {
                connectedByAddress.remove(treatAsDisconnectedIfUnverified)
            }
        }

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
        publish(devices, reason = reason)
    }

    @SuppressLint("MissingPermission")
    private fun deviceHasConnectedProfile(address: String): Boolean {
        val match: (BluetoothDevice) -> Boolean = { it.address == address }
        a2dpProfile?.connectedDevices?.firstOrNull(match)?.let { device ->
            if (a2dpProfile?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                return true
            }
        }
        headsetProfile?.connectedDevices?.firstOrNull(match)?.let { device ->
            if (headsetProfile?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                return true
            }
        }
        bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
            ?.firstOrNull(match)
            ?.let { device ->
                if (bluetoothManager.getConnectionState(device, BluetoothProfile.GATT) ==
                    BluetoothProfile.STATE_CONNECTED
                ) {
                    return true
                }
            }
        return false
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
            val state = proxy.getConnectionState(device)
            if (state == BluetoothProfile.STATE_CONNECTED) {
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
    private fun logDeviceProfileStates(reason: String, device: BluetoothDevice?) {
        if (device == null) {
            Log.i(TAG, "profile snapshot ($reason): device=null")
            return
        }
        val a2dp = a2dpProfile?.getConnectionState(device) ?: -1
        val hfp = headsetProfile?.getConnectionState(device) ?: -1
        val gatt = bluetoothManager?.getConnectionState(device, BluetoothProfile.GATT) ?: -1
        Log.i(
            TAG,
            "profile snapshot ($reason) device=${deviceLabel(device)} " +
                "A2DP=${profileStateName(a2dp)} HFP=${profileStateName(hfp)} GATT=${profileStateName(gatt)}"
        )
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
                lastEvent = BluetoothEvent(true, triggerDevice.safeName(), triggerAddress)
            }
            triggerAddress != null && triggerAddress in removed -> {
                lastEvent = BluetoothEvent(false, triggerDevice.safeName(), triggerAddress)
            }
            added.isNotEmpty() -> {
                val address = added.first()
                lastEvent = BluetoothEvent(true, displayName(address, triggerDevice), address)
            }
            removed.isNotEmpty() -> {
                val address = removed.first()
                lastEvent = BluetoothEvent(false, displayName(address, triggerDevice), address)
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

    @SuppressLint("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice?): String {
        if (device == null) return "unknown"
        return "${device.safeName()} (${device.address})"
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
    private fun publish(devices: List<TrackedBluetoothDevice>, reason: String) {
        publishedDevices = devices
        revision += 1
        val enabled = hasPermission && adapter?.isEnabled == true
        val connected = devices.filter { it.connected }
        Log.i(
            TAG,
            "publish reason=$reason revision=$revision enabled=$enabled " +
                "connected=[${connected.joinToString { "${it.name}:${it.address}:${it.connectedProfiles}" }}] " +
                "pairedDisconnected=[${devices.filter { it.paired && !it.connected }.joinToString { it.name }}]"
        )
        val state = BluetoothUiState(
            bluetoothAvailable = adapter != null,
            bluetoothEnabled = enabled,
            hasPermission = hasPermission,
            devices = devices,
            lastEvent = lastEvent,
            revision = revision
        )
        handler.post {
            onStateChanged(state)
        }
    }

    private fun profileStateName(state: Int): String {
        return when (state) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            -1 -> "UNAVAILABLE"
            else -> "UNKNOWN($state)"
        }
    }

    private fun adapterStateName(state: Int): String {
        return when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN($state)"
        }
    }

    companion object {
        const val TAG = "BtCarConn"
        private const val PROFILE_SETTLE_DELAY_MS = 400L
        private const val RESYNC_INTERVAL_MS = 1000L
        private const val PROFILE_A2DP = "A2DP"
        private const val PROFILE_HEADSET = "HFP"
        private const val PROFILE_GATT = "GATT"
    }
}
