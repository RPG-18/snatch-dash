package com.opendash.opendash_dash_engine.dash.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The two fixed conversations, pinned as sequences.
 *
 * Bytes are pinned elsewhere — `DashCommandsGoldenTest` compares every command against its
 * capture, and `K1GCodecTest` against the dash's own datagrams. What only this file can
 * cover is the other half of invariant 5: the ORDER of the packets and the PAUSES between
 * them. Before stage 3 that half lived as eight statements inside a `suspend fun`, where
 * nothing could assert it; the assertions below are the reason it moved into data.
 *
 * Two of them cost a ride each to learn and are worth stating plainly:
 *   • `navStart` (z2) goes out exactly ONCE per session — a second one and the dash drops
 *     back to its own screen;
 *   • the route card goes out four times BEFORE anything else, because the dash's
 *     destination watchdog refuses to open the decoder without it.
 */
class ScriptsTest {

    private val timeSync = DashCommand.TimeSync(hour = 1, minute = 2, second = 3)

    // ── Initial burst ─────────────────────────────────────────────────────

    @Test
    fun `the burst is nine steps, 20 ms apart`() {
        val steps = Scripts.initialBurst("OpenDash", timeSync)

        assertEquals(9, steps.size)
        assertEquals(List(9) { 20L }, steps.map { it.pauseAfterMs }, "invariant 4")
    }

    @Test
    fun `the burst opens with auth request, hostname and the clock`() {
        val steps = Scripts.initialBurst("OpenDash", timeSync)

        assertEquals(DashCommand.AuthRequest, steps[0].cmd)
        assertEquals(DashCommand.HostnameAnnounce("OpenDash"), steps[1].cmd)
        assertEquals(timeSync, steps[2].cmd)
        // The remaining six are captures nobody has explained; they are replayed as-is,
        // seq bytes included (TxSequencer overwrites those at send time — invariant 2).
        assertEquals(
            listOf(0x03, 0x04, 0x05, 0x06, 0x08, 0x09),
            steps.drop(3).map { assertIs<DashCommand.CapturedBurstStep>(it.cmd).seq },
        )
    }

    @Test
    fun `the burst encodes to the bytes the golden test pins`() {
        val bytes = Scripts.initialBurst("OpenDash", timeSync).map { K1GCodec.encode(it.cmd).toHex() }

        assertEquals(DashCommands.authRequest().toHex(), bytes[0])
        assertEquals(DashCommands.hostnameAnnounce("OpenDash").toHex(), bytes[1])
        assertEquals(
            listOf(
                "0016000200000000020100054b314720030557000155",
                "0016000200000000020100054b3147200405560001aa",
                "0016000200000000020100054b3147200506050001aa",
                "0016000200000000020100054b3147200605170001aa",
                "001d000200000000020100054b314720080a020008aa55000000000000",
                "0044000a00000000020100054b3147200906080001ff060300015506040001a2060f0001aa" +
                    "0601000101054c000113052d00020000051b0001190521000132054d000132",
            ),
            bytes.drop(3),
        )
    }

    // ── Nav entry ─────────────────────────────────────────────────────────

    @Test
    fun `nav entry is ten steps in the captured order`() {
        val steps = Scripts.enterNavMode("Home")

        assertEquals(
            listOf(
                DashCommand.NavContext,
                DashCommand.EmptyLists,
                DashCommand.RouteCard("Home", projectionOn = false),
                DashCommand.RouteCard("Home", projectionOn = false),
                DashCommand.RouteCard("Home", projectionOn = false),
                DashCommand.RouteCard("Home", projectionOn = false),
                DashCommand.ProjectionFrame,
                DashCommand.NavPlaceholder,
                DashCommand.NavStart,
                DashCommand.RouteCard("Home", projectionOn = true),
            ),
            steps.map { it.cmd },
        )
    }

    @Test
    fun `nav entry keeps the pauses read off the capture`() {
        assertEquals(
            listOf(40L, 40L, 100L, 500L, 500L, 500L, 60L, 10L, 40L, 0L),
            Scripts.enterNavMode("Home").map { it.pauseAfterMs },
        )
    }

    @Test
    fun `navStart is sent exactly once`() {
        assertEquals(1, Scripts.enterNavMode("Home").count { it.cmd == DashCommand.NavStart })
    }

    @Test
    fun `nav entry encodes to the bytes DashSession used to send statement by statement`() {
        val bytes = Scripts.enterNavMode("Home").map { K1GCodec.encode(it.cmd).toHex() }

        assertEquals(
            listOf(
                DashCommands.navContext().toHex(),
                DashCommands.emptyLists().toHex(),
                DashCommands.routeCard("Home", projectionOn = false).toHex(),
                DashCommands.routeCard("Home", projectionOn = false).toHex(),
                DashCommands.routeCard("Home", projectionOn = false).toHex(),
                DashCommands.routeCard("Home", projectionOn = false).toHex(),
                DashCommands.projectionFrame().toHex(),
                DashCommands.navPlaceholder().toHex(),
                DashCommands.navStart().toHex(),
                DashCommands.routeCard("Home", projectionOn = true).toHex(),
            ),
            bytes,
        )
    }

    @Test
    fun `the destination name reaches every card`() {
        val cards = Scripts.enterNavMode("Мурманск")
            .mapNotNull { it.cmd as? DashCommand.RouteCard }

        assertEquals(5, cards.size)
        assertEquals(List(5) { "Мурманск" }, cards.map { it.title })
    }
}
