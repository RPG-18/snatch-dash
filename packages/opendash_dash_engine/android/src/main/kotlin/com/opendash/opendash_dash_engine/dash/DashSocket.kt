package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.protocol.K1GPacket
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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
class DashSocket(private val network: android.net.Network? = null) : DashTransport {
    companion object {
        const val DASH_IP    = "192.168.1.1"
        const val BROADCAST  = "192.168.1.255"
        const val CTRL_PORT  = 2000
        const val RX_PORT    = 2002
        const val RTP_PORT   = 5000
        /**
         * The largest datagram this link is expected to carry, plus one.
         *
         * K1G control packets are hundreds of bytes; the dash has never sent anything near
         * a kilobyte. The buffer used to be 65535 and freshly allocated **per receive** —
         * twice a second on timeouts alone, plus one per packet, all of it garbage.
         *
         * The `+ 1` is not decoration. `DatagramSocket.receive`'s own contract is "if the
         * message is longer than the packet's length, the message is truncated" — silently,
         * with `length` capped at the buffer — so with a buffer of exactly [MAX_DATAGRAM] a
         * full-size read is indistinguishable from one that was cut, and a truncated K1G
         * packet parses into nonsense rather than being refused. One spare byte turns
         * "length > MAX_DATAGRAM" into the anomaly signal, which [receive] logs and skips.
         */
        private const val MAX_DATAGRAM    = 2048
        private const val RECV_BUF        = MAX_DATAGRAM + 1

        // DSCP is NOT set on any of the three sockets, and that is a reversal.
        //
        // 2026-09-10 evening this file marked the control plane EF (0xB8) and RTP AF41
        // (0x88). The ride that night made graphical artifacts MORE frequent than the
        // rides before it, and the reversal is on 2026-09-11. Nothing local explained the
        // regression: `drop=0 dropIdr=0` in every window, RSSI -36..-38 dBm, a 65 Mbps
        // link carrying 200 kbps, no send failures outside the one link loss.
        //
        // The mechanism that fits is the remapping the marking itself triggers. Wi-Fi does
        // not carry 64 DSCP values, it carries four WMM access categories: AF41 lands RTP
        // in AC_VI and EF lands control in AC_VO, where they used to share AC_BE. AC_VI's
        // contention window tops out at 15 slots against best effort's 1023, so under
        // collision it can barely back off, and drivers commonly give the real-time
        // categories a lower retry limit on the grounds that late video is worthless. For
        // this stream that trade is backwards: one datagram lost from a key frame costs a
        // whole GOP — eight frames, two seconds of mush — so we would rather have the
        // retries than the priority.
        //
        // Worth keeping in view if this is ever revisited: the ride log could only ever
        // show that the OS ACCEPTED the value. What the Wi-Fi driver did with it, and what
        // the dash saw, are not observable from this side — that needs a capture at the
        // dash. `setTrafficClass` is documented as a hint the implementation "may ignore",
        // `getTrafficClass` "may return a different value than was previously set", and
        // setting precedence bits (EF is `101110xx`) "may result in a SocketException".
        // The line below still reads the traffic class back, so a device that marks
        // packets on its own initiative would still show up.

        // NOT set, deliberately — the plan's task 2.2 asked for a 256 KiB SO_SNDBUF on the
        // RTP socket and that is the wrong direction here. Its premise was that the default
        // is "a few tens of KB". Per `man 7 socket` the default comes from
        // `/proc/sys/net/core/wmem_default` — 212992 B on a stock Linux, and Android does
        // not lower it — and "the kernel doubles this value ... when it is set", so both the
        // default and anything asked for are larger than they read. Either way the socket
        // already holds seconds of a 200 kbps stream; the line below prints what THIS device
        // actually gives, which is the number to argue from.
        // More buffer does not protect a realtime stream, it hides a stalled radio: `sendto`
        // stops blocking, `rtpOutbox` never overflows, `drop=` stays zero, and the dash shows
        // a map from ten seconds ago — the exact failure the capacity-4 outbox (task 2.5) was
        // sized to turn into an honest drop, moved one layer down where the telemetry cannot
        // see it. The line below reports what the platform actually gives us; if a ride ever
        // shows sends blocking, that number is the evidence to change it with.
        private const val TAG             = "DashSocket"
    }

