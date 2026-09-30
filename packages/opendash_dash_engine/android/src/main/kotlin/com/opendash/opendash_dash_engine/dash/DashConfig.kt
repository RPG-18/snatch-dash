package com.opendash.opendash_dash_engine.dash

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics

/**
 * Per-rider dash WiFi configuration, persisted on-device.
 *
 * OpenDash is meant to work on any compatible Tripper dash, not just the author's.
 * Every dash advertises a different SSID (e.g. `RE_P0RP_260525`, `RE_XXXX_yymmdd`) but
 * they all share the `RE_` prefix and the factory passphrase `12345678`. So out of the
 * box we connect by PREFIX (see [DashWifiManager]) — the rider just picks their dash from
 * the system dialog once — and then we remember that exact SSID here for direct reconnects.
 *
 * Everything is overridable in Settings for dashes that don't fit the defaults.
 *
 * **Plain preferences, since 2026-09-30 (task 7.3).** This used to be
 * `EncryptedSharedPreferences`, and the encryption was protecting nothing: the passphrase
 * is `12345678`, printed in the dash's manual and identical on every unit, and an SSID is
 * broadcast in the clear by definition — anyone close enough to care can read it off the
 * air. What it did cost was a failure mode. When the Keystore refused, the old code fell
 * back to a different store, so the rider's saved dash silently reverted to defaults, and
 * it announced that with a `Toast` raised from a data class — a data layer deciding to
 * draw on the screen, with no way for the caller to handle it differently or for a test to
 * see it. `androidx.security:security-crypto` is also deprecated upstream.
 *
 * What is left of it is [migrateFromEncrypted], which runs once to carry existing values
 * back out. It is the only reason the dependency is still declared.
 *
 * **`dash_config.xml` is excluded from Auto Backup and device transfer** — see
 * `android/app/src/main/res/xml/backup_rules.xml` and its Android 12+ twin. That
 * argument about the passphrase covers the factory one; overriding it is a supported
 * feature, and an overridden one now leaves no copy of itself off the device. The same
 * exclusion also keeps the encrypted store's leftovers from being restored onto a phone
 * whose Keystore has no key for them, which is the one shape in which
 * [migrateFromEncrypted] could fail forever.
 */
