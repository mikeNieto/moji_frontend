package com.mhm.moji_frontend

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.mhm.moji_frontend.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * BleManager: Manages Bluetooth Low Energy connection with the ESP32 robot.
 *
 * - Scans for "RobotESP32" device
 * - Saves MAC address in AppPreferences after first pairing
 * - Reconnects automatically when device is in range
 * - Manages GATT connection with Nordic UART Service (NUS)
 * - Sends JSON commands via TX characteristic
 * - Receives telemetry via RX notification characteristic
 */
class BleManager(
    private val context: Context,
    private val preferences: AppPreferences
) {
    companion object {
        private const val TAG = "BleManager"

        // Device name to scan for
        const val DEVICE_NAME = "RobotESP32"

        // Nordic UART Service UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Write (Android → ESP32)
        val RX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Notify (ESP32 → Android)

        // Client Characteristic Configuration Descriptor UUID (standard)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // Max bytes per write (MTU negotiated to 512)
        private const val MAX_MTU = 512

        // Reconnect delay after disconnection
        private const val RECONNECT_DELAY_MS = 5000L
        private const val CONNECTION_ATTEMPT_TIMEOUT_MS = 8000L

        // Scan timeout — if no device found in this time, wait and retry
        private const val SCAN_TIMEOUT_MS = 15000L
        private const val MAX_DIRECT_CONNECT_FAILURES = 1
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionAttemptTimeoutJob: Job? = null
    private var isManuallyDisconnected = false
    private var directConnectFailureCount = 0
    private var forceScanOnNextConnect = false
    private var isHandshakeComplete = false

    private enum class ConnectionMode {
        NONE, DIRECT_MAC, SCAN
    }

    private var connectionMode = ConnectionMode.NONE

    // Connection state
    enum class BleState {
        DISCONNECTED, SCANNING, CONNECTING, CONNECTED, READY
    }

    private val _bleState = MutableStateFlow(BleState.DISCONNECTED)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    // Incoming data callback (telemetry from ESP32)
    var onDataReceived: ((String) -> Unit)? = null

    // ======================== PUBLIC API ========================

    /**
     * Start connecting to the ESP32.
     * If a known MAC is saved, tries to connect directly.
     * Otherwise, scans by device name.
     */
    fun connect() {
        if (!isBluetoothAvailable()) {
            Log.w(TAG, "Bluetooth not available or not enabled")
            _bleState.value = BleState.DISCONNECTED
            if (!isManuallyDisconnected) {
                scheduleReconnect()
            }
            return
        }
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Missing Bluetooth permissions")
            _bleState.value = BleState.DISCONNECTED
            return
        }
        if (_bleState.value == BleState.SCANNING || _bleState.value == BleState.CONNECTING || _bleState.value == BleState.CONNECTED || _bleState.value == BleState.READY) {
            Log.d(TAG, "Already scanning, connecting or connected, ignoring connect()")
            return
        }

        isManuallyDisconnected = false
        reconnectJob?.cancel()

        if (forceScanOnNextConnect) {
            forceScanOnNextConnect = false
            Log.d(TAG, "Forced BLE scan requested for next reconnect attempt")
            startScan()
            return
        }

        val savedMac = preferences.bluetoothDeviceMac
        if (!savedMac.isNullOrBlank()) {
            connectionMode = ConnectionMode.DIRECT_MAC
            Log.d(TAG, "Known device MAC: $savedMac — connecting directly")
            connectToMac(savedMac)
        } else {
            Log.d(TAG, "No known device — starting BLE scan for '$DEVICE_NAME'")
            startScan()
        }
    }

    /**
     * Disconnect from the ESP32 and stop all BLE activity.
     */
    fun disconnect() {
        isManuallyDisconnected = true
        scanTimeoutJob?.cancel()
        reconnectJob?.cancel()
        connectionAttemptTimeoutJob?.cancel()
        stopScan()
        disconnectAndCloseGatt(bluetoothGatt)
        bluetoothGatt = null
        txCharacteristic = null
        rxCharacteristic = null
        isHandshakeComplete = false
        _bleState.value = BleState.DISCONNECTED
        Log.d(TAG, "BLE manually disconnected")
    }

    /**
     * Send a raw JSON string to the ESP32 via the TX characteristic.
     * @return true if write was initiated successfully
     */
    fun sendJson(json: String): Boolean {
        val gatt = bluetoothGatt
        val txChar = txCharacteristic
        if (gatt == null || txChar == null) {
            Log.w(TAG, "Cannot send: BLE not connected (gatt=$gatt, txChar=$txChar)")
            return false
        }
        if (_bleState.value != BleState.READY) {
            Log.w(TAG, "Cannot send: BLE not READY (state=${_bleState.value})")
            return false
        }

        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MTU) {
            Log.w(TAG, "JSON payload too large: ${bytes.size} bytes > $MAX_MTU. Will be truncated!")
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val result = gatt.writeCharacteristic(
                    txChar,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                Log.d(TAG, "BLE TX (API33+): ${bytes.size} bytes → result=$result | $json")
                result == BluetoothGatt.GATT_SUCCESS
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException writing characteristic (API33+): ${e.message}")
                false
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                txChar.value = bytes
                @Suppress("DEPRECATION")
                txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                val result = gatt.writeCharacteristic(txChar)
                Log.d(TAG, "BLE TX (legacy): ${bytes.size} bytes → result=$result | $json")
                result
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException writing characteristic (legacy): ${e.message}")
                false
            }
        }
    }

    /**
     * Check if BLE is ready to send commands.
     */
    fun isReady(): Boolean = _bleState.value == BleState.READY

    // ======================== SCANNING ========================

    private fun startScan() {
        if (!hasRequiredPermissions()) return

        connectionAttemptTimeoutJob?.cancel()
        stopScan()
        connectionMode = ConnectionMode.SCAN
        _bleState.value = BleState.SCANNING
        Log.d(TAG, "Starting BLE scan...")

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner not available")
            _bleState.value = BleState.DISCONNECTED
            if (!isManuallyDisconnected) {
                scheduleReconnect()
            }
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder()
                .setDeviceName(DEVICE_NAME)
                .build()
        )

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting scan: ${e.message}")
            _bleState.value = BleState.DISCONNECTED
            if (!isManuallyDisconnected) {
                scheduleReconnect()
            }
            return
        }

        // Timeout — stop scan if device not found
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_bleState.value == BleState.SCANNING) {
                Log.w(TAG, "BLE scan timeout — device '$DEVICE_NAME' not found. Will retry in 5s.")
                stopScan()
                _bleState.value = BleState.DISCONNECTED
                if (!isManuallyDisconnected) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun stopScan() {
        scanTimeoutJob?.cancel()
        if (!hasRequiredPermissions()) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping scan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try { device.name } catch (_: SecurityException) { null }
            Log.d(TAG, "BLE scan result: name=$name, address=${device.address}")

            if (name == DEVICE_NAME) {
                Log.d(TAG, "Found '$DEVICE_NAME' at ${device.address}!")
                stopScan()
                directConnectFailureCount = 0
                preferences.bluetoothDeviceMac = device.address
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            _bleState.value = BleState.DISCONNECTED
            if (!isManuallyDisconnected) {
                scheduleReconnect()
            }
        }
    }

    // ======================== GATT CONNECTION ========================

    private fun connectToMac(mac: String) {
        val adapter = bluetoothAdapter ?: return
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid MAC address '$mac': ${e.message}")
            preferences.bluetoothDeviceMac = null
            startScan()
            return
        }
        connectToDevice(device)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        stopScan()
        connectionAttemptTimeoutJob?.cancel()
        _bleState.value = BleState.CONNECTING
        isHandshakeComplete = false
        txCharacteristic = null
        rxCharacteristic = null
        Log.d(TAG, "Connecting GATT to ${device.address} via $connectionMode...")

        disconnectAndCloseGatt(bluetoothGatt)
        bluetoothGatt = null

        val newGatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException connecting GATT: ${e.message}")
            _bleState.value = BleState.DISCONNECTED
            if (!isManuallyDisconnected) {
                scheduleReconnect()
            }
            null
        }

        if (newGatt == null) {
            Log.e(TAG, "connectGatt returned null")
            _bleState.value = BleState.DISCONNECTED
            if (!isManuallyDisconnected) {
                scheduleReconnect()
            }
            return
        }

        bluetoothGatt = newGatt
        scheduleConnectionAttemptTimeout(newGatt)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) {
                Log.d(TAG, "Ignoring onConnectionStateChange from stale GATT")
                disconnectAndCloseGatt(gatt)
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected! Discovering services...")
                    _bleState.value = BleState.CONNECTED
                    try {
                        gatt.requestMtu(MAX_MTU)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException requesting MTU: ${e.message}")
                        try { gatt.discoverServices() } catch (ex: SecurityException) { Log.e(TAG, "discoverServices failed: ${ex.message}") }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status, mode=$connectionMode, handshakeComplete=$isHandshakeComplete)")
                    connectionAttemptTimeoutJob?.cancel()
                    txCharacteristic = null
                    rxCharacteristic = null
                    isHandshakeComplete = false
                    _bleState.value = BleState.DISCONNECTED
                    disconnectAndCloseGatt(gatt)
                    bluetoothGatt = null

                    // Update robot battery in StateManager (unknown after disconnect)
                    StateManager.updateRobotBattery(-1)

                    if (!isManuallyDisconnected) {
                        if (connectionMode == ConnectionMode.DIRECT_MAC && !isHandshakeComplete) {
                            directConnectFailureCount += 1
                            Log.w(TAG, "Direct reconnect failed before READY (${directConnectFailureCount}/$MAX_DIRECT_CONNECT_FAILURES)")
                            if (directConnectFailureCount >= MAX_DIRECT_CONNECT_FAILURES) {
                                preferences.bluetoothDeviceMac = null
                                forceScanOnNextConnect = true
                                Log.w(TAG, "Saved MAC cleared — next BLE retry will scan by device name")
                            }
                        }
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== bluetoothGatt) {
                Log.d(TAG, "Ignoring onMtuChanged from stale GATT")
                return
            }
            Log.d(TAG, "MTU changed to $mtu (status=$status)")
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException discovering services: ${e.message}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) {
                Log.d(TAG, "Ignoring onServicesDiscovered from stale GATT")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status: $status")
                requestReconnectWithOptionalScanFallback(gatt)
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Nordic UART Service not found on device!")
                requestReconnectWithOptionalScanFallback(gatt)
                return
            }

            txCharacteristic = service.getCharacteristic(TX_CHAR_UUID)
            rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)

            if (txCharacteristic == null) {
                Log.e(TAG, "TX characteristic not found!")
                requestReconnectWithOptionalScanFallback(gatt)
                return
            }
            if (rxCharacteristic == null) {
                Log.e(TAG, "RX characteristic not found!")
                requestReconnectWithOptionalScanFallback(gatt)
                return
            }

            rxCharacteristic?.let { rxChar ->
                try {
                    val notificationSet = gatt.setCharacteristicNotification(rxChar, true)
                    if (!notificationSet) {
                        Log.e(TAG, "setCharacteristicNotification returned false")
                        requestReconnectWithOptionalScanFallback(gatt)
                        return
                    }

                    val descriptor = rxChar.getDescriptor(CCCD_UUID)
                    if (descriptor == null) {
                        Log.e(TAG, "CCCD descriptor not found on RX characteristic")
                        requestReconnectWithOptionalScanFallback(gatt)
                        return
                    }

                    val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }

                    if (!queued) {
                        Log.e(TAG, "Failed to queue CCCD write for RX notifications")
                        requestReconnectWithOptionalScanFallback(gatt)
                        return
                    }

                    Log.d(TAG, "RX notifications requested — waiting for descriptor callback before READY")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException enabling notifications: ${e.message}")
                    requestReconnectWithOptionalScanFallback(gatt)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (gatt !== bluetoothGatt) {
                Log.d(TAG, "Ignoring onDescriptorWrite from stale GATT")
                return
            }
            if (descriptor.uuid != CCCD_UUID || descriptor.characteristic.uuid != RX_CHAR_UUID) {
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "RX CCCD write failed with status: $status")
                requestReconnectWithOptionalScanFallback(gatt)
                return
            }

            connectionAttemptTimeoutJob?.cancel()
            isHandshakeComplete = true
            directConnectFailureCount = 0
            _bleState.value = BleState.READY
            Log.d(TAG, "BLE READY — RX notifications enabled and TX is writable. Heartbeat can start.")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== bluetoothGatt) {
                Log.d(TAG, "Ignoring onCharacteristicChanged from stale GATT")
                return
            }
            // API 33+
            handleIncomingData(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (gatt !== bluetoothGatt) {
                Log.d(TAG, "Ignoring onCharacteristicChanged (legacy) from stale GATT")
                return
            }
            // API < 33
            handleIncomingData(characteristic.value ?: return)
        }
    }

    private fun handleIncomingData(bytes: ByteArray) {
        val json = bytes.toString(Charsets.UTF_8)
        Log.d(TAG, "BLE RX ← $json")
        onDataReceived?.invoke(json)
    }

    // ======================== UTILITIES ========================

    private fun scheduleReconnect() {
        if (isManuallyDisconnected) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Scheduling BLE reconnect in ${RECONNECT_DELAY_MS}ms...")
            delay(RECONNECT_DELAY_MS)
            if (!isManuallyDisconnected) {
                connect()
            }
        }
    }

    private fun scheduleConnectionAttemptTimeout(expectedGatt: BluetoothGatt) {
        connectionAttemptTimeoutJob?.cancel()
        connectionAttemptTimeoutJob = scope.launch {
            delay(CONNECTION_ATTEMPT_TIMEOUT_MS)

            if (isManuallyDisconnected || bluetoothGatt !== expectedGatt) {
                return@launch
            }

            val state = _bleState.value
            if (state == BleState.CONNECTING || state == BleState.CONNECTED) {
                Log.w(TAG, "BLE connection attempt timed out after ${CONNECTION_ATTEMPT_TIMEOUT_MS}ms — forcing reconnect")

                if (connectionMode == ConnectionMode.DIRECT_MAC) {
                    directConnectFailureCount += 1
                    Log.w(TAG, "Direct reconnect timeout before READY (${directConnectFailureCount}/$MAX_DIRECT_CONNECT_FAILURES)")
                    if (directConnectFailureCount >= MAX_DIRECT_CONNECT_FAILURES) {
                        preferences.bluetoothDeviceMac = null
                        forceScanOnNextConnect = true
                        Log.w(TAG, "Saved MAC cleared after timeout — next BLE retry will scan by device name")
                    }
                }

                txCharacteristic = null
                rxCharacteristic = null
                isHandshakeComplete = false
                disconnectAndCloseGatt(expectedGatt)
                bluetoothGatt = null
                _bleState.value = BleState.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    private fun requestReconnectWithOptionalScanFallback(gatt: BluetoothGatt) {
        connectionAttemptTimeoutJob?.cancel()
        if (connectionMode == ConnectionMode.DIRECT_MAC) {
            preferences.bluetoothDeviceMac = null
            forceScanOnNextConnect = true
            Log.w(TAG, "BLE handshake failed on direct MAC path — next retry will scan")
        }

        try {
            gatt.disconnect()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException disconnecting failed GATT: ${e.message}")
            disconnectAndCloseGatt(gatt)
            bluetoothGatt = null
            _bleState.value = BleState.DISCONNECTED
            if (!isManuallyDisconnected) {
                scheduleReconnect()
            }
        }
    }

    private fun disconnectAndCloseGatt(gatt: BluetoothGatt?) {
        if (gatt == null) return

        try {
            gatt.disconnect()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException disconnecting GATT: ${e.message}")
        } catch (_: IllegalStateException) {
        }

        try {
            gatt.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException closing GATT: ${e.message}")
        } catch (_: IllegalStateException) {
        }
    }

    private fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}
