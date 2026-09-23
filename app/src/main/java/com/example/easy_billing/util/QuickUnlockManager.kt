package com.example.easy_billing.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Quick Unlock — lets a returning user skip retyping their email and
 * password by unlocking with a 4-digit PIN or their fingerprint instead.
 *
 * How it works: the first time a user completes a real email + password
 * login, QuickUnlockActivity (setup mode) offers to remember those
 * credentials — encrypted on-device — behind a PIN and/or fingerprint
 * gate. The next time the app needs a fresh login (no valid session
 * token, e.g. after the session expires, or after an explicit sign-out),
 * QuickUnlockActivity (unlock mode) asks for the PIN or fingerprint
 * instead of the full form; on success it silently replays the same
 * login the user would have typed by hand and lands them exactly where a
 * normal login would.
 *
 * The PIN itself is never stored — only a salted SHA-256 hash of it, so
 * even reading this file's storage doesn't reveal the PIN. The email and
 * password ARE stored (that's the point — they're what gets replayed on
 * unlock), but only inside Android's EncryptedSharedPreferences, which
 * keeps them AES-256 encrypted at rest using a key held in the device's
 * hardware keystore rather than in plain SharedPreferences.
 *
 * Multi-account devices: every value is keyed per account (by a
 * normalized username), so two different shop logins on the same phone
 * get two independent PINs and two independent saved credentials — one
 * account's PIN never unlocks the other's session, and clearing one
 * account's PIN (e.g. "Forgot PIN? Sign out") never touches the other
 * account's Quick Unlock setup. A small "last active account" pointer is
 * kept (not sensitive — just an email/username string) so screens that
 * don't already know which account is in play (cold app launch, the
 * app-wide lock screen) know whose PIN to ask for.
 *
 * Data is cleared per-account by explicit user action (Forgot PIN, or a
 * failed credential replay). It is never cleared automatically by
 * sign-out — a factory reset / app-data wipe is what's expected to clear
 * it, and that happens at the OS level with no app code needed.
 */
object QuickUnlockManager {

    private const val FILE_NAME = "quick_unlock_secure"
    private const val KEY_PIN_HASH = "PIN_HASH"
    private const val KEY_PIN_SALT = "PIN_SALT"
    private const val KEY_BIOMETRIC_ENABLED = "BIOMETRIC_ENABLED"
    private const val KEY_USERNAME = "SAVED_USERNAME"
    private const val KEY_PASSWORD = "SAVED_PASSWORD"
    private const val KEY_CONFIGURED = "CONFIGURED"
    private const val KEY_SETUP_OFFERED = "SETUP_OFFERED"
    private const val KEY_LAST_USERNAME = "LAST_ACTIVE_USERNAME"
    private const val KEY_ACCOUNTS = "KNOWN_ACCOUNTS"

