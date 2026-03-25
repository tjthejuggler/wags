package com.example.wags.data.spotify

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Manages Spotify OAuth 2.0 PKCE authentication flow.
 *
 * Flow:
 *  1. User taps "Connect Spotify" → [buildLoginIntent] opens browser.
 *  2. Spotify redirects back to `wags://spotify-callback` → [handleRedirect].
 *  3. We exchange the auth code for access + refresh tokens.
 *  4. Tokens are stored in SharedPreferences and auto-refreshed when expired.
 */
@Singleton
class SpotifyAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("spotify_prefs") private val prefs: SharedPreferences
) {

    companion object {
        private const val TAG = "SpotifyAuth"
        const val CLIENT_ID = "0222e13813374d96886a1bd19e431bb2"
        const val REDIRECT_URI = "wags://spotify-callback"
        private const val SCOPES = "user-modify-playback-state user-read-playback-state"

        // SharedPreferences keys
        private const val KEY_ACCESS_TOKEN = "spotify_access_token"
        private const val KEY_REFRESH_TOKEN = "spotify_refresh_token"
        private const val KEY_EXPIRES_AT = "spotify_expires_at"
        private const val KEY_CODE_VERIFIER = "spotify_code_verifier"
    }

    private val client = OkHttpClient()

    private val _isConnected = MutableStateFlow(prefs.getString(KEY_ACCESS_TOKEN, null) != null)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // ── PKCE helpers ─────────────────────────────────────────────────────────

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // ── Login flow ───────────────────────────────────────────────────────────

    /**
     * Build an Intent that opens the Spotify authorization page in the browser.
     * The PKCE code verifier is saved to prefs so it survives the redirect.
     */
    fun buildLoginIntent(): Intent {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        prefs.edit().putString(KEY_CODE_VERIFIER, verifier).apply()

        val authUri = Uri.parse("https://accounts.spotify.com/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .build()

        return Intent(Intent.ACTION_VIEW, authUri)
    }

    /**
     * Handle the redirect URI after the user authorizes in the browser.
     * Extracts the auth code and exchanges it for tokens.
     *
     * @return true if tokens were successfully obtained.
     */
    suspend fun handleRedirect(uri: Uri): Boolean {
        val code = uri.getQueryParameter("code") ?: run {
            Log.w(TAG, "No auth code in redirect URI: $uri")
            return false
        }
        val verifier = prefs.getString(KEY_CODE_VERIFIER, null) ?: run {
            Log.w(TAG, "No code verifier found — login flow was not started properly")
            return false
        }
        return exchangeCodeForTokens(code, verifier)
    }

    // ── Token exchange & refresh ─────────────────────────────────────────────

    private suspend fun exchangeCodeForTokens(code: String, verifier: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", REDIRECT_URI)
                    .add("code_verifier", verifier)
                    .build()

                val request = Request.Builder()
                    .url("https://accounts.spotify.com/api/token")
                    .post(body)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                val response = client.newCall(request).execute()
                val json = response.body?.string() ?: return@withContext false

                if (!response.isSuccessful) {
                    Log.e(TAG, "Token exchange failed (${response.code}): $json")
                    return@withContext false
                }

                saveTokens(JSONObject(json))
                true
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange error", e)
                false
            }
        }

    /**
     * Refresh the access token using the stored refresh token.
     * Called automatically by [getValidAccessToken] when the token is expired.
     */
    private suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return@withContext false
        try {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(body)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val response = client.newCall(request).execute()
            val json = response.body?.string() ?: return@withContext false

            if (!response.isSuccessful) {
                Log.e(TAG, "Token refresh failed (${response.code}): $json")
                // If refresh token is revoked, clear everything
                if (response.code == 400 || response.code == 401) {
                    disconnect()
                }
                return@withContext false
            }

            saveTokens(JSONObject(json))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            false
        }
    }

    private fun saveTokens(json: JSONObject) {
        val accessToken = json.getString("access_token")
        val expiresIn = json.getInt("expires_in")
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L) - 60_000L // 1 min buffer

        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()

        // Refresh token may or may not be present in refresh responses
        if (json.has("refresh_token")) {
            prefs.edit().putString(KEY_REFRESH_TOKEN, json.getString("refresh_token")).apply()
        }

        _isConnected.value = true
        Log.d(TAG, "Tokens saved, expires at $expiresAt")
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns a valid access token, refreshing if necessary.
     * Returns null if the user is not connected or refresh fails.
     */
    suspend fun getValidAccessToken(): String? {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)

        return if (System.currentTimeMillis() < expiresAt) {
            token
        } else {
            if (refreshAccessToken()) {
                prefs.getString(KEY_ACCESS_TOKEN, null)
            } else {
                null
            }
        }
    }

    /**
     * Disconnect the Spotify account — clears all stored tokens.
     */
    fun disconnect() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_CODE_VERIFIER)
            .apply()
        _isConnected.value = false
    }
}
