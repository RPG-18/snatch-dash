package com.opendash.opendash_dash_engine.dash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Address arithmetic, checked without a phone.
 *
 * This is the one piece of the engine whose failure mode is a silence. Send the control
 * plane to the wrong broadcast and nothing errors: the dash simply never answers the
 * handshake, and the ride file reports `still unavailable` — indistinguishable from the
 * dash being switched off. So every case here is either "we computed it" or "we correctly
 * refused to compute it and kept the constants that have always worked".
 */
class DashAddressesTest {

    @Test
    fun `the ordinary dash link derives both addresses`() {
        val a = DashAddresses.resolve(ipv4 = "192.168.1.37", prefixLength = 24, gateway = "192.168.1.1")
        assertEquals("192.168.1.255", a.broadcast)
        assertEquals("192.168.1.1", a.dashIp)
        assertTrue("from" in a.source, "derived addresses must say so: ${a.source}")
    }

    @Test
    fun `a dash on some other subnet is followed, which is the whole point`() {
        // If this ever regresses to the constants, the symptom in the field is a handshake
        // that times out on a link that is perfectly up.
        val a = DashAddresses.resolve(ipv4 = "10.42.0.99", prefixLength = 24, gateway = "10.42.0.1")
        assertEquals("10.42.0.255", a.broadcast)
        assertEquals("10.42.0.1", a.dashIp)
    }

    @Test
    fun `the mask is applied, not assumed to be a byte boundary`() {
        // /22 spans four class-C blocks: the broadcast is .3.255, not .0.255. A masking bug
        // that only ever sees /24 in the field would pass every other test in this file.
        assertEquals(
            "192.168.3.255",
            DashAddresses.resolve("192.168.0.5", 22, null).broadcast,
        )
        assertEquals(
            "192.168.1.63",
            DashAddresses.resolve("192.168.1.10", 26, null).broadcast,
        )
        assertEquals(
            "192.255.255.255",
            DashAddresses.resolve("192.168.1.10", 8, null).broadcast,
        )
    }

    @Test
    fun `prefixes with no broadcast address fall back instead of inventing one`() {
        // RFC 3021: /31 and /32 have none. Deriving one gives the host back, which would
        // turn the control plane into unicast-to-self — инвариант 9 broken with no socket
        // code changed and no error anywhere.
        for (prefix in listOf(31, 32)) {
            val a = DashAddresses.resolve("192.168.1.37", prefix, null)
            assertEquals(DashAddresses.FALLBACK_BROADCAST, a.broadcast, "/$prefix")
            assertTrue("no usable broadcast" in a.source, "/$prefix: ${a.source}")
        }
        // And below /8 the broadcast would reach absurdly far.
        assertEquals(
            DashAddresses.FALLBACK_BROADCAST,
            DashAddresses.resolve("10.0.0.1", 7, null).broadcast,
        )
    }

    @Test
    fun `a failed DHCP does not get to define the broadcast`() {
        // 169.254/16 is what the platform assigns when nothing answered. The address is
        // real, the network is not.
        val a = DashAddresses.resolve("169.254.12.34", 16, "169.254.0.1")
        assertEquals(DashAddresses.FALLBACK_BROADCAST, a.broadcast)
        assertEquals(DashAddresses.FALLBACK_DASH, a.dashIp)
    }

    @Test
    fun `an off-subnet gateway is refused while the broadcast is still derived`() {
        // A stale default route is no reason to throw away a broadcast we can compute —
        // and here the constant dash IP is on this very link, so the pair stays coherent.
        // (When it would NOT be, the whole thing falls back; see the test below.)
        val a = DashAddresses.resolve("192.168.1.37", 24, "10.0.0.1")
        assertEquals("192.168.1.255", a.broadcast)
        assertEquals(DashAddresses.FALLBACK_DASH, a.dashIp)
        assertTrue("off-subnet" in a.source, a.source)
    }

