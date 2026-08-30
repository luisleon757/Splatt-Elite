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
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SplattTracePoint(
    val dtMs: Int,
    val x: Float,
    val y: Float,
    val valid: Boolean
)

data class SplattCompletedTrace(
    val shotId: Int,
    val points: List<SplattTracePoint>
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val _statusFlow = MutableStateFlow(SplattStatus())
    val statusFlow: StateFlow<SplattStatus> = _statusFlow.asStateFlow()

    private val _completedTraceFlow =
        MutableStateFlow<SplattCompletedTrace?>(null)

    val completedTraceFlow:
        StateFlow<SplattCompletedTrace?> =
        _completedTraceFlow.asStateFlow()

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

    // La Raspberry envia estado BLE cada 100 ms.
    // Si pasan varios segundos sin recibir nada, la conexion GATT
    // se considera obsoleta aunque Android siga diciendo CONNECTED.
    private val connectionTimeoutMs = 3000L
    private val watchdogIntervalMs = 1000L

    @Volatile
    private var lastStatusReceivedAt = 0L

    @Volatile
    private var gattReady = false

    private val connectionWatchdog = object : Runnable {
        override fun run() {
            val currentGatt = bluetoothGatt

            if (_connectionState.value && currentGatt != null) {
                val ageMs =
                    SystemClock.elapsedRealtime() - lastStatusReceivedAt

                if (
                    lastStatusReceivedAt > 0L
                    && ageMs > connectionTimeoutMs
                ) {
                    Log.w(
                        "BleManager",
                        "BLE watchdog: sin datos durante ${ageMs} ms"
                    )
                    handleConnectionLost(
                        currentGatt,
                        "timeout de datos BLE (${ageMs} ms)"
                    )
                }
            }

            handler.postDelayed(this, watchdogIntervalMs)
        }
    }

    init {
        handler.postDelayed(
            connectionWatchdog,
            watchdogIntervalMs
        )
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: ""
            Log.d("BleManager", "Scan: name='$name' address=${device.address}")
            stopScan()

            // Dar tiempo al stack Bluetooth de Android a cerrar
            // el escaneo antes de iniciar la conexion GATT.
            handler.postDelayed({
                if (!_connectionState.value && bluetoothGatt == null) {
                    connectToDevice(device)
                }
            }, 350L)
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

        // Splatt es un dispositivo dedicado.
        // Mantener el escaneo activo hasta encontrarlo.
        // No introducir pausas periodicas entre busquedas.
        Log.d(
            "BleManager",
            "Escaneo BLE continuo iniciado"
        )
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
        val gatt = bluetoothGatt

        if (gatt != null) {
            handleConnectionLost(
                gatt,
                "desconexion solicitada",
                reconnect = false
            )
        } else {
            _connectionState.value = false
            BatteryStatus.update(-1)
        }
    }



    private var traceRxShotId = -1
    private var traceRxExpectedPoints = 0
    private var traceRxExpectedBlocks = 0
    private var traceRxNextBlock = 0
    private var traceRxError = false

    private val traceRxPoints =
        mutableListOf<SplattTracePoint>()

    private fun resetTraceRx() {
        traceRxShotId = -1
        traceRxExpectedPoints = 0
        traceRxExpectedBlocks = 0
        traceRxNextBlock = 0
        traceRxError = false
        traceRxPoints.clear()
        _completedTraceFlow.value = null
    }

    private fun u16(buffer: ByteBuffer): Int {
        return buffer.short.toInt() and 0xFFFF
    }

    private fun processTracePacket(data: ByteArray) {
        if (data.isEmpty()) {
            return
        }

        val type = data[0].toInt() and 0xFF

        try {
            when (type) {
                1 -> {
                    // START:
                    // type, version, disparo_id, puntos, bloques
                    if (data.size != 8) {
                        Log.e(
                            "BleManager",
                            "[TRACE-RX] START tama?o invalido ${data.size}"
                        )
                        resetTraceRx()
                        return
                    }

                    val buffer = ByteBuffer
                        .wrap(data)
                        .order(ByteOrder.LITTLE_ENDIAN)

                    buffer.get()

                    val version =
                        buffer.get().toInt() and 0xFF

                    val shotId = u16(buffer)
                    val totalPoints = u16(buffer)
                    val totalBlocks = u16(buffer)

                    resetTraceRx()

                    traceRxShotId = shotId
                    traceRxExpectedPoints = totalPoints
                    traceRxExpectedBlocks = totalBlocks

                    if (version != 1) {
                        traceRxError = true
                    }

                    Log.d(
                        "BleManager",
                        "[TRACE-RX] START " +
                            "disparo_id=$shotId " +
                            "version=$version " +
                            "puntos=$totalPoints " +
                            "bloques=$totalBlocks"
                    )
                }

                2 -> {
                    // DATA:
                    // type, disparo_id, bloque, cantidad, puntos...
                    if (data.size < 6) {
                        Log.e(
                            "BleManager",
                            "[TRACE-RX] DATA demasiado corto"
                        )
                        traceRxError = true
                        return
                    }

                    val buffer = ByteBuffer
                        .wrap(data)
                        .order(ByteOrder.LITTLE_ENDIAN)

                    buffer.get()

                    val shotId = u16(buffer)
                    val block = u16(buffer)
                    val count =
                        buffer.get().toInt() and 0xFF

                    val expectedSize =
                        6 + count * 7

                    if (data.size != expectedSize) {
                        Log.e(
                            "BleManager",
                            "[TRACE-RX] DATA tama?o invalido " +
                                "bloque=$block " +
                                "size=${data.size} " +
                                "esperado=$expectedSize"
                        )
                        traceRxError = true
                        return
                    }

                    if (shotId != traceRxShotId) {
                        Log.e(
                            "BleManager",
                            "[TRACE-RX] disparo_id inesperado " +
                                "$shotId != $traceRxShotId"
                        )
                        traceRxError = true
                        return
                    }

                    if (block != traceRxNextBlock) {
                        Log.e(
                            "BleManager",
                            "[TRACE-RX] bloque inesperado " +
                                "$block != $traceRxNextBlock"
                        )
                        traceRxError = true
                    }

                    repeat(count) {
                        val dtMs =
                            buffer.short.toInt()

                        val x100 =
                            buffer.short.toInt()

                        val y100 =
                            buffer.short.toInt()

                        val valid =
                            (buffer.get().toInt() and 0xFF) != 0

                        traceRxPoints.add(
                            SplattTracePoint(
                                dtMs = dtMs,
                                x = x100 / 100.0f,
                                y = y100 / 100.0f,
                                valid = valid
                            )
                        )
                    }

                    traceRxNextBlock = block + 1
                }

                3 -> {
                    // END:
                    // type, disparo_id, puntos, bloques
                    if (data.size != 7) {
                        Log.e(
                            "BleManager",
                            "[TRACE-RX] END tama?o invalido ${data.size}"
                        )
                        traceRxError = true
                        return
                    }

                    val buffer = ByteBuffer
                        .wrap(data)
                        .order(ByteOrder.LITTLE_ENDIAN)

                    buffer.get()

                    val shotId = u16(buffer)
                    val totalPoints = u16(buffer)
                    val totalBlocks = u16(buffer)

                    val validPoints =
                        traceRxPoints.count { it.valid }

                    val firstDt =
                        traceRxPoints.firstOrNull()?.dtMs

                    val lastDt =
                        traceRxPoints.lastOrNull()?.dtMs

                    val ok = (
                        !traceRxError
                        && shotId == traceRxShotId
                        && totalPoints == traceRxExpectedPoints
                        && totalBlocks == traceRxExpectedBlocks
                        && traceRxPoints.size ==
                            traceRxExpectedPoints
                        && traceRxNextBlock ==
                            traceRxExpectedBlocks
                    )

                    Log.d(
                        "BleManager",
                        "[TRACE-RX] END " +
                            "disparo_id=$shotId " +
                            "puntos=${traceRxPoints.size}/" +
                            "$traceRxExpectedPoints " +
                            "bloques=$traceRxNextBlock/" +
                            "$traceRxExpectedBlocks " +
                            "validos=$validPoints " +
                            "dt=$firstDt..$lastDt " +
                            "ok=${if (ok) 1 else 0}"
                    )
                    if (ok) {
                        _completedTraceFlow.value =
                            SplattCompletedTrace(
                                shotId = shotId,
                                points = traceRxPoints.toList()
                            )

                        Log.d(
                            "BleManager",
                            "[TRACE-RX] traza definitiva publicada " +
                                "disparo_id=$shotId"
                        )
                    }
                }

                else -> {
                    Log.w(
                        "BleManager",
                        "[TRACE-RX] tipo desconocido=$type"
                    )
                }
            }

        } catch (e: Exception) {
            traceRxError = true

            Log.e(
                "BleManager",
                "[TRACE-RX] error: ${e.message}",
                e
            )
        }
    }

    private fun handleConnectionLost(
        gatt: BluetoothGatt,
        reason: String,
        reconnect: Boolean = true
    ) {
        handler.post {
            // Un callback antiguo nunca debe cerrar una conexion nueva.
            if (bluetoothGatt !== gatt) {
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                return@post
            }

            Log.w("BleManager", "Conexion BLE perdida: $reason")

            _connectionState.value = false
            gattReady = false
            lastStatusReceivedAt = 0L

            commandChar = null
            configChar = null
            resetTraceRx()
            BatteryStatus.update(-1)

            bluetoothGatt = null

            try {
                gatt.disconnect()
            } catch (_: Exception) {
            }

            try {
                gatt.close()
            } catch (_: Exception) {
            }

            if (reconnect) {
                handler.postDelayed(
                    { startScan() },
                    1000
                )
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            Log.d(
                "BleManager",
                "Connection change: status=$status newState=$newState"
            )

            if (
                newState == BluetoothProfile.STATE_CONNECTED
                && status == BluetoothGatt.GATT_SUCCESS
            ) {
                if (bluetoothGatt !== gatt) {
                    gatt.disconnect()
                    gatt.close()
                    return
                }

                Log.d("BleManager", "GATT conectado; preparando servicio.")

                // La tabla GATT de Splatt ha cambiado durante el desarrollo.
                // Algunos Android conservan handles antiguos y devuelven
                // status=1 al escribir el CCCD. Forzar una recarga de cache
                // antes de descubrir los servicios.
                val cacheRefreshed = try {
                    val refreshMethod =
                        gatt.javaClass.getMethod("refresh")

                    val result =
                        refreshMethod.invoke(gatt)

                    result as? Boolean ?: true

                } catch (e: Exception) {
                    Log.w(
                        "BleManager",
                        "No se pudo refrescar cache GATT: ${e.message}"
                    )
                    false
                }

                Log.d(
                    "BleManager",
                    "GATT cache refresh=$cacheRefreshed"
                )

                // Todavia no mostramos Conectado:
                // primero deben quedar activas las notificaciones Splatt.
                _connectionState.value = false
                gattReady = false
                lastStatusReceivedAt = SystemClock.elapsedRealtime()

                handler.postDelayed({
                    if (bluetoothGatt === gatt) {
                        gatt.requestMtu(512)
                    }
                }, 500)

            } else if (
                newState == BluetoothProfile.STATE_DISCONNECTED
                || status != BluetoothGatt.GATT_SUCCESS
            ) {
                handleConnectionLost(
                    gatt,
                    "callback status=$status state=$newState"
                )
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

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (bluetoothGatt !== gatt) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleConnectionLost(
                    gatt,
                    "fallo descubriendo servicios: $status"
                )
                return
            }

            val service =
                gatt.getService(SERVICE_UUID)

            val statusChar =
                service?.getCharacteristic(STATUS_CHAR_UUID)

            commandChar =
                service?.getCharacteristic(COMMAND_CHAR_UUID)

            configChar =
                service?.getCharacteristic(CONFIG_CHAR_UUID)

            if (
                service == null
                || statusChar == null
                || commandChar == null
                || configChar == null
            ) {
                handleConnectionLost(
                    gatt,
                    "servicio Splatt original incompleto"
                )
                return
            }

            if (
                !gatt.setCharacteristicNotification(
                    statusChar,
                    true
                )
            ) {
                handleConnectionLost(
                    gatt,
                    "no se pudo activar STATUS local"
                )
                return
            }

            val descriptor =
                statusChar.getDescriptor(CCCD_UUID)

            if (descriptor == null) {
                handleConnectionLost(
                    gatt,
                    "CCCD STATUS no disponible"
                )
                return
            }

            descriptor.value =
                BluetoothGattDescriptor
                    .ENABLE_NOTIFICATION_VALUE

            if (!gatt.writeDescriptor(descriptor)) {
                handleConnectionLost(
                    gatt,
                    "no se pudo escribir CCCD STATUS"
                )
            }
        }
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (
                bluetoothGatt !== gatt
                || descriptor.uuid != CCCD_UUID
            ) {
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleConnectionLost(
                    gatt,
                    "fallo activando STATUS: $status"
                )
                return
            }

            gattReady = true

            lastStatusReceivedAt =
                SystemClock.elapsedRealtime()

            _connectionState.value = true

            Log.d(
                "BleManager",
                "Splatt BLE listo: STATUS; watchdog activo."
            )
        }
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (
                bluetoothGatt !== gatt
                || characteristic.uuid != STATUS_CHAR_UUID
            ) {
                return
            }

            val payload =
                characteristic.value ?: return

            if (payload.isEmpty()) {
                return
            }

            val firstByte =
                payload[0].toInt() and 0xFF

            // Los paquetes TRACE binarios empiezan por 1, 2 o 3.
            // El STATUS ASCII empieza por '0'...'3'
            // (bytes 48...51), por lo que no hay ambiguedad.
            if (firstByte in 1..3) {
                processTracePacket(payload)
                return
            }

            lastStatusReceivedAt =
                SystemClock.elapsedRealtime()

            if (gattReady) {
                _connectionState.value = true
            }

            val text = String(payload)

            try {
                val statusValue =
                    parseStatusJson(text)

                _statusFlow.value =
                    statusValue

            } catch (e: Exception) {
                Log.e(
                    "BleManager",
                    "Error parsing status: ${e.message}"
                )
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
        // estado,x,y,valida,tiempo,host,bateria,shot_x,shot_y,
        // frame_ms,shot_ms
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
                val frameTimeMs =
                    fields.getOrNull(9)?.toLongOrNull() ?: 0L
                val shotTimeMs =
                    fields.getOrNull(10)?.toLongOrNull() ?: 0L
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
                    host = host,
                    frameTimeMs = frameTimeMs,
                    shotTimeMs = shotTimeMs
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
            host = map["host"] ?: "",
            frameTimeMs = map["frame_ms"]?.toLongOrNull() ?: 0L,
            shotTimeMs = map["shot_ms"]?.toLongOrNull() ?: 0L
        )
    }
}
