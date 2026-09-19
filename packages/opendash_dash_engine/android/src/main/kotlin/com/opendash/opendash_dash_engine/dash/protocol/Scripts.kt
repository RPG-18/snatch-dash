package com.opendash.opendash_dash_engine.dash.protocol

/**
 * One command and how long to wait after it.
 *
 * The pause is part of the protocol, not of the code that happens to send it: the dash
 * answers the initial burst only if the packets arrive spaced out, and the nav-entry sequence
 * has pauses of 40/100/500/60/10 ms that were read off a capture of the original app. They
 * are invariant 5 of network-refactoring.md — "нельзя менять байты, порядок пакетов и паузы
 * между ними" — so they belong next to the bytes rather than inside a `suspend fun` where
 * the next refactor can quietly reorder them.
 */
internal data class Step(val cmd: DashCommand, val pauseAfterMs: Long)

/** The two fixed conversations with the dash, as data. */
internal object Scripts {

    /**
     * What we say the moment the socket opens.
     *
     * The dash answers with its RSA public key on :2002 only after seeing this on :2000 —
     * that is the whole reason these nine packets exist in this order. Six of them are
     * captures nobody has explained; see [DashCommand.CapturedBurstStep].
     *
     * Pause: 20 ms between all nine (invariant 4).
     */
    fun initialBurst(hostname: String, timeSync: DashCommand.TimeSync): List<Step> = listOf(
        Step(DashCommand.AuthRequest, BURST_PAUSE_MS),
        Step(DashCommand.HostnameAnnounce(hostname), BURST_PAUSE_MS),
        // Time comes in as a parameter rather than being read here: a script is data, and
        // data that reads the clock cannot be compared against a capture in a test.
        Step(timeSync, BURST_PAUSE_MS),
        Step(capturedStep(0x03, K1GPacket.tlv(0x05, 0x57, 0x55)), BURST_PAUSE_MS),
        Step(capturedStep(0x04, K1GPacket.tlv(0x05, 0x56, 0xAA)), BURST_PAUSE_MS),
        Step(capturedStep(0x05, K1GPacket.tlv(0x06, 0x05, 0xAA)), BURST_PAUSE_MS),
        Step(capturedStep(0x06, K1GPacket.tlv(0x05, 0x17, 0xAA)), BURST_PAUSE_MS),
        Step(
            capturedStep(
                0x08,
                K1GPacket.tlv(0x0A, 0x02, 0xAA, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            ),
            BURST_PAUSE_MS,
        ),
        // The ninth is the heartbeat's twin: same ten TLVs, and the same seg_count anomaly
        // (ten declared for ten TLVs, where everything else declares one more). Its first
        // field differs — 06 08 = FF here against 05 in the heartbeat.
        Step(
            DashCommand.CapturedBurstStep(
                tlvs = listOf(
                    K1GPacket.tlv(0x06, 0x08, 0xFF),
                    K1GPacket.tlv(0x06, 0x03, 0x55),
                    K1GPacket.tlv(0x06, 0x04, 0xA2),
                    K1GPacket.tlv(0x06, 0x0F, 0xAA),
                    K1GPacket.tlv(0x06, 0x01, 0x01),
                    K1GPacket.tlv(0x05, 0x4C, 0x13),
                    K1GPacket.tlv(0x05, 0x2D, 0x00, 0x00),
                    K1GPacket.tlv(0x05, 0x1B, 0x19),
                    K1GPacket.tlv(0x05, 0x21, 0x32),
                    K1GPacket.tlv(0x05, 0x4D, 0x32),
                ),
                seq = 0x09,
                segCount = 10,
            ),
            BURST_PAUSE_MS,
        ),
    )

    /**
     * Entering navigation mode: the sequence that makes the dash open its decoder.
     *
     * The route card goes out FOUR times with projection off before anything else, then once
     * more with it on after `navStart`. That is not belt and braces — the dash's destination
     * watchdog tears the decoder down if the card stops arriving, and `navStart` (z2) may be
     * sent only once per session.
     */
    fun enterNavMode(destinationName: String): List<Step> = buildList {
        add(Step(DashCommand.NavContext, 40))
        add(Step(DashCommand.EmptyLists, 40))
        repeat(4) { i ->
            add(
                Step(
                    DashCommand.RouteCard(destinationName, projectionOn = false),
                    if (i < 1) 100 else 500,
                ),
            )
        }
        add(Step(DashCommand.ProjectionFrame, 60))
        add(Step(DashCommand.NavPlaceholder, 10))
        add(Step(DashCommand.NavStart, 40))
        add(Step(DashCommand.RouteCard(destinationName, projectionOn = true), 0))
    }

    private const val BURST_PAUSE_MS = 20L

    private fun capturedStep(seq: Int, tlv: Tlv) =
        DashCommand.CapturedBurstStep(listOf(tlv), seq = seq)
}
