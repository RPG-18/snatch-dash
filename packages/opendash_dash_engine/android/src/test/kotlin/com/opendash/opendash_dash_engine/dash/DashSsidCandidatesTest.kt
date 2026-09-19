package com.opendash.opendash_dash_engine.dash

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which scanned network the app is willing to call "the dash".
 *
 * The only part of the prefix-discovery path a JVM test can reach, and the part that
 * decides something: [DashWifiManager] feeds this list into the encrypted handshake AND
 * persists the winner through `onSsidResolved`, so a wrong pick is a wrong name remembered
 * across restarts.
 */
class DashSsidCandidatesTest {

    @Test
    fun `a single matching network is the answer`() {
        assertEquals(
            listOf("RE_9CP9_250218"),
            dashSsidCandidates(listOf("KeE2741BBE", "RE_9CP9_250218", "TPE7EFCF1F"), "RE_"),
        )
    }

    @Test
    fun `nothing matching is no answer`() {
        // The 2026-09-19 Huawei scan, verbatim: the dash was out of range and every attempt
        // that day failed here rather than later.
        assertEquals(
            emptyList(),
            dashSsidCandidates(listOf("KeE2741BBE", "Cu314F5607", "TPE7EFCF1F"), "RE_"),
        )
    }

    @Test
    fun `quotes around an SSID are not part of its name`() {
        // ScanResult.SSID carries them on some devices and not others.
        assertEquals(listOf("RE_9CP9"), dashSsidCandidates(listOf("\"RE_9CP9\""), "RE_"))
    }

    @Test
    fun `one AP on two bands is one network`() {
        assertEquals(
            listOf("RE_9CP9"),
            dashSsidCandidates(listOf("RE_9CP9", "RE_9CP9"), "RE_"),
        )
    }

    @Test
    fun `two different dashes are reported as two, so the caller can refuse`() {
        assertEquals(
            listOf("RE_AAAA", "RE_BBBB"),
            dashSsidCandidates(listOf("RE_AAAA", "RE_BBBB"), "RE_"),
        )
    }

    @Test
    fun `hidden and nameless networks are skipped`() {
        assertEquals(
            listOf("RE_9CP9"),
            dashSsidCandidates(listOf(null, "", "  ", "\"\"", "RE_9CP9"), "RE_"),
        )
    }

    @Test
    fun `a blank prefix matches nothing rather than everything`() {
        // Clearing the prefix field must not turn "find the dash" into "join whatever is
        // nearest" — the name picked here goes into the handshake and is then remembered.
        assertEquals(emptyList(), dashSsidCandidates(listOf("KeE2741BBE", "RE_9CP9"), ""))
        assertEquals(emptyList(), dashSsidCandidates(listOf("KeE2741BBE", "RE_9CP9"), "   "))
    }

    @Test
    fun `a name already found not to be the dash is skipped`() {
        // The whole point of remembering a rejection: the next request reads the same stale
        // scan, and without this it would pick the same wrong network again, for ever.
        assertEquals(
            listOf("RE_BBBB"),
            dashSsidCandidates(listOf("RE_AAAA", "RE_BBBB"), "RE_", exclude = setOf("RE_AAAA")),
        )
    }

    @Test
    fun `excluding the only candidate leaves nothing to guess`() {
        assertEquals(
            emptyList(),
            dashSsidCandidates(listOf("RE_AAAA"), "RE_", exclude = setOf("RE_AAAA")),
        )
    }

    @Test
    fun `the prefix matches at the start only`() {
        assertEquals(emptyList(), dashSsidCandidates(listOf("MY_RE_9CP9"), "RE_"))
    }
}
