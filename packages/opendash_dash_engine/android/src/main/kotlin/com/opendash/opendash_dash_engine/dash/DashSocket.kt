package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.protocol.K1GPacket
import com.opendash.opendash_dash_engine.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * UDP sockets for the Tripper Dash, matching better-dash exactly:
 *   TX  – bound to :2000, SO_BROADCAST, sends to 192.168.1.255:2000.
 *         The bike IP is never used for the control plane.
 *   RX  – bound to :2002. Must be open BEFORE the first TX packet, both to
 *         catch the early pubkey reply and because unanswered dash→phone
 *         packets generate ICMP port-unreachable, which confuses the dash's
 *         protocol state machine.
 *   RTP – ephemeral, sends H.264 to 192.168.1.1:5000.
 *
 * Every control packet gets the rolling K1G seq byte patched on send.
 */
class DashSocket(private val network: android.net.Network? = null) : AutoCloseable {
    companion object {
        const val DASH_IP    = "192.168.1.1"
        const val BROADCAST  = "192.168.1.255"
        const val CTRL_PORT  = 2000
        const val RX_PORT    = 2002
        const val RTP_PORT   = 5000
        private const val BUF             = 65535
        private const val RECV_TIMEOUT_MS = 500
        private const val TAG             = "DashSocket"
    }

    private val broadcastAddr: InetAddress = InetAddress.getByName(BROADCAST)
    private val dashAddr:      InetAddress = InetAddress.getByName(DASH_IP)
    private val txSocket:  DatagramSocket
    private val rxSocket:  DatagramSocket
    private val rtpSocket: DatagramSocket

    private val sequencer = TxSequencer()

    init {
        var tx:  DatagramSocket? = null
        var rx:  DatagramSocket? = null
        var rtp: DatagramSocket? = null
        try {
            tx = DatagramSocket(null).also {
                it.reuseAddress = true
                it.broadcast = true
                it.bind(InetSocketAddress(CTRL_PORT))
                network?.bindSocket(it)
            }
            rx = DatagramSocket(null).also {
                it.reuseAddress = true
                it.soTimeout = RECV_TIMEOUT_MS
                it.bind(InetSocketAddress(RX_PORT))
                network?.bindSocket(it)
            }
            rtp = DatagramSocket().also { network?.bindSocket(it) }
            DebugLog.i(TAG) { "Sockets open — TX :$CTRL_PORT→$BROADCAST:$CTRL_PORT (broadcast), RX :$RX_PORT, RTP→$DASH_IP:$RTP_PORT" }
            txSocket  = tx
            rxSocket  = rx
            rtpSocket = rtp
        } catch (e: Exception) {
            tx?.close(); rx?.close(); rtp?.close()
            throw e
        }
    }

    /** Send a K1G control packet (seq patched here, like K1GTx in the reference). */
    fun send(data: ByteArray) = sequencer.send(data) { pkt ->
        // Inside the sequencer's lock, and the log line with it: this file's contract is that
        // a TX line can be diffed against a capture byte for byte, and lines written outside
        // the lock arrive in whatever order the threads take it, describing packets that went
        // out in another. In a release build DebugLog compiles the whole thing away; in a
        // debug one it is a hex dump of a few dozen bytes, eight times a second.
        DebugLog.d(TAG) { "TX →$BROADCAST:$CTRL_PORT  ${pkt.size}B  ${pkt.hexFull()}" }
        // UDP fire-and-forget: a dropped/unreachable link (ENETUNREACH, EBADF) must never
        // crash the app — the session will fail and reconnect.
        try {
            txSocket.send(DatagramPacket(pkt, pkt.size, broadcastAddr, CTRL_PORT))
        } catch (e: Exception) {
            DebugLog.w(TAG) { "TX send failed (link down?): ${e.message}" }
        }
    }

    fun sendRtp(data: ByteArray) {
        try {
            rtpSocket.send(DatagramPacket(data, data.size, dashAddr, RTP_PORT))
        } catch (e: Exception) {
            DebugLog.d(TAG) { "RTP send failed (link down?): ${e.message}" }
        }
    }

    /**
     * Blocks up to RECV_TIMEOUT_MS; returns null on timeout. Throws on a genuine socket
     * error (closed/unreachable) — the caller's receive loop catches it and ends the
     * session instead of letting the exception crash the whole app.
     */
    suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        val buf = DatagramPacket(ByteArray(BUF), BUF)
        return@withContext try {
            rxSocket.receive(buf)
            val bytes = buf.data.copyOf(buf.length)
            DebugLog.d(TAG) { "RX ←${buf.address?.hostAddress}:${buf.port}  ${bytes.size}B  ${bytes.hexFull()}" }
            bytes
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        DebugLog.d(TAG) { "Sockets closed" }
        runCatching { txSocket.close() }
        runCatching { rxSocket.close() }
        runCatching { rtpSocket.close() }
    }
}

/**
 * Stamps the rolling K1G seq byte and hands the packet to [transmit] — as one operation, so
 * the wire order matches the seq order.
 *
 * An atomic counter was never the question: it hands out 5 and 6 exactly once each. What it
 * does not do is keep the two steps together — a coroutine can take 5, be descheduled, and
 * send after the one that took 6, putting `…5, 7, 6, 8…` on the wire. The platform does not
 * save us either: `DatagramSocket.send` synchronises on the packet and the channel adaptor on
 * the channel, which stops the bytes interleaving but leaves the ORDER to whoever wins the
 * lock.
 *
 * Reachable rather than theoretical: during a stream six independent coroutines write to this
 * socket from `Dispatchers.IO` — a 4 Hz projection heartbeat, three 1 Hz keep-alives, the RX
 * loop's acks and joystick echoes — roughly eight packets a second, some five thousand over a
 * ten-minute ride. Whether the dash validates the rolling byte we do not know; the reference
 * implementation maintains it carefully, which is reason enough not to hand it a sequence with
 * holes in the middle. The cost is a microsecond-scale critical section around a syscall.
 *
 * Separate from [DashSocket] so the invariant can be tested: that class binds :2000 and :2002
 * in its constructor, which a JVM unit test cannot do, while ten threads can drive this in
 * milliseconds. See `DashSocketOrderingTest`.
 *
 * **The lock is held across the send, and that has a price worth naming.**
 * `DashSession.disconnect()` reaches [DashSocket.send] from the main thread through
 * `runBlocking`, so a sender parked inside `sendto()` stalls Main for as long as it is parked.
 * Accepted deliberately: the socket is connectionless and addressed to a link-local broadcast,
 * so `sendto` returns as soon as the datagram is queued — it waits on a full socket buffer, not
 * on the network — and these are 14-to-60-byte control packets at eight a second. Releasing the
 * lock before the send would buy that latency back by giving up the ordering this class exists
 * for, which is the wrong trade in a protocol whose sequence byte the dash may well check.
 */
internal class TxSequencer {
    private val lock = Any()
    private var next = 0

    /**
     * The whole point is that these two lines cannot be pulled apart.
     *
     * [transmit] runs while the lock is held, so the packet that got number N is on its way
     * before number N+1 is even handed out. Doing the numbering here and the sending outside
     * — which is what this code used to do — lets a coroutine take 5, lose its thread, and
     * transmit after the one that took 6.
     */
    fun send(data: ByteArray, transmit: (ByteArray) -> Unit) {
        synchronized(lock) {
            transmit(K1GPacket.patchSeq(data, next++))
        }
    }
}

/** Full hex dump, no truncation — TX/RX must match so a capture can be diffed byte-for-byte. */
private fun ByteArray.hexFull(): String =
    joinToString(" ") { "%02X".format(it) }
