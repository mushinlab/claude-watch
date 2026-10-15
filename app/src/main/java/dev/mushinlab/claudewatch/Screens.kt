package dev.mushinlab.claudewatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.TimeText

private val ClaudeOrange = Color(0xFFD97757)

@Composable
fun ChatScreen(vm: ChatViewModel, onMic: () -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit) {
    val listState = rememberScalingLazyListState()
    var followBottom by remember { mutableStateOf(true) }
    val lastLength = vm.messages.lastOrNull()?.text?.length

    // Follow the streaming reply only while the user stays at the bottom; scrolling up stops following.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) followBottom = !listState.canScrollForward
    }
    LaunchedEffect(vm.messages.size) { followBottom = true }
    LaunchedEffect(vm.messages.size, lastLength, vm.error, vm.busy) {
        val count = listState.layoutInfo.totalItemsCount
        if (followBottom && count > 0) listState.scrollToItem(count - 1)
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "header") {
                Text(
                    text = "${Settings.modelLabel(vm.settings.model)} · ${vm.settings.effort}",
                    style = MaterialTheme.typography.caption2,
                    color = Color.Gray,
                )
            }
            if (vm.messages.isEmpty() && vm.error == null) {
                item(key = "hint") {
                    Text(
                        text = "Нажми на микрофон и спроси",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            vm.messages.forEach { msg ->
                // One item per paragraph: scaling a single tall text item on every scroll frame is expensive.
                val paragraphs = msg.text.split('\n').filter { it.isNotBlank() }.ifEmpty { listOf("…") }
                paragraphs.forEachIndexed { index, paragraph ->
                    item(key = "${msg.id}-$index") {
                        Text(
                            text = paragraph,
                            color = if (msg.fromUser) ClaudeOrange else Color.White,
                            textAlign = if (msg.fromUser) TextAlign.End else TextAlign.Start,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            vm.error?.let { err ->
                item(key = "error") {
                    Text(
                        text = err,
                        color = Color(0xFFFF6B6B),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption1,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            item(key = "controls") {
                if (vm.busy) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        vm.status?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.caption1) }
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp), indicatorColor = ClaudeOrange)
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Button(onClick = onMic, colors = ButtonDefaults.buttonColors(backgroundColor = ClaudeOrange)) {
                            Text("🎤")
                        }
                        Button(onClick = onNewChat, colors = ButtonDefaults.secondaryButtonColors()) {
                            Text("＋")
                        }
                        Button(onClick = onSettings, colors = ButtonDefaults.secondaryButtonColors()) {
                            Text("⚙")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(settings: Settings, onClose: () -> Unit) {
    var model by remember { mutableStateOf(settings.model) }
    var effort by remember { mutableStateOf(settings.effort) }
    var speak by remember { mutableStateOf(settings.speakReplies) }
    val key = settings.apiKey
    val listState = rememberScalingLazyListState()

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text("Настройки", style = MaterialTheme.typography.title3) }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Модель") },
                    secondaryLabel = { Text(Settings.modelLabel(model)) },
                    onClick = {
                        val ids = Settings.MODELS.map { it.id }
                        model = ids[(ids.indexOf(model) + 1) % ids.size]
                        settings.model = model
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Effort") },
                    secondaryLabel = { Text(effort) },
                    onClick = {
                        val all = Settings.EFFORTS
                        effort = all[(all.indexOf(effort) + 1) % all.size]
                        settings.effort = effort
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = speak,
                    onCheckedChange = { speak = it; settings.speakReplies = it },
                    label = { Text("Озвучивать ответ") },
                    toggleControl = {
                        androidx.wear.compose.material.Icon(
                            imageVector = ToggleChipDefaults.switchIcon(checked = speak),
                            contentDescription = null,
                        )
                    },
                )
            }
            item {
                Text(
                    text = if (key.isBlank()) "API-ключ не задан" else "Ключ: …${key.takeLast(4)}",
                    style = MaterialTheme.typography.caption2,
                    color = if (key.isBlank()) Color(0xFFFF6B6B) else Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp),
                )
            }
            item {
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(backgroundColor = ClaudeOrange)) {
                    Text("OK")
                }
            }
        }
    }
}
