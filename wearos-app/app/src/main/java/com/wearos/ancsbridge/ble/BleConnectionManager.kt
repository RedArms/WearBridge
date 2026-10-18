package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wearos.ancsbridge.model.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedList

/**
 * Manages the BLE GATT connection to an iPhone and the ANCS subscription lifecycle.
 *
 * Flow: connect → bond → discover services → subscribe DS → subscribe NS → active
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "BleConnectionManager"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 3_000L
        private const val TARGET_MTU = 512
        private const val DEFAULT_ATT_MTU = 23
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 2
        private const val HEALTH_WRITE_TIMEOUT_MS = 3_000L
        private const val MAX_DESCRIPTOR_WRITE_ATTEMPTS = 10
        private const val DESCRIPTOR_RETRY_DELAY_MS = 200L
    }

    /** Outcome of [writeHealthData]. */
    enum class HealthWriteResult {
        /** The iPhone acknowledged the batch. */
        SUCCESS,
        /** Another GATT operation is in flight; retry shortly. */
        BUSY,
        /** The companion app (with health support) is not reachable right now. */
        UNSUPPORTED,
        /** The iPhone rejected the write (e.g. Apple Health sync is off), or the link dropped. */
        FAILED,
        /** No answer in time (e.g. the iPhone app is being relaunched); the write may still complete. */
        TIMEOUT
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _notificationSourceEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val notificationSourceEvents: SharedFlow<ByteArray> = _notificationSourceEvents.asSharedFlow()

    private val _dataSourceEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val dataSourceEvents: SharedFlow<ByteArray> = _dataSourceEvents.asSharedFlow()

    private val _callStateEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val callStateEvents: SharedFlow<ByteArray> = _callStateEvents.asSharedFlow()

    private val _wakeAlarmEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    val wakeAlarmEvents: SharedFlow<ByteArray> = _wakeAlarmEvents.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var reconnectJob: Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var autoReconnect = true

    // CCCD write queue — must serialize descriptor writes
    private val descriptorWriteQueue = LinkedList<Pair<BluetoothGattCharacteristic, BluetoothGattDescriptor>>()
    private var isWritingDescriptor = false

    /** ATT MTU negotiated with the iPhone; sizes health batches. */
    @Volatile
    var negotiatedMtu: Int = DEFAULT_ATT_MTU
        private set

    @Volatile
    private var pendingHealthWrite: CompletableDeferred<Boolean>? = null
    private var descriptorWriteAttempts = 0

    private var serviceDiscoveryRetries = 0
    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null

    // Bond events come for every device the watch pairs with (earbuds...): only the iPhone's matter.
    private fun isTarget(device: BluetoothDevice) = device.address == targetDevice?.address

    val bondStateReceiver = BondStateReceiver(
        onBonded = { device ->
            if (!isTarget(device)) return@BondStateReceiver
            Log.i(TAG, "Bond completed, re-discovering services")
            // Small delay after bonding before service discovery
            scope.launch {
                delay(1000)
                gatt?.discoverServices()
            }
        },
        onBondFailed = { device ->
            if (!isTarget(device)) return@BondStateReceiver
            Log.e(TAG, "Bond failed")
            _connectionState.value = ConnectionState.Error("Pairing failed")
            disconnect()
        }
    )

    private val gattCallback = GattCallback(
        onConnected = { gatt ->
            reconnectDelay = INITIAL_RECONNECT_DELAY_MS
            val displayName = if (isTruncatedBleName(gatt.device.name ?: "")) "iPhone" else (gatt.device.name ?: "iPhone")
            _connectionState.value = ConnectionState.Connecting(displayName)

            // Request higher MTU for less fragmentation
            gatt.requestMtu(TARGET_MTU)
        },
        onDisconnected = { gatt, status ->
            Log.i(TAG, "Disconnected, status=$status")
            _connectionState.value = ConnectionState.Disconnected()
            pendingHealthWrite?.complete(false)
            negotiatedMtu = DEFAULT_ATT_MTU
            clearCharacteristics()
            gatt.close()
            this.gatt = null

            if (autoReconnect && targetDevice != null) {
                scheduleReconnect()
            }
        },
        onServicesDiscoveredCallback = { gatt, status ->
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _connectionState.value = ConnectionState.Error("Service discovery failed")
                // Drop the link so the normal reconnect starts over, instead of
                // leaving a live link with nothing subscribed.
                gatt.disconnect()
                return@GattCallback
            }

            val ancsService = gatt.getService(AncsConstants.ANCS_SERVICE_UUID)
            if (ancsService == null) {
                // ANCS not visible — likely not bonded yet
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "ANCS not found, initiating bonding")
                    val bondDisplayName = if (isTruncatedBleName(gatt.device.name ?: "")) "iPhone" else (gatt.device.name ?: "iPhone")
                    _connectionState.value = ConnectionState.Bonding(bondDisplayName)
                    gatt.device.createBond()
                } else {
                    // Bonded but ANCS still not visible — retry once, then give up
                    // (this device may not be an iPhone)
                    serviceDiscoveryRetries++
                    if (serviceDiscoveryRetries <= MAX_SERVICE_DISCOVERY_RETRIES) {
                        Log.w(TAG, "Bonded but ANCS not found, retry $serviceDiscoveryRetries/$MAX_SERVICE_DISCOVERY_RETRIES")
                        scope.launch {
                            gatt.disconnect()
                            delay(2000)
                            connect(gatt.device, autoReconnect = false)
                        }
                    } else {
                        Log.e(TAG, "ANCS not found after $MAX_SERVICE_DISCOVERY_RETRIES retries — not an iPhone?")
                        _connectionState.value = ConnectionState.Error("Not an iPhone (no ANCS)")
                        scope.launch {
                            gatt.disconnect()
                            gatt.close()
                            this@BleConnectionManager.gatt = null
                        }
                    }
                }
                return@GattCallback
            }

            // ANCS service found — get characteristics
            Log.i(TAG, "ANCS service found!")
            serviceDiscoveryRetries = 0

            // If already subscribed (rediscovery for companion service), refresh the
            // companion side only: ANCS stays subscribed (gotcha 12).
            if (notificationSourceChar != null && dataSourceChar != null) {
                Log.i(TAG, "ANCS already subscribed, checking companion service")
                // Fresh characteristic objects, in case handles moved.
                ancsService.getCharacteristic(AncsConstants.NOTIFICATION_SOURCE_UUID)?.let { notificationSourceChar = it }
                ancsService.getCharacteristic(AncsConstants.CONTROL_POINT_UUID)?.let { controlPointChar = it }
                ancsService.getCharacteristic(AncsConstants.DATA_SOURCE_UUID)?.let { dataSourceChar = it }
                readCompanionStatus()
                // The companion app may have republished its service: subscribe again.
                scope.launch {
                    delay(500)
                    subscribeToCallState()
                    subscribeToWakeAlarm()
                }
                return@GattCallback
            }

            notificationSourceChar = ancsService.getCharacteristic(AncsConstants.NOTIFICATION_SOURCE_UUID)
            controlPointChar = ancsService.getCharacteristic(AncsConstants.CONTROL_POINT_UUID)
            dataSourceChar = ancsService.getCharacteristic(AncsConstants.DATA_SOURCE_UUID)

            if (notificationSourceChar == null || dataSourceChar == null) {
                Log.e(TAG, "Missing ANCS characteristics")
                _connectionState.value = ConnectionState.Error("ANCS characteristics missing")
                return@GattCallback
            }

            // Subscribe to Data Source FIRST (Apple recommendation),
            // then Notification Source
            subscribeToCharacteristic(dataSourceChar!!)
        },
        onNotificationSourceChanged = { data ->
            _notificationSourceEvents.tryEmit(data)
        },
        onDataSourceChanged = { data ->
            _dataSourceEvents.tryEmit(data)
        },
        onDescriptorWritten = { descriptor, status ->
            isWritingDescriptor = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor write failed: $status for ${descriptor.characteristic.uuid}")
                when (descriptor.characteristic.uuid) {
                    // Without these there is no ANCS session: start over.
                    AncsConstants.DATA_SOURCE_UUID, AncsConstants.NOTIFICATION_SOURCE_UUID -> gatt?.disconnect()
                    // Companion subscriptions are optional: carry on with the queue.
                    else -> processDescriptorWriteQueue()
                }
                return@GattCallback
            }

            val charUuid = descriptor.characteristic.uuid
            when (charUuid) {
                AncsConstants.DATA_SOURCE_UUID -> {
                    Log.i(TAG, "Subscribed to Data Source, now subscribing to Notification Source")
                    notificationSourceChar?.let { subscribeToCharacteristic(it) }
                }
                AncsConstants.NOTIFICATION_SOURCE_UUID -> {
                    Log.i(TAG, "Subscribed to Notification Source — ANCS session active!")
                    // The bonded-but-no-ANCS retry connects with autoReconnect off; a working
                    // session should reconnect on its own again.
                    if (targetDevice != null) autoReconnect = true
                    // Show initial name (may be truncated BLE name)
                    val deviceName = resolveDeviceName()
                    _connectionState.value = ConnectionState.Connected(deviceName)
                    // Read the real iPhone name from GAP service
                    readGapDeviceName()
                    // Read companion status to notify iPhone app
                    readCompanionStatus()
                    // Subscribe to call state and wake alarm from companion app (if available)
                    scope.launch {
                        delay(500) // Small delay to avoid GATT queue congestion
                        subscribeToCallState()
                        subscribeToWakeAlarm()
                    }
                }
            }

            // Process next queued descriptor write
            processDescriptorWriteQueue()
        },
        onCharacteristicWritten = { characteristic, status ->
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: $status for ${characteristic.uuid}")
            }
            if (characteristic.uuid == AncsConstants.HEALTH_DATA_CHARACTERISTIC_UUID) {
                pendingHealthWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        },
        onMtuChanged = { mtu, status ->
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            // After MTU negotiation, discover services
            gatt?.discoverServices()
        },
        onCharacteristicReadCallback = { characteristic, value ->
            onCharacteristicRead(characteristic.uuid, value)
        },
        onCallStateChanged = { data ->
            _callStateEvents.tryEmit(data)
        },
        onWakeAlarmChanged = { data ->
            _wakeAlarmEvents.tryEmit(data)
        },
        onServicesChangedCallback = { changedGatt ->
            Log.i(TAG, "iPhone services changed, re-discovering")
            changedGatt.discoverServices()
        }
    )

    /**
     * Connect to a BLE device (iPhone).
     */
    fun connect(device: BluetoothDevice, autoReconnect: Boolean = true) {
        // Guard: skip if already connected or connecting to this device
        val currentState = _connectionState.value
        if (gatt != null && gatt?.device?.address == device.address &&
            (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting)) {
            Log.d(TAG, "Already connected/connecting to ${device.address}, skipping")
            return
        }

        reconnectJob?.cancel()
        this.autoReconnect = autoReconnect
        this.targetDevice = device
        this.serviceDiscoveryRetries = 0

        _connectionState.value = ConnectionState.Connecting(if (isTruncatedBleName(device.name ?: "")) "iPhone" else (device.name ?: "iPhone"))

        // A new link starts from scratch: stale characteristics would make it skip the ANCS subscription.
        clearCharacteristics()
        gatt?.close()
        gatt = connectGatt(device, autoConnect = false) // faster initial connection
    }

    /**
     * Reconnect to a previously bonded device using autoConnect.
     */
    fun reconnect(device: BluetoothDevice) {
        // Guard: skip if already connected or connecting to this device
        val currentState = _connectionState.value
        if (gatt != null && gatt?.device?.address == device.address &&
            (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting)) {
            Log.d(TAG, "Already connected/connecting to ${device.address}, skipping reconnect")
            return
        }

        reconnectJob?.cancel()
        this.targetDevice = device
        this.autoReconnect = true
        this.serviceDiscoveryRetries = 0

        _connectionState.value = ConnectionState.Connecting(if (isTruncatedBleName(device.name ?: "")) "iPhone" else (device.name ?: "iPhone"))

        clearCharacteristics()
        gatt?.close()
        gatt = connectGatt(device, autoConnect = true) // chipset whitelist, battery efficient
    }

    /**
     * GATT callbacks run on the main thread, like the coroutines that share this
     * class's state (characteristics, descriptor queue, reconnect job).
     * Null (Bluetooth off) is treated like a disconnect.
     */
    private fun connectGatt(device: BluetoothDevice, autoConnect: Boolean): BluetoothGatt? {
        val connection = device.connectGatt(
            context,
            autoConnect,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            Handler(Looper.getMainLooper())
        )
        if (connection == null) {
            Log.w(TAG, "connectGatt failed (Bluetooth off?)")
            _connectionState.value = ConnectionState.Disconnected()
            if (autoReconnect) scheduleReconnect()
        }
        return connection
    }

    /** Bluetooth was turned back on: reconnect right away instead of waiting for the backoff. */
    fun onBluetoothOn() {
        val device = targetDevice ?: return // nothing to reconnect to, or the user disconnected
        if (!autoReconnect) return
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        reconnect(device)
    }

    /** Bluetooth was turned off: the link is gone. */
    fun onBluetoothOff() {
        reconnectJob?.cancel()
        clearCharacteristics()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Disconnected()
    }

    /**
     * Write to the ANCS Control Point characteristic.
     */
    fun writeControlPoint(data: ByteArray): Boolean {
        val gatt = this.gatt ?: return false
        val char = controlPointChar ?: return false

        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        char.value = data
        return gatt.writeCharacteristic(char)
    }

    /** Cached real device name read from GAP characteristic */
    private var cachedDeviceName: String? = null

    /**
     * Resolve a user-friendly device name from the bonded device list or GATT connection.
     * BLE advertised names are often truncated (e.g., "AncsBrid" instead of "iPhone").
     */
    private fun resolveDeviceName(): String {
        // Return cached GAP name if available
        cachedDeviceName?.let { return it }

        // Check bonded devices for a full name
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val bondedName = adapter?.bondedDevices?.firstOrNull { device ->
            device.address == gatt?.device?.address
        }?.name

        // Prefer the bonded name, fall back to GATT device name
        val rawName = bondedName ?: gatt?.device?.name ?: "iPhone"

        // If the name is a truncated BLE name, show "iPhone" instead
        return if (isTruncatedBleName(rawName)) "iPhone" else rawName
    }

    private fun isTruncatedBleName(name: String): Boolean {
        return name.startsWith("AncsBrid", ignoreCase = true) ||
            name.startsWith("AppleToWe", ignoreCase = true) ||
            name.startsWith("WearBrid", ignoreCase = true) ||
            name.startsWith("WearBridge", ignoreCase = true)
    }

    /**
     * Read the real device name from the GAP (Generic Access Profile) service.
     * The Device Name characteristic (0x2A00) contains the actual iPhone name
     * (e.g., "Karthik's iPhone Max") rather than the truncated BLE advertising name.
     */
    fun readGapDeviceName() {
        val gatt = this.gatt ?: return
        val gapServiceUuid = java.util.UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val deviceNameUuid = java.util.UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        val service = gatt.getService(gapServiceUuid) ?: return
        val char = service.getCharacteristic(deviceNameUuid) ?: return
        gatt.readCharacteristic(char)
        Log.i(TAG, "Reading GAP Device Name characteristic")
    }

    /**
     * Called from GattCallback when a characteristic read completes.
     * If it's the GAP Device Name, cache and broadcast it.
     */
    fun onCharacteristicRead(characteristicUuid: java.util.UUID, value: ByteArray) {
        val deviceNameUuid = java.util.UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        if (characteristicUuid == deviceNameUuid) {
            val name = String(value, Charsets.UTF_8)
            if (name.isNotBlank() && !isTruncatedBleName(name)) {
                cachedDeviceName = name
                Log.i(TAG, "GAP Device Name: $name")
                _connectionState.value = ConnectionState.Connected(name)
                onDeviceNameResolved?.invoke(name)
            }
        }
    }

    /** Callback to notify the UI when the real device name is resolved */
    var onDeviceNameResolved: ((String) -> Unit)? = null

    /**
     * Read the companion app's status characteristic to notify the iPhone app
     * that the watch has connected. This triggers didReceiveRead on the iOS side.
     */
    private fun readCompanionStatus() {
        val gatt = this.gatt ?: return
        val companionServiceUuid = java.util.UUID.fromString("A0E3D5B1-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        val statusCharUuid = java.util.UUID.fromString("A0E3D5B2-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        val service = gatt.getService(companionServiceUuid)
        if (service != null) {
            val char = service.getCharacteristic(statusCharUuid)
            if (char != null) {
                gatt.readCharacteristic(char)
                Log.i(TAG, "Reading companion status characteristic to notify iPhone app")
            } else {
                Log.d(TAG, "Companion status characteristic not found")
            }
        } else {
            Log.d(TAG, "Companion service not found — iPhone companion app may not be running")
        }
    }

    /**
     * Subscribe to the call state characteristic from the iOS companion app.
     * When enabled, the companion pushes real-time call state (ringing/active/ended).
     */
    fun subscribeToCallState() {
        val gatt = this.gatt ?: return
        val service = gatt.getService(AncsConstants.COMPANION_SERVICE_UUID) ?: run {
            Log.d(TAG, "subscribeToCallState: companion service not found")
            return
        }
        val char = service.getCharacteristic(AncsConstants.CALL_STATE_CHARACTERISTIC_UUID) ?: run {
            Log.d(TAG, "subscribeToCallState: call state characteristic not found")
            return
        }
        subscribeToCharacteristic(char)
        Log.i(TAG, "Subscribing to call state characteristic")
    }

    /**
     * Subscribe to the wake alarm characteristic: the companion app says when the
     * iPhone's wake-up alarm rings and stops (driven by a Shortcuts automation).
     */
    fun subscribeToWakeAlarm() {
        val gatt = this.gatt ?: return
        val char = gatt.getService(AncsConstants.COMPANION_SERVICE_UUID)
            ?.getCharacteristic(AncsConstants.WAKE_ALARM_CHARACTERISTIC_UUID) ?: run {
            Log.d(TAG, "subscribeToWakeAlarm: wake alarm characteristic not found")
            return
        }
        subscribeToCharacteristic(char)
        Log.i(TAG, "Subscribing to wake alarm characteristic")
    }

    /**
     * Write quick reply payload to the iPhone companion app via BLE.
     * Format: "callerName|replyMessage"
     */
    fun writeQuickReply(payload: String): Boolean {
        val gatt = this.gatt ?: run {
            Log.e(TAG, "writeQuickReply: no GATT connection")
            return false
        }
        val service = gatt.getService(AncsConstants.COMPANION_SERVICE_UUID) ?: run {
            Log.e(TAG, "writeQuickReply: companion service not found")
            return false
        }
        val char = service.getCharacteristic(AncsConstants.QUICK_REPLY_CHARACTERISTIC_UUID) ?: run {
            Log.e(TAG, "writeQuickReply: quick reply characteristic not found")
            return false
        }
        val data = payload.toByteArray(Charsets.UTF_8)
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val success = gatt.writeCharacteristic(char)
        Log.i(TAG, "writeQuickReply: wrote ${data.size} bytes, success=$success")
        return success
    }

    /**
     * Send one health batch (see HealthProtocol) to the companion app and wait for
     * the iPhone's write response. The caller (HealthSyncManager) sends one batch
     * at a time.
     *
     * If the companion service wasn't there when we connected (iPhone app not
     * running), this returns UNSUPPORTED until the iPhone publishes it: its
     * Service Changed indication makes the watch re-discover services
     * (onServiceChanged), without touching the live ANCS subscription.
     */
    suspend fun writeHealthData(payload: ByteArray): HealthWriteResult {
        val gatt = this.gatt ?: return HealthWriteResult.FAILED
        val char = gatt.getService(AncsConstants.COMPANION_SERVICE_UUID)
            ?.getCharacteristic(AncsConstants.HEALTH_DATA_CHARACTERISTIC_UUID)
            ?: return HealthWriteResult.UNSUPPORTED

        val ack = CompletableDeferred<Boolean>()
        pendingHealthWrite = ack
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        char.value = payload
        if (!gatt.writeCharacteristic(char)) {
            pendingHealthWrite = null
            return HealthWriteResult.BUSY
        }
        val acknowledged = withTimeoutOrNull(HEALTH_WRITE_TIMEOUT_MS) { ack.await() }
        pendingHealthWrite = null
        return when (acknowledged) {
            true -> HealthWriteResult.SUCCESS
            false -> HealthWriteResult.FAILED
            null -> HealthWriteResult.TIMEOUT
        }
    }

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        pendingHealthWrite?.complete(false)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        clearCharacteristics()
        targetDevice = null
        _connectionState.value = ConnectionState.Idle
    }

    /**
     * Get the bonded iPhone device if one exists.
     */
    fun getBondedIPhone(): BluetoothDevice? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return null
        return adapter.bondedDevices?.firstOrNull { device ->
            val name = device.name ?: ""
            name.contains("iPhone", ignoreCase = true) ||
                name.contains("AncsBrid", ignoreCase = true) ||
                name.contains("AncsBridge", ignoreCase = true) ||
                name.contains("AppleToWe", ignoreCase = true) ||
                name.contains("AppleToWearOS", ignoreCase = true) ||
                name.contains("WearBridge", ignoreCase = true) ||
                name.contains("WearBrid", ignoreCase = true)
        }
    }

    private fun subscribeToCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = this.gatt ?: return

        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(AncsConstants.CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "CCCD not found for ${characteristic.uuid}")
            return
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        // Queue the write since BLE only allows one outstanding GATT operation at a time
        descriptorWriteQueue.add(characteristic to descriptor)
        if (!isWritingDescriptor) {
            processDescriptorWriteQueue()
        }
    }

    private fun processDescriptorWriteQueue() {
        val gatt = this.gatt ?: return
        val (characteristic, descriptor) = descriptorWriteQueue.peek() ?: return
        isWritingDescriptor = true
        if (gatt.writeDescriptor(descriptor)) {
            descriptorWriteQueue.poll()
            descriptorWriteAttempts = 0
            return
        }
        // Another GATT operation (e.g. a health sync write) is in flight: retry
        // shortly rather than leaving the queue stuck with isWritingDescriptor set.
        isWritingDescriptor = false
        descriptorWriteAttempts++
        if (descriptorWriteAttempts >= MAX_DESCRIPTOR_WRITE_ATTEMPTS) {
            Log.e(TAG, "Giving up on CCCD write for ${characteristic.uuid}")
            descriptorWriteQueue.poll()
            descriptorWriteAttempts = 0
        }
        scope.launch {
            delay(DESCRIPTOR_RETRY_DELAY_MS)
            if (!isWritingDescriptor) processDescriptorWriteQueue()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Scheduling reconnect in ${reconnectDelay}ms")
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            targetDevice?.let { reconnect(it) }
        }
    }

    private fun clearCharacteristics() {
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
        descriptorWriteQueue.clear()
        isWritingDescriptor = false
        descriptorWriteAttempts = 0
    }

    fun destroy() {
        autoReconnect = false
        reconnectJob?.cancel()
        pendingHealthWrite?.complete(false)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