    @Test
    fun `an off-subnet dash with no gateway falls back WHOLE, never half`() {
        // The regression review caught: deriving 10.42.0.255 and pairing it with the
        // constant 192.168.1.1 gives a dash that connects (control is broadcast, so the
        // handshake works) and then shows nothing, because RTP goes to an address that
        // cannot exist on the link. The constants alone fail at the handshake instead,
        // which is the failure the rider can actually act on.
        val a = DashAddresses.resolve(ipv4 = "10.42.0.99", prefixLength = 24, gateway = null)
        assertEquals(DashAddresses.FALLBACK_BROADCAST, a.broadcast)
        assertEquals(DashAddresses.FALLBACK_DASH, a.dashIp)
        assertTrue("unlocatable" in a.source, a.source)
    }

    @Test
    fun `broadcast and dash always name one subnet`() {
        // The invariant behind the test above, stated once over every case in this file.
        for (case in listOf(
            Triple("192.168.1.37", 24, "192.168.1.1"),
            Triple("10.42.0.99", 24, "10.42.0.1"),
            Triple("10.42.0.99", 24, null),
            Triple("10.42.0.99", 24, "10.0.0.1"),
            Triple("192.168.1.37", 31, "192.168.1.36"),
            Triple("169.254.1.2", 16, "169.254.0.1"),
            Triple(null, 0, null),
        )) {
            val (ip, prefix, gw) = case
            val a = DashAddresses.resolve(ip, prefix, gw)
            // Same /24 is enough to catch a cross-subnet pair for every case here.
            assertEquals(
                a.broadcast.substringBeforeLast('.'),
                a.dashIp.substringBeforeLast('.'),
                "mixed subnets for $ip/$prefix gw=$gw: ${a.broadcast} vs ${a.dashIp}",
            )
        }
    }

    @Test
    fun `a link with no address at all is the documented fallback`() {
        val a = DashAddresses.resolve(null, 0, null)
        assertEquals(DashAddresses.FALLBACK_BROADCAST, a.broadcast)
        assertEquals(DashAddresses.FALLBACK_DASH, a.dashIp)
        assertEquals(DashAddresses.FALLBACK.broadcast, a.broadcast)
        assertEquals(DashAddresses.FALLBACK.dashIp, a.dashIp)
    }

    @Test
    fun `malformed input is refused rather than parsed halfway`() {
        // Anything that is not a dotted quad: IPv6, a scoped address, a hostname, a short
        // form, an out-of-range octet, and the empty string. None of these should reach
        // `InetAddress.getByName` — for a NAME that call does a blocking DNS lookup, on
        // whatever thread the session happens to open on.
        for (bad in listOf(
            "fe80::1", "192.168.1.1%wlan0", "dash.local", "192.168.1", "192.168.1.256",
            "192.168.1.1.1", "", " 192.168.1.1", "192.168.1.",
        )) {
            val a = DashAddresses.resolve(bad, 24, bad)
            assertEquals(DashAddresses.FALLBACK_BROADCAST, a.broadcast, "broadcast from '$bad'")
            assertEquals(DashAddresses.FALLBACK_DASH, a.dashIp, "dash from '$bad'")
        }
    }

    @Test
    fun `high octets survive the sign bit`() {
        // The arithmetic runs on a signed Int, so anything above 127 in the top octet is
        // where a shift-versus-ushr slip shows up. Both cases name a gateway on purpose:
        // off 192.168.1.0/24 and with no gateway the whole pair falls back, and then this
        // would be testing the fallback rather than the arithmetic.
        assertEquals(
            "200.201.202.255",
            DashAddresses.resolve("200.201.202.17", 24, "200.201.202.1").broadcast,
        )
        val a = DashAddresses.resolve("240.0.0.2", 24, "240.0.0.1")
        assertEquals("240.0.0.255", a.broadcast)
        assertEquals("240.0.0.1", a.dashIp)
    }

    @Test
    fun `the source string always says which way each half went`() {
        // It is the only way to tell "we used the constants" from "we derived the same
        // numbers" after the fact, and both produce identical traffic.
        val derived = DashAddresses.resolve("192.168.1.37", 24, "192.168.1.1")
        val fellBack = DashAddresses.resolve(null, 0, null)
        assertTrue(derived.source.isNotBlank() && fellBack.source.isNotBlank())
        assertTrue(derived.source != fellBack.source, "both said: ${derived.source}")
        assertTrue("default" in fellBack.source, fellBack.source)
    }
}
