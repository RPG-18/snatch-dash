package com.opendash.opendash_dash_engine.dash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which copy of a saved setting wins when the encrypted store is retired.
 *
 * The rest of `DashConfig` is `SharedPreferences` calls and cannot be exercised off a
 * device — no Robolectric here. This is the part that is a decision rather than
 * plumbing, and getting it backwards is silent: the rider's dash keeps working, just
 * with a password or a name they replaced some releases ago.
 */
class DashConfigMigrationTest {

    private fun plan(
        plain: Map<String, String> = emptyMap(),
        encrypted: Map<String, String> = emptyMap(),
    ) = DashConfigMigration.plan(
        keys = DashConfig.MIGRATED_KEYS,
        plain = { plain[it] },
        encrypted = { encrypted[it] },
    )

    @Test
    fun `a value only the encrypted store has is carried over`() {
        // The ordinary case: everyone who paired on a build with encryption.
        val carried = plan(encrypted = mapOf("ssid" to "RE_9CP9_250218"))
        assertEquals(mapOf("ssid" to "RE_9CP9_250218"), carried)
    }

    @Test
    fun `all three keys move independently`() {
        val carried = plan(
            encrypted = mapOf(
                "ssid_prefix" to "TRIPPER_",
                "ssid" to "RE_9CP9_250218",
                "password" to "hunter2",
            ),
        )
        assertEquals(3, carried.size, "$carried")
        assertEquals("TRIPPER_", carried["ssid_prefix"])
        assertEquals("hunter2", carried["password"])
    }

    @Test
    fun `a plain value is left alone, not overwritten by the encrypted copy`() {
        // Only one build can leave both: the one whose Keystore refused and fell back to
        // writing plain over an encrypted copy that may hold something older.
        //
        // This pins the rule, not a guarantee that the rule is always right — see the
        // class doc. Nothing here is timestamped, so a momentary refusal, where the
        // plain copy is the OLDER one, is indistinguishable and loses one edit. The
        // permanent refusal is the case that leaves both copies around long enough to
        // matter, and there the encrypted copy is a snapshot from before everything the
        // rider has changed since.
        val carried = plan(
            plain = mapOf("password" to "current"),
            encrypted = mapOf("password" to "stale"),
        )
        assertTrue(carried.isEmpty(), "the older copy won: $carried")
    }

    @Test
    fun `a deliberately emptied SSID is not resurrected`() {
        // `forgetDash()` writes "", and it means "ask the rider to pick the dash again".
        // A rule that read blank as absent would undo the one action whose whole purpose
        // is to erase that name — and the rider would be reconnected to a dash they had
        // just told the app to forget.
        val carried = plan(
            plain = mapOf("ssid" to ""),
            encrypted = mapOf("ssid" to "RE_9CP9_250218"),
        )
        assertTrue(carried.isEmpty(), "the forgotten dash came back: $carried")
    }

    @Test
    fun `an empty encrypted value still moves, because empty is a value`() {
        // The mirror of the case above: "" written before the retirement means the same
        // thing and has to survive it, or the next connect silently re-pairs.
        val carried = plan(encrypted = mapOf("ssid" to ""))
        assertEquals(mapOf("ssid" to ""), carried)
    }

    @Test
    fun `a fresh install carries nothing`() {
        assertTrue(plan().isEmpty())
    }

    private fun leftovers(vararg keys: String) =
        DashConfigMigration.hasEncryptedValues(keys.toSet(), DashConfig.MIGRATED_KEYS)

    @Test
    fun `a fresh install has nothing to migrate`() {
        assertFalse(leftovers())
    }

    @Test
    fun `an already migrated file has nothing to migrate`() {
        // Ours plus the two keysets Tink leaves behind, which are deliberately not
        // deleted — see DashConfig.migrateFromEncrypted.
        assertFalse(
            leftovers(
                "ssid",
                "ssid_prefix",
                "password",
                "__androidx_security_crypto_encrypted_prefs_key_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__",
            ),
        )
    }

    @Test
    fun `an encrypted value left in the file is found`() {
        // Every key the encrypted store writes for a value is a blob, so anything that
        // is neither ours nor a keyset is something to carry out.
        assertTrue(leftovers("ARvEsQ2_kR3nT0pQ", "__androidx_security_crypto_encrypted_prefs_key_keyset__"))
    }

    @Test
    fun `a downgrade and upgrade migrates again`() {
        // The case a "migration done" marker gets wrong, and gets wrong permanently:
        // the older build moves plain values INTO the encrypted store and deletes them,
        // so the file is left with blobs and no plain copies. Asking the file has no
        // memory to be stale.
        assertTrue(
            leftovers(
                "ARvEsQ2_kR3nT0pQ",
                "Bk91mZpQ0sLdH4tX",
                "__androidx_security_crypto_encrypted_prefs_key_keyset__",
            ),
        )
    }

    @Test
    fun `the old plaintext-only build has nothing to migrate`() {
        // Before encryption existed the values were already where they belong.
        assertFalse(leftovers("ssid", "password"))
    }
}
