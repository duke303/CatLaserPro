package com.catlaser.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.catlaser.app.CatLaserViewModel
import com.catlaser.app.network.CatLaserStatus
import com.catlaser.app.ui.components.*
import com.catlaser.app.ui.theme.*
import java.util.Locale

/** Main section "Motion": Motion Engine | Noise & Hotzone | Mixer & Morph. */
@Composable
fun MotionScreen(vm: CatLaserViewModel) {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SubTabs(listOf("Motion Engine", "Noise & Hotzone", "Mixer & Morph"), sub) { sub = it }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (sub) {
                0 -> MotionEngineTab(vm)
                1 -> NoiseTab(vm)
                else -> MixerTab(vm)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MotionEngineTab(vm: CatLaserViewModel) {
    val s = vm.status.collectAsState().value ?: CatLaserStatus()
    val api = vm.api
    fun live(key: String, v: Any) = vm.sendDebounced("mo$key", api.url("/motion", key to v))
    fun done(key: String, v: Any) = vm.sendNow("mo$key", api.url("/motion", key to v))

    SectionCard("Speed & acceleration") {
        Hint(
            "Direct control over speed and acceleration. " +
                "Saving disables Cat Energy (automatic control).",
            color = AccentOrange
        )
        Gap()
        ParamSlider("Max. speed", s.maxVel.toFloat(), 20f..800f, 10f, { "${it.toInt()} °/s" },
            { live("mVel", it.toInt()) }, onFinished = { done("mVel", it.toInt()) })
        ParamSlider("Acceleration", s.maxAccel.toFloat(), 50f..4000f, 50f, { "${it.toInt()} °/s²" },
            { live("mAcc", it.toInt()) }, onFinished = { done("mAcc", it.toInt()) })
        ParamSlider("Braking", s.maxDecel.toFloat(), 50f..6000f, 50f, { "${it.toInt()} °/s²" },
            { live("mDec", it.toInt()) }, onFinished = { done("mDec", it.toInt()) })
        ParamSlider("Sprint speed", s.sprintVel.toFloat(), 50f..1200f, 25f, { "${it.toInt()} °/s" },
            { live("sVel", it.toInt()) }, onFinished = { done("sVel", it.toInt()) })
    }

    SectionCard("Fine tuning") {
        ParamSlider("Tilt speed (relative)", s.velRatioTilt, 0.2f..2f, 0.05f, { f2(it) },
            { live("vrT", it) }, onFinished = { done("vrT", it) })
        ParamSlider("Softness (damping)", s.damping, 0.3f..2.5f, 0.05f, { f2(it) },
            { live("damp", it) }, onFinished = { done("damp", it) })
        Hint("1.00 = default · lower = slight overshoot (livelier) · higher = tighter / more direct")
        Gap()
        ActionBtn(
            "Save motion",
            { vm.send("/motion?save=1", "Motion saved") },
            Modifier.fillMaxWidth(), AccentGreen
        )
    }
}

@Composable
private fun NoiseTab(vm: CatLaserViewModel) {
    val s = vm.status.collectAsState().value ?: CatLaserStatus()
    val api = vm.api
    fun live(key: String, v: Any) = vm.sendDebounced("nz$key", api.url("/set", key to v))
    fun done(key: String, v: Any) = vm.sendNow("nz$key", api.url("/set", key to v))

    SectionCard("Organic Noise") {
        ParamSlider("Noise amount", s.noise, 0f..0.5f, 0.005f, { String.format(Locale.US, "%.3f", it) },
            { live("noise", it) }, onFinished = { done("noise", it) })
        Hint("Smooth, interpolated noise — makes mathematical paths look alive")
    }

    SectionCard("Hotzone") {
        ToggleBtn(
            if (s.hotzoneEnabled) "Hotzone ON" else "Hotzone OFF", s.hotzoneEnabled,
            { vm.send(api.url("/set", "hzEn" to !s.hotzoneEnabled)) },
            Modifier.fillMaxWidth(), AccentBlue
        )
        Gap()
        ParamSlider("Centre share", s.hotzoneWeight.toFloat(), 0f..100f, 5f, { "${it.toInt()} %" },
            { live("hzW", it.toInt()) }, onFinished = { done("hzW", it.toInt()) })
        ParamSlider("Zone size", s.hotzoneSize, 0.1f..0.9f, 0.05f, { f2(it) },
            { live("hzS", it) }, onFinished = { done("hzS", it) })
        Hint("The hotzone is a weighted area in the middle where the laser prefers to stay.")
        Gap()
        ActionBtn(
            "Save",
            { vm.send("/set?save=1", "Saved") },
            Modifier.fillMaxWidth(), AccentGreen
        )
    }
}

@Composable
private fun MixerTab(vm: CatLaserViewModel) {
    val s = vm.status.collectAsState().value ?: CatLaserStatus()
    val api = vm.api

    SectionCard("Transitions (Morph)") {
        ToggleBtn(
            if (s.morphEnabled) "Morph ON" else "Morph OFF", s.morphEnabled,
            { vm.send(api.url("/set", "mrpEn" to !s.morphEnabled)) },
            Modifier.fillMaxWidth(), AccentPurple
        )
        Gap()
        ParamSlider("Morph duration", s.morphDuration, 0.2f..10f, 0.1f, { String.format(Locale.US, "%.1f s", it) },
            { vm.sendDebounced("mrpD", api.url("/set", "mrpD" to it)) },
            onFinished = { vm.sendNow("mrpD", api.url("/set", "mrpD" to it)) })
        Hint("When the pattern changes, the old path is smoothly blended into the new one.")
    }

    SectionCard("Mixer") {
        ToggleBtn(
            if (s.mixEnabled) "Mixer ON" else "Mixer OFF", s.mixEnabled,
            { vm.send(api.url("/set", "mixEn" to !s.mixEnabled)) },
            Modifier.fillMaxWidth(), AccentPurple
        )
        Gap()
        Text("Mixed-in pattern", color = TextMuted, fontSize = 12.sp)
        Gap(6)
        Grid(CatLaserStatus.MIX_PATTERNS.size, 3) { n ->
            val p = CatLaserStatus.MIX_PATTERNS[n]
            ToggleBtn(
                CatLaserStatus.PATTERN_NAMES[p], s.mixPattern == p,
                { vm.send(api.url("/set", "mixP" to p)) },
                Modifier.fillMaxWidth(), AccentOrange
            )
        }
        Gap()
        ParamSlider("Mix amount", s.mixAmount, 0f..1f, 0.05f, { f2(it) },
            { vm.sendDebounced("mixA", api.url("/set", "mixA" to it)) },
            onFinished = { vm.sendNow("mixA", api.url("/set", "mixA" to it)) })
        Hint("Blends a second pattern into the active one, e.g. Circle + a little Lissajous.")
        Gap()
        ActionBtn(
            "Save",
            { vm.send("/set?save=1", "Saved") },
            Modifier.fillMaxWidth(), AccentGreen
        )
    }
}
