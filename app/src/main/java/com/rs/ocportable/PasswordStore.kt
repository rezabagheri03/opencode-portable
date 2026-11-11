package com.rs.ocportable

import android.content.Context
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * One password, generated once and kept in app-private SharedPreferences.
 *
 * The engine reads `OPENCODE_PASSWORD`; without a stable value `serve` generates a random password
 * on every launch, which invalidates the WebView's session and the `auth_token` URL.
 */
object PasswordStore {
    private const val PREFS = "engine"
    private const val KEY = "password"

    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs.edit().putString(KEY, password).apply()
        return password
    }

    /** `base64("opencode:PASSWORD")`, the value the server's Basic auth decoder expects. */
    fun token(password: String): String =
        Base64.getEncoder().encodeToString("opencode:$password".toByteArray(Charsets.UTF_8))

    /** The same token, safe to place in the `?auth_token=` query parameter. */
    fun tokenUrl(password: String): String = URLEncoder.encode(token(password), "UTF-8")
}
