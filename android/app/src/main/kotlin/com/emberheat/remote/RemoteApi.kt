package com.emberheat.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

// HMAC-SHA256 signer for outgoing requests. Mirrors HmacAuth.cs on the
// Windows server — same canonical string format, same scheme name.
class HmacInterceptor(private val server: PairedServer) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()

        // Read the body fully into memory so we can sign it AND replay
        // it as the actual request body downstream. Bodies are tiny here
        // (a few hundred bytes at most), so the buffer cost is fine.
        val bodyBytes: ByteArray = req.body?.let {
            val buf = Buffer()
            it.writeTo(buf)
            buf.readByteArray()
        } ?: ByteArray(0)

        val ts    = System.currentTimeMillis().toString()
        val nonce = Random.nextBytes(12).toHex()

        val path  = req.url.encodedPath
        val query = req.url.encodedQuery ?: ""

        val canonical = buildString {
            append(req.method);   append('\n')
            append(path);         append('\n')
            append(query);        append('\n')
            append(ts);           append('\n')
            append(nonce);        append('\n')
            append(sha256Hex(bodyBytes))
        }

        val key = android.util.Base64.decode(server.secretBase64, android.util.Base64.DEFAULT)
        val sig = hmacSha256(key, canonical.toByteArray())
        // Standard base64 (with "=" padding, no line wraps) — matches what
        // the .NET server's Convert.FromBase64String expects.
        val sigB64 = android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP)

        val auth = "EMBER1 keyId=${server.keyId},ts=$ts,nonce=$nonce,sig=$sigB64"

        val rebuilt: Request = req.newBuilder()
            .header("Authorization", auth)
            .let {
                if (req.body != null) {
                    val contentType = req.body!!.contentType()
                    it.method(req.method, RequestBody.create(contentType, bodyBytes))
                } else it
            }
            .build()
        return chain.proceed(rebuilt)
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).toHex()
        }

        fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(msg)
        }

        fun ByteArray.toHex(): String {
            val sb = StringBuilder(size * 2)
            for (b in this) {
                sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
                sb.append(HEX[b.toInt() and 0x0F])
            }
            return sb.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}

// Builds an OkHttpClient configured for a specific paired server.
//
// Why not OkHttp's CertificatePinner? It only ADDS a pin check on top of
// the platform's default TrustManager — which rejects our self-signed
// laptop cert before the pin even gets a vote, killing the TLS handshake
// with "connection closed". We instead install a custom TrustManager that
// blesses ANY cert whose leaf SHA-256 matches the thumbprint we recorded
// at pairing time. The pin IS the trust anchor here, not an extra check.
//
// Hostname verifier is deliberately permissive: the cert thumbprint
// authenticates the server's identity; the LAN IP / host we connect to
// can change between sessions (laptop on a different network, port
// forward to a public host, etc.) and isn't in the cert's SAN.
object RemoteApi {

    fun client(server: PairedServer): OkHttpClient {
        val expectedHash = hexToBytes(server.certSha256Hex)

        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // We are the client; server never asks us to authenticate.
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("empty cert chain")
                val leaf = chain[0]
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                if (!actual.contentEquals(expectedHash)) {
                    throw CertificateException(
                        "cert pin mismatch\n" +
                        "  expected: ${bytesToHex(expectedHash)}\n" +
                        "  got:      ${bytesToHex(actual)}\n" +
                        "  chain.size=${chain.size}, subject=${leaf.subjectX500Principal.name}")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .addInterceptor(HmacInterceptor(server))
            .connectTimeout(8,  TimeUnit.SECONDS)
            .readTimeout(20,    TimeUnit.SECONDS)
            .writeTimeout(20,   TimeUnit.SECONDS)
            .build()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append("0123456789ABCDEF"[(b.toInt() ushr 4) and 0x0F])
            sb.append("0123456789ABCDEF"[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.replace(":", "").replace(" ", "")
        require(s.length % 2 == 0) { "odd-length hex" }
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }
}
