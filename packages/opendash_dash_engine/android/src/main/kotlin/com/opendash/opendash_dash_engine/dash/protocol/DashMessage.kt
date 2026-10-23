package com.opendash.opendash_dash_engine.dash.protocol

import android.icu.text.Transliterator
import java.util.concurrent.atomic.AtomicInteger

/**
 * One thing the dash said, named.
 *
 * Until 2026-09-19 the RX path worked on raw [Tlv]s and re-derived meaning at every use
 * site: four `if (tlv.type == … && tlv.sub == … && tlv.value.firstOrNull() == …)` chains in
 * `DashSession`, one more inside `DashAuth`. That is a protocol spread across its readers,
 * and it is why `09 06/04 55` was twice read as "a frame was acknowledged" — the name lived
 * in nobody's head but better-dash's, where it was wrong (see spec/video.md).
 *
 * The types here are the vocabulary the reverse engineering actually established. Anything
 * outside it stays [Unknown] rather than being guessed at: the 2026-09-13 inventory found 26
 * subtypes of `0x0C` alone and three whole types the audit had never seen.
 */
internal sealed interface DashMessage {
    /** RSA public modulus, first half of the handshake (`07 00`). */
    data class AuthModulus(val value: ByteArray) : DashMessage

    /** RSA public exponent (`07 03`). Arrives in its own packet as often as not. */
    data class AuthExponent(val value: ByteArray) : DashMessage

    /**
     * The dash's verdict on our key (`07 01`), accepted only when the first byte is `01`.
     *
     * Anything else is a rejection, and the handshake has to start over from the modulus —
     * see `DashAuth.reset` and invariant 7 of spec/k1g_protocol.md.
     */
    data class AuthResult(val accepted: Boolean) : DashMessage

    /**
     * The dash opened its H.264 decoder (`09 06 55` for an IDR, `09 04 55` for a P-frame).
     *
     * **NOT an acknowledgement of a frame.** It is a one-shot milestone, 1-3 per session,
     * and a session streaming perfectly reports zero of them for minutes on end. Two field
     * investigations (2026-08-29 and 09-06) were built on the opposite reading and both were
     * wrong; the name here is the correction, and spec/video.md carries the evidence.
     */
    data class DecoderOpened(val keyFrame: Boolean) : DashMessage

    /** A physical button on the dash (`09 00`), code in the last byte of the value. */
    data class Button(val code: Int, val raw: ByteArray) : DashMessage

    /** Identity/cipher blob (`0F`), sub carries which one. */
    data class Identity(val sub: Int, val cipher: ByteArray) : DashMessage

    /**
     * Telemetry (`0C`) — the dash's own state, 26 known subtypes and no documentation.
     *
     * Kept whole rather than decoded: nothing reads these yet, and inventing meanings for
     * them is how `09 06/04 55` went wrong.
     */
    data class Telemetry(val sub: Int, val value: ByteArray) : DashMessage

    /** Everything else, carried through intact so a log can still show it. */
    data class Unknown(val tlv: Tlv) : DashMessage
}

/**
 * Datagrams that did not parse cleanly, counted rather than rejected.
 *
 * The parser is deliberately lenient — a dash that sends one malformed packet should not
 * cost the session — but "lenient" without a counter is "silent". Both numbers were zero
 * across 7692 packets of the 2026-09-13 log, which is itself the finding: the loyalty this
 * class measures has never yet been needed.
 */
internal class MalformedCounter {
    private val lengthMismatch = AtomicInteger(0)
    private val truncatedTlv = AtomicInteger(0)

    fun onLengthMismatch() { lengthMismatch.incrementAndGet() }
    fun onTruncatedTlv() { truncatedTlv.incrementAndGet() }

    /** Counts for the window just ended, and a fresh window. */
    fun drain() = Window(lengthMismatch.getAndSet(0), truncatedTlv.getAndSet(0))

    /** Renders as `mismatch/truncated`. */
    data class Window(val lengthMismatch: Int, val truncatedTlv: Int) {
        val isEmpty: Boolean get() = lengthMismatch == 0 && truncatedTlv == 0
        override fun toString() = "$lengthMismatch/$truncatedTlv"
    }
}

/** Typed view over [K1GPacket] — same bytes, named contents. */
internal object K1GCodec {

    // Android ICU documents transliterators as stateless and safe to share between callers.
    private val displayTransliterator: Transliterator by lazy {
        Transliterator.getInstance("NFC; Russian-Latin/BGN; Any-Latin; Latin-ASCII")
    }

