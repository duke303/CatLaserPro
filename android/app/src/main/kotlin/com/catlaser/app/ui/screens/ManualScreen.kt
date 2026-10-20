package com.catlaser.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catlaser.app.CatLaserViewModel
import com.catlaser.app.network.CatLaserStatus
import com.catlaser.app.ui.components.*
import com.catlaser.app.ui.theme.*
import kotlin.math.abs

/** Main section "Manual": touch joystick | sensor control | sensor setup. */
@Composable
fun ManualScreen(vm: CatLaserViewModel) {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SubTabs(listOf("Joystick", "Sensor control", "Sensor setup"), sub) { sub = it }
        when (sub) {
            0 -> JoystickTab(vm)
            1 -> SensorControlTab(vm)
            else -> SensorSetupTab(vm)
        }
    }
}

/** Touch pad, sends over UDP in real time. */
@Composable
private fun JoystickTab(vm: CatLaserViewModel) {
    val s = vm.status.collectAsState().value ?: CatLaserStatus()
    val flipX by vm.flipX.collectAsState()
    val flipY by vm.flipY.collectAsState()

    var dot by remember { mutableStateOf<Offset?>(null) }
    var lastSentAt by remember { mutableLongStateOf(0L) }
    var lastPanTilt by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Current values for the gesture lambda (which is only started once)
    val cur by rememberUpdatedState(s)
    val curFlipX by rememberUpdatedState(flipX)
    val curFlipY by rememberUpdatedState(flipY)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard("Axes") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleBtn(
                    if (flipX) "X mirrored" else "Mirror X", flipX,
                    { vm.setFlipX(!flipX) }, Modifier.weight(1f)
                )
                ToggleBtn(
                    if (flipY) "Y mirrored" else "Mirror Y", flipY,
                    { vm.setFlipY(!flipY) }, Modifier.weight(1f)
                )
            }
        }

        SectionCard("Joystick") {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.4f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            ) {
                val wPx = constraints.maxWidth.toFloat()
                val hPx = constraints.maxHeight.toFloat()

                fun handle(pos: Offset) {
                    if (wPx <= 0f || hPx <= 0f) return
                    val p = Offset(pos.x.coerceIn(0f, wPx), pos.y.coerceIn(0f, hPx))
                    dot = p
                    var nx = p.x / wPx
                    var ny = p.y / hPx
                    if (curFlipX) nx = 1f - nx
                    if (curFlipY) ny = 1f - ny
                    val c = cur
                    val halfPan = maxOf(abs(c.panMinR), c.panMaxR, 5)
                    val halfTilt = maxOf(abs(c.tiltMinR), c.tiltMaxR, 5)
                    val pan = Math.round(c.panCenter + (nx - 0.5f) * 2f * halfPan).coerceIn(0, 180)
                    val tilt = Math.round(c.tiltCenter + (ny - 0.5f) * 2f * halfTilt).coerceIn(0, 180)
                    lastPanTilt = pan to tilt
                    val now = System.currentTimeMillis()
                    if (now - lastSentAt >= 25) {
                        lastSentAt = now
                        vm.sendManualUdp(pan, tilt)
                    }
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(wPx, hPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                handle(down.position)
                                drag(down.id) { change ->
                                    change.consume()
                                    handle(change.position)
                                }
                            }
                        }
                ) {
                    drawRect(Color(0xFF0A0A10))
                    val grid = Color.White.copy(alpha = 0.06f)
                    for (i in 1..3) {
                        drawLine(grid, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height), 1f)
                        drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1f)
                    }
                    val cross = AccentRed.copy(alpha = 0.25f)
                    drawLine(cross, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1f)
                    drawLine(cross, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                    drawCircle(AccentRed.copy(alpha = 0.4f), 5f, Offset(size.width / 2, size.height / 2))
                    dot?.let { d ->
                        drawLine(
                            AccentOrange.copy(alpha = 0.4f),
                            Offset(size.width / 2, size.height / 2), d,
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                        )
                        drawCircle(AccentRed, 11f, d)
                    }
                }
            }
            Gap(8)
            val pt = lastPanTilt
            Text(
                if (pt != null) "Pan ${pt.first}°   Tilt ${pt.second}°" else "Pan --   Tilt --",
                color = TextMuted, fontSize = 12.sp
            )
            Gap(4)
            Hint(
                "Drag your finger — the laser follows in real time over UDP. The device switches " +
                    "to manual mode automatically and returns to the playlist after 5 s without input."
            )
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
            if (s.mode != CatLaserStatus.MANUAL_MODE) {
                Gap(8)
                ActionBtn(
                    "Enable manual mode",
                    { vm.setMode(CatLaserStatus.MANUAL_MODE) },
                    Modifier.fillMaxWidth(), AccentPurple
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
