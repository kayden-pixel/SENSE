package app.sense

import android.os.*
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sense.camera.*
import app.sense.core.*
import app.sense.data.*
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.*

@Composable
internal fun WhisperScreen(vm: SenseViewModel, c: Capabilities, permission: (String) -> Unit) {
    val sessions by vm.g.backend.sessions.collectAsStateWithLifecycle()
    val voice by vm.g.whisper.state.collectAsStateWithLifecycle()
    Page {
        Title(
            "Whisper",
            "Close, even apart.",
            "A temporary voice room for your circle. Microphone audio is shared only after you join.",
        )
        if (!c.microphonePermission)
            Setup(
                "Let them hear you",
                "Whisper needs microphone access for live voice.",
                "Allow microphone",
            ) {
                permission("microphone")
            }
        Action("Create a room", vm.g.backend.authenticated) { vm.create("voice") }
        sessions
            .filter { it.getString("kind") == "voice" }
            .forEach { s ->
                val id = s.getString("id")
                Section("Voice room", id.take(8) + " · expires in one hour") {
                    if (voice.sessionId == id && voice.phase != VoicePhase.IDLE) {
                        Text(
                            voice.phase.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = Accent,
                        )
                        Text(
                            (listOf("You") + voice.participants).joinToString(" · "),
                            color = Muted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.action { vm.g.whisper.mute() } }) {
                                Text(if (voice.muted) "Unmute" else "Mute")
                            }
                            OutlinedButton(
                                onClick = {
                                    vm.g.whisper.disconnect()
                                    vm.action { vm.g.backend.sessionAction(id, "leave") }
                                }
                            ) {
                                Text("Disconnect")
                            }
                        }
                        Text(
                            "Stereo positioning follows real Radar direction. Distance-only friends stay centered.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else
                        Action("Join room", c.microphonePermission) {
                            vm.action { vm.g.whisper.connect(id) }
                        }
                    FriendInvites(vm, id)
                    if (voice.sessionId == id && voice.phase == VoicePhase.CONNECTED)
                        WhisperSilentControls(vm, c, permission)
                }
            }
        if (!vm.g.backend.authenticated)
            Setup(
                "Your server connects the room",
                "Configure your SENSE backend and its LiveKit server.",
                "Set up account",
            ) {
                vm.navigate(Destination.YOU)
            }
        Text(
            "Leaving SENSE stops voice and releases the microphone.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun WhisperSilentControls(
    vm: SenseViewModel,
    c: Capabilities,
    permission: (String) -> Unit,
) {
    var enabled by remember { mutableStateOf(false) }
    val match by vm.g.silent.recognized.collectAsStateWithLifecycle()
    val commands by vm.g.silent.commands.collectAsStateWithLifecycle()
    Toggle("Silent room controls", enabled) { enabled = it }
    if (enabled) {
        if (!c.frontCamera) Text("Silent controls need a front camera.", color = Muted)
        else if (!c.canFaceCamera)
            TextButton(onClick = { permission("camera") }) {
                Text("Allow camera for local controls")
            }
        else CameraSurface(vm, CameraMode.SILENT, Modifier.fillMaxWidth().height(140.dp))
        if (commands.isEmpty()) Text("Train a command in Silent first", color = Muted)
    }
    SilentLifecycle(vm, enabled)
    LaunchedEffect(match) { match?.let { if (!it.command.confirm) vm.execute(it) } }
    if (enabled && match?.command?.confirm == true)
        AlertDialog(
            onDismissRequest = { vm.g.silent.clearRecognition() },
            title = { Text(match!!.command.name) },
            text = { Text("Similarity: " + (match!!.confidence * 100).toInt() + "%") },
            confirmButton = { TextButton(onClick = { vm.execute(match!!) }) { Text("Run") } },
            dismissButton = {
                TextButton(onClick = { vm.g.silent.clearRecognition() }) { Text("Cancel") }
            },
        )
}
