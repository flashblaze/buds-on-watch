package dev.flashblaze.heymelodywatch

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconToggleButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextToggleButton
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.flashblaze.heymelodywatch.protocol.AncLevel
import dev.flashblaze.heymelodywatch.protocol.BudPart
import dev.flashblaze.heymelodywatch.protocol.NoiseMode
import dev.flashblaze.heymelodywatch.protocol.NoiseState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppScaffold { BudsNavHost() } } }
    }
}

@Composable
fun BudsNavHost() {
    val context = LocalContext.current
    val buds = remember { Buds.get(context) }
    // Hold the link only while the app is on screen.
    LifecycleStartEffect(buds) {
        buds.acquire()
        onStopOrDispose { buds.release() }
    }
    val nav = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(navController = nav, startDestination = "control") {
        composable("control") { ControlScreen(buds, onChooseBuds = { nav.navigate("pick") }) }
        composable("pick") {
            PickScreen(buds, onPicked = { address ->
                buds.choose(address)
                nav.popBackStack()
            })
        }
    }
}

@Composable
private fun ListScreen(content: TransformingLazyColumnScope.() -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun ControlScreen(buds: Buds, onChooseBuds: () -> Unit) {
    val state by buds.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) buds.reconnect()
    }
    val name = state.deviceName ?: stringResource(R.string.buds)
    val set: (NoiseState) -> Unit = { target ->
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch { buds.setMode(target) }
    }

    // Buzz once the buds confirm a change, or refuse it.
    var wasPending by remember { mutableStateOf(false) }
    LaunchedEffect(state.pending, state.notice) {
        if (wasPending && state.pending == null) {
            haptics.performHapticFeedback(if (state.notice == null) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        }
        wasPending = state.pending != null
    }

    ListScreen {
        item { Header(name, state.link) }

        when (state.link) {
            Link.NO_PERMISSION -> {
                item { Message(stringResource(R.string.no_permission)) }
                item {
                    Button(
                        onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.allow)) }
                }
            }
            Link.BLUETOOTH_OFF -> {
                item { Message(stringResource(R.string.bluetooth_off)) }
                item {
                    Button(
                        onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.open_settings)) }
                }
            }
            Link.NOT_PAIRED -> {
                item { Message(stringResource(R.string.not_paired)) }
                item { SecondaryAction(stringResource(R.string.choose_buds), onChooseBuds) }
            }
            Link.FAILED, Link.UNSUPPORTED -> {
                val text = if (state.link == Link.FAILED) R.string.failed else R.string.unsupported
                item { Message(stringResource(text, name)) }
                item {
                    Button(onClick = buds::reconnect, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.retry))
                    }
                }
                item { SecondaryAction(stringResource(R.string.choose_buds), onChooseBuds) }
            }
            // While connecting, show the last known readings, dimmed and not tappable.
            Link.IDLE, Link.CONNECTING, Link.CONNECTED -> {
                controls(state, enabled = state.link == Link.CONNECTED && state.pending == null, set = set)
                item { SecondaryAction(stringResource(R.string.choose_buds), onChooseBuds) }
            }
        }
    }
}

@Composable
private fun Header(name: String, link: Link) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val status = when (link) {
            Link.CONNECTED -> stringResource(R.string.connected)
            Link.IDLE, Link.CONNECTING -> stringResource(R.string.connecting)
            else -> null
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (link == Link.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun TransformingLazyColumnScope.controls(
    state: BudsState,
    enabled: Boolean,
    set: (NoiseState) -> Unit,
) {
    val reported = state.noise

    if (state.battery.isNotEmpty()) item { BatteryRow(state.battery) }

    item {
        ButtonGroup(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            for ((mode, icon) in MODE_ICONS) {
                IconToggleButton(
                    checked = reported?.mode == mode,
                    // Tapping the active mode again re-sends it; harmless and also re-reads the state.
                    onCheckedChange = { set(NoiseState(mode)) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(painterResource(icon), contentDescription = stringResource(mode.labelRes()))
                }
            }
        }
    }

    item {
        val pending = state.pending
        val line = when {
            pending != null -> stringResource(R.string.switching)
            else -> reported.label(LocalContext.current)
        }
        Text(
            line,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
    }

    state.notice?.let { notice ->
        item { Message(stringResource(notice.labelRes()), MaterialTheme.colorScheme.error) }
    }

    if (state.levels.isEmpty()) return
    // Always listed, so a level is one tap from any mode: picking one turns noise cancelling on at it.
    item { ListHeader { Text(stringResource(R.string.anc_level)) } }
    for (row in state.levels.chunked(2)) {
        item {
            ButtonGroup(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                for (level in row) {
                    TextToggleButton(
                        checked = reported?.level == level,
                        onCheckedChange = { set(NoiseState(NoiseMode.ANC, level)) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Text(stringResource(level.labelRes()), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    if (reported?.level == AncLevel.SMART && state.smartPick != null) {
        item {
            Message(
                stringResource(R.string.smart_picked, stringResource(state.smartPick.labelRes())),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val MODE_ICONS = listOf(
    NoiseMode.OFF to R.drawable.ic_mode_off,
    NoiseMode.ANC to R.drawable.ic_mode_anc,
    NoiseMode.TRANSPARENCY to R.drawable.ic_mode_transparency,
)

@Composable
private fun BatteryRow(battery: Map<BudPart, BatteryReading>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        for (part in BudPart.entries) BatteryGauge(part, battery[part])
    }
}

/** A ring per part. Dimmed when the reading is not from the current link (case lid shut, disconnected). */
@Composable
private fun BatteryGauge(part: BudPart, reading: BatteryReading?) {
    val short = stringResource(
        when (part) {
            BudPart.LEFT -> R.string.battery_left
            BudPart.RIGHT -> R.string.battery_right
            BudPart.CASE -> R.string.battery_case
        },
    )
    val long = stringResource(
        when (part) {
            BudPart.LEFT -> R.string.battery_left_long
            BudPart.RIGHT -> R.string.battery_right_long
            BudPart.CASE -> R.string.battery_case_long
        },
    )
    val level = reading?.battery?.level
    val charging = reading?.battery?.charging == true
    val description = when {
        level == null -> long
        charging -> stringResource(R.string.battery_charging_description, long, level)
        else -> stringResource(R.string.battery_description, long, level)
    }
    val low = level != null && level <= LOW_BATTERY && !charging
    val ringColor = when {
        low -> MaterialTheme.colorScheme.error
        charging -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .alpha(if (reading?.live == true) 1f else 0.45f)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { (level ?: 0) / 100f },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 4.dp,
                colors = ProgressIndicatorDefaults.colors(indicatorColor = ringColor),
            )
            Text(
                if (level == null) stringResource(R.string.battery_unknown) else stringResource(R.string.battery_percent, level),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (charging) {
                Icon(
                    painterResource(R.drawable.ic_bolt),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(short, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val LOW_BATTERY = 15

@Composable
private fun PickScreen(buds: Buds, onPicked: (String) -> Unit) {
    val paired = remember { buds.pairedHeadphones() }
    ListScreen {
        item { ListHeader { Text(stringResource(R.string.paired_headphones)) } }
        if (paired.isEmpty()) item { Message(stringResource(R.string.no_headphones)) }
        for (device in paired) {
            item {
                Button(onClick = { onPicked(device.address) }, modifier = Modifier.fillMaxWidth()) {
                    Text(device.name)
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        CompactButton(onClick = onClick, colors = ButtonDefaults.filledTonalButtonColors()) { Text(label) }
    }
}
