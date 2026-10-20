package com.catlaser.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.catlaser.app.CatLaserViewModel
import com.catlaser.app.network.CatLaserStatus
import com.catlaser.app.ui.components.*
import com.catlaser.app.ui.theme.*
import java.util.Locale

/** Main section "Patterns": playlist | single pattern | Lissajous generator. */
@Composable
fun PatternsScreen(vm: CatLaserViewModel, onOpenManual: () -> Unit) {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SubTabs(listOf("Playlist", "Single", "Lissajous"), sub) { sub = it }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (sub) {
                0 -> PlaylistTab(vm)
                1 -> SingleTab(vm, onOpenManual)
                else -> LissajousTab(vm)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlaylistTab(vm: CatLaserViewModel) {
    val s = vm.status.collectAsState().value ?: CatLaserStatus()

    SectionCard("Sequence") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleBtn(
                if (s.playlistActive) "Playlist ON" else "Playlist OFF", s.playlistActive,
                { vm.playlistSetActive(!s.playlistActive) }, Modifier.weight(1f), AccentPurple
            )
            ToggleBtn(
                if (s.playlistShuffle) "Shuffle" else "In order", s.playlistShuffle,
                { vm.playlistSetShuffle(!s.playlistShuffle) }, Modifier.weight(1f)
            )
            ActionBtn("Next", { vm.playlistNext() }, Modifier.weight(1f), AccentBlue)
        }
    }

    SectionCard("Active patterns") {
        val ids = CatLaserStatus.PATTERN_NAMES.indices.filter { it != CatLaserStatus.MANUAL_MODE }
        Grid(ids.size, 2) { n ->
            val i = ids[n]
            val on = s.playlistEnabled.getOrElse(i) { true }
            ToggleBtn(
                CatLaserStatus.PATTERN_NAMES[i], on,
                { vm.playlistToggle(i) }, Modifier.fillMaxWidth(), AccentPurple
            )
        }
        Gap(6)
        Hint("Only highlighted patterns run in the playlist.")
    }

    SectionCard("Timing") {
        ParamSlider(
            "Duration per pattern", s.patternDuration.toFloat(), 5f..180f, 5f,
            { "${it.toInt()} s" },
            onChange = { vm.sendDebounced("plDur", vm.api.url("/playlist", "dur" to it.toInt())) },
            onFinished = { vm.sendNow("plDur", vm.api.url("/playlist", "dur" to it.toInt())) }
        )
        ParamSlider(
            "Pause between patterns", s.patternPause.toFloat(), 0f..30f, 1f,
            { "${it.toInt()} s" },
            onChange = { vm.sendDebounced("plPause", vm.api.url("/playlist", "pause" to it.toInt())) },
            onFinished = { vm.sendNow("plPause", vm.api.url("/playlist", "pause" to it.toInt())) }
        )
    }
}

@Composable
private fun SingleTab(vm: CatLaserViewModel, onOpenManual: () -> Unit) {
    val s = vm.status.collectAsState().value ?: CatLaserStatus()
    SectionCard("Single pattern") {
        Grid(CatLaserStatus.PATTERN_NAMES.size, 2) { i ->
            val color = when (i) {
                CatLaserStatus.MANUAL_MODE -> AccentPurple
                CatLaserStatus.CAT_MODE -> AccentGreen
                else -> AccentOrange
            }
            ToggleBtn(
                CatLaserStatus.PATTERN_NAMES[i], s.mode == i,
                {
                    vm.setMode(i)
                    if (i == CatLaserStatus.MANUAL_MODE) onOpenManual()
                },
                Modifier.fillMaxWidth(), color
            )
        }
        Gap(6)
        Hint("Choosing a pattern disables the playlist. Manual opens the joystick.")
    }
}

@Composable
private fun LissajousTab(vm: CatLaserViewModel) {
    val s = vm.status.collectAsState().value ?: CatLaserStatus()
    val api = vm.api

    fun live(key: String, v: Any) = vm.sendDebounced("liss$key", api.url("/liss", key to v))
    fun done(key: String, v: Any) = vm.sendNow("liss$key", api.url("/liss", key to v))

    SectionCard("Presets") {
        Grid(6, 3) { i ->
            val names = listOf("Orbit" to "orbit", "Figure 8" to "eight", "Butterfly" to "butterfly",
                "Flower" to "flower", "Star" to "star")
            if (i < names.size) {
                ActionBtn(names[i].first, {
                    vm.send(api.url("/liss", "preset" to names[i].second) + "&save=1", names[i].first)
                }, Modifier.fillMaxWidth(), AccentBlue)
            } else {
                ToggleBtn(
                    if (s.lissDir >= 0) "Forward" else "Backward", s.lissDir < 0,
                    {
                        val d = if (s.lissDir >= 0) -1 else 1
                        vm.send(api.url("/liss", "lDir" to d) + "&save=1")
                    }, Modifier.fillMaxWidth(), AccentPurple
                )
            }
        }
        Gap(6)
        Hint("Select the \"Lissajous\" pattern under Single pattern to see the changes.")
    }

    SectionCard("Frequency & phase") {
        ParamSlider("Freq A", s.lissA.toFloat(), 1f..12f, 1f, { it.toInt().toString() },
            { live("lA", it.toInt()) }, onFinished = { done("lA", it.toInt()) })
        ParamSlider("Freq B", s.lissB.toFloat(), 1f..12f, 1f, { it.toInt().toString() },
            { live("lB", it.toInt()) }, onFinished = { done("lB", it.toInt()) })
        ParamSlider("Phase", s.lissPhase, 0f..6.29f, 0.01f, { f2(it) + " rad" },
            { live("lP", it) }, onFinished = { done("lP", it) })
    }

    SectionCard("Shape") {
        ParamSlider("Amplitude X", s.lissAmpX, 0.1f..1f, 0.05f, { f2(it) },
            { live("lAX", it) }, onFinished = { done("lAX", it) })
        ParamSlider("Amplitude Y", s.lissAmpY, 0.1f..1f, 0.05f, { f2(it) },
            { live("lAY", it) }, onFinished = { done("lAY", it) })
        ParamSlider("Rotation", s.lissRotation, 0f..3.15f, 0.05f, { f2(it) + " rad" },
            { live("lR", it) }, onFinished = { done("lR", it) })
    }

    SectionCard("Motion") {
        ParamSlider("Drift", s.lissDrift, 0f..0.5f, 0.005f, { String.format(Locale.US, "%.3f", it) },
            { live("lD", it) }, onFinished = { done("lD", it) })
        ParamSlider("Breathing", s.lissBreath, 0f..1f, 0.05f, { f2(it) },
            { live("lBr", it) }, onFinished = { done("lBr", it) })
        ParamSlider("Tempo", s.lissSpeed, 0.05f..3f, 0.05f, { f2(it) },
            { live("lS", it) }, onFinished = { done("lS", it) })
        Gap(6)
        ActionBtn(
            "Save Lissajous",
            { vm.send("/liss?save=1", "Lissajous saved") },
            Modifier.fillMaxWidth(), AccentGreen
        )
    }
}

internal fun f2(v: Float): String = String.format(Locale.US, "%.2f", v)
