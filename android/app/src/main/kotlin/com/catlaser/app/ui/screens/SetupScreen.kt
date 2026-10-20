package com.catlaser.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.catlaser.app.CatLaserViewModel
import com.catlaser.app.network.CatLaserStatus
import com.catlaser.app.service.QuickControlService
import com.catlaser.app.ui.components.*
import com.catlaser.app.ui.theme.*

/** Main section "Setup": servo & calibration | time & safety | connection. */
@Composable
fun SetupScreen(vm: CatLaserViewModel) {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SubTabs(listOf("Servo", "Time & safety", "Connection"), sub) { sub = it }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (sub) {
                0 -> ServoTab(vm)
                1 -> TimeSafetyTab(vm)
                else -> ConnectionTab(vm)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ═════════════════════════════════════════════════════════
//  Servo range + calibration
// ═════════════════════════════════════════════════════════
@Composable
private fun ServoTab(vm: CatLaserViewModel) {
    val s = vm.status.collectAsState().value ?: CatLaserStatus()
    val api = vm.api
    fun live(key: String, v: Int) = vm.sendDebounced("rng$key", api.url("/set", key to v))
    fun done(key: String, v: Int) = vm.sendNow("rng$key", api.url("/set", key to v))
    fun deg(v: Float) = "${v.toInt()}°"

    SectionCard("Servo range · Pan") {
        ParamSlider("Min", s.panMinR.toFloat(), -90f..0f, 1f, ::deg,
            { live("panMinR", it.toInt()) }, onFinished = { done("panMinR", it.toInt()) })
        ParamSlider("Max", s.panMaxR.toFloat(), 0f..90f, 1f, ::deg,
            { live("panMaxR", it.toInt()) }, onFinished = { done("panMaxR", it.toInt()) })
    }
    SectionCard("Servo range · Tilt") {
        ParamSlider("Min", s.tiltMinR.toFloat(), -90f..0f, 1f, ::deg,
            { live("tiltMinR", it.toInt()) }, onFinished = { done("tiltMinR", it.toInt()) })
        ParamSlider("Max", s.tiltMaxR.toFloat(), 0f..90f, 1f, ::deg,
            { live("tiltMaxR", it.toInt()) }, onFinished = { done("tiltMaxR", it.toInt()) })
    }

    SectionCard("Calibration") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionBtn("Live ON", { vm.liveMode(true) }, Modifier.weight(1f), AccentBlue)
            ToggleBtn("Live OFF", false, { vm.liveMode(false) }, Modifier.weight(1f))
            ActionBtn("To centre", { vm.calCommand("center") }, Modifier.weight(1f), AccentOrange)
        }
        if (s.calibrationMode) {
            Gap(6)
            Hint("Calibration mode active — the laser is locked while the safety gate is on.",
                color = AccentOrange)
        }
        Gap()
        ParamSlider("Pan Center", s.panCenter.toFloat(), 30f..150f, 1f, ::deg,
            { vm.setCalibrationValue("panC", it.toInt()) })
        ParamSlider("Tilt Center", s.tiltCenter.toFloat(), 30f..150f, 1f, ::deg,
            { vm.setCalibrationValue("tiltC", it.toInt()) })
        Gap(4)
        Text("Move to limits", color = TextMuted, fontSize = 12.sp)
        Gap(6)
        Grid(4, 2) { i ->
            val items = listOf("Pan MIN" to "panMin", "Pan MAX" to "panMax",
                "Tilt MIN" to "tiltMin", "Tilt MAX" to "tiltMax")
            ToggleBtn(items[i].first, false, { vm.calCommand(items[i].second) }, Modifier.fillMaxWidth())
        }
        Gap()
        ActionBtn(
            "Save calibration",
            { vm.send("/set?save=1", "Calibration saved") },
            Modifier.fillMaxWidth(), AccentGreen
        )
    }
}

