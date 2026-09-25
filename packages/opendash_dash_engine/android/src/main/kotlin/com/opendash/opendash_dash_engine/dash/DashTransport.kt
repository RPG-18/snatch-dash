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
}