class DashConfig private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateFromEncrypted()
    }

    /** Broadest match across Tripper variants; rider-overridable. */
    var ssidPrefix: String
        get() = prefs.getString(KEY_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
        set(v) = prefs.edit().putString(KEY_PREFIX, v).apply()

    /** The exact SSID once learned/entered. Empty = not yet known → discover by prefix. */
    var ssid: String
        get() = prefs.getString(KEY_SSID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SSID, v).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    /** True until a specific dash has been identified — connect by prefix discovery. */
    val needsDiscovery: Boolean get() = ssid.isBlank()

    /** Forget the learned dash so the next connect re-runs prefix discovery. */
    fun forgetDash() { ssid = "" }

    /**
     * Carry values written by the encrypted build back into plain preferences. Once.
     *
     * Without it every rider who has already paired loses the remembered SSID and any
     * overridden prefix or password: the encrypted build wrote the same file under
     * encrypted key names, so a plain read finds nothing and falls through to the
     * factory defaults. The SSID would be re-learned by prefix discovery — one more
     * system dialog — but an overridden password would simply stop working, with the
     * symptom appearing as a Wi-Fi failure.
     *
     * **Nothing records that it has run.** Whether there is work is asked of the file
     * itself — see [DashConfigMigration.hasEncryptedValues] — so a Keystore that
     * refuses today is simply retried tomorrow, and a downgrade to the older build
     * followed by an upgrade migrates again instead of finding a latch that says it
     * already did.
     *
     * Plain values are written BEFORE the encrypted ones are dropped, so a process death
     * in between leaves a stale copy rather than no copy. The cleanup is allowed to fail
     * for the same reason: what it removes is inert either way.
     *
     * **Tink's own two keyset entries are left behind on purpose.** They live in this
     * same file as plain keys (`__androidx_security_crypto_..._keyset__`) and they are
     * what makes the encrypted values readable at all. Deleting them would turn a
     * migration that has not run yet — on a device whose Keystore was refusing at the
     * time — into permanent loss, to save two inert strings.
     *
     * **Cost**: this runs on the main thread, from the plugin's `onAttachedToEngine`,
     * and opening a Keystore-backed store is not free. It ran there before too, on
     * every attach — unconditionally. Now the probe above skips it outright for every
     * install that has nothing left to carry, which after the first successful run is
     * all of them.
     */
    private fun migrateFromEncrypted() {
        if (!DashConfigMigration.hasEncryptedValues(prefs.all.keys, MIGRATED_KEYS)) return
        // One guard around the whole thing, the reads included. `getString` on this
        // store decrypts, and decryption throws — `SecurityException` when the Keystore
        // will not unwrap, `IllegalArgumentException` out of Base64 on a truncated
        // value. Thrown from here it leaves `init`, so `DashConfig.get` throws, so
        // `DashEngineController`'s constructor throws, so the plugin fails to attach —
        // every launch, forever, over a stale copy of a Wi-Fi password.
        runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            // Guarded per key, not once for all three. `getString` decrypts, so by the
            // failure model above a truncated `ssid` would abort the whole block before
            // `password` was even read — losing a perfectly readable overridden password
            // to the factory default, and permanently, since the damage that caused it
            // does not heal.
            val unreadable = mutableMapOf<String, String>()
            val carried = DashConfigMigration.plan(
                keys = MIGRATED_KEYS,
                plain = { key -> if (prefs.contains(key)) prefs.getString(key, null) else null },
                encrypted = { key ->
                    runCatching { encrypted.getString(key, null) }.getOrElse { error ->
                        unreadable[key] = error.javaClass.simpleName
                        null
                    }
                },
            )
            prefs.edit().apply {
                carried.forEach { (key, value) -> putString(key, value) }
            }.apply()
            runCatching {
                // Every blob goes, including one that would not decrypt.
                //
                // Keeping it "in case the failure was transient" was the first attempt
                // and it was wrong: the probe above would then stay true forever, so a
                // single corrupt blob would re-open the Keystore on the main thread at
                // every attach and re-emit the warning below into every ride file the
                // phone ever writes — about a setting, quite possibly, that the rider
                // never overrode.
                //
                // And there is little to keep. A transient refusal shows up at
                // `create`, which unwraps the master key; that path is the outer
                // `runCatching`, deletes nothing and is retried next launch. A read
                // that fails AFTER `create` succeeded is not a Keystore that is busy,
                // it is a value that is damaged, and damage does not heal.
                encrypted.edit().apply { MIGRATED_KEYS.forEach { remove(it) } }.apply()
            }.onFailure {
                // Only tidying. The values are already in plain preferences, and what is
                // left behind is unreadable to everything that remains.
                DebugLog.w(TAG) { "could not clear the encrypted copies: ${it.javaClass.simpleName}" }
            }
            if (unreadable.isNotEmpty()) {
                RideDiagnostics.warn(
                    TAG,
                    "could not decrypt ${unreadable.entries.joinToString { "${it.key} (${it.value})" }}" +
                        " while retiring the encrypted dash_config — that setting may" +
                        " have reverted to the factory default",
                    keepWhenIdle = true,
                )
            }
            DebugLog.i(TAG) { "migrated ${carried.size} value(s) out of the encrypted store" }
        }.onFailure { error ->
            // Logged, not shown: whether a rider should be told is a decision for the UI
            // layer, which knows whether a screen is even in front of them. The old Toast
            // made that call here, from a background thread, in a class that stores three
            // strings.
            //
            // Through RideDiagnostics, not DebugLog alone, because the consequence
            // outlives the debug build: the rider's saved dash quietly becomes the
            // factory default, and on release DebugLog writes nothing at all. Buffered
            // until the next ride file opens — this runs long before any connect.
            RideDiagnostics.warn(
                TAG,
                "could not read the encrypted dash_config to migrate it " +
                    "(${error.javaClass.simpleName}) — a saved SSID or password may have " +
                    "reverted to the factory default",
                keepWhenIdle = true,
            )
        }
    }

    companion object {
        private const val TAG = "DashConfig"
        private const val PREFS_NAME = "dash_config"
        private const val KEY_PREFIX   = "ssid_prefix"
        private const val KEY_SSID     = "ssid"
        private const val KEY_PASSWORD = "password"

        internal val MIGRATED_KEYS = listOf(KEY_PREFIX, KEY_SSID, KEY_PASSWORD)

        const val DEFAULT_PREFIX   = "RE_"
        const val DEFAULT_PASSWORD = "12345678"

        @Volatile private var instance: DashConfig? = null
        fun get(context: Context): DashConfig =
            instance ?: synchronized(this) {
                instance ?: DashConfig(context).also { instance = it }
            }
    }
}
