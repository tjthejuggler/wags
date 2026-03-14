package com.example.wags.data.repository

import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches YouTube video title and channel name using the public oEmbed endpoint.
 * No API key required. Works for any standard youtube.com or youtu.be URL.
 *
 * oEmbed spec: https://oembed.com/  |  YouTube endpoint: https://www.youtube.com/oembed
 *
 * Example response (JSON):
 * {
 *   "title": "NSDR - Non Sleep Deep Rest (20 min)",
 *   "author_name": "Andrew Huberman",
 *   ...
 * }
 */
@Singleton
class YouTubeMetadataFetcher @Inject constructor() {

    data class YoutubeMetadata(val title: String, val channel: String)

    /**
     * Returns [YoutubeMetadata] if [url] is a recognisable YouTube URL and the network call
     * succeeds, otherwise returns null (caller should treat null as "not a YouTube URL" or
     * "fetch failed").
     */
    fun fetch(url: String): YoutubeMetadata? {
        if (!isYouTubeUrl(url)) return null
        return try {
            val oEmbedUrl = "https://www.youtube.com/oembed?url=${encode(url)}&format=json"
            val connection = URL(oEmbedUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout    = 8_000
            connection.requestMethod  = "GET"
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode != 200) return null

            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val title   = extractJsonString(body, "title")   ?: return null
            val channel = extractJsonString(body, "author_name") ?: return null
            YoutubeMetadata(title = title, channel = channel)
        } catch (_: Exception) {
            null
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/watch") ||
               lower.contains("youtu.be/") ||
               lower.contains("youtube.com/shorts/") ||
               lower.contains("youtube.com/embed/")
    }

    /** Minimal URL-encode — only encodes the characters that break a query param. */
    private fun encode(url: String): String =
        url.replace(" ", "%20")
           .replace("&", "%26")
           .replace("+", "%2B")

    /**
     * Extracts a JSON string value for [key] from a flat JSON object without pulling in
     * a full JSON library. Handles escaped quotes inside the value.
     *
     * e.g. extractJsonString("""{"title":"Hello \"World\""}""", "title") → Hello "World"
     */
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        val match = pattern.find(json) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "")
            .replace("\\t", "\t")
    }
}
