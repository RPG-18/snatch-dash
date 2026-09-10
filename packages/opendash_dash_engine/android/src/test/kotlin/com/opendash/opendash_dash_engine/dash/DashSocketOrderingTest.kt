package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.protocol.DashCommands
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dash gets a rolling seq byte on every control packet, and the order it arrives in is
 * the order this side put it on the wire — not the order the numbers were handed out.
 *
 * That distinction is the bug this pins down. Numbering and transmission used to be two
 * statements, so a coroutine could take 5, lose its thread, and send after the one that took
 * 6, leaving `…5, 7, 6, 8…` on the wire. It is not a theoretical window: during a stream six
 * coroutines write to this socket from `Dispatchers.IO` — a 4 Hz projection heartbeat, three
 * 1 Hz keep-alives, the RX loop's acks and the joystick echoes — about eight packets a second
 * for the length of a ride.
 */
class DashSocketOrderingTest {

    /** The seq byte lives immediately after the "K1G " magic — see K1GPacket.patchSeq. */
    private fun seqByteOf(packet: ByteArray): Int {
        val magic = byteArrayOf(0x4B, 0x31, 0x47, 0x20)
        outer@ for (i in 0..packet.size - magic.size) {
            for (j in magic.indices) if (packet[i + j] != magic[j]) continue@outer
            return packet[i + 4].toInt() and 0xFF
        }
        error("no K1G magic in the packet — the test's assumption about the format is stale")
    }

    @Test
    fun `packets leave in the order they were numbered, under contention`() {
        val sequencer = TxSequencer()
        val threads = 10
        val perThread = 100

        // Recorded inside the sequencer's own critical section, which is what makes this a
        // test of the invariant rather than of the list: if numbering and transmission can be
        // split, two threads interleave here and the recorded order stops being monotonic.
        val wire = ArrayList<Int>(threads * perThread)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) {
                    sequencer.send(DashCommands.heartbeat()) { pkt -> wire.add(seqByteOf(pkt)) }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "senders did not finish")

        assertEquals(threads * perThread, wire.size, "every packet must reach the wire once")
        // The byte wraps at 256, so "in order" means each one is its predecessor plus one,
        // modulo 256 — over 1000 packets that wraps just under four times.
        wire.zipWithNext().forEachIndexed { i, (previous, current) ->
            assertEquals(
                (previous + 1) and 0xFF,
                current,
                "packet #${i + 1} left out of order: $previous then $current",
            )
        }
    }
}
