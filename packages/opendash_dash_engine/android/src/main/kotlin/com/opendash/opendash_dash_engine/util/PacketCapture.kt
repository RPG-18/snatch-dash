package com.opendash.opendash_dash_engine.util

import com.opendash.opendash_dash_engine.BuildConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Every K1G datagram of a ride, as a pcapng file Wireshark can open.
 *
 * **Why a capture and not more log lines.** The hex dumps in [DebugLog] carry the same bytes,
 * and reading them is how the dash's 1 Hz `01 01` counter was found on 2026-09-24 — but
 * finding it took a Python one-liner over a 9 MB text file, and the thing it answered ("did
 * the dash stall, or did we lose its uplink?") is a question about timing between two
 * directions. That is what a packet list is for. With `tools/k1g.lua` loaded, the same ride
 * opens as named TLVs with deltas, filters and an IO graph.
 *
 * **Debug and profile only** — `BuildConfig.DEBUG`, like [DebugLog]. A release build writes
 * nothing, which is also why nothing here may become the only source of a number: everything
 * that has to survive a release still goes to [RideDiagnostics].
 *
 * **Control plane only.** TX on :2000 and RX on :2002; RTP on :5000 is deliberately NOT
 * captured. A ride of control traffic is a few megabytes, a ride of RTP is thirty, and the
 * write would land on `dash-rtp` inside the frame budget — instrumenting the frame path with
 * something that costs frame time is how a measurement changes what it measures. The
 * dissector still knows :5000, so a capture taken with a real sniffer dissects too.
 *
 * **Synthesised IPv4/UDP headers.** What we hold is a payload, not a frame, so the file gets
 * a 20-byte IPv4 and an 8-byte UDP header built around it. Ports and direction are exact;
 * the addresses are real where we know them (the dash's own source address, for RX) and
 * [LOCAL_IP] where we do not — the phone's address on the dash's network is not worth
 * plumbing three layers down for a field nothing reads. A header this file wrote is not
 * evidence about the wire; the payload is.
 */
internal object PacketCapture {
    private const val TAG = "PacketCapture"

    /**
     * Stand-in for the phone's address on the dash's network.
     *
     * Not looked up: the sockets bind the wildcard, so `localAddress` is 0.0.0.0, and the
     * real one would have to come from `LinkProperties` through two layers that have no
     * other use for it. Wireshark needs *an* address to render a conversation; this one is
     * consistent, obviously synthetic, and documented as such right here.
     */
    private const val LOCAL_IP = "192.168.1.2"

    /**
     * Hard ceiling per ride.
     *
     * The control plane runs about 25 datagrams a second, so a two-hour ride lands near
     * 30 MB and this never fires. It exists for the case that is not the control plane —
     * a loop that starts recording something it should not — because this writes to external
     * storage, where filling the card is the rider's problem and not the app's.
     */
    private const val MAX_BYTES = 64L * 1024 * 1024

    /** Blocks waiting to be written. Sized for a second of control traffic and then some. */
    private const val QUEUE_DEPTH = 256

    /**
     * **Nothing on a caller's thread touches a file.** Blocks are built and queued; one
     * daemon thread writes them.
     *
     * The first version wrote and flushed inline, and review took it apart on three counts
     * that are really one. `DashSocket.send` runs its lambda inside `TxSequencer`'s monitor,
     * whose KDoc justifies holding that monitor across the send precisely because `sendto`
     * never waits — a `write(2)` to FUSE-backed external storage does, so the 4 Hz keep-alive
     * could queue behind another thread's flush, and `dispose()`'s main-thread `runBlocking`
     * behind that. It also perturbed the `interval=`/`overrun=` numbers CLAUDE.md says to
     * read from a `--profile` ride: instrumenting the frame path with something that costs
     * frame time is how a measurement changes what it measures. And the ceiling warning went
     * through [RideDiagnostics.warn] while holding this object's lock, which is the other
     * half of an AB-BA inversion with [RideDiagnostics.start] — a deadlock that would have
     * stopped every control packet for the rest of the ride. Review, 2026-09-24.
     */
    private val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(QUEUE_DEPTH)

    private val lock = Any()
    @Volatile private var dir: File? = null
    @Volatile private var running = false
    private var writer: Thread? = null
    private var ipId = 0
    private val dropped = java.util.concurrent.atomic.AtomicInteger(0)

    /** Same directory [RideDiagnostics] uses. Safe to call repeatedly. */
    fun init(context: android.content.Context) {
        if (!BuildConfig.DEBUG || dir != null) return
        runCatching { File(context.getExternalFilesDir(null), "diag").apply { mkdirs() } }
            .onSuccess { dir = it }
            .onFailure { DebugLog.w(TAG) { "init failed: ${it.message}" } }
    }

    /**
     * Open `ride-<stamp>.pcapng` beside the ride file of the same stamp.
     *
     * The stamp is passed in rather than computed so the two files of one ride cannot end up
     * a second apart and sort differently — [RideDiagnostics.start] already made this
     * decision and this follows it.
     *
     * **Appended, not truncated**, and for the same reason: two `connect()`s inside one
     * second share a stamp, `RideDiagnostics` appends both into one `.log`, and a truncating
     * open here left a `.pcapng` holding only the second while the log held both. pcapng is
     * a sequence of sections, so a second section header simply starts a second section and
     * Wireshark reads the file whole.
     */
    fun start(stamp: String) {
        if (!BuildConfig.DEBUG) return
        val d = dir ?: return
        stop()
        queue.clear()
        dropped.set(0)
        synchronized(lock) { ipId = 0 }
        // Rotation BEFORE the open and outside its runCatching: it deletes other rides'
        // files, and a SecurityException from one of those used to abandon the capture that
        // had just opened fine — a whole ride lost because a previous ride's file could not
        // be deleted.
        runCatching { rotate(d) }
            .onFailure { DebugLog.w(TAG) { "rotate failed: ${it.message}" } }
        val file = File(d, "ride-$stamp.pcapng")
        val stream = runCatching { BufferedOutputStream(FileOutputStream(file, true)) }
            .onFailure { DebugLog.w(TAG) { "could not open the capture: ${it.message}" } }
            .getOrNull() ?: return
        running = true
        writer = Thread({ pump(stream) }, "dash-pcap").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        offer(Pcapng.sectionHeader())
        offer(Pcapng.interfaceDescription())
    }

    fun stop() {
        if (!BuildConfig.DEBUG) return
        val t = writer ?: return
        running = false
        writer = null
        t.interrupt()
        // Bounded: the writer only ever waits on the queue or on one write, and a capture
        // must never be the reason a disconnect takes longer than the rider's patience.
        runCatching { t.join(1_000) }
    }

    /** One datagram we sent. */
    fun tx(payload: ByteArray, srcPort: Int, dstIp: String, dstPort: Int) =
        record(payload, LOCAL_IP, srcPort, dstIp, dstPort)

    /** One datagram we received; [srcIp] is the sender the socket reported. */
    fun rx(payload: ByteArray, srcIp: String, srcPort: Int, dstPort: Int) =
        record(payload, srcIp, srcPort, LOCAL_IP, dstPort)

    private fun record(
        payload: ByteArray,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
    ) {
        if (!BuildConfig.DEBUG || !running) return
        val block = synchronized(lock) {
            // Clock and enqueue under the same lock, and that pairing is the point: sampled
            // outside it, a thread that then parked for a few milliseconds would write an
            // earlier timestamp after a later one, and Wireshark would render a negative
            // frame.time_delta on the one file whose whole purpose is timing between the two
            // directions.
            //
            // Wall clock, not monotonic: pcapng timestamps are seconds since the epoch by
            // definition, and the point of the file is to sit beside a ride log carrying the
            // same clock. An NTP step mid-ride shifts both together.
            val nowUs = System.currentTimeMillis() * 1000L
            runCatching {
                Pcapng.enhancedPacket(
                    Pcapng.ipv4Udp(payload, srcIp, srcPort, dstIp, dstPort, ++ipId),
                    nowUs,
                )
            }.getOrNull()
        } ?: return
        offer(block)
    }

    /** Never blocks a caller: a full queue drops the packet and says so once per ride. */
    private fun offer(block: ByteArray) {
        if (!queue.offer(block) && dropped.getAndIncrement() == 0) {
            DebugLog.w(TAG) { "capture queue full — packets are being dropped this ride" }
        }
    }

    private fun pump(stream: BufferedOutputStream) {
        var written = 0L
        var saidFull = false
        try {
            while (true) {
                val block = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                if (written >= MAX_BYTES) {
                    if (!saidFull) {
                        saidFull = true
                        // On the writer thread, holding no lock of ours — see [queue].
                        RideDiagnostics.warn(
                            TAG,
                            "capture hit ${MAX_BYTES / 1024 / 1024}MiB — no more packets this ride",
                        )
                    }
                    continue
                }
                stream.write(block)
                written += block.size
                // Flushed per block, which is affordable now that it is nobody's critical
                // section: the rides worth reading are the ones that ended in a kill, and a
                // buffered tail is exactly the part nearest the fault.
                stream.flush()
            }
        } catch (e: Exception) {
            DebugLog.w(TAG) { "capture write failed, stopping: ${e.message}" }
        } finally {
            // Whatever is already queued still belongs to this ride.
            runCatching {
                val rest = ArrayList<ByteArray>()
                queue.drainTo(rest)
                for (b in rest) if (written < MAX_BYTES) { stream.write(b); written += b.size }
                stream.flush()
            }
            runCatching { stream.close() }
            val lost = dropped.get()
            if (lost > 0) DebugLog.w(TAG) { "capture dropped $lost packets" }
        }
    }

    private fun rotate(d: File) {
        val caps = d.listFiles { x -> x.name.startsWith("ride-") && x.name.endsWith(".pcapng") }
            ?: return
        // Fewer than the ride logs keep: these are megabytes where those are kilobytes, and
        // the pair only has to line up for the rides anybody still wants to open.
        if (caps.size <= KEEP_FILES) return
        caps.sortedBy { it.lastModified() }.dropLast(KEEP_FILES).forEach { runCatching { it.delete() } }
    }

    private const val KEEP_FILES = 4
}

/**
 * The pcapng and IPv4/UDP encoding of [PacketCapture], with no file and no clock.
 *
 * Split out for the same reason `readableSsid` and `dashSsidCandidates` are split out of
 * `DashWifiManager`: what can be checked without a device should be. Every byte a capture
 * contains is decided here, and a length computed one off makes a file that Wireshark opens
 * to a single "malformed" row — a failure that looks like a protocol problem and is not.
 */
internal object Pcapng {
    // ── pcapng blocks ─────────────────────────────────────────────────────
    //
    // Little-endian throughout; the byte-order magic in the section header is what tells a
    // reader so. Only the three blocks a valid file needs — no options, because every option
    // here would have a default that is already what we want (timestamps in microseconds,
    // section length unknown).

    fun sectionHeader(): ByteArray = block(0x0A0D0D0A) { b ->
        b.u32(0x1A2B3C4D)       // byte-order magic
        b.u16(1); b.u16(0)      // version 1.0
        b.u32(0xFFFFFFFFu.toInt()); b.u32(0xFFFFFFFFu.toInt())  // section length: unknown
    }

    fun interfaceDescription(): ByteArray = block(0x00000001) { b ->
        // LINKTYPE_IPV4 (228): the payload of each packet is a bare IPv4 datagram, which is
        // exactly what [ipv4Udp] builds. LINKTYPE_ETHERNET would mean fourteen more invented
        // bytes per packet and no more information.
        b.u16(228)
        b.u16(0)                // reserved
        b.u32(0)                // snaplen 0 = no limit
    }

    fun enhancedPacket(data: ByteArray, tsUs: Long): ByteArray = block(0x00000006) { b ->
        b.u32(0)                                  // interface id
        b.u32((tsUs ushr 32).toInt())             // timestamp, high
        b.u32((tsUs and 0xFFFFFFFFL).toInt())     // timestamp, low
        b.u32(data.size)                          // captured
        b.u32(data.size)                          // original
        b.raw(data)
        b.pad4()
    }

    /** A pcapng block: type, total length, body, total length again. */
    private inline fun block(type: Int, body: (Buf) -> Unit): ByteArray {
        val b = Buf()
        body(b)
        val total = 12 + b.size
        val out = Buf()
        out.u32(type)
        out.u32(total)
        out.raw(b.toByteArray())
        out.u32(total)
        return out.toByteArray()
    }

    // ── synthesised IPv4 + UDP ────────────────────────────────────────────

    fun ipv4Udp(
        payload: ByteArray,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        ipId: Int,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val b = Buf()
        b.u8(0x45)                       // IPv4, IHL 5
        b.u8(0x00)                       // DSCP/ECN
        b.u16be(totalLen)
        b.u16be(ipId and 0xFFFF)
        b.u16be(0x4000)                  // don't fragment
        b.u8(64)                         // TTL
        b.u8(17)                         // UDP
        b.u16be(0)                       // checksum placeholder
        b.raw(ip(srcIp))
        b.raw(ip(dstIp))
        val header = b.toByteArray()
        // Computed, not zeroed: a bad IPv4 checksum makes Wireshark paint every packet of the
        // file as an error, and a reader who has to switch that warning off first is a reader
        // who stops trusting the capture.
        val ck = checksum(header)
        header[10] = ((ck shr 8) and 0xFF).toByte()
        header[11] = (ck and 0xFF).toByte()

        val u = Buf()
        u.raw(header)
        u.u16be(srcPort)
        u.u16be(dstPort)
        u.u16be(udpLen)
        // UDP checksum 0 — "not computed", which IPv4 explicitly permits and Wireshark reads
        // without complaint. Computing it would mean a pseudo-header over addresses this file
        // invented, i.e. a checksum over a fiction.
        u.u16be(0)
        u.raw(payload)
        return u.toByteArray()
    }

    private fun ip(dotted: String): ByteArray {
        val parts = dotted.split('.')
        return ByteArray(4) { i -> (parts.getOrNull(i)?.toIntOrNull() ?: 0).toByte() }
    }

    private fun checksum(header: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < header.size) {
            sum += ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    /** A growable little-endian byte buffer; big-endian helpers are named `be`. */
    private class Buf {
        private val b = java.io.ByteArrayOutputStream()
        val size: Int get() = b.size()
        fun u8(v: Int) { b.write(v and 0xFF) }
        fun u16(v: Int) { b.write(v and 0xFF); b.write((v shr 8) and 0xFF) }
        fun u32(v: Int) {
            b.write(v and 0xFF); b.write((v shr 8) and 0xFF)
            b.write((v shr 16) and 0xFF); b.write((v shr 24) and 0xFF)
        }
        fun u16be(v: Int) { b.write((v shr 8) and 0xFF); b.write(v and 0xFF) }
        fun raw(a: ByteArray) { b.write(a, 0, a.size) }
        fun pad4() { while (b.size() % 4 != 0) b.write(0) }
        fun toByteArray(): ByteArray = b.toByteArray()
    }
}
