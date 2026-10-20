package com.catlaser.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the HTTP endpoints of the CatLaser ESP32.
 * hostProvider always returns the currently stored host (hostname
 * or IP), so a change of the setting takes effect immediately.
 *
 * Note: "catlaser.local" (mDNS) is NOT resolved on many Android devices.
 * If in doubt, enter the IP address under Setup.
 */
class CatLaserApi(private val hostProvider: () -> String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String {
        val h = hostProvider().trim()
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
    }

    /** GET on path (e.g. "/status" or "/set?laser=1"), returns the body. */
    suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl() + path).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    /** Builds "/endpoint?k=v&k2=v2". Numbers are formatted locale-independently (dot). */
    fun url(endpoint: String, vararg params: Pair<String, Any>): String {
        if (params.isEmpty()) return endpoint
        return endpoint + "?" + params.joinToString("&") { (k, v) ->
            val s = when (v) {
                is Float -> String.format(Locale.US, "%.3f", v)
                is Double -> String.format(Locale.US, "%.3f", v)
                is Boolean -> if (v) "1" else "0"
                else -> v.toString()
            }
            "$k=$s"
        }
    }

    suspend fun getStatus(): CatLaserStatus = CatLaserStatus.fromJson(get("/status"))

    // ── Laser ──────────────────────────────────────────
    suspend fun setLaser(on: Boolean, save: Boolean = true) =
        get(url("/set", "laser" to on) + if (save) "&save=1" else "")

    // ── Patterns / playlist ──────────────────────────────
    suspend fun setMode(mode: Int) = get(url("/mode", "m" to mode))
    suspend fun applyPreset(name: String) = get(url("/preset", "mode" to name))
    suspend fun playlistNext() = get("/playlist?next=1")
    suspend fun playlistSetActive(active: Boolean) = get(url("/playlist", "active" to active))
    suspend fun playlistSetShuffle(shuffle: Boolean) =
        get(url("/playlist", "shuffle" to shuffle) + "&save=1")
    suspend fun playlistToggle(index: Int) = get(url("/playlist", "toggle" to index) + "&save=1")

    // ── Calibration / live / manual ──────────────────
    suspend fun calCommand(cmd: String) = get(url("/cal", "cmd" to cmd))
    suspend fun liveMode(on: Boolean) = get(url("/live", "on" to on))
    suspend fun manual(pan: Int, tilt: Int) = get(url("/manual", "pan" to pan, "tilt" to tilt))
}
