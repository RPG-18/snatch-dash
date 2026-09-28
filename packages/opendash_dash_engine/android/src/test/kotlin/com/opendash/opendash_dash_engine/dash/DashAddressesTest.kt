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
    fun `a correct broadcast is kept even when the dash cannot be placed`() {
        // Until task 13 this fell back whole, because a derived broadcast next to the
        // constant dash IP meant control worked while RTP went nowhere. Now the dash
        // address is provisional — the handshake replaces it before RTP exists — so
        // throwing away a broadcast that is provably right is the worse trade.
        val a = DashAddresses.resolve(ipv4 = "10.42.0.99", prefixLength = 24, gateway = null)
        assertEquals("10.42.0.255", a.broadcast)
        assertEquals(DashAddresses.FALLBACK_DASH, a.dashIp)
        assertTrue("provisional" in a.source, "the ride file must say it is a guess: ${a.source}")
    }

    @Test
    fun `an off-subnet gateway is still refused, provisional or not`() {
        // It would self-correct at the handshake, but an address we can already tell is
        // wrong should not be the one a future reordering sends the first packet to.
        val a = DashAddresses.resolve("192.168.1.37", 24, "10.0.0.1")
        assertEquals("192.168.1.255", a.broadcast)
        assertEquals(DashAddresses.FALLBACK_DASH, a.dashIp)
        assertTrue("off-subnet" in a.source, a.source)
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

    // ── fromLink: the selection half, which used to have no tests at all ──────
    //
    // Everything above pins the arithmetic. These pin the rules that decide what the
    // arithmetic is given — which address to prefer and which source names the dash.
    // Both were platform-shaped and therefore untested until review pointed out that an
    // inverted link-local check or a dropped wildcard guard would pass every gate the
    // project has and surface only as a handshake that times out in the field.

    private fun v4(addr: String, prefix: Int = 24) =
        DashAddresses.LinkV4(addr, prefix, linkLocal = addr.startsWith("169.254."))

    @Test
    fun `a real address is preferred over a link-local one whatever the order`() {
        // 10.42, deliberately: on 192.168.1.x the derived broadcast equals the constant,
        // so a wrong answer looks identical to a right one and the test proves nothing.
        // The first draft of this test made exactly that mistake and a mutation walked
        // past it.
        val real = v4("10.42.0.99")
        val apipa = v4("169.254.7.7", 16)
        for (list in listOf(listOf(apipa, real), listOf(real, apipa))) {
            val a = DashAddresses.fromLink(list, dhcpServer = "10.42.0.1", defaultGateways = emptyList())
            assertEquals("10.42.0.255", a.broadcast, "order: ${list.map { it.address }}")
            assertEquals("10.42.0.1", a.dashIp)
        }
    }

    @Test
    fun `a link-local-only link still reports why, instead of pretending`() {
        val a = DashAddresses.fromLink(listOf(v4("169.254.7.7", 16)), null, emptyList())
        assertEquals(DashAddresses.FALLBACK_BROADCAST, a.broadcast)
        assertTrue("DHCP did not answer" in a.source, a.source)
    }

    @Test
    fun `the DHCP server wins over the default route, and the route is the fallback`() {
        // On API 30+ both can be present and they are the same box; below 30 only the
        // route exists. Pinning the precedence means the API gate cannot quietly invert.
        val withBoth = DashAddresses.fromLink(
            listOf(v4("10.42.0.99")), dhcpServer = "10.42.0.1", defaultGateways = listOf("10.42.0.254"),
        )
        assertEquals("10.42.0.1", withBoth.dashIp)

        val routeOnly = DashAddresses.fromLink(
            listOf(v4("10.42.0.99")), dhcpServer = null, defaultGateways = listOf("10.42.0.254"),
        )
        assertEquals("10.42.0.254", routeOnly.dashIp)
    }

    @Test
    fun `an empty link is the fallback, not a crash`() {
        val a = DashAddresses.fromLink(emptyList(), null, emptyList())
        assertEquals(DashAddresses.FALLBACK_BROADCAST, a.broadcast)
        assertEquals(DashAddresses.FALLBACK_DASH, a.dashIp)
        assertTrue("no IPv4" in a.source, a.source)
    }

    @Test
    fun `every fallback names its own cause`() {
        // On a release build DebugLog is off and this string is the only evidence. Three
        // fallbacks that read alike would erase the difference between "no network",
        // "the lookup threw" and "the platform had nothing".
        val reasons = listOf("no network", "link properties threw", "no link properties")
            .map { DashAddresses.fallback(it).source }
        assertEquals(reasons.size, reasons.toSet().size, "collapsed: $reasons")
        for ((i, r) in reasons.withIndex()) {
            assertTrue(
                listOf("no network", "threw", "no link properties")[i] in r,
                "reason $i lost its cause: $r",
            )
        }
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