    private val broadcastAddr: InetAddress = InetAddress.getByName(BROADCAST)
    private val dashAddr:      InetAddress = InetAddress.getByName(DASH_IP)
    private val txSocket:  DatagramSocket
    private val rxSocket:  DatagramSocket
    private val rtpSocket: DatagramSocket

    private val sequencer = TxSequencer()

    /**
     * One receive buffer for the life of the socket, reused by every [receive].
     *
     * Safe because there is exactly one reader: [DashSession]'s RX loop, a single coroutine
     * that calls [receive] in sequence and copies out what it needs before asking again.
     * Nothing else in the engine touches this socket's inbound side.
     */
    private val rxBuffer = ByteArray(RECV_BUF)

    /** So an oversized-datagram firmware costs one ride-file line, not one per packet. */
    private var loggedOversized = false

    init {
        var tx:  DatagramSocket? = null
        var rx:  DatagramSocket? = null
        var rtp: DatagramSocket? = null
        try {
            // SO_REUSEADDR is NOT set on either fixed port, and that is deliberate.
            //
            // It used to be, on both, and what it bought was the ability to bind a port the
            // previous session still held — which is precisely the failure worth hearing
            // about. Two sockets bound to :2002 do not split the dash's traffic evenly or
            // predictably; the platform hands a datagram to one of them, and the half that
            // reaches a session nobody reads any more shows up as "the dash went quiet".
            // With one-shot sessions the previous socket is closed before the next one is
            // built (DashEngineController.openSession awaits the close), so a BindException
            // here now means a real leak, and it should stop the session rather than be
            // papered over.
            tx = DatagramSocket(null).also {
                it.broadcast = true
                it.bind(InetSocketAddress(CTRL_PORT))
                network?.bindSocket(it)
            }
            rx = DatagramSocket(null).also {
                // No soTimeout: [receive] blocks until a datagram or until [close]. See
                // DashTransport.receive for why the polled 500 ms timeout went away.
                it.bind(InetSocketAddress(RX_PORT))
                network?.bindSocket(it)
            }
            rtp = DatagramSocket().also { network?.bindSocket(it) }
            DebugLog.i(TAG) { "Sockets open — TX :$CTRL_PORT→$BROADCAST:$CTRL_PORT (broadcast), RX :$RX_PORT, RTP→$DASH_IP:$RTP_PORT" }
            txSocket  = tx
            rxSocket  = rx
            rtpSocket = rtp
            reportSocketOptions()
        } catch (e: Exception) {
            tx?.close(); rx?.close(); rtp?.close()
            throw e
        }
    }

    /**
     * Reports what the kernel gave the sockets. Sets nothing — see the block above the
     * constants for why the DSCP marking that used to live here was withdrawn.
     *
     * Kept as a report rather than deleted because both numbers earned their place. The
     * `sndbuf` reading is what overturned task 2.2 of the refactoring plan: this phone
     * hands out 4 MiB by default, so the 256 KiB the plan asked for would have SHRUNK it
     * sixteenfold. And the traffic class is worth watching even when we set nothing — a
     * value other than zero would mean the platform marks packets on its own, which is the
     * one way the withdrawal above could fail to take effect.
     */
    private fun reportSocketOptions() {
        // Read back and printed as "n/a" when the read itself fails: a refused option and
        // an option reading zero are different facts, and a log that showed `0xFFFFFFFF`
        // for the first would just look broken.
        fun tos(socket: DatagramSocket) =
            runCatching { "0x%02X".format(socket.trafficClass) }.getOrDefault("n/a")
        val sndBuf = runCatching { "${rtpSocket.sendBufferSize / 1024}KiB" }.getOrDefault("n/a")
        RideDiagnostics.log(
            "stream",
            "sockets: tos ctrl=${tos(txSocket)} rtp=${tos(rtpSocket)} (neither set) " +
                "sndbuf=$sndBuf (platform default, not set)",
        )
    }

