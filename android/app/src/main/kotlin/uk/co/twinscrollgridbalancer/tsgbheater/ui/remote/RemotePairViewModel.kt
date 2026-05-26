package uk.co.twinscrollgridbalancer.tsgbheater.ui.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator
import uk.co.twinscrollgridbalancer.tsgbheater.remote.PairedServer
import uk.co.twinscrollgridbalancer.tsgbheater.remote.PairingUri
import uk.co.twinscrollgridbalancer.tsgbheater.remote.RemoteHeaterClient

data class RemotePairUi(
    val paired:    List<PairedServer> = emptyList(),
    val currentId: String?            = null,
    val info:      String?            = null,
    val error:     String?            = null,
    val busy:      Boolean            = false,
)

class RemotePairViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ServiceLocator.pairedServers
    private val _ui = MutableStateFlow(RemotePairUi())
    val ui: StateFlow<RemotePairUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            store.all.collect { list -> _ui.value = _ui.value.copy(paired = list) }
        }
        viewModelScope.launch {
            store.currentId.collect { id -> _ui.value = _ui.value.copy(currentId = id) }
        }
    }

    // Called by the screen after a successful QR scan. Parses, verifies
    // both transport (cert pin) and auth (HMAC signing), then persists.
    fun acceptScannedUri(text: String) = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true, info = "Verifying…", error = null)
        try {
            val p = PairingUri.parse(text)
                ?: run { fail("Could not parse pairing URI:\n$text"); return@launch }

            val transient = PairedServer(
                id            = "transient",
                label         = "Probe",
                baseUrl       = p.baseUrl,
                keyId         = p.keyId,
                secretBase64  = p.secretBase64,
                certSha256Hex = p.certSha256Hex,
                pairedAtMs    = System.currentTimeMillis(),
            )
            val client = RemoteHeaterClient(transient)

            val ping = client.ping()
            if (ping.isFailure) { fail("Cannot reach laptop: ${ping.exceptionOrNull()?.message}"); return@launch }
            val seen = ping.getOrThrow().uppercase()
            if (seen != p.certSha256Hex.uppercase()) {
                fail("Cert thumbprint mismatch"); return@launch
            }
            val auth = client.verifyAuth()
            if (auth.isFailure) { fail("Auth failed: ${auth.exceptionOrNull()?.message}"); return@launch }

            val saved = store.add(
                label         = "Laptop",
                baseUrl       = p.baseUrl,
                keyId         = p.keyId,
                secretBase64  = p.secretBase64,
                certSha256Hex = p.certSha256Hex,
            )
            _ui.value = _ui.value.copy(busy = false, info = "Paired with ${saved.label}", error = null)
        } catch (t: Throwable) {
            fail(t.message ?: "Pairing failed")
        }
    }

    private fun fail(msg: String) {
        _ui.value = _ui.value.copy(busy = false, info = null, error = msg)
    }

    fun unpair(id: String) = viewModelScope.launch { store.remove(id) }
    fun makeCurrent(id: String) = viewModelScope.launch { store.setCurrent(id) }
}
