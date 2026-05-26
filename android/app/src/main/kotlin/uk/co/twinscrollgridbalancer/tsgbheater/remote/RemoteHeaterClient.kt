package uk.co.twinscrollgridbalancer.tsgbheater.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Strongly-typed wrapper over the Windows API. One instance per paired
// server. Every call is suspending and runs on Dispatchers.IO so the UI
// thread doesn't see network latency.
class RemoteHeaterClient(val server: PairedServer) {

    private val http = RemoteApi.client(server)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val emptyJson = "{}".toRequestBody("application/json".toMediaType())

    // --- Probing -------------------------------------------------

    // Hits /api/ping (unauthenticated). Returns the cert thumbprint the
    // server reported, so the caller can sanity-check it matches the QR.
    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = http.newCall(Request.Builder().url("${server.baseUrl}/api/ping").build()).execute()
            resp.use {
                if (!it.isSuccessful) error("HTTP ${it.code}")
                val body = it.body?.string().orEmpty()
                val d = json.decodeFromString<PingResp>(body)
                d.cert
            }
        }
    }

    // Authenticated round-trip; tests both HMAC signing and cert pin.
    suspend fun verifyAuth(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = http.newCall(Request.Builder().url("${server.baseUrl}/api/v1/status").build()).execute()
            resp.use {
                if (!it.isSuccessful) error("HTTP ${it.code}")
            }
        }
    }

    // --- Status --------------------------------------------------

    suspend fun status(): Result<StatusResp> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = http.newCall(Request.Builder().url("${server.baseUrl}/api/v1/status").build()).execute()
            resp.use {
                if (!it.isSuccessful) error("HTTP ${it.code}")
                json.decodeFromString<StatusResp>(it.body?.string().orEmpty())
            }
        }
    }

    // --- Commands ------------------------------------------------

    suspend fun connect(): Result<Unit>            = post("/api/v1/connect")
    suspend fun disconnect(): Result<Unit>         = post("/api/v1/disconnect")

    suspend fun start(): Result<Unit>              = post("/api/v1/heater/start")
    suspend fun stop(): Result<Unit>               = post("/api/v1/heater/stop")
    suspend fun vent(): Result<Unit>               = post("/api/v1/heater/vent")

    suspend fun setTarget(celsius: Int): Result<Unit> =
        post("/api/v1/heater/target", """{"c":$celsius}""")
    suspend fun setGear(gear: Int): Result<Unit> =
        post("/api/v1/heater/gear", """{"g":$gear}""")
    suspend fun setRunMode(mode: String): Result<Unit> =
        post("/api/v1/heater/runmode", """{"mode":"$mode"}""")

    private suspend fun post(path: String, body: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rb = body?.toRequestBody("application/json".toMediaType()) ?: emptyJson
                val resp = http.newCall(Request.Builder()
                    .url("${server.baseUrl}$path").post(rb).build()).execute()
                resp.use {
                    if (!it.isSuccessful) error("HTTP ${it.code}: ${it.body?.string().orEmpty().take(120)}")
                }
            }
        }
}

// --- DTOs ------------------------------------------------------

@Serializable
data class PingResp(val ok: Boolean = false, val cert: String = "")

@Serializable
data class StatusResp(
    val state:       String  = "",
    val isReady:     Boolean = false,
    val lastError:   String  = "",
    val currentMac:  String? = null,
    val telemetry:   TelemetryResp? = null,
)

@Serializable
data class TelemetryResp(
    val runningMode:  String?  = null,
    val runningLabel: String?  = null,
    val ambientC:     Double?  = null,
    val housingC:     Double?  = null,
    val intakeC:      Double?  = null,
    val outletC:      Double?  = null,
    val batteryV:     Double?  = null,
    val altitudeM:    Int?     = null,
    val fanRpm:       Int?     = null,
    val pumpHz:       Double?  = null,
    val ignitionW:    Double?  = null,
    val targetC:      Double?  = null,
    val aimGear:      Int?     = null,
    val tempUnitF:    Boolean  = false,
    val faultBits:    Int      = 0,
    val updatedAtMs:  Long     = 0L,
)
