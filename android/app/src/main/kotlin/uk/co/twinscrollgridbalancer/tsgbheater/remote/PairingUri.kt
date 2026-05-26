package uk.co.twinscrollgridbalancer.tsgbheater.remote

import java.net.URLDecoder

// Pairing URI shape (emitted by the Windows app's QR generator):
//   tsgb://pair?u=<percent-encoded https URL>&k=<keyId>&s=<base64url secret>&t=<cert sha256 hex>
//
// We parse this by hand rather than via android.net.Uri because Uri.parse
// is fussy about authority-vs-path edges and we've observed stray slashes
// from some QR producers tripping up host detection. Format we accept,
// case-insensitive scheme, any of:
//   tsgb://pair?<query>
//   tsgb://pair/?<query>
//   tsgb:pair?<query>
//   tsgb:/pair?<query>
//   tsgb:///pair?<query>
data class PairingPayload(
    val baseUrl:       String,
    val keyId:         String,
    val secretBase64:  String,    // converted back to standard base64
    val certSha256Hex: String,
)

object PairingUri {

    fun parse(raw: String): PairingPayload? {
        if (raw.isBlank()) return null
        val text = raw.trim()

        val schemeIdx = text.indexOf(':')
        if (schemeIdx <= 0) return null
        val scheme = text.substring(0, schemeIdx)
        if (!scheme.equals("tsgb", ignoreCase = true)) return null

        // Find the ? that starts the query string. Everything between
        // the scheme and the ? is "tsgb://[/]pair[/]" — we don't care
        // about exact slash count as long as the literal "pair" sits in
        // there somewhere.
        val q = text.indexOf('?', schemeIdx)
        if (q < 0) return null

        val authPath = text.substring(schemeIdx + 1, q)
        if (!authPath.replace("/", "").equals("pair", ignoreCase = true)) return null

        val query = text.substring(q + 1)
        val params = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val k = pair.substring(0, eq)
            val v = try { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") } catch (_: Throwable) { continue }
            params[k] = v
        }

        // Treat empty values as missing — older Windows builds emitted the
        // QR before the server had loaded its cert, producing `&t=` with no
        // payload. Catch that here so the user sees "missing thumbprint"
        // rather than a downstream "cert pin mismatch (expected: <blank>)".
        val url   = params["u"]?.takeIf { it.isNotBlank() } ?: return null
        val keyId = params["k"]?.takeIf { it.isNotBlank() } ?: return null
        val sec   = params["s"]?.takeIf { it.isNotBlank() } ?: return null
        val cert  = params["t"]?.takeIf { it.isNotBlank() } ?: return null

        // Some senders sneak a trailing slash onto the base URL — strip
        // it so OkHttp doesn't end up with `https://host//api/ping`.
        val cleanUrl = url.trimEnd('/')

        // Server emits URL-safe base64 (`-` and `_`, no padding) so it
        // fits cleanly in a query string. Convert back to standard
        // base64 for storage so the rest of the codebase doesn't have
        // to know about the wire encoding.
        val standard = sec.replace('-', '+').replace('_', '/')
        val padded = when (standard.length % 4) {
            2 -> "$standard=="
            3 -> "$standard="
            else -> standard
        }

        return PairingPayload(
            baseUrl       = cleanUrl,
            keyId         = keyId,
            secretBase64  = padded,
            certSha256Hex = cert.uppercase(),
        )
    }
}
