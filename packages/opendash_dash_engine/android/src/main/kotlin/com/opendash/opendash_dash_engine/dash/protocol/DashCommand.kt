package com.opendash.opendash_dash_engine.dash.protocol

/**
 * One thing we say to the dash, named — the outgoing half of [DashMessage].
 *
 * Every variant here used to be a function in `DashCommands` — deleted in stage 4 — and about half
 * of them were hex string literals copied out of captures. The literals were not the problem;
 * losing what the bytes MEAN was. `0016000200000000020100054B314720000556000155` is a
 * projection keep-alive, and nothing but a comment said so.
 *
 * The rule the whole type obeys: **bytes on the wire do not change.** Golden tests written
 * before any of this (`K1GGoldenTest`, 40 of them) are the judge, and they compare
 * against the captures, not against this code.
 */
internal sealed interface DashCommand {

    // ── Auth ──
    /** "Send me your RSA public key." */
    data object AuthRequest : DashCommand

    /** RSA-encrypted `SSID ‖ AES-256 key`. Ciphertext must be exactly 128 B. */
    data class AuthSendKey(val ciphertext: ByteArray) : DashCommand

    // ── Housekeeping ──
    /**
     * Time of day. The dash has no clock of its own — it shows whatever the phone last fed
     * it, which is why this is re-sent every 30 s rather than once.
     */
    data class TimeSync(val hour: Int, val minute: Int, val second: Int) : DashCommand

    /** Device name for the dash's "Connected to X" screen. Truncated to 200 bytes. */
    data class HostnameAnnounce(val hostname: String) : DashCommand

    // ── Navigation mode ──
    data object NavContext : DashCommand
    data object EmptyLists : DashCommand

    /** Starts navigation. Sent ONCE per session, after the route card (invariant 5). */
    data object NavStart : DashCommand
    data object NavPlaceholder : DashCommand

    // ── Projection ──
    /** Keep-alive that MUST repeat at the encoder's frame rate, or the dash drops projection. */
    data object ProjectionFrame : DashCommand
    data object ProjectionOn : DashCommand
    data object ProjectionStop : DashCommand
    data object ProjectionOff : DashCommand

    // ── Replies the dash waits for ──
    /**
     * Reply to the dash's `09 06 55` / `09 04 55`.
     *
     * Named for what it answers, not for what better-dash called it: those notifications are
     * "decoder opened", not per-frame acks (spec/video.md).
     */
    data class DecoderOpenedAck(val keyFrame: Boolean) : DashCommand

    /** Echo of a button code, sent BEFORE any other work (invariant 7). */
    data class ButtonAck(val code: Int) : DashCommand

    /**
     * What the zoom buttons did to the camera — `06 0C`, the dash's only feedback channel
     * for a control that has bottomed out.
     *
     * **Until 2026-09-25 we never sent this at all**, and the cost is on the record: the
     * 2026-09-05 ride put 110 zoom-out presses into the floor, and from the rider's seat a
     * control that has hit its limit is indistinguishable from one that is broken — nothing
     * moves and the dash says nothing. The original answers every press
     * (`docs/k1g_commands.md`, «Zoom sends up to three packets»).
     *
     * A press can produce TWO of these, and that is the original's shape rather than a
     * mistake: its two conditions are independent, so a press that moves the camera AND
     * lands on the bound sends [ZoomState.Applied] first and the bound second. A press that
     * moves nothing sends only the bound — [ZoomState.Applied] means "the camera moved",
     * not "the button was received", and the `06 80` echo already covers the latter.
     */
    data class ZoomLimit(val state: ZoomState) : DashCommand

    // ── Periodic ──
    /** 1 Hz status. On-wire temperature byte is `°C + 40`. */
    data class Heartbeat(val tempC: Int = 25) : DashCommand

