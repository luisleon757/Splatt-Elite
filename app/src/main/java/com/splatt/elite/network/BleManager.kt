package com.splatt.elite.network

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val _statusFlow = MutableStateFlow(SplattStatus())
    val statusFlow: StateFlow<SplattStatus> = _statusFlow.asStateFlow()

    private val _isScanningState = MutableStateFlow(false)
    val isScanningState: StateFlow<Boolean> = _isScanningState.asStateFlow()

    // UUIDs
    private val SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val STATUS_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
    private val COMMAND_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
    private val CONFIG_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef3")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var commandChar: BluetoothGattCharacteristic? = null
    private var configChar: BluetoothGattCharacteristic? = null

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: ""
            Log.d("BleManager", "Scan: name='$name' address=${device.address}")
            stopScan()
            connectToDevice(device)
        }
    }

    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        if (isScanning) return
        if (_connectionState.value) return

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        
        isScanning = true
        _isScanningState.value = true
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)
        
        // Stop scan after 10 seconds
        handler.postDelayed({
            stopScan()
            if (!_connectionState.value) {
                handler.postDelayed({ startScan() }, 1500)
            }
        }, 10000)
    }

    fun stopScan() {
        if (!isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.stopScan(scanCallback)
        isScanning = false
        _isScanningState.value = false
    }

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = false
        BatteryStatus.update(-1)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("BleManager", "Connection change: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BleManager", "Connected to GATT server.")
                _connectionState.value = true
                // Request MTU to prevent JSON truncation (with delay for Android BLE bug)
                handler.postDelayed({
                    gatt.requestMtu(512)
                }, 500)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BleManager", "Disconnected from GATT server.")
                _connectionState.value = false
                BatteryStatus.update(-1)
                bluetoothGatt?.close()
                bluetoothGatt = null
                // Automatically attempt to reconnect by starting scan again
                handler.postDelayed({ startScan() }, 1000)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            Log.d("BleManager", "MTU changed to $mtu")
            // Discover services AFTER MTU is negotiated (with delay for Android BLE bug)
            handler.postDelayed({
                gatt.discoverServices()
            }, 500)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val statusChar = service.getCharacteristic(STATUS_CHAR_UUID)
                    commandChar = service.getCharacteristic(COMMAND_CHAR_UUID)
                    configChar = service.getCharacteristic(CONFIG_CHAR_UUID)

                    if (statusChar != null) {
                        gatt.setCharacteristicNotification(statusChar, true)
                        val descriptor = statusChar.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == STATUS_CHAR_UUID) {
                val json = String(characteristic.value)
                try {
                    val status = parseStatusJson(json)
                    _statusFlow.value = status
                } catch (e: Exception) {
                    Log.e("BleManager", "Error parsing JSON: ${e.message}")
                }
            }
        }
    }

    fun sendCommand(cmd: String) {
        val char = commandChar ?: return
        char.value = cmd.toByteArray()
        bluetoothGatt?.writeCharacteristic(char)
    }

    fun sendConfig(config: String) {
        val char = configChar ?: return
        char.value = config.toByteArray()
        bluetoothGatt?.writeCharacteristic(char)
    }

    private fun parseStatusJson(payload: String): SplattStatus {
        val text = payload.trim()

        // Formato BLE compacto:
        // estado,x,y,valida,tiempo,host,bateria,shot_x,shot_y
        if (!text.startsWith("{")) {
            val fields = text.split(",")

            if (fields.size >= 4) {
                val state = fields[0].toIntOrNull() ?: 0
                val x = fields[1].toFloatOrNull() ?: 0.0f
                val y = fields[2].toFloatOrNull() ?: 0.0f
                val valid = fields[3].toIntOrNull() ?: 0
                val time = fields.getOrNull(4)?.toLongOrNull() ?: 0L
                val host = fields.getOrNull(5)?.trim().orEmpty()
                val battery = fields.getOrNull(6)?.toIntOrNull() ?: -1
                val shotX = fields.getOrNull(7)?.toFloatOrNull() ?: x
                val shotY = fields.getOrNull(8)?.toFloatOrNull() ?: y
                BatteryStatus.update(battery)

                return SplattStatus(
                    state = state,
                    shotX = shotX,
                    shotY = shotY,
                    time = time,
                    x = x,
                    y = y,
                    v = valid,
                    s = 0,
                    c = 1,
                    f = 0,
                    host = host
                )
            }
        }

        val clean = text
            .replace("{", "")
            .replace("}", "")
            .replace("\"", "")

        val map = mutableMapOf<String, String>()

        for (pair in clean.split(",")) {
            val parts = pair.split(":")

            if (parts.size >= 2) {
                val key = parts[0].trim()
                val value = parts.subList(1, parts.size)
                    .joinToString(":")
                    .trim()

                map[key] = value
            }
        }

        BatteryStatus.update(map["battery"]?.toIntOrNull() ?: -1)

        return SplattStatus(
            state = map["state"]?.toIntOrNull() ?: 0,
            shotX = map["shot_x"]?.toFloatOrNull() ?: 0.0f,
            shotY = map["shot_y"]?.toFloatOrNull() ?: 0.0f,
            time = map["time"]?.toLongOrNull() ?: 0,
            x = map["x"]?.toFloatOrNull() ?: 0.0f,
            y = map["y"]?.toFloatOrNull() ?: 0.0f,
            v = map["v"]?.toIntOrNull() ?: 0,
            s = map["s"]?.toIntOrNull() ?: 0,
            c = map["c"]?.toIntOrNull() ?: 0,
            f = map["f"]?.toIntOrNull() ?: 0,
            host = map["host"] ?: ""
        )
    }
}
