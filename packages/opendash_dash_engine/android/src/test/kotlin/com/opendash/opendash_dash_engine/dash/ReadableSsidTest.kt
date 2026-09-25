package com.opendash.opendash_dash_engine.dash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When the platform has actually told us the network's name.
 *
 * The rule behind every "Android hid its name" path in `DashWifiManager`, and the one it
 * applies in two places — the `onAvailable` lookup and the capabilities update. Getting it
 * wrong either way means joining the dash under a name it refuses inside the handshake,
 * which on 2026-09-19 looked like a phone connected to the bike sending it nothing at all.
 */
class ReadableSsidTest {

    @Test
    fun `a name is a name`() {
        assertEquals("RE_9CP9_250218", readableSsid("RE_9CP9_250218"))
    }

    @Test
    fun `the quotes WifiInfo wraps it in are not part of it`() {
        // WifiInfo.getSsid() returns it quoted; ScanResult.SSID usually does not.
        assertEquals("RE_9CP9", readableSsid("\"RE_9CP9\""))
    }

    @Test
    fun `the sentinel Android returns when it will not say is not a name`() {
        // From API 31 this is the common answer for a network the app joined itself.
        assertNull(readableSsid("<unknown ssid>"))
        assertNull(readableSsid("\"<unknown ssid>\""), "quoted, as WifiInfo gives it")
    }

    @Test
    fun `absent and empty are both no name`() {
        assertNull(readableSsid(null))
        assertNull(readableSsid(""))
        assertNull(readableSsid("\"\""), "quotes around nothing")
        assertNull(readableSsid("   "))
    }
}
