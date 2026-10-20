package com.catlaser.app.network

import org.json.JSONObject

/**
 * Complete representation of the JSON the ESP32 serves at /status
 * (see handleStatus() in the sketch). All fields of the web UI are included.
 */
data class CatLaserStatus(
    // Position / motion
    val pan: Int = 90,
    val tilt: Int = 90,
    val velPan: Int = 0,
    val velTilt: Int = 0,
    val mode: Int = 0,
    // Laser
    val laserRequested: Boolean = false,
    val laserOn: Boolean = false,
    val gate: String = "",
    val safeGate: Boolean = true,
    val laserBrightness: Int = 255,
    val laserDimOnSprint: Boolean = true,
    val calibrationMode: Boolean = false,
    // Energy
    val energy: Int = 50,
    val energyAuto: Boolean = true,
    // Motion Engine
    val maxVel: Int = 220,
    val maxAccel: Int = 900,
    val maxDecel: Int = 1400,
    val sprintVel: Int = 600,
    val velRatioTilt: Float = 1f,
    val damping: Float = 1f,
    // Noise / Hotzone
    val noise: Float = 0.08f,
    val hotzoneEnabled: Boolean = true,
    val hotzoneWeight: Int = 60,
    val hotzoneSize: Float = 0.45f,
    // Morph / Mixer
    val morphEnabled: Boolean = true,
    val morphDuration: Float = 2.5f,
    val morphing: Boolean = false,
    val mixEnabled: Boolean = false,
    val mixPattern: Int = 2,
    val mixAmount: Float = 0.25f,
    // Lissajous
    val lissA: Int = 2,
    val lissB: Int = 3,
    val lissPhase: Float = 1.57f,
    val lissAmpX: Float = 1f,
    val lissAmpY: Float = 1f,
    val lissRotation: Float = 0f,
    val lissDrift: Float = 0f,
    val lissBreath: Float = 0f,
    val lissSpeed: Float = 0.6f,
    val lissDir: Int = 1,
    // Cat Mode
    val catState: Int = 0,
    val sprint: Boolean = false,
    // Servo range / calibration
    val panCenter: Int = 90,
    val tiltCenter: Int = 90,
    val panMinR: Int = -40,
    val panMaxR: Int = 40,
    val tiltMinR: Int = -30,
    val tiltMaxR: Int = 30,
    // Playlist
    val playlistActive: Boolean = true,
    val playlistShuffle: Boolean = false,
    val patternDuration: Int = 30,
    val patternPause: Int = 3,
    val patternRemain: Int = 0,
    val inPatternPause: Boolean = false,
    val playlistEnabled: List<Boolean> = emptyList(),
    // Time control
    val ntpEnabled: Boolean = false,
    val ntpSynced: Boolean = false,
    val ntpStart: Int = 9,
    val ntpEnd: Int = 22,
    val sessionDuration: Int = 0,
    val sessionBreak: Int = 10,
    val sessionBlocked: Boolean = false,
    // Connection
    val remoteActive: Boolean = false,
    val wifiConnected: Boolean = false
) {
    companion object {
        /** Pattern names in exactly the order used by the device (patternNames in the sketch). */
        val PATTERN_NAMES = listOf(
            "Random", "Crazy", "Circle", "Manual",
            "Zigzag", "Figure 8", "Spiral", "Pendulum",
            "Corner Jumps", "Stalk+Sprint", "Lissajous", "Cat Mode"
        )
        const val MANUAL_MODE = 3
        const val CAT_MODE = 11

        /** Patterns that can be blended in by the mixer (as in the web UI). */
        val MIX_PATTERNS = listOf(2, 5, 6, 7, 10)

        val CAT_STATE_NAMES = listOf("Lurk", "Watch", "Pounce", "Pause", "Flee")

        fun fromJson(json: String): CatLaserStatus {
            val o = JSONObject(json)
            fun b(key: String, def: Boolean = false) =
                if (o.has(key)) o.optInt(key, if (def) 1 else 0) == 1 else def
            fun f(key: String, def: Float) = o.optDouble(key, def.toDouble()).toFloat()

            val plList = mutableListOf<Boolean>()
            if (o.has("plList")) {
                val arr = o.getJSONArray("plList")
                for (i in 0 until arr.length()) plList.add(arr.optInt(i) == 1)
            }

            return CatLaserStatus(
                pan = o.optInt("pan", 90),
                tilt = o.optInt("tilt", 90),
                velPan = o.optInt("velP", 0),
                velTilt = o.optInt("velT", 0),
                mode = o.optInt("mode", 0),
                laserRequested = b("laser"),
                laserOn = b("laserOn"),
                gate = o.optString("gate", ""),
                safeGate = b("safeGate", true),
                laserBrightness = o.optInt("lBright", 255),
                laserDimOnSprint = b("lDim", true),
                calibrationMode = b("cal"),
                energy = o.optInt("energy", 50),
                energyAuto = b("enAuto", true),
                maxVel = o.optInt("mVel", 220),
                maxAccel = o.optInt("mAcc", 900),
                maxDecel = o.optInt("mDec", 1400),
                sprintVel = o.optInt("sVel", 600),
                velRatioTilt = f("vrT", 1f),
                damping = f("damp", 1f),
                noise = f("noise", 0.08f),
                hotzoneEnabled = b("hzEn", true),
                hotzoneWeight = o.optInt("hzW", 60),
                hotzoneSize = f("hzS", 0.45f),
                morphEnabled = b("mrpEn", true),
                morphDuration = f("mrpD", 2.5f),
                morphing = b("morphing"),
                mixEnabled = b("mixEn"),
                mixPattern = o.optInt("mixP", 2),
                mixAmount = f("mixA", 0.25f),
                lissA = o.optInt("lA", 2),
                lissB = o.optInt("lB", 3),
                lissPhase = f("lP", 1.57f),
                lissAmpX = f("lAX", 1f),
                lissAmpY = f("lAY", 1f),
                lissRotation = f("lR", 0f),
                lissDrift = f("lD", 0f),
                lissBreath = f("lBr", 0f),
                lissSpeed = f("lS", 0.6f),
                lissDir = if (o.optInt("lDir", 1) >= 0) 1 else -1,
                catState = o.optInt("catState", 0),
                sprint = b("sprint"),
                panCenter = o.optInt("panC", 90),
                tiltCenter = o.optInt("tiltC", 90),
                panMinR = o.optInt("panMinR", -40),
                panMaxR = o.optInt("panMaxR", 40),
                tiltMinR = o.optInt("tiltMinR", -30),
                tiltMaxR = o.optInt("tiltMaxR", 30),
                playlistActive = b("plActive", true),
                playlistShuffle = b("plShuffle"),
                patternDuration = o.optInt("plDur", 30),
                patternPause = o.optInt("plPause", 3),
                patternRemain = o.optInt("plRemain", 0),
                inPatternPause = b("plInPause"),
                playlistEnabled = plList,
                ntpEnabled = b("ntpEn"),
                ntpSynced = b("ntpSynced"),
                ntpStart = o.optInt("ntpS", 9),
                ntpEnd = o.optInt("ntpE", 22),
                sessionDuration = o.optInt("sessD", 0),
                sessionBreak = o.optInt("sessB", 10),
                sessionBlocked = b("sessBlk"),
                remoteActive = b("remote"),
                wifiConnected = b("wifi")
            )
        }
    }

    fun patternName(): String = PATTERN_NAMES.getOrElse(mode) { "?" }
    fun catStateName(): String = CAT_STATE_NAMES.getOrElse(catState) { "" }
}