    /** The 0x007E route card: destination title plus the live nav figures. */
    data class RouteCard(
        val title: String,
        val projectionOn: Boolean = false,
        val maneuver: Int? = null,
        val primaryUnit: Int? = null,
        val totalDist: Int? = null,
        val totalUnit: Int? = null,
        val etaHHMM: String? = null,
    ) : DashCommand

    /** The instruction bubble: glyph, distance to the turn, distance remaining. */
    data class ActiveNav(
        val maneuver: Int = DashGlyphs.NAV_MANEUVER_STRAIGHT,
        val primaryDist: Int = 500,
        val primaryUnit: Int = DashGlyphs.NAV_UNIT_METERS,
        val totalDist: Int = 500,
        val totalUnit: Int = DashGlyphs.NAV_UNIT_METERS,
        val projectionOn: Boolean = true,
    ) : DashCommand

    // ── Media and calls ──
    data class NowPlaying(val title: String, val album: String, val artist: String) : DashCommand
    data class CallNotify(val callerName: String) : DashCommand
    data object CallClear : DashCommand

    /**
     * A step of the initial burst that we know only as bytes.
     *
     * Six of the nine burst packets are captures whose meaning was never established: they
     * carry TLVs like `05 57 = 55` and `05 17 = AA` that appear nowhere else and are
     * documented nowhere. Expressing them as typed TLVs would dress a guess up as knowledge,
     * so they stay what they are — a TLV list and the sequence byte the capture had — and the
     * name says so.
     *
     * @param seq the rolling byte as captured. It is overwritten by `TxSequencer` at send
     *   time; it is kept only so these packets reproduce their captures byte for byte.
     * @param segCount as captured too. Most of these say `1 + tlvs.size`; the last one says
     *   `tlvs.size`, exactly like the heartbeat capture does — see [K1GPacket.build].
     */
    data class CapturedBurstStep(
        val tlvs: List<Tlv>,
        val seq: Int,
        val segCount: Int = 1 + tlvs.size,
    ) : DashCommand
}

/**
 * The three values `06 0C` carries, named.
 *
 * Wire values are the original's and are not ours to choose. The THRESHOLDS are ours: the
 * scales differ on purpose — the original steps two whole zoom levels above a floor of 8.0,
 * `DashCameraState` works in hundredths over `floor..ZOOM_MAX` with a step of 50 — so its
 * conditions cannot be ported, only its vocabulary.
 *
 * One asymmetry of the original is deliberately NOT copied: it warns about the upper bound
 * one step early and about the lower bound *three* levels early, with a two-level step. That
 * reads as an accident rather than a rule, and on a scale where the camera clamps instead of
 * refusing to move it would mean greying an icon that still works. Ours fires the bound when
 * the camera is actually ON the bound, which is the statement the dash can act on.
 */
enum class ZoomState(val wire: Int) {
    /** The camera moved. */
    Applied(0x30),

    /** The camera is at `ZOOM_MAX` and a further zoom-in cannot move it. */
    AtMax(0x20),

    /** The camera is at its floor — the dynamic one the installed packs set, not `ZOOM_MIN`. */
    AtMin(0x10),
}

/**
 * [DashCommand.TimeSync] for the phone's clock right now.
 *
 * The only place in the engine that reads the clock to build a packet, and it is here rather
 * than in [Scripts] or [DashSession] so it can be tested: a script is data, and data that
 * reads the clock cannot be compared against a capture.
 *
 * The dash has no clock source of its own — it shows whatever the phone last fed it, which is
 * why this goes out every 30 s. better-dash replays a hardcoded capture time (0x0E 0x33 0x34 =
 * 14:51:52) once, leaving the dash clock both wrong and frozen.
 */
internal fun timeSyncNow(): DashCommand.TimeSync {
    val cal = java.util.Calendar.getInstance()
    return DashCommand.TimeSync(
        hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
        minute = cal.get(java.util.Calendar.MINUTE),
        second = cal.get(java.util.Calendar.SECOND),
    )
}
