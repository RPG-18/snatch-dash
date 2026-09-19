package com.opendash.opendash_dash_engine.dash.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the dash says, decoded — on bytes it really sent.
 *
 * Every datagram below is copied out of `app_log.txt` of the rides of 2026-09-18, not
 * invented: 11 MB of hex dumps from two phones were pulled to investigate the dash
 * restarting mid-ride (network-refactoring.md §0.1), and they double as the only honest
 * corpus for this decoder. Where a capture contradicted an assumption, the test keeps the
 * capture.
 */
class K1GCodecTest {

    private fun decode(hex: String, malformed: MalformedCounter? = null) =
        K1GCodec.decode(hex.replace(" ", "").hexToBytes(), malformed)

    @Test
    fun `telemetry keeps its subtype and payload`() {
        val msgs = decode("000E 0001 00000000 0C08 0002 0000")

        assertEquals(1, msgs.size)
        val t = assertIs<DashMessage.Telemetry>(msgs[0])
        assertEquals(0x08, t.sub)
        assertEquals("0000", t.value.toHex())
    }

    @Test
    fun `a packet with two segments decodes both, in wire order`() {
        // Real: an 0x01 event the audit never catalogued, followed by telemetry. Order is
        // part of the contract — invariant 7 requires a button ack to go out before any
        // other work, and a button can share a packet with anything else.
        val msgs = decode("0018 0002 00000000 0101 0005 4B31470022 0C15 0003 000000")

        assertEquals(2, msgs.size)
        val unknown = assertIs<DashMessage.Unknown>(msgs[0])
        assertEquals(0x01, unknown.tlv.type)
        assertEquals("4b31470022", unknown.tlv.value.toHex())
        assertEquals(0x15, assertIs<DashMessage.Telemetry>(msgs[1]).sub)
    }

    @Test
    fun `a button carries the code from the LAST byte`() {
        val msgs = decode("000D 0001 00000000 0900 0001 13")

        assertEquals(0x13, assertIs<DashMessage.Button>(msgs[0]).code)
    }

    @Test
    fun `auth result is accepted only on 01`() {
        assertTrue(assertIs<DashMessage.AuthResult>(decode("000D 0001 00000000 0701 0001 01")[0]).accepted)
        // Anything else is a rejection, and the handshake restarts from the modulus.
        assertTrue(!assertIs<DashMessage.AuthResult>(decode("000D 0001 00000000 0701 0001 00")[0]).accepted)
        assertTrue(!assertIs<DashMessage.AuthResult>(decode("000D 0001 00000000 0701 0001 02")[0]).accepted)
        // An EMPTY result field must never read as success — this used to live in DashAuth
        // and moved here with the classification. Found while converting its tests.
        assertTrue(!assertIs<DashMessage.AuthResult>(decode("000C 0001 00000000 0701 0000")[0]).accepted)
    }

    @Test
    fun `modulus and exponent arrive in ONE datagram`() {
        // 147 bytes off the Huawei, 2026-09-18 09:41:01 — the dash's answer after it
        // restarted mid-ride. The exponent (01 00 01 = 65537) rides in the same packet as
        // the 128-byte modulus, which is why DashAuth only sends the key once it has both
        // rather than on each arrival.
        val msgs = decode(
            "0093 0002 00000000 0700 0080 " +
                "95DF7B975D9FF4E864C7468B89A205B75D3C6C773BD1E6E4E3A200595ACA2C036836518AA7AE5C" +
                "519CA8DA31CCD9AF8195FE1FC936122AFC6D7A7F380461C3A831D22C8DFC1AC72F1CC9B5637B7C" +
                "2AB660E5097EFE7F7D38947 1C7BF06E7F4BC206FA9087F190D6E5AC00DB0C48D8020A93C477DBC" +
                "3B49AA64EEAB699246BD7B ".replace(" ", "") +
                "0703 0003 010001",
        )

        assertEquals(2, msgs.size)
        assertEquals(128, assertIs<DashMessage.AuthModulus>(msgs[0]).value.size)
        assertEquals("010001", assertIs<DashMessage.AuthExponent>(msgs[1]).value.toHex())
    }

    @Test
    fun `decoder-opened needs the 0x55, and 09 04 AA is not one`() {
        val idr = decode("000D 0001 00000000 0906 0001 55")[0]
        assertTrue(assertIs<DashMessage.DecoderOpened>(idr).keyFrame)

        // Straight from the Xiaomi's log: the same family, sub 04, value AA. The old code
        // gated on 0x55 too; keeping the gate is what stops "the dash said something about
        // frames" from becoming "the dash acknowledged a frame" for the third time
        // (spec/video.md).
        val notAnOpen = decode("000D 0001 00000000 0904 0001 AA")[0]
        assertIs<DashMessage.Unknown>(notAnOpen)
    }

    @Test
    fun `telemetry and a decoder-open share a packet`() {
        val msgs = decode("0012 0002 00000000 0C14 0001 00 0906 0001 55")

        assertEquals(0x14, assertIs<DashMessage.Telemetry>(msgs[0]).sub)
        assertTrue(assertIs<DashMessage.DecoderOpened>(msgs[1]).keyFrame)
    }

    @Test
    fun `a declared length that disagrees with the datagram is counted, not rejected`() {
        val counter = MalformedCounter()
        // Declares 0x00FF, is 14 bytes. The dash has never done this — 7692 packets of the
        // 2026-09-13 log agreed to the byte — but leniency without a counter is silence.
        val msgs = decode("00FF 0001 00000000 0C08 0002 0000", counter)

        assertEquals(1, msgs.size, "still parsed")
        assertEquals("1/0", counter.drain().toString())
        assertEquals("0/0", counter.drain().toString(), "and the window resets")
    }

    @Test
    fun `a TLV that runs past the end of the datagram is counted, and handed over short`() {
        val counter = MalformedCounter()
        // 0C 08 declares two payload bytes and only one follows. Counting this is the whole
        // point of the counter: the value arrives short, and without the count a dash that
        // started producing them would look exactly like a dash that never had.
        val msgs = decode("000D 0001 00000000 0C08 0002 00", counter)

        val t = assertIs<DashMessage.Telemetry>(msgs[0])
        assertEquals("00", t.value.toHex(), "one byte, not two")
        assertEquals(1, counter.drain().truncatedTlv)
    }

    @Test
    fun `a whole datagram of complete TLVs counts nothing`() {
        val counter = MalformedCounter()
        // Two TLVs, both complete, declared length matching the datagram — the shape every
        // one of the 2026-09-18 captures has. The counters stay at zero on a healthy packet,
        // or a warning a minute would train the reader to skip the line.
        decode("0012 0002 00000000 0C14 0001 00 0906 0001 55", counter)

        assertTrue(counter.drain().isEmpty)
    }
}
