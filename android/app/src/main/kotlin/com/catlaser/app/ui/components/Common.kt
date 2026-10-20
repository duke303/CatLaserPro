package com.catlaser.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catlaser.app.ui.theme.*
import kotlin.math.roundToInt

/** Card with a title -- corresponds to the ".card" blocks of the web UI. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardDark)
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(
            title.uppercase(),
            color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = TextMuted) {
    Text(text, color = color, fontSize = 11.sp, lineHeight = 15.sp, modifier = modifier)
}

@Composable
fun Gap(h: Int = 10) { Spacer(Modifier.height(h.dp)) }

/** Toggle button: active = filled with colour, inactive = dark. */
@Composable
fun ToggleBtn(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = AccentOrange
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) activeColor else BorderDark,
            contentColor = if (active) Color.Black else TextLight
        )
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Plain action (not toggleable). */
@Composable
fun ActionBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AccentBlue
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black)
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Grid with a fixed number of columns (without the experimental FlowRow API). */
@Composable
fun Grid(count: Int, columns: Int, cell: @Composable (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var start = 0
        while (start < count) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until columns) {
                    val i = start + c
                    Box(Modifier.weight(1f)) { if (i < count) cell(i) }
                }
            }
            start += columns
        }
    }
}

/**
 * Slider with a label and value display. Holds the value locally while
 * the finger is on the slider (otherwise the status poll would make it
 * jump back), and afterwards takes over the value from the device again.
 * Values are snapped to [step] manually (without Material tick marks).
 */
@Composable
fun ParamSlider(
    label: String,
    serverValue: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onFinished: (Float) -> Unit = {}
) {
    var local by remember { mutableStateOf(serverValue) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(serverValue) { if (!dragging) local = serverValue }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(format(local), color = AccentOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = local.coerceIn(range.start, range.endInclusive),
            onValueChange = {
                dragging = true
                val snapped = ((it / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
                if (snapped != local) {
                    local = snapped
                    onChange(snapped)
                }
            },
            onValueChangeFinished = {
                dragging = false
                onFinished(local)
            },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AccentOrange,
                activeTrackColor = AccentOrange,
                inactiveTrackColor = BorderDark
            )
        )
    }
}

/** Small status badge ("Connected", "Home WiFi" ...). */
@Composable
fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Row with text on the left and a switch on the right. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = TextLight)
            if (subtitle != null) Hint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Sub-menu inside a main section (tab bar). */
@Composable
fun SubTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    ScrollableTabRow(
        selectedTabIndex = selected,
        containerColor = BgDark,
        contentColor = AccentOrange,
        edgePadding = 8.dp
    ) {
        labels.forEachIndexed { i, label ->
            Tab(
                selected = selected == i,
                onClick = { onSelect(i) },
                text = { Text(label, fontSize = 13.sp, maxLines = 1) },
                selectedContentColor = AccentOrange,
                unselectedContentColor = TextMuted
            )
        }
    }
}
