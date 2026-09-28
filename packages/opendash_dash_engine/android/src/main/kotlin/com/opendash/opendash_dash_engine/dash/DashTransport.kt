package com.opendash.opendash_dash_engine.dash

/**
 * The wire, as a session needs to see it.
 *
 * Exists so [DashSession] can be driven by a test. Everything the session does that matters —
 * the burst and its pauses, the handshake, the tick divisors, the farewell — is a statement
 * about *what goes out and when*, and until this interface there was no way to assert any of
 * it: [DashSocket] binds :2000 and :2002 in its constructor, which a JVM unit test cannot do.
 *
 * Deliberately three methods and no more. A session does not configure the link, does not
 * reopen it, and does not ask it anything — it writes control packets, writes RTP, reads
 * datagrams, and closes. Anything else belongs to whoever created the transport.
 */
internal interface DashTransport : AutoCloseable {

    /** One K1G control packet. The rolling seq byte is stamped by the implementation. */
    fun send(data: ByteArray)

    /** One RTP datagram. Separate socket, separate order, never sequenced. */
    fun sendRtp(data: ByteArray)

    /**
     * The next datagram from the dash. Blocks until one arrives or the transport fails.
     *
     * No timeout and no null: a session that wants to stop [close]s the transport, and the
     * exception that raises here is the normal way this loop ends. The 500 ms `soTimeout`
     * this replaces existed only so a cancelled loop would eventually notice — structured
     * concurrency does that properly, and the polled timeout cost two wakeups a second and
     * made "nothing arrived" and "the link is gone" the same return value.
     */
    suspend fun receive(): ByteArray

    /**
     * Take the sender of the datagram [receive] returned last as the dash's own address,
     * and send RTP there from now on.
     *
     * **Must be called from the receive loop itself, while still handling the datagram
     * whose sender is meant** — in practice from the `07 01 01` branch of the session's
     * dispatch. The first version called it from the coroutine waiting on the auth
     * signal instead, which is a different coroutine on the same dispatcher: the RX loop
     * re-enters [receive] as soon as it completes the signal, so a datagram arriving in
     * that window would have been the one adopted. Review caught it, 2026-09-28.
     *
     * **This is not peer authentication, and the protocol offers none.** `07 01 01` is a
     * plaintext status byte, and the dash's RSA key arrives in the clear from whoever
     * sends it, so anything already joined to the dash's AP can complete a handshake and
     * be adopted here. The exposure that adds is one thing: the map video would go to
     * that device instead of the dash, and the rider sees a blank dash immediately.
     * Joining the AP needs the dash's WPA2 password, which is per-dash and already in
     * the app, so an attacker who has it can equally well just pair their own phone. The
     * trade is accepted deliberately — before this, an unreachable derived address sent
     * RTP nowhere, silently, which is the failure we actually saw.
     *
     * Implementations with no notion of a peer (tests) may do nothing.
     */
    fun adoptSenderAsDash()
}
