package com.catlaser.app.sensors

import org.json.JSONObject

/** Sensor kinds the app can use. */
enum class SensorKind(val label: String) {
    ACCEL("Accelerometer"),
    GAME_ROTATION("Orientation sensor (gyroscope)"),
    ROTATION("Compass orientation sensor"),
    LIGHT("Light sensor"),
    PROXIMITY("Proximity sensor")
}

/**
 * Measured quantities that can be assigned to an axis (pan/tilt).
 * All values are angles in degrees. [wraps] = angle wraps around at ±180°.
 */
enum class Channel(val label: String, val sensor: SensorKind, val wraps: Boolean) {
    TILT_ROLL("Tilt left/right", SensorKind.ACCEL, false),
    TILT_PITCH("Tilt forward/back", SensorKind.ACCEL, false),
    GYRO_YAW("Turn left/right (gyro)", SensorKind.GAME_ROTATION, true),
    GYRO_PITCH("Pitch (gyro)", SensorKind.GAME_ROTATION, false),
    GYRO_ROLL("Roll (gyro)", SensorKind.GAME_ROTATION, true),
    COMPASS("Compass heading", SensorKind.ROTATION, true)
}

enum class Pose(val label: String) {
    FLAT("Flat (display up)"),
    UPRIGHT("Upright (display facing you)")
}

enum class SensorAction(val label: String) {
    NONE("None"),
    LASER_TOGGLE("Laser on/off"),
    NEXT_PATTERN("Next pattern")
}

/** Settings of one steering axis. */
data class AxisConfig(
    val source: Channel? = null,
    /** Degrees of tilt/rotation for full deflection (edge of the servo range). */
    val range: Float = 30f,
    val deadzone: Float = 2f,
    /** 0 = unfiltered, 0.9 = very sluggish. */
    val smoothing: Float = 0.5f,
    val invert: Boolean = false,
    /** Stored zero point (calibration), used when "auto zero" is off. */
    val savedZero: Float = 0f
)

data class SensorConfig(
    val pose: Pose = Pose.FLAT,
    /** true = steer only while the button is held (like button A on the M5Stick). */
    val holdToSteer: Boolean = true,
    /** When steering starts, take the current pose as the centre. */
    val autoZero: Boolean = true,
    val pan: AxisConfig = AxisConfig(source = Channel.TILT_ROLL),
    val tilt: AxisConfig = AxisConfig(source = Channel.TILT_PITCH),

    val lightEnabled: Boolean = false,
    val lightDarkLux: Float = 5f,
    val lightBrightLux: Float = 500f,
    val lightInvert: Boolean = false,

    val proximityEnabled: Boolean = false,
    val proximityAction: SensorAction = SensorAction.LASER_TOGGLE,

    val shakeEnabled: Boolean = false,
    val shakeThreshold: Float = 18f,
    val shakeAction: SensorAction = SensorAction.NEXT_PATTERN
) {
    fun withAxis(isPan: Boolean, f: (AxisConfig) -> AxisConfig): SensorConfig =
        if (isPan) copy(pan = f(pan)) else copy(tilt = f(tilt))

    fun axis(isPan: Boolean): AxisConfig = if (isPan) pan else tilt

    fun toJson(): String {
        fun axisJson(a: AxisConfig) = JSONObject()
            .put("source", a.source?.name ?: "OFF")
            .put("range", a.range.toDouble())
            .put("deadzone", a.deadzone.toDouble())
            .put("smoothing", a.smoothing.toDouble())
            .put("invert", a.invert)
            .put("savedZero", a.savedZero.toDouble())
        return JSONObject()
            .put("pose", pose.name)
            .put("holdToSteer", holdToSteer)
            .put("autoZero", autoZero)
            .put("pan", axisJson(pan))
            .put("tilt", axisJson(tilt))
            .put("lightEnabled", lightEnabled)
            .put("lightDarkLux", lightDarkLux.toDouble())
            .put("lightBrightLux", lightBrightLux.toDouble())
            .put("lightInvert", lightInvert)
            .put("proximityEnabled", proximityEnabled)
            .put("proximityAction", proximityAction.name)
            .put("shakeEnabled", shakeEnabled)
            .put("shakeThreshold", shakeThreshold.toDouble())
            .put("shakeAction", shakeAction.name)
            .toString()
    }

    companion object {
        private inline fun <reified T : Enum<T>> parseEnum(s: String?, def: T): T =
            try { if (s == null) def else enumValueOf<T>(s) } catch (_: Exception) { def }

        private fun parseAxis(o: JSONObject?, def: AxisConfig): AxisConfig {
            if (o == null) return def
            val src = o.optString("source", "")
            return AxisConfig(
                source = Channel.values().firstOrNull { it.name == src },
                range = o.optDouble("range", def.range.toDouble()).toFloat().coerceIn(5f, 90f),
                deadzone = o.optDouble("deadzone", def.deadzone.toDouble()).toFloat().coerceIn(0f, 15f),
                smoothing = o.optDouble("smoothing", def.smoothing.toDouble()).toFloat().coerceIn(0f, 0.9f),
                invert = o.optBoolean("invert", def.invert),
                savedZero = o.optDouble("savedZero", 0.0).toFloat()
            )
        }

        fun fromJson(json: String?): SensorConfig {
            val d = SensorConfig()
            if (json.isNullOrBlank()) return d
            return try {
                val o = JSONObject(json)
                SensorConfig(
                    pose = parseEnum(o.optString("pose"), d.pose),
                    holdToSteer = o.optBoolean("holdToSteer", d.holdToSteer),
                    autoZero = o.optBoolean("autoZero", d.autoZero),
                    pan = parseAxis(o.optJSONObject("pan"), d.pan),
                    tilt = parseAxis(o.optJSONObject("tilt"), d.tilt),
                    lightEnabled = o.optBoolean("lightEnabled", d.lightEnabled),
                    lightDarkLux = o.optDouble("lightDarkLux", d.lightDarkLux.toDouble()).toFloat(),
                    lightBrightLux = o.optDouble("lightBrightLux", d.lightBrightLux.toDouble()).toFloat(),
                    lightInvert = o.optBoolean("lightInvert", d.lightInvert),
                    proximityEnabled = o.optBoolean("proximityEnabled", d.proximityEnabled),
                    proximityAction = parseEnum(o.optString("proximityAction"), d.proximityAction),
                    shakeEnabled = o.optBoolean("shakeEnabled", d.shakeEnabled),
                    shakeThreshold = o.optDouble("shakeThreshold", d.shakeThreshold.toDouble()).toFloat()
                        .coerceIn(8f, 40f),
                    shakeAction = parseEnum(o.optString("shakeAction"), d.shakeAction)
                )
            } catch (_: Exception) {
                d
            }
        }
    }
}
