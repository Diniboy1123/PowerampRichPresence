package com.github.diniboy1123.poweramprichpresence

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class SpotifyHandler(private val context: Context) {

    companion object {
        const val TAG = "SpotifyHandler"
        const val SPOTIFY_TOKEN_URL = "https://open.spotify.com/get_access_token"
        const val SPOTIFY_SEARCH_URL = "https://api.spotify.com/v1/search?q=%s&type=track&limit=1"
        const val CACHE_FILE = "cache.json"
        const val MAX_CACHE_SIZE = 500
        const val PREFS_NAME = "SpotifyPrefs"
        const val TOKEN_KEY = "accessToken"
        const val TOKEN_EXPIRATION_KEY = "tokenExpirationTime"
    }

    // Cache for track URIs (TrackTitle_Artist -> TrackURI)
    private val trackCache = ConcurrentHashMap<String, String>()

    // Last known playback state
    // if we change track, we need to send a playback state change event to update the position
    private var lastPlayingStatus: Boolean? = null

    init {
        // Load cache from file
        loadTrackCache()
    }

    suspend fun handleTrackChange(trackTitle: String?, artist: String?, position: Int) {
        if (trackTitle == null || artist == null) return

        try {
            val token = getSpotifyAccessToken()

            // Check if track URI is already cached
            val cacheKey = "$trackTitle|$artist"
            val trackUri = trackCache[cacheKey] ?: searchSpotifyTrack(token, trackTitle, artist)?.also {
                // Cache the track URI for future use
                trackCache[cacheKey] = it
                saveTrackCache()
            }

            if (trackUri != null) {
                Log.i(TAG, "Track URI: $trackUri, Title: $trackTitle, Artist: $artist")
                sendSpotifyMetadataChanged(trackUri, trackTitle, artist)

                val playingStatus = lastPlayingStatus ?: true
                sendSpotifyPlaybackStateChanged(playingStatus, position)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun handlePlaybackStateChanged(playing: Boolean, position: Int) {
        try {
            if (lastPlayingStatus == playing) {
                // No change in playback state
                return
            }

            lastPlayingStatus = playing

            sendSpotifyPlaybackStateChanged(playing, position)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Get cached token from shared preferences or fetch new if expired
    private suspend fun getSpotifyAccessToken(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTime = System.currentTimeMillis()

        // Check if token is valid and return cached token if so
        val savedToken = prefs.getString(TOKEN_KEY, null)
        val savedExpirationTime = prefs.getLong(TOKEN_EXPIRATION_KEY, 0L)
        if (savedToken != null && currentTime < savedExpirationTime) {
            return savedToken
        }

        // Fetch new token if expired or missing
        val tokenResponse = fetchSpotifyToken()
        val tokenJson = JSONObject(tokenResponse)

        val accessToken = tokenJson.getString("accessToken")
        val tokenExpirationTime = tokenJson.getLong("accessTokenExpirationTimestampMs")

        // Save token and expiration time to shared preferences
        prefs.edit().apply {
            putString(TOKEN_KEY, accessToken)
            putLong(TOKEN_EXPIRATION_KEY, tokenExpirationTime)
            apply()
        }

        return accessToken
    }

    private suspend fun fetchSpotifyToken(): String {
        return withContext(Dispatchers.IO) {
            val url = URL(SPOTIFY_TOKEN_URL)
            val urlConnection = url.openConnection() as HttpURLConnection

            try {
                val stream = InputStreamReader(urlConnection.inputStream)
                val reader = BufferedReader(stream)
                val sb = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }

                sb.toString()
            } finally {
                urlConnection.disconnect()
            }
        }
    }

    private suspend fun searchSpotifyTrack(accessToken: String, trackTitle: String, artist: String): String? {
        return withContext(Dispatchers.IO) {
            val query = URLEncoder.encode("$trackTitle artist:$artist", "UTF-8")
            val url = URL(String.format(SPOTIFY_SEARCH_URL, query))
            val urlConnection = url.openConnection() as HttpURLConnection
            urlConnection.setRequestProperty("Authorization", "Bearer $accessToken")

            try {
                val stream = InputStreamReader(urlConnection.inputStream)
                val reader = BufferedReader(stream)
                val sb = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }

                val responseJson = JSONObject(sb.toString())
                Log.i(TAG, "Search response: $responseJson")

                val tracks = responseJson.getJSONObject("tracks").getJSONArray("items")

                if (tracks.length() > 0) {
                    tracks.getJSONObject(0).getString("uri")
                } else {
                    // No tracks found, returning null
                    Log.e(TAG, "No tracks found for query: $query")
                    null
                }
            } finally {
                urlConnection.disconnect()
            }
        }
    }

    @Suppress("SpellCheckingInspection")
    private fun sendSpotifyMetadataChanged(trackUri: String, trackTitle: String, artist: String) {
        Log.i(TAG, "Sending metadata changed broadcast with track: $trackTitle, artist: $artist, uri: $trackUri")
        val metadataIntent = Intent("com.spotify.music.metadatachanged").apply {
            putExtra("id", trackUri)
            putExtra("track", trackTitle)
            putExtra("artist", artist)
            putExtra("timeSent", System.currentTimeMillis())
        }
        context.sendBroadcast(metadataIntent)
    }

    @Suppress("SpellCheckingInspection")
    private fun sendSpotifyPlaybackStateChanged(isPlaying: Boolean, position: Int) {
        Log.i(TAG, "Sending playback state changed broadcast with isPlaying: $isPlaying, position: $position")
        val playbackStateIntent = Intent("com.spotify.music.playbackstatechanged").apply {
            putExtra("playing", isPlaying)
            putExtra("playbackPosition", position)
            putExtra("timeSent", System.currentTimeMillis())
        }
        context.sendBroadcast(playbackStateIntent)
    }

    // Load the track cache from a JSON file
    private fun loadTrackCache() {
        try {
            val file = File(context.filesDir, CACHE_FILE)

            if (file.exists()) {
                val jsonStr = file.readText()
                val jsonObject = JSONObject(jsonStr)
                val keys = jsonObject.keys()

                while (keys.hasNext()) {
                    val key = keys.next()
                    trackCache[key] = jsonObject.getString(key)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Save the track cache to a JSON file
    private fun saveTrackCache() {
        try {
            // Enforce cache size limit
            if (trackCache.size > MAX_CACHE_SIZE) {
                // Remove the oldest entries
                val oldestKeys = trackCache.keys.take(trackCache.size - MAX_CACHE_SIZE)
                oldestKeys.forEach { trackCache.remove(it) }
            }

            val jsonObject = JSONObject()
            for ((key, value) in trackCache) {
                jsonObject.put(key, value)
            }

            val file = File(context.filesDir, CACHE_FILE)
            file.writeText(jsonObject.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
