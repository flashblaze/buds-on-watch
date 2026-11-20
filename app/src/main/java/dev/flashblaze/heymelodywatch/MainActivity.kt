package dev.flashblaze.heymelodywatch

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.flashblaze.heymelodywatch.protocol.AncLevel
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
        TransformingLazyColumn(state = listState, contentPadding = padding, content = content)
    }
}

@Composable
private fun ControlScreen(buds: Buds, onChooseBuds: () -> Unit) {
    val state by buds.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) buds.reconnect()
    }
    val name = state.deviceName ?: stringResource(R.string.buds)
    val connected = state.link == Link.CONNECTED
    val set: (NoiseState) -> Unit = { target -> scope.launch { buds.setMode(target) } }

    ListScreen {
        item { ListHeader { Text(name) } }

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
            Link.IDLE, Link.CONNECTING -> {
                item { CircularProgressIndicator() }
                item { Message(stringResource(R.string.connecting)) }
                state.noise?.let { item { Message(stringResource(R.string.last_known, it.label(context))) } }
            }
            Link.CONNECTED -> {
                state.notice?.let { item { Message(stringResource(it.labelRes()), MaterialTheme.colorScheme.error) } }
                modeItems(state, connected, set)
                item { SecondaryAction(stringResource(R.string.choose_buds), onChooseBuds) }
            }
        }
    }
}

private fun TransformingLazyColumnScope.modeItems(
    state: BudsState,
    enabled: Boolean,
    set: (NoiseState) -> Unit,
) {
    val reported = state.noise
    val idle = enabled && state.pending == null

    for (mode in listOf(NoiseMode.ANC, NoiseMode.TRANSPARENCY, NoiseMode.OFF)) {
        item {
            RadioButton(
                selected = reported?.mode == mode,
                onSelect = { set(NoiseState(mode)) },
                enabled = idle,
                modifier = Modifier.fillMaxWidth(),
                secondaryLabel = if (state.pending?.mode == mode && state.pending.level == null) {
                    { Text(stringResource(R.string.switching)) }
                } else {
                    null
                },
            ) { Text(stringResource(mode.labelRes())) }
        }
    }

    // Always listed, so a level is one tap from any mode: picking one turns noise cancelling on at it.
    if (state.levels.isEmpty()) return
    item { ListHeader { Text(stringResource(R.string.anc_level)) } }
    for (level in state.levels) {
        item {
            val secondary: String? = when {
                state.pending?.level == level -> stringResource(R.string.switching)
                level == AncLevel.SMART && reported?.level == AncLevel.SMART && state.smartPick != null ->
                    stringResource(R.string.smart_picked, stringResource(state.smartPick.labelRes()))
                else -> null
            }
            RadioButton(
                selected = reported?.level == level,
                onSelect = { set(NoiseState(NoiseMode.ANC, level)) },
                enabled = idle,
                modifier = Modifier.fillMaxWidth(),
                secondaryLabel = secondary?.let { { Text(it) } },
            ) { Text(stringResource(level.labelRes())) }
        }
    }
}

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
private fun Message(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
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
    CompactButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}
