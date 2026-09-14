package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.protocol.Tlv
import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Characterization test for the RSA-1024 → AES-256 handshake.
 *
 * The dash offers its public key as two TLVs — modulus (07 00) and exponent (07 03) — and
 * they do not have to arrive in the same datagram, so [DashAuth] accumulates across calls
 * and fires exactly once when both halves are in hand. Getting that wrong is not a visible
 * bug: a second key packet, or one built from a half-filled key, simply makes the dash go
 * quiet, and the session dies later to the RX watchdog with nothing in the ride log
 * pointing here.
 *
 * The key pair is generated inside the test rather than replayed from a capture: the dash
 * mints a fresh one per session, and pinning one would prove only that this test's own
 * bytes round-trip. What IS pinned is the plaintext layout — `ssid ‖ 32-byte AES key` —
 * because that is what the dash's firmware parses.
 */
class DashAuthTest {

    private companion object {
        const val SSID = "TRIPPER_1234"
        const val AES_KEY_BYTES = 32

        /** RSA-1024 PKCS#1 v1.5 plaintext limit (117 B) minus the AES key. */
        const val MAX_SSID_BYTES = 85
    }

    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()

    private val modulusTlv: Tlv
    private val exponentTlv: Tlv

    init {
        val spec = java.security.KeyFactory.getInstance("RSA")
            .getKeySpec(keyPair.public, java.security.spec.RSAPublicKeySpec::class.java)
        // BigInteger.toByteArray() may prepend a sign byte; DashAuth reads with
        // BigInteger(1, value), which ignores it, so feeding it raw is faithful to the wire.
        modulusTlv = Tlv(0x07, 0x00, spec.modulus.toByteArray())
        exponentTlv = Tlv(0x07, 0x03, spec.publicExponent.toByteArray())
    }

    /** The plaintext the dash will see once it decrypts the packet [DashAuth] emitted. */
    private fun decrypt(sendKey: AuthEvent.SendKey): ByteArray {
        val ciphertext = sendKey.packet.copyOfRange(21, sendKey.packet.size)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        return cipher.doFinal(ciphertext)
    }

    private fun sendKeyAfterBothHalves(auth: DashAuth, exponentFirst: Boolean = false): AuthEvent.SendKey {
        val first = if (exponentFirst) exponentTlv else modulusTlv
        val second = if (exponentFirst) modulusTlv else exponentTlv

        assertIs<AuthEvent.None>(auth.ingest(first), "one half of the key is not enough")
        val event = auth.ingest(second)

        return assertIs<AuthEvent.SendKey>(event, "both halves are in — the key packet is due")
    }

    // ── Accumulating the public key ───────────────────────────────────────

    @Test
    fun `the key packet is emitted once both halves have arrived`() {
        val auth = DashAuth(SSID)

        val sendKey = sendKeyAfterBothHalves(auth)

        assertEquals(149, sendKey.packet.size, "a q3c.d packet: 21-byte header + 128 ciphertext")
    }

    @Test
    fun `modulus and exponent may arrive in either order`() {
        // Two separate datagrams in the captures, and nothing guarantees which lands first.
        val sendKey = sendKeyAfterBothHalves(DashAuth(SSID), exponentFirst = true)

        val plaintext = decrypt(sendKey)
        assertEquals(SSID, String(plaintext.copyOfRange(0, SSID.length), Charsets.UTF_8))
    }

    @Test
    fun `the plaintext is the SSID followed by exactly 32 key bytes`() {
        val auth = DashAuth(SSID)

        val plaintext = decrypt(sendKeyAfterBothHalves(auth))

        assertEquals(SSID.length + AES_KEY_BYTES, plaintext.size)
        assertEquals(SSID, String(plaintext.copyOfRange(0, SSID.length), Charsets.UTF_8))
        assertContentEquals(
            plaintext.copyOfRange(SSID.length, plaintext.size),
            assertNotNull(auth.sessionKey, "the key must be readable for the AES stage"),
            "sessionKey is what was actually encrypted, not a second draw",
        )
    }

    @Test
    fun `the key packet fires exactly once, however many TLVs keep coming`() {
        // The dash re-sends its pubkey while it waits. A second q3c.d would restart the
        // firmware's handshake with a different AES key and strand the session.
        val auth = DashAuth(SSID)
        sendKeyAfterBothHalves(auth)

        assertIs<AuthEvent.None>(auth.ingest(modulusTlv))
        assertIs<AuthEvent.None>(auth.ingest(exponentTlv))
        assertIs<AuthEvent.None>(auth.ingest(modulusTlv))
    }

    // ── Confirmation and rejection ────────────────────────────────────────

