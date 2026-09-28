package com.opendash.opendash_dash_engine.dash

import java.net.InetAddress

/**
 * Where RTP goes, and the one rule for changing it.
 *
 * Split out of [DashSocket] for the same reason `TxSequencer` was: the class that owns
 * the sockets binds :2000 and :2002 in its constructor, so no JVM test can build one, and
 * the whole substance of task 13 — "the address the dash answered from replaces the one
 * the link suggested" — would have shipped with nothing but a call counter on a fake
 * behind it. A wrong field compared, a sender captured from the wrong datagram or a
 * missing latch all pass `flutter test`, `testDebugUnitTest` and `lintDebug`, and surface
 * as a blank dash in the field.
 *
 * Not thread-safe by itself; [DashSocket] holds it behind volatile reads and a single
 * writer (the RX loop). The state it protects is one reference.
 */
internal class DashPeerLatch(initial: InetAddress, private val derived: Boolean) {

    /** What [outcome] decided, and what the ride file should say about it. */
    sealed interface Outcome {
        /** The peer is where we were already sending. [note] is the ride-file line. */
        data class Confirmed(val note: String) : Outcome

        /** The peer is somewhere else; RTP moves. [note] is a WARN line. */
        data class Moved(val peer: InetAddress, val note: String) : Outcome

        /** Nothing to decide — no sender seen, or the latch is already closed. */
        object Ignored : Outcome
    }

    /** Current RTP destination. */
    var dash: InetAddress = initial
        private set

    /** Set for every datagram; read only when [adopt] runs, in the same loop. */
    var lastSender: InetAddress? = null

    private var adopted = false

    /**
     * Take [lastSender] as the dash, once.
     *
     * **Once is the point.** `07 01 01` is a plaintext status byte that `DashAuth` turns
     * into `Confirmed` every time it arrives, so without this latch any later one — a
     * retransmit, or a peer on the AP sending three bytes — would re-point the whole
     * stream mid-ride and append a WARN per datagram from inside the RX loop.
     */
    fun adopt(): Outcome {
        if (adopted) return Outcome.Ignored
        val peer = lastSender ?: return Outcome.Ignored
        adopted = true
        val how = if (derived) "as derived from the link" else "matching the fallback constant"
        if (peer == dash) return Outcome.Confirmed("dash confirmed at ${peer.hostAddress} ($how)")
        val was = dash.hostAddress
        dash = peer
        return Outcome.Moved(
            peer,
            "dash answered from ${peer.hostAddress}, not $was — RTP follows the handshake",
        )
    }
}
