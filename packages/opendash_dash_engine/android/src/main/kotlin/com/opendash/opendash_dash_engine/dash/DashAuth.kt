package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.protocol.DashCommands
import com.opendash.opendash_dash_engine.dash.protocol.Tlv
import com.opendash.opendash_dash_engine.util.DebugLog
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/** Result of feeding one incoming TLV into the auth state machine. */
sealed class AuthEvent {
    /** Both pubkey halves received — send this q3c.d packet now. */
    data class SendKey(val packet: ByteArray) : AuthEvent()
    /** Dash confirmed (07 01 01). */
    object Confirmed : AuthEvent()
    /** Dash rejected (07 01 != 01) — resend authRequest if retries remain. */
    object Rejected : AuthEvent()
    object None : AuthEvent()
}

/**
 * RSA-1024 + AES-256 handshake state machine, ported from better-dash AuthState.
 *
 * The dash sends modulus (07 00) and exponent (07 03) — possibly in SEPARATE
 * packets — so state accumulates across calls. The session-key packet is
 * emitted exactly once per attempt; [reset] re-arms it after a rejection.
 */
class DashAuth(private val ssid: String) {
    private companion object {
        const val TAG = "DashAuth"
        /** RSA-1024 PKCS#1 v1.5 plaintext limit (117 B) minus the 32-byte AES key. */
        const val MAX_SSID_BYTES = 85
    }

    private var modulus: BigInteger? = null
    private var exponent: BigInteger? = null
    private var keySent = false

    var sessionKey: ByteArray? = null
        private set

    fun ingest(tlv: Tlv): AuthEvent {
        if (tlv.type != 0x07) return AuthEvent.None
        when (tlv.sub) {
            0x00 -> modulus  = BigInteger(1, tlv.value)
            0x03 -> exponent = BigInteger(1, tlv.value)
            0x01 -> return if (tlv.value.firstOrNull() == 0x01.toByte())
                AuthEvent.Confirmed else AuthEvent.Rejected
            else -> return AuthEvent.None
        }

        val m = modulus; val e = exponent
        if (!keySent && m != null && e != null) {
            keySent = true
            return AuthEvent.SendKey(buildKeyPacket(m, e))
        }
        return AuthEvent.None
    }

    /** Re-arm after a 07 01 != 01 rejection so the dash can re-offer its pubkey. */
    fun reset() {
        modulus = null
        exponent = null
        keySent = false
    }

    private fun buildKeyPacket(modulus: BigInteger, exponent: BigInteger): ByteArray {
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sessionKey = aes

        // RSA-1024 with PKCS#1 v1.5 padding tops out at 117 bytes of plaintext, and the AES
        // key already claims 32 of them. A real 802.11 SSID is capped at 32 bytes so this can
        // only trip on a hand-entered one; truncate rather than let doFinal throw, since this
        // runs inside the RX loop where the throw would fail the whole session.
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8).let {
            if (it.size > MAX_SSID_BYTES) {
                DebugLog.w(TAG) { "SSID is ${it.size}B — truncating to $MAX_SSID_BYTES to fit the RSA block" }
                it.copyOf(MAX_SSID_BYTES)
            } else it
        }
        val payload = ssidBytes + aes
        val pubKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        return DashCommands.authSendKey(cipher.doFinal(payload))
    }
}
