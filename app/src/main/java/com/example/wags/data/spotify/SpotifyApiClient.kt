package com.example.wags.data.spotify

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight data class for a Spotify track returned by the Web API.
 * Used in the song picker UI to display previously-played songs.
 *
 * @property spotifyUri  e.g. "spotify:track:0VjIjW4GlWMTYvCEqiYqC4"
 * @property title       Track name.
 * @property artist      Primary artist name.
 * @property durationMs  Track duration in milliseconds.
 * @property albumArt    URL to album art image (300×300), null if unavailable.
 */
data class SpotifyTrackDetail(
    val spotifyUri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val albumArt: String? = null
)

/**
 * Client for the Spotify Web API.
 *
 * Uses the access token from [SpotifyAuthManager] to:
 *  - Look up track metadata (title, artist, duration, album art).
 *  - Start playback of a specific track on the user's active device.
 */
@Singleton
class SpotifyApiClient @Inject constructor(
    private val authManager: SpotifyAuthManager
) {

    companion object {
        private const val TAG = "SpotifyApi"
        private const val BASE_URL = "https://api.spotify.com/v1"
    }

    private val client = OkHttpClient()

    /**
     * Fetch track details from the Spotify Web API.
     *
     * @param trackUri Spotify URI like "spotify:track:XXXX"
     * @return [SpotifyTrackDetail] or null if the request fails.
     */
    suspend fun getTrackDetail(trackUri: String): SpotifyTrackDetail? =
        withContext(Dispatchers.IO) {
            val token = authManager.getValidAccessToken() ?: return@withContext null
            val trackId = trackUri.removePrefix("spotify:track:")
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/tracks/$trackId")
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null

                if (!response.isSuccessful) {
                    Log.w(TAG, "getTrackDetail failed (${response.code}): $body")
                    return@withContext null
                }

                val json = JSONObject(body)
                val artists = json.getJSONArray("artists")
                val artistName = if (artists.length() > 0) {
                    artists.getJSONObject(0).getString("name")
                } else ""

                val album = json.optJSONObject("album")
                val images = album?.optJSONArray("images")
                val artUrl = if (images != null && images.length() > 0) {
                    // Pick the medium-sized image (usually index 1, ~300px)
                    val idx = if (images.length() > 1) 1 else 0
                    images.getJSONObject(idx).getString("url")
                } else null

                SpotifyTrackDetail(
                    spotifyUri = trackUri,
                    title = json.getString("name"),
                    artist = artistName,
                    durationMs = json.getLong("duration_ms"),
                    albumArt = artUrl
                )
            } catch (e: Exception) {
                Log.e(TAG, "getTrackDetail error", e)
                null
            }
        }

    /**
     * Search for a track by title and artist using the Spotify Web API.
     *
     * @param title  Track name.
     * @param artist Artist name.
     * @return The Spotify URI (e.g. "spotify:track:XXXX") of the best match, or null.
     */
    suspend fun searchTrack(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext null
        try {
            val query = "$title artist:$artist"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/search?q=$encodedQuery&type=track&limit=1")
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) {
                Log.w(TAG, "searchTrack failed (${response.code}): $body")
                return@withContext null
            }

            val json = JSONObject(body)
            val tracks = json.optJSONObject("tracks")?.optJSONArray("items")
            if (tracks != null && tracks.length() > 0) {
                tracks.getJSONObject(0).getString("uri")
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "searchTrack error", e)
            null
        }
    }

    /**
     * Start playback of a specific track on the user's active Spotify device.
     *
     * When Spotify has just been opened and no song has been played yet, the
     * Web API has no "active device" and the initial PUT returns 404. In that
     * case we fetch the available device list, pick the first one, and retry
     * with an explicit `device_id` query parameter.
     *
     * @param trackUri Spotify URI like "spotify:track:XXXX"
     * @return true if the command was accepted by Spotify.
     */
    suspend fun startPlayback(trackUri: String): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext false
        try {
            val jsonBody = JSONObject().apply {
                put("uris", org.json.JSONArray().put(trackUri))
            }

            val request = Request.Builder()
                .url("$BASE_URL/me/player/play")
                .addHeader("Authorization", "Bearer $token")
                .put(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val ok = response.isSuccessful || code == 204

            if (ok) return@withContext true

            // 404 = "No active device found" — common on first play after
            // opening Spotify. Resolve by picking an available device explicitly.
            if (code == 404) {
                Log.d(TAG, "startPlayback: no active device (404), resolving device list…")
                val deviceId = getFirstAvailableDeviceId(token)
                if (deviceId != null) {
                    Log.d(TAG, "startPlayback: retrying with device_id=$deviceId")
                    val retryRequest = Request.Builder()
                        .url("$BASE_URL/me/player/play?device_id=$deviceId")
                        .addHeader("Authorization", "Bearer $token")
                        .put(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val retryResponse = client.newCall(retryRequest).execute()
                    if (!retryResponse.isSuccessful && retryResponse.code != 204) {
                        Log.w(TAG, "startPlayback retry failed (${retryResponse.code}): ${retryResponse.body?.string()}")
                    }
                    return@withContext retryResponse.isSuccessful || retryResponse.code == 204
                } else {
                    Log.w(TAG, "startPlayback: no available devices found")
                }
            } else {
                Log.w(TAG, "startPlayback failed ($code): ${response.body?.string()}")
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "startPlayback error", e)
            false
        }
    }

    /**
     * Fetch the first available Spotify device ID from the Web API.
     * Returns null if no devices are available or the request fails.
     */
    private fun getFirstAvailableDeviceId(token: String): String? {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/me/player/devices")
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                Log.w(TAG, "getDevices failed (${response.code}): $body")
                return null
            }
            val devices = JSONObject(body).optJSONArray("devices") ?: return null
            // Prefer the device on this phone (type = "Smartphone"), fall back to any device
            var fallbackId: String? = null
            for (i in 0 until devices.length()) {
                val device = devices.getJSONObject(i)
                val id = device.getString("id")
                val type = device.optString("type", "")
                if (type.equals("Smartphone", ignoreCase = true)) return id
                if (fallbackId == null) fallbackId = id
            }
            fallbackId
        } catch (e: Exception) {
            Log.e(TAG, "getFirstAvailableDeviceId error", e)
            null
        }
    }
}