// ═════════════════════════════════════════════════════════
//  Time control + safety
// ═════════════════════════════════════════════════════════
@Composable
private fun TimeSafetyTab(vm: CatLaserViewModel) {
    val s = vm.status.collectAsState().value ?: CatLaserStatus()
    val api = vm.api
    fun live(key: String, v: Int) = vm.sendDebounced("t$key", api.url("/set", key to v))
    fun done(key: String, v: Int) = vm.sendNow("t$key", api.url("/set", key to v))

    SectionCard("Safety gate") {
        SwitchRow(
            "Safety gate",
            "Covers the NTP time window, session break, pattern pause and calibration lock.",
            s.safeGate
        ) { vm.setSafetyGate(it) }
        if (!s.safeGate) {
            Gap(6)
            Hint(
                "Gate OFF: none of these locks apply — the laser only follows the ON/OFF switch.",
                color = AccentRed
            )
        }
        if (s.gate.isNotEmpty()) {
            Gap(6)
            Hint("Currently blocked by: ${s.gate}", color = AccentOrange)
        }
    }

    SectionCard("Time of day (NTP)") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ToggleBtn(
                if (s.ntpEnabled) "NTP ON" else "NTP OFF", s.ntpEnabled,
                { vm.send(api.url("/set", "ntpEn" to !s.ntpEnabled)) },
                Modifier.weight(1f), AccentBlue
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (s.ntpSynced) "synced" else "not synced",
                color = if (s.ntpSynced) AccentGreen else TextMuted, fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
        }
        Gap()
        ParamSlider("From", s.ntpStart.toFloat(), 0f..23f, 1f, { "${it.toInt()}:00" },
            { live("ntpS", it.toInt()) }, onFinished = { done("ntpS", it.toInt()) })
        ParamSlider("To", s.ntpEnd.toFloat(), 0f..23f, 1f, { "${it.toInt()}:00" },
            { live("ntpE", it.toInt()) }, onFinished = { done("ntpE", it.toInt()) })
        Hint("Laser only allowed inside this time window (time zone CET/CEST).")
    }

    SectionCard("Session timer") {
        ParamSlider("Play time", s.sessionDuration.toFloat(), 0f..120f, 5f,
            { if (it.toInt() == 0) "off" else "${it.toInt()} min" },
            { live("sessD", it.toInt()) }, onFinished = { done("sessD", it.toInt()) })
        ParamSlider("Break", s.sessionBreak.toFloat(), 1f..60f, 1f, { "${it.toInt()} min" },
            { live("sessB", it.toInt()) }, onFinished = { done("sessB", it.toInt()) })
        Hint("0 min = session timer off" + if (s.sessionBlocked) " · session break in progress" else "")
        Gap()
        ActionBtn(
            "Save time control",
            { vm.send("/set?save=1", "Time control saved") },
            Modifier.fillMaxWidth(), AccentGreen
        )
    }
}

// ═════════════════════════════════════════════════════════
//  Connection, background service, device/OTA
// ═════════════════════════════════════════════════════════
@Composable
private fun ConnectionTab(vm: CatLaserViewModel) {
    val context = LocalContext.current
    val s = vm.status.collectAsState().value
    val connected by vm.connected.collectAsState()
    val host by vm.hostState.collectAsState()
    val udpPort by vm.udpPortState.collectAsState()

    var hostInput by remember(host) { mutableStateOf(host) }
    var portInput by remember(udpPort) { mutableStateOf(udpPort.toString()) }
    var serviceRunning by remember { mutableStateOf(false) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Start the service either way (without permission only the notification stays invisible).
        startQuickService(context)
        serviceRunning = true
    }

    SectionCard("Connection") {
        OutlinedTextField(
            value = hostInput, onValueChange = { hostInput = it },
            label = { Text("Host or IP address") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Gap()
        OutlinedTextField(
            value = portInput, onValueChange = { portInput = it.filter(Char::isDigit) },
            label = { Text("UDP port (joystick)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Gap()
        ActionBtn(
            "Save & connect",
            {
                vm.setHost(hostInput.trim())
                portInput.toIntOrNull()?.let { vm.setUdpPort(it) }
                vm.toast("Connection saved")
                vm.refreshNow()
            },
            Modifier.fillMaxWidth(), AccentGreen
        )
        Gap(6)
        Hint(
            "\"catlaser.local\" does not work on every Android device (mDNS). If the " +
                "connection fails, enter the IP address instead — it is printed on the serial monitor " +
                "when the ESP32 starts, or listed in your router's device list."
        )
    }

    SectionCard("Background service") {
        SwitchRow(
            "Quick-access notification",
            "Laser ON/OFF and Next straight from the notification — even when the app is closed.",
            serviceRunning
        ) { checked ->
            if (checked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startQuickService(context)
                    serviceRunning = true
                }
            } else {
                context.stopService(Intent(context, QuickControlService::class.java))
                serviceRunning = false
            }
        }
        Gap(6)
        Hint("A home-screen widget is also available (laser ON/OFF, next pattern).")
    }

    SectionCard("Device") {
        InfoRow("Connection", if (connected) "connected" else "no connection")
        InfoRow("Host", host)
        if (s != null) {
            InfoRow("WiFi", if (s.wifiConnected) "Home network" else "Access point")
            InfoRow("Remote (UDP)", if (s.remoteActive) "active" else "inactive")
            InfoRow("Energy control", if (s.energyAuto) "automatic" else "manual")
            InfoRow("NTP", if (s.ntpSynced) "synced" else "not synced")
        }
    }

    SectionCard("OTA update") {
        InfoRow("Hostname", "catlaser")
        InfoRow("Password", "set in secrets.h")
        Hint("Arduino IDE → Port → catlaser (network port). Firmware update over WiFi.")
    }
}

private fun startQuickService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, QuickControlService::class.java))
}
