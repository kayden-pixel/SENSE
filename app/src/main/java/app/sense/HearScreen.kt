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
internal fun HearScreen(vm: SenseViewModel, c: Capabilities, permission: (String) -> Unit) {
    val state by vm.g.hear.state.collectAsStateWithLifecycle()
    var gain by remember { mutableFloatStateOf(.35f) }
    var focus by remember { mutableStateOf(false) }
    Page {
        Title(
            "Hear",
            "Tune into here.",
            "Listen to your surroundings through headphones, with gentle gain and clipping protection.",
        )
        if (!c.microphonePermission)
            Setup(
                "Ambient audio stays local",
                "The microphone feeds your headphones. No audio is saved or sent.",
                "Allow microphone",
            ) {
                permission("microphone")
            }
        if (!c.headset)
            Section(
                "Connect headphones",
                "Monitoring needs headphones to avoid speaker feedback.",
            ) {}
        Section(if (state.active) "Listening" else "Ready when you are", c.route) {
            LinearProgressIndicator(
                progress = { state.level },
                modifier = Modifier.fillMaxWidth(),
                color = Accent,
                trackColor = Ink,
            )
            Text("Monitor gain · " + (gain * 100).toInt() + "%", color = Muted)
            Slider(
                gain,
                {
                    gain = it
                    vm.g.hear.gain = it
                },
                valueRange = 0f..1f,
            )
            Toggle("Speech focus filter", focus) {
                focus = it
                vm.g.hear.focus = it
            }
            Action(
                if (state.active) "Stop listening" else "Start listening",
                state.active || c.canHear,
            ) {
                if (state.active) vm.g.hear.stop()
                else {
                    vm.g.whisper.disconnect()
                    vm.action { vm.g.hear.start() }
                }
            }
            if (state.error != null) Text(state.error!!, color = Accent)
        }
        Text(
            "Start with low headphone volume. Bluetooth adds delay; wired or USB headphones usually work better. Noise reduction depends on device support.",
            color = Muted,
            lineHeight = 22.sp,
        )
        Text(
            "Target-speaker separation is in Hear Labs.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