    /**
     * Every message in one datagram, in wire order.
     *
     * Order matters and is preserved: invariant 7 requires a button ack to be sent before any
     * other work, and a packet can carry a button next to telemetry.
     */
    fun decode(datagram: ByteArray, malformed: MalformedCounter? = null): List<DashMessage> {
        if (malformed != null && datagram.size >= 2) {
            val declared = ((datagram[0].toInt() and 0xFF) shl 8) or (datagram[1].toInt() and 0xFF)
            if (declared != datagram.size) malformed.onLengthMismatch()
        }
        return K1GPacket.parseIncoming(datagram) { malformed?.onTruncatedTlv() }
            .map { tlv -> classify(tlv) }
    }

    /**
     * Bytes for one command — the single place that knows the wire format of what we send.
     *
     * Existing captured fields keep their value rather than a tidier one:
     * `05 03 = 0x34`, `05 0B = "001000"`, `05 55 = 0x20`. New media forms follow
     * the static contract in spec/multimedia.md; encoding does not prove hardware support.
     * Logical album art is not a scalar packet and must be expanded by the writer.
     */
    fun encode(cmd: DashCommand): ByteArray = when (cmd) {
        DashCommand.AuthRequest -> K1GPacket.build(K1GPacket.tlv(0x08, 0x04, 0x01))

        is DashCommand.AuthSendKey -> {
            require(cmd.ciphertext.size == 128) {
                "auth key expects 128B RSA ciphertext, got ${cmd.ciphertext.size}"
            }
            K1GPacket.build(K1GPacket.tlv(0x08, 0x00, cmd.ciphertext))
        }

        is DashCommand.TimeSync ->
            K1GPacket.build(K1GPacket.tlv(0x06, 0x06, cmd.hour, cmd.minute, cmd.second))

        is DashCommand.HostnameAnnounce -> {
            val raw = cmd.hostname.toByteArray(Charsets.UTF_8).let {
                if (it.size > MAX_HOSTNAME_BYTES) it.copyOf(MAX_HOSTNAME_BYTES) else it
            }
            // seq 0x01 as captured, and NOT a placeholder: this is the one command whose
            // capture carries a nonzero one outside the burst. TxSequencer overwrites it.
            K1GPacket.build(listOf(K1GPacket.tlv(0x06, 0x0B, raw + 0x00.toByte())), seq = 0x01)
        }

        DashCommand.NavContext -> K1GPacket.build(K1GPacket.tlv(0x05, 0x2E, 0x1E))
        DashCommand.EmptyLists -> K1GPacket.build(
            (0x2F..0x33).map { sub -> K1GPacket.tlv(0x05, sub, 0x00) },
        )
        DashCommand.NavStart -> K1GPacket.build(K1GPacket.tlv(0x06, 0x80, 0x0B))
        DashCommand.NavPlaceholder -> K1GPacket.build(K1GPacket.tlv(0x06, 0x0A, 0x00, 0x00))

        DashCommand.ProjectionFrame -> K1GPacket.build(K1GPacket.tlv(0x05, 0x56, 0x55))
        DashCommand.ProjectionOn -> K1GPacket.build(K1GPacket.tlv(0x06, 0x05, 0x55))
        DashCommand.ProjectionStop -> K1GPacket.build(K1GPacket.tlv(0x05, 0x56, 0xAA))
        DashCommand.ProjectionOff -> K1GPacket.build(K1GPacket.tlv(0x06, 0x05, 0xAA))

        is DashCommand.DecoderOpenedAck ->
            K1GPacket.build(K1GPacket.tlv(0x06, if (cmd.keyFrame) 0x11 else 0x12, 0x55))

        is DashCommand.ButtonAck -> K1GPacket.build(K1GPacket.tlv(0x06, 0x80, cmd.code))

        is DashCommand.ZoomLimit -> K1GPacket.build(K1GPacket.tlv(0x06, 0x0C, cmd.state.wire))

        is DashCommand.Heartbeat -> K1GPacket.build(
            listOf(
                K1GPacket.tlv(0x06, 0x08, 0x05),
                // °C + 40 on the wire.
                K1GPacket.tlv(0x06, 0x10, (cmd.tempC + 40) and 0xFF),
                K1GPacket.tlv(0x06, 0x03, 0x55),
                K1GPacket.tlv(0x06, 0x04, 0xA2),
                K1GPacket.tlv(0x06, 0x0F, 0xAA),
                K1GPacket.tlv(0x06, 0x01, 0x01),
                K1GPacket.tlv(0x05, 0x4C, cmd.musicVolume),
                K1GPacket.tlv(0x05, 0x2D, 0x00, 0x00),
                K1GPacket.tlv(0x05, 0x1B, cmd.callVolume),
                K1GPacket.tlv(0x05, 0x21, 0x32),
                K1GPacket.tlv(0x05, 0x4D, 0x32),
            ),
            // ELEVEN for eleven TLVs — see K1GPacket.build. The capture says so, and the
            // capture is the only thing that is known to work.
            segCount = 11,
        )

        is DashCommand.RouteCard -> {
            val title = displayText(cmd.title, MAX_TITLE_BYTES) + 0x00.toByte()
            // Four BYTES, not four Chars: the field is a fixed-width ASCII HH:MM and the
            // dash reads it positionally, so a string that encodes to any other length is
            // refused rather than shipped short. etaHHMM crosses the MethodChannel, so
            // "it is always four digits" is a claim about a caller we do not compile with.
            val eta = cmd.etaHHMM?.toByteArray(Charsets.US_ASCII)?.takeIf { it.size == 4 }
                ?: CAPTURED_ETA
            K1GPacket.build(
                listOf(
                    K1GPacket.tlv(0x05, 0x01, title),
                    K1GPacket.tlv(0x05, 0x02, cmd.maneuver ?: CAPTURED_MANEUVER),
                    K1GPacket.tlv(0x05, 0x03, 0x34),
                    // The captured ride's secondary distance (0x000A) is always zeroed: a
                    // card re-sent at 1 Hz would otherwise keep asserting a figure from a
                    // French road none of our riders is on.
                    K1GPacket.tlv(0x05, 0x05, 0x00, 0x00),
                    K1GPacket.tlv(0x05, 0x06, cmd.primaryUnit ?: 0x30),
                    K1GPacket.tlv(0x05, 0x07, 0x30),
                    K1GPacket.tlv(0x05, 0x08, eta),
                    K1GPacket.tlv(0x05, 0x54, 0x30),
                    // Same reasoning as 05 05: default 0, not the capture's 0x004F.
                    K1GPacket.tlv(0x05, 0x09, u16(cmd.totalDist ?: 0)),
                    K1GPacket.tlv(0x05, 0x46, cmd.totalUnit ?: 0x10),
                    K1GPacket.tlv(0x05, 0x0A, 0x55),
                    K1GPacket.tlv(0x05, 0x0C, 0x04),
                    K1GPacket.tlv(0x05, 0x0B, CAPTURED_050B),
                    K1GPacket.tlv(0x05, 0x55, 0x20),
                    K1GPacket.tlv(0x06, 0x05, if (cmd.projectionOn) 0x55 else 0xAA),
                    K1GPacket.tlv(0x06, 0x0D, 0xAA),
                ),
            )
        }

        is DashCommand.ActiveNav -> K1GPacket.build(
            listOf(
                K1GPacket.tlv(0x05, 0x02, cmd.maneuver),
                K1GPacket.tlv(0x05, 0x04, u16(cmd.primaryDist)),
                K1GPacket.tlv(0x05, 0x06, cmd.primaryUnit),
                K1GPacket.tlv(0x05, 0x09, u16(cmd.totalDist)),
                K1GPacket.tlv(0x05, 0x46, cmd.totalUnit),
                K1GPacket.tlv(0x05, 0x0A, 0x55),   // decimal separator '.'
                K1GPacket.tlv(0x06, 0x05, if (cmd.projectionOn) 0x55 else 0xAA),
                K1GPacket.tlv(0x06, 0x0D, 0xAA),   // decimal format off
            ),
        )

        is DashCommand.NowPlaying -> K1GPacket.build(
            K1GPacket.tlv(
                0x05, 0x0D,
                displayText(cmd.title, MEDIA_FIELD_MAX) + 0x00 +
                    displayText(cmd.album, MEDIA_FIELD_MAX) + 0x00 +
                    displayText(cmd.artist, MEDIA_FIELD_MAX),
            ),
        )
        is DashCommand.MediaActive ->
            K1GPacket.build(K1GPacket.tlv(0x05, 0x17, if (cmd.active) 0x55 else 0xAA))
        is DashCommand.MediaPlayback ->
            K1GPacket.build(K1GPacket.tlv(0x05, 0x19, if (cmd.playing) 0x55 else 0xAA))
        is DashCommand.AlbumArtMarker ->
            K1GPacket.build(K1GPacket.tlv(0x05, 0x58, if (cmd.present) 0x55 else 0xAA))
        is DashCommand.AlbumArtChunk ->
            K1GPacket.build(K1GPacket.tlv(0x05, 0x40, cmd.value))
        is DashCommand.AlbumArt ->
            throw IllegalArgumentException("AlbumArt must be expanded into scalar chunks before encoding")
        is DashCommand.CallNotify ->
            K1GPacket.build(K1GPacket.tlv(0x05, 0x22, displayText(cmd.callerName, MEDIA_FIELD_MAX) + 0x00))
        DashCommand.CallClear -> K1GPacket.build(K1GPacket.tlv(0x05, 0x22, 0x00))

        is DashCommand.CapturedBurstStep ->
            K1GPacket.build(cmd.tlvs, segCount = cmd.segCount, seq = cmd.seq)
    }

