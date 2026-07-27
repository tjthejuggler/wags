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
            val token = authManager.getValidAccessToken()
            if (token == null) {
                Log.w(TAG, "getTrackDetail: no valid token")
                return@withContext null
            }
            val trackId = trackUri.removePrefix("spotify:track:")
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/tracks/$trackId")
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null

                if (!response.isSuccessful) {
                    Log.w(TAG, "getTrackDetail failed for $trackId (${response.code}): ${body.take(200)}")
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
     * Fetch details for MULTIPLE tracks in a single API call.
     *
     * Calls `GET /v1/tracks?ids={comma-separated-ids}` which supports up to 50
     * track IDs per request. This avoids the per-track `GET /v1/tracks/{id}`
     * calls that were triggering Spotify's 429 rate-limit when loading the
     * song picker.
     *
     * @param trackUris Spotify URIs like "spotify:track:XXXX"
     * @return Map of trackUri → [SpotifyTrackDetail] for every track found.
     */
    suspend fun getTracksDetail(trackUris: List<String>): Map<String, SpotifyTrackDetail> =
        withContext(Dispatchers.IO) {
            val token = authManager.getValidAccessToken() ?: return@withContext emptyMap()
            val ids = trackUris
                .map { it.removePrefix("spotify:track:") }
                .filter { it.isNotBlank() }
                .distinct()
            if (ids.isEmpty()) return@withContext emptyMap()

            val result = mutableMapOf<String, SpotifyTrackDetail>()
            try {
                for (chunk in ids.chunked(50)) {
                    val request = Request.Builder()
                        .url("$BASE_URL/tracks?ids=${chunk.joinToString(",")}")
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: continue
                    if (!response.isSuccessful) {
                        Log.w(TAG, "getTracksDetail failed (${response.code}): ${body.take(200)}")
                        continue
                    }
                    val tracks = JSONObject(body).optJSONArray("tracks") ?: continue
                    for (i in 0 until tracks.length()) {
                        val json = tracks.optJSONObject(i) ?: continue
                        val uri = json.optString("uri")
                        val artists = json.optJSONArray("artists")
                        val artistName = if (artists != null && artists.length() > 0) {
                            artists.getJSONObject(0).optString("name")
                        } else ""
                        val album = json.optJSONObject("album")
                        val images = album?.optJSONArray("images")
                        val artUrl = if (images != null && images.length() > 0) {
                            val idx = if (images.length() > 1) 1 else 0
                            images.getJSONObject(idx).optString("url")
                        } else null
                        if (uri.isNotBlank()) {
                            result[uri] = SpotifyTrackDetail(
                                spotifyUri = uri,
                                title = json.optString("name"),
                                artist = artistName,
                                durationMs = json.optLong("duration_ms"),
                                albumArt = artUrl
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getTracksDetail error", e)
            }
            result
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
     * Start playback of an ordered list of tracks on the user's active Spotify device.
     *
     * This replaces the active playback context with the provided track URIs.
     * Track 1 plays immediately, tracks 2+ become the upcoming queue in order.
     *
     * When Spotify has just been opened and no song has been played yet, the
     * Web API has no "active device" and the initial PUT returns 404. In that
     * case we fetch the available device list, pick the first one, and retry
     * with an explicit `device_id` query parameter.
     *
     * @param trackUris Ordered list of Spotify URIs like "spotify:track:XXXX"
     * @return true if the command was accepted by Spotify.
     */
    suspend fun startPlaybackUris(trackUris: List<String>): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext false
        if (trackUris.isEmpty()) {
            Log.w(TAG, "startPlaybackUris: empty track list")
            return@withContext false
        }
        try {
            val jsonBody = JSONObject().apply {
                val arr = org.json.JSONArray()
                trackUris.forEach { arr.put(it) }
                put("uris", arr)
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
                Log.d(TAG, "startPlaybackUris: no active device (404), resolving device list…")
                val deviceId = getFirstAvailableDeviceId(token)
                if (deviceId != null) {
                    Log.d(TAG, "startPlaybackUris: retrying with device_id=$deviceId")
                    val retryRequest = Request.Builder()
                        .url("$BASE_URL/me/player/play?device_id=$deviceId")
                        .addHeader("Authorization", "Bearer $token")
                        .put(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val retryResponse = client.newCall(retryRequest).execute()
                    if (!retryResponse.isSuccessful && retryResponse.code != 204) {
                        Log.w(TAG, "startPlaybackUris retry failed (${retryResponse.code}): ${retryResponse.body?.string()}")
                    }
                    return@withContext retryResponse.isSuccessful || retryResponse.code == 204
                } else {
                    Log.w(TAG, "startPlaybackUris: no available devices found")
                }
            } else {
                Log.w(TAG, "startPlaybackUris failed ($code): ${response.body?.string()}")
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "startPlaybackUris error", e)
            false
        }
    }

    /**
     * Start playback of a specific track on the user's active Spotify device.
     *
     * This is a convenience wrapper around [startPlaybackUris] for single-track playback.
     *
     * @param trackUri Spotify URI like "spotify:track:XXXX"
     * @return true if the command was accepted by Spotify.
     */
    suspend fun startPlayback(trackUri: String): Boolean = startPlaybackUris(listOf(trackUri))

    /**
     * Get a recommended track URI based on a seed track.
     *
     * Calls `GET /v1/recommendations?seed_tracks={trackId}&limit=5` and returns
     * a random URI from the results (to add variety). Returns null if the request
     * fails or the recommendations list is empty.
     *
     * @param seedTrackUri Spotify URI like "spotify:track:XXXX"
     * @return A recommended Spotify URI, or null on failure.
     */
    suspend fun getRecommendation(seedTrackUri: String): String? = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext null
        val trackId = seedTrackUri.removePrefix("spotify:track:")
        try {
            val request = Request.Builder()
                .url("$BASE_URL/recommendations?seed_tracks=$trackId&limit=5")
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) {
                Log.w(TAG, "getRecommendation failed (${response.code}): $body")
                return@withContext null
            }

            val tracks = JSONObject(body).optJSONArray("tracks")
            if (tracks == null || tracks.length() == 0) return@withContext null

            // Pick a random track from the results for variety
            val pick = tracks.getJSONObject((0 until tracks.length()).random())
            pick.getString("uri")
        } catch (e: Exception) {
            Log.e(TAG, "getRecommendation error", e)
            null
        }
    }

    /**
     * Add a track to the user's Spotify playback queue.
     *
     * Calls `POST /v1/me/player/queue?uri={trackUri}`.
     *
     * @param trackUri Spotify URI like "spotify:track:XXXX"
     * @return true if the command was accepted (HTTP 204).
     */
    suspend fun addToQueue(trackUri: String): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext false
        try {
            val encodedUri = java.net.URLEncoder.encode(trackUri, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/me/player/queue?uri=$encodedUri")
                .addHeader("Authorization", "Bearer $token")
                .post("".toRequestBody(null))
                .build()

            val response = client.newCall(request).execute()
            val ok = response.isSuccessful || response.code == 204
            if (!ok) {
                Log.w(TAG, "addToQueue failed (${response.code}): ${response.body?.string()}")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "addToQueue error", e)
            false
        }
    }

    /**
     * Get the current user's Spotify user ID (`GET /v1/me`).
     * Returns null on failure.
     */
    suspend fun getCurrentUserId(): String? = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext null
        try {
            val request = Request.Builder()
                .url("$BASE_URL/me")
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            if (!response.isSuccessful) {
                Log.w(TAG, "getCurrentUserId failed (${response.code}): ${body.take(200)}")
                return@withContext null
            }
            JSONObject(body).optString("id").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentUserId error", e)
            null
        }
    }

    /**
     * Find a playlist owned by [userId] whose name matches [name].
     * Pages through `GET /v1/me/playlists`. Returns the playlist ID or null.
     */
    suspend fun findPlaylistByName(userId: String, name: String): String? = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext null
        try {
            var url: String? = "$BASE_URL/me/playlists?limit=50"
            while (url != null) {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null
                if (!response.isSuccessful) {
                    Log.w(TAG, "findPlaylistByName failed (${response.code}): ${body.take(200)}")
                    return@withContext null
                }
                val json = JSONObject(body)
                val items = json.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val pl = items.getJSONObject(i)
                        if (pl.optString("name").equals(name, ignoreCase = true)) {
                            return@withContext pl.optString("id").takeIf { it.isNotBlank() }
                        }
                    }
                }
                url = json.optString("next").takeIf { it.isNotBlank() && it != "null" }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "findPlaylistByName error", e)
            null
        }
    }

    /**
     * Create a new private playlist for [userId] (`POST /v1/users/{user_id}/playlists`).
     * Returns the new playlist ID or null on failure.
     */
    suspend fun createPlaylist(userId: String, name: String, description: String): String? = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext null
        try {
            val jsonBody = JSONObject().apply {
                put("name", name)
                put("description", description)
                put("public", false)
            }
            val request = Request.Builder()
                .url("$BASE_URL/users/$userId/playlists")
                .addHeader("Authorization", "Bearer $token")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            if (!response.isSuccessful) {
                Log.w(TAG, "createPlaylist failed (${response.code}): ${body.take(200)}")
                return@withContext null
            }
            JSONObject(body).optString("id").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "createPlaylist error", e)
            null
        }
    }

    /**
     * Atomically replace ALL tracks in a playlist with [trackUris], in order
     * (`PUT /v1/playlists/{playlist_id}/tracks`). This wipes existing contents.
     * Returns true on success.
     */
    suspend fun replacePlaylistTracks(playlistId: String, trackUris: List<String>): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext false
        try {
            val arr = org.json.JSONArray()
            trackUris.forEach { arr.put(it) }
            val jsonBody = JSONObject().apply { put("uris", arr) }
            val request = Request.Builder()
                .url("$BASE_URL/playlists/$playlistId/tracks")
                .addHeader("Authorization", "Bearer $token")
                .put(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful || response.code == 201
            if (!ok) {
                Log.w(TAG, "replacePlaylistTracks failed (${response.code}): ${response.body?.string()?.take(200)}")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "replacePlaylistTracks error", e)
            false
        }
    }

    /**
     * Start playback of a playlist context (`PUT /v1/me/player/play` with context_uri).
     * Falls back to an explicit device_id on 404 (no active device).
     * Returns true if accepted.
     */
    suspend fun playContext(contextUri: String): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext false
        try {
            val jsonBody = JSONObject().apply { put("context_uri", contextUri) }
            val request = Request.Builder()
                .url("$BASE_URL/me/player/play")
                .addHeader("Authorization", "Bearer $token")
                .put(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val code = response.code
            if (response.isSuccessful || code == 204) return@withContext true
            if (code == 404) {
                val deviceId = getFirstAvailableDeviceId(token)
                if (deviceId != null) {
                    val retry = Request.Builder()
                        .url("$BASE_URL/me/player/play?device_id=$deviceId")
                        .addHeader("Authorization", "Bearer $token")
                        .put(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val retryResp = client.newCall(retry).execute()
                    return@withContext retryResp.isSuccessful || retryResp.code == 204
                }
            } else {
                Log.w(TAG, "playContext failed ($code): ${response.body?.string()?.take(200)}")
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "playContext error", e)
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
