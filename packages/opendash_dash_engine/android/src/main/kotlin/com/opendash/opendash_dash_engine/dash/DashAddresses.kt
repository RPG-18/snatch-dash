package com.opendash.opendash_dash_engine.dash

/**
 * Where to send: the control-plane broadcast and the dash's own address.
 *
 * **Why this is not just two constants any more.** `192.168.1.255` and `192.168.1.1` are
 * what every dash seen so far hands out, and they are still the answer when nothing better
 * can be worked out. But they are a guess about someone else's DHCP server, and the way
 * that guess fails is silent: a dash on a different subnet would leave the control plane
 * broadcasting into a network the phone is not on, the handshake would simply never get an
 * answer, and the ride file would say `still unavailable` — a Wi-Fi-shaped symptom for an
 * addressing cause. Task 1 of `network-refactoring.md`.
 *
 * **Инвариант 9 is untouched**: control stays broadcast, RTP stays unicast. What changes is
 * only which numbers those two are, and only when the platform states them.
 *
 * [source] is for the ride file. Every path sets it, including the fallbacks, because "we
 * used the constants" and "we derived 192.168.1.255 from the interface" produce identical
 * traffic and have to be told apart after the fact.
 */
internal data class DashAddresses(
    val broadcast: String,
    val dashIp: String,
    val source: String,
) {
    /** One IPv4 the platform reports on the link. */
    data class LinkV4(val address: String?, val prefixLength: Int, val linkLocal: Boolean)

    companion object {
        /** What the dash has always been. Used when the link says nothing usable. */
        const val FALLBACK_BROADCAST = "192.168.1.255"
        const val FALLBACK_DASH = "192.168.1.1"

        val FALLBACK = DashAddresses(
            broadcast = FALLBACK_BROADCAST,
            dashIp = FALLBACK_DASH,
            source = "defaults (no link properties)",
        )

        /**
         * The constants, with the reason this ride is using them.
         *
         * Every caller passes a different [reason]. A ride file that says only "defaults"
         * cannot tell "there was no network" from "the lookup threw" from "the platform
         * had nothing", and those are the three most likely ways this goes wrong — on a
         * release build, where `DebugLog` is off, this string is the only evidence there
         * is.
         */
        fun fallback(reason: String): DashAddresses = FALLBACK.copy(source = "defaults ($reason)")

        /**
         * Everything the platform said about the link, turned into two addresses.
         *
         * Plain values rather than `LinkProperties`/`LinkAddress`/`RouteInfo` so the
         * selection rules below are testable without a device. They were not, and they
         * are where the platform subtleties live: which address to prefer, and which of
         * two sources names the dash.
         */
        fun fromLink(
            ipv4s: List<LinkV4>,
            dhcpServer: String?,
            defaultGateways: List<String>,
        ): DashAddresses {
            // A non-link-local address first. 169.254/16 is what the platform leaves
            // behind when DHCP did not answer, and taking it merely because it sorts
            // first would discard a real address further down the list — [resolve] would
            // then reject it and fall back, on a link that could have been described.
            val v4 = ipv4s.firstOrNull { !it.linkLocal } ?: ipv4s.firstOrNull()
            // The DHCP server is the direct answer; the default route's gateway is the
            // same box reached the long way round, and is all API 29 has.
            val gateway = dhcpServer ?: defaultGateways.firstOrNull()
            return resolve(
                ipv4 = v4?.address,
                prefixLength = v4?.prefixLength ?: 0,
                gateway = gateway,
            )
        }

        /**
         * Work out both addresses from what the platform says about the interface.
         *
         * @param ipv4 the phone's own IPv4 address on the dash's network, dotted-quad, or
         *   null if the link has none yet.
         * @param prefixLength its prefix length, as `LinkAddress.getPrefixLength` reports it.
         * @param gateway the dash's address as the platform knows it — the DHCP server on
         *   API 30+, otherwise the default route's gateway — or null.
         *
         * **The two are answered together, never mixed.** An earlier version let each half
         * fall back on its own, and review found what that produces: a 10.42.0.0/24 dash
         * with no gateway got the derived broadcast `10.42.0.255` paired with the constant
         * `192.168.1.1`. Control would work, RTP would go to an address that cannot exist
         * on that link, and the rider would get a dash that connects and shows nothing —
         * strictly worse than the clean handshake failure the constants used to give. So
         * either the link explains both addresses or neither is taken from it.
         *
         * **Considered and not done: assuming the `.1` of whatever subnet we are on.** It
         * would rescue exactly the case above, and the dash IS the access point, so `.1`
         * is a decent bet. But it is a bet, the task asked for the constants when
         * derivation fails, and a wrong guess here fails the same silent way. Falling back
         * whole costs nothing we have ever observed.
         */
        fun resolve(ipv4: String?, prefixLength: Int, gateway: String?): DashAddresses {
            val host = parseIpv4(ipv4)
            val derivedBroadcast = broadcastFor(host, prefixLength)
                ?: return fallback(whyNoBroadcast(ipv4, host, prefixLength))
            // A successful broadcast proves `host` is non-null; the compiler cannot see
            // that through the helper, so say it once here instead of in both takeIfs.
            val me = requireNotNull(host)
            fun onLink(addr: Int): Boolean = sameSubnet(me, addr, prefixLength)

            // A gateway outside our own subnet is not our dash — it is a stale route, or a
            // second interface's default.
            val gw = parseIpv4(gateway)?.takeIf(::onLink)

            // No gateway: the constant is only usable if it is on THIS link. Off-link it is
            // the mixed pair described above.
            val constantOnLink = parseIpv4(FALLBACK_DASH)?.takeIf(::onLink)
            val dash = gw ?: constantOnLink
                ?: return fallback("dash unlocatable on $ipv4/$prefixLength")

            val dashSource = when {
                gw != null -> "dash from gateway"
                gateway == null -> "dash default (no gateway, on-link)"
                else -> "dash default (gateway $gateway is off-subnet)"
            }
            return DashAddresses(
                broadcast = format(derivedBroadcast),
                dashIp = format(dash),
                source = "broadcast from $ipv4/$prefixLength, $dashSource",
            )
        }

        /**
         * Which of [broadcastFor]'s three refusals fired.
         *
         * They are not interchangeable to whoever reads the ride file: `169.254` means
         * the dash's DHCP did not answer, which a rider can act on by power-cycling it.
         * The other two mean a link no dash has ever presented.
         */
        private fun whyNoBroadcast(ipv4: String?, host: Int?, prefixLength: Int): String = when {
            ipv4 == null -> "link has no IPv4"
            host == null -> "'$ipv4' is not a dotted quad"
            isLinkLocal(host) -> "$ipv4 is link-local — the dash's DHCP did not answer"
            else -> "/$prefixLength has no usable broadcast"
        }

        /**
         * Broadcast for [host]/[prefixLength], or null when there is none to compute.
         *
         * Rejected, each for its own reason rather than as a blanket range check:
         *  - **A prefix of 31 or 32** has no broadcast address at all (RFC 3021); a
         *    "broadcast" derived from one is the host itself, which would quietly turn the
         *    control plane into unicast-to-self and break инвариант 9 without changing a
         *    line of socket code.
         *  - **A prefix below 8** is not a link the dash could be on, and the wider the
         *    prefix the further the broadcast reaches — worth refusing rather than
         *    shouting the handshake across half the address space.
         *  - **169.254.0.0/16** means DHCP did not answer. The address exists but nothing
         *    is reachable through it, and the constants are no worse.
         */
        private fun broadcastFor(host: Int?, prefixLength: Int): Int? {
            if (host == null) return null
            if (prefixLength < 8 || prefixLength > 30) return null
            if (isLinkLocal(host)) return null
            val mask = maskFor(prefixLength)
            return host or mask.inv()
        }

        /**
         * Prefix to netmask, for prefixes of 1..32 only.
         *
         * No `prefixLength == 0` branch: both callers exclude it ([broadcastFor] refuses
         * below 8, [sameSubnet] outside 1..32), and `-1 shl 32` is `-1` rather than 0
         * because Kotlin's shift takes its count modulo 32. A guard for an input that
         * cannot arrive would only suggest this is safe for arbitrary prefixes.
         */
        private fun maskFor(prefixLength: Int): Int = -1 shl (32 - prefixLength)

        private fun sameSubnet(a: Int, b: Int, prefixLength: Int): Boolean {
            if (prefixLength !in 1..32) return false
            val mask = maskFor(prefixLength)
            return (a and mask) == (b and mask)
        }

        /** `169.254.0.0/16` — the address a failed DHCP leaves behind. */
        private fun isLinkLocal(addr: Int): Boolean = (addr ushr 16) == 0xA9FE

        /**
         * Dotted-quad to a big-endian Int, or null for anything else.
         *
         * Hand-rolled rather than `InetAddress.getByName`, which for a non-literal does a
         * DNS lookup — a blocking network call on whatever thread this runs on, for a
         * string that should never have been a name.
         */
        private fun parseIpv4(s: String?): Int? {
            if (s.isNullOrEmpty()) return null
            var acc = 0
            var octet = -1
            var parts = 0
            for (ch in s) {
                when {
                    ch in '0'..'9' -> {
                        if (octet < 0) octet = 0
                        octet = octet * 10 + (ch - '0')
                        if (octet > 255) return null
                    }
                    ch == '.' -> {
                        if (octet < 0) return null
                        acc = (acc shl 8) or octet
                        octet = -1
                        parts++
                        if (parts > 3) return null
                    }
                    // Anything else — a scope id, an IPv6 colon, a hostname letter.
                    else -> return null
                }
            }
            if (parts != 3 || octet < 0) return null
            return (acc shl 8) or octet
        }

        private fun format(addr: Int): String =
            "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}." +
                "${(addr ushr 8) and 0xFF}.${addr and 0xFF}"
    }
}
