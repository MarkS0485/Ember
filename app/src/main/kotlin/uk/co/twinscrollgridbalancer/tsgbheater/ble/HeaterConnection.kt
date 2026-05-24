package uk.co.twinscrollgridbalancer.tsgbheater.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.UUID

// One GATT connection to one heater. Owns the BluetoothGatt object, runs
// the discover-services / enable-notifications dance, and surfaces
// ConnectionState plus a stream of raw frames in both directions.
// Single-instance per phone — created and owned by BleManager.
@SuppressLint("MissingPermission")
class HeaterConnection(private val ctx: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<RawFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<RawFrame> = _frames.asSharedFlow()

    private val _telemetry = MutableStateFlow<HeaterTelemetry?>(null)
    val telemetry: StateFlow<HeaterTelemetry?> = _telemetry.asStateFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null

    // The GATT API forces one write at a time. This deferred resolves on
    // the matching onCharacteristicWrite callback so suspend write() can
    // await it.
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null

    fun connect(mac: String) {
        scope.launch {
            disconnect() // tear down any previous session before opening a new one
            val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val device: BluetoothDevice? = mgr?.adapter?.getRemoteDevice(mac)
            if (device == null) {
                Log.w(TAG, "No adapter or device for $mac")
                _state.value = ConnectionState.Failed
                return@launch
            }
            _state.value = ConnectionState.Connecting
            gatt = device.connectGatt(ctx, /*autoConnect*/ false, gattCallback)
        }
    }

    fun disconnect() {
        scope.launch {
            val g = gatt
            gatt = null
            writeChar = null
            notifyChar = null
            pendingWrite?.complete(false)
            pendingWrite = null
            if (g != null) {
                runCatching { g.disconnect() }
                runCatching { g.close() }
            }
            _state.value = ConnectionState.Idle
        }
    }

    // Suspend until the GATT layer ACKs the write (or timeout). Returns
    // true on success. Concurrent callers serialise — each one waits for
    // the previous to complete before its frame goes on the wire.
    suspend fun write(bytes: ByteArray, timeoutMs: Long = 2_000L): Boolean {
        val g  = gatt ?: return false
        val ch = writeChar ?: return false
        val pending = CompletableDeferred<Boolean>()
        synchronized(this) {
            pendingWrite?.complete(false) // cancel any orphan
            pendingWrite = pending
        }
        ch.value = bytes
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") g.writeCharacteristic(ch)
        }
        if (!started) {
            pending.complete(false)
            return false
        }
        _frames.tryEmit(RawFrame(tx = true, bytes = bytes, timestampMs = System.currentTimeMillis()))
        return withTimeoutOrNull(timeoutMs) { pending.await() } ?: false
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected — discovering services")
                    _state.value = ConnectionState.DiscoveringServices
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected (status=$status)")
                    runCatching { g.close() }
                    gatt = null
                    writeChar = null
                    notifyChar = null
                    _state.value = if (status == 0) ConnectionState.Idle else ConnectionState.Failed
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.Failed
                return
            }
            val svc = g.getService(BleConstants.HEATER_SERVICE)
                ?: g.services.firstOrNull { it.characteristics.any { c -> hasWrite(c) && hasNotify(c) } }
            if (svc == null) {
                Log.w(TAG, "No matching service on device — known services: " +
                        g.services.joinToString { it.uuid.toString() })
                _state.value = ConnectionState.Failed
                return
            }
            // Pick by property bits — the vendor app does the same since char
            // UUIDs aren't hardcoded across firmware revisions.
            writeChar  = svc.characteristics.firstOrNull { hasWrite(it) }
            notifyChar = svc.characteristics.firstOrNull { hasNotify(it) }

            val nc = notifyChar
            if (nc == null) {
                Log.w(TAG, "No notify characteristic — telemetry stream will be silent")
                _state.value = ConnectionState.Ready
                return
            }
            g.setCharacteristicNotification(nc, true)
            val cccd = nc.getDescriptor(BleConstants.CCCD)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            } else {
                _state.value = ConnectionState.Ready
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == BleConstants.CCCD) {
                _state.value = ConnectionState.Ready
                // Vendor app does this ~400 ms after notify enable; we mirror
                // the timing so weaker BLE radios don't drop the request.
                scope.launch {
                    delay(400)
                    runCatching { g.requestMtu(247) }
                    delay(200)
                    // Kick the controller into pushing live telemetry. The
                    // app can still issue one-shot reads alongside this.
                    write(FrameCodec.buildStartTelemetryStream())
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingWrite = null
        }

        // Pre-T callback
        @Deprecated("Targets API < 33", level = DeprecationLevel.HIDDEN)
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION") val v = c.value ?: return
            handleNotification(c.uuid, v)
        }

        // API 33+ callback
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(c.uuid, value)
        }
    }

    private fun handleNotification(uuid: UUID, bytes: ByteArray) {
        _frames.tryEmit(RawFrame(tx = false, bytes = bytes, timestampMs = System.currentTimeMillis()))
        val t = FrameCodec.parseTelemetry(bytes) ?: return
        _telemetry.value = t
    }

    private fun hasWrite(c: BluetoothGattCharacteristic): Boolean =
        (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

    private fun hasNotify(c: BluetoothGattCharacteristic): Boolean =
        (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0

    private companion object { const val TAG = "HeaterConnection" }
}