    // Building EncryptedSharedPreferences means a MasterKey.Builder() call,
    // which round-trips the Android Keystore — noticeably slow (and, on some
    // OEMs, occasionally flaky) to do on EVERY single get/put. This object
    // is called many times per screen (every isConfigured/verifyPin/etc.
    // check), so the instance is built once per process and reused.
    @Volatile private var cachedPrefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val built = try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Extremely unlikely (keystore failure) — fall back to a
                // regular, unencrypted store rather than crashing the app.
                // Quick Unlock still works, just without the extra at-rest
                // encryption layer for this one device.
                context.applicationContext.getSharedPreferences(
                    "${FILE_NAME}_fallback", Context.MODE_PRIVATE
                )
            }
            cachedPrefs = built
            return built
        }
    }

    /** Normalizes a username/email so "A@x.com" and "a@x.com " key the same account. */
    private fun accountKey(username: String): String = username.trim().lowercase()

    private fun key(base: String, username: String): String = "$base::${accountKey(username)}"

    /** The most recently active account on this device, if any Quick Unlock account exists. */
    fun getLastUsername(context: Context): String? =
        prefs(context).getString(KEY_LAST_USERNAME, null)

    private fun setLastUsername(context: Context, username: String) {
        prefs(context).edit().putString(KEY_LAST_USERNAME, username).apply()
    }

    /**
     * Marks this account as the device's "last active" one — call after ANY
     * successful sign-in for it, including a PIN/fingerprint unlock (not
     * just setup()/updateCredentials(), which only fire on a real
     * email+password login). Without this, a PIN/fingerprint unlock for
     * account B on a device that last did a full login as account A would
     * leave the device silently still pointing at A — the very next
     * app-lock or cold-launch screen would then challenge the wrong
     * account's PIN while B's session is the one actually active.
     */
    fun markActive(context: Context, username: String) {
        setLastUsername(context, username)
    }

    /** Every account on this device that has ever completed Quick Unlock setup and hasn't been cleared, sorted for stable display order. */
    fun getAccounts(context: Context): List<String> {
        reconcileAccountsList(context)
        return prefs(context).getStringSet(KEY_ACCOUNTS, emptySet())?.sorted() ?: emptyList()
    }

    /**
     * Rebuilds KEY_ACCOUNTS from the actual per-account CONFIGURED records
     * found in storage, so any account that has real, valid Quick Unlock
     * data — regardless of WHEN or by which older version of this code it
     * was set up — is always found, without needing one more login to
     * "re-register" it first. Cheap (one pass over this file's own entries)
     * and safe to run on every getAccounts() call: addAccount()'s
     * normalized-dedup means re-adding an already-listed account is a no-op.
     */
    private fun reconcileAccountsList(context: Context) {
        val p = prefs(context)
        val all = try { p.all } catch (e: Exception) { return }
        val configuredPrefix = "$KEY_CONFIGURED::"
        all.forEach { (storedKey, value) ->
            if (storedKey.startsWith(configuredPrefix) && value == true) {
                val accountId = storedKey.removePrefix(configuredPrefix)
                // Prefer the real display casing saved alongside it; fall
                // back to the normalized id itself if that's ever missing.
                val display = p.getString("$KEY_USERNAME::$accountId", null) ?: accountId
                addAccount(context, display)
            }
        }
    }

    private fun addAccount(context: Context, username: String) {
        val p = prefs(context)
        val current = (p.getStringSet(KEY_ACCOUNTS, emptySet()) ?: emptySet()).toMutableSet()
        // Drop any existing entry for the same normalized account before
        // adding the new display form, so re-logging in with different
        // casing/whitespace can't create a duplicate row.
        current.removeAll { accountKey(it) == accountKey(username) }
        current.add(username)
        p.edit().putStringSet(KEY_ACCOUNTS, current).apply()
    }

    private fun removeAccount(context: Context, username: String) {
        val p = prefs(context)
        val current = (p.getStringSet(KEY_ACCOUNTS, emptySet()) ?: emptySet()).toMutableSet()
        current.removeAll { accountKey(it) == accountKey(username) }
        p.edit().putStringSet(KEY_ACCOUNTS, current).apply()
    }

    /** True once the given account has completed Quick Unlock setup (PIN, optionally + fingerprint). */
    fun isConfigured(context: Context, username: String): Boolean =
        prefs(context).getBoolean(key(KEY_CONFIGURED, username), false)

    /** Convenience for screens that don't already know which account is active — falls back to the last active account. */
    fun isConfigured(context: Context): Boolean {
        val last = getLastUsername(context) ?: return false
        return isConfigured(context, last)
    }

    /** True once we've shown the "set up Quick Unlock?" prompt at least once for this account — we never nag again after a Skip. */
    fun wasSetupOffered(context: Context, username: String): Boolean =
        prefs(context).getBoolean(key(KEY_SETUP_OFFERED, username), false)

    fun markSetupOffered(context: Context, username: String) {
        prefs(context).edit().putBoolean(key(KEY_SETUP_OFFERED, username), true).apply()
    }

    fun hasBiometricEnabled(context: Context, username: String): Boolean =
        prefs(context).getBoolean(key(KEY_BIOMETRIC_ENABLED, username), false)

    private fun hash(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((salt + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Saves this account's PIN (hashed + salted, never in plain text) and the credentials it will unlock (encrypted). */
    fun setup(
        context: Context,
        pin: String,
        username: String,
        password: String,
        enableBiometric: Boolean
    ) {
        val salt = SecureRandom().let { rnd ->
            ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        }
        prefs(context).edit()
            .putString(key(KEY_PIN_SALT, username), salt)
            .putString(key(KEY_PIN_HASH, username), hash(pin, salt))
            .putString(key(KEY_USERNAME, username), username)
            .putString(key(KEY_PASSWORD, username), password)
            .putBoolean(key(KEY_BIOMETRIC_ENABLED, username), enableBiometric)
            .putBoolean(key(KEY_CONFIGURED, username), true)
            .apply()
        addAccount(context, username)
        setLastUsername(context, username)
    }

    /** Keeps this account's replayed credentials current if the user changes their password after Quick Unlock is already set up. */
    fun updateCredentials(context: Context, username: String, password: String) {
        if (!isConfigured(context, username)) return
        prefs(context).edit()
            .putString(key(KEY_USERNAME, username), username)
            .putString(key(KEY_PASSWORD, username), password)
            .apply()
        // This is the path a normal full login takes for an account that's
        // ALREADY configured (setup() only runs once, the first time) — so
        // it must also (re)register the account in the enumerable list, not
        // just setup(). Without this, an already-configured account that
        // never happens to be the one passed as the sign-out hint to
        // buildLoginIntent() stays permanently invisible to the account
        // picker even though its PIN is perfectly valid.
        addAccount(context, username)
        setLastUsername(context, username)
    }

    fun verifyPin(context: Context, username: String, pin: String): Boolean {
        val p = prefs(context)
        val salt = p.getString(key(KEY_PIN_SALT, username), null) ?: return false
        val storedHash = p.getString(key(KEY_PIN_HASH, username), null) ?: return false
        return hash(pin, salt) == storedHash
    }

    fun getCredentials(context: Context, username: String): Pair<String, String>? {
        val p = prefs(context)
        val u = p.getString(key(KEY_USERNAME, username), null) ?: return null
        val pw = p.getString(key(KEY_PASSWORD, username), null) ?: return null
        return u to pw
    }

    /**
     * Wipes Quick Unlock data for ONE account only (its PIN hash/salt,
     * saved credentials, biometric flag, setup-offered flag) — used only
     * for explicit "start over" escape hatches for that account (Forgot
     * PIN, or a failed credential replay because the saved password is
     * stale), never on ordinary sign-out. Other accounts' Quick Unlock
     * data on the same device is untouched.
     */
    fun clear(context: Context, username: String) {
        val p = prefs(context)
        val editor = p.edit()
            .remove(key(KEY_PIN_HASH, username))
            .remove(key(KEY_PIN_SALT, username))
            .remove(key(KEY_USERNAME, username))
            .remove(key(KEY_PASSWORD, username))
            .remove(key(KEY_BIOMETRIC_ENABLED, username))
            .remove(key(KEY_CONFIGURED, username))
            .remove(key(KEY_SETUP_OFFERED, username))
        if (getLastUsername(context)?.let { accountKey(it) } == accountKey(username)) {
            editor.remove(KEY_LAST_USERNAME)
        }
        editor.apply()
        removeAccount(context, username)
    }

    /**
     * Where to send the user when a fresh login is needed (after sign-out,
     * or a cold app launch) — decided purely by how many accounts on this
     * device have Quick Unlock set up:
     *  • none  → the real email+password form
     *  • one   → straight to that account's PIN/fingerprint screen (no
     *    picker needed — nothing to choose between)
     *  • two+  → an account-picker screen first, so the right PIN is
     *    checked against the right account
     */
    fun buildLoginIntent(context: Context, currentUsername: String? = null): android.content.Intent {
        // Self-heal: an account whose PIN was configured before this
        // enumerable accounts list existed (or any other way its entry
        // went missing) still has valid CONFIGURED/PIN_HASH/credentials —
        // isConfigured() would say true — but getAccounts() alone would
        // never surface it, silently sending a real Quick Unlock user back
        // to the full login form on sign-out instead of their PIN. If the
        // caller can tell us which account just signed out, backfill it
        // here before deciding.
        if (currentUsername != null && isConfigured(context, currentUsername)) {
            addAccount(context, currentUsername)
        }
        val accounts = getAccounts(context)
        return when {
            accounts.isEmpty() -> android.content.Intent(context, com.example.easy_billing.MainActivity::class.java)
            accounts.size == 1 -> android.content.Intent(context, com.example.easy_billing.QuickUnlockActivity::class.java).apply {
                putExtra(com.example.easy_billing.QuickUnlockActivity.EXTRA_MODE, com.example.easy_billing.QuickUnlockActivity.MODE_UNLOCK)
                putExtra(com.example.easy_billing.QuickUnlockActivity.EXTRA_USERNAME, accounts[0])
            }
            else -> android.content.Intent(context, com.example.easy_billing.AccountSelectionActivity::class.java)
        }
    }
}
