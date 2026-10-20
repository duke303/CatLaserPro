package com.catlaser.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Centre and half-width of the servo range (as with the touch joystick). */
data class SteerRanges(val panCenter: Int, val panHalf: Int, val tiltCenter: Int, val tiltHalf: Int)

/** Snapshot for the UI (updated about 10 times per second). */
data class SensorLive(
    val raw: Map<Channel, Float> = emptyMap(),
    val lux: Float? = null,
    val near: Boolean? = null,
    val shake: Float = 0f,
    val panNorm: Float = 0f,
    val tiltNorm: Float = 0f,
    val panDeg: Int? = null,
    val tiltDeg: Int? = null
)

data class SensorInfo(val kind: SensorKind, val present: Boolean, val name: String)

/**
 * Detects the phone's sensors, reads them and converts them into
 * pan/tilt angles (and additional actions). Runs entirely on the main thread.
 * Sensors are only registered while they are really needed
 * (steering active, extra function enabled or setup view open).
 */
class SensorController(context: Context) : SensorEventListener {

    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // ── Callbacks to the app ───────────────────────
    var onSteer: (pan: Int, tilt: Int) -> Unit = { _, _ -> }
    var onBrightness: (Int) -> Unit = {}
    var onAction: (SensorAction) -> Unit = {}
    var rangeProvider: () -> SteerRanges = { SteerRanges(90, 40, 90, 30) }

    // ── State for the UI ─────────────────────
    private val _live = MutableStateFlow(SensorLive())
    val live: StateFlow<SensorLive> = _live
    private val _steering = MutableStateFlow(false)
    val steering: StateFlow<Boolean> = _steering

    // ── Detection ──────────────────────────────────────
    private fun typeOf(k: SensorKind) = when (k) {
        SensorKind.ACCEL -> Sensor.TYPE_ACCELEROMETER
        SensorKind.GAME_ROTATION -> Sensor.TYPE_GAME_ROTATION_VECTOR
        SensorKind.ROTATION -> Sensor.TYPE_ROTATION_VECTOR
        SensorKind.LIGHT -> Sensor.TYPE_LIGHT
        SensorKind.PROXIMITY -> Sensor.TYPE_PROXIMITY
    }

    private fun sensorOf(k: SensorKind): Sensor? = sm.getDefaultSensor(typeOf(k))

    val infos: List<SensorInfo> = SensorKind.values().map { k ->
        val s = sensorOf(k)
        SensorInfo(k, s != null, s?.name ?: "")
    }

    fun has(k: SensorKind): Boolean = infos.first { it.kind == k }.present
    fun channelAvailable(c: Channel): Boolean = has(c.sensor)

    // ── Configuration / state ───────────────────────
    private var config = SensorConfig()
    private var foreground = false
    private var preview = false
    private val registered = mutableSetOf<SensorKind>()

    private class AxisState {
        var zero = 0f
        var zeroed = false
        var smooth = 0f
    }

    private val panState = AxisState()
    private val tiltState = AxisState()

    private val raw = HashMap<Channel, Float>()
    private val gravity = FloatArray(3)
    private var haveGravity = false
    private val rot = FloatArray(9)
    private val rot2 = FloatArray(9)
    private val ori = FloatArray(3)

    private var lux: Float? = null
    private var near: Boolean? = null
    private var shakeMag = 0f
    private var panNorm = 0f
    private var tiltNorm = 0f
    private var panDeg: Int? = null
    private var tiltDeg: Int? = null

    private var lastLiveEmit = 0L
    private var lastSend = 0L
    private var lastSentPan = -999
    private var lastSentTilt = -999
    private var lastShake = 0L
    private var lastProx = 0L
    private var lastLightSend = 0L
    private var lastLightValue = -999

    fun setConfig(c: SensorConfig) {
        val poseChanged = c.pose != config.pose
        config = c
        if (poseChanged) {
            haveGravity = false
            raw.clear()
            panState.zeroed = false
            tiltState.zeroed = false
        }
        refresh()
    }

    fun setForeground(on: Boolean) {
        foreground = on
        if (!on && _steering.value) stopSteering()
        refresh()
    }

