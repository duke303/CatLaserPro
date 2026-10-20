package com.catlaser.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catlaser.app.CatLaserViewModel
import com.catlaser.app.network.CatLaserStatus
import com.catlaser.app.ui.components.*
import com.catlaser.app.ui.theme.*
import kotlin.math.abs

/** Main section "Control": laser, Cat Energy, live preview, status overview. */
@Composable
fun ControlScreen(vm: CatLaserViewModel, onOpenManual: () -> Unit) {
    val status by vm.status.collectAsState()
    val trail by vm.trail.collectAsState()
    val s = status ?: CatLaserStatus()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Laser ──────────────────────────────────────
        SectionCard("Laser") {
            Button(
                onClick = { vm.toggleLaser() },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (s.laserRequested) AccentRed else BorderDark,
                    contentColor = if (s.laserRequested) Color.White else TextLight
                )
            ) {
                Text(
                    if (s.laserRequested) "LASER ON" else "LASER OFF",
                    fontSize = 20.sp, fontWeight = FontWeight.ExtraBold
                )
            }
            if (s.laserRequested && !s.laserOn && s.gate.isNotEmpty()) {
                Gap(6)
                Hint("Blocked: ${s.gate}", color = AccentOrange)
            }
            Gap()
            ParamSlider(
                label = "Brightness",
                serverValue = s.laserBrightness.toFloat(),
                range = 10f..255f, step = 5f,
                format = { it.toInt().toString() },
                onChange = { vm.setBrightnessDebounced(it.toInt()) },
                onFinished = { vm.setBrightnessFinal(it.toInt()) }
            )
            ToggleBtn(
                text = if (s.laserDimOnSprint) "Dim during sprint ON" else "Dim during sprint OFF",
                active = s.laserDimOnSprint,
                onClick = { vm.setDimOnSprint(!s.laserDimOnSprint) },
                modifier = Modifier.fillMaxWidth(),
                activeColor = AccentBlue
            )
        }

        // ── Cat Energy ─────────────────────────────────
        SectionCard("Cat Energy") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LAZY", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("CRAZY", color = AccentRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            ParamSlider(
                label = if (s.energyAuto) "Energy" else "Energy (manual – Motion Engine active)",
                serverValue = s.energy.toFloat(),
                range = 0f..100f, step = 1f,
                format = { it.toInt().toString() },
                onChange = { vm.setEnergyDebounced(it.toInt()) },
                onFinished = { vm.setEnergyFinal(it.toInt()) }
            )
            Hint("Controls speed, acceleration, noise, jumps and pauses")
            Gap()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionBtn("Lazy", { vm.applyPreset("lazy") }, Modifier.weight(1f), AccentBlue)
                ActionBtn("Normal", { vm.applyPreset("normal") }, Modifier.weight(1f), AccentOrange)
                ActionBtn("Aggressive", { vm.applyPreset("aggressive") }, Modifier.weight(1f), AccentRed)
            }
        }

        // ── Live preview ──────────────────────────────
        SectionCard("Live preview") {
            Box(Modifier.clip(RoundedCornerShape(8.dp))) {
                LivePreview(
                    trail = trail,
                    laserOn = s.laserOn,
                    hotzoneEnabled = s.hotzoneEnabled,
                    hotzoneSize = s.hotzoneSize
                )
            }
            Gap(8)
            Row(Modifier.fillMaxWidth()) {
                Text("Pattern ", color = TextMuted, fontSize = 12.sp)
                Text(s.patternName(), color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val parts = buildList {
                    if (s.mode == CatLaserStatus.CAT_MODE) add(s.catStateName())
                    if (s.sprint) add("SPRINT")
                    if (s.morphing) add("Morph")
                }
                Text(parts.joinToString(" · "), color = AccentOrange, fontSize = 12.sp)
            }
            if (s.mode == CatLaserStatus.MANUAL_MODE) {
                Gap(8)
                ActionBtn("Open joystick", onOpenManual, Modifier.fillMaxWidth(), AccentPurple)
            }
        }

        // ── Playlist progress ───────────────────────
        SectionCard("Current") {
            val du = if (s.patternDuration > 0) s.patternDuration else 30
            val progress = if (s.inPatternPause) 1f
            else (1f - s.patternRemain.toFloat() / du).coerceIn(0f, 1f)
            if (s.inPatternPause) {
                Hint("Pause between patterns...", color = AccentOrange)
                Gap(4)
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = AccentPurple,
                trackColor = BorderDark
            )
            Gap(6)
            Row(Modifier.fillMaxWidth()) {
                Text(s.patternName(), color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (!s.playlistActive) "Playlist off"
                    else if (s.inPatternPause) "Pause" else "${s.patternRemain}s",
                    color = TextMuted, fontSize = 13.sp
                )
            }
            Gap(8)
            ActionBtn("Next pattern", { vm.playlistNext() }, Modifier.fillMaxWidth(), AccentPurple)
        }

        // ── Status line ────────────────────────────────
        SectionCard("Status") {
            InfoRow("Pan / Tilt", "${s.pan}° / ${s.tilt}°")
            InfoRow("Speed", "${abs(s.velPan)} / ${abs(s.velTilt)} °/s")
            InfoRow("Laser", if (s.laserOn) "ON" else "OFF")
            InfoRow("Energy", "${s.energy}" + if (s.energyAuto) "" else " (manual)")
        }
        Spacer(Modifier.height(8.dp))
    }
}
