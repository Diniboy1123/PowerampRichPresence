package com.github.diniboy1123.poweramprichpresence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Suppress("SpellCheckingInspection")
class PowerampReceiver : BroadcastReceiver(), CoroutineScope by CoroutineScope(Dispatchers.IO) {

    companion object {
        const val TAG = "PowerampReceiver"
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        Log.e("PowerampReceiver", "Received intent: $intent")
        if ("com.maxmpz.audioplayer.TRACK_CHANGED_EXPLICIT" == intent.action) {
            val extras = intent.extras
            if (extras != null) {
                val trackTitle = extras.getString("title")
                val artist = extras.getString("artist")
                val position = extras.getInt("pos", 0) * 1000

                Log.i(TAG, "Track: $trackTitle, Artist: $artist, Position: $position")

                if (isConnectedToInternet(context)) {
                    // we handle the track change in a coroutine to not block the main thread
                    GlobalScope.launch {
                        SpotifyHandler(context).handleTrackChange(trackTitle, artist, position)
                    }
                }
            }
        } else if ("com.maxmpz.audioplayer.STATUS_CHANGED_EXPLICIT" == intent.action) {
            val extras = intent.extras
            if (extras != null) {
                val isPlaying = !extras.getBoolean("paused", true)
                val position = extras.getInt("pos", 0) * 1000
                Log.w("PowerampReceiver", "Playing: $isPlaying, Position: $position")

                if (isConnectedToInternet(context)) {
                    // we handle the playback state change in a coroutine to not block the main thread
                    GlobalScope.launch {
                        SpotifyHandler(context).handlePlaybackStateChanged(isPlaying, position)
                    }
                }
            }
        }
    }

    private fun isConnectedToInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val networkCapabilities = cm.getNetworkCapabilities(network)
        return networkCapabilities != null && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
