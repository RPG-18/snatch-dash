package com.opendash.opendash_dash_engine.dash

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The redirect that decides where a whole ride's video goes.
 *
 * This lived inside `DashSocket`, which binds :2000 and :2002 in its constructor, so the
 * only test behind it was a call counter on a fake that has no notion of an address. A
 * wrong field compared, a sender taken from the wrong datagram, or a missing "once" all
 * passed every gate the project has and would have surfaced as a blank dash in the field.
 */
class DashPeerLatchTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun `a dash answering from where we expected is confirmed, not moved`() {
        val latch = DashPeerLatch(ip("192.168.1.1"), derived = true)
        latch.lastSender = ip("192.168.1.1")

        val outcome = assertIs<DashPeerLatch.Outcome.Confirmed>(latch.adopt())
        assertEquals(ip("192.168.1.1"), latch.dash)
        assertTrue("as derived from the link" in outcome.note, outcome.note)
    }

    @Test
    fun `confirmation does not claim the address was derived when it was the constant`() {
        // The whole point of `DashAddresses.source` is that a derived 192.168.1.1 and the
        // fallback one produce identical traffic. A confirmation line saying "as derived"
        // over the constant re-erases that distinction for the one reader — a release
        // post-mortem — who has nothing else to cross-check against.
        val latch = DashPeerLatch(ip("192.168.1.1"), derived = false)
        latch.lastSender = ip("192.168.1.1")

        val outcome = assertIs<DashPeerLatch.Outcome.Confirmed>(latch.adopt())
        assertTrue("fallback constant" in outcome.note, outcome.note)
        assertTrue("derived" !in outcome.note, "must not claim a provenance it lacks: ${outcome.note}")
    }

    @Test
    fun `a dash answering from elsewhere moves RTP and says both addresses`() {
        val latch = DashPeerLatch(ip("192.168.1.1"), derived = true)
        latch.lastSender = ip("10.42.0.1")

        val outcome = assertIs<DashPeerLatch.Outcome.Moved>(latch.adopt())
        assertEquals(ip("10.42.0.1"), latch.dash, "RTP must follow the handshake")
        assertEquals(ip("10.42.0.1"), outcome.peer)
        // Both, because the line's whole job is to show that the link and the dash
        // disagreed — either address alone says nothing about that.
        assertTrue("10.42.0.1" in outcome.note && "192.168.1.1" in outcome.note, outcome.note)
    }

    @Test
    fun `only the first adoption counts`() {
        // `07 01 01` is a plaintext status byte and DashAuth raises Confirmed for every
        // one of them. Without this latch a retransmit — or three bytes from anything
        // else on the AP — re-points the whole stream mid-ride and appends a WARN from
        // inside the RX loop, per datagram.
        val latch = DashPeerLatch(ip("192.168.1.1"), derived = true)
        latch.lastSender = ip("10.42.0.1")
        assertIs<DashPeerLatch.Outcome.Moved>(latch.adopt())

        latch.lastSender = ip("10.42.0.99")
        assertIs<DashPeerLatch.Outcome.Ignored>(latch.adopt())
        assertEquals(ip("10.42.0.1"), latch.dash, "a second handshake must not steal the stream")
    }

    @Test
    fun `nothing is adopted before a datagram has been seen`() {
        val latch = DashPeerLatch(ip("192.168.1.1"), derived = true)
        assertIs<DashPeerLatch.Outcome.Ignored>(latch.adopt())
        assertEquals(ip("192.168.1.1"), latch.dash)

        // And the latch is NOT spent by that: an adopt with no sender must leave the one
        // real chance intact, or a stray early call would disarm the whole mechanism.
        latch.lastSender = ip("10.42.0.1")
        assertIs<DashPeerLatch.Outcome.Moved>(latch.adopt())
        assertEquals(ip("10.42.0.1"), latch.dash)
    }
}
