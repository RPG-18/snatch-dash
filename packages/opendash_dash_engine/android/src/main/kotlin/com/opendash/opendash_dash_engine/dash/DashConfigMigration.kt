package com.opendash.opendash_dash_engine.dash

/**
 * Which saved values move out of the encrypted store and into plain preferences.
 *
 * A pure function over two lookups, because the rule is not obvious and the class that
 * applies it cannot be tested: `SharedPreferences` needs a device and there is no
 * Robolectric in this project. What IS worth pinning is which of two copies wins, and
 * that fits in a map.
 *
 * **Plain wins when both exist**, and that is the whole decision. Three builds have
 * written these keys:
 *
 *  - before the encrypted store, straight into plain `dash_config`;
 *  - with it, into the same file under encrypted key names, having first moved any
 *    plain values across and deleted them;
 *  - with it but on a device where the Keystore refused, which fell back to writing
 *    plain again — over an encrypted copy that may still hold something older.
 *
 * Only the third can leave both, and it is a choice between two copies with no way to
 * tell which is newer: nothing here is timestamped, and `SharedPreferences` does not
 * keep one.
 *
 * **So this is the better bet, not a guarantee, and the difference is worth stating.**
 * If the Keystore refused permanently, every edit the rider made afterwards went to the
 * plain copy and the encrypted one is an old snapshot — preferring it would undo all of
 * them. If the refusal was momentary — the profile still locked at launch, a
 * `KeyStoreException` under load — then the plain copy is the older one, and this rule
 * reverts the setting by one edit. Both are real; the first is the one that leaves both
 * copies lying around for long enough to be migrated, and it loses more when guessed
 * wrong. Nothing about the second is detectable from here.
 *
 * **Presence, not emptiness.** `ssid` is legitimately empty — that is what
 * [DashConfig.forgetDash] writes, and it means "ask the rider to pick the dash again".
 * A rule that treated blank as absent would resurrect the forgotten name from the
 * encrypted copy, undoing the one action whose entire purpose is to erase it.
 */
internal object DashConfigMigration {

    /**
     * Keys Tink writes into the same file to hold the encrypted store's own keysets.
     * Plain names, unlike everything else that store writes.
     */
    private const val TINK_PREFIX = "__androidx_security_crypto"

    /**
     * Is there anything of the encrypted store's left in this file?
     *
     * **This replaces a "migration done" marker, and the difference is data loss.** A
     * marker is a one-way latch that the older build neither clears nor knows about,
     * while that build actively moves plain values INTO the encrypted store and deletes
     * them. Downgrade and upgrade again — routine while sideloading builds onto the
     * field phones — and the latch says "done" over a plain store that is now empty:
     * the remembered SSID and any overridden password are gone for good.
     *
     * Asking the file instead is idempotent in both directions and costs nothing: every
     * key the encrypted store writes for a VALUE is an encrypted blob, so anything here
     * that is neither one of ours nor one of Tink's own keysets is a value waiting to be
     * carried out. A fresh install has no keys at all; a file we have already migrated
     * has only ours and the keysets.
     */
    fun hasEncryptedValues(allKeys: Set<String>, ours: List<String>): Boolean =
        allKeys.any { it !in ours && !it.startsWith(TINK_PREFIX) }

    /**
     * @param plain what plain preferences hold for a key, or null if the key is absent
     * @param encrypted the same for the encrypted store
     * @return the values to write into plain preferences; keys not listed stay as they are
     */
    fun plan(
        keys: List<String>,
        plain: (String) -> String?,
        encrypted: (String) -> String?,
    ): Map<String, String> = buildMap {
        for (key in keys) {
            if (plain(key) != null) continue
            val carried = encrypted(key) ?: continue
            put(key, carried)
        }
    }
}
