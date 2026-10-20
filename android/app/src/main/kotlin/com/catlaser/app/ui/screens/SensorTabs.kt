package com.catlaser.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catlaser.app.CatLaserViewModel
import com.catlaser.app.network.CatLaserStatus
import com.catlaser.app.sensors.*
import com.catlaser.app.ui.components.*
import com.catlaser.app.ui.theme.*
import java.util.Locale

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun f1(v: Float) = String.format(Locale.US, "%.1f", v)

// ═════════════════════════════════════════════════════════
//  Sensor control (usage)
// ═════════════════════════════════════════════════════════
@Composable
fun SensorControlTab(vm: CatLaserViewModel) {
    val cfg by vm.sensorConfig.collectAsState()
    val live by vm.sensorLive.collectAsState()
    val steering by vm.sensorSteering.collectAsState()
    val s = vm.status.collectAsState().value ?: CatLaserStatus()
    val context = LocalContext.current
    val view = LocalView.current

    // Register sensors, keep the screen on and lock rotation while this view is open.
    DisposableEffect(Unit) {
        val act = context.findActivity()
        val oldOrientation = act?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        view.keepScreenOn = true
        vm.sensorPreview(true)
        onDispose {
            vm.setSensorSteering(false)
            vm.sensorPreview(false)
            view.keepScreenOn = false
            act?.requestedOrientation = oldOrientation
        }
    }

    val panOk = cfg.pan.source?.let { vm.sensors.channelAvailable(it) } == true
    val tiltOk = cfg.tilt.source?.let { vm.sensors.channelAvailable(it) } == true
    val usable = panOk || tiltOk

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard("Steer with your phone") {
            if (!usable) {
                Hint(
                    "No axis is assigned to an available sensor. Under \"Sensor setup\" " +
                        "choose a sensor for pan and/or tilt.",
                    color = AccentOrange
                )
                Gap()
            }
            val padColor = if (steering) AccentGreen else BorderDark
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (usable) padColor else BorderDark.copy(alpha = 0.4f))
                    .pointerInput(cfg.holdToSteer, usable) {
                        if (!usable) return@pointerInput
                        if (cfg.holdToSteer) {
                            detectTapGestures(onPress = {
                                vm.setSensorSteering(true)
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    vm.setSensorSteering(false)
                                }
                            })
                        } else {
                            detectTapGestures(onTap = {
                                vm.setSensorSteering(!vm.sensorSteering.value)
                            })
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        steering -> "STEERING ACTIVE"
                        cfg.holdToSteer -> "HOLD PRESSED\nto steer"
                        else -> "TAP\nto start"
                    },
                    textAlign = TextAlign.Center,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (steering) Color.Black else TextLight
                )
            }
            Gap(8)
            Hint(
                (if (cfg.autoZero) "The pose at start counts as the centre (auto zero). " else
                    "The stored zero point is used. ") +
                    "Releasing or leaving the app stops steering; after 5 s without a signal " +
                    "the device goes back to its playlist."
            )
        }

        SectionCard("Live") {
            Row(Modifier.fillMaxWidth()) {
                Text("Pan", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(live.panDeg?.let { "$it°" } ?: "--", color = AccentOrange, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { ((live.panNorm + 1f) / 2f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = AccentOrange, trackColor = BorderDark
            )
            Gap(8)
            Row(Modifier.fillMaxWidth()) {
                Text("Tilt", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(live.tiltDeg?.let { "$it°" } ?: "--", color = AccentBlue, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { ((live.tiltNorm + 1f) / 2f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = AccentBlue, trackColor = BorderDark
            )
            Gap(10)
            InfoRow("Pan source", cfg.pan.source?.label ?: "Off")
            InfoRow("Tilt source", cfg.tilt.source?.label ?: "Off")
        }

        SectionCard("Laser") {
            Button(
                onClick = { vm.toggleLaser() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (s.laserRequested) AccentRed else BorderDark,
                    contentColor = if (s.laserRequested) Color.White else TextLight
                )
            ) {
                Text(if (s.laserRequested) "LASER ON" else "LASER OFF", fontWeight = FontWeight.ExtraBold)
            }
            Gap(6)
            val extras = buildList {
                if (cfg.lightEnabled) add("Light → brightness")
                if (cfg.proximityEnabled) add("Proximity → ${cfg.proximityAction.label}")
                if (cfg.shakeEnabled) add("Shake → ${cfg.shakeAction.label}")
            }
            if (extras.isNotEmpty()) Hint("Active extras: " + extras.joinToString(" · "))
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ═════════════════════════════════════════════════════════
//  Sensor setup (detection, assignment, calibration)
// ═════════════════════════════════════════════════════════
@Composable
fun SensorSetupTab(vm: CatLaserViewModel) {
    val cfg by vm.sensorConfig.collectAsState()
    val live by vm.sensorLive.collectAsState()

    DisposableEffect(Unit) {
        vm.sensorPreview(true)
        onDispose { vm.sensorPreview(false) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Detected sensors ──────────────────────────
        SectionCard("Detected sensors") {
            vm.sensors.infos.forEach { info ->
                val value = when (info.kind) {
                    SensorKind.LIGHT -> live.lux?.let { "${f1(it)} lx" }
                    SensorKind.PROXIMITY -> live.near?.let { if (it) "near" else "far" }
                    SensorKind.ACCEL -> "Shake strength ${f1(live.shake)}"
                    else -> null
                }
                InfoRow(
                    info.kind.label,
                    if (info.present) "✓ available" + (if (value != null) " · $value" else "")
                    else "✗ not available"
                )
                if (info.present && info.name.isNotBlank()) Hint(info.name)
            }
            Gap(6)
            Hint(
                "Only available sensors can be selected below. A gyroscope is not needed for " +
                    "tilt steering – the accelerometer is enough."
            )
        }

        // ── General ──────────────────────────────────
        SectionCard("Pose & behaviour") {
            Text("How do you hold the phone while steering?", color = TextMuted, fontSize = 12.sp)
            Gap(6)
            Grid(Pose.values().size, 2) { i ->
                val p = Pose.values()[i]
                ToggleBtn(p.label, cfg.pose == p, { vm.updateSensorConfig { it.copy(pose = p) } },
                    Modifier.fillMaxWidth())
            }
            Gap(10)
            SwitchRow(
                "Steer only while the button is held",
                "Safer: releasing stops immediately (like button A on the M5Stick). Off = tap to start/stop.",
                cfg.holdToSteer
            ) { on -> vm.updateSensorConfig { it.copy(holdToSteer = on) } }
            Gap(8)
            SwitchRow(
                "Auto zero on start",
                "The pose at start counts as the centre. Off = use the stored zero point of the axis.",
                cfg.autoZero
            ) { on -> vm.updateSensorConfig { it.copy(autoZero = on) } }
        }

        // ── Axes ─────────────────────────────────────
        AxisCard("Pan (left / right)", true, vm, cfg, live)
        AxisCard("Tilt (up / down)", false, vm, cfg, live)

        // ── Additional sensors ─────────────────────────────
        SectionCard("Light sensor → laser brightness") {
            val present = vm.sensors.has(SensorKind.LIGHT)
            SwitchRow(
                "Active", if (present) "Ambient light at the phone controls the laser brightness." else "Not available.",
                cfg.lightEnabled && present
            ) { on -> if (present) vm.updateSensorConfig { it.copy(lightEnabled = on) } }
            if (present) {
                Gap(8)
                InfoRow("Current value", live.lux?.let { "${f1(it)} lx" } ?: "--")
                InfoRow("Calibrated: dark / bright", "${f1(cfg.lightDarkLux)} / ${f1(cfg.lightBrightLux)} lx")
                Gap(6)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionBtn("Now = dark", { vm.calibrateLight(true) }, Modifier.weight(1f), AccentBlue)
                    ActionBtn("Now = bright", { vm.calibrateLight(false) }, Modifier.weight(1f), AccentOrange)
                }
                Gap(6)
                SwitchRow("Invert", "Dark = bright, bright = dark", cfg.lightInvert) { on ->
                    vm.updateSensorConfig { it.copy(lightInvert = on) }
                }
                Hint("To calibrate: cover the sensor → \"Now = dark\", then hold it into the light → \"Now = bright\".")
            }
        }

        SectionCard("Proximity sensor → action") {
            val present = vm.sensors.has(SensorKind.PROXIMITY)
            SwitchRow(
                "Active",
                if (present) "Holding a hand in front of the sensor (top of the display) triggers the action." else "Not available.",
                cfg.proximityEnabled && present
            ) { on -> if (present) vm.updateSensorConfig { it.copy(proximityEnabled = on) } }
            if (present) {
                Gap(8)
                InfoRow("State", live.near?.let { if (it) "near" else "far" } ?: "--")
                Gap(6)
                ActionChoice(cfg.proximityAction) { a -> vm.updateSensorConfig { it.copy(proximityAction = a) } }
            }
        }

        SectionCard("Shake → action") {
            val present = vm.sensors.has(SensorKind.ACCEL)
            SwitchRow(
                "Active",
                if (present) "Shaking the phone firmly triggers the action (1.2 s lockout afterwards)." else "Not available.",
                cfg.shakeEnabled && present
            ) { on -> if (present) vm.updateSensorConfig { it.copy(shakeEnabled = on) } }
            if (present) {
                Gap(8)
                ParamSlider(
                    "Threshold (higher = shake harder)", cfg.shakeThreshold, 8f..40f, 1f,
                    { "${it.toInt()} m/s²" },
                    onChange = { v -> vm.updateSensorConfig { it.copy(shakeThreshold = v) } }
                )
                InfoRow("Current strength", f1(live.shake) + " m/s²")
                Gap(6)
                ActionChoice(cfg.shakeAction) { a -> vm.updateSensorConfig { it.copy(shakeAction = a) } }
            }
        }

        SectionCard("Reset") {
            ActionBtn("Reset all sensor settings", { vm.resetSensorConfig() },
                Modifier.fillMaxWidth(), AccentRed)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActionChoice(current: SensorAction, onSelect: (SensorAction) -> Unit) {
    Text("Action", color = TextMuted, fontSize = 12.sp)
    Gap(6)
    Grid(SensorAction.values().size, 3) { i ->
        val a = SensorAction.values()[i]
        ToggleBtn(a.label, current == a, { onSelect(a) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun AxisCard(
    title: String,
    isPan: Boolean,
    vm: CatLaserViewModel,
    cfg: SensorConfig,
    live: SensorLive
) {
    val ax = cfg.axis(isPan)
    val options: List<Channel?> = buildList<Channel?> {
        add(null)
        for (ch in Channel.values()) if (vm.sensors.channelAvailable(ch)) add(ch)
    }

    SectionCard("Axis · $title") {
        Text("Sensor / measurement", color = TextMuted, fontSize = 12.sp)
        Gap(6)
        Grid(options.size, 2) { i ->
            val ch = options[i]
            ToggleBtn(
                ch?.label ?: "Off (hold centre)", ax.source == ch,
                { vm.updateSensorConfig { c -> c.withAxis(isPan) { it.copy(source = ch) } } },
                Modifier.fillMaxWidth()
            )
        }
        if (ax.source != null && !vm.sensors.channelAvailable(ax.source)) {
            Gap(6)
            Hint("The selected sensor is not available on this phone.", color = AccentRed)
        }

        if (ax.source != null) {
            Gap(10)
            val rawNow = live.raw[ax.source]
            InfoRow("Current reading", rawNow?.let { "${f1(it)}°" } ?: "--")
            InfoRow("Stored zero point", "${f1(ax.savedZero)}°")
            Gap(6)
            ActionBtn("Set zero point now (current pose = centre)", { vm.calibrateAxis(isPan) },
                Modifier.fillMaxWidth(), AccentGreen)
            Gap(10)
            ParamSlider(
                "Full deflection at", ax.range, 5f..90f, 1f, { "±${it.toInt()}°" },
                onChange = { v -> vm.updateSensorConfig { c -> c.withAxis(isPan) { it.copy(range = v) } } }
            )
            ParamSlider(
                "Dead zone", ax.deadzone, 0f..15f, 0.5f, { "${f1(it)}°" },
                onChange = { v -> vm.updateSensorConfig { c -> c.withAxis(isPan) { it.copy(deadzone = v) } } }
            )
            ParamSlider(
                "Smoothing", ax.smoothing, 0f..0.9f, 0.05f, { "${(it * 100).toInt()} %" },
                onChange = { v -> vm.updateSensorConfig { c -> c.withAxis(isPan) { it.copy(smoothing = v) } } }
            )
            SwitchRow("Invert direction", "If the laser moves the opposite way.", ax.invert) { on ->
                vm.updateSensorConfig { c -> c.withAxis(isPan) { it.copy(invert = on) } }
            }
        }
    }
}