    @Test
    fun `07 01 01 is a confirmation and any other value is a rejection`() {
        val auth = DashAuth(SSID)

        assertIs<AuthEvent.Confirmed>(auth.ingest(Tlv(0x07, 0x01, byteArrayOf(0x01))))
        assertIs<AuthEvent.Rejected>(auth.ingest(Tlv(0x07, 0x01, byteArrayOf(0x00))))
        assertIs<AuthEvent.Rejected>(auth.ingest(Tlv(0x07, 0x01, byteArrayOf(0x02))))
        assertIs<AuthEvent.Rejected>(
            auth.ingest(Tlv(0x07, 0x01, ByteArray(0))),
            "an empty result field must not read as success",
        )
    }

    @Test
    fun `after a rejection, reset re-arms the machine for a second attempt`() {
        val auth = DashAuth(SSID)
        val firstKey = decrypt(sendKeyAfterBothHalves(auth))
        assertIs<AuthEvent.Rejected>(auth.ingest(Tlv(0x07, 0x01, byteArrayOf(0x00))))

        auth.reset()
        val secondKey = decrypt(sendKeyAfterBothHalves(auth))

        val firstAes = firstKey.copyOfRange(SSID.length, firstKey.size)
        val secondAes = secondKey.copyOfRange(SSID.length, secondKey.size)
        assertEquals(AES_KEY_BYTES, secondAes.size)
        assertTrue(
            !firstAes.contentEquals(secondAes),
            "each attempt must draw a fresh AES key — SecureRandom is not injected, so this " +
                "is checked by uniqueness rather than by a fixed expectation",
        )
    }

    @Test
    fun `without reset, a rejection leaves the machine unable to retry`() {
        // Pins why DashSession calls reset() before re-sending authRequest (инвариант 7):
        // the state machine will not emit a second key on its own.
        val auth = DashAuth(SSID)
        sendKeyAfterBothHalves(auth)
        auth.ingest(Tlv(0x07, 0x01, byteArrayOf(0x00)))

        assertIs<AuthEvent.None>(auth.ingest(modulusTlv))
        assertIs<AuthEvent.None>(auth.ingest(exponentTlv))
    }

    // ── Everything else on the wire ───────────────────────────────────────

    @Test
    fun `TLVs outside type 07 are ignored`() {
        val auth = DashAuth(SSID)

        assertIs<AuthEvent.None>(auth.ingest(Tlv(0x09, 0x06, byteArrayOf(0x55))))
        assertIs<AuthEvent.None>(auth.ingest(Tlv(0x0C, 0x01, byteArrayOf(0x01))))
        assertIs<AuthEvent.None>(auth.ingest(Tlv(0x07, 0x42, byteArrayOf(0x01))), "unknown 07 sub")
    }

    // ── SSID length ───────────────────────────────────────────────────────

    @Test
    fun `an over-long SSID is truncated to fit the RSA block instead of throwing`() {
        // 117 bytes of PKCS#1 plaintext minus the 32-byte key leaves 85. A real 802.11 SSID
        // caps at 32, so this can only come from a hand-entered one — and the throw it
        // replaces would happen inside the RX loop and kill the whole session.
        val longSsid = "S".repeat(100)
        val auth = DashAuth(longSsid)

        val plaintext = decrypt(sendKeyAfterBothHalves(auth))

        assertEquals(MAX_SSID_BYTES + AES_KEY_BYTES, plaintext.size)
        assertContentEquals(
            longSsid.substring(0, MAX_SSID_BYTES).toByteArray(Charsets.UTF_8),
            plaintext.copyOfRange(0, MAX_SSID_BYTES),
        )
    }

    @Test
    fun `an SSID exactly at the limit is not truncated`() {
        val auth = DashAuth("S".repeat(MAX_SSID_BYTES))

        val plaintext = decrypt(sendKeyAfterBothHalves(auth))

        assertEquals(MAX_SSID_BYTES + AES_KEY_BYTES, plaintext.size)
    }

    @Test
    fun `one byte over the limit is already truncated`() {
        // The other side of the boundary, and the one that bites. Without it a cap raised
        // to anything below the 100-byte SSID above still passes every test in this file
        // while building a plaintext the RSA block cannot hold — and that throw happens
        // inside the RX loop, where it takes the session with it.
        val auth = DashAuth("S".repeat(MAX_SSID_BYTES + 1))

        val plaintext = decrypt(sendKeyAfterBothHalves(auth))

        assertEquals(MAX_SSID_BYTES + AES_KEY_BYTES, plaintext.size)
    }

    @Test
    fun `the SSID limit counts bytes, so a Cyrillic name truncates sooner in characters`() {
        // Same byte-vs-character cut as the hostname announce. Documented rather than
        // endorsed: an SSID of 50 Cyrillic characters is 100 bytes and loses the last 15.
        val auth = DashAuth("ю".repeat(50))

        val plaintext = decrypt(sendKeyAfterBothHalves(auth))

        assertEquals(MAX_SSID_BYTES + AES_KEY_BYTES, plaintext.size)
        assertEquals(
            0xD1,
            plaintext[MAX_SSID_BYTES - 1].toInt() and 0xFF,
            "the cut lands mid-character, leaving a lone UTF-8 lead byte",
        )
    }
}