    /** Send a K1G control packet (seq patched here, like K1GTx in the reference). */
    override fun send(data: ByteArray) = sequencer.send(data) { pkt ->
        // Inside the sequencer's lock, and the log line with it: this file's contract is that
        // a TX line can be diffed against a capture byte for byte, and lines written outside
        // the lock arrive in whatever order the threads take it, describing packets that went
        // out in another. In a release build DebugLog compiles the whole thing away; in a
        // debug one it is a hex dump of a few dozen bytes, eight times a second.
        DebugLog.d(TAG) { "TX →$BROADCAST:$CTRL_PORT  ${pkt.size}B  ${pkt.hexFull()}" }
        // UDP fire-and-forget: a dropped/unreachable link (ENETUNREACH, EBADF) must never
        // crash the app — the session will fail and reconnect.
        //
        // IOException, not Exception. The broad catch also swallowed every RuntimeException,
        // and one of those is NetworkOnMainThreadException — the platform's own alarm that
        // this call is on the wrong thread. Swallowed, it reads as "TX send failed (link
        // down?): null", which is what the 2026-09-05 log said while the real fault was a
        // send from Main (see DashSession.disconnect's runBlocking). Let it through: a
        // programming error should stop the caller, not disguise itself as a bad link.
        try {
            txSocket.send(DatagramPacket(pkt, pkt.size, broadcastAddr, CTRL_PORT))
        } catch (e: IOException) {
            DebugLog.w(TAG) { "TX send failed (link down?): ${e.message}" }
        }
    }

    override fun sendRtp(data: ByteArray) {
        // IOException only — see the note in [send] on why a broad catch hid a
        // NetworkOnMainThreadException as a link failure.
        try {
            rtpSocket.send(DatagramPacket(data, data.size, dashAddr, RTP_PORT))
        } catch (e: IOException) {
            DebugLog.d(TAG) { "RTP send failed (link down?): ${e.message}" }
        }
    }

    /**
     * Blocks until a datagram arrives, or throws when the socket is closed or the link dies.
     *
     * An oversized datagram loops back for the next one rather than returning: it is
     * discarded, but discarding is not the same as the link being silent, and only the
     * caller's watchdog gets to decide what silence means.
     */
    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        val buf = DatagramPacket(rxBuffer, rxBuffer.size)
        while (true) {
            buf.length = rxBuffer.size
            rxSocket.receive(buf)
            if (buf.length > MAX_DATAGRAM) {
                // Bigger than anything this protocol produces, and already truncated by the
                // buffer — the tail is gone. `K1GPacket.parseIncoming` would not crash on it
                // (it clamps every TLV to the array), it would simply read the cut-off bytes
                // as a shorter, wrong message. Better to have nothing than a plausible lie.
                //
                // Reported once per socket: a firmware that sends these will send them
                // steadily, and a warn per datagram is a synchronized append to external
                // storage on the RX loop's own thread.
                if (!loggedOversized) {
                    loggedOversized = true
                    RideDiagnostics.warn(
                        TAG,
                        "RX datagram over ${MAX_DATAGRAM}B from ${buf.address?.hostAddress} — " +
                            "truncated and dropped; further ones this session are silent",
                    )
                }
                continue
            }
            val bytes = buf.data.copyOf(buf.length)
            DebugLog.d(TAG) { "RX ←${buf.address?.hostAddress}:${buf.port}  ${bytes.size}B  ${bytes.hexFull()}" }
            return@withContext bytes
        }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
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
