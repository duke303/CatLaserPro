package com.catlaser.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catlaser.app.ui.components.Badge
import com.catlaser.app.ui.screens.ControlScreen
import com.catlaser.app.ui.screens.ManualScreen
import com.catlaser.app.ui.screens.MotionScreen
import com.catlaser.app.ui.screens.PatternsScreen
import com.catlaser.app.ui.screens.SetupScreen
import com.catlaser.app.ui.theme.*

class MainActivity : ComponentActivity() {

    private val viewModel: CatLaserViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.sensorForeground(true)
    }

    override fun onPause() {
        // Sensor steering stops automatically as soon as the app is no longer in the foreground.
        viewModel.sensorForeground(false)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CatLaserTheme {
                Surface(color = BgDark) {
                    CatLaserApp(viewModel)
                }
            }
        }
    }
}

/** Main menu (bottom bar). Each section may have sub-menus of its own. */
private enum class MainTab(val label: String, val icon: ImageVector) {
    CONTROL("Control", Icons.Filled.PlayArrow),
    PATTERNS("Patterns", Icons.Filled.Apps),
    MOTION("Motion", Icons.Filled.Speed),
    MANUAL("Manual", Icons.Filled.TouchApp),
    SETUP("Setup", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatLaserApp(vm: CatLaserViewModel) {
    var current by remember { mutableStateOf(MainTab.CONTROL) }

    val status by vm.status.collectAsState()
    val connected by vm.connected.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.messageShown()
        }
    }

    Scaffold(
        containerColor = BgDark,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(CardDark)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Cat Laser PRO 2", color = AccentOrange, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text(current.label, color = TextMuted, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Badge(
                        if (connected) "Connected" else "Offline",
                        if (connected) AccentGreen else AccentRed
                    )
                    status?.let {
                        Badge(
                            if (it.wifiConnected) "Home WiFi" else "AP",
                            if (it.wifiConnected) AccentGreen else AccentOrange
                        )
                        if (it.gate.isNotEmpty()) Badge(it.gate, AccentRed)
                        if (it.remoteActive) Badge("Remote", AccentOrange)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = CardDark) {
                MainTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = current == t,
                        onClick = { current = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(BgDark)) {
            when (current) {
                MainTab.CONTROL -> ControlScreen(vm, onOpenManual = { current = MainTab.MANUAL })
                MainTab.PATTERNS -> PatternsScreen(vm, onOpenManual = { current = MainTab.MANUAL })
                MainTab.MOTION -> MotionScreen(vm)
                MainTab.MANUAL -> ManualScreen(vm)
                MainTab.SETUP -> SetupScreen(vm)
            }
        }
    }
}
