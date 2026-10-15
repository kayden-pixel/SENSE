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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sense.camera.*
import app.sense.core.*
import app.sense.data.*
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.*

@Composable
internal fun SilentScreen(vm: SenseViewModel, c: Capabilities, permission: (String) -> Unit) {
    val commands by vm.g.silent.commands.collectAsStateWithLifecycle()
    val examples by vm.g.silent.examples.collectAsStateWithLifecycle()
    val recording by vm.g.silent.recording.collectAsStateWithLifecycle()
    val status by vm.g.silent.status.collectAsStateWithLifecycle()
    val match by vm.g.silent.recognized.collectAsStateWithLifecycle()
    var live by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("Radar") }
    var expanded by remember { mutableStateOf(false) }
    var threshold by remember { mutableFloatStateOf(.78f) }
    var confirm by remember { mutableStateOf(true) }
    SilentLifecycle(vm, live)
    LaunchedEffect(match) { match?.let { if (!it.command.confirm) vm.execute(it) } }
    Page {
        Title(
            "Silent",
            "Say it without sound.",
            "Train a few distinct mouth gestures. Recognition runs locally and works best facing the camera.",
        )
        if (!c.frontCamera)
            Text("This device has no front camera for mouth sensing.", color = Muted)
        if (c.frontCamera && !c.cameraPermission)
            Setup(
                "Local camera sensing",
                "SENSE stores encrypted mouth geometry, never video.",
                "Allow camera",
            ) {
                permission("camera")
            }
        if (c.canFaceCamera)
            CameraSurface(vm, CameraMode.SILENT, Modifier.fillMaxWidth().height(260.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live recognition", modifier = Modifier.weight(1f))
            Switch(live, { live = it }, enabled = commands.isNotEmpty() && c.canFaceCamera)
        }
        Text(status, color = Accent)
        if (!live)
            Section("Create a command") {
                OutlinedTextField(
                    name,
                    { name = it.take(32) },
                    label = { Text("Command name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text("Action: $action") }
                    DropdownMenu(expanded, { expanded = false }) {
                        listOf("Radar", "Whisper", "Camera", "Mute", "Yes", "No").forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a) },
                                onClick = {
                                    action = a
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "Record 3–6 examples, about two seconds each. Use a clearly different gesture for each command.",
                    color = Muted,
                )
                Action(
                    if (recording) "Recording…" else "Record example (" + examples.size + "/3)",
                    c.canFaceCamera && !recording && examples.size < 6,
                ) {
                    vm.action { vm.g.silent.example() }
                }
                Text("Match threshold · " + (threshold * 100).toInt() + "%", color = Muted)
                Slider(
                    threshold,
                    {
                        threshold = it
                        vm.g.silent.threshold = it.toDouble()
                    },
                    valueRange = 0.6f..0.95f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Confirm before action", modifier = Modifier.weight(1f))
                    Switch(
                        confirm,
                        {
                            confirm = it
                            vm.g.silent.confirmation = it
                        },
                    )
                }
                Action("Save command", examples.size >= 3 && name.isNotBlank()) {
                    vm.action {
                        vm.g.silent.save(name, action)
                        name = ""
                    }
                }
            }
        commands.forEach { command ->
            Section(
                command.name,
                command.action + " · " + command.samples.size + " local examples",
            ) {
                TextButton(
                    onClick = { vm.action { vm.g.store.records.delete("command", command.id) } }
                ) {
                    Text("Delete training")
                }
            }
        }
        if (match?.command?.confirm == true)
            AlertDialog(
                onDismissRequest = { vm.g.silent.clearRecognition() },
                title = { Text(match!!.command.name) },
                text = {
                    Text(
                        "Recognized with " +
                            (match!!.confidence * 100).toInt() +
                            "% similarity. Run this action?"
                    )
                },
                confirmButton = { TextButton(onClick = { vm.execute(match!!) }) { Text("Run") } },
                dismissButton = {
                    TextButton(onClick = { vm.g.silent.clearRecognition() }) { Text("Cancel") }
                },
            )
    }
}

@Composable
internal fun SilentLifecycle(vm: SenseViewModel, live: Boolean) {
    val owner = LocalLifecycleOwner.current
    val lease = remember { Any() }
    LaunchedEffect(owner, live) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.g.silent.listen(live, lease)
            try {
                awaitCancellation()
            } finally {
                vm.g.silent.stop(lease)
            }
        }
    }
}