    fun setPreview(on: Boolean) {
        preview = on
        refresh()
    }

    fun setSteering(on: Boolean) {
        if (on == _steering.value) return
        if (on) {
            panState.zeroed = false
            tiltState.zeroed = false
            panState.smooth = 0f
            tiltState.smooth = 0f
            lastSend = 0L
            lastSentPan = -999
            lastSentTilt = -999
            _steering.value = true
        } else {
            stopSteering()
        }
        refresh()
    }

    private fun stopSteering() {
        _steering.value = false
        panNorm = 0f
        tiltNorm = 0f
        panDeg = null
        tiltDeg = null
        emitLive(force = true)
    }

    /** Take the current reading of this axis as the zero point. Returns it (for saving). */
    fun zeroNow(isPan: Boolean): Float? {
        val ch = config.axis(isPan).source ?: return null
        val r = raw[ch] ?: return null
        val st = if (isPan) panState else tiltState
        st.zero = r
        st.zeroed = true
        st.smooth = 0f
        return r
    }

    fun luxNow(): Float? = lux

    fun release() {
        foreground = false
        preview = false
        _steering.value = false
        refresh()
    }

    // ── Register / unregister sensors ──────────────────────────
    private fun refresh() {
        val want = mutableSetOf<SensorKind>()
        if (foreground) {
            val c = config
            if (preview) want.addAll(SensorKind.values())
            if (_steering.value) {
                c.pan.source?.let { want.add(it.sensor) }
                c.tilt.source?.let { want.add(it.sensor) }
            }
            if (c.shakeEnabled) want.add(SensorKind.ACCEL)
            if (c.lightEnabled) want.add(SensorKind.LIGHT)
            if (c.proximityEnabled) want.add(SensorKind.PROXIMITY)
        }
        want.retainAll { has(it) }

        for (k in registered.toList()) {
            if (k !in want) {
                sensorOf(k)?.let { sm.unregisterListener(this, it) }
                registered.remove(k)
            }
        }
        for (k in want) {
            if (k !in registered) {
                val s = sensorOf(k)
                if (s != null && sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)) {
                    registered.add(k)
                }
            }
        }
        if (registered.isEmpty()) haveGravity = false
    }

    // ── Sensor events ──────────────────────────────
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccel(e.values)
            Sensor.TYPE_GAME_ROTATION_VECTOR -> onRotation(e.values, game = true)
            Sensor.TYPE_ROTATION_VECTOR -> onRotation(e.values, game = false)
            Sensor.TYPE_LIGHT -> onLight(e.values[0])
            Sensor.TYPE_PROXIMITY -> onProximity(e.values[0], e.sensor.maximumRange)
        }
    }

    private fun deg(rad: Float) = rad * 57.29578f

    private fun onAccel(v: FloatArray) {
        if (!haveGravity) {
            for (i in 0..2) gravity[i] = v[i]
            haveGravity = true
        } else {
            val a = 0.8f
            for (i in 0..2) gravity[i] = a * gravity[i] + (1f - a) * v[i]
        }
        val gx = gravity[0]
        val gy = gravity[1]
        val gz = gravity[2]

        val roll: Float
        val pitch: Float
        if (config.pose == Pose.FLAT) {
            roll = deg(atan2(-gx, hypot(gy, gz)))
            pitch = deg(atan2(-gy, hypot(gx, gz)))
        } else {
            roll = deg(atan2(-gx, gy))
            pitch = deg(atan2(gz, hypot(gx, gy)))
        }
        raw[Channel.TILT_ROLL] = roll
        raw[Channel.TILT_PITCH] = pitch

        // Shake: magnitude of the acceleration without gravity
        val lx = v[0] - gx
        val ly = v[1] - gy
        val lz = v[2] - gz
        shakeMag = sqrt(lx * lx + ly * ly + lz * lz)
        val now = SystemClock.elapsedRealtime()
        if (config.shakeEnabled && shakeMag > config.shakeThreshold && now - lastShake > 1200) {
            lastShake = now
            if (config.shakeAction != SensorAction.NONE) onAction(config.shakeAction)
        }
        step()
    }

    private fun onRotation(values: FloatArray, game: Boolean) {
        val v = if (values.size > 4) values.copyOfRange(0, 4) else values
        SensorManager.getRotationMatrixFromVector(rot, v)
        val m = if (config.pose == Pose.UPRIGHT) {
            SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, rot2)
            rot2
        } else {
            rot
        }
        SensorManager.getOrientation(m, ori)
        if (game) {
            raw[Channel.GYRO_YAW] = deg(ori[0])
            raw[Channel.GYRO_PITCH] = deg(ori[1])
            raw[Channel.GYRO_ROLL] = deg(ori[2])
        } else {
            raw[Channel.COMPASS] = deg(ori[0])
        }
        step()
    }

    private fun onLight(value: Float) {
        lux = value
        val c = config
        if (c.lightEnabled) {
            val lo = ln(c.lightDarkLux + 1f)
            val hi = ln(c.lightBrightLux + 1f)
            var t = ((ln(value + 1f) - lo) / max(0.01f, hi - lo)).coerceIn(0f, 1f)
            if (c.lightInvert) t = 1f - t
            val b = (10f + t * 245f).roundToInt().coerceIn(10, 255)
            val now = SystemClock.elapsedRealtime()
            if (abs(b - lastLightValue) >= 8 && now - lastLightSend >= 250) {
                lastLightValue = b
                lastLightSend = now
                onBrightness(b)
            }
        }
        emitLive()
    }

    private fun onProximity(value: Float, maxRange: Float) {
        val isNear = value < min(maxRange, 5f)
        val wasNear = near == true
        near = isNear
        val now = SystemClock.elapsedRealtime()
        if (config.proximityEnabled && isNear && !wasNear && now - lastProx > 800) {
            lastProx = now
            if (config.proximityAction != SensorAction.NONE) onAction(config.proximityAction)
        }
        emitLive()
    }

    // ── Conversion reading → pan/tilt ─────────────────
    private fun wrap(a: Float): Float {
        var d = a
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    private fun norm(ax: AxisConfig, st: AxisState): Float? {
        val ch = ax.source ?: return null
        val r = raw[ch] ?: return null
        if (!st.zeroed) {
            st.zero = if (config.autoZero) r else ax.savedZero
            st.zeroed = true
        }
        var d = r - st.zero
        if (ch.wraps) d = wrap(d)
        if (ax.invert) d = -d
        val dz = ax.deadzone
        d = when {
            d > dz -> d - dz
            d < -dz -> d + dz
            else -> 0f
        }
        val span = max(1f, ax.range - dz)
        val n = (d / span).coerceIn(-1f, 1f)
        st.smooth += (1f - ax.smoothing) * (n - st.smooth)
        return st.smooth
    }

    private fun step() {
        if (_steering.value) {
            val c = config
            val pn = norm(c.pan, panState)
            val tn = norm(c.tilt, tiltState)
            if (pn != null || tn != null) {
                val r = rangeProvider()
                panNorm = pn ?: 0f
                tiltNorm = tn ?: 0f
                val p = (r.panCenter + panNorm * r.panHalf).roundToInt().coerceIn(0, 180)
                val t = (r.tiltCenter + tiltNorm * r.tiltHalf).roundToInt().coerceIn(0, 180)
                panDeg = p
                tiltDeg = t
                val now = SystemClock.elapsedRealtime()
                val changed = abs(p - lastSentPan) >= 1 || abs(t - lastSentTilt) >= 1
                // When idle, resend every 1 s so the device does not fall back to its playlist.
                val keepAlive = now - lastSend >= 1000
                if ((changed && now - lastSend >= 25) || keepAlive) {
                    lastSend = now
                    lastSentPan = p
                    lastSentTilt = t
                    onSteer(p, t)
                }
            }
        }
        emitLive()
    }

    private fun emitLive(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLiveEmit < 100) return
        lastLiveEmit = now
        _live.value = SensorLive(
            raw = HashMap(raw),
            lux = lux,
            near = near,
            shake = shakeMag,
            panNorm = panNorm,
            tiltNorm = tiltNorm,
            panDeg = panDeg,
            tiltDeg = tiltDeg
        )
    }
}
