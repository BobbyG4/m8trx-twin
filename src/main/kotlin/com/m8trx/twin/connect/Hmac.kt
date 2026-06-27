package com.m8trx.twin.connect

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 sign + verify for the Connect `X-M8TRX-Signature` header.
 *
 * The same primitive runs on BOTH sides of the wire, opposite directions:
 *   - inbound push  — sign the raw request body with the integration's hmac_secret,
 *                     send as `X-M8TRX-Signature: sha256=<hex>` (Connect API doc §8).
 *   - outbound recv — verify M8TRX's `X-M8TRX-Signature` over the exact bytes received (§9).
 *
 * HARD RULE: always sign / verify over the EXACT transmitted bytes — never a re-serialization.
 * The number-one HMAC bug is signing one serialization and sending another.
 */
object Hmac {
    private const val ALGORITHM = "HmacSHA256"
    private const val PREFIX = "sha256="

    /** Lowercase-hex HMAC-SHA256 of [body] under [secret]. */
    fun hex(secret: String, body: ByteArray): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    /** The full `X-M8TRX-Signature` header value: `sha256=<hex>`. */
    fun signatureHeader(secret: String, body: ByteArray): String = PREFIX + hex(secret, body)

    /**
     * Constant-time verify. Strips an optional `sha256=` prefix and lower-cases before comparison
     * (matching core's verifier). Returns false on null / blank / malformed input rather than throwing.
     */
    fun verify(secret: String, body: ByteArray, providedSignature: String?): Boolean {
        if (providedSignature.isNullOrBlank()) return false
        val provided = providedSignature.removePrefix(PREFIX).trim().lowercase()
        val expected = hex(secret, body)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
    }
}
