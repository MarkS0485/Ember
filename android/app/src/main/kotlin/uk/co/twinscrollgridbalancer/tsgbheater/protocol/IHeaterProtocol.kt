package uk.co.twinscrollgridbalancer.tsgbheater.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// The thin contract every heater driver implements. Designed so the
// rest of the app (ViewModels, Schedule controller, Auto-Start/Stop,
// Groups, remote API, widgets…) can talk to ANY heater through this
// interface without knowing which protocol is on the wire.
//
// Lifecycle: drivers own their own connection state. The same instance
// can be told to connect → use → disconnect → reconnect. Closing means
// "tear down completely and let me go" — for that, drop the reference
// and let GC handle it. There's no `close()` to keep the contract small.
//
// Errors: every command returns a Result<Unit>. Drivers MAY return
// failure(UnsupportedOperationException) when called for something
// outside `capabilities` — but the right thing is for the UI to gate
// these calls in advance so we never get that far.
//
// Thread safety: every member is safe to invoke from any thread.
// Drivers serialise internally (typically via a dispatched coroutine
// scope or single-threaded executor).
interface IHeaterProtocol {

    val kind:         ProtocolKind
    val capabilities: HeaterCapabilities

    // --- Connection lifecycle ---------------------------------------

    /** Connect to the heater at [mac]. Idempotent if already connected to the same MAC. */
    suspend fun connect(mac: String): Result<Unit>

    /** Disconnect cleanly. Idempotent if already disconnected. */
    suspend fun disconnect(): Result<Unit>

    /** Live connection state. `true` = ready to send commands. */
    val isConnected: StateFlow<Boolean>

    // --- Live data --------------------------------------------------

    /** Latest telemetry snapshot. Always emits at least [CommonTelemetry.Empty] on subscribe. */
    val telemetry: StateFlow<CommonTelemetry>

    /**
     * Raw notification frames as they arrive — for the Debug Box page.
     * Drivers without raw frames (e.g. cloud-relayed protocols) can return
     * an empty flow; UI gates on [HeaterCapabilities.hasRawFrameStream].
     */
    val rawFrames: Flow<ByteArray>

    // --- Commands ---------------------------------------------------
    // All return Result so callers can surface failures uniformly.

    suspend fun start():                      Result<Unit>
    suspend fun stop():                       Result<Unit>
    suspend fun vent():                       Result<Unit>
    suspend fun setTargetC(c: Int):           Result<Unit>
    suspend fun setGear(gear: Int):           Result<Unit>
    suspend fun setAltitudeM(metres: Int):    Result<Unit>
    suspend fun pulsePump(seconds: Int):      Result<Unit>

    /**
     * Escape hatch for protocol-specific commands the common interface
     * doesn't model (test mode, raw hex sends, vendor diagnostics). The
     * value of [opcode] is driver-defined and the UI surfaces only those
     * the driver advertises via [capabilities].
     */
    suspend fun sendRaw(opcode: String, payload: ByteArray): Result<Unit>
}
