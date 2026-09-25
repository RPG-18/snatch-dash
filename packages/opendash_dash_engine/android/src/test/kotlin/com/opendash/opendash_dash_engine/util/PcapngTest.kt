package com.opendash.opendash_dash_engine.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bytes of a ride capture, checked without a phone.
 *
 * What these guard is narrow and worth naming: a pcapng whose lengths are one off still
 * WRITES, and the failure surfaces as Wireshark opening the file to a single "malformed"
 * row — which reads like a protocol fault and is not one. A capture nobody can open is worse
 * than no capture, because the ride it belonged to is already over.
 */
class PcapngTest {

    /** Little-endian u32 at [at]. */
    private fun ByteArray.u32(at: Int): Long =
        (this[at].toLong() and 0xFF) or
            ((this[at + 1].toLong() and 0xFF) shl 8) or
            ((this[at + 2].toLong() and 0xFF) shl 16) or
            ((this[at + 3].toLong() and 0xFF) shl 24)

    private fun ByteArray.u16be(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun file(vararg payloads: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(Pcapng.sectionHeader())
        out.write(Pcapng.interfaceDescription())
        payloads.forEachIndexed { i, p ->
            out.write(
                Pcapng.enhancedPacket(
                    Pcapng.ipv4Udp(p, "192.168.1.2", 2000, "192.168.1.255", 2000, i + 1),
                    1_700_000_000_000_000L + i,
                ),
            )
        }
        return out.toByteArray()
    }

    @Test
    fun `every block declares its own length twice and the chain ends exactly at EOF`() {
        // The one property that makes a file openable at all: a reader walks it by these
        // numbers and nothing else. An odd payload is in the list on purpose — a block body
        // that is not a multiple of four has to be padded, and forgetting the padding shifts
        // every block after it.
        val bytes = file(ByteArray(1) { 0x55 }, ByteArray(22) { it.toByte() }, ByteArray(0))

        var at = 0
        var blocks = 0
        while (at < bytes.size) {
            assertTrue(at + 12 <= bytes.size, "block $blocks starts past the end")
            val total = bytes.u32(at + 4).toInt()
            assertTrue(total >= 12 && total % 4 == 0, "block $blocks length $total")
            assertTrue(at + total <= bytes.size, "block $blocks runs past the end")
            assertEquals(
                total.toLong(),
                bytes.u32(at + total - 4),
                "block $blocks: trailing length disagrees with the leading one",
            )
            at += total
            blocks++
        }
        assertEquals(bytes.size, at)
        assertEquals(5, blocks, "section header, interface description, three packets")
    }

    @Test
    fun `the section header says little-endian version 1_0 and an unknown length`() {
        val b = Pcapng.sectionHeader()
        assertEquals(0x0A0D0D0AL, b.u32(0))
        assertEquals(0x1A2B3C4DL, b.u32(8), "byte-order magic — this is what says the file is LE")
        assertEquals(1, ((b[13].toInt() and 0xFF) shl 8) or (b[12].toInt() and 0xFF))
        assertEquals(0, ((b[15].toInt() and 0xFF) shl 8) or (b[14].toInt() and 0xFF))
        // -1 as two u32s: the section length is not known while it is still being written.
        assertEquals(0xFFFFFFFFL, b.u32(16))
        assertEquals(0xFFFFFFFFL, b.u32(20))
    }

    @Test
    fun `the interface is LINKTYPE_IPV4, which is what makes the synthesised headers legible`() {
        val b = Pcapng.interfaceDescription()
        assertEquals(0x00000001L, b.u32(0))
        // 228. Say it as a number here rather than reading a constant: this test exists to
        // notice the value changing, and a constant on both sides notices nothing.
        assertEquals(228, ((b[9].toInt() and 0xFF) shl 8) or (b[8].toInt() and 0xFF))
    }

    @Test
    fun `a packet block carries the whole IP datagram, its timestamp, and nothing rounded`() {
        val payload = ByteArray(22) { (it + 1).toByte() }
        val ip = Pcapng.ipv4Udp(payload, "192.168.1.1", 2002, "192.168.1.2", 2002, 7)
        val ts = 1_700_000_000_123_456L
        val b = Pcapng.enhancedPacket(ip, ts)

        assertEquals(0x00000006L, b.u32(0))
        assertEquals(0L, b.u32(8), "interface id")
        assertEquals(ts, (b.u32(12) shl 32) or b.u32(16), "timestamp, split high/low")
        // Captured and original are the IP datagram, not the UDP payload: a reader that
        // trusted the payload length would cut every packet twenty-eight bytes short.
        assertEquals(ip.size.toLong(), b.u32(20))
        assertEquals(ip.size.toLong(), b.u32(24))
        assertContentEquals(ip, b.copyOfRange(28, 28 + ip.size))
    }

    @Test
    fun `the IPv4 header checksum is real, so Wireshark does not paint the ride red`() {
        // Verified the way a receiver verifies it: summed over the header WITH the checksum
        // in place, the ones' complement is zero. A test that recomputed it the way the
        // encoder does would agree with the encoder about being wrong.
        for (size in listOf(0, 1, 2, 3, 64, 269, 1400)) {
            val ip = Pcapng.ipv4Udp(ByteArray(size) { 0x5A }, "10.1.2.3", 1, "255.255.255.255", 65535, size)
            var sum = 0
            var i = 0
            while (i < 20) {
                sum += ((ip[i].toInt() and 0xFF) shl 8) or (ip[i + 1].toInt() and 0xFF)
                i += 2
            }
            while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            assertEquals(0xFFFF, sum, "header checksum for a ${size}B payload")
        }
    }

    @Test
    fun `ports, lengths and payload survive the synthesised headers unchanged`() {
        val payload = "K1G ".toByteArray() + ByteArray(13) { it.toByte() }
        val ip = Pcapng.ipv4Udp(payload, "192.168.1.1", 2002, "192.168.1.2", 49152, 1)

        assertEquals(0x45, ip[0].toInt() and 0xFF, "IPv4, IHL 5 — a reader slices on this")
        assertEquals(17, ip[9].toInt() and 0xFF, "protocol UDP")
        assertEquals(20 + 8 + payload.size, ip.u16be(2), "IP total length")
        assertContentEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 1), ip.copyOfRange(12, 16))
        assertContentEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 2), ip.copyOfRange(16, 20))

        assertEquals(2002, ip.u16be(20), "source port — the dash's :2002")
        assertEquals(49152, ip.u16be(22), "destination port")
        assertEquals(8 + payload.size, ip.u16be(24), "UDP length")
        assertEquals(0, ip.u16be(26), "UDP checksum deliberately zero — see ipv4Udp")
        assertContentEquals(payload, ip.copyOfRange(28, ip.size))
    }

    @Test
    fun `an empty datagram is still a packet`() {
        // The dash has never sent one, but a zero-length UDP payload is legal and a capture
        // that produced a malformed block for it would lose the rest of the ride with it.
        val ip = Pcapng.ipv4Udp(ByteArray(0), "192.168.1.1", 2002, "192.168.1.2", 2002, 1)
        assertEquals(28, ip.size)
        assertEquals(8, ip.u16be(24))
        val b = Pcapng.enhancedPacket(ip, 1L)
        assertEquals(b.size.toLong(), b.u32(4))
        assertEquals(b.size.toLong(), b.u32(b.size - 4))
    }
}