    /** Big-endian u16 as a two-byte value. */
    private fun u16(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun displayText(value: String, maxBytes: Int): ByteArray {
        val asciiLength = minOf(value.length, maxBytes)
        var asciiEnd = 0
        while (asciiEnd < asciiLength && value[asciiEnd].code <= 0x7F) asciiEnd++
        if (asciiEnd == asciiLength) {
            return ByteArray(asciiLength) { value[it].code.toByte() }
        }

        // ICU needs the right context too (e.g. Ё before a lowercase letter).
        // Transliterating a field-sized source prefix can change even its first
        // output bytes. Bound the source work, then truncate only the result.
        var sourceLimit = minOf(value.length, MAX_DISPLAY_INPUT_CHARS)
        if (sourceLimit < value.length && Character.isHighSurrogate(value[sourceLimit - 1]) &&
            Character.isLowSurrogate(value[sourceLimit])
        ) {
            sourceLimit--
        }
        val latin = displayTransliterator.transliterate(value.substring(0, sourceLimit))
        // At most two UTF-16 units per output code point; do not scan an unused tail.
        val size = minOf(maxBytes, latin.codePointCount(0, minOf(latin.length, maxBytes * 2)))
        val bytes = ByteArray(size)
        var offset = 0
        for (i in bytes.indices) {
            val codePoint = Character.codePointAt(latin, offset)
            bytes[i] = (if (codePoint <= 0x7F) codePoint else '?'.code).toByte()
            offset += Character.charCount(codePoint)
        }
        return bytes
    }


    private const val MAX_DISPLAY_INPUT_CHARS = 4096
    private const val MEDIA_FIELD_MAX = 20
    private const val MAX_HOSTNAME_BYTES = 200
    private const val MAX_TITLE_BYTES = 60

    /** Captured defaults kept verbatim — nobody has established what they mean. */
    private val CAPTURED_ETA = "0303".toByteArray(Charsets.US_ASCII)
    private const val CAPTURED_MANEUVER = 0x3C
    private val CAPTURED_050B = "001000".toByteArray(Charsets.US_ASCII)

    private fun classify(tlv: Tlv): DashMessage = when {
        tlv.type == 0x07 && tlv.sub == 0x00 -> DashMessage.AuthModulus(tlv.value)
        tlv.type == 0x07 && tlv.sub == 0x03 -> DashMessage.AuthExponent(tlv.value)
        tlv.type == 0x07 && tlv.sub == 0x01 ->
            DashMessage.AuthResult(accepted = tlv.value.firstOrNull() == 0x01.toByte())
        // The 0x55 is part of the signal, not decoration: the same sub with another value is
        // something else, and treating it as a decoder-open would resurrect the reading
        // spec/video.md spent two field sessions falsifying.
        tlv.type == 0x09 && tlv.sub == 0x06 && tlv.value.firstOrNull()?.toInt() == 0x55 ->
            DashMessage.DecoderOpened(keyFrame = true)
        tlv.type == 0x09 && tlv.sub == 0x04 && tlv.value.firstOrNull()?.toInt() == 0x55 ->
            DashMessage.DecoderOpened(keyFrame = false)
        tlv.type == 0x09 && tlv.sub == 0x00 && tlv.value.isNotEmpty() ->
            // Last byte, not first: the captures carry `09 00 00 01 <code>` and also longer
            // forms, and it is the trailing byte that identifies the button in both.
            DashMessage.Button(code = tlv.value.last().toInt() and 0xFF, raw = tlv.value)
        tlv.type == 0x0F -> DashMessage.Identity(tlv.sub, tlv.value)
        tlv.type == 0x0C -> DashMessage.Telemetry(tlv.sub, tlv.value)
        // Truncation is counted by K1GPacket.parseIncoming, which is the only place that
        // still knows the declared length; by the time a Tlv exists it reads as complete.
        else -> DashMessage.Unknown(tlv)
    }
}
