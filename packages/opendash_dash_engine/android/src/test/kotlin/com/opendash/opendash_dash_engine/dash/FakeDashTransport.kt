package com.opendash.opendash_dash_engine.dash

import java.io.IOException
import java.util.Collections
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * The wire, under the test's control.
 *
 * Records what went out and WHEN — the timestamp is the point. Half of what
 * network-refactoring.md calls invariant 4 and 5 is about pauses (20 ms between burst
 * packets, 40/100/500/60/10 ms through nav entry, 250 ms between projection frames), and
 * before this a test could only check that the right bytes existed somewhere in the list.
 *
 * Note what it does NOT do: stamp the rolling K1G seq byte. That lives in [DashSocket]'s
 * [TxSequencer] and is covered by `DashSocketOrderingTest`, so the bytes recorded here are
 * exactly what [com.opendash.opendash_dash_engine.dash.protocol.K1GCodec] produced and can be
 * compared with it directly.
 *
 * **[receive] parks the way a socket parks: cancellation does not wake it, only [close]
 * does.** That is `suspendCoroutine` and not a `Channel`, and the difference is not a detail.
 * A Channel-backed fake resumes on cancellation, which made a deadlock invisible in review
 * (2026-09-19): the session closed its transport in `job.invokeOnCompletion`, i.e. only once
 * every child had finished — including the receive loop that nothing but the close could
 * release. On real sockets that is a hung `close()`, two ports left bound for good, and an
 * ANR from `dispose()`. A fake that is easier to cancel than the thing it stands for tests
 * the fake.
 */
internal class FakeDashTransport(private val clock: () -> Long) : DashTransport {

    data class Sent(val bytes: ByteArray, val atMs: Long) {
        // Data class over a ByteArray: equals/hashCode would compare identity. Nothing here
        // compares Sent values — the tests read .bytes — but leaving the generated versions
        // in place would be a trap for whoever does.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    private val outbound = Collections.synchronizedList(mutableListOf<Sent>())
    private val rtpOut = Collections.synchronizedList(mutableListOf<ByteArray>())
    /** Datagrams delivered while nothing was parked in [receive]. */
    private val pending = ArrayDeque<ByteArray>()
    private var waiter: Continuation<ByteArray>? = null
    private val lock = Any()

    @Volatile var closed = false
        private set

    /** Everything written to the control socket, oldest first. */
    val sent: List<Sent> get() = synchronized(outbound) { outbound.toList() }

    /** Everything written to the RTP socket. */
    val rtp: List<ByteArray> get() = synchronized(rtpOut) { rtpOut.toList() }

    override fun send(data: ByteArray) {
        // A real socket does not refuse writes after close, it throws — but a closed
        // DatagramSocket cannot put bytes on the wire either way, and "did anything leave
        // AFTER we closed" is the question three of these tests ask.
        if (!closed) outbound.add(Sent(data, clock()))
    }

    override fun sendRtp(data: ByteArray) {
        if (!closed) rtpOut.add(data)
    }

    override suspend fun receive(): ByteArray = suspendCoroutine { cont ->
        synchronized(lock) {
            val next = pending.removeFirstOrNull()
            when {
                next != null -> cont.resume(next)
                closed -> cont.resumeWithException(IOException("transport closed"))
                // Deliberately NOT suspendCancellableCoroutine: see the class doc.
                else -> waiter = cont
            }
        }
    }

    override fun close() {
        val parked = synchronized(lock) {
            closed = true
            waiter.also { waiter = null }
        }
        // With a cause, so a coroutine parked in [receive] gets the same kind of failure a
        // closed DatagramSocket gives it, rather than a silent end-of-stream.
        parked?.resumeWithException(IOException("transport closed"))
    }

    /** Forget everything sent so far, so an assertion can talk about one window. */
    fun clearSent() {
        outbound.clear()
    }

    /** Hand the session one datagram, as if the dash had sent it. */
    fun deliver(datagram: ByteArray) {
        val parked = synchronized(lock) {
            if (closed) return
            waiter?.also { waiter = null } ?: run { pending.addLast(datagram); null }
        }
        parked?.resume(datagram)
    }
}
