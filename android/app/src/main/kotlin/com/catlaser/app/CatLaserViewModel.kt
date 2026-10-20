package com.catlaser.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.catlaser.app.data.SettingsRepository
import com.catlaser.app.network.CatLaserApi
import com.catlaser.app.network.CatLaserStatus
import com.catlaser.app.network.UdpController
import com.catlaser.app.sensors.SensorAction
import com.catlaser.app.sensors.SensorConfig
import com.catlaser.app.sensors.SensorController
import com.catlaser.app.sensors.SensorLive
import com.catlaser.app.sensors.SteerRanges
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Central state holder. Polls /status and provides all actions of the
 * web UI. Sliders send debounced (like the web UI, 200 ms),
 * switches/buttons send immediately.
 */
class CatLaserViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)

    private var currentHost: String = SettingsRepository.DEFAULT_HOST
    private var currentUdpPort: Int = SettingsRepository.DEFAULT_UDP_PORT

    val api = CatLaserApi { currentHost }
    private val udp = UdpController { currentUdpPort }

    private val _status = MutableStateFlow<CatLaserStatus?>(null)
    val status: StateFlow<CatLaserStatus?> = _status

    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected

    private val _hostState = MutableStateFlow(SettingsRepository.DEFAULT_HOST)
    val hostState: StateFlow<String> = _hostState

    private val _udpPortState = MutableStateFlow(SettingsRepository.DEFAULT_UDP_PORT)
    val udpPortState: StateFlow<Int> = _udpPortState

    private val _flipX = MutableStateFlow(false)
    val flipX: StateFlow<Boolean> = _flipX
    private val _flipY = MutableStateFlow(false)
    val flipY: StateFlow<Boolean> = _flipY

    /** Short message ("Saved" etc.), shown by the UI as a snackbar. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun messageShown() { _message.value = null }

    /** Trail of the latest positions for the live preview (normalised 0..1). */
    private val _trail = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    val trail: StateFlow<List<Pair<Float, Float>>> = _trail

    private var pollJob: Job? = null
    private val debounceJobs = HashMap<String, Job>()

    // ── Phone sensors (optional steering) ───────────
    val sensors = SensorController(application)
    val sensorLive: StateFlow<SensorLive> = sensors.live
    val sensorSteering: StateFlow<Boolean> = sensors.steering
    private val _sensorConfig = MutableStateFlow(SensorConfig())
    val sensorConfig: StateFlow<SensorConfig> = _sensorConfig
    private var sensorSaveJob: Job? = null

    init {
        viewModelScope.launch {
            currentHost = settings.hostFlow.first()
            _hostState.value = currentHost
            currentUdpPort = settings.udpPortFlow.first()
        }
        viewModelScope.launch { settings.hostFlow.collect { currentHost = it; _hostState.value = it } }
        viewModelScope.launch { settings.udpPortFlow.collect { currentUdpPort = it; _udpPortState.value = it } }
        viewModelScope.launch { settings.flipXFlow.collect { _flipX.value = it } }
        viewModelScope.launch { settings.flipYFlow.collect { _flipY.value = it } }
        setupSensors()
        startPolling()
    }

    private fun setupSensors() {
        sensors.onSteer = { pan, tilt -> sendManualUdp(pan, tilt) }
        sensors.onBrightness = { b -> sendDebounced("sensLight", api.url("/set", "lBright" to b), 50) }
        sensors.onAction = { a ->
            when (a) {
                SensorAction.LASER_TOGGLE -> toggleLaser()
                SensorAction.NEXT_PATTERN -> playlistNext()
                SensorAction.NONE -> Unit
            }
        }
        sensors.rangeProvider = {
            val st = _status.value
            SteerRanges(
                panCenter = st?.panCenter ?: 90,
                panHalf = maxOf(abs(st?.panMinR ?: -40), st?.panMaxR ?: 40, 5),
                tiltCenter = st?.tiltCenter ?: 90,
                tiltHalf = maxOf(abs(st?.tiltMinR ?: -30), st?.tiltMaxR ?: 30, 5)
            )
        }
        viewModelScope.launch {
            val cfg = SensorConfig.fromJson(settings.sensorConfigFlow.first())
            _sensorConfig.value = cfg
            sensors.setConfig(cfg)
        }
    }

    /** Change settings: takes effect immediately, persisted shortly afterwards. */
    fun updateSensorConfig(change: (SensorConfig) -> SensorConfig) {
        val n = change(_sensorConfig.value)
        _sensorConfig.value = n
        sensors.setConfig(n)
        sensorSaveJob?.cancel()
        sensorSaveJob = viewModelScope.launch {
            delay(400)
            settings.setSensorConfig(n.toJson())
        }
    }

    fun resetSensorConfig() {
        updateSensorConfig { SensorConfig() }
        toast("Sensor settings reset")
    }

    fun setSensorSteering(on: Boolean) = sensors.setSteering(on)
    fun sensorPreview(on: Boolean) = sensors.setPreview(on)
    fun sensorForeground(on: Boolean) = sensors.setForeground(on)

    /** Store the phone's current pose as the zero point of this axis. */
    fun calibrateAxis(isPan: Boolean) {
        val z = sensors.zeroNow(isPan)
        if (z == null) {
            toast("No reading – choose a sensor for the axis and wait a moment")
            return
        }
        updateSensorConfig { c -> c.withAxis(isPan) { it.copy(savedZero = z) } }
        toast("Zero point set")
    }

    /** Light sensor: remember the current value as "dark" or "bright". */
    fun calibrateLight(dark: Boolean) {
        val lux = sensors.luxNow()
        if (lux == null) {
            toast("No light reading – enable the light sensor and wait a moment")
            return
        }
        updateSensorConfig { c ->
            if (dark) c.copy(lightDarkLux = lux.coerceAtMost(c.lightBrightLux - 1f).coerceAtLeast(0f))
            else c.copy(lightBrightLux = lux.coerceAtLeast(c.lightDarkLux + 1f))
        }
        toast(if (dark) "Dark value set" else "Bright value set")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                pollOnce()
                delay(500)
            }
        }
    }

    private suspend fun pollOnce() {
        try {
            val s = api.getStatus()
            _status.value = s
            _connected.value = true
            updateTrail(s)
        } catch (_: Exception) {
            _connected.value = false
        }
    }

    private fun updateTrail(s: CatLaserStatus) {
        val pI = s.panCenter + s.panMinR
        val pA = s.panCenter + s.panMaxR
        val tI = s.tiltCenter + s.tiltMinR
        val tA = s.tiltCenter + s.tiltMaxR
        val nx = (s.pan - pI).toFloat() / maxOf(1, pA - pI)
        val ny = (s.tilt - tI).toFloat() / maxOf(1, tA - tI)
        val list = _trail.value + (nx to ny)
        _trail.value = if (list.size > 90) list.takeLast(90) else list
    }

    fun refreshNow() { viewModelScope.launch { pollOnce() } }

    fun toast(msg: String) { _message.value = msg }

    // ── Connection / local settings ──────────────
    fun setHost(host: String) { viewModelScope.launch { settings.setHost(host) } }
    fun setUdpPort(port: Int) { viewModelScope.launch { settings.setUdpPort(port) } }
    fun setFlipX(v: Boolean) { viewModelScope.launch { settings.setFlipX(v) } }
    fun setFlipY(v: Boolean) { viewModelScope.launch { settings.setFlipY(v) } }

    // ── General send helpers ────────────────────────

    /** Send immediately; then refresh the status so the UI is up to date. */
    fun send(path: String, message: String? = null, refresh: Boolean = true) {
        viewModelScope.launch {
            val ok = runCatching { api.get(path) }.isSuccess
            if (message != null) _message.value = if (ok) message else "Sending failed"
            if (refresh) pollOnce()
        }
    }

    /** Debounced sending for sliders: only the last value within delayMs is sent. */
    fun sendDebounced(key: String, path: String, delayMs: Long = 150) {
        debounceJobs[key]?.cancel()
        debounceJobs[key] = viewModelScope.launch {
            delay(delayMs)
            runCatching { api.get(path) }
        }
    }

    /** Ends a slider drag: send the last value immediately. */
    fun sendNow(key: String, path: String) {
        debounceJobs[key]?.cancel()
        debounceJobs[key] = viewModelScope.launch {
            runCatching { api.get(path) }
            pollOnce()
        }
    }

    // ── Laser ──────────────────────────────────────────
    fun toggleLaser() {
        val cur = _status.value?.laserRequested ?: false
        send(api.url("/set", "laser" to !cur) + "&save=1")
    }
    fun setBrightnessDebounced(v: Int) = sendDebounced("lBright", api.url("/set", "lBright" to v))
    fun setBrightnessFinal(v: Int) = sendNow("lBright", api.url("/set", "lBright" to v) + "&save=1")
    fun setDimOnSprint(on: Boolean) = send(api.url("/set", "lDim" to on) + "&save=1")
    fun setSafetyGate(on: Boolean) = send(api.url("/set", "safeGate" to on) + "&save=1")

    // ── Energy ─────────────────────────────────────────
    fun setEnergyDebounced(v: Int) = sendDebounced("energy", api.url("/set", "energy" to v) + "&save=1")
    fun setEnergyFinal(v: Int) = sendNow("energy", api.url("/set", "energy" to v) + "&save=1")
    fun applyPreset(name: String) = send(api.url("/preset", "mode" to name), "Preset: $name")

    // ── Patterns ─────────────────────────────────────────
    fun setMode(mode: Int) = send(api.url("/mode", "m" to mode))
    fun playlistSetActive(on: Boolean) = send(api.url("/playlist", "active" to on))
    fun playlistSetShuffle(on: Boolean) = send(api.url("/playlist", "shuffle" to on) + "&save=1")
    fun playlistNext() = send("/playlist?next=1")
    fun playlistToggle(index: Int) = send(api.url("/playlist", "toggle" to index) + "&save=1")

    // ── Calibration ───────────────────────────────────
    fun calCommand(cmd: String) = send(api.url("/cal", "cmd" to cmd))
    fun liveMode(on: Boolean) = send(api.url("/live", "on" to on))

    /** Like updCal() in the web UI: set the value, then move to the centre so the result is visible. */
    fun setCalibrationValue(param: String, value: Int) {
        val key = "cal$param"
        debounceJobs[key]?.cancel()
        debounceJobs[key] = viewModelScope.launch {
            delay(150)
            runCatching {
                api.get(api.url("/set", param to value))
                api.get("/cal?cmd=center")
            }
        }
    }

    // ── Joystick (UDP, low latency) ────────────────
    fun sendManualUdp(pan: Int, tilt: Int) {
        val laserOn = _status.value?.laserRequested ?: false
        viewModelScope.launch { udp.send(currentHost, pan, tilt, laserOn) }
    }

    override fun onCleared() {
        super.onCleared()
        sensors.release()
        udp.close()
    }
}
