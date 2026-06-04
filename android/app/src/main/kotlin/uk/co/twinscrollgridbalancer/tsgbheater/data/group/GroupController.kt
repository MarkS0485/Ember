package uk.co.twinscrollgridbalancer.tsgbheater.data.group

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.co.twinscrollgridbalancer.tsgbheater.ble.BleManager
import uk.co.twinscrollgridbalancer.tsgbheater.ble.ConnectionState
import uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec
import uk.co.twinscrollgridbalancer.tsgbheater.ble.OneShotSender
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDeviceStore

// Fans a single command out to every member of a group, one at a time.
// Members that are NOT the currently-connected heater are reached via a
// fresh transient GATT connection (OneShotSender); the currently-connected
// one uses the live HeaterConnection so we don't have to disconnect it.
//
// One broadcast at a time — concurrent calls serialise on the Mutex. UI
// observes [progress] to render a "1 / 3" overlay while it runs.
class GroupController(
    ctx: Context,
    private val ble: BleManager,
    private val boundDevices: BoundDeviceStore,
    // Pro gate. The Groups UI is already locked behind Pro, but guarding the
    // single broadcast funnel too means a lapsed trial can't fan out commands.
    private val isProActive: StateFlow<Boolean>,
) {

    private val sender  = OneShotSender(ctx.applicationContext)
    private val mutex   = Mutex()

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    sealed class Progress {
        data object Idle : Progress()
        data class Running(
            val groupName: String,
            val action:    String,
            val current:   Int,
            val total:     Int,
            val mac:       String,
        ) : Progress()
        data class Done(
            val groupName: String,
            val action:    String,
            val results:   List<MemberResult>,
        ) : Progress()
    }

    data class MemberResult(val mac: String, val ok: Boolean)

    // --- Public broadcast API ------------------------------------------

    suspend fun start(group: HeaterGroup) =
        broadcast(group, "Start") { FrameCodec.buildStartHeater() }

    suspend fun stop(group: HeaterGroup) =
        broadcast(group, "Stop")  { FrameCodec.buildStopHeater()  }

    suspend fun ventilate(group: HeaterGroup) =
        broadcast(group, "Ventilate") { FrameCodec.buildBlowOn() }

    suspend fun setTarget(group: HeaterGroup, celsius: Int) =
        broadcast(group, "Target ${celsius}°C") {
            FrameCodec.buildSetTargetTemp(celsius, FrameCodec.TempUnit.Celsius)
        }

    suspend fun setGear(group: HeaterGroup, gear: Int) =
        broadcast(group, "Gear $gear") { FrameCodec.buildSetGear(gear) }

    // --- Core -----------------------------------------------------------

    private suspend fun broadcast(
        group: HeaterGroup,
        action: String,
        commandBuilder: () -> ByteArray,
    ): List<MemberResult> = mutex.withLock {
        if (!isProActive.value) {
            Log.w(TAG, "Group broadcast ignored — Pro required")
            _progress.value = Progress.Done(group.name, action, emptyList())
            return@withLock emptyList()
        }
        val members = group.memberMacs
        if (members.isEmpty()) {
            _progress.value = Progress.Done(group.name, action, emptyList())
            return@withLock emptyList()
        }

        val currentMac = boundDevices.currentMac.first()
        val liveReady  = ble.connectionState.value == ConnectionState.Ready

        val results = mutableListOf<MemberResult>()
        members.forEachIndexed { idx, mac ->
            _progress.value = Progress.Running(
                groupName = group.name,
                action    = action,
                current   = idx + 1,
                total     = members.size,
                mac       = mac,
            )

            val ok = if (mac == currentMac && liveReady) {
                // Reuse the live connection — instant, no reconnect cost.
                ble.sendRaw(commandBuilder())
            } else {
                // Open a fresh transient GATT connection for this member.
                sender.send(mac, commandBuilder())
            }
            results += MemberResult(mac, ok)
            Log.i(TAG, "[$mac] $action: ${if (ok) "OK" else "FAIL"}")

            // Small breather between members so the BLE radio settles.
            if (idx < members.lastIndex) delay(300)
        }

        _progress.value = Progress.Done(group.name, action, results)
        results
    }

    private companion object { const val TAG = "GroupController" }
}
